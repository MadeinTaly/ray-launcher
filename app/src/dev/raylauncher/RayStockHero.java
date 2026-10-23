package dev.raylauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;
import java.util.Locale;

/**
 * Hero of the frameless skins (docs/SKIN_FORMAT.md): cover art at the top right, faded to the left and to the
 * bottom, and a text block at the left (eyebrow, title, remaining time + progress) anchored above the buttons.
 * The art is masked once per change into a reused bitmap; nothing is allocated while the view draws.
 */
final class RayStockHero extends View {
    private final RaySkin skin;
    private final int w, h;
    private final float u;
    private final Bitmap art;
    private final Canvas artCanvas;
    private final Rect artRect = new Rect(), dst = new Rect(), src = new Rect();
    private final Paint blit = new Paint(Paint.FILTER_BITMAP_FLAG), mask = new Paint(), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint eyebrowP = new TextPaint(Paint.ANTI_ALIAS_FLAG), titleP = new TextPaint(Paint.ANTI_ALIAS_FLAG),
            metaP = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();
    private final PorterDuffXfermode dstIn = new PorterDuffXfermode(PorterDuff.Mode.DST_IN), atop = new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP);
    private boolean hasArt;
    private StaticLayout eyebrow, title, meta;
    private float progress = -1;

    RayStockHero(Context context, RaySkin skin, int w, int h) {
        super(context);
        this.skin = skin;
        this.w = w;
        this.h = h;
        u = h / 720f;
        setFocusable(false);
        artRect.set(Math.round(w * .38f), 0, w, Math.round(h * .56f));
        art = Bitmap.createBitmap(artRect.width() / 2, artRect.height() / 2, Bitmap.Config.ARGB_8888);
        artCanvas = new Canvas(art);
        mask.setXfermode(dstIn);
        dst.set(0, 0, art.getWidth(), art.getHeight());
        eyebrowP.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        eyebrowP.setTextSize(12 * u);
        eyebrowP.setLetterSpacing(.133f);
        eyebrowP.setColor(skin.mutedColor);
        titleP.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleP.setTextSize(40 * u);
        titleP.setLetterSpacing(-.01f);
        titleP.setColor(skin.textColor);
        metaP.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        metaP.setTextSize(14 * u);
        metaP.setColor(skin.mutedColor);
    }

    /** art may be null. eyebrow/meta may be empty; progress < 0 hides the bar. */
    void show(Bitmap cover, String eyebrowText, String titleText, String metaText, float progress) {
        hasArt = cover != null;
        if (hasArt) {
            artCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            float s = Math.max((float) art.getWidth() / cover.getWidth(), (float) art.getHeight() / cover.getHeight());
            int sw = Math.round(art.getWidth() / s), sh = Math.round(art.getHeight() / s);
            int x = (cover.getWidth() - sw) / 2, y = (cover.getHeight() - sh) / 2;
            src.set(x, y, x + sw, y + sh);
            artCanvas.drawBitmap(cover, src, dst, blit);
            int aw = art.getWidth(), ah = art.getHeight();
            // the controls at the top right sit on the art: a veil in the theme's scrim colour keeps them readable
            int rgb = skin.light ? 0xffffff : 0x080a14;
            artCanvas.drawColor(Color.argb(skin.light ? 50 : 56, rgb >> 16, (rgb >> 8) & 255, rgb & 255), PorterDuff.Mode.SRC_ATOP);
            mask.setXfermode(atop);
            mask.setShader(new LinearGradient(0, 0, 0, ah * .45f, Color.argb(skin.light ? 170 : 150, rgb >> 16, (rgb >> 8) & 255, rgb & 255), Color.TRANSPARENT, Shader.TileMode.CLAMP));
            artCanvas.drawRect(0, 0, aw, ah, mask);
            mask.setXfermode(dstIn);
            mask.setShader(new LinearGradient(0, 0, aw * .42f, 0, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP));
            artCanvas.drawRect(0, 0, aw, ah, mask);
            mask.setShader(new LinearGradient(0, ah * .55f, 0, ah, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP));
            artCanvas.drawRect(0, 0, aw, ah, mask);
        }
        int tw = Math.round(w * .40f);
        eyebrow = eyebrowText == null || eyebrowText.isEmpty() ? null : layout(eyebrowText.toUpperCase(Locale.getDefault()), eyebrowP, tw, 1);
        title = layout(titleText == null ? "" : titleText, titleP, tw, 2);
        meta = metaText == null || metaText.isEmpty() ? null : layout(metaText, metaP, tw - Math.round(140 * u), 1);
        this.progress = progress;
        invalidate();
    }

    private static StaticLayout layout(String text, TextPaint paint, int width, int maxLines) {
        StaticLayout l = new StaticLayout(text, paint, width, Layout.Alignment.ALIGN_NORMAL, 1.08f, 0f, false);
        if (l.getLineCount() <= maxLines) return l;
        String cut = text.substring(0, l.getLineEnd(maxLines - 1)).trim();
        if (cut.length() > 1) cut = cut.substring(0, cut.length() - 1).trim() + "…";
        return new StaticLayout(cut, paint, width, Layout.Alignment.ALIGN_NORMAL, 1.08f, 0f, false);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) { setMeasuredDimension(w, h); }

    @Override protected void onDraw(Canvas canvas) {
        if (title == null) return;
        if (hasArt) canvas.drawBitmap(art, null, artRect, blit);
        float x = w * .05f, y = h * .39f;
        if (meta != null || progress >= 0) {
            float mh = meta == null ? 0 : meta.getHeight();
            y -= Math.max(mh, 3 * u);
            if (meta != null) { canvas.save(); canvas.translate(x, y); meta.draw(canvas); canvas.restore(); }
            if (progress >= 0) {
                float bx = x + (meta == null ? 0 : meta.getLineWidth(0) + 10 * u), by = y + Math.max(mh, 3 * u) / 2;
                bar.set(bx, by - 1.5f * u, bx + 120 * u, by + 1.5f * u);
                fill.setColor(skin.panelStrong);
                canvas.drawRoundRect(bar, 2 * u, 2 * u, fill);
                bar.right = bx + 120 * u * Math.min(1, progress);
                fill.setColor(skin.textColor);
                canvas.drawRoundRect(bar, 2 * u, 2 * u, fill);
            }
            y -= 10 * u;
        }
        y -= title.getHeight();
        canvas.save(); canvas.translate(x, y); title.draw(canvas); canvas.restore();
        if (eyebrow != null) {
            y -= 8 * u + eyebrow.getHeight();
            canvas.save(); canvas.translate(x, y); eyebrow.draw(canvas); canvas.restore();
        }
    }
}
