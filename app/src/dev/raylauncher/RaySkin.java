package dev.raylauncher;

import android.content.Context;
import android.graphics.Color;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A home-screen skin (format v1, docs/SKIN_FORMAT.md): a folder with skin.json, the frame artwork drawn by
 * RayFluidBackdrop and where each control sits on it. Skins live in assets/skins/&lt;id&gt; (built in) or in
 * getExternalFilesDir("skins")/&lt;id&gt; (user). Rectangles are {x, y, width, height} as fractions of the screen.
 */
final class RaySkin {
    static final String DEFAULT = "essenziale";
    private static final String TAG = "RaySkin";
    private static final String[] NAV = {"home", "video", "games", "music", "apps"};
    private static final String[] ACTIONS = {"search", "apps", "sources", "audio", "pair", "settings"};
    private static List<RaySkin> builtIn;
    /** The only files a skin zip may carry, optionally under one top folder; everything else is ignored. */
    private static final Pattern ENTRY = Pattern.compile("(?:[^/]+/)?(skin\\.json|frame(?:-day|-night)?\\.(?:webp|png)|remote(?:-day|-night)?\\.webp|preview\\.webp|icons(?:-day|-night)?/[a-z]{1,16}\\.png)");
    private static final int FILE_MAX = 8 << 20, ZIP_MAX = 24 << 20;

    final String id;
    /** Folder: "skins/&lt;id&gt;" in assets, or an absolute path for a user skin. */
    final String dir;
    final boolean user;
    String nameIt, nameEn, author;
    int accent;
    /** Text colour of the weather window (0 = the skin's normal text). */
    int weatherColor;
    /** Path of the frame artwork (see open), or null for a frameless skin. */
    String shell;
    /** true: the artwork is drawn as it is (no tint, no animation) and accent is fixed. */
    boolean raw;
    /** "user", "none" or a file of the folder. Parsed only. */
    String wallpaper = "none";
    boolean hasRemote, hasPreview;
    /** Phone background (remote.webp or the variant's) and the folder of the themed icons. */
    String remoteFile = "remote.webp", iconDir = "icons";
    /** Day and night variants (skin.json "variants"), fully parsed; null = the base art in that mode. Only set on the base skin. */
    RaySkin day, night;
    /** Frameless skins: parsed only, drawn by the frameless renderer. */
    float cardRadius = 12;
    String panel = "glass";
    int textColor = Color.WHITE, mutedColor = Color.rgb(160, 175, 190);
    /** Frameless ("stock") skins: drawn by code, layout fixed in stockLayout (docs/SKIN_FORMAT.md). */
    boolean stock;
    boolean light;
    float dim = .78f, focusScale = 1.08f, ringDp = 2.5f;
    int ring = Color.WHITE, shadow = 0x8c000000, panelFill = 0x1affffff, panelStrong = 0x29ffffff, ok = 0xff22a55b,
            btnBg = 0xfff4f6fa, btnFg = 0xff10131a, btn2Bg = 0x24ffffff, btn2Fg = 0xfff4f6fa;
    /** wallpaperDefault and wallpaperFx of skin.json, read by RayWallpaper. */
    JSONObject wpDefault, wpFx;
    /** The big window of the frame (hero), or null when the skin has none. */
    float[] hero;
    /** Inset (fraction of hero w, h) that keeps hero text inside round or organic holes. */
    float[] heroInset = {0, 0};
    float[] logo, device, clock, mode, pair, resume, discover, connect;
    /** Optional weather window (temperature, glyph, city), or null. */
    float[] weather;
    String[] nav;
    float[][] navRects;
    String[] actions;
    float[][] actionRects;
    /** Left padding per action (fraction of the screen), to clear artwork drawn in the frame. */
    float[] actionPad;
    float[][] rowTitles;
    float[][][] rows;

    private RaySkin(String id, String dir, boolean user) {
        this.id = id;
        this.dir = dir;
        this.user = user;
    }

