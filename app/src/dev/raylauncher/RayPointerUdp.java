package dev.raylauncher;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Choreographer;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Air-mouse pointer channel: relative motion from the phone, summed, applied once per vsync on its own render thread, never smoothed.
 * Packet (18 bytes, big endian): type(4=delta, 6=hello, 7=echo) flags(0) seq(u32 = sender ms clock) dx(i16) dy(i16) mac(8).
 * dx/dy are reference pixels: 1920 of them are the screen width on both axes (pixels are square); the TV scales.
 * mac = first 8 bytes of HMAC-SHA256(pairing key, bytes 0..9): authenticates the sender without any session state,
 * and since seq is inside the MAC a captured datagram can only be replayed in order, i.e. dropped as stale.
 * Datagrams that are not newer than the last accepted seq are dropped; a missing one is never waited for (it was a
 * micro-move). A hello is answered with the same datagram as type 7; the phone falls back to the WebSocket
 * (remote.delta, summed too) when it stops hearing it. Layout v1 (absolute x/y, types 1-3) is gone.
 */
final class RayPointerUdp implements Runnable {
    static final int PORT = 8767, LEN = RayMotionMath.LEN, REF_W = 1920;
    static final byte DELTA = 4, HELLO = 6, ECHO = 7;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Handler render;
    private static RayPointerUdp instance;
    private static final RayPtrStat FRAME = new RayPtrStat("vsync-frame");
    private static final RayPtrStat ARRIVE = new RayPtrStat("udp-arrive");

    // motion accumulator, shared by the UDP thread and the WebSocket reader (reference pixels)
    private static final Object ACC = new Object();
    private static int accX, accY;
    private static DimService svc;
    private static final AtomicBoolean queued = new AtomicBoolean();
    // render thread only
    private static boolean scheduled, dirty;
    private static long lastMove, lastFocus;
    private static int frames, sn;
    private static final long[] st = new long[16];
    private static final float[] sx = new float[16], sy = new float[16];
    private static long sumX, sumY; // debug (RayPtrStat.ON): total reference pixels applied

    private final DimService service;
    private final DatagramSocket socket;
    private int lastSeq;
    private boolean haveSeq;
    private String macKey;
    private Mac mac;

    private RayPointerUdp(DimService service, DatagramSocket socket) { this.service = service; this.socket = socket; }

    static synchronized void start(DimService service) {
        if (instance != null) return;
        try {
            DatagramSocket s = new DatagramSocket(PORT);
            instance = new RayPointerUdp(service, s);
            Thread t = new Thread(instance, "ray-udp");
            t.setDaemon(true);
            t.setPriority(Thread.MAX_PRIORITY);
            t.start();
        } catch (java.io.IOException ignored) { } // port busy: phones simply stay on the WebSocket
    }

    static synchronized void stop() {
        if (instance != null) instance.socket.close();
        instance = null;
    }

    @Override public void run() {
        byte[] buf = new byte[64];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        while (!socket.isClosed()) {
            try {
                p.setLength(buf.length);
                socket.receive(p);
                if (p.getLength() != LEN || !verify(buf)) continue;
                int seq = RayMotionMath.seqOf(buf);
                if (haveSeq && seq - lastSeq <= 0) continue; // out of order or replayed
                haveSeq = true;
                lastSeq = seq;
                if (buf[0] == HELLO) {
                    buf[0] = ECHO;
                    socket.send(new DatagramPacket(buf, LEN, p.getSocketAddress()));
                } else if (buf[0] == DELTA) {
                    if (RayPtrStat.ON) ARRIVE.tick();
                    delta(service, RayMotionMath.dxOf(buf), RayMotionMath.dyOf(buf));
                }
            } catch (java.io.IOException e) {
                if (socket.isClosed()) return;
            } catch (RuntimeException ignored) { }
        }
    }

    private boolean verify(byte[] b) {
        String key = service.pairingKey();
        if (key == null) return false;
        try {
            if (mac == null || !key.equals(macKey)) {
                mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                macKey = key;
            }
            return RayMotionMath.macOk(b, mac);
        } catch (java.security.GeneralSecurityException e) { return false; }
    }

    /** Add relative motion (any thread). Applied at the next vsync, on the pointer render thread, with everything else that arrived. */
    static void delta(DimService s, int dx, int dy) {
        synchronized (ACC) { accX += dx; accY += dy; svc = s; }
        if (queued.compareAndSet(false, true)) render().post(KICK);
    }

