package dev.raylauncher;

import java.security.MessageDigest;
import javax.crypto.Mac;

/**
 * Air-mouse math: angular velocity in, pointer delta out (reference pixels, 1920 px = screen width), like a mouse.
 * No filtering and no absolute state: the delta of one sensor event is exactly what the hand did in that interval.
 */
final class RayMotionMath {
    private RayMotionMath() { }

    static final int LEN = 18;

    /** Pixels per radian at sensitivity 100 (about 40 degrees sweeps the 1920 px wide reference screen). */
    static final float BASE = 2700f;
    /** Rad/s below which a rate is hand tremor / gyro bias and is ignored. */
    static final float DEAD = .02f;
    /** Pointer acceleration: gain = 1 + K * speed(rad/s), capped at MAX_GAIN. Slow = precise, fast flick = far. */
    static final float K = .45f, MAX_GAIN = 3.5f;

    static final class Integrator {
        float dx, dy;                   // result of the last step, reference pixels (fractions kept by the caller)
        private float ux, uy, uz = 1;   // unit "up" in device coordinates (low-passed accelerometer)
        private boolean haveUp;
        private long lastNs;
        private float lastYaw, lastPitch;
        private boolean haveAngles;

        void reset() { lastNs = 0; haveAngles = false; dx = dy = 0; }

        /** For the debug channel: where this integrator thinks "up" is, device axes. */
        float[] up() { return haveUp ? new float[]{ux, uy, uz} : null; }

        /** Accelerometer at rest reads +g along "up"; heavy low-pass so hand shake does not tilt the yaw axis. */
        void gravity(float ax, float ay, float az) {
            float n = (float) Math.sqrt(ax * ax + ay * ay + az * az);
            if (!(n > 1f)) return;
            ax /= n; ay /= n; az /= n;
            if (!haveUp) { ux = ax; uy = ay; uz = az; haveUp = true; return; }
            ux += (ax - ux) * .05f; uy += (ay - uy) * .05f; uz += (az - uz) * .05f;
        }

        /**
         * TYPE_GYROSCOPE event (rad/s, device axes: x right, y up the screen, z out of the screen). Both pointer axes
         * are world axes, so any roll of the hand (flat, upright, on its side like a Wii remote) moves the same way.
         * Yaw is the rotation about "up": turning left is +yaw and must move the pointer left, dx = -yaw*dt.
         * Pitch is the rotation about "right" = forward x up: raising the nose is +pitch and must move it up (screen
         * y grows downward), dy = -pitch*dt. Forward is the top edge (y) levelled; when the top edge points at the
         * ceiling it says nothing, so there the back of the phone (-z) takes over.
         */
        boolean gyro(float wx, float wy, float wz, long ns, int sensitivity) {
            dx = dy = 0;
            long prev = lastNs;
            lastNs = ns;
            if (prev == 0 || !finite(wx) || !finite(wy) || !finite(wz)) return false;
            float dt = (ns - prev) / 1e9f;
            if (dt <= 0 || dt > .05f) return false; // first event after a pause: no jump
            if (!haveUp) return emit(-wz, -wx, dt, sensitivity);
            float yaw = wx * ux + wy * uy + wz * uz;
            // forward = a * (y levelled) + (1 - a) * (-z levelled); a = 1 up to 45 degrees of top edge lift, 0 from 70
            float a = Math.max(0, Math.min(1, (.94f - Math.abs(uy)) / .23f));
            float fx = -a * uy * ux + (1 - a) * uz * ux;
            float fy = a * (1 - uy * uy) + (1 - a) * uz * uy;
            float fz = -a * uy * uz + (1 - a) * (uz * uz - 1);
            float n = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
            if (n < .01f) return emit(-yaw, -wx, dt, sensitivity); // ponytail: exact singular pose, x stays the pitch axis
            fx /= n; fy /= n; fz /= n;
            float rx = fy * uz - fz * uy, ry = fz * ux - fx * uz, rz = fx * uy - fy * ux;
            return emit(-yaw, -(wx * rx + wy * ry + wz * rz), dt, sensitivity);
        }

        /** Fallback without gyroscope: successive GAME_ROTATION_VECTOR yaw/pitch (SensorManager.getOrientation). */
        boolean angles(float yaw, float pitch, long ns, int sensitivity) {
            dx = dy = 0;
            long prev = lastNs;
            lastNs = ns;
            if (!finite(yaw) || !finite(pitch)) return false;
            float py = lastYaw, pp = lastPitch;
            lastYaw = yaw; lastPitch = pitch;
            boolean had = haveAngles;
            haveAngles = true;
            float dt = (ns - prev) / 1e9f;
            if (!had || prev == 0 || dt <= 0 || dt > .1f) return false;
            // azimuth grows turning right, pitch grows tipping the top edge down: both already screen-oriented
            return emit(wrap(yaw - py) / dt, wrap(pitch - pp) / dt, dt, sensitivity);
        }

        private boolean emit(float rateX, float rateY, float dt, int sensitivity) {
            rateX = dead(rateX);
            rateY = dead(rateY);
            if (rateX == 0 && rateY == 0) return false;
            float g = gain((float) Math.hypot(rateX, rateY), sensitivity);
            dx = rateX * dt * g;
            dy = rateY * dt * g;
            return true;
        }
    }

    static float dead(float rate) { return Math.abs(rate) < DEAD ? 0 : rate; }

    static float gain(float speed, int sensitivity) {
        return BASE * Math.max(1, Math.min(400, sensitivity)) / 100f * Math.min(MAX_GAIN, 1 + K * speed);
    }

    static boolean finite(float v) { return !Float.isNaN(v) && !Float.isInfinite(v); }

    private static float wrap(float a) {
        while (a > Math.PI) a -= 2 * Math.PI;
        while (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }

    /** TV side: apply a reference-pixel delta to a normalized position. Clamps; the overshoot is dropped. */
    static float move(float pos, int ref, float scale) {
        float p = pos + ref * scale;
        return p < 0 ? 0 : p > 1 ? 1 : p;
    }

    /** Sender side: take the whole pixels out of an accumulator, return the remaining fraction. */
    static int whole(double acc) {
        long r = Math.round(acc);
        return (int) Math.max(-32767, Math.min(32767, r));
    }

    /** Fill b (LEN bytes) with a signed datagram. */
    static void pack(byte[] b, int type, int flags, int seq, int dx, int dy, Mac mac) {
        b[0] = (byte) type;
        b[1] = (byte) flags;
        b[2] = (byte) (seq >> 24); b[3] = (byte) (seq >> 16); b[4] = (byte) (seq >> 8); b[5] = (byte) seq;
        b[6] = (byte) (dx >> 8); b[7] = (byte) dx; b[8] = (byte) (dy >> 8); b[9] = (byte) dy;
        mac.update(b, 0, 10);
        System.arraycopy(mac.doFinal(), 0, b, 10, 8);
    }

    static int seqOf(byte[] b) { return ((b[2] & 255) << 24) | ((b[3] & 255) << 16) | ((b[4] & 255) << 8) | (b[5] & 255); }
    static int dxOf(byte[] b) { return (short) (((b[6] & 255) << 8) | (b[7] & 255)); }
    static int dyOf(byte[] b) { return (short) (((b[8] & 255) << 8) | (b[9] & 255)); }

    static boolean macOk(byte[] b, Mac mac) {
        mac.update(b, 0, 10);
        return MessageDigest.isEqual(java.util.Arrays.copyOf(mac.doFinal(), 8), java.util.Arrays.copyOfRange(b, 10, 18));
    }
}
