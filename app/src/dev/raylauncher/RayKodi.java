package dev.raylauncher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Kodi does not publish to the Android TV provider, so when Kodi (or a fork) is in front its own web server answers
 * instead: JSON-RPC on 127.0.0.1:8080, no password. Anything else (web control off, password set, slow answer) is an
 * empty list and no error: the phone then shows controls and search only. Plain sockets with short timeouts; call off
 * the UI thread.
 */
final class RayKodi {
    static final int PORT = 8080;
    private static final int CONNECT_MS = 500, READ_MS = 1500, LIMIT = 8;

    private RayKodi() { }

    static boolean matches(String pkg) {
        return pkg != null && (pkg.startsWith("org.xbmc.") || pkg.toLowerCase(java.util.Locale.ROOT).contains("kodi"));
    }

    /** HTTP body of a request to Kodi's web server, or null on any problem (status other than 200 included). */
    static byte[] http(String method, String path, String body) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", PORT), CONNECT_MS);
            s.setSoTimeout(READ_MS);
            byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            OutputStream out = s.getOutputStream();
            out.write((method + " " + path + " HTTP/1.0\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nContent-Length: "
                    + payload.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(payload);
            out.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0 && all.size() < 4 * 1024 * 1024) all.write(buffer, 0, n);
            byte[] raw = all.toByteArray();
            int split = -1;
            for (int i = 0; i + 3 < raw.length && split < 0; i++)
                if (raw[i] == '\r' && raw[i + 1] == '\n' && raw[i + 2] == '\r' && raw[i + 3] == '\n') split = i + 4;
            if (split < 0 || !new String(raw, 0, Math.min(raw.length, 16), StandardCharsets.US_ASCII).contains(" 200")) return null;
            byte[] result = new byte[raw.length - split];
            System.arraycopy(raw, split, result, 0, result.length);
            return result;
        } catch (java.io.IOException | RuntimeException unavailable) {
            return null;
        }
    }

    private static JSONObject rpc(String method, JSONObject params) {
        Object result = rpcRaw(method, params);
        return result instanceof JSONObject ? (JSONObject) result : null;
    }

    /** The "result" of a JSON-RPC call (an object, or a string such as "OK"), or null. */
    private static Object rpcRaw(String method, JSONObject params) {
        try {
            JSONObject request = new JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method);
            if (params != null) request.put("params", params);
            byte[] answer = http("POST", "/jsonrpc", request.toString());
            return answer == null ? null : new JSONObject(new String(answer, StandardCharsets.UTF_8)).opt("result");
        } catch (JSONException | RuntimeException bad) {
            return null;
        }
    }

    private static JSONObject window() throws JSONException {
        return new JSONObject().put("start", 0).put("end", LIMIT);
    }

    private static RayWatchHistory.Entry entry(String pkg, String kind, JSONObject item, String title, String sub) {
        JSONObject art = item.optJSONObject("art");
        String image = art == null ? "" : art.optString("poster", art.optString("thumb", art.optString("tvshow.poster", "")));
        RayWatchHistory.Entry e = new RayWatchHistory.Entry(title, pkg, kind + ":" + item.optLong(kind + "id"), null, image);
        e.id = "kodi-" + kind + "-" + item.optLong(kind + "id");
        e.description = sub;
        JSONObject resume = item.optJSONObject("resume");
        if (resume != null && resume.optDouble("total") > 0 && resume.optDouble("position") > 0) {
            e.progress = (float) Math.min(1, resume.optDouble("position") / resume.optDouble("total"));
            e.minutesLeft = (int) Math.max(1, (resume.optDouble("total") - resume.optDouble("position")) / 60);
        }
        return e;
    }

    /** Continue watching (movies and shows in progress) and Suggested (recently added movies) of the Kodi library. */
    static RayMedia.Lists query(String pkg) {
        RayMedia.Lists lists = new RayMedia.Lists();
        try {
            JSONObject movies = rpc("VideoLibrary.GetMovies", new JSONObject().put("properties", new JSONArray().put("title").put("art").put("resume"))
                    .put("filter", new JSONObject().put("field", "inprogress").put("operator", "true").put("value", ""))
                    .put("sort", new JSONObject().put("method", "lastplayed").put("order", "descending")).put("limits", window()));
            JSONArray list = movies == null ? null : movies.optJSONArray("movies");
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject m = list.getJSONObject(i);
                lists.cont.add(entry(pkg, "movie", m, m.optString("title", m.optString("label")), ""));
            }
            JSONObject shows = rpc("VideoLibrary.GetInProgressTVShows", new JSONObject().put("properties", new JSONArray().put("title")).put("limits", window()));
            JSONArray tv = shows == null ? null : shows.optJSONArray("tvshows");
            for (int i = 0; tv != null && i < tv.length() && i < 4; i++) {
                JSONObject show = tv.getJSONObject(i);
                JSONObject ep = firstEpisode(show.optLong("tvshowid"), "inprogress", "true");
                if (ep == null) ep = firstEpisode(show.optLong("tvshowid"), "playcount", "0");
                if (ep != null)
                    lists.cont.add(entry(pkg, "episode", ep, show.optString("title", show.optString("label")),
                            "S" + ep.optInt("season") + "E" + ep.optInt("episode") + ", " + ep.optString("title")));
            }
            JSONObject recent = rpc("VideoLibrary.GetRecentlyAddedMovies", new JSONObject().put("properties", new JSONArray().put("title").put("art")).put("limits", window()));
            JSONArray added = recent == null ? null : recent.optJSONArray("movies");
            for (int i = 0; added != null && i < added.length(); i++) {
                JSONObject m = added.getJSONObject(i);
                lists.sugg.add(entry(pkg, "movie", m, m.optString("title", m.optString("label")), ""));
            }
        } catch (JSONException bad) { }
        return lists;
    }

    private static JSONObject firstEpisode(long show, String field, String value) throws JSONException {
        JSONObject r = rpc("VideoLibrary.GetEpisodes", new JSONObject().put("tvshowid", show)
                .put("properties", new JSONArray().put("title").put("season").put("episode").put("art").put("resume"))
                .put("filter", new JSONObject().put("field", field).put("operator", field.equals("playcount") ? "is" : "true").put("value", field.equals("playcount") ? value : ""))
                .put("sort", new JSONObject().put("method", "episode").put("order", "ascending"))
                .put("limits", new JSONObject().put("start", 0).put("end", 1)));
        JSONArray a = r == null ? null : r.optJSONArray("episodes");
        return a == null || a.length() == 0 ? null : a.optJSONObject(0);
    }

    /** Plays the item ("movie:12" / "episode:7": numbers only, what query() produced). */
    static boolean open(RayWatchHistory.Entry e) {
        String[] part = e.intentUri.split(":");
        if (part.length != 2 || !(part[0].equals("movie") || part[0].equals("episode")) || !part[1].matches("[0-9]{1,12}")) return false;
        try {
            return rpcRaw("Player.Open", new JSONObject().put("item", new JSONObject().put(part[0] + "id", Long.parseLong(part[1])))) != null;
        } catch (JSONException bad) {
            return false;
        }
    }

    /** Image of an item as Kodi serves it (its image:// URL through /image/). */
    static byte[] image(String artUri) {
        if (artUri == null || !artUri.startsWith("image://")) return null;
        try { byte[] image = http("GET", "/image/" + URLEncoder.encode(artUri, "UTF-8"), null); return image == null || image.length == 0 ? null : image; }
        catch (java.io.UnsupportedEncodingException never) { return null; }
    }
}
