package dev.raylauncher;

import android.content.Context;
import android.os.Handler;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * MCP endpoint (Model Context Protocol, Streamable HTTP transport, JSON responses only, no SSE) at POST /mcp, so an
 * AI assistant such as Claude Code can drive the TV:
 *
 *   claude mcp add --transport http ray http://TV_IP:8766/mcp --header "X-Key: PAIRING_KEY"
 *
 * Off until enabled on the TV (pref "ray_mcp"); every call needs the pairing key, like the rest of the API. The
 * tools run the same commands as the phone remote (RaySocket.exec), with the same checks.
 */
final class RayMcp {
    static final String PREF = "ray_mcp";
    private static final String PROTOCOL = "2025-06-18";

    private RayMcp() {
    }

    static boolean enabled(Context c) {
        return c.getSharedPreferences(DimService.PREFS, Context.MODE_PRIVATE).getBoolean(PREF, false);
    }

    static void set(Context c, boolean on) {
        c.getSharedPreferences(DimService.PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF, on).apply();
        RaySocket.stateChanged();
    }

    /** The line to paste in a terminal, or null without Wi-Fi. */
    static String command(Context c) {
        String ip = RemoteServer.localIp();
        return ip == null ? null : "claude mcp add --transport http ray http://" + ip + ":" + RemoteServer.PORT + "/mcp --header \"X-Key: "
                + DimService.pairingKey(c.getSharedPreferences(DimService.PREFS, Context.MODE_PRIVATE)) + "\"";
    }

