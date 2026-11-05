package dev.raylauncher;

/** Plain JVM: javac -d out app/src/dev/raylauncher/RaySearch.java test/RaySearchTest.java && java -cp out dev.raylauncher.RaySearchTest */
public class RaySearchTest {
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    public static void main(String[] a) {
        check(RaySearch.matches("Netflix", "net"), "prefix");
        check(RaySearch.matches("La città incantata", "CITTA"), "accents and case");
        check(RaySearch.matches("Ocean Lab, season 1", "lab ocean"), "words in any order");
        check(!RaySearch.matches("Ocean Lab", "ocean mars"), "every word must match");
        check(!RaySearch.matches("Ocean Lab", "   "), "an empty query matches nothing");
        check(!RaySearch.matches(null, "x"), "no text");
        check(RaySearch.rank("Rai", "rai") == 0, "exact name first");
        check(RaySearch.rank("RaiPlay", "RAI") == 0, "name starts with the query");
        check(RaySearch.rank("Prime Rai", "rai") == 2, "word start");
        check(RaySearch.rank("Trailer", "rai") == 3, "substring");
        check(RaySearch.rank("Trailer", "xqzw") == -1, "no match");
        check(RaySearch.rank("Cinema", "cin xyz") == -1, "every word must be in the name");
        java.util.List<String> names = new java.util.ArrayList<>(java.util.Arrays.asList("Trailer", "Prime Rai", "RaiPlay", "Rai"));
        java.util.Collections.sort(names, new java.util.Comparator<String>() {
            @Override public int compare(String x, String y) { return Integer.compare(RaySearch.rank(x, "rai"), RaySearch.rank(y, "rai")); }
        });
        check(names.get(0).startsWith("Rai") && names.get(1).startsWith("Rai") && names.get(2).equals("Prime Rai") && names.get(3).equals("Trailer"), "Rai before Trailer: " + names);
        System.out.println("RaySearchTest OK");
    }
}
