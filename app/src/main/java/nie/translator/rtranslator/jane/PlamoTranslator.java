package nie.translator.rtranslator.jane;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Japanese <-> Nepali with PLaMo 3 Translate (on device), using what the model takes natively:
 * <ul>
 *   <li>the user's phrase book first: an exact hit is the official translation and needs no model call;</li>
 *   <li>dictionary terms found in the text go into the PFML3 glossary (longest match, so 車椅子 is not
 *       also given as 椅子): measured, this fixed 移乗 / 賞味期限 / 速達 that the bare model mistranslated;</li>
 *   <li>the previous turns of the conversation go in as translation history (both speakers, turned into
 *       the direction of the request), so a short or ambiguous utterance is read in context;</li>
 *   <li>Japanese output in polite style (the bare model answered a clinic visitor in plain style).</li>
 * </ul>
 * The result is checked by {@link TranslationValidator} (numbers, negation, required terms, script ...);
 * when it has a hard problem and history was used, the sentence is translated once more without history
 * and the better of the two is kept. A translation that still has a hard problem is marked for review.
 * <p>
 * Not thread-safe: the caller serialises translations (Translator's translation lock).
 */
public final class PlamoTranslator {
    /** cache slots of the model (one per direction, see {@link TextCompletion#complete(int, String, int, String...)}) */
    static final int SLOT_JA_NE = 0, SLOT_NE_JA = 1;
    static final int MAX_TERMS = 24;
    /** longest input sent in one piece; longer texts are translated sentence by sentence */
    static final int MAX_PIECE_CHARS = 400;

    private final TextCompletion model;
    private final Glossary glossary;
    private volatile PhraseBook phrases = new PhraseBook();
    private boolean polite = true;
    private int historyTurns = 3, historyMaxTurns = 6, historyMaxChars = 900;

    /** conversation turns, oldest first: {japanese, nepali} */
    private final List<String[]> turns = new ArrayList<>();

    public PlamoTranslator(TextCompletion model, Glossary glossary) {
        this.model = model;
        this.glossary = glossary == null ? Glossary.withDefaults() : glossary;
    }

    public void setPhraseBook(PhraseBook p) { phrases = p == null ? new PhraseBook() : p; }

    /** Japanese output in polite style (default true). */
    public void setPolite(boolean on) { polite = on; }

    /**
     * Conversation history given to the model. It grows to {@code max} turns and is then cut back to the
     * last {@code keep}: between cuts every prompt of a direction starts with the previous one, so the model
     * reuses what it computed instead of reading the whole history again on each turn.
     */
    public void setHistory(int keep, int max) {
        historyTurns = Math.max(0, keep);
        historyMaxTurns = Math.max(historyTurns, max);
    }

    /** Receives the translation as it is generated (first model call of each translation only); null = none. */
    private volatile java.util.function.Consumer<String> draftListener;
    /** set while the first candidate of a translation is generated: drafts are reported then only */
    private java.util.function.Consumer<String> activeDraft;
    private final StringBuilder draftPrefix = new StringBuilder();

    /** Draft listener for the following translations (null: none). Drafts are raw model text, before checks. */
    public void setDraftListener(java.util.function.Consumer<String> listener) { draftListener = listener; }

    /** Forgets the conversation (new conversation, language change). */
    public synchronized void clearConversation() { turns.clear(); }

    public synchronized int historySize() { return turns.size(); }

    public JaNeTranslator.Result translate(String text, String src, String tgt) throws Exception {
        if ("ja".equals(src) && "ne".equals(tgt)) return jaToNe(text);
        if ("ne".equals(src) && "ja".equals(tgt)) return neToJa(text);
        throw new IllegalArgumentException("PLaMo path is for ja <-> ne only: " + src + " -> " + tgt);
    }

    private JaNeTranslator.Result jaToNe(String raw) throws Exception {
        String norm = JaNormalizer.normalize(raw);
        if (norm.isEmpty()) return new JaNeTranslator.Result("", Collections.emptyList());
        String phrase = phrases.lookupJaNe(norm);
        if (phrase != null) {
            String out = JaNeTranslator.finishNe(phrase, norm);
            if (!out.isEmpty()) return remember(norm, out, phraseResult(norm, out, "ne"));
        }
        List<Glossary.Term> terms = glossary.termsJaNe(norm, null, MAX_TERMS);
        JaNeTranslator.Candidate best = run("ja", "ne", norm, terms);
        return remember(norm, best.output, new JaNeTranslator.Result(best.output, Collections.singletonList(best), best.issues));
    }

    private JaNeTranslator.Result neToJa(String raw) throws Exception {
        String t = Normalizer.normalize(raw == null ? "" : raw, Normalizer.Form.NFC).replace('|', '।').trim();
        if (t.isEmpty()) return new JaNeTranslator.Result("", Collections.emptyList());
        String phrase = phrases.lookupNeJa(t);
        if (phrase != null) {
            String out = JaNeTranslator.finishJa(phrase);
            if (!out.isEmpty()) return remember(out, t, phraseResult(t, out, "ja"));
        }
        // what the model reads; the history keeps what was said
        String q = t, call = "";
        java.util.regex.Matcher v = NE_LEAD_VOCATIVE.matcher(t);
        if (v.find() && v.end() < t.length()) {
            q = t.substring(v.end()).trim();
            call = NE_ELDER.matcher(v.group(2)).matches() ? "すみません、" : "ねえ、";
        }
        q = deliberative(q);
        List<Glossary.Term> terms = glossary.termsNeJa(q, MAX_TERMS);
        JaNeTranslator.Candidate best = run("ne", "ja", q, terms);
        if (firstPersonPastWithoutSubject(q) && tenseLost(q, best.output)) best = withSubject(q, terms, best);
        if (!call.isEmpty() && !best.output.startsWith(call)) {
            String out = call + best.output;
            best = new JaNeTranslator.Candidate(best.label, t, out, null, Double.NaN, JaNeTranslator.validate(t, out, Collections.emptyList(), "ja"));
        }
        return remember(best.output, t, new JaNeTranslator.Result(best.output, Collections.singletonList(best), best.issues));
    }

    /**
     * A report in the first person past with the subject left out (ब्रेक लगाउन बिर्सें "I forgot the brake",
     * दिसा जाँच तालिकामा लेखें "I wrote it in the bowel chart") was read by the model as a request or an
     * invitation (忘れないでください, 書きましょう) or in the present (忘れます). With the subject written out (मैले ...)
     * it gets the tense right; the 私は it then adds is dropped, as Japanese leaves it out. Kept only when the
     * tense is then right.
     */
    private JaNeTranslator.Candidate withSubject(String t, List<Glossary.Term> terms, JaNeTranslator.Candidate first) throws Exception {
        List<String> required = new ArrayList<>();
        for (Glossary.Term g : terms) if (g.required && !required.contains(g.target)) required.add(g.target);
        JaNeTranslator.Candidate c = candidate("plamo+subject", "ne", "ja", "मैले " + t, Collections.emptyList(), terms, required);
        String out = JaNeTranslator.finishJa(LEADING_WATASHI.matcher(c.output).replaceFirst(""));
        if (out.isEmpty() || tenseLost(t, out)) return first;
        JaNeTranslator.Candidate fixed = new JaNeTranslator.Candidate("plamo+subject", t, out, null, Double.NaN,
                JaNeTranslator.validate(t, out, required, "ja"));
        return JaNeTranslator.better(first, fixed) ? first : fixed;
    }

    private static final java.util.regex.Pattern LEADING_WATASHI = java.util.regex.Pattern.compile("^(私|わたし)(は|が)、?");
    // last word of the text: a first person past (बिर्सें, लेखिसकें, भएँ) or its negative (बुझिनँ, गरिनँ)
    private static final java.util.regex.Pattern NE_FIRST_PERSON_PAST = java.util.regex.Pattern.compile("(ें|ेँ|एँ|िनँ|इनँ)[।?!\\s]*$");
    private static final java.util.regex.Pattern NE_SUBJECT = java.util.regex.Pattern.compile("(?<![\\u0900-\\u097F])(म|मैले|हामी|हामीले)(?![\\u0900-\\u097F])");
    // Japanese ending in a request or an invitation, with no past tense
    private static final java.util.regex.Pattern JA_REQUEST_END = java.util.regex.Pattern.compile("(ください|下さい|ましょう|ましょうか|なさい|しよう)[。！？!?]*$");

    static boolean firstPersonPastWithoutSubject(String ne) {
        return ne != null && NE_FIRST_PERSON_PAST.matcher(ne).find() && !NE_SUBJECT.matcher(ne).find();
    }

    // a positive first person past (not बुझिनँ: わかりません is a right reading of "I did not understand")
    private static final java.util.regex.Pattern NE_POSITIVE_PAST = java.util.regex.Pattern.compile("(ें|ेँ|एँ)[।?!\\s]*$");
    private static final java.util.regex.Pattern JA_PAST_END = java.util.regex.Pattern.compile("(た|だ|ました|でした|ませんでした|かった)(よ|ね|けど)?[。！？!?]*$");

    /** The Japanese of a first person past report reads as a request, or (for a positive past) not as past. */
    static boolean tenseLost(String ne, String ja) {
        if (requestOrVolitional(ja)) return true;
        return ja != null && NE_POSITIVE_PAST.matcher(ne).find() && !JA_PAST_END.matcher(ja.trim()).find();
    }

    /**
     * A call at the start (ए बाबु, / दिदी,) is not part of the sentence: the model wrote ए बाबु as アバブ and
     * दाइ as お父さん. It is taken off before the model reads the text and given back as すみません、 (to an elder
     * colleague: दिदी, दाइ) or ねえ、. Parents and grandparents (आमा, बुबा) are left in: the model gets them right,
     * and so is a bare ए, (an exclamation: あ、 / え、, not a call).
     */
    private static final java.util.regex.Pattern NE_LEAD_VOCATIVE = java.util.regex.Pattern.compile(
            "^\\s*(ए|हे|ओए|ओइ)?\\s*(बाबु|नानी|छोरा|छोरी|दिदी|दाइ|दाजु|भाइ|बहिनी)\\s*[,،!]\\s*");
    private static final java.util.regex.Pattern NE_ELDER = java.util.regex.Pattern.compile("दिदी|दाइ|दाजु");

    /**
     * "What should I ...?" asked with the bare -ने form (के हेर्ने? कहाँ लेख्ने?) was read as a plain question
     * about someone else (何を見るのですか); with -नुपर्छ ("must") the model asks what to do (何を見ればいいですか).
     * Only a short question with a question word and no second person (तपाईं जाने? is "are you going?").
     */
    static String deliberative(String ne) {
        if (ne == null || ne.length() > 80) return ne;
        java.util.regex.Matcher m = NE_DELIBERATIVE.matcher(ne);
        if (!m.find() || !NE_QUESTION_WORD.matcher(ne).find() || NE_SECOND_PERSON.matcher(ne).find()) return ne;
        return ne.substring(0, m.start(1)) + "नुपर्छ" + ne.substring(m.end(1));
    }

    private static final java.util.regex.Pattern NE_DELIBERATIVE = java.util.regex.Pattern.compile("[\\u0915-\\u0939\\u094D\\u093E-\\u094C](ने)\\s*\\?\\s*$");
    private static final java.util.regex.Pattern NE_QUESTION_WORD = java.util.regex.Pattern.compile(
            "(?<![\\u0900-\\u097F])(के|कहाँ|कहां|कति|कसरी|कुन|कहिले|किन|कसलाई|कसले)(?![\\u0900-\\u097F])");
    private static final java.util.regex.Pattern NE_SECOND_PERSON = java.util.regex.Pattern.compile("तपाई|तपाइ|तिमी|तँ|हजुर");

    static boolean requestOrVolitional(String ja) {
        return ja != null && JA_REQUEST_END.matcher(ja.trim()).find();
    }

    private static JaNeTranslator.Result phraseResult(String source, String out, String outLang) {
        List<TranslationValidator.Issue> iss = JaNeTranslator.validate(source, out, Collections.emptyList(), outLang);
        JaNeTranslator.Candidate c = new JaNeTranslator.Candidate("phrase", source, out, null, Double.NaN, iss);
        return new JaNeTranslator.Result(out, Collections.singletonList(c), iss);
    }

    // the model with history, and once more without it when the first translation has a hard problem
    private JaNeTranslator.Candidate run(String src, String tgt, String text, List<Glossary.Term> terms) throws Exception {
        List<String> required = new ArrayList<>();
        for (Glossary.Term g : terms) if (g.required && !required.contains(g.target)) required.add(g.target);
        List<PlamoPrompt.Turn> history = history(src);
        JaNeTranslator.Candidate first = null;
        activeDraft = draftListener;   // drafts during the first model call only: a retry is not shown half-done
        draftPrefix.setLength(0);
        if (!history.isEmpty()) {
            try {
                first = candidate("plamo+history", src, tgt, text, history, terms, required);
                activeDraft = null;
                if (first.hard() == 0) return first;
            } catch (java.io.IOException tooLong) {
                first = null;   // prompt over the context budget: translate without history below
                draftPrefix.setLength(0);
            }
        }
        JaNeTranslator.Candidate plain;
        try {
            plain = candidate("plamo", src, tgt, text, Collections.emptyList(), terms, required);
        } finally {
            activeDraft = null;
        }
        if (first == null) return plain;
        return JaNeTranslator.better(plain, first) ? plain : first;
    }

    private JaNeTranslator.Candidate candidate(String label, String src, String tgt, String text, List<PlamoPrompt.Turn> history,
                                               List<Glossary.Term> terms, List<String> required) throws Exception {
        String out = "ne".equals(tgt) ? JaNeTranslator.finishNe(translatePieces(src, tgt, text, history, terms), text)
                : JaNeTranslator.finishJa(translatePieces(src, tgt, text, history, terms), text);
        List<TranslationValidator.Issue> iss = JaNeTranslator.validate(text, out, required, tgt);
        return new JaNeTranslator.Candidate(label, text, out, null, Double.NaN, iss);
    }

    private String translatePieces(String src, String tgt, String text, List<PlamoPrompt.Turn> history, List<Glossary.Term> terms) throws Exception {
        if (text.length() <= MAX_PIECE_CHARS) return complete(src, tgt, text, history, terms);
        List<String> pieces = capLength("ja".equals(src) ? JaSegmenter.split(text) : splitNepali(text));
        StringBuilder out = new StringBuilder();
        for (String piece : pieces) {
            String o = complete(src, tgt, piece, history, terms);
            if (o.isEmpty()) continue;
            if (out.length() > 0 && "ne".equals(tgt)) out.append(' ');
            out.append(o);
            if (activeDraft != null) { draftPrefix.setLength(0); draftPrefix.append(out).append("ne".equals(tgt) ? " " : ""); }
        }
        return out.toString();
    }

    private static List<String> splitNepali(String text) {
        List<String> out = new ArrayList<>();
        for (String s : text.split("(?<=[।॥?!？！])")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }

    /**
     * A sentence with no full stop can still be longer than MAX_PIECE_CHARS (a long spoken utterance): cut it
     * at the last comma or space before the limit, else at the limit, so no piece exceeds the context budget.
     */
    static List<String> capLength(List<String> pieces) {
        List<String> out = new ArrayList<>();
        for (String p : pieces) {
            String rest = p;
            while (rest.length() > MAX_PIECE_CHARS) {
                int cut = -1;
                for (int i = MAX_PIECE_CHARS; i > MAX_PIECE_CHARS / 2; i--) {
                    char c = rest.charAt(i - 1);
                    if ("、，,;；".indexOf(c) >= 0) { cut = i; break; }
                    if (cut < 0 && Character.isWhitespace(c)) cut = i;   //a space is the fallback; keep looking for a comma
                }
                if (cut < 0) cut = MAX_PIECE_CHARS;
                //never inside a syllable: not after a virama (्) nor before a vowel sign / nukta / nasal mark
                while (cut > 1 && (rest.charAt(cut - 1) == '\u094D' || Character.getType(rest.charAt(cut)) == Character.NON_SPACING_MARK
                        || Character.getType(rest.charAt(cut)) == Character.COMBINING_SPACING_MARK || Character.isLowSurrogate(rest.charAt(cut)))) cut--;
                String head = rest.substring(0, cut).trim();
                if (!head.isEmpty()) out.add(head);
                rest = rest.substring(cut).trim();
            }
            if (!rest.isEmpty()) out.add(rest);
        }
        return out;
    }

    private String complete(String src, String tgt, String text, List<PlamoPrompt.Turn> history, List<Glossary.Term> terms) throws Exception {
        String prompt = PlamoPrompt.build(src, tgt, text, history, terms, polite);
        java.util.function.Consumer<String> draft = activeDraft;
        String prefix = draftPrefix.toString();
        String out = draft == null
                ? model.complete("ja".equals(src) ? SLOT_JA_NE : SLOT_NE_JA, prompt, maxTokens(text), PlamoPrompt.STOPS)
                : model.complete("ja".equals(src) ? SLOT_JA_NE : SLOT_NE_JA, prompt, maxTokens(text),
                        part -> draft.accept(prefix + PlamoPrompt.clean(Normalizer.normalize(part, Normalizer.Form.NFC))), PlamoPrompt.STOPS);
        return PlamoPrompt.clean(Normalizer.normalize(out == null ? "" : out, Normalizer.Form.NFC));
    }

    /** generous for Nepali written from short Japanese (more tokens per meaning); bounds a looping output */
    static int maxTokens(String text) { return Math.min(512, 48 + 3 * text.length()); }

    // ---------------- conversation history ----------------

    private synchronized List<PlamoPrompt.Turn> history(String src) {
        List<PlamoPrompt.Turn> h = new ArrayList<>();
        for (String[] t : turns) h.add("ja".equals(src) ? new PlamoPrompt.Turn(t[0], t[1]) : new PlamoPrompt.Turn(t[1], t[0]));
        return h;
    }

    private synchronized JaNeTranslator.Result remember(String ja, String ne, JaNeTranslator.Result r) {
        // a translation marked for review is not fed back: a wrong rendering would steer the next turns
        if (historyTurns == 0 || r.needsReview() || ja.isEmpty() || ne.isEmpty()) return r;
        turns.add(new String[]{ja, ne});
        int chars = 0;
        for (String[] t : turns) chars += t[0].length() + t[1].length();
        if (turns.size() > historyMaxTurns || chars > historyMaxChars) {
            while (turns.size() > historyTurns) turns.remove(0);
            chars = 0;
            for (String[] t : turns) chars += t[0].length() + t[1].length();
            while (!turns.isEmpty() && chars > historyMaxChars) { String[] t = turns.remove(0); chars -= t[0].length() + t[1].length(); }
        }
        return r;
    }
}