    /** One JSON-RPC message in, the reply out; null for a notification (answered 202 with no body). */
    static JSONObject handle(DimService service, Handler main, JSONObject req) throws JSONException {
        String method = req.optString("method");
        Object id = req.opt("id");
        if (id == null) return null; // notifications/initialized and the like
        RayDebug.log("mcp " + method + ("tools/call".equals(method) ? " " + req.optJSONObject("params").optString("name") : ""));
        JSONObject result;
        switch (method) {
            case "initialize":
                String asked = req.optJSONObject("params") == null ? "" : req.optJSONObject("params").optString("protocolVersion");
                result = new JSONObject().put("protocolVersion", asked.isEmpty() ? PROTOCOL : asked)
                        .put("capabilities", new JSONObject().put("tools", new JSONObject()))
                        .put("serverInfo", new JSONObject().put("name", "ray-launcher").put("version", version(service)))
                        .put("instructions", "Controls an Android TV running the RAY launcher. Read get_state first: it lists the "
                                + "installed apps (package names for launch_app), the app on screen, the skin and the screen filter.");
                RayDebug.toast(service, "AI assistant connected over MCP");
                break;
            case "ping":
                result = new JSONObject();
                break;
            case "tools/list":
                result = new JSONObject().put("tools", tools());
                break;
            case "tools/call":
                JSONObject p = req.optJSONObject("params");
                if (p == null) return error(id, -32602, "params required");
                result = call(service, main, p.optString("name"), p.optJSONObject("arguments") == null ? new JSONObject() : p.optJSONObject("arguments"));
                break;
            default:
                return error(id, -32601, "method not found: " + method);
        }
        return new JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result);
    }

    private static JSONObject error(Object id, int code, String message) throws JSONException {
        return new JSONObject().put("jsonrpc", "2.0").put("id", id).put("error", new JSONObject().put("code", code).put("message", message));
    }

    private static String version(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }

    private static JSONObject tool(String name, String description, JSONObject properties, String... required) throws JSONException {
        JSONObject schema = new JSONObject().put("type", "object").put("properties", properties);
        if (required.length > 0) schema.put("required", new JSONArray(java.util.Arrays.asList(required)));
        return new JSONObject().put("name", name).put("description", description).put("inputSchema", schema);
    }

    private static JSONObject prop(String type, String description) throws JSONException {
        return new JSONObject().put("type", type).put("description", description);
    }

    private static JSONObject choice(String description, String... values) throws JSONException {
        return prop("string", description).put("enum", new JSONArray(java.util.Arrays.asList(values)));
    }

    static JSONArray tools() throws JSONException {
        JSONObject none = new JSONObject();
        return new JSONArray()
                .put(tool("get_state", "Everything RAY knows right now: installed apps, the app on screen and its media session, skin, "
                        + "day/night, screen filter, audio effects, background music, weather, connected phones.", none))
                .put(tool("launch_app", "Opens an installed app by package name (see get_state.apps).",
                        new JSONObject().put("package", prop("string", "Android package name, e.g. com.google.android.youtube.tv")), "package"))
                .put(tool("press_key", "Presses a remote key on the TV, as the phone remote does. Works over other apps when RAY's "
                        + "accessibility service is on.", new JSONObject()
                        .put("key", choice("The key", "up", "down", "left", "right", "ok", "back", "home", "menu",
                                "media_play_pause", "media_rewind", "media_fast_forward"))
                        .put("times", prop("integer", "Repeat count, 1 to 20 (default 1)")), "key"))
                .put(tool("move_pointer", "Shows the air-mouse pointer at a point of the screen (0..1 from the top left) and focuses "
                        + "what is under it. Over other apps it needs RAY's accessibility service or the overlay permission.", new JSONObject()
                        .put("x", prop("number", "0 = left edge, 1 = right edge")).put("y", prop("number", "0 = top, 1 = bottom")), "x", "y"))
                .put(tool("click", "Clicks where the pointer is (or presses OK when the pointer is hidden).", none))
                .put(tool("go_home", "Brings the RAY home screen to the front.", none))
                .put(tool("set_screen_filter", "Warm filter and dimmer drawn above every app.", new JSONObject()
                        .put("on", prop("boolean", "Filter on or off"))
                        .put("red", prop("integer", "Red tint 0-100"))
                        .put("brightness", prop("integer", "Brightness 5-100"))
                        .put("temp", prop("integer", "Colour temperature 1000-6500 K"))))
                .put(tool("apply_scene", "A ready-made filter preset.", new JSONObject()
                        .put("scene", choice("cinema: dim and warm, night: very dark and red, reset: no tint", "cinema", "night", "reset")), "scene"))
                .put(tool("set_theme", "Changes the home skin and/or the day/night look.", new JSONObject()
                        .put("skin", prop("string", "Skin id from get_state.theme.skins"))
                        .put("daynight", choice("Look", "auto", "day", "night"))))
                .put(tool("set_music", "Very quiet ambient music on the home screen.", new JSONObject().put("on", prop("boolean", "On or off")), "on"))
                .put(tool("media_control", "Controls the media session of the app on screen (needs notification access on the TV).",
                        new JSONObject().put("action", choice("What to do", "play", "pause", "toggle", "next", "previous", "stop", "seek"))
                                .put("ms", prop("integer", "For seek: milliseconds to move, negative goes back")), "action"))
                .put(tool("media_search", "Searches inside the app on screen, when it supports it.",
                        new JSONObject().put("query", prop("string", "What to look for")), "query"))
                .put(tool("set_debug_overlay", "Shows or hides the debug panel on the TV.", new JSONObject().put("on", prop("boolean", "On or off")), "on"))
                .put(tool("get_debug_log", "The last commands and events seen by RAY, newest last.", none));
    }

    private static JSONObject call(DimService service, Handler main, String name, JSONObject a) throws JSONException {
        Object out;
        switch (name) {
            case "get_state": out = RaySocket.state(service, main); break;
            case "launch_app": out = RaySocket.exec(service, main, "app.launch", new JSONObject().put("package", a.optString("package"))); break;
            case "press_key": {
                int times = Math.max(1, Math.min(20, a.optInt("times", 1)));
                JSONObject r = null;
                for (int i = 0; i < times; i++) {
                    r = RaySocket.exec(service, main, "remote.key", new JSONObject().put("key", a.optString("key")));
                    if (r == null || !r.optBoolean("ok")) break;
                    if (times > 1) try { Thread.sleep(120); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
                out = r;
                break;
            }
            case "move_pointer": {
                double x = a.optDouble("x", -1), y = a.optDouble("y", -1);
                if (!(x >= 0 && x <= 1 && y >= 0 && y <= 1)) return new JSONObject().put("isError", true).put("content", new JSONArray().put(text("x and y must be 0..1")));
                out = RaySocket.exec(service, main, "remote.pointer", new JSONObject().put("x", x).put("y", y));
                break;
            }
            case "click": out = RaySocket.exec(service, main, "remote.key", new JSONObject().put("key", "ok")); break;
            case "go_home": out = RaySocket.exec(service, main, "launcher.home", new JSONObject()); break;
            case "set_screen_filter": out = RaySocket.exec(service, main, "filter.set", a); break;
            case "apply_scene": out = RaySocket.exec(service, main, "scene.apply", new JSONObject().put("id", a.optString("scene"))); break;
            case "set_theme": out = RaySocket.exec(service, main, "theme.set", a); break;
            case "set_music": out = RaySocket.exec(service, main, "music.set", a); break;
            case "media_control": out = RaySocket.exec(service, main, "media.control", a); break;
            case "media_search": out = RaySocket.exec(service, main, "media.search", a); break;
            case "set_debug_overlay": out = RaySocket.exec(service, main, "debug.set", a); break;
            case "get_debug_log": out = RayDebug.logJson(); break;
            default:
                return new JSONObject().put("isError", true).put("content", new JSONArray().put(text("unknown tool: " + name)));
        }
        boolean failed = out == null || out instanceof JSONObject && ((JSONObject) out).has("ok") && !((JSONObject) out).optBoolean("ok");
        String body = out == null ? "{\"ok\":false,\"error\":\"timeout\"}" : out.toString();
        return new JSONObject().put("isError", failed).put("content", new JSONArray().put(text(body)));
    }

    private static JSONObject text(String s) throws JSONException {
        return new JSONObject().put("type", "text").put("text", s);
    }
}
