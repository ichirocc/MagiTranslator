package nie.translator.rtranslator.voice_translation.neural_networks.voice;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * WhisperDetokenizer on a small ONNX file written here with the same layout as Whisper_detokenizer.onnx
 * (a Cast node, then a BpeDecoder node with id_vocab, byte_decoder, added_tokens, all_special_ids and
 * skip_special_tokens). The real file was checked against onnxruntime-extensions' BpeDecoder on every single
 * token and 2003 + 5000 random sequences (identical output; tools/models/README.md); it is a downloaded
 * model file, so not used here. Run: see .github/workflows/release-build.yml.
 */
public class WhisperDetokenizerTest {
    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) failures++;
    }

    // ---- protobuf writing ----
    static void varint(ByteArrayOutputStream o, long v) {
        while ((v & ~0x7fL) != 0) { o.write((int) ((v & 0x7f) | 0x80)); v >>>= 7; }
        o.write((int) v);
    }

    static void bytesField(ByteArrayOutputStream o, int field, byte[] b) {
        varint(o, ((long) field << 3) | 2);
        varint(o, b.length);
        o.write(b, 0, b.length);
    }

    static void intField(ByteArrayOutputStream o, int field, long v) {
        varint(o, (long) field << 3);
        varint(o, v);
    }

    static byte[] attrString(String name, String value) {
        ByteArrayOutputStream a = new ByteArrayOutputStream();
        bytesField(a, 1, name.getBytes(StandardCharsets.UTF_8));
        bytesField(a, 4, value.getBytes(StandardCharsets.UTF_8));
        intField(a, 20, 3);   // type STRING
        return a.toByteArray();
    }

    static byte[] attrInt(String name, long value) {
        ByteArrayOutputStream a = new ByteArrayOutputStream();
        bytesField(a, 1, name.getBytes(StandardCharsets.UTF_8));
        intField(a, 3, value);
        intField(a, 20, 2);   // type INT
        return a.toByteArray();
    }

    static byte[] node(String opType, byte[]... attrs) {
        ByteArrayOutputStream n = new ByteArrayOutputStream();
        bytesField(n, 1, "in".getBytes(StandardCharsets.UTF_8));
        bytesField(n, 2, "out".getBytes(StandardCharsets.UTF_8));
        bytesField(n, 4, opType.getBytes(StandardCharsets.UTF_8));
        for (byte[] a : attrs) bytesField(n, 5, a);
        bytesField(n, 7, "ai.onnx.contrib".getBytes(StandardCharsets.UTF_8));
        return n.toByteArray();
    }

    /** GPT-2 byte table, "unicode code\tbyte" per line as in the BpeDecoder attribute. */
    static char[] byteToChar = new char[256];

    static String byteDecoder() {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (int b = 0; b < 256; b++) {
            boolean printable = (b >= '!' && b <= '~') || (b >= 0xA1 && b <= 0xAC) || (b >= 0xAE && b <= 0xFF);
            byteToChar[b] = printable ? (char) b : (char) (256 + n++);
        }
        for (int b = 0; b < 256; b++) sb.append((int) byteToChar[b]).append('\t').append(b).append('\n');
        return sb.toString();
    }

    static String bytesAsVocab(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(byteToChar[b & 0xff]);
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        String bd = byteDecoder();
        // ids: 0 "Hello", 1 " world", 2 "日" (3 bytes), 3 first byte of "本", 4 its last two bytes, 5 NUL, 6 "!"
        byte[] hon = "本".getBytes(StandardCharsets.UTF_8);
        String[] vocab = {
            bytesAsVocab("Hello".getBytes(StandardCharsets.UTF_8)),
            bytesAsVocab(" world".getBytes(StandardCharsets.UTF_8)),
            bytesAsVocab("日".getBytes(StandardCharsets.UTF_8)),
            bytesAsVocab(new byte[]{hon[0]}),
            bytesAsVocab(new byte[]{hon[1], hon[2]}),
            bytesAsVocab(new byte[]{0}),
            bytesAsVocab("!".getBytes(StandardCharsets.UTF_8)),
        };
        String idVocab = String.join("\n", vocab);
        String added = "7\t<|endoftext|>\n8\t<|ja|>\n9\t<|0.00|>\n";
        String special = "7\n8\n";

        ByteArrayOutputStream graph = new ByteArrayOutputStream();
        bytesField(graph, 1, node("Cast", attrInt("to", 7)));
        bytesField(graph, 1, node("BpeDecoder", attrString("added_tokens", added), attrString("all_special_ids", special),
                attrString("byte_decoder", bd), attrString("id_vocab", idVocab), attrInt("skip_special_tokens", 1)));
        ByteArrayOutputStream model = new ByteArrayOutputStream();
        intField(model, 1, 9);   // ir_version
        bytesField(model, 2, "test".getBytes(StandardCharsets.UTF_8));
        bytesField(model, 7, graph.toByteArray());
        ByteArrayOutputStream opset = new ByteArrayOutputStream();
        intField(opset, 2, 17);
        bytesField(model, 8, opset.toByteArray());

        WhisperDetokenizer d = WhisperDetokenizer.fromModel(model.toByteArray());
        check(d.vocabularySize() == 7, "vocabulary read from the BpeDecoder node (" + d.vocabularySize() + ")");
        check(d.decode(new int[]{0, 1}).equals("Hello world"), "byte-level tokens -> text");
        check(d.decode(new int[]{8, 0, 1, 7}).equals("Hello world"), "special ids skipped");
        check(d.decode(new int[]{9, 0}).equals("<|0.00|>Hello"), "other added tokens (timestamps) kept as text");
        check(d.decode(new int[]{2, 3, 4}).equals("日本"), "a character split over two tokens");
        check(d.decode(new int[]{2, 3}).equals("日�"), "incomplete UTF-8 at the end -> U+FFFD");
        check(d.decode(new int[]{0, 5, 6}).equals("Hello"), "cut at a NUL byte (BpeDecoder's string ended there)");
        check(d.decode(new int[]{0, 99, -1, 6}).equals("Hello!"), "ids outside the vocabulary ignored");
        check(d.decode(new int[]{}).isEmpty(), "no ids -> empty text");
        boolean refused = false;
        try { WhisperDetokenizer.fromModel(new byte[]{8, 1}); } catch (java.io.IOException e) { refused = true; }
        check(refused, "a file without a BpeDecoder node is refused");
        System.out.println(failures == 0 ? "WhisperDetokenizerTest: all passed" : "WhisperDetokenizerTest: " + failures + " FAILED");
        if (failures != 0) System.exit(1);
    }
}
