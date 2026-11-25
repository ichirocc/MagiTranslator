import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import nie.translator.rtranslator.jane.Backend;
import nie.translator.rtranslator.jane.Glossary;
import nie.translator.rtranslator.jane.JaNeTranslator;
import nie.translator.rtranslator.jane.PhraseBook;
import nie.translator.rtranslator.jane.UserDictionary;

/**
 * Runs the app's ja<->ne library (jane) on a test set on a PC. The neural backend is the Python port of
 * the app's Madlad call (onnx_runner.py --serve), so the only difference from the device is the runtime.
 * Each row gets a fresh JaNeTranslator (no TranslationMemory carried between test sentences).
 *
 *   javac -encoding UTF-8 -d out app/src/main/java/nie/translator/rtranslator/jane/*.java tools/eval/JaneEval.java
 *   java -cp out JaneEval --models DIR --in tools/eval/testset_seed.tsv --out jane.tsv [--beam 4] [--nbest 3]
 */
public class JaneEval {
    static final class Py implements Closeable {
        final Process p; final BufferedWriter w; final BufferedReader r;
        Py(String models) throws IOException {
            String dir = Paths.get(JaneEval.class.getProtectionDomain().getCodeSource().getLocation().getPath()).toString();
            String runner = System.getProperty("runner", "tools/eval/onnx_runner.py");
            p = new ProcessBuilder("python3", runner, "--models", models, "--serve").redirectError(ProcessBuilder.Redirect.INHERIT).start();
            w = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
            r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        }
        synchronized List<String> call(String tgt, int beam, int n, String text) throws IOException {
            w.write(tgt + "\t" + beam + "\t" + n + "\t" + esc(text) + "\n");
            w.flush();
            String line = r.readLine();
            if (line == null) throw new IOException("runner exited");
            String[] parts = line.split("\t", -1);
            int k = Integer.parseInt(parts[0]);
            List<String> out = new ArrayList<>();
            for (int i = 1; i <= k; i++) out.add(unesc(parts[i]));
            return out;
        }
        public void close() { p.destroy(); }
    }

    static String esc(String s) { return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t"); }
    static String unesc(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                b.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
            } else b.append(c);
        }
        return b.toString();
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> a = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) a.put(args[i], args[i + 1]);
        int beam = Integer.parseInt(a.getOrDefault("--beam", "4"));   // the app forces beam >= 4 for ja<->ne
        int nbest = Integer.parseInt(a.getOrDefault("--nbest", "3"));
        String only = a.get("--only");   // e.g. ja-ne
        try (Py py = new Py(a.get("--models"));
             BufferedWriter out = Files.newBufferedWriter(Paths.get(a.get("--out")), StandardCharsets.UTF_8)) {
            Backend backend = new Backend() {
                public String translate(String text, String src, String tgt) throws Exception {
                    List<String> h = py.call(tgt, beam, 1, text);
                    return h.isEmpty() ? "" : h.get(0);
                }
                public List<String> translateNBest(String text, String src, String tgt, int n) throws Exception {
                    return py.call(tgt, beam, Math.min(n, nbest), text);
                }
                public List<String> translateNBestWide(String text, String src, String tgt, int n, int b) throws Exception {
                    return py.call(tgt, Math.max(beam, b), n, text);
                }
            };
            for (String line : Files.readAllLines(Paths.get(a.get("--in")), StandardCharsets.UTF_8)) {
                if (line.startsWith("#") || line.startsWith("id\t") || line.trim().isEmpty()) continue;
                String[] c = line.split("\t", -1);
                String id = c[0], dir = c[1], src = c[3];
                if (only != null && !only.equals(dir)) continue;
                String[] d = dir.split("-");
                long t0 = System.currentTimeMillis();
                Glossary g = Glossary.withDefaults();
                JaNeTranslator tr = new JaNeTranslator(backend, g);
                tr.setDiversify(!a.containsKey("--no-diversify"));
                if (a.containsKey("--userdict")) {   // directory of #!jane files (e.g. dictionaries/)
                    PhraseBook pb = new PhraseBook();
                    UserDictionary.loadDirectory(new java.io.File(a.get("--userdict")), g, pb);
                    tr.setPhraseBook(pb);
                }
                JaNeTranslator.Result r = tr.translate(src, d[0], d[1]);
                String o = r.text == null ? "" : r.text.replace("\t", " ").replace("\n", " ");
                out.write(id + "\t" + o + "\n");
                out.flush();
                System.err.println(id + " " + (System.currentTimeMillis() - t0) + "ms cands=" + r.candidates.size() + " issues=" + r.issues + " -> " + o);
            }
        }
    }
}
