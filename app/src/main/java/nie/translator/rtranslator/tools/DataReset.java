package nie.translator.rtranslator.tools;

import java.io.File;

/** Deletes everything the app downloaded or imported into its private files/ and cache/ folders
 *  (AI models, extracted packs, Nepali Whisper, user dictionaries, staging / rollback leftovers).
 *  Settings and the small database live outside files/ and are kept. Pure Java (testable). */
public final class DataReset {
    private DataReset() {}

    public static long sizeOf(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long s = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) s += sizeOf(k);
        return s;
    }

    /** Deletes the contents of each root (not the root itself). Returns the bytes freed. */
    public static long clear(File... roots) {
        long freed = 0;
        for (File root : roots) {
            File[] kids = root == null ? null : root.listFiles();
            if (kids == null) continue;
            for (File k : kids) { long s = sizeOf(k); if (delete(k)) freed += s; }
        }
        return freed;
    }

    private static boolean delete(File f) {
        File[] kids = f.isDirectory() ? f.listFiles() : null;
        if (kids != null) for (File k : kids) delete(k);
        return f.delete() || !f.exists();
    }

    public static String human(long bytes) {
        if (bytes >= 1L << 30) return String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (double) (1L << 30));
        if (bytes >= 1L << 20) return String.format(java.util.Locale.ROOT, "%.0f MB", bytes / (double) (1L << 20));
        return (bytes / 1024) + " KB";
    }
}
