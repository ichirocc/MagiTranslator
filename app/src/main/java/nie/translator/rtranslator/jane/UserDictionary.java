package nie.translator.rtranslator.jane;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A user's own dictionary file, kept only on the device (files/user_dict/). Three kinds, told apart by
 * the first line:
 * <pre>
 *   #!jane ja-ne-terms   japanese&lt;TAB&gt;nepali        terms for ja -> ne
 *   #!jane ne-ja-terms   nepali&lt;TAB&gt;japanese        terms for ne -> ja
 *   #!jane phrases       japanese&lt;TAB&gt;nepali[&lt;TAB&gt;ja&gt;ne]   whole sentences ("ja&gt;ne" = ja -> ne only)
 * </pre>
 * Lines starting with # are comments. Entries are user data, not verified dictionary truth (SPEC v2
 * Part 4): terms only steer the ranking (PREFERRED), phrases only add a candidate that still goes
 * through the validator. Contents are never logged.
 */
public final class UserDictionary {

    public enum Kind {
        JA_NE_TERMS("ja-ne-terms", "ja_ne_terms.tsv"),
        NE_JA_TERMS("ne-ja-terms", "ne_ja_terms.tsv"),
        PHRASES("phrases", "phrases.tsv");
        public final String header, fileName;
        Kind(String header, String fileName) { this.header = header; this.fileName = fileName; }
    }

    /** Directory under the app's private files dir (excluded from backup by data_extraction_rules). */
    public static final String DIR_NAME = "user_dict";
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    public static final int MAX_ENTRIES = 50_000;
    public static final int MAX_FIELD_CHARS = 400;
    public static final String JA_TO_NE_ONLY = "ja>ne";

    public static final class Entry {
        public final String source, target;
        public final boolean jaToNeOnly;
        Entry(String source, String target, boolean jaToNeOnly) { this.source = source; this.target = target; this.jaToNeOnly = jaToNeOnly; }
    }

    /** Result of reading one file: only counts are meant to be shown or logged, never the entries. */
    public static final class Parsed {
        public final Kind kind;
        public final List<Entry> entries;
        public final int rejected, duplicates;
        Parsed(Kind kind, List<Entry> entries, int rejected, int duplicates) {
            this.kind = kind; this.entries = Collections.unmodifiableList(entries); this.rejected = rejected; this.duplicates = duplicates;
        }
        public int accepted() { return entries.size(); }
        @Override public String toString() { return kind.header + " accepted=" + accepted() + " rejected=" + rejected + " duplicates=" + duplicates; }
    }

    private UserDictionary() {}

    /** The kind named by a first line, or null when the line is not a jane dictionary header. */
    public static Kind kindOf(String firstLine) {
        if (firstLine == null) return null;
        String l = firstLine.startsWith("﻿") ? firstLine.substring(1) : firstLine;
        l = l.trim();
        if (!l.startsWith("#!jane ")) return null;
        String h = l.substring(7).trim();
        for (Kind k : Kind.values()) if (k.header.equals(h)) return k;
        return null;
    }

    /** @throws IllegalArgumentException when the header is missing or unknown */
    public static Parsed parse(Reader reader) throws IOException {
        BufferedReader br = new BufferedReader(reader);
        Kind kind = kindOf(br.readLine());
        if (kind == null) throw new IllegalArgumentException("not a jane dictionary (first line must be #!jane ja-ne-terms|ne-ja-terms|phrases)");
        List<Entry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int rejected = 0, dup = 0;
        String line;
        while ((line = br.readLine()) != null) {
            if (line.trim().isEmpty() || line.startsWith("#")) continue;
            Entry e = entry(kind, line);
            if (e == null) { rejected++; continue; }
            if (!seen.add(e.source)) { dup++; continue; }
            if (out.size() >= MAX_ENTRIES) { rejected++; continue; }
            out.add(e);
        }
        return new Parsed(kind, out, rejected, dup);
    }

