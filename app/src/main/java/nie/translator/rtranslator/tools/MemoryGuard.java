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

    /** Total size of the regular files under {@code path} (a file, or a directory up to 2 levels deep). */
    public static long sizeOf(File path) {
        if (path == null || !path.exists()) return 0;
        if (path.isFile()) return path.length();
        long total = 0;
        File[] children = path.listFiles();
        if (children == null) return 0;
        for (File c : children) {
            if (c.isFile()) {
                total += c.length();
            } else if (c.isDirectory()) {
                File[] grand = c.listFiles();
                if (grand != null) for (File g : grand) if (g.isFile()) total += g.length();
            }
        }
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
        return info.availMem - needed > info.threshold;
    }
}