    /** The skin as it looks by day or by night: its variant, or itself when it has none for that mode. */
    RaySkin forMode(boolean isDay) {
        RaySkin v = isDay ? day : night;
        return v != null ? v : this;
    }

    String title(boolean italian) {
        String a = italian ? nameIt : nameEn, b = italian ? nameEn : nameIt;
        return a != null ? a : b != null ? b : id;
    }

    /** Opens a file of a skin folder, or an absolute path (user skins) / asset path as given by `dir`. */
    static InputStream openPath(Context c, String path) throws IOException {
        return path.startsWith("/") ? new FileInputStream(path) : c.getAssets().open(path);
    }

    InputStream open(Context c, String name) throws IOException { return openPath(c, dir + "/" + name); }

    private boolean has(Context c, String name) {
        try { open(c, name).close(); return true; } catch (IOException e) { return false; }
    }

    static byte[] bytes(InputStream in, int max) throws IOException {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                b.write(buf, 0, n);
                if (b.size() > max) throw new IOException("too large");
            }
            return b.toByteArray();
        } finally { in.close(); }
    }

    static File userDir(Context c) { return c.getExternalFilesDir("skins"); }

    /** Built-in skins then valid user skins (user ids that collide with an earlier one are skipped). */
    static synchronized List<RaySkin> list(Context c) {
        if (builtIn == null) {
            builtIn = new ArrayList<RaySkin>();
            try {
                String[] names = c.getAssets().list("skins");
                if (names != null) for (String n : names) add(c, builtIn, "skins/" + n, n, false);
            } catch (IOException e) { Log.w(TAG, "assets/skins unreadable", e); }
        }
        List<RaySkin> out = new ArrayList<RaySkin>(builtIn);
        File[] dirs = userDir(c) == null ? null : userDir(c).listFiles();
        if (dirs != null) {
            Arrays.sort(dirs);
            for (File d : dirs) if (d.isDirectory() && !d.getName().startsWith(".")) add(c, out, d.getAbsolutePath(), d.getName(), true);
        }
        return out;
    }

    private static void add(Context c, List<RaySkin> out, String dir, String name, boolean user) {
        RaySkin s = parse(c, dir, name, user);
        if (s == null) return;
        for (RaySkin o : out) if (o.id.equals(s.id)) { Log.w(TAG, "skin id taken, skipped: " + dir); return; }
        out.add(s);
    }

    /** Unzips a skin package into the user folder (only allowed names, size caps), validates it and replaces a user skin of the same id. Returns the id. */
    static synchronized String install(Context c, InputStream in) throws IOException {
        File root = userDir(c);
        if (root == null) throw new IOException("no storage");
        File tmp = new File(root, ".tmp-" + System.nanoTime());
        try {
            ZipInputStream z = new ZipInputStream(in);
            ZipEntry e;
            long total = 0;
            byte[] buf = new byte[8192];
            while ((e = z.getNextEntry()) != null) {
                Matcher m = ENTRY.matcher(e.getName().replace('\\', '/'));
                if (e.isDirectory() || !m.matches()) continue;
                File f = new File(tmp, m.group(1)); // the name comes from the whitelist, never from the zip path
                f.getParentFile().mkdirs();
                long size = 0;
                FileOutputStream out = new FileOutputStream(f);
                try {
                    int n;
                    while ((n = z.read(buf)) > 0) {
                        size += n;
                        if (size > FILE_MAX || total + size > ZIP_MAX) throw new IOException("skin too large");
                        out.write(buf, 0, n);
                    }
                } finally { out.close(); }
                total += size;
            }
            RaySkin s = parse(c, tmp.getPath(), null, true);
            if (s == null) throw new IOException("invalid skin.json or missing files");
            RaySkin old = get(c, s.id);
            if (old != null && !old.user) throw new IOException("id taken by a built-in skin");
            File dest = new File(root, s.id);
            wipe(dest);
            if (!tmp.renameTo(dest)) throw new IOException("cannot store the skin");
            RayIcons.clear();
            return s.id;
        } finally { wipe(tmp); }
    }

    private static void wipe(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) wipe(k);
        f.delete();
    }

    /** The skin with this id, or null. */
    static RaySkin get(Context c, String id) {
        for (RaySkin s : list(c)) if (s.id.equals(id)) return s;
        return null;
    }

    /** Parses and validates the skin in `dir`; null (with a log line) when it is not a valid v1 skin. */
    static RaySkin parse(Context c, String dir, String folder, boolean user) {
        try {
            JSONObject j = new JSONObject(new String(bytes(openPath(c, dir + "/skin.json"), 65536), "UTF-8"));
            if (j.optInt("format") != 1) throw new IllegalArgumentException("format must be 1");
            String id = j.optString("id", "");
            if (!id.matches("[a-z0-9_-]{1,32}") || id.equals("match") || (folder != null && !id.equals(folder)))
                throw new IllegalArgumentException("bad id (a-z0-9_-, same as the folder)");
            RaySkin s = build(c, dir, id, user, j);
            JSONObject vs = j.optJSONObject("variants");
            if (vs != null) {
                s.day = variant(c, dir, id, user, j, vs.optJSONObject("day"));
                s.night = variant(c, dir, id, user, j, vs.optJSONObject("night"));
            }
            return s;
        } catch (Exception e) {
            Log.w(TAG, "skin skipped (" + dir + "): " + e);
            return null;
        }
    }

    /** A variant is the base skin.json with the variant's fields on top (style merged key by key, everything else replaced). A broken variant is dropped, not the skin. */
    private static RaySkin variant(Context c, String dir, String id, boolean user, JSONObject base, JSONObject v) {
        if (v == null) return null;
        try {
            JSONObject m = new JSONObject(base.toString());
            m.remove("variants");
            for (java.util.Iterator<String> it = v.keys(); it.hasNext(); ) {
                String k = it.next();
                JSONObject own = "style".equals(k) ? m.optJSONObject(k) : null, add = v.optJSONObject(k);
                if (own != null && add != null) {
                    for (java.util.Iterator<String> i2 = add.keys(); i2.hasNext(); ) { String k2 = i2.next(); own.put(k2, add.get(k2)); }
                } else m.put(k, v.get(k));
            }
            return build(c, dir, id, user, m);
        } catch (Exception e) {
            Log.w(TAG, "variant skipped (" + dir + "): " + e);
            return null;
        }
    }

    private static RaySkin build(Context c, String dir, String id, boolean user, JSONObject j) throws Exception {
        {
            RaySkin s = new RaySkin(id, dir, user);
            JSONObject name = j.optJSONObject("name");
            if (name != null) { s.nameIt = text(name.optString("it", "")); s.nameEn = text(name.optString("en", "")); }
            if (s.nameIt == null && s.nameEn == null) throw new IllegalArgumentException("name.it or name.en required");
            s.author = text(j.optString("author", ""));
            s.accent = color(j.optString("accent", ""), 0);
            s.weatherColor = color(j.optString("weatherColor", ""), 0);
            if (s.accent == 0) throw new IllegalArgumentException("accent must be #rrggbb");
            String frame = j.isNull("frame") ? null : j.optString("frame", "");
            if (frame != null) {
                if (!frame.matches("frame(-day|-night)?\\.(webp|png)") || !s.has(c, frame)) throw new IllegalArgumentException("frame file missing");
                s.shell = dir + "/" + frame;
            }
            s.raw = !j.optBoolean("animated", false);
            String wp = j.optString("wallpaper", "none");
            s.wallpaper = wp.matches("user|none|[A-Za-z0-9_-]+\\.(webp|png|jpg)") ? wp : "none";
            String remote = j.optString("remote", "remote.webp"), icons = j.optString("icons", "icons");
            if (remote.matches("remote(-day|-night)?\\.webp")) s.remoteFile = remote;
            if (icons.matches("icons(-day|-night)?")) s.iconDir = icons;
            s.hasRemote = s.has(c, s.remoteFile);
            s.hasPreview = s.has(c, "preview.webp");

            JSONObject st = j.optJSONObject("style");
            if (st != null) {
                s.cardRadius = Math.max(0, Math.min(64, (float) st.optDouble("cardRadius", 12)));
                String panel = st.optString("panel", "glass");
                s.panel = panel.matches("glass|solid|none") ? panel : "glass";
                s.textColor = color(st.optString("text", ""), s.textColor);
                s.mutedColor = argb(st.optString("muted", ""), s.mutedColor);
                s.dim = (float) Math.max(.3, Math.min(1, st.optDouble("dim", s.dim)));
                s.focusScale = (float) Math.max(1, Math.min(1.3, st.optDouble("focusScale", s.focusScale)));
                s.ringDp = (float) Math.max(0, Math.min(8, st.optDouble("ringDp", s.ringDp)));
                s.ring = argb(st.optString("ring", ""), s.ring);
                s.shadow = argb(st.optString("shadow", ""), s.shadow);
                s.panelFill = argb(st.optString("panelFill", ""), s.panelFill);
                s.panelStrong = argb(st.optString("panelStrong", ""), s.panelStrong);
                s.ok = argb(st.optString("ok", ""), s.ok);
                s.btnBg = argb(st.optString("btnBg", ""), s.btnBg);
                s.btnFg = argb(st.optString("btnFg", ""), s.btnFg);
                s.btn2Bg = argb(st.optString("btn2Bg", ""), s.btn2Bg);
                s.btn2Fg = argb(st.optString("btn2Fg", ""), s.btn2Fg);
            }
            s.light = Color.red(s.textColor) + Color.green(s.textColor) + Color.blue(s.textColor) < 300;
            s.wpDefault = j.optJSONObject("wallpaperDefault");
            s.wpFx = j.optJSONObject("wallpaperFx");
            if (s.shell == null) { s.stock = true; s.stockLayout(); return s; }

            JSONObject sl = j.getJSONObject("slots");
            s.hero = rect(sl, "hero", false);
            JSONArray inset = sl.optJSONArray("heroInset");
            if (inset != null && inset.length() == 2)
                s.heroInset = new float[]{(float) Math.max(0, Math.min(.4, inset.optDouble(0, 0))), (float) Math.max(0, Math.min(.4, inset.optDouble(1, 0)))};
            float[] none = {0, 0, 0, 0};
            s.logo = or(rect(sl, "logo", false), none);
            s.clock = or(rect(sl, "clock", false), none);
            s.device = rect(sl, "device", false);
            s.weather = rect(sl, "weather", false);
            s.mode = rect(sl, "mode", false);
            s.pair = rect(sl, "pair", false);
            s.connect = rect(sl, "connect", true);
            s.resume = rect(sl, "resume", true);
            s.discover = rect(sl, "discover", true);

            List<String> ids = new ArrayList<String>();
            List<float[]> rects = new ArrayList<float[]>();
            entries(sl.optJSONArray("nav"), NAV, ids, rects, null);
            s.nav = ids.toArray(new String[0]);
            s.navRects = rects.toArray(new float[0][]);
            ids.clear(); rects.clear();
            List<float[]> pads = new ArrayList<float[]>();
            entries(sl.optJSONArray("actions"), ACTIONS, ids, rects, pads);
            s.actions = ids.toArray(new String[0]);
            s.actionRects = rects.toArray(new float[0][]);
            s.actionPad = new float[s.actions.length];
            for (int i = 0; i < s.actionPad.length; i++) s.actionPad[i] = pads.get(i)[0];

            JSONArray titles = sl.optJSONArray("rowTitles");
            s.rowTitles = new float[titles == null ? 0 : Math.min(titles.length(), 8)][];
            for (int i = 0; i < s.rowTitles.length; i++) s.rowTitles[i] = rect(titles.optJSONArray(i));
            JSONArray rows = sl.getJSONArray("rows");
            if (rows.length() < 1 || rows.length() > 8) throw new IllegalArgumentException("rows: 1..8 rows");
            s.rows = new float[rows.length()][][];
            for (int i = 0; i < s.rows.length; i++) {
                JSONArray row = rows.getJSONArray(i);
                if (row.length() < 1 || row.length() > 16) throw new IllegalArgumentException("rows[" + i + "]: 1..16 cards");
                s.rows[i] = new float[row.length()][];
                for (int k = 0; k < row.length(); k++) s.rows[i][k] = rect(row.optJSONArray(k));
            }
            return s;
        }
    }

    private static float[] or(float[] a, float[] b) { return a != null ? a : b; }

    private static String text(String s) { s = s.trim(); return s.isEmpty() ? null : s.substring(0, Math.min(s.length(), 48)); }

    /** Fixed layout of frameless skins (fractions of the screen): 5 Watch Next cards, 8 app cards, 3 round actions. */
    private void stockLayout() {
        float u = 1 / 1280f, v = 1 / 720f;
        hero = null;
        logo = clock = new float[]{.05f, .055f, .24f, .12f};
        connect = new float[]{.65f, .045f, .30f, .125f};
        resume = new float[]{.05f, .415f, 96 * u, 40 * v};
        discover = new float[]{.05f + 108 * u, .415f, 87 * u, 40 * v};
        nav = new String[0];
        navRects = new float[0][];
        actions = new String[]{"search", "sources", "settings"};
        actionRects = new float[3][];
        actionPad = new float[3];
        for (int i = 0; i < 3; i++) actionRects[i] = new float[]{(804 + 56 * i) * u, 48 * v, 44 * u, 44 * v};
        rowTitles = new float[][]{{.05f, .542f, .4f, .04f}, {.05f, .81f, .4f, .04f}};
        rows = new float[2][][];
        rows[0] = new float[5][];
        for (int i = 0; i < 5; i++) rows[0][i] = new float[]{.05f + i * .1825f, .595f, .17f, .17f};
        rows[1] = new float[8][];
        for (int i = 0; i < 8; i++) rows[1][i] = new float[]{.05f + i * .114f, .86f, .1015f, .1f};
    }

    /** #rrggbb or #aarrggbb. */
    private static int argb(String s, int fallback) {
        return s.matches("#[0-9a-fA-F]{6}|#[0-9a-fA-F]{8}") ? Color.parseColor(s) : fallback;
    }

    private static int color(String s, int fallback) {
        if (!s.matches("#[0-9a-fA-F]{6}")) return fallback;
        return Color.parseColor(s);
    }

    private static float[] rect(JSONObject o, String key, boolean required) {
        JSONArray a = o.optJSONArray(key);
        if (a == null && !required) return null;
        return rect(a);
    }

    /** {x, y, w, h} clamped to the screen; throws when it is not four numbers. */
    private static float[] rect(JSONArray a) {
        if (a == null || a.length() != 4) throw new IllegalArgumentException("rect must be [x, y, w, h]");
        float[] r = new float[4];
        for (int i = 0; i < 4; i++) {
            double v = a.optDouble(i, Double.NaN);
            if (Double.isNaN(v)) throw new IllegalArgumentException("rect: numbers only");
            r[i] = (float) Math.max(0, Math.min(1, v));
        }
        r[2] = Math.min(r[2], 1 - r[0]);
        r[3] = Math.min(r[3], 1 - r[1]);
        return r;
    }

    /** [{id, rect, pad?}] keeping known ids only (unknown ones are ignored), at most 12. */
    private static void entries(JSONArray a, String[] known, List<String> ids, List<float[]> rects, List<float[]> pads) {
        if (a == null) return;
        for (int i = 0; i < a.length() && ids.size() < 12; i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || !Arrays.asList(known).contains(o.optString("id"))) continue;
            ids.add(o.optString("id"));
            rects.add(rect(o.optJSONArray("rect")));
            if (pads != null) pads.add(new float[]{(float) Math.max(0, Math.min(.5, o.optDouble("pad", 0)))});
        }
    }
}
