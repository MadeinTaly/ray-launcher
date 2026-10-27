package dev.raylauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

final class RayGlobalControl {
    private static final Object LOCK = new Object();
    private static final Object SHELL_LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Context app;
    private static WindowManager wm;
    private static volatile Overlay pointer;
    private static Context pointerOwner;
    private static boolean pointerFailed;
    private static boolean probeStarted;
    private static boolean rootChecked;
    private static boolean rootAvailable;
    private static boolean shellReady;
    private static int generation;
    private static String helper;
    private static Process shell;
    private static BufferedWriter shellInput;
    private static LinkedBlockingQueue<String> shellOutput;
    private static int commandId;
    private static volatile float x = .5f;
    private static volatile float y = .5f;

    private RayGlobalControl() { }

    static boolean move(Context context, double nx, double ny, boolean forceWake) {
        if (Double.isNaN(nx) || Double.isInfinite(nx) || Double.isNaN(ny) || Double.isInfinite(ny)
                || nx < 0 || nx > 1 || ny < 0 || ny > 1) return false;
        synchronized (LOCK) {
            initialize(context);
            x = (float) nx;
            y = (float) ny;
        }
        if (Looper.myLooper() != Looper.getMainLooper()) return false;
        return showPointer(forceWake);
    }

    /** Current pointer position, normalized 0..1 (the one move() last stored). */
    static float x() { return x; }
    static float y() { return y; }

    /** Render thread: store the position and draw it on the overlay. False when the overlay isn't ready (caller falls back to the UI thread). */
    static boolean glide(float nx, float ny) {
        x = nx;
        y = ny;
        Overlay p = pointer;
        return p != null && p.draw(nx, ny, false);
    }

