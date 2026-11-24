package nie.translator.rtranslator.tools;

import android.content.Context;
import android.content.pm.ApplicationInfo;

/**
 * Conversation content (ASR text, source text, translation candidates) must never reach Logcat in
 * release builds. Call {@link #init(Context)} once at startup; then {@link #text(String)} returns the
 * text itself only in debuggable builds and a length-only placeholder otherwise.
 */
public final class PrivacyLog {
    private static volatile boolean debuggable = false;

    private PrivacyLog() {}

    public static void init(Context context) {
        debuggable = (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    public static boolean isDebuggable() { return debuggable; }

    public static String text(String s) {
        if (debuggable) return s;
        return s == null ? "<null>" : "<redacted " + s.length() + " chars>";
    }
}
