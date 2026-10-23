package dev.raylauncher;

import android.content.Context;
import java.util.Calendar;

/** Day or night for the skins' variants: pref ray_daynight = auto (default) | day | night. */
final class RayDayNight {
    static final String PREF = "ray_daynight";
    /** Fallback when the weather (sunrise and sunset) is off: 07:00 to 19:30 local. */
    static final int RISE = 7 * 60, SET = 19 * 60 + 30;

    private RayDayNight() { }

    static String mode(Context c) {
        String m = c.getSharedPreferences(DimService.PREFS, Context.MODE_PRIVATE).getString(PREF, "auto");
        return "day".equals(m) || "night".equals(m) ? m : "auto";
    }

    /** auto, day, night, auto... */
    static String next(String mode) { return "auto".equals(mode) ? "day" : "day".equals(mode) ? "night" : "auto"; }

    static void set(Context c, String mode) {
        c.getSharedPreferences(DimService.PREFS, Context.MODE_PRIVATE).edit().putString(PREF, mode).apply();
    }

    static boolean isDay(Context c) {
        String m = mode(c);
        if (!"auto".equals(m)) return "day".equals(m);
        int[] sun = RayWeather.sun(c); // null unless the weather is on, has a city and fetched once
        Calendar now = Calendar.getInstance();
        return isDay(now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE), sun == null ? RISE : sun[0], sun == null ? SET : sun[1]);
    }

    static boolean isDay(int minute, int rise, int set) { return minute >= rise && minute < set; }
}
