package nie.translator.rtranslator.downloader;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/**
 * Ed25519 signature over the model manifest (SPEC v2 part 3 §4.3 / §17.5).
 *
 * The SHA-256 pins in {@link ModelSources} detect a corrupted or swapped file; the signature states
 * that the manifest itself was approved by the holder of the release signing key. The private key is
 * never in the repository: tools/ManifestSigner.java signs with it from the CI secret
 * MAGI_MANIFEST_SIGNING_KEY (see docs/MODEL_SIGNING.md).
 *
 * Current state: no key has been generated yet, so {@link #PUBLIC_KEY_X509_B64} and
 * {@link #MANIFEST_SIGNATURE_B64} are empty and {@link #check()} returns {@link Status#UNSIGNED}: the
 * app keeps today's behaviour (hash-only). Once both constants are filled, a mismatch returns
 * {@link Status#INVALID} and the downloader refuses to start.
 *
 * Note: the manifest is compiled into the APK, so today the signature mainly guards the build pipeline
 * (models.tsv edited without the key); it becomes the real trust anchor once a manifest is fetched
 * from the network. Before filling the constants, confirm on the device that "Ed25519" is available
 * (Conscrypt on Android 13+); {@link Status#UNSUPPORTED} is returned otherwise.
 *
 * Pure Java so it can be tested on a PC.
 */
public final class ManifestSignature {
    private ManifestSignature() {}

    /** PLACEHOLDER: X.509 (SubjectPublicKeyInfo) Base64 of the Ed25519 public key. Empty = unsigned. */
    public static final String PUBLIC_KEY_X509_B64 = "";
    /** PLACEHOLDER: Base64 Ed25519 signature of {@link #canonical(Map)} over ModelSources hashes. Empty = unsigned. */
    public static final String MANIFEST_SIGNATURE_B64 = "";

    public enum Status { UNSIGNED, VALID, INVALID, UNSUPPORTED }

    /**
     * Canonical bytes that are signed: one line "name\tsha256\n" per file, sorted by name. Sizes and
     * URLs are not included (the URL is derived from the release tag, the size check is separate).
     */
    public static byte[] canonical(Map<String, String> hashes) {
        StringBuilder sb = new StringBuilder("magi-models-manifest-v1\n");
        for (Map.Entry<String, String> e : new TreeMap<>(hashes).entrySet()) {
            sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Status of the manifest compiled into this build. */
    public static Status check() {
        return check(ModelSources.expectedHashes(), PUBLIC_KEY_X509_B64, MANIFEST_SIGNATURE_B64);
    }

    public static Status check(Map<String, String> hashes, String publicKeyB64, String signatureB64) {
        boolean noKey = publicKeyB64 == null || publicKeyB64.isEmpty();
        boolean noSig = signatureB64 == null || signatureB64.isEmpty();
        if (noKey && noSig) return Status.UNSIGNED;
        if (noKey || noSig) return Status.INVALID;   // half-configured build: refuse rather than silently skip
        try {
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyB64)));
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(key);
            s.update(canonical(hashes));
            return s.verify(Base64.getDecoder().decode(signatureB64)) ? Status.VALID : Status.INVALID;
        } catch (java.security.NoSuchAlgorithmException e) {
            return Status.UNSUPPORTED;
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return Status.INVALID;
        }
    }

    /** Downloads may start unless the manifest is signed and the signature is wrong. */
    public static boolean allowsDownload(Status s) {
        return s != Status.INVALID;
    }
}
