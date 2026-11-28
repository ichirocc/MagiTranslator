import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import nie.translator.rtranslator.jane.*;

/**
 * Builds the app's PLaMo prompt (with the dictionary glossary, no history) for lang<TAB>text lines, as the
 * third column plamo_cli reads: lang<TAB>text<TAB>prompt. The phrasebook is not used, so a sentence that is
 * in it is translated by the model as an unseen one would be.
 *   java -cp <jane classes>:tools/llama PromptDump dictionaries < in.tsv > prompts.tsv
 */
public class PromptDump {
    public static void main(String[] a) throws Exception {
        Glossary g = Glossary.withDefaults();
        load(new File(a[0], "ja_ne_terms.tsv"), g, false);
        load(new File(a[0], "ne_ja_terms.tsv"), g, true);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintStream out = new PrintStream(System.out, true, "UTF-8");
        for (String line; (line = in.readLine()) != null; ) {
            String[] p = line.split("\t");
            if (p.length < 2) continue;
            String text, prompt;
            if (p[0].equals("ja")) {
                text = JaNormalizer.normalize(p[1]);
                prompt = PlamoPrompt.build("ja", "ne", text, null, g.termsJaNe(text, null, 24), false);
            } else {
                text = Normalizer.normalize(p[1], Normalizer.Form.NFC).replace('|', '।').trim();
                prompt = PlamoPrompt.build("ne", "ja", text, null, g.termsNeJa(text, 24), true);
            }
            out.println(p[0] + "\t" + p[1] + "\t" + prompt);
        }
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
