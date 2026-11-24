package nie.translator.rtranslator.downloader;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Where MagiTranslator downloads its models from, and the SHA-256 every downloaded file must have.
 *
 * All files are served from this repository's own release (tag {@link #RELEASE_TAG}), not from the
 * upstream author's repositories, so a file changed or removed upstream cannot reach the app.
 * The release is filled by .github/workflows/mirror-models.yml, which copies the upstream files once
 * and refuses any file whose hash differs from models/models.tsv. Keep {@link #SHA256} and
 * models/models.tsv identical (ModelSourcesTest checks it).
 *
 * Pure Java (no Android dependency) so that it can be unit-tested on a PC.
 */
public final class ModelSources {
    private ModelSources() {}

    public static final String RELEASE_TAG = "models-v1";
    public static final String BASE_URL = "https://github.com/ichirocc/MagiTranslator/releases/download/" + RELEASE_TAG + "/";

    /** Download URL of a model file (by file name, e.g. "Madlad.zip"). */
    public static String url(String fileName) {
        return BASE_URL + fileName;
    }

    // file name -> lowercase hex SHA-256 (see models/models.tsv)
    private static final Map<String, String> SHA256;
    static {
        Map<String, String> m = new HashMap<>();
        m.put("Whisper_initializer.onnx", "dbf3281f2399b46527afbf0401a91174596222b79fa4e6646b0f2d49fb397d85");
        m.put("Whisper_encoder.onnx", "0171be36c908738326f70c8a7f58049ba4786a07d7ea79c0e0f9e26550cdfb50");
        m.put("Whisper_decoder.onnx", "704de24c96a812779f3d0d7ed2a47a37b070f44abb0d92ca5e19d31c727718df");
        m.put("Whisper_cache_initializer.onnx", "6e303b22e38ea5a2f6c1e4c29ddbf11b047f77d8e7a99841182e709ee6b2d9d1");
        m.put("Whisper_cache_initializer_batch.onnx", "6da66ede8430347e314a7341fa05481778ac945d5b028106a8340361fdc99986");
        m.put("Whisper_detokenizer.onnx", "713447d18dc0f4cae0759f616aba98a909100185941f80844bf954ef12888e12");
        m.put("NLLB_cache_initializer.onnx", "4150b3cba7db9487a94e3b693f79d0f89772e75d80bffd0e60b06de1b574e3d9");
        m.put("NLLB_decoder.onnx", "86028825495fc34085c7ebb80fb5f0eaa18ac6600f9d4dbc55bc731cc6981159");
        m.put("NLLB_embed_and_lm_head.onnx", "2745a6f932237b8bb0c35ded54987ea3fc455b696215d48d32271c28c785dd50");
        m.put("NLLB_encoder.onnx", "9b25d62f4875e5087ffde53c2794308c07f60f4c77670fc5bbfe308a1ef88cd9");
        m.put("TranslationDictionaries.zip", "250d00e1ae22a6b1e7ea999a5b1dc3746d37e0ef1eba6b884895dc5e7b458477");
        m.put("Tatoeba.zip", "6bb6f6054ee4870c8f6bc9de43fedfe7b62a7e368c50dc765f0a7a28512f9dd5");
        m.put("Mozilla_ja.zip", "7dc89da8423afde983f101e01d14c9739b81dc73bd7750f33ea71ee375cd7e9f");
        m.put("Mozilla_vi.zip", "c6c2dc34448337c3ee61e5d0d10140771323a812b8bc7ea161619c221f3b751f");
        m.put("Madlad.zip", "ec3183e6106182938fe095d8e48057e2412ded278b560c2dc818c72cfedd682d");
        m.put("HY-MT.zip", "15d5dd9b0634fa0daac27fd50a06ec46c940eaadac2409d51361d3aa0ce3aff8");
        SHA256 = Collections.unmodifiableMap(m);
    }

    public static Map<String, String> expectedHashes() {
        return SHA256;
    }

    /** Expected SHA-256 of a model file, or null when the file is not a known model file. */
    public static String expectedSha256(String fileName) {
        return SHA256.get(fileName);
    }

    /**
     * True when the file has the expected hash. A name without a pinned hash is rejected: every file
     * the app downloads must be listed, so a new download cannot silently skip the check.
     */
    public static boolean verify(File file, String fileName) throws IOException {
        String expected = expectedSha256(fileName);
        return expected != null && expected.equals(sha256(file));
    }

    public static String sha256(File file) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        byte[] buf = new byte[1 << 20];
        try (InputStream in = new FileInputStream(file)) {
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
