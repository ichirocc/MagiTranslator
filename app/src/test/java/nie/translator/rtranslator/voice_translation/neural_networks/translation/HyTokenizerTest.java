package nie.translator.rtranslator.voice_translation.neural_networks.translation;

import java.util.Arrays;

/**
 * HyTokenizer on a small tokenizer.json with HY-MT's own pre-tokenizer, normalizer and decoder and a tiny
 * byte-level BPE (the 256 byte symbols and 10 merges) plus added tokens. The expected ids are those of
 * HuggingFace tokenizers 0.23 (encode of [text] as pre-tokenized, no special tokens added; decode with
 * special tokens kept), as the app called them through DJL. The full HY-MT tokenizer.json (120000 tokens)
 * was checked against tokenizers on 26107 texts in 30 languages (tools/models/README.md); it is a
 * downloaded model file, so not used here. Run: see .github/workflows/release-build.yml.
 */
public class HyTokenizerTest {
    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) failures++;
    }

    static final String PRE_TOKENIZER = "{\"type\": \"Sequence\", \"pretokenizers\": [{\"type\": \"Split\", \"pattern\": {\"Regex\": \"\\\\p{N}{1,3}\"}, \"behavior\": \"Isolated\", \"invert\": false}, {\"type\": \"Split\", \"pattern\": {\"Regex\": \"[\\u4e00-\\u9fa5\\u3040-\\u309f\\u30a0-\\u30ff]+\"}, \"behavior\": \"Isolated\", \"invert\": false}, {\"type\": \"Split\", \"pattern\": {\"Regex\": \"[!\\\"#$%&'()*+,\\\\-./:;<=>?@\\\\[\\\\\\\\\\\\]^_`{|}~][A-Za-z]+|[^\\r\\n\\\\p{L}\\\\p{P}\\\\p{S}]?[\\\\p{L}\\\\p{M}]+| ?[\\\\p{P}\\\\p{S}]+[\\r\\n]*|\\\\s*[\\r\\n]+|\\\\s+(?!\\\\S)|\\\\s+\"}, \"behavior\": \"Isolated\", \"invert\": false}, {\"type\": \"ByteLevel\", \"add_prefix_space\": false, \"trim_offsets\": true, \"use_regex\": false}]}";
    static final String DECODER = "{\"type\": \"ByteLevel\", \"add_prefix_space\": true, \"trim_offsets\": true, \"use_regex\": true}";
    static final String[][] MERGES = {{"\u0120", "t"}, {"h", "e"}, {"\u0120t", "he"}, {"\u00e3", "\u0123"}, {"l", "l"}, {"e", "ll"}, {"h", "ell"}, {"hell", "o"}, {"1", "2"}, {"12", "3"}};

    static String tinyTokenizerJson() {
        // GPT-2 byte symbols in byte order get ids 0..255, then one id per merge result
        StringBuilder vocab = new StringBuilder("{");
        int n = 0, id = 0;
        java.util.List<String> seen = new java.util.ArrayList<>();
        for (int b = 0; b < 256; b++) {
            boolean printable = (b >= '!' && b <= '~') || (b >= 0xA1 && b <= 0xAC) || (b >= 0xAE && b <= 0xFF);
            if (!printable) continue;
            seen.add(String.valueOf((char) b));
        }
        for (int b = 0; b < 256; b++) {
            boolean printable = (b >= '!' && b <= '~') || (b >= 0xA1 && b <= 0xAC) || (b >= 0xAE && b <= 0xFF);
            if (printable) continue;
            seen.add(String.valueOf((char) (256 + n++)));
        }
        for (String[] m : MERGES) if (!seen.contains(m[0] + m[1])) seen.add(m[0] + m[1]);
        for (String s : seen) {
            if (id > 0) vocab.append(',');
            vocab.append(json(s)).append(':').append(id++);
        }
        vocab.append('}');
        StringBuilder merges = new StringBuilder("[");
        for (int i = 0; i < MERGES.length; i++) merges.append(i > 0 ? "," : "").append('[').append(json(MERGES[i][0])).append(',').append(json(MERGES[i][1])).append(']');
        merges.append(']');
        String[] added = {"<\uff5chy_begin\u2581of\u2581sentence\uff5c>", "<\uff5chy_Assistant\uff5c>", "<x>"};
        StringBuilder at = new StringBuilder("[");
        for (int i = 0; i < added.length; i++) {
            at.append(i > 0 ? "," : "").append("{\"id\":").append(id + i).append(",\"content\":").append(json(added[i]))
              .append(",\"single_word\":false,\"lstrip\":false,\"rstrip\":false,\"normalized\":false,\"special\":").append(i < 2).append('}');
        }
        at.append(']');
        return "{\"added_tokens\":" + at + ",\"normalizer\":{\"type\":\"Sequence\",\"normalizers\":[]},\"pre_tokenizer\":" + PRE_TOKENIZER
                + ",\"decoder\":" + DECODER + ",\"model\":{\"type\":\"BPE\",\"dropout\":null,\"unk_token\":null,"
                + "\"continuing_subword_prefix\":null,\"end_of_word_suffix\":null,\"fuse_unk\":false,\"byte_fallback\":false,"
                + "\"ignore_merges\":false,\"vocab\":" + vocab + ",\"merges\":" + merges + "}}";
    }

    static String json(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20 || c > 0x7e) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    public static void main(String[] args) throws Exception {
        HyTokenizer t = HyTokenizer.parse(tinyTokenizerJson());
        String[] texts = {"hello the world", "<\uff5chy_begin\u2581of\u2581sentence\uff5c>\u3053\u3093\u306b\u3061\u306f12345<\uff5chy_Assistant\uff5c>", "\u0928\u092e\u0938\u094d\u0924\u0947, the 1234!\n\n<x>ok", "  a\r\nb", "don't-stop"};
        int[][] expected = {{257, 260, 78, 258, 220, 86, 78, 81, 75, 67}, {266, 259, 241, 159, 224, 241, 259, 104, 259, 94, 259, 107, 265, 19, 20, 267}, {156, 97, 101, 156, 97, 106, 156, 97, 116, 156, 98, 235, 156, 97, 97, 156, 98, 229, 11, 258, 220, 265, 19, 0, 198, 198, 268, 78, 74}, {220, 220, 64, 201, 198, 65}, {67, 78, 77, 6, 83, 12, 82, 83, 78, 79}};
        for (int i = 0; i < texts.length; i++) {
            int[] got = t.encode(texts[i]);
            check(Arrays.equals(got, expected[i]), "encode " + texts[i].replace("\n", "\\n").replace("\r", "\\r") + " -> " + Arrays.toString(got));
            check(t.decode(expected[i]).equals(texts[i]), "decode round trip " + i);
        }
        check(t.decode(new int[]{156, 97}).equals("\ufffd"), "incomplete UTF-8 -> U+FFFD (as HF's from_utf8_lossy)");
        check(t.encode("").length == 0, "empty text");
        boolean refused = false;
        try { HyTokenizer.parse(tinyTokenizerJson().replace("\"type\":\"BPE\"", "\"type\":\"WordPiece\"")); } catch (java.io.IOException e) { refused = true; }
        check(refused, "an unsupported tokenizer.json is refused, not tokenized differently");
        System.out.println(failures == 0 ? "HyTokenizerTest: all passed" : "HyTokenizerTest: " + failures + " FAILED");
        if (failures != 0) System.exit(1);
    }
}
