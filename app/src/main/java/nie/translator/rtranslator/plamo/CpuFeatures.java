package nie.translator.rtranslator.plamo;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Locale;

/**
 * libplamo_engine.so is compiled for Armv8.6-A with dotprod and i8mm (the int8 matrix instructions that
 * llama.cpp's repacked Q4 kernels use; Tensor G3/G4/G5 and other Armv9 phones have them). On a CPU without
 * them the library would crash with SIGILL, so the features are checked before it is loaded.
 */
final class CpuFeatures {
    private CpuFeatures() {}

    static boolean canRunEngine() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!arch.contains("aarch64") && !arch.contains("arm64")) return true;   // host builds (tests) are not Arm-specific
        String features = cpuFeatures();
        return hasAll(features, "asimddp", "i8mm");
    }

    /** "Features" line of /proc/cpuinfo (same on every core of a phone); empty when unreadable. */
    static String cpuFeatures() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("Features")) {
                    int c = line.indexOf(':');
                    return c < 0 ? "" : line.substring(c + 1);
                }
            }
        } catch (IOException ignored) {
        }
        return "";
    }

    static boolean hasAll(String features, String... names) {
        String padded = " " + features.trim().replaceAll("\\s+", " ") + " ";
        for (String n : names) if (!padded.contains(" " + n + " ")) return false;
        return true;
    }
}
