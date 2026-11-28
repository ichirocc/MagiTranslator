import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import nie.translator.rtranslator.jane.*;

/**
 * Builds the app's PLaMo prompt for lang<TAB>text lines, as the third column plamo_cli reads:
 * lang<TAB>text<TAB>prompt. Glossary as the app builds it, and (unless -noexamples) the phrase-book examples
 * PlamoTranslator adds, with every input sentence removed from the phrase book first (leave-one-out), so a
 * sentence that is in it is translated as an unseen one would be. No exact phrase-book match, no history.
 *   java -cp <jane classes>:tools/llama PromptDump dictionaries [-noexamples] < in.tsv > prompts.tsv
 */
public class PromptDump {
    public static void main(String[] a) throws Exception {
        boolean ex = !(a.length > 1 && a[1].equals("-noexamples"));
        Glossary g = Glossary.withDefaults();
        load(new File(a[0], "ja_ne_terms.tsv"), g, false);
        load(new File(a[0], "ne_ja_terms.tsv"), g, true);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintStream out = new PrintStream(System.out, true, "UTF-8");
        List<String[]> lines = new ArrayList<>();
        Set<String> inputs = new HashSet<>();
        for (String line; (line = in.readLine()) != null; ) {
            String[] p = line.split("\t");
            if (p.length < 2) continue;
            lines.add(p);
            inputs.add(p[1].trim());
        }
        PhraseBook book = new PhraseBook(), fullBook = new PhraseBook();
        for (String l : java.nio.file.Files.readAllLines(new File(a[0], "phrases.tsv").toPath(), StandardCharsets.UTF_8)) {
            if (l.startsWith("#") || l.isEmpty()) continue;
            String[] p = l.split("\t");
            if (p.length < 2 || inputs.contains(p[0].trim()) || inputs.contains(p[1].trim())) continue;
            String dir = p.length > 2 ? p[2].trim() : "";
            fullBook.add(p[0], p[1], dir.equals("ja>ne"), dir.equals("ne>ja"));
            book.add(p[0], p[1], dir.equals("ja>ne"), dir.equals("ne>ja"));
        }
        for (String[] p : lines) {
            String text, prompt;
            if (p[0].equals("ja")) {
                text = JaIdioms.rewrite(JaNormalizer.modernize(JaAnswer.declines(JaNormalizer.normalize(p[1]))));
                prompt = PlamoPrompt.build("ja", "ne", text, examples(book, ex, "ja", text), common(fullBook, g.termsJaNe(text, null, 24)), false);
            } else {
                text = Normalizer.normalize(p[1], Normalizer.Form.NFC).replace('|', '।').trim();
                prompt = PlamoPrompt.build("ne", "ja", text, examples(book, ex, "ne", text), g.termsNeJa(text, 24), true);
            }
            out.println(p[0] + "\t" + p[1] + "\t" + prompt);
        }
    }

    // as PlamoTranslator.examples: up to 2 at least 0.4 similar, the closest one last
    private static List<PlamoPrompt.Turn> examples(PhraseBook b, boolean on, String src, String text) {
        List<PlamoPrompt.Turn> out = new ArrayList<>();
        if (!on) return out;
        for (String[] e : b.examples().similar(text, src, Integer.getInteger("k", 3), Double.parseDouble(System.getProperty("sim", "0.3")))) out.add(0, new PlamoPrompt.Turn(e[0], e[1]));
        return out;
    }

    // as PlamoTranslator.commonDropped
    private static List<Glossary.Term> common(PhraseBook b, List<Glossary.Term> terms) {
        if (System.getProperty("keepCommon") != null) return terms;
        List<Glossary.Term> out = new ArrayList<>();
        for (Glossary.Term t : terms) if (b.jaShare(t.source) <= 0.005) out.add(t);
        return out;
    }

    private static void load(File f, Glossary g, boolean reverse) throws IOException {
        for (String l : java.nio.file.Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
            if (l.startsWith("#") || l.isEmpty()) continue;
            String[] p = l.split("\t");
            if (p.length < 2) continue;
            if (reverse) g.putUserReverse(p[0], p[1]); else g.putUser(p[0], p[1]);
        }
    }
}
