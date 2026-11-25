package nie.translator.rtranslator.jane.ir;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stage 1 Language IR (SPEC v2 part 2, rule based). One instance describes one text in one language.
 *
 * Every field is evidence found in the text itself (InformationSource EXPLICIT_TEXT /
 * MORPHOLOGICAL_EVIDENCE); nothing is inferred from context. Two kinds of fields exist:
 * - stated fields (whWords, modalities, tense, currencies, places): what the text says, detected
 *   strictly. Used when the text is the source.
 * - cover fields (whCover, modalityCover, and tense PAST on the Nepali side): forms that could express a
 *   concept, detected leniently. Used when the text is a candidate, so a correct candidate is not made
 *   hard by an unusual but valid rendering.
 * Stage 1 fills only what a rule uses: Nepali stated modalities and the Japanese modality cover stay
 * empty, and an empty set there means "not analysed", not "absent".
 */
public final class LanguageIR {
    public final String language;          // "ja" | "ne"
    public final String originalText;
    /** predicate-level negation present */
    public final boolean negated;
    /** interrogative sentence */
    public final boolean question;
    /** stated question words with the matched surface, in rule order */
    public final Map<WhConcept, String> whWords;
    public final Set<WhConcept> whCover;
    public final Set<Modality> modalities;
    public final Set<Modality> modalityCover;
    /** ja: tense of the last sentence's predicate (PAST / NON_PAST); ne: PAST if any past form, else UNKNOWN */
    public final Tense tense;
    /** numbers as written (digits, Devanagari digits, Nepali number words) */
    public final List<String> numbers;
    /** ja: kanji numerals before a counter turned into digits first; ne: same as numbers */
    public final List<String> numbersNormalized;
    public final Set<Currency> currencies;
    /** Japanese key of each known place the text names (ne: stem at word start; ja: substring) */
    public final Set<String> places;
    /** ja only: places named as places, not as a demonym / language (ネパール語, 日本人) */
    public final Set<String> placesAsPlace;
    /** ja: katakana words of 2+ chars that are not known places (name candidates); ne: empty */
    public final List<String> names;
    /** degenerate output: a katakana word immediately repeated (ネパールネパール) */
    public final boolean degenerateRepeat;

    LanguageIR(String language, String originalText, boolean negated, boolean question,
               LinkedHashMap<WhConcept, String> whWords, EnumSet<WhConcept> whCover,
               EnumSet<Modality> modalities, EnumSet<Modality> modalityCover, Tense tense,
               List<String> numbers, List<String> numbersNormalized, EnumSet<Currency> currencies,
               LinkedHashSet<String> places, LinkedHashSet<String> placesAsPlace, List<String> names,
               boolean degenerateRepeat) {
        this.language = language;
        this.originalText = originalText;
        this.negated = negated;
        this.question = question;
        this.whWords = Collections.unmodifiableMap(whWords);
        this.whCover = Collections.unmodifiableSet(whCover);
        this.modalities = Collections.unmodifiableSet(modalities);
        this.modalityCover = Collections.unmodifiableSet(modalityCover);
        this.tense = tense;
        this.numbers = Collections.unmodifiableList(numbers);
        this.numbersNormalized = Collections.unmodifiableList(numbersNormalized);
        this.currencies = Collections.unmodifiableSet(currencies);
        this.places = Collections.unmodifiableSet(places);
        this.placesAsPlace = Collections.unmodifiableSet(placesAsPlace);
        this.names = Collections.unmodifiableList(names);
        this.degenerateRepeat = degenerateRepeat;
    }

    @Override public String toString() {
        return language + "{neg=" + negated + ", q=" + question + ", wh=" + whWords.keySet() + ", whCover=" + whCover
                + ", mod=" + modalities + ", modCover=" + modalityCover + ", tense=" + tense + ", num=" + numbersNormalized
                + ", cur=" + currencies + ", places=" + places + ", names=" + names + (degenerateRepeat ? ", REPEAT" : "") + "}";
    }
}
