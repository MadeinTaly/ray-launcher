package dev.raylauncher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Current weather for the city the user typed (Open-Meteo, no key, no location lookup: only the city name and
 * its coordinates leave the device). OFF until the user accepts consentText(): without it nothing here touches the
 * network. Cached in prefs for 30 min; callers read cached() on the UI thread and call refresh() off it.
 */
final class RayWeather {
    private static final long TTL = 30 * 60 * 1000L;

    /** Shown on the TV and on the phone before the weather is switched on. */
    static String consentText() {
        return I18n.t("For the weather the TV contacts the chosen service (Open-Meteo, servers in the EU; or OpenWeatherMap) "
                + "sending the city name and its coordinates; the service sees the TV's IP address. No other data is sent.",
                "Per il meteo la TV contatta il servizio scelto (Open-Meteo, server in UE; oppure OpenWeatherMap) "
                + "inviando il nome della città e le sue coordinate; il servizio vede l’indirizzo IP della TV. "
                + "Nessun altro dato viene inviato.");
    }

    private RayWeather() { }

    static boolean consent(Context context) { return prefs(context).getBoolean("ray_weather_consent", false); }

    /** Accepting enables the weather; refusing (or revoking) forgets city, cache and sun times. */
    static void setConsent(Context context, boolean accepted) {
        SharedPreferences.Editor e = prefs(context).edit().putBoolean("ray_weather_consent", accepted);
        if (!accepted) e.remove("ray_weather_city").remove("ray_weather_now").remove("ray_weather_at")
                .remove("ray_weather_rise").remove("ray_weather_set");
        e.apply();
    }

    /** Today's {sunrise, sunset} in minutes after 00:00 local time at the weather city, or null (no consent, no city, no data yet). */
    static int[] sun(Context context) {
        SharedPreferences p = prefs(context);
        if (!consent(context) || p.getString("ray_weather_city", "").isEmpty() || !p.contains("ray_weather_rise")) return null;
        return new int[]{p.getInt("ray_weather_rise", 0), p.getInt("ray_weather_set", 0)};
    }

    /** {temp °C, WMO code, is_day} plus the city, or null without consent, without a city, or before the first fetch. */
    static JSONObject cached(Context context) {
        SharedPreferences p = prefs(context);
        if (!consent(context) || p.getString("ray_weather_city", "").isEmpty()) return null;
        try {
            String raw = p.getString("ray_weather_now", "");
            return raw.isEmpty() ? null : new JSONObject(raw);
        } catch (Exception e) {
            return null;
        }
    }

