package dev.raylauncher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.view.accessibility.AccessibilityEvent;

/** Lends its window to the dimmer and remote pointer for accessibility overlays. */
public class DimAccessibilityService extends AccessibilityService {
    static volatile DimAccessibilityService instance;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        RayGlobalControl.accessibilityChanged();
        refresh();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (instance == this) instance = null;
        RayGlobalControl.accessibilityChanged();
        refresh();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        RayGlobalControl.accessibilityChanged();
        super.onDestroy();
    }

    private void refresh() {
        Compat.startForegroundService(this, new Intent(this, DimService.class).putExtra("refresh", true));
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.getPackageName() != null) {
            String pkg = event.getPackageName().toString();
            if (!pkg.equals(RayMedia.windowPackage)) {
                RayMedia.windowPackage = pkg;
                RaySocket.stateChanged();
            }
        }
    }

    /** A tap at (x, y) in screen pixels, for the remote pointer over other apps when there is no root. */
    boolean tap(int x, int y) {
        Path path = new Path();
        path.moveTo(x, y);
        try {
            return dispatchGesture(new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 50)).build(), null, null);
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public void onInterrupt() {
    }
}
