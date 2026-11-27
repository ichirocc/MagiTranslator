package nie.translator.rtranslator.downloader;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Atomic install of downloaded model files (SPEC v2 part 3 §17.3-17.6).
 *
 * Layout inside a model destination directory {@code dir}:
 * <pre>
 *   dir/&lt;name&gt;              the active file (only ever a fully verified one)
 *   dir/.staging/&lt;name&gt;     download target; PRDownloader writes here (itself via &lt;name&gt;.temp)
 *   dir/.previous/&lt;name&gt;    the file that was active before the last install (rollback), if disk allowed
 *   dir/.failed.tsv          name \t sha256 of every file that failed verification or was rolled back
 * </pre>
 * A file reaches {@code dir/<name>} only by a rename from staging after its size and SHA-256 matched,
 * so a partial or wrong download is never visible under the name the app loads.
 *
 * Disk limit: the full model set is ~5.6 GB and Madlad.zip alone ~1.7 GB. Keeping a previous copy
 * doubles that file's footprint, so it is kept only when the free space after the install stays above
 * {@link #PREVIOUS_KEEP_MARGIN_BYTES}; otherwise the old file is deleted and rollback is unavailable
 * for that file (install still atomic). Zip files are unpacked and then deleted by the downloader, so
 * for them the rollback copy is the zip, not the unpacked model.
 *
 * Pure Java (no Android dependency) so it can be tested on a PC.
 */
public final class ModelStore {
    private ModelStore() {}

    public static final String STAGING_DIR = ".staging";
    public static final String PREVIOUS_DIR = ".previous";
    public static final String FAILED_FILE = ".failed.tsv";
    /** keep a rollback copy only if at least this much stays free afterwards (2 GB) */
    public static final long PREVIOUS_KEEP_MARGIN_BYTES = 2L << 30;

    public enum Result { INSTALLED, INSTALLED_WITH_PREVIOUS, SIZE_MISMATCH, HASH_MISMATCH, UNKNOWN_FILE, MISSING, IO_ERROR, CANCELLED }

    public static File stagingDir(String dir) {
        return new File(dir, STAGING_DIR);
    }

    public static File stagingFile(String dir, String name) {
        return new File(stagingDir(dir), name);
    }

    /**
     * Verify the staged file (expected size when {@code expectedSizeBytes > 0}, pinned SHA-256) and move it
     * into place atomically. On any failure the staged file is deleted, the failure is recorded and the
     * active file is left untouched.
     */
    public static Result install(String dir, String name, long expectedSizeBytes) {
        return install(dir, name, expectedSizeBytes, ModelSources.expectedSha256(name));
    }

    /** Same as {@link #install(String, String, long)} with an explicit pin (package-private, for tests). */
    static Result install(String dir, String name, long expectedSizeBytes, String expected) {
        return install(dir, name, expectedSizeBytes, expected, null);
    }

    /**
     * Asked once the staged file is verified, right before it is moved into place. {@link #lock()} is held while it
     * decides and while the file moves, so a caller that cancels while holding the same lock either
     * runs before the decision (the install is refused: {@link Result#CANCELLED}, staged file deleted) or after
     * the move (the cancel sees the installed file). Hashing, the long part, runs outside the lock.
     */
    public interface Gate { Object lock(); boolean proceed(); }

    /** {@link #install(String, String, long)} that also asks {@code gate} before moving the file into place. */
    public static Result install(String dir, String name, long expectedSizeBytes, Gate gate) {
        return install(dir, name, expectedSizeBytes, ModelSources.expectedSha256(name), gate);
    }

    static Result install(String dir, String name, long expectedSizeBytes, String expected, Gate gate) {
        File staged = stagingFile(dir, name);
        if (!staged.isFile()) return Result.MISSING;
        if (expected == null) { discard(staged); return Result.UNKNOWN_FILE; }
        if (expectedSizeBytes > 0 && staged.length() != expectedSizeBytes) {
            recordFailure(dir, name, "size:" + staged.length());
            discard(staged);
            return Result.SIZE_MISMATCH;
        }
        String actual;
        try {
            actual = ModelSources.sha256(staged);
        } catch (IOException e) {
            discard(staged);
            return Result.IO_ERROR;
        }
        if (!expected.equals(actual)) {
            recordFailure(dir, name, actual);
            discard(staged);
            return Result.HASH_MISMATCH;
        }
        if (gate == null) return moveIntoPlace(dir, name, staged);
        synchronized (gate.lock()) {
            if (!gate.proceed()) { discard(staged); return Result.CANCELLED; }
            return moveIntoPlace(dir, name, staged);
        }
    }

    private static Result moveIntoPlace(String dir, String name, File staged) {
        File active = new File(dir, name);
        boolean keptPrevious = false;
        try {
            if (active.exists()) {
                File prevDir = new File(dir, PREVIOUS_DIR);
                long freeAfter = new File(dir).getUsableSpace();   // the staged copy is already on disk, the move costs nothing
                if (freeAfter > PREVIOUS_KEEP_MARGIN_BYTES && (prevDir.isDirectory() || prevDir.mkdirs())) {
                    move(active.toPath(), new File(prevDir, name).toPath());
                    keptPrevious = true;
                }
            }
            move(staged.toPath(), active.toPath());   // replaces the active file if it was not kept
        } catch (IOException e) {
            // put the previous file back so the app keeps a working model
            if (keptPrevious && !active.exists()) {
                try { move(new File(new File(dir, PREVIOUS_DIR), name).toPath(), active.toPath()); } catch (IOException ignored) {}
            }
            return Result.IO_ERROR;
        }
        return keptPrevious ? Result.INSTALLED_WITH_PREVIOUS : Result.INSTALLED;
    }

    /**
     * Deletes everything kept for {@code name} in {@code dir}: the active file, its rollback copy in
     * .previous/ and a partial download in .staging/ (also PRDownloader's &lt;name&gt;.temp). The failure
     * record stays (a revision refused once is not taken again). Returns the bytes freed.
     */
    public static long deleteAll(String dir, String name) {
        long freed = 0;
        File staging = stagingDir(dir);
        for (File f : new File[]{new File(dir, name), new File(new File(dir, PREVIOUS_DIR), name),
                new File(staging, name), new File(staging, name + ".temp")}) {
            long size = f.isFile() ? f.length() : 0;
            if (f.isFile() && f.delete()) freed += size;
        }
        return freed;
    }

    /** True when a rollback copy of {@code name} exists. */
    public static boolean canRollback(String dir, String name) {
        return new File(new File(dir, PREVIOUS_DIR), name).isFile();
    }

    /**
     * Restore the previous file of {@code name} and record the current one as failed, so the same
     * revision is not re-activated automatically (§17.6). Returns false when no previous copy exists.
     */
    public static boolean rollback(String dir, String name) {
        File prev = new File(new File(dir, PREVIOUS_DIR), name);
        if (!prev.isFile()) return false;
        File active = new File(dir, name);
        try {
            if (active.isFile()) recordFailure(dir, name, ModelSources.sha256(active));
            move(prev.toPath(), active.toPath());
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** True when this exact content of {@code name} was recorded as failed before. */
    public static boolean isFailed(String dir, String name, String sha256) {
        File f = new File(dir, FAILED_FILE);
        if (!f.isFile()) return false;
        try {
            List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            return lines.contains(name + "\t" + sha256);
        } catch (IOException e) {
            return false;
        }
    }

    static void recordFailure(String dir, String name, String what) {
        try {
            Files.write(new File(dir, FAILED_FILE).toPath(), (name + "\t" + what + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // best effort: the install itself already refused the file
        }
    }

    private static void discard(File f) {
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // same directory tree on one filesystem in practice; fall back to a plain replace
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
