package dev.raylauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

/** The scene behind the home (wallpaper or the skin's gradient), painted as one bitmap. The view owns and recycles it. */
final class RayBackdrop extends View {
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect dst = new Rect();
    private Bitmap scene;

    RayBackdrop(Context context) {
        super(context);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        setFocusable(false);
    }

    void setScene(Bitmap next) {
        Bitmap old = scene;
        scene = next;
        invalidate();
        if (old != null && old != next) old.recycle();
    }

    void release() {
        Bitmap old = scene;
        scene = null;
        if (old != null) old.recycle();
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.rgb(3, 8, 17));
        if (scene == null || scene.isRecycled()) return;
        dst.set(0, 0, getWidth(), getHeight());
        canvas.drawBitmap(scene, null, dst, paint);
    }
}
