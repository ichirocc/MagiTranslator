/*
 * Copyright 2026 MagiTranslator contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nie.translator.rtranslator.voice_translation.neural_networks.translation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The HY-MT tokenizer (tokenizer.json: byte-level BPE, a sequence of regex splits, added tokens) in Java.
 * Replaces the DJL HuggingFace tokenizer, whose native library is not 16 KB page aligned (Android 15+).
 * Gives the token ids of HuggingFace tokenizers' encode (no special tokens added: this tokenizer has no
 * template) and its decode with special tokens kept, as the app called them through DJL; checked against
 * the tokenizers library on a multilingual text set (tools/models/README.md). Supports exactly what this
 * tokenizer.json uses and refuses anything else, so a different file fails loudly instead of tokenizing
 * differently. Pure Java (tested on a PC).
 */
public final class HyTokenizer {
    private final Map<String, Integer> vocab = new HashMap<>();
    private final String[] idToToken;
    private final Map<String, Integer> mergeRank = new HashMap<>();
    private final List<Pattern> splits = new ArrayList<>();
    private final Map<String, Integer> added = new HashMap<>();   // content -> id
    private final Map<Integer, String> addedById = new HashMap<>();
    private final List<String> addedByLength = new ArrayList<>();   // longest first (leftmost-longest match)
    private static final char[] BYTE_TO_CHAR = new char[256];
    private static final int[] CHAR_TO_BYTE = new int[0x200];
    private final Map<String, int[]> cache = new LinkedHashMap<String, int[]>(4096, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, int[]> e) { return size() > 20000; }
    };

    static {
        // GPT-2 byte <-> unicode table (ByteLevel)
        java.util.Arrays.fill(CHAR_TO_BYTE, -1);
        int n = 0;
        for (int b = 0; b < 256; b++) {
            boolean printable = (b >= '!' && b <= '~') || (b >= 0xA1 && b <= 0xAC) || (b >= 0xAE && b <= 0xFF);
            char c = printable ? (char) b : (char) (256 + n++);
            BYTE_TO_CHAR[b] = c;
            CHAR_TO_BYTE[c] = b;
        }
    }

    @SuppressWarnings("unchecked")
    private HyTokenizer(Map<String, Object> json) throws IOException {
        Map<String, Object> model = (Map<String, Object>) json.get("model");
        require("BPE".equals(model.get("type")), "model type " + model.get("type"));
        require(!Boolean.TRUE.equals(model.get("byte_fallback")) && model.get("continuing_subword_prefix") == null
                && model.get("end_of_word_suffix") == null && model.get("dropout") == null
                && !Boolean.TRUE.equals(model.get("ignore_merges")), "BPE options");
        int max = 0;
        for (Map.Entry<String, Object> e : ((Map<String, Object>) model.get("vocab")).entrySet()) {
            int id = ((Number) e.getValue()).intValue();
            vocab.put(e.getKey(), id);
            max = Math.max(max, id);
        }
        List<Object> merges = (List<Object>) model.get("merges");
        for (int r = 0; r < merges.size(); r++) {
            Object m = merges.get(r);
            String a, b;
            if (m instanceof List) { a = (String) ((List<Object>) m).get(0); b = (String) ((List<Object>) m).get(1); }
            else { String s = (String) m; int sp = s.indexOf(' '); a = s.substring(0, sp); b = s.substring(sp + 1); }
            mergeRank.putIfAbsent(a + ' ' + b, r);
        }
        for (Object o : (List<Object>) json.get("added_tokens")) {
            Map<String, Object> t = (Map<String, Object>) o;
            require(!Boolean.TRUE.equals(t.get("lstrip")) && !Boolean.TRUE.equals(t.get("rstrip"))
                    && !Boolean.TRUE.equals(t.get("single_word")), "added token options");
            int id = ((Number) t.get("id")).intValue();
            String c = (String) t.get("content");
            added.put(c, id);
            addedById.put(id, c);
            addedByLength.add(c);
            max = Math.max(max, id);
        }
        addedByLength.sort((x, y) -> y.length() - x.length());
        idToToken = new String[max + 1];
        for (Map.Entry<String, Integer> e : vocab.entrySet()) idToToken[e.getValue()] = e.getKey();

        Map<String, Object> norm = (Map<String, Object>) json.get("normalizer");
        require(norm == null || ("Sequence".equals(norm.get("type")) && ((List<Object>) norm.get("normalizers")).isEmpty()), "normalizer");
        Map<String, Object> pre = (Map<String, Object>) json.get("pre_tokenizer");
        require("Sequence".equals(pre.get("type")), "pre_tokenizer");
        List<Object> pres = (List<Object>) pre.get("pretokenizers");
        for (int i = 0; i < pres.size(); i++) {
            Map<String, Object> p = (Map<String, Object>) pres.get(i);
            if ("Split".equals(p.get("type"))) {
                require("Isolated".equals(p.get("behavior")) && !Boolean.TRUE.equals(p.get("invert")), "split behavior");
                String regex = (String) ((Map<String, Object>) p.get("pattern")).get("Regex");
                require(regex != null, "split pattern");
                // Oniguruma (HF) \s, \d, \w are Unicode-aware; Java's only with UNICODE_CHARACTER_CLASS
                splits.add(Pattern.compile(regex, Pattern.UNICODE_CHARACTER_CLASS));
            } else {
                require("ByteLevel".equals(p.get("type")) && i == pres.size() - 1
                        && !Boolean.TRUE.equals(p.get("add_prefix_space")) && !Boolean.TRUE.equals(p.get("use_regex")), "pre_tokenizer " + p.get("type"));
            }
        }
        Map<String, Object> dec = (Map<String, Object>) json.get("decoder");
        require(dec != null && "ByteLevel".equals(dec.get("type")), "decoder");
    }

    private static void require(boolean ok, String what) throws IOException {
        if (!ok) throw new IOException("unsupported tokenizer.json: " + what);
    }

    public static HyTokenizer load(Path tokenizerJson) throws IOException {
        return parse(new String(Files.readAllBytes(tokenizerJson), StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    public static HyTokenizer parse(String json) throws IOException {
        return new HyTokenizer((Map<String, Object>) new Json(json).value());
    }

    /** Token ids of {@code text} (HF encode without added special tokens; added tokens in the text are kept). */
    public int[] encode(String text) {
        List<Integer> out = new ArrayList<>();
        int i = 0, start = 0;
        while (i < text.length()) {
            String hit = null;
            for (String a : addedByLength) {
                if (text.startsWith(a, i)) { hit = a; break; }
            }
            if (hit == null) { i++; continue; }
            encodeText(text.substring(start, i), out);
            out.add(added.get(hit));
            i += hit.length();
            start = i;
        }
        encodeText(text.substring(start), out);
        int[] r = new int[out.size()];
        for (int k = 0; k < r.length; k++) r[k] = out.get(k);
        return r;
    }

    private void encodeText(String s, List<Integer> out) {
        if (s.isEmpty()) return;
        List<String> pieces = new ArrayList<>();
        pieces.add(s);
        for (Pattern p : splits) {
            List<String> next = new ArrayList<>();
            for (String piece : pieces) {
                Matcher m = p.matcher(piece);
                int last = 0;
                while (m.find()) {
                    if (m.start() == m.end()) continue;   // HF skips empty matches
                    if (m.start() > last) next.add(piece.substring(last, m.start()));
                    next.add(piece.substring(m.start(), m.end()));
                    last = m.end();
                }
                if (last < piece.length()) next.add(piece.substring(last));
            }
            pieces = next;
        }
        for (String piece : pieces) {
            //access-ordered LinkedHashMap: get() reorders the list, so reads and writes share one lock (the
            //tokenizer can be reached from more than one thread); bpe() runs outside it
            int[] ids;
            synchronized (cache) { ids = cache.get(piece); }
            if (ids == null) {
                ids = bpe(byteLevel(piece));
                synchronized (cache) { cache.put(piece, ids); }
            }
            for (int id : ids) out.add(id);
        }
    }

    private static String byteLevel(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(b.length);
        for (byte x : b) sb.append(BYTE_TO_CHAR[x & 0xff]);
        return sb.toString();
    }

    private int[] bpe(String word) {
        Integer whole = vocab.get(word);
        List<String> sym = new ArrayList<>(word.length());
        for (int k = 0; k < word.length(); k++) sym.add(String.valueOf(word.charAt(k)));
        while (sym.size() > 1) {
            int best = -1, bestRank = Integer.MAX_VALUE;
            for (int k = 0; k + 1 < sym.size(); k++) {
                Integer r = mergeRank.get(sym.get(k) + ' ' + sym.get(k + 1));
                if (r != null && r < bestRank) { bestRank = r; best = k; }   // lowest rank, then leftmost (as HF)
            }
            if (best < 0) break;
            sym.set(best, sym.get(best) + sym.get(best + 1));
            sym.remove(best + 1);
        }
        int[] ids = new int[sym.size()];
        for (int k = 0; k < ids.length; k++) {
            Integer id = vocab.get(sym.get(k));
            ids[k] = id != null ? id : (whole != null && sym.size() == 1 ? whole : -1);   // no unk token: HF drops unknown pieces
        }
        int n = 0;
        for (int id : ids) if (id >= 0) n++;
        if (n == ids.length) return ids;
        int[] kept = new int[n];
        n = 0;
        for (int id : ids) if (id >= 0) kept[n++] = id;
        return kept;
    }

    /** HF decode with special tokens kept (ByteLevel decoder; invalid UTF-8 becomes U+FFFD). */
    public String decode(int[] ids) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int id : ids) {
            String t = addedById.get(id);
            if (t == null) t = id >= 0 && id < idToToken.length ? idToToken[id] : null;
            if (t == null) continue;
            byte[] bytes = new byte[t.length()];
            boolean all = true;
            for (int k = 0; k < t.length(); k++) {
                char c = t.charAt(k);
                int b = c < CHAR_TO_BYTE.length ? CHAR_TO_BYTE[c] : -1;
                if (b < 0) { all = false; break; }
                bytes[k] = (byte) b;
            }
            if (!all) bytes = t.getBytes(StandardCharsets.UTF_8);   // as HF: a token with any other character keeps its text
            out.write(bytes, 0, bytes.length);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    public int vocabularySize() {
        return idToToken.length;
    }

    /** A small JSON reader (objects, arrays, strings, numbers, true/false/null): no library needed. */
    static final class Json {
        private final String s;
        private int p;

        Json(String s) { this.s = s; }

        Object value() throws IOException {
            ws();
            if (p >= s.length()) throw new IOException("unexpected end of JSON");
            char c = s.charAt(p);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", p)) { p += 4; return Boolean.TRUE; }
            if (s.startsWith("false", p)) { p += 5; return Boolean.FALSE; }
            if (s.startsWith("null", p)) { p += 4; return null; }
            return number();
        }

        private void ws() {
            while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++;
        }

        private Map<String, Object> object() throws IOException {
            Map<String, Object> m = new LinkedHashMap<>();
            p++;
            ws();
            if (s.charAt(p) == '}') { p++; return m; }
            while (true) {
                ws();
                String k = string();
                ws();
                if (s.charAt(p++) != ':') throw new IOException("expected : at " + p);
                m.put(k, value());
                ws();
                char c = s.charAt(p++);
                if (c == '}') return m;
                if (c != ',') throw new IOException("expected , at " + p);
            }
        }

        private List<Object> array() throws IOException {
            List<Object> l = new ArrayList<>();
            p++;
            ws();
            if (s.charAt(p) == ']') { p++; return l; }
            while (true) {
                l.add(value());
                ws();
                char c = s.charAt(p++);
                if (c == ']') return l;
                if (c != ',') throw new IOException("expected , at " + p);
            }
        }

        private String string() throws IOException {
            if (s.charAt(p) != '"') throw new IOException("expected string at " + p);
            p++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(p++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char e = s.charAt(p++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u': sb.append((char) Integer.parseInt(s.substring(p, p + 4), 16)); p += 4; break;
                    default: throw new IOException("bad escape at " + p);
                }
            }
        }

        private Number number() throws IOException {
            int st = p;
            while (p < s.length() && "+-0123456789.eE".indexOf(s.charAt(p)) >= 0) p++;
            if (st == p) throw new IOException("unexpected character at " + p);
            String n = s.substring(st, p);
            if (n.indexOf('.') >= 0 || n.indexOf('e') >= 0 || n.indexOf('E') >= 0) return Double.parseDouble(n);
            return Long.parseLong(n);
        }
    }
}
