package dev.raylauncher;

import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Plain JVM: javac -d out app/src/dev/raylauncher/RayMotionMath.java test/RayAirMouseTest.java && java -cp out dev.raylauncher.RayAirMouseTest */
public class RayAirMouseTest {
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    public static void main(String[] a) throws Exception {
        // gyro: phone flat (no gravity yet -> wz), turning left must move left, tipping the top edge up must move up
        RayMotionMath.Integrator g = new RayMotionMath.Integrator();
        long t = 1_000_000_000L;
        g.gyro(0, 0, 0, t, 100);
        t += 5_000_000;
        check(g.gyro(0, 0, 1f, t, 100) && g.dx < 0 && g.dy == 0, "left");
        t += 5_000_000;
        check(g.gyro(0, 0, -1f, t, 100) && g.dx > 0, "right");
        t += 5_000_000;
        check(g.gyro(1f, 0, 0, t, 100) && g.dy < 0 && g.dx == 0, "up");
        // dead zone: tremor and bias produce nothing
        t += 5_000_000;
        check(!g.gyro(.01f, .01f, .019f, t, 100) && g.dx == 0 && g.dy == 0, "deadzone");
        // upright phone (up = +y): yaw comes from wy
        RayMotionMath.Integrator u = new RayMotionMath.Integrator();
        for (int i = 0; i < 50; i++) u.gravity(0, 9.8f, 0);
        t = 1_000_000_000L;
        u.gyro(0, 0, 0, t, 100);
        t += 5_000_000;
        check(u.gyro(0, 1f, 0, t, 100) && u.dx < 0, "upright yaw");
        // a pause longer than 50 ms must not integrate
        t += 500_000_000L;
        check(!u.gyro(0, 5f, 0, t, 100), "gap");
        // acceleration: 3 rad/s moves more than 3x one 1 rad/s over the same time; slow stays 1x-ish; cap holds
        float slow = RayMotionMath.gain(.1f, 100) * .1f, fast = RayMotionMath.gain(3f, 100) * 3f;
        check(fast > 30 * slow * .9f && RayMotionMath.gain(100, 100) == RayMotionMath.gain(1000, 100), "accel curve");
        check(RayMotionMath.gain(1, 200) == 2 * RayMotionMath.gain(1, 100), "sensitivity scales");
        // sum of tiny steps == integral (no filter, no lag): 1 s at 1 rad/s in 200 steps, accelerated gain 1+K
        RayMotionMath.Integrator s = new RayMotionMath.Integrator();
        t = 1; s.gyro(0, 0, 0, t, 100);
        double sum = 0;
        for (int i = 0; i < 200; i++) { t += 5_000_000; s.gyro(0, 0, -1f, t, 100); sum += s.dx; }
        double want = RayMotionMath.BASE * (1 + RayMotionMath.K);
        check(Math.abs(sum - want) < want * .001, "integral " + sum + " vs " + want);

        // sender accumulator: fractions carried, i16 clamp
        double acc = 0; int sent = 0;
        for (int i = 0; i < 1000; i++) { acc += .3; int w = RayMotionMath.whole(acc); acc -= w; sent += w; }
        check(sent == 300 && Math.abs(acc) < .5, "carry " + sent);
        check(RayMotionMath.whole(1e9) == 32767 && RayMotionMath.whole(-1e9) == -32767, "i16 clamp");

        // TV: clamp without storing overshoot, reversing moves at once
        float x = .5f;
        x = RayMotionMath.move(x, 100000, 1f / 1920);
        check(x == 1f, "right edge");
        x = RayMotionMath.move(x, -192, 1f / 1920);
        check(Math.abs(x - .9f) < 1e-6, "no overshoot stored: " + x);
        x = RayMotionMath.move(x, -100000, 1f / 1920);
        check(x == 0f, "left edge");
        // many packets summed in one frame == the same moves one by one
        float one = .5f, sum2 = .5f; int total = 0;
        for (int i = 0; i < 17; i++) { one = RayMotionMath.move(one, 37, 1f / 1920); total += 37; }
        sum2 = RayMotionMath.move(sum2, total, 1f / 1920);
        check(Math.abs(one - sum2) < 1e-5, "sum == sequence");

        // packet roundtrip + HMAC
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("pairing-key".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] b = new byte[RayMotionMath.LEN];
        RayMotionMath.pack(b, 4, 0, 0x89ABCDEF, -1234, 32767, mac);
        check(b[0] == 4 && RayMotionMath.seqOf(b) == 0x89ABCDEF && RayMotionMath.dxOf(b) == -1234 && RayMotionMath.dyOf(b) == 32767, "roundtrip");
        check(RayMotionMath.macOk(b, mac), "mac ok");
        b[6] ^= 1;
        check(!RayMotionMath.macOk(b, mac), "tampered dx rejected");
        Mac other = Mac.getInstance("HmacSHA256");
        other.init(new SecretKeySpec("other".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        RayMotionMath.pack(b, 4, 0, 1, 5, 5, mac);
        check(!RayMotionMath.macOk(b, other), "wrong key rejected");
        // replay rule (as the TV applies it): seq - last <= 0 dropped, gaps fine, wraparound fine
        int last = 100;
        check(!(105 - last <= 0) && (100 - last <= 0) && (99 - last <= 0) && !(0x80000001 - 0x7FFFFFFF <= 0), "seq rule");
        System.out.println("RayAirMouseTest OK");
    }
}
