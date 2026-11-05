package dev.raylauncher;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * Whether this device gets the animated background. "auto" (default) asks for a real GPU and memory, then the scene
 * measures its own frames and turns itself off for good on this device if it cannot keep up. Pref "ray_anim":
 * auto, on, off. Without it the home shows the still wallpaper, nothing else changes.
 */
final class RayGraphics {
    static final String PREF = "ray_anim", SLOW = "ray_anim_slow";
    static final long MIN_RAM_MB = 1800;
    static final int MIN_CORES = 4, MIN_GLES = 0x30000;
    /** Average frame time, ms, above which the scene gives up: it aims at 33 ms, 30 fps. */
    static final float SLOW_MS = 55;

    private RayGraphics() { }

    /** Pure rule, test/RayGraphicsTest.java: a GLES 2 GPU (Mali-450 and friends) or a small box stays still. */
    static boolean capable(long ramMb, int cores, int gles, boolean lowRam) {
        return !lowRam && ramMb >= MIN_RAM_MB && cores >= MIN_CORES && gles >= MIN_GLES;
    }

    static boolean animated(Context c) {
        SharedPreferences p = c.getSharedPreferences(DimService.PREFS, Context.MODE_PRIVATE);
        String mode = p.getString(PREF, "auto");
        if ("off".equals(mode)) return false;
        if ("on".equals(mode)) return true;
        return !p.getBoolean(SLOW, false) && capable(c);
    }

    static boolean capable(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mem);
        return capable(mem.totalMem >> 20, Runtime.getRuntime().availableProcessors(),
                am.getDeviceConfigurationInfo().reqGlEsVersion, am.isLowRamDevice());
    }

    /** Called by the scene when it measured too many slow frames. */
    static void markSlow(Context c) {
        c.getSharedPreferences(DimService.PREFS, Context.MODE_PRIVATE).edit().putBoolean(SLOW, true).apply();
        RayDebug.log("animated background off: device too slow");
    }
}