    static boolean injectKey(Context context, int keyCode) {
        int linuxCode = keyCode;
        if (!allowedKey(linuxCode)) return false;
        if (keyCode >= 85) { // media keys reach the active session through the audio service, no permission needed
            android.media.AudioManager am = (android.media.AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.dispatchMediaKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode));
                am.dispatchMediaKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode));
                return true;
            }
        }
        if (globalAction(keyCode)) return true;
        initialize(context);
        return runRootCommand("keyevent " + linuxCode);
    }

    /**
     * Back and Home through RAY's accessibility service, and on Android 13+ the D-pad too (GLOBAL_ACTION_DPAD_*):
     * the remote then works over other apps without root.
     */
    static boolean globalAction(int keyCode) {
        DimAccessibilityService a11y = DimAccessibilityService.instance;
        if (a11y == null) return false;
        int action = keyCode == 4 ? 1 : keyCode == 3 ? 2 : 0; // GLOBAL_ACTION_BACK, GLOBAL_ACTION_HOME
        if (Build.VERSION.SDK_INT >= 33) {
            if (keyCode == 19) action = 16;      // GLOBAL_ACTION_DPAD_UP
            else if (keyCode == 20) action = 17; // DPAD_DOWN
            else if (keyCode == 21) action = 18; // DPAD_LEFT
            else if (keyCode == 22) action = 19; // DPAD_RIGHT
            else if (keyCode == 23) action = 20; // DPAD_CENTER
        }
        return action != 0 && a11y.performGlobalAction(action);
    }

    static boolean tap(Context context) {
        initialize(context);
        int width = context.getResources().getDisplayMetrics().widthPixels;
        int height = context.getResources().getDisplayMetrics().heightPixels;
        int px, py;
        synchronized (LOCK) {
            px = Math.max(0, Math.min(width - 1, Math.round(x * width)));
            py = Math.max(0, Math.min(height - 1, Math.round(y * height)));
        }
        DimAccessibilityService a11y = DimAccessibilityService.instance;
        if (a11y != null && a11y.tap(px, py)) return true;
        return runRootCommand("tap " + px + " " + py);
    }

    static void hidePointer() {
        Runnable hide = new Runnable() {
            @Override public void run() {
                synchronized (LOCK) {
                    if (pointer != null) pointer.hide();
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) hide.run(); else MAIN.post(hide);
    }

    static JSONObject state(Context context) {
        initialize(context);
        boolean overlayAllowed = DimAccessibilityService.instance != null
                || Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context);
        boolean active;
        boolean root, checked, ready, failed;
        synchronized (LOCK) {
            active = pointer != null && pointer.shown;
            root = rootAvailable;
            checked = rootChecked;
            ready = shellReady;
            failed = pointerFailed;
        }
        JSONObject result = new JSONObject();
        try {
            result.put("rootChecked", checked).put("rootAvailable", root)
                    .put("globalInput", ready).put("motionPointer", overlayAllowed && !failed)
                    .put("pointerActive", active);
        } catch (JSONException ignored) { }
        return result;
    }

    static int controlTier(Context context) {
        JSONObject state = state(context);
        return state.optBoolean("globalInput") && state.optBoolean("motionPointer") ? 2 : 0;
    }

    static void shutdown() {
        final int token;
        Process oldShell;
        synchronized (LOCK) {
            generation++;
            token = generation;
            probeStarted = false;
            rootChecked = false;
            rootAvailable = false;
            shellReady = false;
            oldShell = shell;
            shell = null;
            shellInput = null;
            shellOutput = null;
        }
        closeProcess(oldShell);
        Runnable remove = new Runnable() {
            @Override public void run() {
                synchronized (LOCK) {
                    if (token != generation || pointer == null) return;
                    try { wm.removeView(pointer); } catch (RuntimeException ignored) { }
                    pointer = null;
                    pointerOwner = null;
                    wm = null;
                    pointerFailed = false;
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) remove.run(); else MAIN.post(remove);
    }

    private static void initialize(Context context) {
        synchronized (LOCK) {
            if (app == null) app = context.getApplicationContext();
            if (!probeStarted) {
                probeStarted = true;
                final int token = generation;
                Thread probe = new Thread(new Runnable() {
                    @Override public void run() { probeRoot(token); }
                }, "ray-root-probe");
                probe.setDaemon(true);
                probe.start();
            }
        }
    }

    static void accessibilityChanged() {
        synchronized (LOCK) {
            if (pointer != null && pointerOwner != app && pointerOwner != DimAccessibilityService.instance) {
                pointer.hide();
                try { wm.removeView(pointer); } catch (RuntimeException ignored) { }
                pointer = null;
                pointerOwner = null;
                wm = null;
            }
            pointerFailed = false;
        }
        RaySocket.stateChanged();
    }

    private static long overlayAt;
    private static volatile boolean overlayOk;

    /** canDrawOverlays costs ~10 ms here: first answer is synchronous, later refreshes run off the UI thread. */
    private static boolean overlayAllowed() {
        long now = android.os.SystemClock.uptimeMillis();
        if (overlayAt == 0) {
            overlayOk = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(app);
            overlayAt = now;
        } else if (now - overlayAt > 2000) {
            overlayAt = now;
            new Thread(new Runnable() {
                @Override public void run() { overlayOk = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(app); }
            }, "ray-overlay-check").start();
        }
        return overlayOk;
    }

    private static boolean showPointer(boolean forceWake) {
        synchronized (LOCK) {
            Context owner = DimAccessibilityService.instance;
            boolean accessibility = owner != null;
            if (!accessibility) {
                if (!overlayAllowed()) return false;
                owner = app;
            }
            if (pointer != null && pointerOwner != owner) {
                pointer.hide();
                try { wm.removeView(pointer); } catch (RuntimeException ignored) { }
                pointer = null;
            }
            if (pointer == null) {
                Overlay created = new Overlay(owner);
                wm = (WindowManager) owner.getSystemService(Context.WINDOW_SERVICE);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        -1, -1, accessibility ? 2032 : Build.VERSION.SDK_INT >= 26 ? 2038 : 2003,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.LEFT;
                lp.alpha = Compat.maxObscuringOpacity(app);
                try {
                    wm.addView(created, lp);
                    pointer = created;
                    pointerOwner = owner;
                    pointerFailed = false;
                } catch (RuntimeException e) {
                    pointerFailed = true;
                    return false;
                }
            }
            pointer.draw(x, y, forceWake);
            return true;
        }
    }

    private static void probeRoot(final int token) {
        boolean available = false;
        String[] candidates = {"/system/xbin/su"};
        for (String candidate : candidates) {
            Process process = null;
            try {
                ProcessBuilder builder = new ProcessBuilder(candidate, "0", "sh", "-c", "id -u");
                process = builder.redirectErrorStream(true).start();
                final BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream()));
                final String[] line = new String[1];
                Thread reader = new Thread(new Runnable() {
                    @Override public void run() {
                        try { line[0] = output.readLine(); } catch (IOException ignored) { }
                    }
                }, "ray-root-probe-output");
                reader.setDaemon(true);
                reader.start();
                long deadline = System.currentTimeMillis() + 1500;
                while (System.currentTimeMillis() < deadline) {
                    try {
                        int exit = process.exitValue();
                        reader.join(250);
                        available = exit == 0 && "0".equals(line[0]);
                        break;
                    } catch (IllegalThreadStateException e) {
                        Thread.sleep(20);
                    }
                }
            } catch (Exception ignored) {
            } finally {
                closeProcess(process);
            }
            if (available) {
                synchronized (LOCK) { if (token == generation) helper = candidate; }
                break;
            }
        }
        synchronized (LOCK) {
            if (token != generation) return;
            rootChecked = true;
            rootAvailable = available;
            if (!available) return;
        }
        startRootShell(token);
    }

    private static void startRootShell(int token) {
        Process process = null;
        try {
            String executable;
            synchronized (LOCK) { executable = helper; }
            if (executable == null) return;
            ProcessBuilder builder = new ProcessBuilder(executable, "0", "sh");
            process = builder.redirectErrorStream(true).start();
            final LinkedBlockingQueue<String> output = new LinkedBlockingQueue<String>();
            final BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            Thread readThread = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        String line;
                        while ((line = reader.readLine()) != null) output.offer(line);
                    } catch (IOException ignored) { }
                }
            }, "ray-root-shell-output");
            readThread.setDaemon(true);
            readThread.start();
            BufferedWriter input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            synchronized (LOCK) {
                if (token != generation) { closeProcess(process); return; }
                shell = process;
                shellInput = input;
                shellOutput = output;
            }
            if (execute("if [ \"$(id -u)\" = 0 ]; then echo __RAY_ROOT_READY__; fi", "__RAY_ROOT_READY__")) {
                synchronized (LOCK) { if (token == generation) shellReady = true; }
                RaySocket.stateChanged();
            } else {
                synchronized (LOCK) {
                    if (shell == process) { shell = null; shellInput = null; shellOutput = null; }
                }
                closeProcess(process);
            }
        } catch (Exception ignored) {
            closeProcess(process);
        }
    }

    private static boolean runRootCommand(String command) {
        synchronized (LOCK) { if (!shellReady) return false; }
        int id;
        synchronized (LOCK) { id = ++commandId; }
        String marker = "__RAY_DONE_" + id + "__";
        return execute("input " + command + " >/dev/null 2>&1 && echo " + marker + " || echo " + marker + "_ERROR", marker);
    }

    private static boolean execute(String command, String marker) {
        synchronized (SHELL_LOCK) {
            BufferedWriter input;
            LinkedBlockingQueue<String> output;
            synchronized (LOCK) {
                input = shellInput;
                output = shellOutput;
            }
            if (input == null || output == null) return false;
            try {
                input.write(command);
                input.newLine();
                input.flush();
                long deadline = System.currentTimeMillis() + 2500;
                while (System.currentTimeMillis() < deadline) {
                    String line = output.poll(100, TimeUnit.MILLISECONDS);
                    if (marker.equals(line)) return true;
                    if ((marker + "_ERROR").equals(line)) return false;
                }
            } catch (Exception ignored) { }
            Process failed;
            synchronized (LOCK) {
                generation++;
                shellReady = false;
                rootAvailable = false;
                rootChecked = false;
                probeStarted = false;
                failed = shell;
                shell = null;
                shellInput = null;
                shellOutput = null;
            }
            closeProcess(failed);
            return false;
        }
    }

    private static boolean allowedKey(int code) {
        return code == 3 || code == 4 || code == 19 || code == 20 || code == 21 || code == 22
                || code == 23 || code == 82 || code == 85 || code == 86 || code == 87 || code == 88 || code == 89 || code == 90;
    }

    private static void closeProcess(Process process) {
        if (process == null) return;
        try { process.getOutputStream().close(); } catch (IOException ignored) { }
        process.destroy();
    }

    /**
     * Full-screen, touch-less container; the arrow is a small child moved with setTranslationX/Y, so a move is a
     * RenderNode property change: no layout, no display-list re-record, no redraw of the launcher under it.
     */
    static final class Pointer extends android.widget.FrameLayout {
        private final Arrow arrow;
        private float anchorX, anchorY;
        private boolean positioned;
        private final Runnable idle = new Runnable() {
            @Override public void run() {
                setVisibility(INVISIBLE);
                RaySocket.stateChanged();
            }
        };
        Pointer(Context context) {
            super(context);
            float d = getResources().getDisplayMetrics().density;
            arrow = new Arrow(context, d);
            addView(arrow, new LayoutParams(Math.round(48 * d), Math.round(48 * d)));
            setWillNotDraw(true);
            setVisibility(INVISIBLE);
        }
        void setPosition(float x, float y, boolean forceWake) {
            int width = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
            int height = getHeight() > 0 ? getHeight() : getResources().getDisplayMetrics().heightPixels;
            float dx = (x - anchorX) * width, dy = (y - anchorY) * height;
            if (!positioned || forceWake || dx * dx + dy * dy > 4) {
                positioned = true;
                anchorX = x;
                anchorY = y;
                setVisibility(VISIBLE);
                MAIN.removeCallbacks(idle);
                MAIN.postDelayed(idle, 2000);
            }
            arrow.setTranslationX(x * (width - 1) - arrow.hot);
            arrow.setTranslationY(y * (height - 1) - arrow.hot);
        }
        void hide() {
            MAIN.removeCallbacks(idle);
            setVisibility(INVISIBLE);
        }
        @Override protected void onDetachedFromWindow() {
            MAIN.removeCallbacks(idle);
            super.onDetachedFromWindow();
        }
    }

    /**
     * The window's only view: a transparent SurfaceView the arrow is drawn into by whichever thread moves it (the
     * air-mouse render thread), so a busy UI thread (focus changes, scene repaints) can no longer stall the pointer.
     */
    static final class Overlay extends android.view.SurfaceView implements android.view.SurfaceHolder.Callback {
        private final Arrow arrow;
        private int w, h;
        private boolean ready;
        private float anchorX = Float.NaN, anchorY, lastX, lastY;
        volatile boolean shown;
        private volatile long lastDraw;
        private final Runnable idle = new Runnable() {
            @Override public void run() {
                long left = lastDraw + 2000 - android.os.SystemClock.uptimeMillis();
                if (left > 0) { MAIN.postDelayed(this, left); return; }
                hide();
                RaySocket.stateChanged();
            }
        };
        Overlay(Context context) {
            super(context);
            arrow = new Arrow(context, getResources().getDisplayMetrics().density);
            setZOrderOnTop(true);
            getHolder().setFormat(PixelFormat.TRANSLUCENT);
            getHolder().addCallback(this);
        }
        /** Any thread. Draws the arrow at the normalized position; a sleeping pointer wakes on a move over 2 px or wake. */
        synchronized boolean draw(float x, float y, boolean wake) {
            if (!ready) return false;
            if (!shown) {
                float dx = (x - anchorX) * w, dy = (y - anchorY) * h;
                if (!wake && dx * dx + dy * dy <= 4) return true; // NaN anchor: first time always shows
                anchorX = x;
                anchorY = y;
                shown = true;
                MAIN.removeCallbacks(idle);
                MAIN.postDelayed(idle, 2000);
            }
            lastDraw = android.os.SystemClock.uptimeMillis();
            paint(x, y);
            return true;
        }
        synchronized void hide() {
            if (!shown) return;
            shown = false;
            if (ready) paint(-1, -1);
        }
        private void paint(float x, float y) { // caller holds the lock
            lastX = x;
            lastY = y;
            android.view.SurfaceHolder holder = getHolder();
            Canvas c;
            try { c = holder.getSurface().lockHardwareCanvas(); }
            catch (RuntimeException e) { c = holder.lockCanvas(); }
            if (c == null) return;
            c.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR); // note: whole surface; a hardware canvas has no dirty rect, GPU clear is cheap
            if (x >= 0) {
                c.save();
                c.translate(x * (w - 1) - arrow.hot, y * (h - 1) - arrow.hot);
                arrow.onDraw(c);
                c.restore();
            }
            holder.getSurface().unlockCanvasAndPost(c);
        }
        @Override public void surfaceCreated(android.view.SurfaceHolder holder) { }
        @Override public synchronized void surfaceChanged(android.view.SurfaceHolder holder, int format, int width, int height) {
            w = width;
            h = height;
            ready = true;
            paint(shown ? lastX : -1, lastY);
        }
        @Override public synchronized void surfaceDestroyed(android.view.SurfaceHolder holder) { ready = false; }
        @Override protected void onDetachedFromWindow() {
            MAIN.removeCallbacks(idle);
            super.onDetachedFromWindow();
        }
    }

    /** The arrow itself, drawn once with its tip at (hot, hot). */
    private static final class Arrow extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();
        private final Drawable systemArrow;
        final float hot;
        private final float d;
        Arrow(Context context, float d) {
            super(context);
            this.d = d;
            hot = 5 * d;
            Drawable icon = null;
            int id = getResources().getIdentifier("pointer_arrow", "drawable", "android");
            if (id != 0) try { icon = getResources().getDrawable(id, context.getTheme()); }
            catch (RuntimeException ignored) { }
            systemArrow = icon;
        }
        @Override protected void onDraw(Canvas canvas) {
            if (systemArrow != null) {
                systemArrow.setBounds(0, 0, systemArrow.getIntrinsicWidth(), systemArrow.getIntrinsicHeight());
                systemArrow.draw(canvas);
                return;
            }
            float px = hot, py = hot;
            arrow.reset();
            arrow.moveTo(px, py);
            arrow.lineTo(px, py + 22 * d);
            arrow.lineTo(px + 6 * d, py + 17 * d);
            arrow.lineTo(px + 10 * d, py + 25 * d);
            arrow.lineTo(px + 14 * d, py + 23 * d);
            arrow.lineTo(px + 10 * d, py + 15 * d);
            arrow.lineTo(px + 18 * d, py + 15 * d);
            arrow.close();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2 * d);
            paint.setColor(Color.BLACK);
            canvas.drawPath(arrow, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            canvas.drawPath(arrow, paint);
        }
    }
}
