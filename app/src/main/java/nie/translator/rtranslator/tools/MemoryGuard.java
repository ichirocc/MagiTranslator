package nie.translator.rtranslator.tools;

import android.app.ActivityManager;
import android.content.Context;

import java.io.File;

/**
 * MagiTranslator (spec v2 part 11): checks available memory before loading a large ONNX model, so a load that
 * would push the process into the low-memory killer is refused with ERROR_OUT_OF_MEMORY instead of crashing.
 * The estimate is the model files' size plus a margin; it is a guard, not an exact measurement.
 */
public final class MemoryGuard {
    /** extra room for ORT arenas, KV cache and the rest of the app (fraction of the model size) */
    private static final double MARGIN = 0.25;

    private MemoryGuard() {}

    /** Total size of the regular files under {@code path} (a file, or a directory at any depth up to 8 levels). */
    public static long sizeOf(File path) {
        return sizeOf(path, 0);
    }

    private static long sizeOf(File path, int depth) {
        if (path == null || !path.exists()) return 0;
        if (path.isFile()) return path.length();
        if (depth >= 8) return 0;   //a guard against symlink loops; model folders are 1-2 levels deep
        long total = 0;
        File[] children = path.listFiles();
        if (children == null) return 0;
        for (File c : children) total += sizeOf(c, depth + 1);
        return total;
    }

    /** True when loading {@code modelBytes} of model files leaves the system above its low-memory threshold. */
    public static boolean hasRoomFor(Context context, long modelBytes) {
        if (modelBytes <= 0) return true;
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return true;
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(info);
        long needed = (long) (modelBytes * (1.0 + MARGIN));
        boolean ok = info.availMem - needed > info.threshold;
        //sizes only (no text): the material for tuning MARGIN on real devices
        android.util.Log.d("memory_guard", (ok ? "allow" : "refuse") + " model=" + (modelBytes >> 20) + "MB needed=" + (needed >> 20)
                + "MB avail=" + (info.availMem >> 20) + "MB threshold=" + (info.threshold >> 20) + "MB");
        return ok;
    }
}
