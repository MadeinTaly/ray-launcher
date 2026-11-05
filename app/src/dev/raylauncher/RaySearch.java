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

    /** Where an app name stands for a query: 0 the name starts with it (or is it), 2 every word starts a word of the name, 3 the words are inside it, -1 no match. Continue watching sits at 1. */
    static int rank(String name, String query) {
        if (!matches(name, query)) return -1;
        String t = plain(name), q = plain(query);
        if (t.startsWith(q)) return 0;
        t = " " + t.replaceAll("[^\\p{L}\\p{N}]+", " ");
        for (String word : q.split("\\s+")) if (!t.contains(" " + word)) return 3;
        return 2;
    }
}