    /** Geocodes the city and fetches it once; false if the name is unknown or there is no consent. Blocking: never on the UI thread. */
    static boolean setCity(Context context, String city) {
        city = city == null ? "" : city.trim();
        SharedPreferences.Editor e = prefs(context).edit();
        if (city.isEmpty()) {
            e.remove("ray_weather_city").remove("ray_weather_now").remove("ray_weather_at")
                    .remove("ray_weather_rise").remove("ray_weather_set").apply();
            return true;
        }
        if (!consent(context) || city.length() > 80) return false;
        try {
            JSONObject geo = get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=it&name="
                    + URLEncoder.encode(city, "UTF-8"));
            JSONArray results = geo.optJSONArray("results");
            if (results == null || results.length() == 0) return false;
            JSONObject r = results.getJSONObject(0);
            e.putString("ray_weather_city", r.optString("name", city))
                    .putFloat("ray_weather_lat", (float) r.getDouble("latitude"))
                    .putFloat("ray_weather_lon", (float) r.getDouble("longitude"))
                    .putLong("ray_weather_at", 0).apply();
        } catch (Exception ex) {
            return false;
        }
        refresh(context);
        return true;
    }

    /** Providers: Open-Meteo needs nothing; OpenWeatherMap uses the user's own API key. */
    static final String[] PROVIDERS = {"openmeteo", "openweathermap"};

    /** Picks the provider and its key; the next refresh() uses them. */
    static boolean setProvider(Context context, String provider, String key) {
        if (!java.util.Arrays.asList(PROVIDERS).contains(provider)) return false;
        key = key == null ? "" : key.trim();
        if (!"openmeteo".equals(provider) && !key.matches("[A-Za-z0-9]{8,64}")) return false;
        prefs(context).edit().putString("ray_weather_provider", provider).putString("ray_weather_key", key)
                .putLong("ray_weather_at", 0).apply();
        return true;
    }

    /** Fetches when the cache is older than 30 min. No consent: returns before any network use. Blocking: never on the UI thread. */
    static void refresh(Context context) {
        SharedPreferences p = prefs(context);
        String city = p.getString("ray_weather_city", "");
        if (!consent(context) || city.isEmpty() || System.currentTimeMillis() - p.getLong("ray_weather_at", 0) < TTL) return;
        String provider = p.getString("ray_weather_provider", "openmeteo"), key = p.getString("ray_weather_key", "");
        float lat = p.getFloat("ray_weather_lat", 0), lon = p.getFloat("ray_weather_lon", 0);
        int rise = -1, set = -1;
        try {
            JSONObject now = new JSONObject().put("city", city);
            if ("openweathermap".equals(provider)) {
                JSONObject r = get("https://api.openweathermap.org/data/2.5/weather?units=metric&lat=" + lat + "&lon=" + lon
                        + "&appid=" + key);
                int id = r.getJSONArray("weather").getJSONObject(0).optInt("id", 800);
                JSONObject sys = r.optJSONObject("sys");
                long t = r.optLong("dt");
                now.put("temp", Math.round(r.getJSONObject("main").getDouble("temp"))).put("code", owm(id))
                        .put("day", sys == null || t >= sys.optLong("sunrise") && t < sys.optLong("sunset"));
                if (sys != null && sys.has("sunrise") && sys.has("sunset")) { // epoch seconds, shown in the TV's time zone
                    rise = minutes(sys.optLong("sunrise") * 1000);
                    set = minutes(sys.optLong("sunset") * 1000);
                }
            } else {
                JSONObject r = get("https://api.open-meteo.com/v1/forecast?current=temperature_2m,weather_code,is_day"
                        + "&daily=sunrise,sunset&forecast_days=1&timezone=auto&latitude=" + lat + "&longitude=" + lon);
                JSONObject c = r.getJSONObject("current");
                now.put("temp", Math.round(c.getDouble("temperature_2m"))).put("code", c.optInt("weather_code"))
                        .put("day", c.optInt("is_day", 1) == 1);
                JSONObject d = r.optJSONObject("daily");
                if (d != null) { // "2026-10-22T07:41", already in the city's local time
                    rise = clock(d.getJSONArray("sunrise").optString(0));
                    set = clock(d.getJSONArray("sunset").optString(0));
                }
            }
            SharedPreferences.Editor e = p.edit().putString("ray_weather_now", now.toString())
                    .putLong("ray_weather_at", System.currentTimeMillis());
            if (rise >= 0 && set > rise) e.putInt("ray_weather_rise", rise).putInt("ray_weather_set", set);
            e.apply();
        } catch (Exception ignored) {
            // offline, bad key or API down: keep the last value
        }
    }

    private static int minutes(long millis) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(millis);
        return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
    }

    /** "yyyy-MM-ddTHH:mm" to minutes after 00:00, -1 when malformed. */
    static int clock(String iso) {
        int t = iso == null ? -1 : iso.indexOf('T');
        if (t < 0 || iso.length() < t + 6) return -1;
        try { return Integer.parseInt(iso.substring(t + 1, t + 3)) * 60 + Integer.parseInt(iso.substring(t + 4, t + 6)); }
        catch (NumberFormatException e) { return -1; }
    }

    /** OpenWeatherMap condition id → WMO code (the scale label() and glyph() speak). */
    static int owm(int id) {
        if (id >= 200 && id < 300) return 95;
        if (id >= 300 && id < 400) return 51;
        if (id >= 500 && id < 600) return id >= 520 ? 80 : 61;
        if (id >= 600 && id < 700) return 71;
        if (id >= 700 && id < 800) return 45;
        return id == 800 ? 0 : id <= 802 ? 2 : 3;
    }

    /** Short Italian label for a WMO weather code. */
    static String label(int code) {
        if (code == 0) return I18n.t("Clear", "Sereno");
        if (code <= 2) return I18n.t("Partly cloudy", "Poco nuvoloso");
        if (code == 3) return I18n.t("Overcast", "Coperto");
        if (code == 45 || code == 48) return I18n.t("Fog", "Nebbia");
        if (code >= 51 && code <= 67) return I18n.t("Rain", "Pioggia");
        if (code >= 71 && code <= 77) return I18n.t("Snow", "Neve");
        if (code >= 80 && code <= 82) return I18n.t("Showers", "Rovesci");
        if (code >= 85 && code <= 86) return I18n.t("Snow", "Neve");
        if (code >= 95) return I18n.t("Thunderstorm", "Temporale");
        return "";
    }

    /** One glyph that renders on Android 9 without emoji fonts. */
    static String glyph(int code, boolean day) {
        if (code == 0) return day ? "☀" : "☾";
        if (code <= 3) return "☁";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "❄";
        if (code >= 95) return "ϟ";
        if (code >= 45 && code <= 48) return "≋";
        return "☂";
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(DimService.PREFS, Context.MODE_PRIVATE);
    }

    private static JSONObject get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(5000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < 256 * 1024) out.write(buf, 0, n);
            return new JSONObject(out.toString("UTF-8"));
        } finally {
            c.disconnect();
        }
    }
}
