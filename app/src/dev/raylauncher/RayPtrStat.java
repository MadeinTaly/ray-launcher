package dev.raylauncher;

import android.util.Log;

import java.util.Arrays;

/** Debug only: logs p50/p95/max of the gaps between events, every 5 s, tag RayPtr. */
final class RayPtrStat {
    static final boolean ON = false; // set true to log inter-arrival stats (adb logcat -s RayPtr)
    private final String name;
    private final long[] gaps = new long[2048];
    private int n;
    private long last, windowStart;

    RayPtrStat(String name) { this.name = name; }

    synchronized void tick() {
        long now = System.nanoTime();
        if (last != 0 && n < gaps.length) gaps[n++] = now - last;
        last = now;
        if (windowStart == 0) windowStart = now;
        if (now - windowStart > 5_000_000_000L) {
            if (n > 0) {
                long[] s = Arrays.copyOf(gaps, n);
                Arrays.sort(s);
                Log.i("RayPtr", name + " n=" + n + " p50=" + s[n / 2] / 1000 + "us p95=" + s[(int) (n * .95)] / 1000
                        + "us max=" + s[n - 1] / 1000 + "us");
            }
            n = 0;
            windowStart = now;
        }
    }
}
