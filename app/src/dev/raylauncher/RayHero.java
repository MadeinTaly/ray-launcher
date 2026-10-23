package dev.raylauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;

import java.util.Locale;

/**
 * The big window of the skin: featured item with artwork, scrims, metadata, title, description and page dots, or a
 * greeting with the clock when there is nothing to feature. Lives in the scene layer (under the frame), so it is only
 * drawn into the scene bitmap. Layers are built when the content changes, never per frame; a change crossfades.
 */
final class RayHero extends View {
    private static final long FADE_MS = 250;

    private static final class Layer {
        Bitmap art; // shared with the caller: never recycled here
        final Rect src = new Rect();
        String time;
        StaticLayout meta, title, desc;
        float yMeta, yTitle, yDesc;
        int dots, dot;
    }

    private final int accent;
    private final float u;
    private final float holeL, holeT, holeR, holeB, textBottom;
    private final int w, h;
    private final Rect dst = new Rect();
    private final RectF rect = new RectF();
    private final Paint art = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrimLeft = new Paint(), scrimBottom = new Paint(), plain = new Paint();
    private final TextPaint metaP = new TextPaint(Paint.ANTI_ALIAS_FLAG), titleP = new TextPaint(Paint.ANTI_ALIAS_FLAG),
            descP = new TextPaint(Paint.ANTI_ALIAS_FLAG), timeP = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private Layer cur, prev;
    private long fadeStart;

    /** Geometry in view pixels: the hole of the frame inside the (bleeding) view, and the text baseline above the buttons. */
    RayHero(Context context, int w, int h, float[] hole, float textBottom, float unit, int accent) {
        super(context);
        this.w = w;
        this.h = h;
        this.holeL = hole[0];
        this.holeT = hole[1];
        this.holeR = hole[2];
        this.holeB = hole[3];
        this.textBottom = textBottom;
        this.u = unit;
        this.accent = accent;
        setFocusable(false);
        dst.set(0, 0, w, h);
        scrimLeft.setShader(new LinearGradient(holeL, 0, holeL + (holeR - holeL) * .72f, 0,
                Color.argb(235, 4, 8, 16), Color.TRANSPARENT, Shader.TileMode.CLAMP));
        scrimBottom.setShader(new LinearGradient(0, holeT + (holeB - holeT) * .35f, 0, h,
                Color.TRANSPARENT, Color.argb(215, 0, 0, 0), Shader.TileMode.CLAMP));
        plain.setShader(new LinearGradient(0, 0, w, h, mix(accent, Color.rgb(4, 8, 16), .78f),
                Color.rgb(3, 6, 12), Shader.TileMode.CLAMP));
        metaP.setTypeface(Fonts.bold(context));
        metaP.setTextSize(13 * u);
        metaP.setLetterSpacing(.12f);
        metaP.setColor(accent);
        titleP.setTypeface(Fonts.bold(context));
        titleP.setTextSize(30 * u);
        titleP.setLetterSpacing(.05f);
        titleP.setColor(Color.WHITE);
        descP.setTypeface(Fonts.regular(context));
        descP.setTextSize(15 * u);
        descP.setColor(Color.rgb(222, 230, 238));
        timeP.setTypeface(Fonts.bold(context));
        timeP.setTextSize(68 * u);
        timeP.setTextAlign(Paint.Align.RIGHT);
        timeP.setColor(Color.WHITE);
        fill.setStyle(Paint.Style.FILL);
    }

    private static int mix(int a, int b, float t) {
        return Color.rgb(Math.round(Color.red(a) * (1 - t) + Color.red(b) * t),
                Math.round(Color.green(a) * (1 - t) + Color.green(b) * t),
                Math.round(Color.blue(a) * (1 - t) + Color.blue(b) * t));
    }

    /** Featured item. art may be null (gradient background); dots/dot: page dots, hidden when dots < 2. */
    void show(Bitmap artwork, String meta, String title, String desc, int dots, int dot) {
        Layer l = new Layer();
        l.art = artwork;
        if (artwork != null) crop(l.src, artwork);
        l.dots = dots;
        l.dot = dot;
        int tw = Math.round((holeR - holeL) * .5f);
        float gap = 6 * u;
        String shown = title.length() <= 24 ? title.toUpperCase(Locale.getDefault()) : title;
        l.title = clip(shown, titleP, tw, 2);
        l.meta = clip(meta == null ? "" : meta.toUpperCase(Locale.getDefault()), metaP, tw, 1);
        l.desc = desc == null || desc.isEmpty() ? null : clip(desc, descP, tw, 2);
        float top = holeT + 10 * u;
        float total = l.title.getHeight() + gap + l.meta.getHeight() + (l.desc == null ? 0 : l.desc.getHeight() + gap);
        if (textBottom - total < top && l.desc != null) { l.desc = null; total = l.title.getHeight() + gap + l.meta.getHeight(); }
        float y = Math.max(top, textBottom - total);
        l.yMeta = y;
        l.yTitle = y + l.meta.getHeight() + gap;
        l.yDesc = l.yTitle + l.title.getHeight() + gap;
        swap(l);
    }

