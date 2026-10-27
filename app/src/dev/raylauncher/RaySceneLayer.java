package dev.raylauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.view.View;
import android.graphics.Rect;
import android.os.SystemClock;
import android.widget.FrameLayout;
import java.io.InputStream;

/**
 * Cards painted into the backdrop's scene texture instead of on screen: the animated skin is drawn on top of them
 * and bends them like everything else behind the glass. The views stay in place for focus, clicks and the pointer.
 */
final class RaySceneLayer extends FrameLayout implements Runnable {
    private static final int W = 1280, H = 720;
    private static final long MIN_FRAME_MS = 40;
    private final RayFluidBackdrop backdrop;
    /** Fraction of a card: about its bleed under the frame, so the feather barely enters the hole. */
    static final float FEATHER = .09f;
    private final Rect full = new Rect(0, 0, W, H);
    private final Paint fade = new Paint();
    private Bitmap landscape;
    /** Behind the cards when there is no picture: a skin with a frame is full, nothing of a landscape shows. */
    private int base = Color.BLACK;
    private long lastUpload;
    private boolean queued;

    RaySceneLayer(Context context, RayFluidBackdrop backdrop) { this(context, backdrop, true, Color.BLACK); }

    RaySceneLayer(Context context, RayFluidBackdrop backdrop, boolean picture, int base) {
        super(context);
        this.backdrop = backdrop;
        this.base = base;
        setWillNotDraw(false);
        fade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        if (picture) try (InputStream stream = context.getAssets().open("ray-landscape.jpg")) {
            landscape = BitmapFactory.decodeStream(stream, null, options);
        } catch (Exception ignored) { }
    }

    /** The user's wallpaper (RGB_565 1280x720, owned from now on) instead of the built-in landscape; null restores it. */
    void setBackground(Bitmap wallpaper) {
        Bitmap old = landscape;
        if (wallpaper != null) landscape = wallpaper;
        else try (InputStream stream = getContext().getAssets().open("ray-landscape.jpg")) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            landscape = BitmapFactory.decodeStream(stream, null, options);
        } catch (Exception | OutOfMemoryError ignored) { }
        if (old != null && old != landscape) old.recycle();
        invalidate();
    }

    /** Nothing reaches the screen: any change to the cards schedules a new scene texture. */
    @Override protected void dispatchDraw(Canvas canvas) {
        if (queued) return;
        queued = true;
        postDelayed(this, Math.max(0, MIN_FRAME_MS - (SystemClock.uptimeMillis() - lastUpload)));
    }

    /** Each card fades out towards its edges, so no straight border shows through the glass. */
    @Override protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        if (canvas.isHardwareAccelerated()) return super.drawChild(canvas, child, drawingTime);
        float l = child.getLeft(), t = child.getTop(), r = child.getRight(), b = child.getBottom();
        float fx = (r - l) * FEATHER, fy = (b - t) * FEATHER;
        int layer = canvas.saveLayer(l - fx, t - fy, r + fx, b + fy, null);
        boolean more = super.drawChild(canvas, child, drawingTime);
        edge(canvas, l, t, r, t + fy, l, t, l, t + fy);
        edge(canvas, l, b - fy, r, b, l, b, l, b - fy);
        edge(canvas, l, t, l + fx, b, l, t, l + fx, t);
        edge(canvas, r - fx, t, r, b, r, t, r - fx, t);
        canvas.restoreToCount(layer);
        return more;
    }

    private void edge(Canvas canvas, float l, float t, float r, float b, float x0, float y0, float x1, float y1) {
        fade.setShader(new LinearGradient(x0, y0, x1, y1, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        canvas.drawRect(l, t, r, b, fade);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(this);
        queued = false;
        super.onDetachedFromWindow();
    }

    @Override public void run() {
        queued = false;
        if (getWidth() == 0 || getHeight() == 0) return;
        lastUpload = SystemClock.uptimeMillis();
        Bitmap scene = Bitmap.createBitmap(W, H, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(scene);
        if (landscape != null) canvas.drawBitmap(landscape, null, full, null);
        else canvas.drawColor(base);
        canvas.scale((float) W / getWidth(), (float) H / getHeight());
        super.dispatchDraw(canvas);
        backdrop.setScene(scene);
    }
}
