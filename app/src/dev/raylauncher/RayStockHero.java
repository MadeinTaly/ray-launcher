package dev.raylauncher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;
import java.util.Random;

/**
 * The stage of the home: what has the focus becomes the picture, in one of three compositions (style.layout).
 * cinema: the picture fills the screen, scrims keep the text readable. rivista: flat paper, big serif title, the
 * picture cut hard into the right of the page, or the title's initial when there is none. lacca: lacquer with gold
 * cracks, the picture inside a moon. Pictures crossfade; two half-size bitmaps are reused, nothing allocates in onDraw.
 */
final class RayStockHero extends View {
    private static final String CINEMA = "cinema", RIVISTA = "rivista", LACCA = "lacca";
    
    private final RaySkin skin;
    private final int w, h;
    private final float u;
    private final Rect target = new Rect();
    private final RectF targetF = new RectF(), bar = new RectF();
    private Bitmap front, back;
    private Canvas backCanvas;
    private boolean hasFront, hasBack;
    private float fade = 1;
    private ValueAnimator animator;
    private final Paint blit = new Paint(Paint.FILTER_BITMAP_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrimBottom = new Paint(), scrimLeft = new Paint(), scrimTop = new Paint();
    private final Paint moonFront = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG), moonBack = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint titleP = new TextPaint(Paint.ANTI_ALIAS_FLAG), metaP = new TextPaint(Paint.ANTI_ALIAS_FLAG), initialP = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Bitmap surface;
    private final int textWidth, maxLines;
    private StaticLayout title, meta;
    private String initial;
    private float progress = -1;
    private boolean grid;
    private float textBottom = .715f;

    RayStockHero(Context context, RaySkin skin, int w, int h) {
        super(context);
        this.skin = skin;
        this.w = w;
        this.h = h;
        u = h / 720f;
        setFocusable(false);
        Typeface head;
        float size;
        if (RIVISTA.equals(skin.layout)) {
            target.set(Math.round(w * .46f), 0, w, Math.round(h * .545f));
            textWidth = Math.round(w * .39f);
            maxLines = 3;
            head = Fonts.serif();
            size = 58 * u;
        } else if (LACCA.equals(skin.layout)) {
            int r = Math.round(h * .17f), cx = Math.round(w * .74f), cy = Math.round(h * .37f); // clear of the discs and the rows
            target.set(cx - r, cy - r, cx + r, cy + r);
            textWidth = Math.round(w * .46f);
            maxLines = 2;
            head = Fonts.serif();
            size = 50 * u;
        } else {
            target.set(0, 0, w, h);
            textWidth = Math.round(w * .6f);
            maxLines = 2;
            head = skin.cubeA != 0 ? Fonts.chakra(context) : Fonts.display(context, 500);
            size = 52 * u;
            titleP.setShadowLayer(14 * u, 0, 2 * u, 0x99000000);
            metaP.setShadowLayer(8 * u, 0, u, 0x99000000);
        }
        targetF.set(target);
        int reduce = RayGraphics.capable(context) ? 2 : 3; // the stage is the biggest memory of the home: smaller on a small box
        front = Bitmap.createBitmap(target.width() / reduce, target.height() / reduce, Bitmap.Config.ARGB_8888);
        back = Bitmap.createBitmap(target.width() / reduce, target.height() / reduce, Bitmap.Config.ARGB_8888);
        backCanvas = new Canvas(back);
        titleP.setTypeface(head);
        titleP.setTextSize(size);
        titleP.setColor(skin.textColor);
        metaP.setTypeface(Fonts.regular(context));
        metaP.setTextSize(19 * u);
        metaP.setColor(skin.mutedColor);
        initialP.setTypeface(head);
        initialP.setTextSize(target.height() * (LACCA.equals(skin.layout) ? .62f : .56f));
        initialP.setTextAlign(Paint.Align.CENTER);
        initialP.setColor(Color.argb(235, 255, 255, 255));
        line.setStyle(Paint.Style.STROKE);
        int rgb = skin.light ? 0xffffff : 0x06080e;
        int r = rgb >> 16, g = (rgb >> 8) & 255, b = rgb & 255;
        scrimBottom.setShader(new LinearGradient(0, h * .46f, 0, h, Color.TRANSPARENT, Color.argb(240, r, g, b), Shader.TileMode.CLAMP));
        scrimLeft.setShader(new LinearGradient(0, 0, w * .7f, 0, Color.argb(165, r, g, b), Color.TRANSPARENT, Shader.TileMode.CLAMP));
        scrimTop.setShader(new LinearGradient(0, 0, 0, h * .24f, Color.argb(150, r, g, b), Color.TRANSPARENT, Shader.TileMode.CLAMP));
        surface = LACCA.equals(skin.layout) ? lacquer(w / 2, h / 2, skin) : null;
    }

