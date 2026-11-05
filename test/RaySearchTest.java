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
        System.out.println("RaySearchTest OK");
    }
}