    /** Nothing to feature: greeting, date and a big clock. */
    void showGreeting(String greeting, String date, String time) {
        show(null, date, greeting, "", 0, 0);
        cur.time = time;
    }

    private void swap(Layer l) {
        // note: no crossfade; the scene texture is captured off-screen and a half-done fade got stuck as a ghost
        prev = null;
        cur = l;
        invalidate();
    }

    private void crop(Rect out, Bitmap b) {
        float scale = Math.max((float) w / b.getWidth(), (float) h / b.getHeight());
        int sw = Math.round(w / scale), sh = Math.round(h / scale);
        int x = (b.getWidth() - sw) / 2, y = (b.getHeight() - sh) / 2;
        out.set(x, y, x + sw, y + sh);
    }

    private StaticLayout clip(String text, TextPaint paint, int width, int maxLines) {
        StaticLayout l = new StaticLayout(text, paint, width, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false);
        if (l.getLineCount() <= maxLines) return l;
        String cut = text.substring(0, l.getLineEnd(maxLines - 1)).trim();
        if (cut.length() > 1) cut = cut.substring(0, cut.length() - 1).trim() + "…";
        return new StaticLayout(cut, paint, width, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) { setMeasuredDimension(w, h); }

    @Override protected void onDraw(Canvas canvas) {
        if (cur == null) return;
        float t = 1;
        if (prev != null) {
            t = Math.min(1f, (SystemClock.uptimeMillis() - fadeStart) / (float) FADE_MS);
            t = t * t * (3 - 2 * t);
            if (t >= 1) prev = null;
        }
        canvas.drawColor(Color.rgb(3, 6, 12));
        if (prev != null) background(canvas, prev, 255);
        background(canvas, cur, prev == null ? 255 : Math.round(255 * t));
        if (prev != null) text(canvas, prev, Math.round(255 * (1 - t)));
        text(canvas, cur, prev == null ? 255 : Math.round(255 * t));
        dots(canvas, cur);
        if (prev != null) postInvalidateOnAnimation();
    }

    private void background(Canvas canvas, Layer l, int alpha) {
        if (l.art != null) {
            art.setAlpha(alpha);
            canvas.drawBitmap(l.art, l.src, dst, art);
            scrimLeft.setAlpha(alpha);
            scrimBottom.setAlpha(alpha);
            canvas.drawRect(0, 0, w, h, scrimLeft);
            canvas.drawRect(0, 0, w, h, scrimBottom);
        } else {
            plain.setAlpha(alpha);
            canvas.drawRect(0, 0, w, h, plain);
        }
    }

    private void text(Canvas canvas, Layer l, int alpha) {
        if (alpha <= 0) return;
        float x = holeL + (holeR - holeL) * .045f;
        metaP.setAlpha(alpha);
        titleP.setAlpha(alpha);
        descP.setAlpha(Math.round(alpha * .85f));
        draw(canvas, l.meta, x, l.yMeta);
        draw(canvas, l.title, x, l.yTitle);
        if (l.desc != null) draw(canvas, l.desc, x, l.yDesc);
        if (l.time != null) {
            timeP.setAlpha(Math.round(alpha * .92f));
            canvas.drawText(l.time, holeL + (holeR - holeL) * .72f, (holeT + holeB) / 2 + 24 * u, timeP);
        }
    }

    private void draw(Canvas canvas, StaticLayout l, float x, float y) {
        canvas.save();
        canvas.translate(x, y);
        l.draw(canvas);
        canvas.restore();
    }

    private void dots(Canvas canvas, Layer l) {
        if (l.dots < 2) return;
        float x = holeL + (holeR - holeL) * .5f, y = holeB - 14 * u, r = 3.5f * u, step = 12 * u;
        for (int i = 0; i < l.dots; i++) {
            boolean on = i == l.dot;
            float len = on ? 16 * u : 2 * r;
            fill.setColor(on ? accent : Color.argb(120, 255, 255, 255));
            rect.set(x, y - r, x + len, y + r);
            canvas.drawRoundRect(rect, r, r, fill);
            x += len + step - 2 * r;
        }
    }
}
