package nie.translator.rtranslator.downloader;

import java.io.File;
import java.io.IOException;

/** Zip Slip protection: an archive entry must resolve inside the extraction directory. */
public final class ZipSafety {
    private ZipSafety() {}

    /**
     * Returns the destination file for {@code entryName} under {@code baseDir}, or throws when the entry
     * is absolute or escapes the directory (e.g. "../../shared_prefs/x.xml").
     */
    public static File resolve(String baseDir, String entryName) throws IOException {
        if (entryName == null || entryName.isEmpty()) throw new IOException("empty zip entry name");
        String normalized = entryName.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
            throw new IOException("absolute zip entry rejected: " + entryName);
        }
        File base = new File(baseDir).getCanonicalFile();
        File dest = new File(base, normalized).getCanonicalFile();
        String basePath = base.getPath();
        String destPath = dest.getPath();
        if (!destPath.equals(basePath) && !destPath.startsWith(basePath + File.separator)) {
            throw new IOException("zip entry outside target dir rejected: " + entryName);
        }
        return dest;
    }
}
