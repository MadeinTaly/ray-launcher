package dev.raylauncher;

/**
 * Compile with android.jar on the classpath (the class also reads the device):
 * javac -cp "$ANDROID_JAR" -sourcepath app/src -d out test/RayGraphicsTest.java; run with java -cp "out;$ANDROID_JAR" dev.raylauncher.RayGraphicsTest
 */
public class RayGraphicsTest {
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    public static void main(String[] a) {
        check(RayGraphics.capable(2048, 4, 0x30002, false), "mid box");
        check(RayGraphics.capable(8192, 8, 0x30002, false), "good TV");
        check(!RayGraphics.capable(2048, 4, 0x20000, false), "GLES 2 only");
        check(!RayGraphics.capable(1024, 4, 0x30002, false), "1 GB");
        check(!RayGraphics.capable(2048, 2, 0x30002, false), "2 cores");
        check(!RayGraphics.capable(4096, 8, 0x30002, true), "low RAM flag");
        System.out.println("RayGraphicsTest OK");
    }
}
