package nie.translator.rtranslator.downloader;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import nie.translator.rtranslator.jane.DictionaryFiles;

/**
 * Stores the jane dictionaries (see {@link DictionaryFiles}) in files/jane/, outside the APK.
 *  - {@link #downloadAll}: from this repository's release "jane-dictionaries" (HTTPS);
 *  - {@link #importFile}: from a TSV file the user picks; the type comes from its first line.
 * A file is written only after it passed {@link DictionaryFiles#check}, through a temporary file and a
 * rename, so the translator never sees a partial or invalid dictionary. Without files the translator
 * simply runs without dictionaries.
 */
public final class DictionaryStore {
    private DictionaryStore() {}

    public static final String RELEASE_TAG = "jane-dictionaries";
    public static final String BASE_URL = "https://github.com/ichirocc/MagiTranslator/releases/download/" + RELEASE_TAG + "/";
    private static final String TAG = "dictionaries";

    public interface Callback {
        /** Called on the main thread. {@code errors} is empty when everything succeeded. */
        void onDone(List<String> saved, List<String> errors);
    }

    public static File dir(Context context) {
        File d = new File(context.getFilesDir(), "jane");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    public static File file(Context context, DictionaryFiles.Kind kind) {
        return new File(dir(context), kind.fileName);
    }

    /** Text of a stored dictionary if it exists and is valid, otherwise null. */
    @Nullable
    public static String readValid(Context context, DictionaryFiles.Kind kind) {
        File f = file(context, kind);
        if (!f.isFile() || f.length() > DictionaryFiles.MAX_BYTES) return null;
        try {
            String text = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            DictionaryFiles.Check c = DictionaryFiles.check(text);
            if (c.ok() && c.kind == kind) return text;
            Log.w(TAG, kind.fileName + " ignored: " + c);
        } catch (IOException e) {
            Log.w(TAG, kind.fileName + " unreadable", e);
        }
        return null;
    }

    /** Changes whenever a stored dictionary changes, so the translator knows when to reload. */
    public static String stamp(Context context) {
        StringBuilder sb = new StringBuilder();
        for (DictionaryFiles.Kind k : DictionaryFiles.Kind.values()) {
            File f = file(context, k);
            sb.append(k.tag).append('=').append(f.isFile() ? f.lastModified() + ":" + f.length() : "none").append(';');
        }
        return sb.toString();
    }

    /** One line per dictionary for the settings screen, e.g. "phrases: 55". */
    public static String status(Context context) {
        StringBuilder sb = new StringBuilder();
        for (DictionaryFiles.Kind k : DictionaryFiles.Kind.values()) {
            String text = readValid(context, k);
            if (sb.length() > 0) sb.append('\n');
            sb.append(k.tag).append(": ").append(text == null ? "-" : String.valueOf(DictionaryFiles.check(text).entries));
        }
        return sb.toString();
    }

    public static boolean anyMissing(Context context) {
        for (DictionaryFiles.Kind k : DictionaryFiles.Kind.values()) if (readValid(context, k) == null) return true;
        return false;
    }

    /** Downloads the dictionaries that are missing (at app start; silent, failures only logged). */
    public static void ensureDownloaded(Context context) {
        Context app = context.getApplicationContext();
        if (!anyMissing(app)) return;
        downloadAll(app, (saved, errors) -> Log.i(TAG, "initial download: saved=" + saved + " errors=" + errors));
    }

    /** Downloads all dictionaries (Settings -> update). Each file is checked and replaced on its own. */
    public static void downloadAll(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            List<String> saved = new ArrayList<>(), errors = new ArrayList<>();
            for (DictionaryFiles.Kind k : DictionaryFiles.Kind.values()) {
                try {
                    String text = download(BASE_URL + k.fileName);
                    DictionaryFiles.Check c = DictionaryFiles.check(text);
                    if (!c.ok()) throw new IOException(c.error);
                    if (c.kind != k) throw new IOException("wrong dictionary type " + c.kind.tag);
                    save(app, k, text);
                    saved.add(k.tag + " (" + c.entries + ")");
                } catch (Exception e) {
                    Log.w(TAG, "download " + k.fileName + " failed", e);
                    errors.add(k.tag + ": " + e.getMessage());
                }
            }
            new Handler(Looper.getMainLooper()).post(() -> callback.onDone(saved, errors));
        }, "jane-dictionaries-download").start();
    }

    /** Imports a dictionary chosen by the user; the type is read from its first line. */
    public static void importFile(Context context, Uri uri, Callback callback) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            List<String> saved = new ArrayList<>(), errors = new ArrayList<>();
            try (InputStream in = app.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("cannot open the file");
                String text = new String(readLimited(in), StandardCharsets.UTF_8);
                DictionaryFiles.Check c = DictionaryFiles.check(text);
                if (!c.ok()) throw new IOException(c.error);
                save(app, c.kind, text);
                saved.add(c.kind.tag + " (" + c.entries + ")");
            } catch (Exception e) {
                Log.w(TAG, "import failed", e);
                errors.add(e.getMessage());
            }
            new Handler(Looper.getMainLooper()).post(() -> callback.onDone(saved, errors));
        }, "jane-dictionaries-import").start();
    }

    private static String download(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(30000);
        con.setInstanceFollowRedirects(true);   //GitHub release assets redirect to another https host
        try {
            int code = con.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);
            try (InputStream in = con.getInputStream()) {
                return new String(readLimited(in), StandardCharsets.UTF_8);
            }
        } finally {
            con.disconnect();
        }
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > DictionaryFiles.MAX_BYTES) throw new IOException("file too large");
        }
        return out.toByteArray();
    }

    private static synchronized void save(Context context, DictionaryFiles.Kind kind, String text) throws IOException {
        File target = file(context, kind);
        File tmp = new File(target.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("cannot save " + kind.fileName);
        }
    }
}
