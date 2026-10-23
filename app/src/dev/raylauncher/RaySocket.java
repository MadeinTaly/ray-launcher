package dev.raylauncher;

import android.os.Handler;

import org.json.JSONObject;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class RaySocket {
    private static final int LIMIT = 32768;
    private static final RayPtrStat ARRIVE = new RayPtrStat("tcp-arrive");
    private static final java.util.Timer AUTH_TIMER = new java.util.Timer("ray-auth-deadline", true);
    private static final CopyOnWriteArrayList<RaySocket> CLIENTS = new CopyOnWriteArrayList<>();
    private static final ExecutorService PUSH = Executors.newSingleThreadExecutor();
    private final DimService service;
    private final RemoteServer server;
    private final Handler main;
    private final Socket socket;
    private final AtomicLong snapshotSeq = new AtomicLong();
    private long lastPush;
    private volatile OutputStream output;
    private final AtomicBoolean pushQueued = new AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicReference<JSONObject> pendingMove = new java.util.concurrent.atomic.AtomicReference<>();

    RaySocket(RemoteServer server, DimService service, Handler main, Socket socket) {
        this.server = server;
        this.service = service;
        this.main = main;
        this.socket = socket;
    }

    void run() throws IOException {
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();
        output = out;
        int[] op = new int[1];
        java.util.TimerTask deadline = new java.util.TimerTask() {
            @Override public void run() { try { socket.close(); } catch (IOException ignored) { } }
        };
        AUTH_TIMER.schedule(deadline, 5000);
        byte[] authBytes;
        try { authBytes = readFrame(in, out, op); }
        finally { deadline.cancel(); AUTH_TIMER.purge(); }
        if (authBytes == null || op[0] != 1) return;
        JSONObject auth;
        try { auth = new JSONObject(new String(authBytes, StandardCharsets.UTF_8)); }
        catch (Exception e) { auth = new JSONObject(); }
        if (!"auth".equals(auth.optString("type")) || !serviceServerKeyMatches(auth.optString("key", ""))) {
            sendJson(out, message("auth.error", "error", "pairing key"));
            close(out, 1008);
            return;
        }
        // companions send a ping every 5 s: a phone that vanished from the Wi-Fi is dropped instead of counting forever
        socket.setSoTimeout(45000);
        sendJson(out, message("auth.ok", "protocol", 1));
        CLIENTS.add(this);
        snapshot(out);
        long commandWindow = android.os.SystemClock.uptimeMillis();
        int commands = 0, fastCommands = 0;
        while (!socket.isClosed()) {
            byte[] payload = readFrame(in, out, op);
            if (payload == null) return;
            int opcode = op[0];
            if (opcode == 8) { frame(out, 8, payload); return; }
            if (opcode == 9) { frame(out, 10, payload); continue; }
            if (opcode == 10) continue;
            if (opcode != 1) { close(out, 1003); return; }
            long now = android.os.SystemClock.uptimeMillis();
            if (now - commandWindow >= 1000) { commandWindow = now; commands = 0; fastCommands = 0; }
            if (RayPtrStat.ON) ARRIVE.tick();
            JSONObject request;
            try {
                request = new JSONObject(new String(payload, StandardCharsets.UTF_8));
            } catch (Exception badJson) {
                sendJson(out, message("ack", "ok", false, "error", "bad json"));
                continue;
            }
            JSONObject payloadObject = request.optJSONObject("payload");
            if (payloadObject == null) payloadObject = request;
            String command = request.optString("type", request.optString("command", ""));
            String id = request.optString("id", "");
            // pointer packets have their own, much higher cap: the 60/s limit used to kill a 100 Hz mouse
            boolean fast = command.equals("remote.motion") || command.equals("remote.pointer") || command.equals("remote.delta");
            if (fast ? ++fastCommands > 500 : ++commands > 60) { close(out, 1008); return; }
            // the app on screen can change without any event reaching RAY: each ping refreshes the companion
            if (command.equals("ping")) { snapshot(out); continue; }
            if (!allowed(command)) {
                sendJson(out, message("ack", "id", id, "ok", false, "error", "command"));
                continue;
            }
            JSONObject result = command(command, payloadObject);
            boolean ok = result != null && result.optBoolean("ok", false);
            if (!command.equals("remote.motion") && !command.equals("remote.pointer") && !command.equals("remote.delta")) {
                sendJson(out, message("ack", "id", id, "ok", ok, "error", ok ? "" : "UNSUPPORTED_ACTION"));
                snapshot(out);
            }
        }
    }

    private boolean serviceServerKeyMatches(String key) {
        return server.keyMatches(key);
    }

    void remove() { CLIENTS.remove(this); }

    static int clientCount() { return CLIENTS.size(); }

    static void stateChanged() {
        for (RaySocket client : CLIENTS) {
            if (!client.pushQueued.compareAndSet(false, true)) continue;
            PUSH.execute(new Runnable() {
            @Override public void run() {
                try {
                    long now = android.os.SystemClock.uptimeMillis();
                    if (!CLIENTS.contains(client)) return;
                    long delay = 100 - (now - client.lastPush);
                    if (delay > 0) Thread.sleep(delay);
                    client.lastPush = now;
                    client.sendSnapshot(client.output);
                } catch (IOException ignored) { client.remove(); try { client.socket.close(); } catch (IOException e) { } }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { client.pushQueued.set(false); }
            }
            });
        }
    }

    private static byte[] readFrame(InputStream in, OutputStream out, int[] opcodeOut) throws IOException {
        int a = in.read();
        if (a < 0) return null;
        int b = in.read();
        if (b < 0) throw new EOFException();
        int opcode = a & 15;
        long length = b & 127;
        if (length == 126) {
            int x = in.read(), y = in.read();
            if (x < 0 || y < 0) throw new EOFException();
            length = (x << 8) | y;
            if (length < 126) { close(out, 1002); return null; }
        } else if (length == 127) { close(out, 1009); return null; }
        boolean masked = (b & 128) != 0;
        if ((a & 128) == 0 || (a & 112) != 0 || !masked || length > LIMIT
                || (opcode != 1 && opcode != 8 && opcode != 9 && opcode != 10)
                || (opcode >= 8 && length > 125) || (opcode == 8 && length == 1)) { close(out, 1002); return null; }
        byte[] mask = readExact(in, 4);
        byte[] payload = readExact(in, (int) length);
        for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
        opcodeOut[0] = opcode;
        return payload;
    }

    private static boolean allowed(String command) {
        return command.equals("up") || command.equals("down") || command.equals("left") || command.equals("right")
                || command.equals("ok") || command.equals("back") || command.equals("home") || command.equals("apps")
                || command.equals("launch") || command.equals("touch") || command.equals("motion") || command.equals("recenter")
                || command.equals("remote.key") || command.equals("remote.pointer") || command.equals("remote.motion") || command.equals("remote.delta")
                || command.equals("launcher.home") || command.equals("app.launch") || command.equals("filter.set")
                || command.equals("audio.set") || command.equals("scene.apply") || command.equals("theme.set") || command.equals("weather.set")
                || command.equals("weather.consent") || command.equals("vpn.open")
                || command.equals("media.control") || command.equals("media.search") || command.equals("media.open");
    }

    private JSONObject command(String name, JSONObject request) {
        if (name.equals("remote.key") || name.equals("ok") || name.equals("back")
                || name.equals("up") || name.equals("down") || name.equals("left") || name.equals("right")) {
            String key = keyName(request.optString("key", name));
            int code = keyCode(key);
            if (code == 0) return message("result", "ok", false);
            if (code == 3) name = "launcher.home";
            else if (!RayLauncherActivity.isHomeActive() || code >= 85) {
                boolean applied = (code == 23) ? RayGlobalControl.tap(service) : RayGlobalControl.injectKey(service, code);
                return message("result", "ok", applied);
            }
        }
        if (name.equals("remote.delta")) { // relative motion: summed (never latest-wins), moved once per vsync
            RayPointerUdp.delta(service, (int) Math.max(-32767, Math.min(32767, request.optDouble("dx", 0))),
                    (int) Math.max(-32767, Math.min(32767, request.optDouble("dy", 0))));
            return message("result", "ok", true);
        }
        if (name.startsWith("media.")) return media(name, request);
        if (name.equals("weather.set")) { // network: runs on this reader thread, never on the UI thread
            try { return RayLauncherActivity.command(service, new JSONObject(request.toString()).put("type", "weather.set")); }
            catch (org.json.JSONException e) { return message("result", "ok", false); }
        }
        if (name.equals("filter.set")) return setFilter(request);
        if (name.equals("audio.set")) return setAudio(request);
        if (name.equals("scene.apply")) {
            String scene = request.optString("id", request.optString("scene", ""));
            try {
                if (scene.equals("cinema")) return setFilter(new JSONObject().put("on", true).put("red", 15).put("bright", 55).put("temp", 1500));
                if (scene.equals("night")) return setFilter(new JSONObject().put("on", true).put("red", 20).put("bright", 10).put("temp", 1100));
                if (scene.equals("reset")) return setFilter(new JSONObject().put("on", true).put("red", 0).put("bright", 100).put("temp", 1100));
            } catch (org.json.JSONException ignored) { }
            return new JSONObject();
        }
        JSONObject payload = new JSONObject();
        try {
            if (name.equals("app.launch") || name.equals("launch")) payload.put("type", "app.launch").put("package", request.optString("package"));
            else if (name.equals("remote.motion") || name.equals("motion")) payload.put("type", "remote.motion").put("x", request.optDouble("x", -1)).put("y", request.optDouble("y", -1));
            else if (name.equals("remote.pointer") || name.equals("touch")) {
                payload.put("type", "remote.pointer").put("x", request.optDouble("x", -1)).put("y", request.optDouble("y", -1));
                if (request.has("visible")) payload.put("visible", request.optBoolean("visible"));
            }
            else if (name.equals("recenter")) payload.put("type", "remote.pointer").put("x", 0.5).put("y", 0.5);
            else if (name.equals("theme.set")) {
                payload.put("type", "theme.set");
                if (request.has("color")) payload.put("color", request.get("color"));
                if (request.has("style")) payload.put("style", request.get("style"));
                if (request.has("skin")) payload.put("skin", request.get("skin"));
                if (request.has("remoteSkin")) payload.put("remoteSkin", request.get("remoteSkin"));
                if (request.has("daynight")) payload.put("daynight", request.get("daynight"));
            }
            else if (name.equals("vpn.open")) payload.put("type", "vpn.open").put("package", request.optString("pkg", request.optString("package", "")));
            else if (name.equals("weather.consent")) payload.put("type", "weather.consent").put("accepted", request.optBoolean("accepted"));
            else if (name.equals("launcher.home") || name.equals("home") || name.equals("apps")) payload.put("type", "launcher.home");
            else payload.put("type", "remote.key").put("key", keyName(request.optString("key", name)));
        } catch (org.json.JSONException e) {
            return new JSONObject();
        }
        if (payload.optString("type").startsWith("remote.") && !payload.has("visible")
                && (name.equals("remote.motion") || name.equals("remote.pointer") || name.equals("motion") || name.equals("touch"))) {
            // latest wins: the reader thread never waits for the UI thread, so a busy frame cannot back packets up into a burst
            if (pendingMove.getAndSet(payload) == null) main.post(new Runnable() {
                @Override public void run() {
                    JSONObject p = pendingMove.getAndSet(null);
                    if (p != null) try { RayLauncherActivity.command(service, p); } catch (Exception ignored) { }
                }
            });
            return message("result", "ok", true);
        }
        CountDownLatch done = new CountDownLatch(1);
        JSONObject[] result = new JSONObject[1];
        main.post(new Runnable() {
            @Override public void run() {
                long t0 = System.nanoTime(), q = t0;
                try { result[0] = RayLauncherActivity.command(service, payload); }
                catch (Exception ignored) { }
                finally { done.countDown(); if (RayPtrStat.ON) android.util.Log.i("RayPtr", "cmd " + (System.nanoTime() - t0) / 1000 + "us"); }
            }
        });
        try { done.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return result[0];
    }

    /** Companion for the app on screen: transport controls, its search and its recommendations. */
    private JSONObject media(String name, JSONObject request) {
        String pkg = RayMedia.foreground(service);
        boolean ok;
        if (name.equals("media.control")) ok = RayMedia.control(service, pkg, request.optString("action"), request.optLong("ms", 0));
        else if (name.equals("media.search")) ok = RayMedia.search(service, pkg, request.optString("query"));
        else ok = RayMedia.openSuggestion(service, pkg, request.optInt("index", -1));
        return message("result", "ok", ok);
    }

    private JSONObject setFilter(JSONObject values) {
        RemoteServer.SetTask task = new RemoteServer.SetTask(service,
                values.has("red") ? values.optInt("red", -1) : -1,
                values.has("brightness") ? values.optInt("brightness", -1) : values.has("bright") ? values.optInt("bright", -1) : -1,
                values.has("temp") ? values.optInt("temp", -1) : -1,
                values.has("on") ? (values.optBoolean("on") ? 1 : 0) : -1);
        CountDownLatch done = new CountDownLatch(1);
        main.post(new Runnable() { @Override public void run() { task.run(); done.countDown(); } });
        try { done.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        JSONObject result = new JSONObject();
        try { result.put("ok", done.getCount() == 0); } catch (org.json.JSONException ignored) { }
        return result;
    }

    private JSONObject setAudio(JSONObject values) {
        RemoteServer.AudioTask task = new RemoteServer.AudioTask(service, values);
        main.post(task);
        boolean applied = task.await();
        JSONObject result = new JSONObject();
        try { result.put("ok", applied); } catch (org.json.JSONException ignored) { }
        return result;
    }

    private static String keyName(String key) {
        String k = key.toUpperCase(java.util.Locale.ROOT);
        return k.startsWith("DPAD_") ? k.substring(5) : k.equals("ENTER") ? "OK" : k;
    }

    private static int keyCode(String key) {
        return "UP".equals(key) ? 19 : "DOWN".equals(key) ? 20 : "LEFT".equals(key) ? 21
                : "RIGHT".equals(key) ? 22 : "OK".equals(key) || "ENTER".equals(key) || "CENTER".equals(key) ? 23
                : "BACK".equals(key) ? 4 : "HOME".equals(key) ? 3 : "MENU".equals(key) ? 82
                : "MEDIA_PLAY_PAUSE".equals(key) ? 85 : "MEDIA_REWIND".equals(key) ? 89
                : "MEDIA_FAST_FORWARD".equals(key) ? 90 : 0;
    }

    private void snapshot(OutputStream out) throws IOException {
        sendSnapshot(out);
    }

    private void sendSnapshot(OutputStream out) throws IOException {
        JSONObject data = snapshotData();
        synchronized (out) {
            sendJson(out, message("state.snapshot", "seq", snapshotSeq.incrementAndGet(),
                    "ts", System.currentTimeMillis(), "payload", data));
        }
    }

    private static JSONObject message(String type, Object... values) {
        JSONObject result = new JSONObject();
        try {
            result.put("type", type);
            for (int i = 0; i + 1 < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        } catch (org.json.JSONException ignored) { }
        return result;
    }

    private JSONObject snapshotData() {
        JSONObject[] result = new JSONObject[1];
        Runnable capture = new Runnable() {
            @Override public void run() {
                JSONObject data;
                try {
                    data = RayLauncherActivity.rayState(service);
                    data.put("filter", new JSONObject(service.stateJson()));
                    data.put("audio", new JSONObject(service.audioJson()));
                } catch (Exception ignored) { data = new JSONObject(); }
                result[0] = data;
            }
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            capture.run();
            return result[0];
        }
        CountDownLatch done = new CountDownLatch(1);
        main.post(new Runnable() { @Override public void run() { try { capture.run(); } finally { done.countDown(); } } });
        try { done.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return result[0] == null ? new JSONObject() : result[0];
    }

    private static byte[] readExact(InputStream in, int count) throws IOException {
        byte[] bytes = new byte[count];
        int offset = 0;
        while (offset < count) {
            int n = in.read(bytes, offset, count - offset);
            if (n < 0) throw new EOFException();
            offset += n;
        }
        return bytes;
    }

    private static void sendJson(OutputStream out, JSONObject value) throws IOException {
        frame(out, 1, value.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void close(OutputStream out, int code) throws IOException {
        frame(out, 8, new byte[]{(byte) (code >> 8), (byte) code});
    }

    private static void frame(OutputStream out, int opcode, byte[] data) throws IOException {
        synchronized (out) {
            out.write(0x80 | opcode);
            if (data.length < 126) out.write(data.length);
            else { out.write(126); out.write(data.length >> 8); out.write(data.length); }
            out.write(data);
            out.flush();
        }
    }
}
