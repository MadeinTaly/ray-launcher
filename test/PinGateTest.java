package dev.raylauncher;

/** Plain JVM: javac -d out app/src/dev/raylauncher/PinGate.java test/PinGateTest.java && java -cp out dev.raylauncher.PinGateTest */
public class PinGateTest {
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    public static void main(String[] a) {
        long t = 1_000_000;
        PinGate g = new PinGate();
        check(g.finish(t, "a", "0000").equals("closed"), "nothing active");
        String pin = g.start(t);
        check(pin != null && pin.matches("[0-9]{4}"), "4 digits");
        check(g.start(t + 1000) == null, "start rate limited");
        check(pin.equals(g.start(t + 3000)), "active code is kept");
        // 5 wrong from one client lock that client out, another client still pairs
        for (int i = 0; i < 5; i++) check(g.finish(t + 3100, "a", wrong(pin)).equals("pin"), "wrong " + i);
        check(g.finish(t + 3200, "a", pin).equals("locked"), "locked even with the right code");
        check(g.finish(t + 3300, "b", pin).equals("ok"), "other client pairs");
        check(g.finish(t + 3400, "b", pin).equals("closed"), "single use");
        // expiry: 120 s after the last start
        long s = t + 10_000;
        pin = g.start(s);
        check(g.finish(s + PinGate.TTL - 1, "c", wrong(pin)).equals("pin"), "still open");
        check(g.finish(s + PinGate.TTL, "c", pin).equals("closed"), "expired");
        // 20 wrong from many clients close the code and delay the next start
        s += 200_000;
        pin = g.start(s);
        for (int i = 0; i < 20; i++) g.finish(s + 1, "x" + i, wrong(pin));
        check(g.finish(s + 2, "z", pin).equals("closed"), "closed after 20 wrong");
        check(g.start(s + 10_000) == null, "cool-down");
        check(g.start(s + PinGate.COOL + 2) == null, "the window that expired above made this wait double");
        check(g.start(s + 2 * PinGate.COOL + 2) != null, "start after cool-down");
        // the network cannot extend or reopen a window at will; someone at the TV always can
        PinGate n = new PinGate();
        long t0 = 1000;
        check(n.start(t0) != null, "first");
        n.start(t0 + PinGate.GAP + 1);
        check(n.code(t0 + PinGate.TTL + 1) == null, "not extended from the network");
        check(n.start(t0 + PinGate.TTL + PinGate.GAP + 2) == null, "an unused window makes the next wait");
        check(n.startLocal(t0 + PinGate.TTL + PinGate.GAP + 3) != null, "the TV is never blocked");
        n.close();
        check(n.code(t0 + PinGate.TTL + PinGate.GAP + 4) == null, "closed");
        // proof: same inputs same hash; another key, nonce or address differs
        String pr = PinGate.proof("k", "aa", "10.0.0.2");
        check(pr.equals(PinGate.proof("k", "aa", "10.0.0.2")) && pr.length() == 64, "proof stable");
        check(!pr.equals(PinGate.proof("x", "aa", "10.0.0.2")) && !pr.equals(PinGate.proof("k", "ab", "10.0.0.2")) && !pr.equals(PinGate.proof("k", "aa", "10.0.0.3")), "proof binds key, nonce, address");
        System.out.println("PinGate ok");
    }

    static String wrong(String pin) { return pin.equals("0000") ? "0001" : "0000"; }
}
