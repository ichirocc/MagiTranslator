package nie.translator.rtranslator.jane;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Writes the Java validator's verdict for every (seed source, candidate) pair of one direction, so
 * tools/eval/check_validator_parity.py can check that the Python port (metrics.validate) agrees.
 * Candidates: seed references plus every stored output TSV. Output lines:
 *   JN|NJ \t source \t candidate \t issues      and      NEG \t ja|ne \t text \t true|false
 * Usage: ValidatorParityDump testset_seed.tsv outputs1.tsv ... > dump.tsv
 */
public final class ValidatorParityDump {
    public static void main(String[] args) throws Exception {
        List<String> jaSrc = new ArrayList<>(), neSrc = new ArrayList<>();
        Set<String> jaC = new LinkedHashSet<>(), neC = new LinkedHashSet<>();
        List<String> ids = new ArrayList<>(), dirs = new ArrayList<>();
        for (String l : Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8)) {
            if (l.startsWith("#")) continue;
            String[] c = l.split("\t");
            if (c.length < 5) continue;
            ids.add(c[0]); dirs.add(c[1]);
            if (c[1].startsWith("ja")) { jaSrc.add(c[3]); neC.add(c[4]); } else { neSrc.add(c[3]); jaC.add(c[4]); }
        }
        for (int i = 1; i < args.length; i++) {
            for (String l : Files.readAllLines(Paths.get(args[i]), StandardCharsets.UTF_8)) {
                String[] c = l.split("\t", 2);
                if (c.length < 2) continue;
                int k = ids.indexOf(c[0]);
                if (k < 0) continue;
                (dirs.get(k).startsWith("ja") ? neC : jaC).add(c[1]);
            }
        }
        StringBuilder out = new StringBuilder();
        for (String s : jaSrc) for (String c : neC) out.append("JN\t").append(s).append('\t').append(c).append('\t').append(TranslationValidator.validateJaToNe(s, c, null)).append('\n');
        for (String s : neSrc) for (String c : jaC) out.append("NJ\t").append(s).append('\t').append(c).append('\t').append(TranslationValidator.validateNeToJa(s, c)).append('\n');
        for (String s : jaSrc) out.append("NEG\tja\t").append(s).append('\t').append(TranslationValidator.jaHasNegation(s)).append('\n');
        for (String s : jaC) out.append("NEG\tja\t").append(s).append('\t').append(TranslationValidator.jaHasNegation(s)).append('\n');
        for (String s : neSrc) out.append("NEG\tne\t").append(s).append('\t').append(TranslationValidator.neHasNegation(s)).append('\n');
        for (String s : neC) out.append("NEG\tne\t").append(s).append('\t').append(TranslationValidator.neHasNegation(s)).append('\n');
        System.out.write(out.toString().getBytes(StandardCharsets.UTF_8));
        System.out.flush();
    }
}
