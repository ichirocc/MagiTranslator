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

    // ---- personal LoRA adapter (trained on the user's own dictionary, see tools/llama/finetune) ----
    // Device-only like the user dictionaries: it is derived from the user's private material, so it is
    // imported from a file the user picks and never downloaded, uploaded or bundled.
    static final String ADAPTER_FILE = "personal_lora.gguf";
    public static final long ADAPTER_MAX_BYTES = 256L << 20;

    /** The imported adapter, or null. */
    public static File adapter(File filesDir) {
        File f = new File(dir(filesDir), ADAPTER_FILE);
        return f.isFile() ? f : null;
    }

    /** True when the stream starts like a GGUF file (magic "GGUF"); the stream must support mark/reset. */
    public static boolean looksLikeGguf(java.io.InputStream in) throws IOException {
        in.mark(8);
        byte[] m = new byte[4];
        int n = 0, r;
        while (n < 4 && (r = in.read(m, n, 4 - n)) > 0) n += r;
        in.reset();
        return n == 4 && m[0] == 'G' && m[1] == 'G' && m[2] == 'U' && m[3] == 'F';
    }

    /**
     * Stores a LoRA adapter (GGUF) as the personal adapter: copied to a temporary file, then moved into place,
     * so a failed copy never replaces a working adapter. Whether it fits the model is checked when the engine
     * applies it (llama.cpp compares every tensor shape); an adapter that does not fit is not used.
     * @return the size in bytes
     */
    public static long importAdapter(java.io.InputStream in, File filesDir) throws IOException {
        if (!in.markSupported()) in = new java.io.BufferedInputStream(in);
        if (!looksLikeGguf(in)) throw new IOException("not a GGUF file");
        File d = dir(filesDir);
        if (!d.isDirectory() && !d.mkdirs()) throw new IOException("cannot create " + d);
        File tmp = new File(d, ADAPTER_FILE + ".part");
        long total = 0;
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) {
                total += r;
                if (total > ADAPTER_MAX_BYTES) throw new IOException("adapter too large");
                o.write(buf, 0, r);
            }
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        }
        Files.move(tmp.toPath(), new File(d, ADAPTER_FILE).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        return total;
    }

    public static void deleteAdapter(File filesDir) {
        //noinspection ResultOfMethodCallIgnored
        new File(dir(filesDir), ADAPTER_FILE).delete();
    }

    private static void write(File f, String text) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot create " + parent);
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(text.getBytes(StandardCharsets.UTF_8)); }
    }
}
