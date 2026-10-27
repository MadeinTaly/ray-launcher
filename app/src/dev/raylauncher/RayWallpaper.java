package dev.raylauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The user's TV wallpaper (filesDir/wallpaper.jpg, set from the phone) and the scene bitmaps built from it.
 * Everything here runs once per wallpaper or skin change, off the UI thread; nothing per frame.
 */
final class RayWallpaper {
    static final int W = 1280, H = 720;
    private static final String TAG = "RayWallpaper";
    static final int MAX_UPLOAD = 15 << 20;

    private RayWallpaper() {}

    static File file(Context c) { return new File(c.getFilesDir(), "wallpaper.jpg"); }

    static long stamp(Context c) { return file(c).lastModified(); }

    static synchronized boolean reset(Context c) { return !file(c).exists() || file(c).delete(); }

    /** Validates and stores an uploaded image: decoded with inSampleSize to at most 1920 wide, re-encoded as JPEG q88. */
    static synchronized void save(Context c, InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[16384];
        int n;
        while ((n = in.read(chunk)) > 0) { // the stream is the socket's: not closed here
            buf.write(chunk, 0, n);
            if (buf.size() > MAX_UPLOAD) throw new IOException("image up to 15 MB");
        }
        byte[] data = buf.toByteArray();
        buf = null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0 || o.outWidth * (long) o.outHeight > 200_000_000L) throw new IOException("not an image");
        int sample = 1;
        while (o.outWidth / sample > 1920 * 2 || o.outHeight / sample > 1920) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        Bitmap b;
        try { b = BitmapFactory.decodeByteArray(data, 0, data.length, o); }
        catch (OutOfMemoryError e) { throw new IOException("image too large"); }
        data = null;
        if (b == null) throw new IOException("not an image");
        if (b.getWidth() > 1920) {
            Bitmap s = Bitmap.createScaledBitmap(b, 1920, Math.max(1, Math.round(b.getHeight() * 1920f / b.getWidth())), true);
            b.recycle();
            b = s;
        }
        File tmp = new File(c.getFilesDir(), "wallpaper.tmp");
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            try { if (!b.compress(Bitmap.CompressFormat.JPEG, 88, out)) throw new IOException("encode failed"); }
            finally { out.close(); }
            if (!tmp.renameTo(file(c))) { file(c).delete(); if (!tmp.renameTo(file(c))) throw new IOException("cannot store"); }
        } finally { b.recycle(); tmp.delete(); }
    }

    /** true when a wallpaper file exists but could not be decoded (the TV settings say so). */
    static volatile boolean failed;

    /** The wallpaper cover-cropped to w x h, or null when there is none or it cannot be decoded (never throws). */
    static Bitmap cover(Context c, int w, int h, Bitmap.Config config) {
        File f = file(c);
        failed = false;
        if (!f.exists()) return null;
        failed = true;
        Bitmap src = null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            if (o.outWidth <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            src = BitmapFactory.decodeFile(f.getPath(), o);
            if (src == null) return null;
            Bitmap out = Bitmap.createBitmap(w, h, config);
            float s = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
            int sw = Math.round(w / s), sh = Math.round(h / s);
            int x = (src.getWidth() - sw) / 2, y = (src.getHeight() - sh) / 2;
            new Canvas(out).drawBitmap(src, new Rect(x, y, x + sw, y + sh), new Rect(0, 0, w, h), new Paint(Paint.FILTER_BITMAP_FLAG));
            failed = false;
            return out;
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "wallpaper unreadable: " + e);
            return null;
        } finally { if (src != null) src.recycle(); }
    }

    /**
     * Scene of a frameless skin: wallpaper (or the skin's default gradient), blurred below `blurBelowY` and
     * covered by the legibility scrim. 1280x720 ARGB_8888; the caller hands it to the backdrop.
     */
    static Bitmap stockScene(Context c, RaySkin skin) {
        Bitmap scene = null;
        try {
            scene = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) { return null; }
        Canvas canvas = new Canvas(scene);
        Bitmap wp = cover(c, W, H, Bitmap.Config.ARGB_8888);
        float boost = 0;
        Bitmap art = wp == null ? defaultImage(c, skin) : null;
        if (art != null) {
            canvas.drawBitmap(art, null, new android.graphics.Rect(0, 0, W, H), new Paint(Paint.FILTER_BITMAP_FLAG));
            art.recycle();
        } else if (wp == null) defaultGradient(canvas, skin.wpDefault);
        else {
            Bitmap small = null;
            try {
                canvas.drawBitmap(wp, 0, 0, null);
                small = Bitmap.createScaledBitmap(wp, W / 8, H / 8, true);
                boost = legibility(small, skin.light);
                blurBand(canvas, small, skin.wpFx);
            } catch (RuntimeException | OutOfMemoryError e) {
                Log.w(TAG, "wallpaper treatment skipped: " + e);
            } finally {
                wp.recycle();
                if (small != null) small.recycle();
            }
        }
        scrim(canvas, skin.wpFx, skin.light, boost);
        return scene;
    }

    /** +.15 alpha when the wallpaper is too bright for a dark theme or too dark for a light one (left third and bottom). */
    private static float legibility(Bitmap small, boolean light) {
        int w = small.getWidth(), h = small.getHeight();
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        double sum = 0;
        int n = 0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            if (x >= w / 3 && y < h * 3 / 4) continue;
            int p = px[y * w + x];
            sum += (.299 * Color.red(p) + .587 * Color.green(p) + .114 * Color.blue(p)) / 255;
            n++;
        }
        double lum = n == 0 ? .5 : sum / n;
        return light ? (lum < .45 ? .15f : 0) : (lum > .55 ? .15f : 0);
    }

    private static void blurBand(Canvas canvas, Bitmap small, JSONObject fx) {
        float y0 = (float) (fx == null ? .5 : fx.optDouble("blurBelowY", .5)), feather = (float) (fx == null ? .1 : fx.optDouble("blurFeather", .1));
        if (y0 >= 1) return;
        int w = small.getWidth(), h = small.getHeight();
        int[] a = new int[w * h], b = new int[w * h];
        small.getPixels(a, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) { box(a, b, w, h, 3, true); box(b, a, w, h, 3, false); }
        Bitmap blurred = Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888);
        try {
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale((float) W / w, (float) H / h);
            android.graphics.BitmapShader bs = new android.graphics.BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            bs.setLocalMatrix(m);
            float t0 = Math.max(0, y0 - feather / 2) * H, t1 = Math.min(1, y0 + feather / 2) * H;
            LinearGradient mask = new LinearGradient(0, t0, 0, Math.max(t0 + 1, t1), Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP);
            p.setShader(new ComposeShader(bs, mask, PorterDuff.Mode.DST_IN));
            canvas.drawRect(0, t0, W, H, p);
        } finally { blurred.recycle(); }
    }

    /** One box blur pass of radius r along rows (horizontal) or columns, edges clamped. */
    private static void box(int[] src, int[] dst, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w, step = horizontal ? 1 : w, stride = horizontal ? w : 1;
        int div = 2 * r + 1;
        for (int l = 0; l < lines; l++) {
            int base = l * stride;
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = src[base + Math.max(0, Math.min(len - 1, i)) * step];
                sr += Color.red(p); sg += Color.green(p); sb += Color.blue(p);
            }
            for (int i = 0; i < len; i++) {
                dst[base + i * step] = Color.rgb(sr / div, sg / div, sb / div);
                int add = src[base + Math.min(len - 1, i + r + 1) * step], sub = src[base + Math.max(0, i - r) * step];
                sr += Color.red(add) - Color.red(sub);
                sg += Color.green(add) - Color.green(sub);
                sb += Color.blue(add) - Color.blue(sub);
            }
        }
    }

    private static void scrim(Canvas canvas, JSONObject fx, boolean light, float boost) {
        int rgb = light ? 0xffffff : 0x080a14;
        if (fx != null && fx.optString("scrimRgb", "").matches("[0-9a-fA-F]{6}")) rgb = Integer.parseInt(fx.optString("scrimRgb"), 16);
        float[][] h = stops(fx == null ? null : fx.optJSONArray("scrimH"), light ? new float[][]{{0, .62f}, {.45f, .30f}, {.7f, 0}} : new float[][]{{0, .62f}, {.45f, .25f}, {.7f, 0}});
        float[][] v = stops(fx == null ? null : fx.optJSONArray("scrimV"), new float[][]{{0, .35f}, {.22f, 0}, {.48f, 0}, {1, light ? .6f : .72f}});
        Paint p = new Paint();
        p.setShader(gradient(0, 0, W, 0, h, rgb, boost));
        canvas.drawRect(0, 0, W, H, p);
        p.setShader(gradient(0, 0, 0, H, v, rgb, boost));
        canvas.drawRect(0, 0, W, H, p);
    }

    private static float[][] stops(JSONArray a, float[][] fallback) {
        if (a == null || a.length() < 2) return fallback;
        float[][] out = new float[a.length()][];
        for (int i = 0; i < out.length; i++) {
            JSONArray s = a.optJSONArray(i);
            if (s == null || s.length() != 2) return fallback;
            out[i] = new float[]{(float) Math.max(0, Math.min(1, s.optDouble(0))), (float) Math.max(0, Math.min(1, s.optDouble(1)))};
            if (i > 0 && out[i][0] < out[i - 1][0]) return fallback;
        }
        return out;
    }

    private static LinearGradient gradient(float x0, float y0, float x1, float y1, float[][] st, int rgb, float boost) {
        int[] colors = new int[st.length];
        float[] pos = new float[st.length];
        for (int i = 0; i < st.length; i++) {
            pos[i] = st[i][0];
            float a = st[i][1];
            colors[i] = Color.argb(Math.round(255 * (a > 0 ? Math.min(.85f, a + boost) : 0)), (rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255);
        }
        return new LinearGradient(x0, y0, x1, y1, colors, pos, Shader.TileMode.CLAMP);
    }

    /** wallpaperDefault.image: a picture shipped in the skin (wallpaper.webp, -day or -night), drawn instead of the gradient. */
    private static Bitmap defaultImage(Context c, RaySkin skin) {
        String name = skin.wpDefault == null ? "" : skin.wpDefault.optString("image");
        if (!name.matches("wallpaper(-day|-night)?\\.webp")) return null;
        try (java.io.InputStream in = skin.open(c, name)) {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return android.graphics.BitmapFactory.decodeStream(in, null, o);
        } catch (java.io.IOException | RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "skin wallpaper " + name + ": " + e);
            return null;
        }
    }

    /** wallpaperDefault: linear gradient at `angle` degrees, elliptical glows and optional mountain silhouettes. */
    private static void defaultGradient(Canvas canvas, JSONObject d) {
        int[] cols = {0xff16213f, 0xff2a2c58, 0xff5b3a62, 0xffb5584f};
        float angle = 160;
        JSONArray g = d == null ? null : d.optJSONArray("gradient");
        if (g != null && g.length() >= 2) {
            cols = new int[g.length()];
            for (int i = 0; i < cols.length; i++) cols[i] = parse(g.optString(i), Color.BLACK);
        }
        if (d != null) angle = (float) d.optDouble("angle", angle);
        double rad = Math.toRadians(angle);
        // CSS angle: 180 = top to bottom; the gradient line is as long as the box projected on it
        float dx = (float) Math.sin(rad), dy = (float) -Math.cos(rad);
        float len = Math.abs(W * dx) + Math.abs(H * dy);
        float cx = W / 2f, cy = H / 2f;
        Paint p = new Paint(Paint.DITHER_FLAG);
        float[] pos = new float[cols.length];
        for (int i = 0; i < pos.length; i++) pos[i] = i / (float) (pos.length - 1);
        JSONArray gp = d == null ? null : d.optJSONArray("stops");
        if (gp != null && gp.length() == pos.length) for (int i = 0; i < pos.length; i++) pos[i] = (float) gp.optDouble(i, pos[i]);
        p.setShader(new LinearGradient(cx - dx * len / 2, cy - dy * len / 2, cx + dx * len / 2, cy + dy * len / 2, cols, pos, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, W, H, p);
        JSONArray glows = d == null ? null : d.optJSONArray("glows");
        if (glows != null) for (int i = 0; i < glows.length(); i++) {
            JSONObject o = glows.optJSONObject(i);
            if (o == null) continue;
            int c = parse(o.optString("c"), Color.WHITE);
            int a = Math.round(255 * (float) o.optDouble("a", .5));
            float rx = (float) o.optDouble("r", .7) * W, ry = (float) o.optDouble("r", .7) * H * 1.03f;
            canvas.save();
            canvas.translate((float) o.optDouble("x", .5) * W, (float) o.optDouble("y", .5) * H);
            canvas.scale(1, ry / rx);
            p.setShader(new RadialGradient(0, 0, rx, Color.argb(a, Color.red(c), Color.green(c), Color.blue(c)),
                    Color.argb(0, Color.red(c), Color.green(c), Color.blue(c)), Shader.TileMode.CLAMP));
            canvas.drawCircle(0, 0, rx, p);
            canvas.restore();
        }
        JSONArray mt = d == null ? null : d.optJSONArray("mountains");
        if (mt != null) {
            float[][] ridge = {{.40f, .86f}, {.60f, .44f}, {.72f, .55f}, {.86f, .53f}, {.96f, .62f}, {1.05f, .56f}};
            float[][] ridge2 = {{.30f, .95f}, {.48f, .62f}, {.62f, .70f}, {.80f, .64f}, {1.0f, .8f}};
            mountain(canvas, ridge, parse(mt.optString(0), 0xccffffff));
            if (mt.length() > 1) mountain(canvas, ridge2, parse(mt.optString(1), 0xccffffff));
        }
        JSONObject hz = d == null ? null : d.optJSONObject("horizon");
        if (hz != null) horizon(canvas, hz);
        double grain = d == null ? 0 : d.optDouble("grain", 0);
        if (grain > 0) grain(canvas, (float) Math.min(.2, grain));
    }

    /**
     * horizon: {y, land, lines, lights, count} - a low flat land, long faint wind lines above it and a far row of
     * lit points along it. Seeded, so the same skin always draws the same night.
     */
    private static void horizon(Canvas canvas, JSONObject hz) {
        float y = (float) Math.max(.3, Math.min(.98, hz.optDouble("y", .82))) * H;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        java.util.Random r = new java.util.Random(7);
        int line = parse(hz.optString("lines"), 0);
        if (line != 0) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.2f);
            Path path = new Path();
            for (int i = 0; i < 9; i++) {
                float y0 = y * (.3f + .65f * r.nextFloat()), amp = 6 + 18 * r.nextFloat(), x0 = -W * .1f + W * .5f * r.nextFloat();
                path.reset();
                path.moveTo(x0, y0);
                path.cubicTo(x0 + W * .3f, y0 - amp, x0 + W * .6f, y0 + amp, x0 + W * 1.1f, y0 - amp / 2);
                p.setColor(Color.argb(Math.round(Color.alpha(line) * (.35f + .65f * r.nextFloat())), Color.red(line), Color.green(line), Color.blue(line)));
                canvas.drawPath(path, p);
            }
            p.setStyle(Paint.Style.FILL);
        }
        int land = parse(hz.optString("land"), 0);
        if (land != 0) {
            p.setShader(new LinearGradient(0, y - 8, 0, y + 24, land & 0xffffff, land, Shader.TileMode.CLAMP));
            canvas.drawRect(0, y - 8, W, H, p);
            p.setShader(null);
        }
        int lights = parse(hz.optString("lights"), 0);
        if (lights != 0) {
            float[] towns = {.47f, .61f, .78f, .9f};
            int n = Math.max(0, Math.min(120, hz.optInt("count", 40)));
            for (int i = 0; i < n; i++) {
                float x = (towns[r.nextInt(towns.length)] + (float) r.nextGaussian() * .025f) * W, ly = y + 2 - 4 * r.nextFloat();
                float bright = .45f + .55f * r.nextFloat();
                p.setShader(new RadialGradient(x, ly, 7, Color.argb(Math.round(70 * bright), Color.red(lights), Color.green(lights), Color.blue(lights)),
                        lights & 0xffffff, Shader.TileMode.CLAMP));
                canvas.drawCircle(x, ly, 7, p);
                p.setShader(null);
                p.setColor(Color.argb(Math.round(235 * bright), Color.red(lights), Color.green(lights), Color.blue(lights)));
                canvas.drawCircle(x, ly, .9f + .6f * r.nextFloat(), p);
            }
        }
    }

    /** Fine film grain: light and dark specks at `amount` opacity, so a flat gradient does not band or look rendered. */
    private static void grain(Canvas canvas, float amount) {
        java.util.Random r = new java.util.Random(3);
        int n = W * H / 6;
        float[] light = new float[n], dark = new float[n];
        for (int i = 0; i < n; i += 2) {
            light[i] = r.nextInt(W); light[i + 1] = r.nextInt(H);
            dark[i] = r.nextInt(W); dark[i + 1] = r.nextInt(H);
        }
        Paint p = new Paint();
        p.setStrokeWidth(1);
        p.setColor(Color.argb(Math.round(255 * amount), 255, 255, 255));
        canvas.drawPoints(light, p);
        p.setColor(Color.argb(Math.round(255 * amount), 0, 0, 0));
        canvas.drawPoints(dark, p);
    }

    private static void mountain(Canvas canvas, float[][] pts, int color) {
        Path path = new Path();
        path.moveTo(pts[0][0] * W, H);
        for (float[] q : pts) path.lineTo(q[0] * W, q[1] * H);
        path.lineTo(W * 1.05f, H);
        path.close();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        canvas.drawPath(path, p);
    }

    private static int parse(String s, int fallback) {
        try { return Color.parseColor(s); } catch (RuntimeException e) { return fallback; }
    }
}
