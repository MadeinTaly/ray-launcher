package dev.raylauncher;

import java.text.Normalizer;
import java.util.Locale;

/** Text matching for the search of the home: no accents, no case, every word of the query. test/RaySearchTest.java. */
final class RaySearch {
    private RaySearch() { }

    static String plain(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).trim();
    }

    static boolean matches(String text, String query) {
        String t = plain(text), q = plain(query);
        if (q.isEmpty()) return false;
        for (String word : q.split("\\s+")) if (!t.contains(word)) return false;
        return true;
    }
}
