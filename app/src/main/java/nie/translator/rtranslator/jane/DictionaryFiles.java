package nie.translator.rtranslator.jane;

import java.text.Normalizer;

/**
 * The jane dictionaries are data files kept OUTSIDE the APK: they are downloaded from this
 * repository's release "jane-dictionaries" or imported by the user, stored in the app's files dir
 * (files/jane/), and can be updated without a new app version. Translation works without them.
 *
 * Every file starts with a type line, so that a file can be recognised whatever its name:
 * <pre>
 *   #!jane ja-ne-terms   japanese&lt;TAB&gt;nepali            (Glossary, ja -&gt; ne)
 *   #!jane ne-ja-terms   nepali&lt;TAB&gt;japanese            (NeJaGlossary, ne -&gt; ja)
 *   #!jane phrases       japanese&lt;TAB&gt;nepali[&lt;TAB&gt;ja&gt;ne] (PhraseBook, both directions)
 * </pre>
 * {@link #check} validates a whole file strictly; a file with a single bad line is rejected as a
 * whole, so a broken download or a wrong file never reaches the translator.
 *
 * Pure Java (no Android dependency) so that CI checks the same rules as the app.
 */
public final class DictionaryFiles {
    private DictionaryFiles() {}

    public enum Kind {
        JA_NE_TERMS("ja-ne-terms", "ja_ne_terms.tsv"),
        NE_JA_TERMS("ne-ja-terms", "ne_ja_terms.tsv"),
        PHRASES("phrases", "phrases.tsv");

        public final String tag;
        /** file name in the app's files/jane/ folder, in the repository's dictionaries/ folder and in the release */
        public final String fileName;

        Kind(String tag, String fileName) { this.tag = tag; this.fileName = fileName; }

        public static Kind fromTag(String tag) {
            for (Kind k : values()) if (k.tag.equals(tag)) return k;
            return null;
        }
    }

    public static final String TYPE_PREFIX = "#!jane ";
    /** a dictionary is small text; anything bigger is not a dictionary */
    public static final int MAX_BYTES = 1_000_000;

    private static final String NE = "\\u0900-\\u0963\\u0966-\\u097F";
    private static final String NE_TERM = "[" + NE + "]+( [" + NE + "]+)*";
    private static final String DEVANAGARI_ANY = ".*[\\u0900-\\u097F].*";

    /** Result of {@link #check}: the kind and number of entries, or an error (kind == null). */
    public static final class Check {
        public final Kind kind;
        public final int entries;
        public final String error;

        private Check(Kind kind, int entries, String error) { this.kind = kind; this.entries = entries; this.error = error; }

        public boolean ok() { return error == null; }

        @Override public String toString() { return ok() ? kind.tag + " (" + entries + ")" : "error: " + error; }
    }

    public static Check check(String content) {
        if (content == null) return new Check(null, 0, "empty file");
        if (content.length() > MAX_BYTES) return new Check(null, 0, "file too large");
        if (content.startsWith("﻿")) content = content.substring(1);
        String[] lines = content.split("\r?\n", -1);
        if (lines.length == 0 || !lines[0].startsWith(TYPE_PREFIX)) {
            return new Check(null, 0, "first line must be \"" + TYPE_PREFIX + "<ja-ne-terms|ne-ja-terms|phrases>\"");
        }
        Kind kind = Kind.fromTag(lines[0].substring(TYPE_PREFIX.length()).trim());
        if (kind == null) return new Check(null, 0, "unknown dictionary type: " + lines[0]);
        java.util.Set<String> keys = new java.util.HashSet<>();
        int entries = 0;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] c = line.split("\t", -1);
            String why = checkLine(kind, c);
            if (why != null) return new Check(null, 0, "line " + (i + 1) + ": " + why);
            String key = kind == Kind.PHRASES ? PhraseBook.keyJa(c[0]) : c[0];
            if (!keys.add(key)) return new Check(null, 0, "line " + (i + 1) + ": duplicate entry " + c[0]);
            entries++;
        }
        if (entries == 0) return new Check(null, 0, "no entries");
        return new Check(kind, entries, null);
    }

    private static String checkLine(Kind kind, String[] c) {
        switch (kind) {
            case JA_NE_TERMS:
                if (c.length != 2) return "expected japanese<TAB>nepali";
                if (!c[0].equals(c[0].trim()) || c[0].codePointCount(0, c[0].length()) < 2 || c[0].matches(DEVANAGARI_ANY)) {
                    return "japanese term must be at least 2 characters, without Devanagari";
                }
                if (!c[1].matches(NE_TERM)) return "nepali term must be Devanagari words";
                return null;
            case NE_JA_TERMS:
                if (c.length != 2) return "expected nepali<TAB>japanese";
                if (!c[0].matches(NE_TERM) || !c[0].equals(Normalizer.normalize(c[0], Normalizer.Form.NFC))) {
                    return "nepali term must be Devanagari words in NFC";
                }
                if (c[1].isEmpty() || !c[1].equals(c[1].trim()) || c[1].matches(DEVANAGARI_ANY)) return "japanese term missing or contains Devanagari";
                return null;
            case PHRASES:
                if (!(c.length == 2 || (c.length == 3 && c[2].equals("ja>ne")))) return "expected japanese<TAB>nepali[<TAB>ja>ne]";
                if (c[0].isEmpty() || c[0].matches(DEVANAGARI_ANY) || PhraseBook.keyJa(c[0]).isEmpty()) return "japanese phrase missing";
                if (!c[1].matches("[\\u0900-\\u097F ,?]+") || !c[1].equals(c[1].trim())) return "nepali phrase must be Devanagari";
                return null;
            default:
                return "unknown type";
        }
    }
}
