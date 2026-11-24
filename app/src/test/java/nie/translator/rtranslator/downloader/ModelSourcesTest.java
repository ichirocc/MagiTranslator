package nie.translator.rtranslator.downloader;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain-Java checks (run from the repository root):
 *  - models/models.tsv and ModelSources.SHA256 are identical;
 *  - every model file the app downloads (ModelSources.url("...") in Global.java, plus the Mozilla
 *    focus languages) has a pinned hash;
 *  - no download URL points to the upstream author's repositories;
 *  - verify() accepts the right hash and rejects a wrong one or an unknown name.
 */
public class ModelSourcesTest {
    static int failures = 0;

    static void check(boolean ok, String what) {
        if (ok) System.out.println("ok   " + what);
        else { failures++; System.out.println("FAIL " + what); }
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> tsv = new HashMap<>();
        for (String line : Files.readAllLines(Paths.get("models/models.tsv"), StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] c = line.split("\t");
            check(c.length == 4, "4 columns: " + c[0]);
            check(c[1].matches("[0-9a-f]{64}"), "hex sha256: " + c[0]);
            check(!c[3].contains("ichirocc"), "upstream URL is the source, not the mirror: " + c[0]);
            tsv.put(c[0], c[1]);
        }
        check(tsv.equals(ModelSources.expectedHashes()), "models/models.tsv == ModelSources.SHA256 (" + tsv.size() + " files)");

        String global = new String(Files.readAllBytes(Paths.get("app/src/main/java/nie/translator/rtranslator/Global.java")), StandardCharsets.UTF_8);
        check(!global.contains("github.com/niedev"), "Global.java has no upstream URL");
        Matcher m = Pattern.compile("ModelSources\\.url\\(\"([^\"]+)\"\\)").matcher(global);
        int n = 0;
        while (m.find()) {
            n++;
            check(ModelSources.expectedSha256(m.group(1)) != null, "pinned: " + m.group(1));
        }
        check(n > 0, "Global.java uses ModelSources.url");
        for (String lang : new String[]{"ja", "vi"}) {
            check(ModelSources.expectedSha256("Mozilla_" + lang + ".zip") != null, "pinned: Mozilla_" + lang + ".zip");
        }
        check(ModelSources.url("Madlad.zip").startsWith("https://github.com/ichirocc/MagiTranslator/releases/download/"), "own release URL");

        File tmp = File.createTempFile("model", ".bin");
        tmp.deleteOnExit();
        Files.write(tmp.toPath(), "abc".getBytes(StandardCharsets.US_ASCII));
        check("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad".equals(ModelSources.sha256(tmp)), "sha256(\"abc\")");
        String anyName = ModelSources.expectedHashes().keySet().iterator().next();
        check(!ModelSources.verify(tmp, anyName), "wrong content rejected");
        check(!ModelSources.verify(tmp, "unknown.zip"), "unknown name rejected");

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }
}
