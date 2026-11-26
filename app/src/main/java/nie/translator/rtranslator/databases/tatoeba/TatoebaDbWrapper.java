package nie.translator.rtranslator.databases.tatoeba;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * Read-only access to the Tatoeba database with Android's built-in SQLite (android.database.sqlite).
 *
 * It replaces sqlite4java, whose native libraries (libsqlite4java.so and its own libc++_shared.so) are
 * aligned to 4 KB pages and cannot be loaded as they are on devices with 16 KB pages (Android 15+).
 * sqlite4java was used to stream the large "links" BLOB without Android's CursorWindow, which only holds
 * about 2 MB per row: {@link BlobInputStream} reads the BLOB in slices with SQL substr() instead, so no
 * single row returned by a cursor is larger than {@link BlobInputStream#CHUNK}.
 */
public class TatoebaDbWrapper {
    File dbFile;

    public TatoebaDbWrapper(String dbPath) {
        dbFile = new File(dbPath);
    }

    private SQLiteDatabase open() {
        return SQLiteDatabase.openDatabase(dbFile.getPath(), null,
                SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
    }

    @Nullable
    public String getSentence(int id) {
        String out = null;
        try (SQLiteDatabase database = open();
             Cursor c = database.rawQuery("SELECT text FROM sentences WHERE id = " + id, null)) {   //an int: safe to inline
            if (c.moveToFirst()) {
                out = c.getString(0); // first column = "text"
            }
        } catch (RuntimeException e) {   //android.database.SQLException and friends are unchecked
            e.printStackTrace();
        }
        return out;
    }

    @NonNull
    public String[] getSentences(int[] ids) {
        String[] out = new String[ids.length];
        if (ids.length == 0) return out;

        // ints are inlined (safe, and compared as integers whatever the column affinity)
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) list.append(",");
            list.append(ids[i]);
        }
        String sql = "SELECT id, text FROM sentences WHERE id IN (" + list + ")";

        try (SQLiteDatabase database = open();
             Cursor c = database.rawQuery(sql, null)) {
            // IN(...) order is not guaranteed: map id -> text, then reorder like the input
            java.util.HashMap<Integer, String> byId = new java.util.HashMap<>();
            while (c.moveToNext()) {
                byId.put(c.getInt(0), c.getString(1));
            }
            for (int i = 0; i < ids.length; i++) {
                out[i] = byId.get(ids[i]); // null if missing
            }
        } catch (RuntimeException e) {
            e.printStackTrace();
            out = new String[0];
        }
        return out;
    }

    @Nullable
    public LinksData.DataMap getLinkData(String srcLang, String tgtLang) {
        try (SQLiteDatabase database = open()) {
            long rowid = -1, length = 0;
            try (Cursor c = database.rawQuery("SELECT rowid, length(data) FROM links WHERE srcLang = ? AND tgtLang = ? LIMIT 1",
                    new String[]{srcLang, tgtLang})) {
                if (c.moveToFirst()) {
                    rowid = c.getLong(0);
                    length = c.getLong(1);
                }
            }
            if (rowid < 0) return null;
            // Key point: stream the BLOB in slices (no row bigger than the CursorWindow)
            try (InputStream in = new BlobInputStream(database, rowid, length)) {
                return LinksData.DataMap.parseFrom(in);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /** The "data" BLOB of one row of "links", read lazily in slices of {@link #CHUNK} bytes with substr(). */
    static final class BlobInputStream extends InputStream {
        static final int CHUNK = 1 << 20;   // 1 MB: well below the ~2 MB CursorWindow
        private final SQLiteDatabase db;
        private final long rowid, length;
        private long next = 0;              // offset (0-based) of the next slice to fetch
        private byte[] buf = new byte[0];
        private int pos = 0;

        BlobInputStream(SQLiteDatabase db, long rowid, long length) {
            this.db = db; this.rowid = rowid; this.length = length;
        }

        private boolean fill() throws IOException {
            if (pos < buf.length) return true;
            if (next >= length) return false;
            int n = (int) Math.min(CHUNK, length - next);
            // substr() on a BLOB counts bytes and is 1-based
            try (Cursor c = db.rawQuery("SELECT substr(data, " + (next + 1) + ", " + n + ") FROM links WHERE rowid = " + rowid, null)) {
                if (!c.moveToFirst()) throw new IOException("links row " + rowid + " disappeared");
                byte[] b = c.getBlob(0);
                if (b == null || b.length != n) throw new IOException("short read at " + next + ": " + (b == null ? 0 : b.length) + " of " + n);
                buf = b; pos = 0; next += n;
                return true;
            } catch (RuntimeException e) {
                throw new IOException(e);
            }
        }

        @Override public int read() throws IOException {
            return fill() ? (buf[pos++] & 0xFF) : -1;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (!fill()) return -1;
            int k = Math.min(len, buf.length - pos);
            System.arraycopy(buf, pos, b, off, k);
            pos += k;
            return k;
        }

        @Override public int available() {
            return buf.length - pos;
        }
    }
}
