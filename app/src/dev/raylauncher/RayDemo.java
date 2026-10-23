package dev.raylauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Hidden demo mode for clean store screenshots: set the boolean pref ray_demo (adb only, no UI) and the
 * launcher shows neutral sample content drawn here instead of the installed apps, the Watch Next row and the
 * weather. Nothing leaves the device and everything is generated, so there is no third-party artwork.
 */
final class RayDemo {
    static final String[] APPS = {"Film", "Serie TV", "Musica", "Foto", "Giochi", "Browser", "Impostazioni"};
    private static final int[][] APP_COLORS = {
        {0xffe8505b, 0xff7a1f4a}, {0xff3d7be8, 0xff1d2f78}, {0xffb05ce6, 0xff4a2285}, {0xff2fb7a4, 0xff145a6b},
        {0xfff0a030, 0xffa8431f}, {0xff4cc36a, 0xff1b6a55}, {0xff7f8da3, 0xff3a4558}};
    private static final String[] TITLES = {"Ocean Lab", "Northern Lights", "City at Night", "Mountain Trails", "Jazz Session"};
    private static final String[] SUBS = {"Season 1", "Documentary", "Cityscapes", "Outdoor", "Live"};
    private static final float[] PROGRESS = {.35f, .62f, .8f, .15f, .5f};
    private static final int[][] SKY = {
        {0xff0a3d62, 0xff48b6c9}, {0xff0b1f3a, 0xff1fa57a}, {0xff160f33, 0xffe0568f}, {0xff35507a, 0xfff2b46d}, {0xff1c1230, 0xffd9803a}};

    private RayDemo() { }

    static boolean on(Context context) {
        return context.getSharedPreferences(DimService.PREFS, Context.MODE_PRIVATE).getBoolean("ray_demo", false);
    }

    static JSONObject weather() {
        try { return new JSONObject().put("code", 0).put("temp", 21).put("day", true).put("city", "Roma"); }
        catch (JSONException e) { return null; }
    }

    static List<RayWatchHistory.Entry> watch() {
        ArrayList<RayWatchHistory.Entry> out = new ArrayList<>();
        for (int i = 0; i < TITLES.length; i++) {
            RayWatchHistory.Entry e = new RayWatchHistory.Entry(TITLES[i], "demo.watch", "", scene(i, 640, 360));
            e.progress = PROGRESS[i];
            e.minutesLeft = 12 + 17 * i;
            e.description = SUBS[i];
            out.add(e);
        }
        return out;
    }

    /** Banner for the n-th sample app: coloured gradient, simple glyph, name. */
    static Drawable app(int n) {
        int w = 640, h = 360;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, w, h, APP_COLORS[n][0], APP_COLORS[n][1], Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        p.setColor(0xf2ffffff);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(12);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        glyph(c, p, n, w / 2f, 150);
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(52);
        c.drawText(APPS[n], w / 2f, 305, p);
        return new BitmapDrawable(b);
    }

    private static void glyph(Canvas c, Paint p, int n, float x, float y) {
        Path path = new Path();
        switch (n) {
            case 0: // film: frame with a play triangle
                c.drawRoundRect(new RectF(x - 75, y - 55, x + 75, y + 55), 16, 16, p);
                path.moveTo(x - 18, y - 26); path.lineTo(x + 28, y); path.lineTo(x - 18, y + 26); path.close();
                c.drawPath(path, p); break;
            case 1: // series: television
                c.drawRoundRect(new RectF(x - 75, y - 55, x + 75, y + 35), 14, 14, p);
                c.drawLine(x - 35, y + 62, x + 35, y + 62, p); break;
            case 2: // music: note
                c.drawCircle(x - 28, y + 38, 20, p);
                c.drawLine(x - 8, y + 38, x - 8, y - 55, p);
                c.drawLine(x - 8, y - 55, x + 45, y - 38, p); break;
            case 3: // photo: frame, hill, sun
                c.drawRoundRect(new RectF(x - 75, y - 55, x + 75, y + 55), 16, 16, p);
                path.moveTo(x - 75, y + 40); path.lineTo(x - 22, y - 5); path.lineTo(x + 20, y + 28); path.lineTo(x + 40, y + 12); path.lineTo(x + 75, y + 40);
                c.drawPath(path, p);
                c.drawCircle(x + 38, y - 24, 11, p); break;
            case 4: // games: pad with cross and buttons
                c.drawRoundRect(new RectF(x - 85, y - 42, x + 85, y + 48), 40, 40, p);
                c.drawLine(x - 50, y + 3, x - 18, y + 3, p); c.drawLine(x - 34, y - 13, x - 34, y + 19, p);
                c.drawCircle(x + 34, y - 6, 8, p); c.drawCircle(x + 56, y + 14, 8, p); break;
            case 5: // browser: globe
                c.drawCircle(x, y, 58, p);
                c.drawOval(new RectF(x - 26, y - 58, x + 26, y + 58), p);
                c.drawLine(x - 58, y, x + 58, y, p); break;
            default: // settings: sliders
                for (int i = 0; i < 3; i++) {
                    float yy = y - 38 + i * 38;
                    c.drawLine(x - 70, yy, x + 70, yy, p);
                    p.setStyle(Paint.Style.FILL); c.drawCircle(x - 30 + i * 30, yy, 13, p); p.setStyle(Paint.Style.STROKE);
                }
        }
    }

    /** Abstract landscape: sky gradient, sun, two ridges. */
    private static Bitmap scene(int n, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h, SKY[n][0], SKY[n][1], Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        p.setColor(0x55ffffff);
        c.drawCircle(w * (.25f + .15f * n), h * .42f, h * .13f, p);
        for (int layer = 0; layer < 2; layer++) {
            Path ridge = new Path();
            ridge.moveTo(0, h);
            float base = h * (.68f + .12f * layer);
            for (int i = 0; i <= 8; i++) {
                double wave = Math.sin(i * (1.1 + .25 * n) + layer * 2 + n) * .09 + Math.sin(i * 2.3 + n) * .04;
                ridge.lineTo(w * i / 8f, (float) (base - wave * h));
            }
            ridge.lineTo(w, h);
            ridge.close();
            p.setColor(layer == 0 ? 0x99101826 : 0xe6070b14);
            c.drawPath(ridge, p);
        }
        return b;
    }
}
