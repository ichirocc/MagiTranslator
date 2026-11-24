package nie.translator.rtranslator.jane;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Japanese <-> Nepali direct translation (no English pivot), accuracy over speed.
 *
 * ja -> ne pipeline:
 *  1. normalise (NFKC, fillers, sentence end)                       JaNormalizer
 *  2. build candidate inputs: whole text / sentence by sentence /
 *     with terminology and name transliteration injected           JaSegmenter, Glossary
 *  3. translate every candidate with the neural backend, post-edit   NePostEditor
 *  4. back-translate ne -> ja and score round-trip chrF + penalties  QualityScorer
 *  5. keep the best (lexicographic like the MAGI optimizer: hard constraints first, then quality)
 */
public final class JaNeTranslator {
    public static final class Candidate {
        public final String label, input, output, backTranslation;
        public final double score;
        public final List<TranslationValidator.Issue> issues;
        Candidate(String label, String input, String output, String backTranslation, double score) {
            this(label, input, output, backTranslation, score, Collections.emptyList());
        }
        Candidate(String label, String input, String output, String backTranslation, double score, List<TranslationValidator.Issue> issues) {
            this.label = label; this.input = input; this.output = output; this.backTranslation = backTranslation; this.score = score; this.issues = issues;
        }
        public int hard() { return TranslationValidator.hardCount(issues); }
        @Override public String toString() { return label + " hard=" + hard() + " score=" + String.format("%.3f", score) + " issues=" + issues + " -> " + output; }
    }

    public static final class Result {
        public final String text;
        public final List<Candidate> candidates;
        /** problems detected in the chosen translation (empty when none) */
        public final List<TranslationValidator.Issue> issues;
        Result(String text, List<Candidate> candidates) { this(text, candidates, Collections.emptyList()); }
        Result(String text, List<Candidate> candidates, List<TranslationValidator.Issue> issues) { this.text = text; this.candidates = candidates; this.issues = issues; }
    }

    private final Backend backend;
    private final Glossary glossary;
    private int maxCandidates = 4;
    private int nBest = 3;
    private static final double MBR_WEIGHT = 0.3;

    public JaNeTranslator(Backend backend, Glossary glossary) {
        this.backend = backend;
        this.glossary = glossary == null ? Glossary.withDefaults() : glossary;
    }

    public void setMaxCandidates(int n) { maxCandidates = Math.max(1, n); }

    /** Beam hypotheses taken per single-segment input (1 = only the best). */
    public void setNBest(int n) { nBest = Math.max(1, n); }

    public Result translate(String text, String src, String tgt) throws Exception {
        if ("ja".equals(src) && "ne".equals(tgt)) return jaToNe(text);
        if ("ne".equals(src) && "ja".equals(tgt)) return neToJa(text);
        String out = backend.translate(text, src, tgt);
        return new Result(out, Collections.emptyList());
    }

    // ---------------- ja -> ne ----------------
    private Result jaToNe(String raw) throws Exception {
        String norm = JaNormalizer.normalize(raw);
        if (norm.isEmpty()) return new Result("", Collections.emptyList());
        String whole = JaNormalizer.ensureSentenceEnd(norm);
        List<String> sentences = JaSegmenter.split(norm);

        // candidate inputs (label -> list of segments translated independently and joined)
        Map<String, List<String>> inputs = new LinkedHashMap<>();
        Map<String, List<String>> required = new LinkedHashMap<>();
        Glossary.Injection injWhole = glossary.inject(whole);
        // Required terms come from the source text, so every candidate must satisfy the same ones;
        // otherwise a candidate without injection (and without the terms) would be exempt and win.
        List<String> mustHave = injWhole.requiredTargets;
        List<String> preferred = injWhole.preferredTargets;
        if (injWhole.changed(whole)) {   // terminology first: it is the most constrained candidate
            inputs.put("terms", Collections.singletonList(injWhole.text));
            required.put("terms", mustHave);
        }
        inputs.put("whole", Collections.singletonList(whole));
        required.put("whole", mustHave);
        if (sentences.size() > 1) {
            List<String> segs = new ArrayList<>();
            boolean changed = false;
            for (String s : sentences) {
                Glossary.Injection inj = glossary.inject(s);
                segs.add(inj.text);
                changed |= inj.changed(s);
            }
            inputs.put(changed ? "sentences+terms" : "sentences", segs);
            required.put(changed ? "sentences+terms" : "sentences", mustHave);
        }

        List<Candidate> cands = new ArrayList<>();
        int i = 0;
        for (Map.Entry<String, List<String>> e : inputs.entrySet()) {
            if (i++ >= maxCandidates) break;
            List<String> segs = e.getValue();
            if (segs.size() == 1 && nBest > 1) {
                // one model call, several beam hypotheses: diversity at no extra encoder cost
                List<String> hyps = backend.translateNBest(segs.get(0), "ja", "ne", nBest);
                for (int k = 0; k < hyps.size(); k++) {
                    String out = NePostEditor.edit(hyps.get(k));
                    if (out.isEmpty()) continue;
                    String label = k == 0 ? e.getKey() : e.getKey() + "#" + (k + 1);
                    if (k > 0) required.put(label, required.getOrDefault(e.getKey(), Collections.emptyList()));
                    cands.add(new Candidate(label, segs.get(0), out, null, Double.NaN));
                }
            } else {
                String out = NePostEditor.edit(translateSegments(segs, "ja", "ne"));
                if (out.isEmpty()) continue;
                cands.add(new Candidate(e.getKey(), String.join(" ", segs), out, null, Double.NaN));
            }
        }
        return rerank(cands, whole, required, preferred, "ne", "ja");
    }

