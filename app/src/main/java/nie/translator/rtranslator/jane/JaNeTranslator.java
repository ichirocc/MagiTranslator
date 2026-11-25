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
    private final TranslationMemory memory;
    private int maxCandidates = 5;
    private int nBest = 3;
    private static final double MBR_WEIGHT = 0.3;

    public JaNeTranslator(Backend backend, Glossary glossary) { this(backend, glossary, new TranslationMemory()); }

    public JaNeTranslator(Backend backend, Glossary glossary, TranslationMemory memory) {
        this.backend = backend;
        this.glossary = glossary == null ? Glossary.withDefaults() : glossary;
        this.memory = memory == null ? new TranslationMemory() : memory;
    }

    /** Conversation memory of term / name renderings; clear it when the conversation ends. */
    public TranslationMemory memory() { return memory; }

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
        // known terms and names: glossary first, then renderings fixed earlier in this conversation
        Map<String, String> mem = memory.snapshot();
        Glossary.Injection injWhole = glossary.inject(whole, mem, false);
        // Required terms come from the source text, so every candidate must satisfy the same ones;
        // otherwise a candidate without injection (and without the terms) would be exempt and win.
        List<String> mustHave = injWhole.requiredTargets;
        List<String> preferred = new ArrayList<>(injWhole.preferredTargets);
        // unknown names: transliterate / keep katakana / common noun, chosen by the rerank (SPEC §10.2)
        List<ProperNameResolver.Variant> nameVariants = ProperNameResolver.variants(injWhole.text, glossary, mem);
        Map<String, Map<String, String>> labelNames = new LinkedHashMap<>();
        List<String> unknown = ProperNameResolver.unknownNames(injWhole.text, glossary, mem);
        String termsText = injWhole.text;
        for (ProperNameResolver.Variant v : nameVariants)
            if (v.mode == ProperNameResolver.Mode.TRANSLITERATE) {
                termsText = v.text;
                labelNames.put("terms", v.names);
                for (String d : v.names.values()) if (!preferred.contains(d) && !mustHave.contains(d)) preferred.add(d);
            }
        if (!termsText.equals(whole)) {   // terminology first: it is the most constrained candidate
            inputs.put("terms", Collections.singletonList(termsText));
            required.put("terms", mustHave);
        }
        inputs.put("whole", Collections.singletonList(whole));
        required.put("whole", mustHave);
        if (sentences.size() > 1) {
            List<String> segs = new ArrayList<>();
            Map<String, String> names = new LinkedHashMap<>();
            boolean changed = false;
            for (String s : sentences) {
                Glossary.Injection inj = glossary.inject(s, mem, true);
                segs.add(inj.text);
                changed |= inj.changed(s);
                for (Map.Entry<String, String> a : inj.applied.entrySet()) if (unknown.contains(a.getKey())) names.put(a.getKey(), a.getValue());
            }
            String label = changed ? "sentences+terms" : "sentences";
            inputs.put(label, segs);
            required.put(label, mustHave);
            labelNames.put(label, names);
        }
        for (ProperNameResolver.Variant v : nameVariants) {
            if (v.mode == ProperNameResolver.Mode.TRANSLITERATE) continue;
            if (v.text.equals(whole) || v.text.equals(termsText)) continue;
            String label = v.mode == ProperNameResolver.Mode.KEEP ? "terms+name:keep"
                    : v.mode == ProperNameResolver.Mode.TRANSLITERATE_BARE ? "terms+name:bare" : "terms+name:noun";
            inputs.put(label, Collections.singletonList(v.text));
            required.put(label, mustHave);
            if (v.mode == ProperNameResolver.Mode.TRANSLITERATE_BARE) labelNames.put(label, v.names);
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
                    String out = finishNe(hyps.get(k), whole);
                    if (out.isEmpty()) continue;
                    String label = k == 0 ? e.getKey() : e.getKey() + "#" + (k + 1);
                    if (k > 0) required.put(label, required.getOrDefault(e.getKey(), Collections.emptyList()));
                    cands.add(new Candidate(label, segs.get(0), out, null, Double.NaN));
                }
            } else {
                String out = finishNe(translateSegments(segs, "ja", "ne"), whole);
                if (out.isEmpty()) continue;
                cands.add(new Candidate(e.getKey(), String.join(" ", segs), out, null, Double.NaN));
            }
        }
        Result res = rerank(cands, whole, required, preferred, "ne", "ja");
        remember(res, injWhole.applied, labelNames);
        return res;
    }

    /**
     * Fixes the renderings used in the chosen translation for the rest of the conversation: known
     * terms/names that were injected, and rule-transliterated names when the chosen candidate came from
     * a transliterated input and actually contains that spelling.
     */
    private void remember(Result res, Map<String, String> applied, Map<String, Map<String, String>> labelNames) {
        if (res.text == null || res.text.isEmpty()) return;
        for (Map.Entry<String, String> a : applied.entrySet())
            if (TranslationValidator.containsTerm(res.text, a.getValue())) memory.remember(a.getKey(), a.getValue());
        Candidate chosen = null;
        for (Candidate c : res.candidates) if (c.output.equals(res.text)) { chosen = c; break; }
        if (chosen == null) return;
        int hash = chosen.label.indexOf('#');
        Map<String, String> names = labelNames.get(hash < 0 ? chosen.label : chosen.label.substring(0, hash));
        if (names == null) return;
        for (Map.Entry<String, String> n : names.entrySet())
            if (TranslationValidator.containsTerm(res.text, n.getValue())) memory.remember(n.getKey(), n.getValue());
    }

    // ---------------- ne -> ja ----------------
    private Result neToJa(String raw) throws Exception {
        String t = Normalizer.normalize(raw == null ? "" : raw, Normalizer.Form.NFC).replace('|', '।').trim();
        if (t.isEmpty()) return new Result("", Collections.emptyList());
        Map<String, List<String>> inputs = new LinkedHashMap<>();
        Map<String, List<String>> required = new LinkedHashMap<>();
        // known names/terms (नेपाल, काठमाडौं ...) are annotated with their Japanese form: the raw model turned
        // काठमाडौं into カタルーニャ and नेपाल into ネバダ州 in the baseline (docs/eval/2026-11-25_*)
        Glossary.Injection rev = glossary.injectReverse(t);
        if (!rev.text.equals(t)) {
            inputs.put("terms", Collections.singletonList(rev.text));
            required.put("terms", rev.requiredTargets);
        }
        inputs.put("whole", Collections.singletonList(t));
        List<String> sentences = new ArrayList<>();
        for (String s : t.split("(?<=[।?!])")) if (!s.trim().isEmpty()) sentences.add(s.trim());
        if (sentences.size() > 1) inputs.put("sentences", sentences);
        List<Candidate> cands = new ArrayList<>();
        // the names are in the source whatever the input variant: every candidate must render them
        for (String k : inputs.keySet()) required.put(k, rev.requiredTargets);
        for (Map.Entry<String, List<String>> e : new ArrayList<>(inputs.entrySet())) {
            List<String> segs = e.getValue();
            if (segs.size() == 1 && nBest > 1) {
                // beam alternatives: the baseline turned १,००० रुपैयाँ into 「100万円」 with no other candidate
                List<String> hyps = backend.translateNBest(segs.get(0), "ne", "ja", nBest);
                for (int k = 0; k < hyps.size(); k++) {
                    String out = finishJa(hyps.get(k));
                    if (out.isEmpty()) continue;
                    String label = k == 0 ? e.getKey() : e.getKey() + "#" + (k + 1);
                    required.putIfAbsent(label, rev.requiredTargets);
                    cands.add(new Candidate(label, segs.get(0), out, null, Double.NaN));
                }
            } else {
                String out = finishJa(translateSegments(segs, "ne", "ja"));
                if (out.isEmpty()) continue;
                cands.add(new Candidate(e.getKey(), String.join(" ", segs), out, null, Double.NaN));
            }
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
        if ("ja".equals(outLang)) {
            // ne->ja: beam alternatives (label "#k") only as a fallback. Measured on the seed set, letting them
            // compete changed meanings (これは何ですか -> いくらですか); they are needed only when every primary
            // candidate has a hard problem (e.g. the number hallucination 1,000 rupees -> 100万円).
            Candidate primary = null;
            for (Candidate c : scored) if (!c.label.contains("#") && (primary == null || better(c, primary))) primary = c;
            if (primary != null && primary.hard() == 0) best = primary;
        }
        return new Result(best.output, scored, best.issues);
    }

    private static List<TranslationValidator.Issue> withRequiredTerms(List<TranslationValidator.Issue> issues, String output, List<String> req) {
        if (req == null || req.isEmpty()) return issues;
        List<TranslationValidator.Issue> all = new ArrayList<>(issues);
        for (String t : req) if (!TranslationValidator.containsTerm(output, t)) all.add(new TranslationValidator.Issue(TranslationValidator.Kind.TERM_MISSING, true, t));
        return all;
    }

    static boolean better(Candidate a, Candidate b) {
        if (a.hard() != b.hard()) return a.hard() < b.hard();
        return a.score > b.score;
    }

    private static List<TranslationValidator.Issue> validate(String source, String output, List<String> req, String outLang) {
        return "ne".equals(outLang)
                ? TranslationValidator.validateJaToNe(source, output, req)
                : withRequiredTerms(TranslationValidator.validateNeToJa(source, output), output, req);
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

    /** Japanese output clean-up: no spaces, no leading list dash the model sometimes emits ("- こんにちは"). */
    static String finishJa(String ja) {
        if (ja == null) return "";
        String t = ja.replaceAll("\\s+", "").replaceFirst("^[-‐‑–—・*]+", "");
        return collapseJa(t);
    }

    /** Nepali output: add the danda when the source sentence ended and the model dropped the full stop. */
    static String finishNe(String ne, String sourceJa) {
        String t = NePostEditor.edit(ne);
        if (t.isEmpty()) return t;
        String src = sourceJa == null ? "" : sourceJa.trim();
        boolean srcEnds = !src.isEmpty() && "。．.".indexOf(src.charAt(src.length() - 1)) >= 0;
        if (srcEnds && "।?!॥".indexOf(t.charAt(t.length() - 1)) < 0) t = t + "।";
        return t;
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
