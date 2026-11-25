package nie.translator.rtranslator.downloader;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Plain-Java checks for ModelStore (staged, verified, atomic install + rollback) and
 * ManifestSignature (Ed25519; unsigned = hash-only). Run from the repository root.
 * The success path of install() needs a file matching a pinned hash; the smallest pinned file is
 * 71 KB of model data that is not in the repository, so the success path is checked only when
 * tools/testdata/Whisper_initializer.onnx exists (skipped otherwise).
 */
public class ModelStoreTest {
    static int failures = 0;

    static void check(boolean ok, String what) {
        if (ok) System.out.println("ok   " + what);
        else { failures++; System.out.println("FAIL " + what); }
    }

    static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    static void write(File f, String s) throws Exception {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
    }

    static String sha(String content) throws Exception {
        File f = File.createTempFile("mstest", ".bin");
        write(f, content);
        return ModelSources.sha256(f);
    }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("modelstore").toFile();
        String d = dir.getAbsolutePath();
        String name = "Whisper_initializer.onnx";
        File staged = ModelStore.stagingFile(d, name);
        File active = new File(dir, name);

        check(ModelStore.install(d, name, 0) == ModelStore.Result.MISSING, "missing staged file");

        // wrong content: refused, staged file deleted, active name never created, failure recorded
        write(staged, "not the model");
        check(ModelStore.install(d, name, 0) == ModelStore.Result.HASH_MISMATCH, "wrong content refused");
        check(!staged.exists(), "staged file deleted after refusal");
        check(!active.exists(), "active file not created by a wrong download");
        check(ModelStore.isFailed(d, name, sha("not the model")), "failure recorded with its sha256");

        write(staged, "x");
        check(ModelStore.install(d, name, 71378) == ModelStore.Result.SIZE_MISMATCH, "size mismatch refused before hashing");
        check(!staged.exists(), "staged file deleted after size mismatch");

        write(ModelStore.stagingFile(d, "unknown.bin"), "x");
        check(ModelStore.install(d, "unknown.bin", 0) == ModelStore.Result.UNKNOWN_FILE, "unpinned name refused");

        // a refused update leaves the active file untouched
        write(active, "old good");
        write(staged, "evil");
        ModelStore.install(d, name, 0);
        check(read(active).equals("old good"), "active file kept on refused update");

        // rollback: previous copy restored, current revision recorded as failed
        write(new File(new File(dir, ModelStore.PREVIOUS_DIR), name), "previous");
        check(ModelStore.canRollback(d, name), "rollback available");
        check(ModelStore.rollback(d, name), "rollback done");
        check(read(active).equals("previous"), "previous content active");
        check(ModelStore.isFailed(d, name, sha("old good")), "rolled-back revision recorded as failed");
        check(!ModelStore.rollback(d, name), "no second rollback without a previous copy");

        // success path with an explicit pin: atomic move, previous copy kept when disk allows
        String nm = "pinned.bin";
        File act2 = new File(dir, nm);
        write(act2, "v1");
        write(ModelStore.stagingFile(d, nm), "v2");
        ModelStore.Result r2 = ModelStore.install(d, nm, 2, sha("v2"));
        check(r2 == ModelStore.Result.INSTALLED || r2 == ModelStore.Result.INSTALLED_WITH_PREVIOUS, "verified file installed: " + r2);
        check(read(act2).equals("v2") && !ModelStore.stagingFile(d, nm).exists(), "active = new content, staging empty");
        if (r2 == ModelStore.Result.INSTALLED_WITH_PREVIOUS) {
            check(ModelStore.rollback(d, nm) && read(act2).equals("v1"), "rollback to v1 after install");
            check(ModelStore.isFailed(d, nm, sha("v2")), "v2 recorded as failed after rollback");
        } else {
            System.out.println("skip rollback-after-install (less than 2 GB free here)");
        }

        // success path with the real pinned file (only when present)
        File real = new File("tools/testdata/" + name);
        if (real.isFile()) {
            Files.copy(real.toPath(), staged.toPath());
            ModelStore.Result r = ModelStore.install(d, name, real.length());
            check(r == ModelStore.Result.INSTALLED || r == ModelStore.Result.INSTALLED_WITH_PREVIOUS, "pinned file installed: " + r);
            check(!staged.exists() && ModelSources.verify(active, name), "active file is the verified one");
        } else {
            System.out.println("skip success path (tools/testdata/" + name + " not present)");
        }

        // --- ManifestSignature
        Map<String, String> h = ModelSources.expectedHashes();
        check(ManifestSignature.check(h, "", "") == ManifestSignature.Status.UNSIGNED, "unsigned = hash-only");
        check(ManifestSignature.allowsDownload(ManifestSignature.check()), "this build allows downloads (placeholder key)");
        check(ManifestSignature.check(h, "abc", "") == ManifestSignature.Status.INVALID, "half-configured is invalid");
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(kp.getPrivate());
        s.update(ManifestSignature.canonical(h));
        String sig = Base64.getEncoder().encodeToString(s.sign());
        String pub = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
        check(ManifestSignature.check(h, pub, sig) == ManifestSignature.Status.VALID, "valid signature");
        Map<String, String> tampered = new HashMap<>(h);
        tampered.put("Madlad.zip", "0000000000000000000000000000000000000000000000000000000000000000");
        check(ManifestSignature.check(tampered, pub, sig) == ManifestSignature.Status.INVALID, "tampered manifest rejected");
        check(!ManifestSignature.allowsDownload(ManifestSignature.Status.INVALID), "invalid blocks downloads");
        check(ManifestSignature.check(h, pub, "!!notbase64") == ManifestSignature.Status.INVALID, "garbage signature rejected");
        String c = new String(ManifestSignature.canonical(new TreeMap<>(h)), StandardCharsets.UTF_8);
        check(c.startsWith("magi-models-manifest-v1\n") && c.split("\n").length == h.size() + 1, "canonical format");

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        if (failures != 0) System.exit(1);
    }
}
