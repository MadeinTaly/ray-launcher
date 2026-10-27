package dev.raylauncher;
/** Weather code mapping and sun-time parsing (plain JVM, android.jar on the classpath). */
public class RayWeatherTest {
    public static void main(String[] a) {
        java.util.Locale.setDefault(java.util.Locale.ITALIAN); // the texts below are the Italian ones
        if (RayWeather.owm(800) != 0 || RayWeather.owm(211) != 95 || RayWeather.owm(601) != 71 || RayWeather.owm(804) != 3 || RayWeather.owm(521) != 80) throw new AssertionError("owm");
        if (!RayWeather.label(0).equals("Sereno") || !RayWeather.label(95).equals("Temporale")) throw new AssertionError("label");
        if (RayWeather.clock("2026-10-22T07:41") != 461 || RayWeather.clock("2026-10-22T18:05") != 1085 || RayWeather.clock("x") != -1 || RayWeather.clock(null) != -1) throw new AssertionError("clock");
        if (java.util.Arrays.asList(RayWeather.PROVIDERS).contains("unknown")) throw new AssertionError("providers");
        if (RayWeather.PROVIDERS.length != 2 || !RayWeather.PROVIDERS[0].equals("openmeteo") || !RayWeather.PROVIDERS[1].equals("openweathermap")) throw new AssertionError("providers2");
        if (!RayWeather.consentText().startsWith("Per il meteo la TV contatta il servizio scelto (Open-Meteo, server in UE; oppure OpenWeatherMap)")) throw new AssertionError("consent");
        if (!RayDayNight.isDay(8 * 60, 7 * 60, 19 * 60 + 30) || RayDayNight.isDay(20 * 60, 7 * 60, 19 * 60 + 30) || RayDayNight.isDay(6 * 60, 7 * 60, 19 * 60 + 30)) throw new AssertionError("daynight");
        java.util.Locale.setDefault(java.util.Locale.ENGLISH);
        if (!RayWeather.label(0).equals("Clear") || !RayWeather.label(95).equals("Thunderstorm")) throw new AssertionError("label en");
        System.out.println("weather ok");
    }
}
