package nie.translator.rtranslator.plamo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * PLaMo 3 Translate model files on the device, and the user's acceptance of the PLaMo Community License.
 * Pure Java (no Android dependency) so that it can be tested on a PC.
 * <p>
 * The model is "Built with PLaMo" (Preferred Networks, Inc.): it is downloaded from the publisher's Hugging
 * Face repository (pinned revision and SHA-256 in ModelSources), only after the user has accepted the
 * license, and is used only inside this app's translation screens.
 */
public final class PlamoModels {
    private PlamoModels() {}

    public enum Variant {
        /** 0.8B: about 1 GB of RAM; measured best balance (PC: about 1.3 s per Japanese sentence) */
        SMALL("plamo-3-translate-0.8b-Q4_K_M.gguf", 573_126_848L),
        /** 2B: more accurate ja->ne, about 3x slower, about 3.3 GB of RAM */
        LARGE("plamo-3-translate-2b-Q4_K_M.gguf", 1_807_203_040L);

        public final String fileName;
        public final long sizeBytes;
        Variant(String fileName, long sizeBytes) { this.fileName = fileName; this.sizeBytes = sizeBytes; }
    }

    public static final String DIR = "Translation/PLaMo";
    public static final String LICENSE_URL = "https://huggingface.co/pfnet/plamo-3-translate-0.8b/blob/main/LICENSE/en";
    /** date of the license text the user accepted (the PLaMo Community License as revised on 2026-11-17) */
    public static final String LICENSE_VERSION = "2026-11-17";
    static final String ACCEPTED_FILE = "LICENSE_ACCEPTED";
    static final String VARIANT_FILE = "VARIANT";

    public static File dir(File filesDir) { return new File(filesDir, DIR); }

    public static File file(File filesDir, Variant v) { return new File(dir(filesDir), v.fileName); }

    /** Installed means present with the expected size; the hash was checked when it was installed. */
    public static boolean isInstalled(File filesDir, Variant v) {
        File f = file(filesDir, v);
        return f.isFile() && f.length() == v.sizeBytes;
    }

    public static boolean isLicenseAccepted(File filesDir) {
        File f = new File(dir(filesDir), ACCEPTED_FILE);
        try {
            return f.isFile() && new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim().equals(LICENSE_VERSION);
        } catch (IOException e) {
            return false;
        }
    }

    public static void acceptLicense(File filesDir) throws IOException {
        write(new File(dir(filesDir), ACCEPTED_FILE), LICENSE_VERSION);
    }

    public static void revokeLicense(File filesDir) {
        //noinspection ResultOfMethodCallIgnored
        new File(dir(filesDir), ACCEPTED_FILE).delete();
    }

    /** The variant the user chose (SMALL by default). */
    public static Variant chosen(File filesDir) {
        File f = new File(dir(filesDir), VARIANT_FILE);
        try {
            if (f.isFile()) return Variant.valueOf(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim());
        } catch (IOException | IllegalArgumentException ignored) {
        }
        return Variant.SMALL;
    }

    public static void choose(File filesDir, Variant v) throws IOException {
        write(new File(dir(filesDir), VARIANT_FILE), v.name());
    }

    /**
     * The model file to use: the chosen variant, else the other installed one; null when PLaMo must not be
     * used (license not accepted, nothing installed).
     */
    public static File usable(File filesDir) {
        if (!isLicenseAccepted(filesDir)) return null;
        Variant c = chosen(filesDir);
        if (isInstalled(filesDir, c)) return file(filesDir, c);
        for (Variant v : Variant.values()) if (isInstalled(filesDir, v)) return file(filesDir, v);
        return null;
    }

    private static void write(File f, String text) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot create " + parent);
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(text.getBytes(StandardCharsets.UTF_8)); }
    }
}
