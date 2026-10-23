package dev.raylauncher;

import android.graphics.Canvas;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import java.io.InputStream;

/** Minimal feedback above the controls already drawn in the glass artwork. */
final class RayGlassDrawable extends Drawable {
    private static Bitmap selectedAtlas;
    private static int themeRow;
    private static ColorMatrixColorFilter horrorTint;
    private static boolean atlasLoaded;
    private final boolean day, focused, selected;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF frame = new RectF();
    private final Rect source = new Rect();
    private float radius;

    RayGlassDrawable(boolean day, boolean focused, boolean selected) {
        this.day = day;
        this.focused = focused;
        this.selected = selected;
    }

    static void configure(Context context, int color, int style) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        themeRow = style == 5 ? 1 : hsv[0] < 65 || hsv[0] > 330 ? 2
                : hsv[0] < 165 ? 3 : hsv[0] < 250 ? 0 : 1;
        float r = Color.red(color) / 255f, g = Color.green(color) / 255f, b = Color.blue(color) / 255f;
        horrorTint = style == 5 ? new ColorMatrixColorFilter(new ColorMatrix(new float[]{
                .3f*r,.5f*r,.2f*r,0,0, .3f*g,.5f*g,.2f*g,0,0, .3f*b,.5f*b,.2f*b,0,0, 0,0,0,1,0})) : null;
        if (atlasLoaded) return;
        atlasLoaded = true;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 2;
        try (InputStream stream = context.getAssets().open("ray-selected-buttons.png")) {
            selectedAtlas = BitmapFactory.decodeStream(stream, null, options);
        } catch (Exception ignored) { }
    }

    @Override protected void onBoundsChange(Rect bounds) {
        frame.set(bounds);
        radius = Math.min(frame.width(), frame.height()) * .18f;
    }

    @Override public void draw(Canvas canvas) {
        if (!focused && !selected) return;
        if (selectedAtlas != null) {
            int height = selectedAtlas.getHeight();
            source.set(0, themeRow * height / 4, selectedAtlas.getWidth(), (themeRow + 1) * height / 4);
            paint.setAlpha(day ? focused ? 230 : 180 : focused ? 150 : 100);
            paint.setColorFilter(horrorTint);
            canvas.drawBitmap(selectedAtlas, source, frame, paint);
            return;
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(day ? (focused ? 0x248ef6ff : 0x165defff)
                : (focused ? 0x2879c9f4 : 0x184b9bce));
        canvas.drawOval(frame, paint);
    }

    @Override public void getOutline(android.graphics.Outline outline) {
        outline.setRoundRect(getBounds(), radius);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
