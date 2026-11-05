package dev.raylauncher;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import java.io.IOException;

/**
 * "Full access", opt-in: on devices whose own ADB is open without authorisation (many projectors and TV boxes), RAY
 * uses it to give itself the permissions a person would otherwise have to hunt for, and to read what apps publish on
 * the TV home, which Android below 12 shows only to privileged apps. Nothing leaves the device. Off until asked.
 */
final class RayAdb {
    static final String PREF = "ray_adb";
    private static final int PORT = 5555;
    private static final long EMPTY_MS = 5 * 60 * 1000L;
    private static final java.util.HashMap<String, Long> EMPTY = new java.util.HashMap<>();
    /** Keys go one at a time, off the phone's socket; while a press runs at most one more waits, a held arrow never queues up. */
    private static final java.util.concurrent.ThreadPoolExecutor KEYS = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.ArrayBlockingQueue<Runnable>(1), new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());

    /** Set by probe(): Full access is on but the device's ADB does not answer now. */
    static volatile boolean broken;

    private RayAdb() { }

    static boolean enabled(Context c) {
        return c.getSharedPreferences(DimService.PREFS, Context.MODE_PRIVATE).getBoolean(PREF, false);
    }

    static void disable(Context c) {
        c.getSharedPreferences(DimService.PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF, false).apply();
    }

    /** Network: never on the UI thread. Does the ADB of this device still answer? Short read timeout, the answer is local. */
    static boolean probe() {
        try { broken = !AdbClient.shell("127.0.0.1", PORT, "echo ok", 2000).contains("ok"); }
        catch (IOException closed) { broken = true; }
        return !broken;
    }

    /** Network: never on the UI thread. False when this device's ADB is closed or wants an authorisation. */
    static boolean setup(Context c) {
        String pkg = c.getPackageName();
        try {
            if (!AdbClient.shell(PORT, "echo ok").contains("ok")) return false;
            for (String p : new String[]{"WRITE_SECURE_SETTINGS", "DUMP", "READ_TV_LISTINGS"})
                AdbClient.shell(PORT, "pm grant " + pkg + " android.permission." + p);
            AdbClient.shell(PORT, "appops set " + pkg + " GET_USAGE_STATS allow");
            AdbClient.shell(PORT, "appops set " + pkg + " SYSTEM_ALERT_WINDOW allow");
            AdbClient.shell(PORT, "cmd notification allow_listener " + pkg + "/" + pkg + ".RayMediaListener");
        } catch (IOException closed) {
            RayDebug.log("full access: " + closed.getMessage());
            return false;
        }
        // only the real adbd can grant this: anything else answering on that port gets nowhere
        if (c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
        c.getSharedPreferences(DimService.PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF, true).apply();
        broken = false;
        RayDebug.log("full access on");
        return true;
    }

    /** One key through ADB. False when Full access is off. */
    static boolean key(Context c, final int keyCode) {
        if (!enabled(c) || broken) return false;
        KEYS.execute(new Runnable() {
            @Override public void run() {
                try { AdbClient.shell(PORT, "input keyevent " + keyCode); }
                catch (IOException closed) { RayDebug.log("adb key: " + closed.getMessage()); }
            }
        });
        return true;
    }

    /** The provider if it answers with rows; otherwise, with full access on, the same query through ADB. pkg may be null. */
    static Cursor query(Context c, Uri uri, String[] columns, String pkg, String sort) {
        Cursor cursor = null;
        try {
            cursor = c.getContentResolver().query(uri, columns, pkg == null ? null : "package_name=?", pkg == null ? null : new String[]{pkg}, sort);
        } catch (RuntimeException refused) { // Android below 12 throws on a filter from an app that is not privileged
            if (!enabled(c)) throw refused;
        }
        if (cursor != null && cursor.getCount() > 0 || !enabled(c)) return cursor;
        if (pkg != null && !pkg.matches("[A-Za-z0-9._]+")) return cursor;
        String asked = uri + "|" + pkg;
        synchronized (EMPTY) { // `content` starts a process: an app that published nothing is not asked again for a while
            Long until = EMPTY.get(asked);
            if (until != null && android.os.SystemClock.uptimeMillis() < until) return cursor;
        }
        try {
            String command = "content query --uri " + uri + " --projection " + android.text.TextUtils.join(":", columns)
                    + (pkg == null ? "" : " --where \"package_name='" + pkg + "'\"") + (sort == null ? "" : " --sort \"" + sort + "\"");
            MatrixCursor rows = new MatrixCursor(columns);
            for (String[] row : AdbClient.rows(AdbClient.shell(PORT, command), columns)) rows.addRow(row);
            if (rows.getCount() == 0) synchronized (EMPTY) { EMPTY.put(asked, android.os.SystemClock.uptimeMillis() + EMPTY_MS); }
            if (cursor != null) cursor.close();
            return rows;
        } catch (IOException closed) {
            return cursor;
        }
    }
}