    /** Polished lacquer: a deep vertical gradient and a few gold repairs wandering across it, always the same. */
    private static Bitmap lacquer(int bw, int bh, RaySkin skin) {
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, bw * .35f, bh, skin.surface, skin.surface2, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, bw, bh, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(skin.ring);
        Random random = new Random(1471995);
        Path path = new Path();
        for (int i = 0; i < 4; i++) {
            float x = i % 2 == 0 ? 0 : bw, y = bh * (.04f + random.nextFloat() * .55f), dir = i % 2 == 0 ? 1 : -1;
            path.reset();
            path.moveTo(x, y);
            int steps = 5 + random.nextInt(4);
            for (int s = 0; s < steps; s++) {
                float nx = x + dir * bw * (.05f + random.nextFloat() * .07f), ny = y + bh * (random.nextFloat() - .5f) * .1f;
                path.quadTo(x + (nx - x) * .5f, y + bh * (random.nextFloat() - .5f) * .05f, nx, ny);
                x = nx;
                y = ny;
            }
            p.setAlpha(70 + random.nextInt(70));
            p.setStrokeWidth(.6f + random.nextFloat() * .6f);
            c.drawPath(path, p);
        }
        return b;
    }

    /** Where the title block ends, fraction of the height: above the rows, which are one or two. */
    void textBottom(float fraction) { textBottom = fraction; invalidate(); }

    /** cover may be null: then tint (0 = none) and the title's initial stand in where the composition needs a picture. */
    void show(Bitmap cover, String titleText, String metaText, float progress, int tint) {
        grid = false;
        String text = titleText == null ? "" : titleText;
        initial = cover == null && !text.isEmpty() ? text.substring(0, text.offsetByCodePoints(0, 1)).toUpperCase(java.util.Locale.getDefault()) : null;
        paintBack(cover, tint);
        title = layout(text, titleP, textWidth, maxLines);
        meta = metaText == null || metaText.isEmpty() ? null : layout(metaText, metaP, textWidth, 1);
        this.progress = progress;
        crossfade();
    }

    /** Replaces the initial that stands in for a picture, e.g. with a number. */
    void initial(String text) { initial = text; invalidate(); }

    /** The whole-screen grid: a heading at the top, no picture. */
    void showGrid(String heading, String metaText) {
        grid = true;
        initial = null;
        paintBack(null, 0);
        title = layout(heading, titleP, Math.round(w * .5f), 1);
        meta = metaText == null || metaText.isEmpty() ? null : layout(metaText, metaP, Math.round(w * .5f), 1);
        progress = -1;
        crossfade();
    }

    private void paintBack(Bitmap cover, int tint) {
        hasBack = cover != null || tint != 0;
        backCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        if (cover != null) {
            int bw = back.getWidth(), bh = back.getHeight();
            float s = Math.max((float) bw / cover.getWidth(), (float) bh / cover.getHeight());
            int sw = Math.round(bw / s), sh = Math.round(bh / s), x = (cover.getWidth() - sw) / 2, y = (cover.getHeight() - sh) / 2;
            backCanvas.drawBitmap(cover, new Rect(x, y, x + sw, y + sh), new Rect(0, 0, bw, bh), blit);
        } else if (tint != 0) backCanvas.drawColor(tint | 0xff000000);
    }

