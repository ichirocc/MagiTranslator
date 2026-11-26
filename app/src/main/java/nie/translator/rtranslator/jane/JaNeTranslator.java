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
    private static final java.util.regex.Pattern NE_SENTENCE_END = java.util.regex.Pattern.compile("(?<=[।?!])");
    private static final java.util.regex.Pattern JA_CLAUSE_END = java.util.regex.Pattern.compile("(?<=[。！？、])");
    private static final java.util.regex.Pattern SPACES = java.util.regex.Pattern.compile("\\s+");

    private final NeJaGlossary neGlossary;
    private PhraseBook phrases = new PhraseBook();

    /** Everyday set phrases translated by lookup (whole utterance or whole sentence only). */
    public void setPhraseBook(PhraseBook phrases) { this.phrases = phrases == null ? new PhraseBook() : phrases; }

    public JaNeTranslator(Backend backend, Glossary glossary) {
        this(backend, glossary, null);
    }

    /** @param neGlossary Nepali -> Japanese terms for the ne -> ja direction (null = none) */
    public JaNeTranslator(Backend backend, Glossary glossary, NeJaGlossary neGlossary) {
        this.backend = backend;
        this.glossary = glossary == null ? Glossary.withDefaults() : glossary;
        this.neGlossary = neGlossary == null ? new NeJaGlossary() : neGlossary;
    }

    public void setMaxCandidates(int n) { maxCandidates = Math.max(1, n); }

    /** Beam hypotheses taken per single-segment input (1 = only the best). */
    public void setNBest(int n) { nBest = Math.max(1, n); }

    // ---------------- speed: memoisation ----------------
    // Beam search is deterministic, so the same input always gives the same model output. One utterance
    // makes several model calls (candidates + back-translations) and a conversation repeats sentences
    // ("はい、わかりました。"), so model calls and whole results are memoised (small LRU caches).
    // The owner must call clearCache() when anything that changes the model output changes (beam size,
    // model); dictionaries are fixed for the lifetime of a JaNeTranslator (a new one is built on change).
    private static final int MODEL_CACHE_SIZE = 256, RESULT_CACHE_SIZE = 64;
    private final Map<String, Object> modelCache = lru(MODEL_CACHE_SIZE);
    private final Map<String, Result> resultCache = lru(RESULT_CACHE_SIZE);

    private static <V> Map<String, V> lru(int max) {
        return Collections.synchronizedMap(new LinkedHashMap<String, V>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, V> e) { return size() > max; }
        });
    }

    /** Forgets memoised model outputs and results (call when the beam size or the model changes). */
    public void clearCache() { modelCache.clear(); resultCache.clear(); }

    private String modelTranslate(String text, String src, String tgt) throws Exception {
        String key = "1\u0000" + src + "\u0000" + tgt + "\u0000" + text;
        Object hit = modelCache.get(key);
        if (hit instanceof String) return (String) hit;
        String out = backend.translate(text, src, tgt);
        if (out != null) modelCache.put(key, out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<String> modelTranslateNBest(String text, String src, String tgt, int n) throws Exception {
        String key = "N" + n + "\u0000" + src + "\u0000" + tgt + "\u0000" + text;
        Object hit = modelCache.get(key);
        if (hit instanceof List) return (List<String>) hit;
        List<String> out = backend.translateNBest(text, src, tgt, n);
        if (out != null) modelCache.put(key, Collections.unmodifiableList(new ArrayList<>(out)));
        return out;
    }

    public Result translate(String text, String src, String tgt) throws Exception {
        String key = src + "\u0000" + tgt + "\u0000" + maxCandidates + "\u0000" + nBest + "\u0000" + text;
        Result hit = resultCache.get(key);
        if (hit != null) return hit;
        Result r;
        if ("ja".equals(src) && "ne".equals(tgt)) r = jaToNe(text);
        else if ("ne".equals(src) && "ja".equals(tgt)) r = neToJa(text);
        else r = new Result(modelTranslate(text, src, tgt), Collections.emptyList());
        if (r.text != null && !r.text.isEmpty()) resultCache.put(key, r);   //an empty result may be a transient failure
        return r;
    }

    // ---------------- ja -> ne ----------------
    private Result jaToNe(String raw) throws Exception {
        String norm = JaNormalizer.normalize(raw);
        if (norm.isEmpty()) return new Result("", Collections.emptyList());
        String fixed = phrases.lookupJaToNe(norm);
        if (fixed != null) return phraseResult(norm, fixed);
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
                if (phrases.lookupJaToNe(s) != null) { segs.add(s); continue; }   //translated by the phrase book
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
                List<String> hyps = modelTranslateNBest(segs.get(0), "ja", "ne", nBest);
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
        String fixed = phrases.lookupNeToJa(t);
        if (fixed != null) return phraseResult(t, fixed);
        Map<String, List<String>> inputs = new LinkedHashMap<>();
        // terminology (NeJaGlossary): the same required Japanese terms apply to every candidate
        Glossary.Injection inj = neGlossary.inject(t);
        List<String> mustHave = inj.requiredTargets;
        if (inj.changed(t)) inputs.put("terms", Collections.singletonList(inj.text));
        inputs.put("whole", Collections.singletonList(t));
        List<String> sentences = new ArrayList<>();
        for (String s : NE_SENTENCE_END.split(t)) if (!s.trim().isEmpty()) sentences.add(s.trim());
        if (sentences.size() > 1) {
            List<String> segs = new ArrayList<>();
            boolean changed = false;
            for (String s : sentences) {
                if (phrases.lookupNeToJa(s) != null) { segs.add(s); continue; }   //translated by the phrase book
                Glossary.Injection si = neGlossary.inject(s);
                segs.add(si.text);
                changed |= si.changed(s);
            }
            inputs.put(changed ? "sentences+terms" : "sentences", segs);
        }
        List<Candidate> cands = new ArrayList<>();
        Map<String, List<String>> required = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : inputs.entrySet()) {
            String out = SPACES.matcher(translateSegments(e.getValue(), "ne", "ja")).replaceAll("");
            out = collapseJa(out);
            if (out.isEmpty()) continue;
            cands.add(new Candidate(e.getKey(), String.join(" ", e.getValue()), out, null, Double.NaN));
            required.put(e.getKey(), mustHave);
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
        // Speed: the validator is cheap and the selection is lexicographic (hard issues first), so a
        // candidate with more hard issues than the best one can never be chosen whatever its score:
        // only the candidates tied on the fewest hard issues are back-translated (one model call each).
        // When a single candidate is left, no back-translation is needed at all.
        List<List<TranslationValidator.Issue>> issues = new ArrayList<>();
        int minHard = Integer.MAX_VALUE;
        for (Candidate c : unique) {
            List<TranslationValidator.Issue> iss = validate(source, c.output, required.getOrDefault(c.label, Collections.emptyList()), outLang);
            issues.add(iss);
            minHard = Math.min(minHard, TranslationValidator.hardCount(iss));
        }
        int contenders = 0;
        for (List<TranslationValidator.Issue> iss : issues) if (TranslationValidator.hardCount(iss) == minHard) contenders++;
        List<Candidate> scored = new ArrayList<>();
        Candidate best = null;
        for (int ci = 0; ci < unique.size(); ci++) {
            Candidate c = unique.get(ci);
            List<String> req = required.getOrDefault(c.label, Collections.emptyList());
            if (TranslationValidator.hardCount(issues.get(ci)) > minHard || contenders == 1) {
                //cannot lose (sole contender) or cannot win (more hard issues): no round trip, no score
                Candidate sc = new Candidate(c.label, c.input, c.output, null, Double.NaN, issues.get(ci));
                scored.add(sc);
                if (best == null || better(sc, best)) best = sc;
                continue;
            }
            String back = modelTranslate(c.output, outLang, srcLang);
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
            Candidate sc = new Candidate(c.label, c.input, c.output, back, s, issues.get(ci));
            scored.add(sc);
            if (best == null || better(sc, best)) best = sc;
        }
        return new Result(best.output, scored, best.issues);
    }

    private static Result phraseResult(String input, String output) {
        Candidate c = new Candidate("phrase", input, output, null, Double.NaN);
        return new Result(output, Collections.singletonList(c));
    }

    static boolean better(Candidate a, Candidate b) {
        if (a.hard() != b.hard()) return a.hard() < b.hard();
        return a.score > b.score;
    }

    private static List<TranslationValidator.Issue> validate(String source, String output, List<String> req, String outLang) {
        return "ne".equals(outLang)
                ? TranslationValidator.validateJaToNe(source, output, req)
                : TranslationValidator.validateNeToJa(source, output, req);
    }

    private String translateSegments(List<String> segments, String src, String tgt) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String s : segments) {
            String fixed = phrases.lookup(s, src, tgt);   //a whole sentence that is a set phrase
            String o = fixed != null ? fixed : modelTranslate(s, src, tgt);
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
        String[] parts = JA_CLAUSE_END.split(ja);
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (!out.isEmpty() && out.get(out.size() - 1).equals(p)) continue;
            out.add(p);
        }
        return String.join("", out);
    }
}
