package nie.translator.rtranslator.jane.ir;

/**
 * Question-word concepts shared by both languages. A source analyzer reports the concepts its text
 * states (strict); a target analyzer reports the concepts its text could express (lenient cover).
 * Declaration order is irrelevant: issue order follows the source analyzer's rule order.
 */
public enum WhConcept {
    /** ja 何時 (clock time) */ TIME_OCLOCK,
    /** ja 何人 */ COUNT_PERSON,
    /** ja いくら */ PRICE,
    /** ne कति (how much / how many) */ QUANTITY,
    WHO, WHERE, WHEN, WHY, HOW, WHICH
}
