package dev.raylauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The TV home row as the phone shows it ("On TV", nothing playing): the same order as the row on the TV (pinned, then
 * by last use, Watch Next mixed in by RayHomeRow), plus the artwork of those entries and the icon of an app. Cached for
 * a few seconds: the snapshot is built often.
 */
final class RayHomeFeed {
    private static final long ROW_TTL_MS = 10000, WATCH_TTL_MS = 60000;
    private static List<RayWatchHistory.Entry> watch = Collections.emptyList();
    private static long watchAt;
    private static boolean loading;
    private static JSONArray row = new JSONArray();
    private static long rowAt;
    private static List<String> used = Collections.emptyList();

    private RayHomeFeed() { }

    /** @param apps the snapshot's "apps": [{package, name}] */
    static synchronized JSONArray row(final Context context, JSONArray apps) {
        long now = android.os.SystemClock.uptimeMillis();
        loadWatch(context, now);
        if (rowAt != 0 && now - rowAt < ROW_TTL_MS) return row;
        rowAt = now;
        SharedPreferences prefs = context.getSharedPreferences(DimService.PREFS, Context.MODE_PRIVATE);
        ArrayList<String> installed = new ArrayList<>();
        java.util.HashMap<String, String> names = new java.util.HashMap<>();
        for (int i = 0; apps != null && i < apps.length(); i++) {
            JSONObject app = apps.optJSONObject(i);
            if (app == null) continue;
            installed.add(app.optString("package"));
            names.put(app.optString("package"), app.optString("name"));
        }
        LinkedHashSet<String> order = new LinkedHashSet<>();
        for (String p : prefs.getString("ray_pinned", "").split("\\n")) if (!p.isEmpty()) order.add(p);
        order.addAll(used);
        for (String p : prefs.getString("ray_recent", "").split("\\n")) if (!p.isEmpty()) order.add(p);
        ArrayList<String> known = new ArrayList<>();
        for (String p : order) if (installed.contains(p)) known.add(p);
        for (String p : installed) if (known.size() < 5 && !known.contains(p)) known.add(p); // a new TV: never an empty home
        // as on the TV: what you were watching first, then the apps
        ArrayList<Object> ordered = new ArrayList<>(watch.subList(0, Math.min(10, watch.size())));
        for (String p : known) if (ordered.size() < 22) ordered.add(p);
        JSONArray out = new JSONArray();
        try {
            for (Object o : ordered) {
                if (o instanceof String) {
                    out.put(new JSONObject().put("k", "app").put("package", o).put("name", names.get(o)));
                } else {
                    RayWatchHistory.Entry e = (RayWatchHistory.Entry) o;
                    String label = names.get(e.packageName);
                    out.put(new JSONObject().put("k", "watch").put("index", watch.indexOf(e)).put("title", e.title)
                            .put("package", e.packageName).put("name", label == null ? "" : label)
                            .put("progress", e.progress).put("minutes", e.minutesLeft));
                }
            }
        } catch (JSONException ignored) { }
        row = out;
        return out;
    }

    private static void loadWatch(final Context context, long now) {
        if (loading || watchAt != 0 && now - watchAt < WATCH_TTL_MS) return;
        loading = true;
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    List<RayWatchHistory.Entry> found = RayDemo.on(context) ? RayDemo.watch() : RayWatchHistory.load(context);
                    List<String> recent = usedRecently(context); // a binder call: never on the thread that builds the snapshot
                    synchronized (RayHomeFeed.class) {
                        boolean changed = found.size() != watch.size() || !recent.equals(used);
                        for (int i = 0; !changed && i < found.size(); i++) changed = !found.get(i).title.equals(watch.get(i).title);
                        watch = found;
                        used = recent;
                        if (changed) rowAt = 0;
                    }
                } catch (RuntimeException | OutOfMemoryError failed) {
                    RayDebug.log("home feed: " + failed);
                } finally {
                    synchronized (RayHomeFeed.class) {
                        watchAt = android.os.SystemClock.uptimeMillis();
                        loading = false;
                    }
                }
                RaySocket.stateChanged();
            }
        }, "ray-home-feed");
        thread.setDaemon(true);
        thread.start();
    }

    /** Packages used in the last 30 days, most recent first; empty without usage access (same rule as the TV row). */
    private static List<String> usedRecently(Context context) {
        ArrayList<String> out = new ArrayList<>();
        try {
            android.app.usage.UsageStatsManager u = (android.app.usage.UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            List<android.app.usage.UsageStats> stats = u.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_BEST, now - 30L * 86400000L, now);
            Collections.sort(stats, new Comparator<android.app.usage.UsageStats>() {
                @Override public int compare(android.app.usage.UsageStats a, android.app.usage.UsageStats b) { return Long.compare(b.getLastTimeUsed(), a.getLastTimeUsed()); }
            });
            for (android.app.usage.UsageStats st : stats)
                if (st.getTotalTimeInForeground() > 0 && !st.getPackageName().equals(context.getPackageName()) && !out.contains(st.getPackageName())) out.add(st.getPackageName());
        } catch (RuntimeException unavailable) { }
        return out;
    }

    /** Artwork of the n-th Watch Next entry of the last row, JPEG; null when unknown. */
    static synchronized byte[] watchArt(int index) {
        if (index < 0 || index >= watch.size() || watch.get(index).artwork == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        watch.get(index).artwork.compress(Bitmap.CompressFormat.JPEG, 80, out);
        return out.toByteArray();
    }

    /** The title decides: the list may have changed since the phone drew it. */
    static synchronized boolean openWatch(Context context, int index, String title) {
        if (title != null && !title.isEmpty()) {
            for (RayWatchHistory.Entry e : watch) if (title.equals(e.title)) return RayWatchHistory.launch(context, e);
            return false;
        }
        return index >= 0 && index < watch.size() && RayWatchHistory.launch(context, watch.get(index));
    }

    /** Launcher icon of an installed app as a 144 px PNG; null when the package is unknown. */
    static byte[] icon(Context context, String pkg) {
        try {
            PackageManager pm = context.getPackageManager();
            Drawable d = pm.getApplicationIcon(pkg);
            Bitmap b = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, 144, 144);
            d.draw(c);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
            b.recycle();
            return out.toByteArray();
        } catch (PackageManager.NameNotFoundException | RuntimeException unknown) {
            return null;
        }
    }
}