    private static synchronized Handler render() {
        if (render == null) {
            HandlerThread t = new HandlerThread("ray-ptr", android.os.Process.THREAD_PRIORITY_DISPLAY);
            t.start();
            render = new Handler(t.getLooper());
        }
        return render;
    }

    private static final Runnable KICK = new Runnable() {
        @Override public void run() {
            queued.set(false);
            if (scheduled) return;
            scheduled = true;
            Choreographer.getInstance().postFrameCallback(FRAME_CB);
        }
    };

    // the UI thread only ever gets work that is rate-limited by construction: one pending focus search, one pending fallback move
    private static final AtomicBoolean focusQueued = new AtomicBoolean(), moveQueued = new AtomicBoolean();
    private static final Runnable FOCUS = new Runnable() {
        @Override public void run() { focusQueued.set(false); RayLauncherActivity.focusUnderPointer(); }
    };

    private static void focus(long nowMs) {
        lastFocus = nowMs;
        if (focusQueued.compareAndSet(false, true)) MAIN.post(FOCUS);
    }

    /** Overlay not up yet (or no overlay permission): the UI thread creates it / moves the in-activity arrow, latest wins. */
    private static void fallback(final DimService s) {
        if (!moveQueued.compareAndSet(false, true)) return;
        MAIN.post(new Runnable() {
            @Override public void run() {
                moveQueued.set(false);
                try {
                    // note: x/y read here and stored by move() are two steps; a delta landing between them is lost (fallback only)
                    RayLauncherActivity.command(s, new JSONObject().put("type", "remote.motion")
                            .put("x", RayGlobalControl.x()).put("y", RayGlobalControl.y()).put("nofocus", true));
                } catch (Exception ignored) { }
            }
        });
    }

    private static float speed(long nowMs) { // px/s over the last 100 ms of moves
        int last = (sn - 1) & 15, first = last;
        for (int k = 1; k < Math.min(sn, 16); k++) {
            int i = (sn - 1 - k) & 15;
            if (nowMs - st[i] > 100) break;
            first = i;
        }
        float dt = Math.max(st[last] - st[first], 30) / 1000f;
        return (float) Math.hypot(sx[last] - sx[first], sy[last] - sy[first]) / dt;
    }

    private static final Choreographer.FrameCallback FRAME_CB = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameNs) {
            int ax, ay;
            DimService s;
            synchronized (ACC) { ax = accX; ay = accY; accX = accY = 0; s = svc; }
            if (s == null) { scheduled = false; return; }
            long now = frameNs / 1000000L;
            boolean moved = false;
            float x = RayGlobalControl.x(), y = RayGlobalControl.y();
            if (ax != 0 || ay != 0) {
                android.util.DisplayMetrics dm = s.getResources().getDisplayMetrics();
                // clamp to the screen and forget the overshoot: reversing moves the pointer at once, like a PC mouse
                float nx = RayMotionMath.move(x, ax, 1f / REF_W);
                float ny = RayMotionMath.move(y, ay, (float) dm.widthPixels / dm.heightPixels / REF_W);
                if (nx != x || ny != y) {
                    x = nx; y = ny;
                    moved = dirty = true;
                    lastMove = now;
                    int i = sn++ & 15;
                    st[i] = now; sx[i] = x * dm.widthPixels; sy[i] = y * dm.heightPixels;
                }
                if (RayPtrStat.ON) { sumX += ax; sumY += ay; }
            }
            if (moved) {
                if (RayPtrStat.ON) FRAME.tick();
                if (!RayGlobalControl.glide(x, y)) fallback(s);
                // the focus search walks every view and repaints the hero/scene: only while (nearly) slow, never mid-sweep
                if (now - lastFocus >= 50 && speed(now) < 300) focus(now);
                if (RayPtrStat.ON && (++frames & 127) == 0) android.util.Log.i("RayPtr", "applied sum=" + sumX + "," + sumY + " pos=" + x + "," + y);
            } else if (dirty && now - lastMove >= 80) {
                dirty = false; // stopped: one last search
                focus(now);
            }
            if (moved || dirty) Choreographer.getInstance().postFrameCallback(this);
            else scheduled = false;
        }
    };
}
