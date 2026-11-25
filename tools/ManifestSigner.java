import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/**
 * Model manifest signing (see docs/MODEL_SIGNING.md). Run from the repository root with JDK 15+:
 *   java tools/ManifestSigner.java genkey   -> prints a new key pair. Store the private key only as the
 *                                              CI secret MAGI_MANIFEST_SIGNING_KEY; never commit it.
 *   MAGI_MANIFEST_SIGNING_KEY=... java tools/ManifestSigner.java sign
 *                                           -> prints the signature of models/models.tsv for
 *                                              ManifestSignature.MANIFEST_SIGNATURE_B64
 * The canonical bytes must match ManifestSignature.canonical().
 */
public class ManifestSigner {
    static byte[] canonical() throws Exception {
        Map<String, String> m = new TreeMap<>();
        for (String line : Files.readAllLines(Paths.get("models/models.tsv"), StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] c = line.split("\t");
            m.put(c[0], c[1]);
        }
        StringBuilder sb = new StringBuilder("magi-models-manifest-v1\n");
        for (Map.Entry<String, String> e : m.entrySet()) sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        String cmd = args.length > 0 ? args[0] : "";
        if (cmd.equals("genkey")) {
            KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            System.out.println("PUBLIC_KEY_X509_B64 = " + Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()));
            System.out.println("PRIVATE (PKCS8, secret!) = " + Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()));
        } else if (cmd.equals("sign")) {
            String k = System.getenv("MAGI_MANIFEST_SIGNING_KEY");
            if (k == null || k.isEmpty()) { System.err.println("MAGI_MANIFEST_SIGNING_KEY is not set"); System.exit(2); }
            PrivateKey pk = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(k.trim())));
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(pk);
            s.update(canonical());
            System.out.println(Base64.getEncoder().encodeToString(s.sign()));
        } else {
            System.err.println("usage: java tools/ManifestSigner.java genkey | sign");
            System.exit(1);
        }
    }
}
