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
 * A home-screen skin (format v1, docs/SKIN_FORMAT.md): a folder with skin.json, optional wallpaper images and
 * icons. The home is drawn by code (RayStockHero). Skins live in assets/skins/&lt;id&gt; (built in) or in
 * getExternalFilesDir("skins")/&lt;id&gt; (user).
 */
final class RaySkin {
    static final String DEFAULT = "cinema";
    private static final String TAG = "RaySkin";
    private static List<RaySkin> builtIn;
    private static final java.util.HashMap<String, RaySkin> ONE = new java.util.HashMap<>();
    /** The only files a skin zip may carry, optionally under one top folder; everything else is ignored. */
    private static final Pattern ENTRY = Pattern.compile("(?:[^/]+/)?(skin\\.json|wallpaper(?:-day|-night)?\\.webp|remote(?:-day|-night)?\\.webp|preview\\.webp|icons(?:-day|-night)?/[a-z]{1,16}\\.png)");
    private static final int FILE_MAX = 8 << 20, ZIP_MAX = 24 << 20;

    final String id;
    /** Folder: "skins/&lt;id&gt;" in assets, or an absolute path for a user skin. */
    final String dir;
    final boolean user;
    String nameIt, nameEn, author;
    int accent;
    boolean hasRemote;
    /** Phone background (remote.webp or the variant's) and the folder of the themed icons. */
    String remoteFile = "remote.webp", iconDir = "icons";
    /** Day and night variants (skin.json "variants"), fully parsed; null = the base art in that mode. Only set on the base skin. */
    RaySkin day, night;
    /** Composition of the home (RayStockHero): cinema, rivista or lacca. */
    String layout = "cinema";
    /** style.scene: the animated background (RaySynthScene), "synth", where the device can take it (RayGraphics). */
    String scene;
    /** The two ends of the gradient of the wireframe cube buttons; 0 = round discs. */
    int cubeA, cubeB;
    /** Dark text on a light page (day variants). */
    boolean light;
    float cardRadius = 12;
    int textColor = Color.WHITE, mutedColor = Color.rgb(160, 175, 190);
    /** Flat colours of the rivista and lacca compositions: page or lacquer gradient, and the empty moon. */
    int surface = 0xff0a0a0c, surface2 = 0xff0a0a0c, moon = 0xfff1e6cf;
    float dim = .78f, focusScale = 1.08f, ringDp = 2.5f;
    int ring = Color.WHITE, shadow = 0x8c000000, panelFill = 0x1affffff, panelStrong = 0x29ffffff, ok = 0xff22a55b, btnFg = 0xff10131a;
    /** wallpaperDefault and wallpaperFx of skin.json, read by RayWallpaper. */
    JSONObject wpDefault, wpFx;

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

    /** Names in more languages than it/en ({"de": .., "fr": ..}), used when the system language matches. */
    JSONObject names;

    String title(boolean italian) {
        String other = names == null || italian ? null : text(names.optString(java.util.Locale.getDefault().getLanguage(), ""));
        if (other != null) return other;
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
        synchronized (RaySkin.class) { // the start needs one skin: parsing them all costs a visible quarter of a second on a TV box
            if (builtIn == null && id != null && id.matches("[a-z0-9_-]{1,32}")) {
                RaySkin known = ONE.get(id);
                if (known == null) {
                    try { c.getAssets().open("skins/" + id + "/skin.json").close(); known = parse(c, "skins/" + id, id, false); }
                    catch (IOException notBundled) { }
                    if (known != null) ONE.put(id, known);
                }
                if (known != null) return known;
            }
        }
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
            if (name != null) { s.nameIt = text(name.optString("it", "")); s.nameEn = text(name.optString("en", "")); s.names = name; }
            if (s.nameIt == null && s.nameEn == null) throw new IllegalArgumentException("name.it or name.en required");
            s.author = text(j.optString("author", ""));
            s.accent = color(j.optString("accent", ""), 0);
            if (s.accent == 0) throw new IllegalArgumentException("accent must be #rrggbb");
            if (j.has("frame") && !j.isNull("frame")) throw new IllegalArgumentException("framed skins are not supported");
            String remote = j.optString("remote", "remote.webp"), icons = j.optString("icons", "icons");
            if (remote.matches("remote(-day|-night)?\\.webp")) s.remoteFile = remote;
            if (icons.matches("icons(-day|-night)?")) s.iconDir = icons;
            s.hasRemote = s.has(c, s.remoteFile);

            JSONObject st = j.optJSONObject("style");
            if (st != null) {
                s.cardRadius = Math.max(0, Math.min(64, (float) st.optDouble("cardRadius", 12)));
                String layout = st.optString("layout", "cinema");
                s.layout = layout.matches("cinema|rivista|lacca") ? layout : "cinema";
                String scene = st.optString("scene", "");
                s.scene = "synth".equals(scene) ? scene : null;
                s.cubeA = argb(st.optString("cubeA", ""), s.cubeA);
                s.cubeB = argb(st.optString("cubeB", ""), s.cubeB);
                s.surface = argb(st.optString("surface", ""), s.surface);
                s.surface2 = argb(st.optString("surface2", ""), s.surface);
                s.moon = argb(st.optString("moon", ""), s.moon);
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
                s.btnFg = argb(st.optString("btnFg", ""), s.btnFg);
            }
            s.light = Color.red(s.textColor) + Color.green(s.textColor) + Color.blue(s.textColor) < 300;
            s.wpDefault = j.optJSONObject("wallpaperDefault");
            s.wpFx = j.optJSONObject("wallpaperFx");
            return s;
        }
    }

    private static String text(String s) { s = s.trim(); return s.isEmpty() ? null : s.substring(0, Math.min(s.length(), 48)); }

    /** #rrggbb or #aarrggbb. */
    private static int argb(String s, int fallback) {
        return s.matches("#[0-9a-fA-F]{6}|#[0-9a-fA-F]{8}") ? Color.parseColor(s) : fallback;
    }

    private static int color(String s, int fallback) {
        if (!s.matches("#[0-9a-fA-F]{6}")) return fallback;
        return Color.parseColor(s);
    }
}