    private void crossfade() {
        Bitmap b = front; front = back; back = b;
        boolean had = hasFront; hasFront = hasBack; hasBack = had;
        backCanvas = new Canvas(back);
        if (LACCA.equals(skin.layout)) {
            moonFront.setShader(shader(front));
            moonBack.setShader(shader(back));
        }
        if (animator != null) animator.cancel();
        fade = 0;
        animator = ValueAnimator.ofFloat(0, 1).setDuration(320);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) { fade = (Float) a.getAnimatedValue(); invalidate(); }
        });
        animator.start();
    }

    private BitmapShader shader(Bitmap b) {
        BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale((float) target.width() / b.getWidth(), (float) target.height() / b.getHeight());
        m.postTranslate(target.left, target.top);
        s.setLocalMatrix(m);
        return s;
    }

    @Override protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }

    private static StaticLayout layout(String text, TextPaint paint, int width, int maxLines) {
        StaticLayout l = new StaticLayout(text, paint, width, Layout.Alignment.ALIGN_NORMAL, 1.04f, 0f, false);
        if (l.getLineCount() <= maxLines) return l;
        String cut = text.substring(0, l.getLineEnd(maxLines - 1)).trim();
        if (cut.length() > 1) cut = cut.substring(0, cut.length() - 1).trim() + "…";
        return new StaticLayout(cut, paint, width, Layout.Alignment.ALIGN_NORMAL, 1.04f, 0f, false);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) { setMeasuredDimension(w, h); }

    @Override protected void onDraw(Canvas canvas) {
        if (title == null) return;
        boolean rivista = RIVISTA.equals(skin.layout), lacca = LACCA.equals(skin.layout);
        if (lacca) canvas.drawBitmap(surface, null, new Rect(0, 0, w, h), blit);
        else if (rivista) canvas.drawColor(skin.surface);
        if (lacca) drawMoon(canvas);
        else drawPicture(canvas);
        if (!rivista && !lacca) {
            canvas.drawRect(0, 0, w, h, scrimBottom);
            if (hasFront) { // a film under the text; the bare background is dark enough and stays clear
                canvas.drawRect(0, 0, w, h, scrimTop);
                canvas.drawRect(0, 0, w, h, scrimLeft);
            }
            if (grid) canvas.drawColor(Color.argb(170, 6, 8, 14));
        }
        if (initial != null && rivista && !grid) {
            float y = target.exactCenterY() - (initialP.descent() + initialP.ascent()) / 2;
            initialP.setColor(hasFront ? Color.argb(235, 255, 255, 255) : skin.ring);
            canvas.drawText(initial, target.exactCenterX(), y, initialP);
        }
        if (rivista && !grid) {
            line.setColor(skin.textColor);
            line.setStrokeWidth(Math.max(1, u));
            canvas.drawLine(w * .05f, h * (textBottom + .012f), w * .95f, h * (textBottom + .012f), line);
        }
        float x = w * .05f;
        if (grid) {
            canvas.save(); canvas.translate(x, h * .055f); title.draw(canvas); canvas.restore();
            if (meta != null) { canvas.save(); canvas.translate(x, h * .055f + title.getHeight() + 2 * u); meta.draw(canvas); canvas.restore(); }
            return;
        }
        float y = h * (rivista ? textBottom - .012f : textBottom);
        if (progress >= 0) {
            y -= 5 * u;
            bar.set(x, y, x + 260 * u, y + 5 * u);
            fill.setColor(skin.panelStrong);
            canvas.drawRoundRect(bar, 3 * u, 3 * u, fill);
            bar.right = x + 260 * u * Math.min(1, progress);
            fill.setColor(skin.ring);
            canvas.drawRoundRect(bar, 3 * u, 3 * u, fill);
            y -= 10 * u;
        }
        if (meta != null) {
            y -= meta.getHeight();
            canvas.save(); canvas.translate(x, y); meta.draw(canvas); canvas.restore();
            y -= 6 * u;
        }
        y -= title.getHeight();
        canvas.save(); canvas.translate(x, y); title.draw(canvas); canvas.restore();
    }

    private void drawPicture(Canvas canvas) {
        if (grid && !hasBack) { if (fade >= 1) return; }
        if (hasBack && fade < 1 && !grid) canvas.drawBitmap(back, null, target, blit);
        if (hasFront) {
            blit.setAlpha(Math.round(255 * fade));
            canvas.drawBitmap(front, null, target, blit);
            blit.setAlpha(255);
        }
    }

    private void drawMoon(Canvas canvas) {
        if (grid) return;
        float cx = targetF.centerX(), cy = targetF.centerY(), r = targetF.width() / 2;
        fill.setColor(skin.moon);
        canvas.drawCircle(cx, cy, r, fill);
        if (hasBack && fade < 1) { moonBack.setAlpha(255); canvas.drawCircle(cx, cy, r, moonBack); }
        if (hasFront) { moonFront.setAlpha(Math.round(255 * fade)); canvas.drawCircle(cx, cy, r, moonFront); }
        if (initial != null) {
            initialP.setColor(hasFront ? Color.argb(235, 255, 255, 255) : skin.surface);
            float y = cy - (initialP.descent() + initialP.ascent()) / 2;
            canvas.drawText(initial, cx, y, initialP);
        }
        line.setColor(skin.ring);
        line.setStrokeWidth(3 * u);
        canvas.drawCircle(cx, cy, r, line);
    }
}
