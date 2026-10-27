package dev.raylauncher;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Developer aid: a ring of the last events (remote and MCP commands, keys, launches) readable from the phone and
 * over MCP, and an optional panel on the TV home with frame rate, memory, focus, last key, connections and the log.
 * Pref "ray_debug". Nothing is written to disk and nothing leaves the local network.
 */
final class RayDebug {
    static final String PREF = "ray_debug";
    private static final int KEEP = 40;
    private static final java.util.ArrayDeque<String> LOG = new java.util.ArrayDeque<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile String lastKey = "-";
    private static Panel panel;

    private RayDebug() {
    }

    static void log(String event) {
        String line = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(new java.util.Date()) + "  " + event;
        synchronized (LOG) {
            LOG.addLast(line.length() > 140 ? line.substring(0, 140) + "…" : line);
            while (LOG.size() > KEEP) LOG.removeFirst();
        }
    }

    static JSONArray logJson() {
        synchronized (LOG) { return new JSONArray(LOG); }
    }

    static void key(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() > 0) return;
        lastKey = KeyEvent.keyCodeToString(e.getKeyCode()).replace("KEYCODE_", "");
        log("key " + lastKey);
    }

    static boolean enabled(Context c) {
        return c.getSharedPreferences(DimService.PREFS, Context.MODE_PRIVATE).getBoolean(PREF, false);
    }

    static JSONObject state(Context c) {
        JSONObject o = new JSONObject();
        try {
            Runtime rt = Runtime.getRuntime();
            o.put("on", enabled(c)).put("log", logJson()).put("heapMb", (rt.totalMemory() - rt.freeMemory()) >> 20)
                    .put("heapMaxMb", rt.maxMemory() >> 20).put("lastKey", lastKey).put("mcp", RayMcp.enabled(c))
                    .put("sdk", android.os.Build.VERSION.SDK_INT);
        } catch (org.json.JSONException ignored) { }
        return o;
    }

    /** Turns the TV panel on or off, from any thread. */
    static void set(final Context c, boolean on) {
        c.getSharedPreferences(DimService.PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF, on).apply();
        log("debug panel " + (on ? "on" : "off"));
        MAIN.post(new Runnable() { @Override public void run() { RayLauncherActivity.debugChanged(); } });
        RaySocket.stateChanged();
    }

    static void toggle(Context c) { set(c, !enabled(c)); }

    static void toast(final Context c, final String text) {
        log(text);
        MAIN.post(new Runnable() { @Override public void run() { android.widget.Toast.makeText(c, text, android.widget.Toast.LENGTH_SHORT).show(); } });
    }

    /** UI thread: shows the panel over the home if the pref says so, otherwise removes it. */
    static void attach(Activity a, FrameLayout root) {
        boolean on = enabled(a);
        if (panel != null && (!on || panel.root != root)) { panel.detach(); panel = null; }
        if (on && panel == null) panel = new Panel(a, root);
    }

    static void detach() {
        if (panel != null) { panel.detach(); panel = null; }
    }

    private static final class Panel implements Choreographer.FrameCallback, Runnable {
        final FrameLayout root;
        final Activity activity;
        final TextView text;
        int frames;
        long since;
        float fps;
        boolean live = true;

        Panel(Activity a, FrameLayout root) {
            this.activity = a;
            this.root = root;
            text = new TextView(a);
            text.setTypeface(android.graphics.Typeface.MONOSPACE);
            text.setTextSize(11);
            text.setTextColor(Color.rgb(230, 232, 236));
            text.setLineSpacing(0, 1.1f);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.argb(215, 8, 10, 14));
            bg.setCornerRadius(12 * a.getResources().getDisplayMetrics().density);
            text.setBackground(bg);
            int pad = Math.round(12 * a.getResources().getDisplayMetrics().density);
            text.setPadding(pad, pad, pad, pad);
            text.setFocusable(false);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(a.getResources().getDisplayMetrics().widthPixels * .34f), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
            lp.setMargins(pad, pad, pad * 2, pad * 2);
            root.addView(text, lp);
            since = System.nanoTime();
            Choreographer.getInstance().postFrameCallback(this);
            run();
        }

        @Override public void doFrame(long now) {
            if (!live) return;
            frames++;
            if (now - since >= 1_000_000_000L) { fps = frames * 1e9f / (now - since); frames = 0; since = now; }
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override public void run() {
            if (!live) return;
            Runtime rt = Runtime.getRuntime();
            View focus = activity.getCurrentFocus();
            StringBuilder b = new StringBuilder("RAY debug\n");
            b.append(String.format(java.util.Locale.ROOT, "frames/s %.0f   heap %d/%d MB   sdk %d\n", fps,
                    (rt.totalMemory() - rt.freeMemory()) >> 20, rt.maxMemory() >> 20, android.os.Build.VERSION.SDK_INT));
            b.append("focus  ").append(focus == null ? "-" : focus.getClass().getSimpleName() + " " + (focus.getTag() == null ? "" : focus.getTag())).append('\n');
            b.append("key    ").append(lastKey).append('\n');
            String ip = RemoteServer.localIp();
            b.append("phones ").append(RaySocket.clientCount()).append("   ").append(ip == null ? "no Wi-Fi" : ip + ":" + RemoteServer.PORT)
                    .append("   mcp ").append(RayMcp.enabled(activity) ? "on" : "off").append('\n');
            b.append("skin   ").append(activity.getSharedPreferences(DimService.PREFS, Context.MODE_PRIVATE).getString("ray_skin", "default"))
                    .append(RayDayNight.isDay(activity) ? " day" : " night").append("   music ")
                    .append(RayAmbient.enabled(activity) ? (RayAmbient.isPlaying() ? "playing" : "on") : "off").append("\n\n");
            String[] lines;
            synchronized (LOG) { lines = LOG.toArray(new String[0]); }
            for (int i = Math.max(0, lines.length - 10); i < lines.length; i++) b.append(lines[i]).append('\n');
            text.setText(b.toString().trim());
            MAIN.postDelayed(this, 500);
        }

        void detach() {
            live = false;
            MAIN.removeCallbacks(this);
            root.removeView(text);
        }
    }
}
