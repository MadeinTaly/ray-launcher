package dev.raylauncher;

import java.util.List;

/** Plain JVM: javac -d out app/src/dev/raylauncher/AdbClient.java test/AdbClientTest.java && java -cp out dev.raylauncher.AdbClientTest */
public class AdbClientTest {
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    public static void main(String[] a) {
        String[] cols = {"package_name", "title", "poster_art_uri", "intent_uri"};
        List<String[]> r = AdbClient.rows("Row: 0 package_name=x.y, title=Trump: potere, politica, accuse, poster_art_uri=https://a/b.webp, intent_uri=z://1\n"
                + "Row: 1 package_name=x.y, title=Oldboy, poster_art_uri=NULL, intent_uri=z://2\nnoise line\n", cols);
        check(r.size() == 2, "two rows, got " + r.size());
        check(r.get(0)[1].equals("Trump: potere, politica, accuse"), "commas stay in the value: " + r.get(0)[1]);
        check(r.get(0)[2].equals("https://a/b.webp"), "poster");
        check(r.get(1)[2] == null, "NULL is null");
        check(r.get(1)[3].equals("z://2"), "last column, trimmed: " + r.get(1)[3]);
        check(AdbClient.rows("No result found.\n", cols).isEmpty(), "no rows");
        check(AdbClient.rows("Error while accessing provider\n", cols).isEmpty(), "errors are no rows");
        System.out.println("AdbClientTest OK");
    }
}
