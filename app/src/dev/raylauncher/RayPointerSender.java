package dev.raylauncher;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.LockSupport;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Phone side of the pointer channel (format in RayPointerUdp): the sensor thread adds relative motion to an
 * accumulator, this thread takes and zeroes it every 8 ms and sends the whole pixels (the fraction stays here).
 * Never queues: a lost datagram loses one micro-move, nothing else. While the TV does not echo, the same deltas
 * go through the fallback (WebSocket, at most every 20 ms, summed).
 */
final class RayPointerSender implements Runnable {
    static final int HZ = 125;
    interface Fallback { void send(int dx, int dy); }

    private final String host, key;
    private final Fallback fallback;
    private volatile boolean running = true;
    private volatile long lastEcho;
    private final Object lock = new Object();
    private double accX, accY;

    RayPointerSender(String host, String key, Fallback fallback) { this.host = host; this.key = key; this.fallback = fallback; }

    void start() {
        Thread t = new Thread(this, "ray-udp-send");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
    }

    void stop() { running = false; }

    /** Called from the sensor thread, any rate. Reference pixels (may be fractional). */
    void add(float dx, float dy) { synchronized (lock) { accX += dx; accY += dy; } }

    void reset() { synchronized (lock) { accX = accY = 0; } }

    /** True while the TV has answered a hello in the last 1.5 s; otherwise the fallback is used. */
    boolean alive() { return running && android.os.SystemClock.elapsedRealtime() - lastEcho < 1500; }

    @Override public void run() {
        DatagramSocket socket = null;
        Mac mac = null;
        try {
            socket = new DatagramSocket();
            socket.connect(InetAddress.getByName(host), RayPointerUdp.PORT);
            mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            final DatagramSocket s = socket;
            Thread rx = new Thread(new Runnable() {
                @Override public void run() {
                    byte[] b = new byte[32];
                    DatagramPacket p = new DatagramPacket(b, b.length);
                    while (running && !s.isClosed()) {
                        try {
                            p.setLength(b.length);
                            s.receive(p);
                            if (p.getLength() == RayPointerUdp.LEN && b[0] == RayPointerUdp.ECHO)
                                lastEcho = android.os.SystemClock.elapsedRealtime();
                        } catch (java.io.IOException e) { return; }
                    }
                }
            }, "ray-udp-recv");
            rx.setDaemon(true);
            rx.start();
        } catch (Exception e) {
            socket = null; // blocked or unresolvable: alive() stays false and the fallback carries the motion
        }
        byte[] b = new byte[RayPointerUdp.LEN];
        DatagramPacket p = new DatagramPacket(b, b.length);
        long period = 1_000_000_000L / HZ, next = System.nanoTime();
        long lastSend = 0, lastHello = 0, lastWs = 0;
        int lastSeq = 0;
        while (running) {
            next += period;
            long wait = next - System.nanoTime();
            if (wait > 0) LockSupport.parkNanos(wait); else next = System.nanoTime();
            long nowMs = android.os.SystemClock.elapsedRealtime();
            int seq = (int) System.currentTimeMillis(); // ms clock: survives an app restart
            if (seq - lastSeq <= 0) seq = lastSeq + 1;
            try {
                if (socket != null && nowMs - lastHello >= 400) {
                    lastHello = nowMs;
                    lastSeq = seq;
                    RayMotionMath.pack(b, RayPointerUdp.HELLO, 0, seq, 0, 0, mac);
                    socket.send(p);
                    continue;
                }
                if (alive()) {
                    int dx, dy;
                    synchronized (lock) {
                        dx = RayMotionMath.whole(accX);
                        dy = RayMotionMath.whole(accY);
                        accX -= dx;
                        accY -= dy;
                    }
                    if (dx == 0 && dy == 0 && nowMs - lastSend < 250) continue; // nothing new: stay silent
                    lastSend = nowMs;
                    lastSeq = seq;
                    RayMotionMath.pack(b, RayPointerUdp.DELTA, 0, seq, dx, dy, mac);
                    socket.send(p);
                } else if (nowMs - lastWs >= 20) {
                    int dx, dy;
                    synchronized (lock) {
                        dx = RayMotionMath.whole(accX);
                        dy = RayMotionMath.whole(accY);
                        accX -= dx;
                        accY -= dy;
                    }
                    if (dx == 0 && dy == 0) continue;
                    lastWs = nowMs;
                    fallback.send(dx, dy);
                }
            } catch (java.io.IOException ignored) { } // network hiccup: the next tick tries again
        }
        if (socket != null) socket.close();
    }
}
