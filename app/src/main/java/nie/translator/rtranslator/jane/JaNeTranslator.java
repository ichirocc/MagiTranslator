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

        /** Abstention (spec v2 part 5): the chosen translation still has a hard problem, so the UI marks it "要確認". */
        public boolean needsReview() { return TranslationValidator.hardCount(issues) > 0; }

        /** Kinds of the hard problems in the chosen translation, without any text (safe to log). */
        public List<TranslationValidator.Kind> hardKinds() {
            List<TranslationValidator.Kind> kinds = new ArrayList<>();
            for (TranslationValidator.Issue i : issues) if (i.hard && !kinds.contains(i.kind)) kinds.add(i.kind);
            return kinds;
        }
    }

    private final Backend backend;
    private final Glossary glossary;
    // whole-sentence phrases from the user's own dictionary (UserDictionary); empty unless imported
    private volatile PhraseBook phrases = new PhraseBook();

    /** Sets the user's phrase book. A hit adds a "phrase" candidate that is validated and ranked like the others. */
    public void setPhraseBook(PhraseBook p) { phrases = p == null ? new PhraseBook() : p; }
    private final TranslationMemory memory;
    private int maxCandidates = 5;
    private int nBest = 3;
    private static final double MBR_WEIGHT = 0.3;
    // extra round when every candidate is hard (diversification): wider beam and more hypotheses
    static final int WIDE_BEAM = 8, WIDE_NBEST = 6;
    private boolean diversify = true;

    /** Enables the bounded extra round of candidates when every candidate still has a hard problem. */
    public void setDiversify(boolean on) { diversify = on; }

    public JaNeTranslator(Backend backend, Glossary glossary) { this(backend, glossary, new TranslationMemory()); }

    public JaNeTranslator(Backend backend, Glossary glossary, TranslationMemory memory) {
        this.backend = new MemoBackend(backend);
        this.glossary = glossary == null ? Glossary.withDefaults() : glossary;
        this.memory = memory == null ? new TranslationMemory() : memory;
    }

    /** Conversation memory of term / name renderings; clear it when the conversation ends. */
    public TranslationMemory memory() { return memory; }

    public void setMaxCandidates(int n) { maxCandidates = Math.max(1, n); }

    /** Beam hypotheses taken per single-segment input (1 = only the best). */
    public void setNBest(int n) { nBest = Math.max(1, n); }

    public Result translate(String text, String src, String tgt) throws Exception {
        ((MemoBackend) backend).clear();   // request-local: identical calls within one translation are made once
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
        for (String u : glossary.userPreferred(whole)) if (!preferred.contains(u) && !mustHave.contains(u)) preferred.add(u);
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
        // katakana adds an epenthetic u to consonant-final foreign names (ラム = Ram): when a bare rendering
        // exists, a candidate spelling the name with that u is ranked below one that does not (jn17 रामु)
        List<String> extraVowel = new ArrayList<>();
        Map<String, String> full = labelNames.get("terms");
        Map<String, String> bareNames = labelNames.get("terms+name:bare");
        if (full != null && bareNames != null)
            for (Map.Entry<String, String> e : full.entrySet()) {
                String b = bareNames.get(e.getKey());
                if (b != null && !b.equals(e.getValue())) {
                    extraVowel.add(e.getValue());
                    if (!preferred.contains(b)) preferred.add(b);
                }
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
        String phrase = phrases.lookupJaNe(whole);
        if (phrase != null) {
            String out = finishNe(phrase, whole);
            if (!out.isEmpty()) {
                cands.add(new Candidate("phrase", whole, out, null, Double.NaN));
                required.put("phrase", mustHave);
                if (!preferred.contains(out)) preferred.add(out);
            }
        }
        Result res = rerank(cands, whole, required, preferred, "ne", "ja", extraVowel);
        if (diversify && TranslationValidator.hardCount(res.issues) > 0) {
            // every candidate is hard: one bounded extra round (wider beam, clause split, paraphrase)
            List<Candidate> more = new ArrayList<>(cands);
            addHyps(more, required, mustHave, "wide", whole, backend.translateNBestWide(whole, "ja", "ne", WIDE_NBEST, WIDE_BEAM), true, whole);
            if (sentences.size() == 1) {
                List<String> clauses = JaParaphrase.clauses(whole);
                if (clauses.size() > 1) {
                    String out = finishNe(translateSegments(clauses, "ja", "ne"), whole);
                    if (!out.isEmpty()) { more.add(new Candidate("clauses", whole, out, null, Double.NaN)); required.put("clauses", mustHave); }
                }
            }
            String para = JaParaphrase.paraphrase(termsText);
            if (!para.equals(termsText))
                addHyps(more, required, mustHave, "para", para, backend.translateNBest(para, "ja", "ne", nBest), false, whole);
            if (more.size() > cands.size()) res = rerank(more, whole, required, preferred, "ne", "ja", extraVowel);
        }
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
        if (Boolean.parseBoolean(System.getProperty("jane.nePunct", "true"))) t = NePunctuation.restore(t);   // speech recognition drops the final mark; restore it before translating
        Map<String, List<String>> inputs = new LinkedHashMap<>();
        Map<String, List<String>> required = new LinkedHashMap<>();
        // known names/terms (नेपाल, काठमाडौं ...) are annotated with their Japanese form: the raw model turned
        // काठमाडौं into カタルーニャ and नेपाल into ネバダ州 in the baseline (docs/eval/2026-11-25_*)
        Glossary.Injection rev = glossary.injectReverse(t);
        if (!rev.text.equals(t)) {
            inputs.put("terms", Collections.singletonList(rev.text));
            required.put("terms", rev.requiredTargets);
        }
        Glossary.Injection user = glossary.injectReverseUser(rev.text);
        List<String> neToJaPreferred = new ArrayList<>(user.preferredTargets);
        if (!user.text.equals(rev.text)) {
            inputs.put("userterms", Collections.singletonList(user.text));
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
        String phrase = phrases.lookupNeJa(t);
        if (phrase != null) {
            String out = finishJa(phrase);
            if (!out.isEmpty()) {
                cands.add(new Candidate("phrase", t, out, null, Double.NaN));
                required.put("phrase", rev.requiredTargets);
                if (!neToJaPreferred.contains(out)) neToJaPreferred.add(out);
            }
        }
        Result res = rerank(cands, t, required, neToJaPreferred, "ja", "ne");
        if (diversify && TranslationValidator.hardCount(res.issues) > 0) {
            List<Candidate> more = new ArrayList<>(cands);
            addHyps(more, required, rev.requiredTargets, "wide", t, backend.translateNBestWide(t, "ne", "ja", WIDE_NBEST, WIDE_BEAM), true, null);
            String para = NeParaphrase.paraphrase(rev.text);
            if (!para.equals(rev.text))
                addHyps(more, required, rev.requiredTargets, "para", para, backend.translateNBest(para, "ne", "ja", nBest), false, null);
            if (more.size() > cands.size()) res = rerank(more, t, required, neToJaPreferred, "ja", "ne");
        }
        if (diversify && TranslationValidator.hardCount(res.issues) > 0) {
            // deterministic repairs of the scored candidates; validated and ranked like any other candidate
            List<Candidate> pool = new ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (Candidate c : res.candidates) { pool.add(new Candidate(c.label, c.input, c.output, null, Double.NaN)); seen.add(c.output); }
            int before = pool.size();
            for (Candidate c : res.candidates) {
                if (c.hard() == 0) continue;
                boolean numberOk = true;
                for (TranslationValidator.Issue i : c.issues)
                    if (i.hard && i.kind == TranslationValidator.Kind.NUMBER_LOST) numberOk = false;
                String cur = numberOk ? JaRepair.currency(t, c.output) : null;
                if (cur != null && seen.add(cur)) { pool.add(new Candidate("repair:currency", c.input, cur, null, Double.NaN)); required.put("repair:currency", rev.requiredTargets); }
                // No repetition repair here on purpose: collapsing nj17 「ネパールネパールの首都ネパールシティ」 gives
                // 「ネパールの首都ネパールシティ」, which passes every check yet is still a hallucination. The repetition
                // is the only signal that keeps the 要確認 marker on it, so the collapsed form must not replace it.
            }
            if (pool.size() > before) res = rerank(pool, t, required, neToJaPreferred, "ja", "ne");
        }
        return res;
    }

    /** Adds beam hypotheses as candidates; with allHash every label carries "#" (fallback-only alternatives). */
    private void addHyps(List<Candidate> into, Map<String, List<String>> required, List<String> req, String base, String input,
                         List<String> hyps, boolean allHash, String jaWhole) {
        for (int k = 0; k < hyps.size(); k++) {
            String out = jaWhole != null ? finishNe(hyps.get(k), jaWhole) : finishJa(hyps.get(k));
            if (out.isEmpty()) continue;
            String label = allHash || k > 0 ? base + "#" + (k + 1) : base;
            required.put(label, req);
            into.add(new Candidate(label, input, out, null, Double.NaN));
        }
    }

    /**
     * Lexicographic selection: (1) fewest hard validator issues (number lost, negation lost, required
     * term missing, untranslated source script), (2) highest quality score. Back-translation is only a
     * signal inside the score, never a proof of correctness.
     */
    private Result rerank(List<Candidate> cands, String source, Map<String, List<String>> required, List<String> preferred, String outLang, String srcLang) throws Exception {
        return rerank(cands, source, required, preferred, outLang, srcLang, Collections.emptyList());
    }

    private Result rerank(List<Candidate> cands, String source, Map<String, List<String>> required, List<String> preferred, String outLang, String srcLang, List<String> extraVowel) throws Exception {
        if (cands.isEmpty()) return new Result("", cands);
        List<Candidate> unique = new ArrayList<>();
        for (Candidate c : cands) {
            boolean dup = false;
            for (Candidate u : unique) if (u.output.equals(c.output)) { dup = true; break; }
            if (!dup) unique.add(c);
        }
        if (unique.size() == 1) {   // nothing to choose: skip the round trip but still report problems
            Candidate c = unique.get(0);
            List<TranslationValidator.Issue> iss = withExtraVowel(validate(source, c.output, required.getOrDefault(c.label, Collections.emptyList()), outLang), c.output, extraVowel);
            Candidate v = new Candidate(c.label, c.input, c.output, null, Double.NaN, iss);
            return new Result(v.output, Collections.singletonList(v), iss);
        }
        List<Candidate> scored = new ArrayList<>();
        Candidate best = null;
        java.util.Set<Candidate> toScore = backTranslationSet(unique, source, required, preferred, outLang, extraVowel);
        for (Candidate c : unique) {
            List<String> req = required.getOrDefault(c.label, Collections.emptyList());
            if (!toScore.contains(c)) {   // cannot win on the hard checks or ranked below the top K: no round trip
                double s0 = Double.NEGATIVE_INFINITY;
                if (btTopK(outLang, source) == 0) {   // no back-translation: rank by agreement with the other candidates + preferred terms
                    double agree = 0;
                    for (Candidate o : unique) if (o != c) agree += QualityScorer.chrF(c.output, o.output);
                    s0 = agree / Math.max(1, unique.size() - 1) + preferredShare(c.output, preferred);
                }
                Candidate sc = new Candidate(c.label, c.input, c.output, null, s0, withExtraVowel(validate(source, c.output, req, outLang), c.output, extraVowel));
                scored.add(sc);
                if (best == null || better(sc, best)) best = sc;
                continue;
            }
            String back = backend.translate(c.output, outLang, srcLang);
            List<String> scoredTerms = new ArrayList<>(req);
            for (String p : preferred) if (!scoredTerms.contains(p)) scoredTerms.add(p);
            double s = "ne".equals(outLang)
                    ? QualityScorer.score(source, c.output, back, scoredTerms)
                    : QualityScorer.chrF(back, source) - 0.15 * missingNumbers(source, KanjiNumber.toDigits(c.output))
                      + PREFERRED_JA_WEIGHT * preferredShare(c.output, preferred);
            // MBR consensus (Freitag et al. 2022/2023): expected chrF against the other candidates
            if (unique.size() >= 3) {
                double agree = 0;
                for (Candidate o : unique) if (o != c) agree += QualityScorer.chrF(c.output, o.output);
                s += MBR_WEIGHT * agree / (unique.size() - 1);
            }
            Candidate sc = new Candidate(c.label, c.input, c.output, back, s, withExtraVowel(validate(source, c.output, req, outLang), c.output, extraVowel));
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

    static List<TranslationValidator.Issue> withExtraVowel(List<TranslationValidator.Issue> issues, String output, List<String> extraVowel) {
        if (extraVowel == null || extraVowel.isEmpty()) return issues;
        List<TranslationValidator.Issue> all = new ArrayList<>(issues);
        for (String d : extraVowel) if (TranslationValidator.containsTerm(output, d)) all.add(new TranslationValidator.Issue(TranslationValidator.Kind.NAME_EXTRA_VOWEL, true, d));
        return all;
    }

    // ne->ja: share of the user's preferred Japanese terms present in a candidate (0 when there are none,
    // so behaviour without a user dictionary is unchanged)
    static final double PREFERRED_JA_WEIGHT = 0.05;

    static double preferredShare(String output, List<String> preferred) {
        if (preferred == null || preferred.isEmpty()) return 0;
        int hit = 0;
        for (String p : preferred) if (TranslationValidator.containsTerm(output, p)) hit++;
        return hit / (double) preferred.size();
    }

    static boolean better(Candidate a, Candidate b) {
        if (a.hard() != b.hard() && (a.hard() == 0 || b.hard() == 0)) return a.hard() < b.hard();
        if (a.hard() > 0 && b.hard() > 0) {
            // both still have hard problems: the less severe set wins (a wrong number or a flipped negation is
            // worse than a missing preferred term); ties fall back to the count, then the score
            int sa = severity(a.issues), sb = severity(b.issues);
            if (sa != sb) return sa < sb;
            if (a.hard() != b.hard()) return a.hard() < b.hard();
        }
        return a.score > b.score;
    }

    /** Severity weight of one hard issue (SPEC v2 05b mapping: CRITICAL > MAJOR > MODERATE). */
    static int severity(TranslationValidator.Kind k) {
        switch (k) {
            case EMPTY: return 1000;
            case NUMBER_LOST: case NEGATION_LOST: return 250;   // above two currency or place issues
            case CURRENCY_MISMATCH: case PLACE_ADDED: case PLACE_MISSING: return 100;
            case QUESTION_WORD_LOST: case WH_LOST: case REPETITION: case UNTRANSLATED_SOURCE_SCRIPT: return 60;
            case OBLIGATION_LOST: case REQUEST_LOST: case PAST_LOST: case QUESTION_LOST: return 40;
            case TERM_MISSING: return 30;
            case NAME_EXTRA_VOWEL: return 10;
            default: return 20;
        }
    }

    static int severity(List<TranslationValidator.Issue> issues) {
        int s = 0;
        for (TranslationValidator.Issue i : issues) if (i.hard) s += severity(i.kind);
        return s;
    }

    static List<TranslationValidator.Issue> validate(String source, String output, List<String> req, String outLang) {
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

    /** Back-translations per rerank: the round trip is the dominant cost (measured: 2-4 of 3-5 model calls per
     *  sentence). Hard checks are cheap and decide first, so only candidates in the best hard class can win; among
     *  them only the top btTopK(outLang, source) by a cheap proxy (MBR agreement + preferred terms) are
     *  back-translated. A single survivor needs no round trip at all. */
    // Measured on the seed set (docs/eval/2026-11-26_jane-speed-seed.md): for ja->ne, ranking by candidate agreement
    // without back-translation kept the critical-error rate (5%) and raised chrF++ (67.7 -> 74.5) at ~2.5x speed;
    // for ne->ja it lost a number (1000 -> 1000万), so ne->ja keeps the round trip.
    static final int BT_TOP_K_JA_NE = Integer.getInteger("jane.btTopK.jaNe", 0);
    static final int BT_TOP_K_NE_JA = Integer.getInteger("jane.btTopK.neJa", 2);
    // ne->ja without numbers / currency / places: the measured loss without the round trip was a number error only
    static final int BT_TOP_K_NE_JA_PLAIN = Integer.getInteger("jane.btTopK.neJaPlain", 0);
    static int btTopK(String outLang, String source) {
        if ("ne".equals(outLang)) return BT_TOP_K_JA_NE;
        nie.translator.rtranslator.jane.ir.LanguageIR ir = nie.translator.rtranslator.jane.ir.NeAnalyzer.analyze(source);
        boolean risky = !ir.numbers.isEmpty() || !ir.currencies.isEmpty() || !ir.places.isEmpty();
        return risky ? BT_TOP_K_NE_JA : BT_TOP_K_NE_JA_PLAIN;
    }

    private java.util.Set<Candidate> backTranslationSet(List<Candidate> unique, String source, Map<String, List<String>> required,
                                                        List<String> preferred, String outLang, List<String> extraVowel) {
        List<Candidate> checked = new ArrayList<>();
        for (Candidate c : unique)
            checked.add(new Candidate(c.label, c.input, c.output, null, 0,
                    withExtraVowel(validate(source, c.output, required.getOrDefault(c.label, Collections.emptyList()), outLang), c.output, extraVowel)));
        Candidate top = null;
        for (Candidate c : checked) if (top == null || classBetter(c, top)) top = c;
        List<Candidate> pool = new ArrayList<>();   // every candidate in the best hard class (originals, not copies)
        for (int i = 0; i < checked.size(); i++) if (!classBetter(top, checked.get(i))) pool.add(unique.get(i));
        java.util.Set<Candidate> out = new java.util.HashSet<>();
        if (pool.size() <= 1 || btTopK(outLang, source) == 0) return out;   // the hard checks already decide (or round trips are off)
        // ne->ja: the primary (non-"#k") candidates take precedence when hard-free, so they are always scored
        if ("ja".equals(outLang)) for (Candidate c : pool) if (!c.label.contains("#")) out.add(c);
        final Map<Candidate, Double> proxy = new java.util.HashMap<>();
        for (Candidate c : pool) {
            double agree = 0;
            for (Candidate o : unique) if (o != c) agree += QualityScorer.chrF(c.output, o.output);
            proxy.put(c, agree / Math.max(1, unique.size() - 1) + preferredShare(c.output, preferred));
        }
        List<Candidate> ranked = new ArrayList<>(pool);
        ranked.sort((x, y) -> Double.compare(proxy.get(y), proxy.get(x)));
        int added = 0;
        for (Candidate c : ranked) {
            if (added >= btTopK(outLang, source)) break;
            if (out.add(c)) added++; else if (!c.label.contains("#")) added++;
        }
        return out;
    }

    /** Hard-check class only (no score): a beats b when it is hard-free and b is not, or less severe, or fewer. */
    private static boolean classBetter(Candidate a, Candidate b) {
        if ((a.hard() == 0) != (b.hard() == 0)) return a.hard() == 0;
        if (a.hard() == 0) return false;
        int sa = severity(a.issues), sb = severity(b.issues);
        if (sa != sb) return sa < sb;
        return a.hard() < b.hard();
    }

    /** Request-local memo of model calls: the diversification round re-ranks the earlier candidates, and their
     *  back-translations must not be decoded twice. Cleared at the start of every {@link #translate}. */
    private static final class MemoBackend implements Backend {
        private final Backend inner;
        private final Map<String, Object> memo = new java.util.HashMap<>();
        MemoBackend(Backend inner) { this.inner = inner; }
        void clear() { memo.clear(); }
        public String translate(String text, String s, String t) throws Exception {
            String k = "1\u0000" + s + "\u0000" + t + "\u0000" + text;
            Object v = memo.get(k);
            if (v == null) { v = inner.translate(text, s, t); if (v != null) memo.put(k, v); }
            return (String) v;
        }
        @SuppressWarnings("unchecked")
        public List<String> translateNBest(String text, String s, String t, int n) throws Exception {
            String k = "n" + n + "\u0000" + s + "\u0000" + t + "\u0000" + text;
            Object v = memo.get(k);
            if (v == null) { v = inner.translateNBest(text, s, t, n); if (v != null) memo.put(k, v); }
            return (List<String>) v;
        }
        @SuppressWarnings("unchecked")
        public List<String> translateNBestWide(String text, String s, String t, int n, int beam) throws Exception {
            String k = "w" + n + "/" + beam + "\u0000" + s + "\u0000" + t + "\u0000" + text;
            Object v = memo.get(k);
            if (v == null) { v = inner.translateNBestWide(text, s, t, n, beam); if (v != null) memo.put(k, v); }
            return (List<String>) v;
        }
    }
}