    static Entry entry(Kind kind, String line) {
        String[] c = line.split("\t", -1);
        if (c.length < 2 || c.length > 3) return null;
        String a = clean(c[0]), b = clean(c[1]);
        if (a == null || b == null) return null;
        boolean only = false;
        if (c.length == 3) {
            String flag = c[2].trim();
            if (kind != Kind.PHRASES || !(flag.isEmpty() || JA_TO_NE_ONLY.equals(flag))) return null;
            only = !flag.isEmpty();
        }
        // the Japanese side must not be Devanagari and the Nepali side must be (catches swapped columns)
        String ja = kind == Kind.NE_JA_TERMS ? b : a, ne = kind == Kind.NE_JA_TERMS ? a : b;
        if (hasDevanagari(ja) || !hasDevanagari(ne)) return null;
        return new Entry(a, b, only);
    }

    private static String clean(String s) {
        String t = Normalizer.normalize(s, Normalizer.Form.NFC).trim();
        if (t.isEmpty() || t.length() > MAX_FIELD_CHARS) return null;
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (Character.isISOControl(ch)) return null;
        }
        return t;
    }

    static boolean hasDevanagari(String s) {
        for (int i = 0; i < s.length(); i++) { char ch = s.charAt(i); if (ch >= 0x0900 && ch <= 0x097F) return true; }
        return false;
    }

    /** Adds the entries to the glossary (terms) or the phrase book (phrases). */
    public static void apply(Parsed p, Glossary glossary, PhraseBook phrases) {
        for (Entry e : p.entries) {
            switch (p.kind) {
                case JA_NE_TERMS: glossary.putUser(e.source, e.target); break;
                case NE_JA_TERMS: glossary.putUserReverse(e.source, e.target); break;
                case PHRASES: phrases.add(e.source, e.target, e.jaToNeOnly); break;
            }
        }
    }

    /**
     * Validates a picked file and, only if it is a jane dictionary, stores it in {@code dir} under the
     * name of its kind (replacing an earlier file of the same kind). Written to a temp file first.
     * @throws IllegalArgumentException for a non-dictionary file or one over {@link #MAX_BYTES}
     */
    public static Parsed importStream(InputStream in, File dir) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) > 0) {
            buf.write(b, 0, n);
            if (buf.size() > MAX_BYTES) throw new IllegalArgumentException("dictionary file too large");
        }
        byte[] data = buf.toByteArray();
        Parsed p = parse(new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8));
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir.getName());
        File tmp = new File(dir, p.kind.fileName + ".tmp"), dst = new File(dir, p.kind.fileName);
        try (FileOutputStream fo = new FileOutputStream(tmp)) { fo.write(data); fo.getFD().sync(); }
        if (!tmp.renameTo(dst)) { tmp.delete(); throw new IOException("cannot store dictionary"); }
        return p;
    }

    /** Loads every stored dictionary in {@code dir}; a broken file is skipped (its summary is not returned). */
    public static List<Parsed> loadDirectory(File dir, Glossary glossary, PhraseBook phrases) {
        List<Parsed> loaded = new ArrayList<>();
        if (dir == null || !dir.isDirectory()) return loaded;
        for (Kind k : Kind.values()) {
            File f = new File(dir, k.fileName);
            if (!f.isFile() || f.length() > MAX_BYTES) continue;
            try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
                Parsed p = parse(r);
                if (p.kind != k) continue;
                apply(p, glossary, phrases);
                loaded.add(p);
            } catch (IOException | IllegalArgumentException ignored) {
                // skipped; the import screen reports problems when the file is picked
            }
        }
        return loaded;
    }

    /** Changes whenever a stored dictionary is added, replaced or deleted (for rebuilding the translator). */
    public static String stamp(File dir) {
        StringBuilder sb = new StringBuilder();
        for (Kind k : Kind.values()) {
            File f = dir == null ? null : new File(dir, k.fileName);
            sb.append(f != null && f.isFile() ? f.lastModified() + ":" + f.length() : "-").append(';');
        }
        return sb.toString();
    }

    /** Deletes all stored user dictionaries. */
    public static void deleteAll(File dir) {
        if (dir == null) return;
        for (Kind k : Kind.values()) new File(dir, k.fileName).delete();
    }
}