    // ---------------- ne -> ja ----------------
    private Result neToJa(String raw) throws Exception {
        String t = Normalizer.normalize(raw == null ? "" : raw, Normalizer.Form.NFC).replace('|', '।').trim();
        if (t.isEmpty()) return new Result("", Collections.emptyList());
        Map<String, List<String>> inputs = new LinkedHashMap<>();
        inputs.put("whole", Collections.singletonList(t));
        List<String> sentences = new ArrayList<>();
        for (String s : t.split("(?<=[।?!])")) if (!s.trim().isEmpty()) sentences.add(s.trim());
        if (sentences.size() > 1) inputs.put("sentences", sentences);
        List<Candidate> cands = new ArrayList<>();
        Map<String, List<String>> required = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : inputs.entrySet()) {
            String out = translateSegments(e.getValue(), "ne", "ja").replaceAll("\\s+", "");
            out = collapseJa(out);
            if (out.isEmpty()) continue;
            cands.add(new Candidate(e.getKey(), String.join(" ", e.getValue()), out, null, Double.NaN));
            required.put(e.getKey(), Collections.emptyList());
        }
        return rerank(cands, t, required, Collections.emptyList(), "ja", "ne");
    }

    /**
     * Lexicographic selection: (1) fewest hard validator issues (number lost, negation lost, required
     * term missing, untranslated source script), (2) highest quality score. Back-translation is only a
     * signal inside the score, never a proof of correctness.
     */
    private Result rerank(List<Candidate> cands, String source, Map<String, List<String>> required, List<String> preferred, String outLang, String srcLang) throws Exception {
        if (cands.isEmpty()) return new Result("", cands);
        List<Candidate> unique = new ArrayList<>();
        for (Candidate c : cands) {
            boolean dup = false;
            for (Candidate u : unique) if (u.output.equals(c.output)) { dup = true; break; }
            if (!dup) unique.add(c);
        }
        if (unique.size() == 1) {   // nothing to choose: skip the round trip but still report problems
            Candidate c = unique.get(0);
            List<TranslationValidator.Issue> iss = validate(source, c.output, required.getOrDefault(c.label, Collections.emptyList()), outLang);
            Candidate v = new Candidate(c.label, c.input, c.output, null, Double.NaN, iss);
            return new Result(v.output, Collections.singletonList(v), iss);
        }
        List<Candidate> scored = new ArrayList<>();
        Candidate best = null;
        for (Candidate c : unique) {
            List<String> req = required.getOrDefault(c.label, Collections.emptyList());
            String back = backend.translate(c.output, outLang, srcLang);
            List<String> scoredTerms = new ArrayList<>(req);
            for (String p : preferred) if (!scoredTerms.contains(p)) scoredTerms.add(p);
            double s = "ne".equals(outLang)
                    ? QualityScorer.score(source, c.output, back, scoredTerms)
                    : QualityScorer.chrF(back, source) - 0.15 * missingNumbers(source, KanjiNumber.toDigits(c.output));
            // MBR consensus (Freitag et al. 2022/2023): expected chrF against the other candidates
            if (unique.size() >= 3) {
                double agree = 0;
                for (Candidate o : unique) if (o != c) agree += QualityScorer.chrF(c.output, o.output);
                s += MBR_WEIGHT * agree / (unique.size() - 1);
            }
            Candidate sc = new Candidate(c.label, c.input, c.output, back, s, validate(source, c.output, req, outLang));
            scored.add(sc);
            if (best == null || better(sc, best)) best = sc;
        }
        return new Result(best.output, scored, best.issues);
    }

    static boolean better(Candidate a, Candidate b) {
        if (a.hard() != b.hard()) return a.hard() < b.hard();
        return a.score > b.score;
    }

    private static List<TranslationValidator.Issue> validate(String source, String output, List<String> req, String outLang) {
        return "ne".equals(outLang)
                ? TranslationValidator.validateJaToNe(source, output, req)
                : TranslationValidator.validateNeToJa(source, output);
    }

    private String translateSegments(List<String> segments, String src, String tgt) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String s : segments) {
            String o = backend.translate(s, src, tgt);
            if (o == null) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(o.trim());
        }
        return sb.toString();
    }

    private static int missingNumbers(String src, String out) {
        List<String> a = QualityScorer.numbers(src), b = QualityScorer.numbers(out);
        int miss = 0;
        for (String n : a) if (!b.remove(n)) miss++;
        return miss;
    }

    /** Collapse looped Japanese clauses ("ありがとう。ありがとう。"). */
    static String collapseJa(String ja) {
        String[] parts = ja.split("(?<=[。！？、])");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (!out.isEmpty() && out.get(out.size() - 1).equals(p)) continue;
            out.add(p);
        }
        return String.join("", out);
    }
}
