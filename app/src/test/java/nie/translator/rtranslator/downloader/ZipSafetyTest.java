package nie.translator.rtranslator.downloader;

import java.io.File;
import java.nio.file.Files;

public class ZipSafetyTest {
    public static void main(String[] args) throws Exception {
        File base = Files.createTempDirectory("zs").toFile();
        String b = base.getPath() + File.separator;
        int fail = 0;
        String[] ok = {"Madlad/a.onnx", "HY-MT/sub/dir/x.json", "./file.bin"};
        String[] bad = {"../evil", "Madlad/../../evil", "/etc/passwd", "a/../../b", "..\\..\\evil"};
        for (String e : ok) { try { ZipSafety.resolve(b, e); System.out.println("ok   accept " + e); } catch (Exception x) { fail++; System.out.println("FAIL accept " + e); } }
        for (String e : bad) { try { ZipSafety.resolve(b, e); fail++; System.out.println("FAIL reject " + e); } catch (java.io.IOException x) { System.out.println("ok   reject " + e); } }
        System.out.println(fail == 0 ? "ALL PASSED" : fail + " FAILED");
        if (fail > 0) System.exit(1);
    }
}
