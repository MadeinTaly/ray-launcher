package dev.raylauncher;

import android.app.SearchManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Companion for the app in the foreground: its media session (title, artwork, play/pause, seek, skip) and the
 * recommendations it publishes on the Android TV home (preview programs and Watch Next).
 * Media sessions of other apps need notification access for RayMediaListener (Settings, or
 * adb shell cmd notification allow_listener dev.raylauncher/dev.raylauncher.RayMediaListener).
 */
final class RayMedia {
    private static final Uri PREVIEW = Uri.parse("content://android.media.tv/preview_program");
    private static final Uri WATCH_NEXT = Uri.parse("content://android.media.tv/watch_next_program");
    private static final String[] COLUMNS = {"_id", "title", "episode_title", "poster_art_uri", "thumbnail_uri", "intent_uri",
            "last_playback_position_millis", "duration_millis"};
    private static final String[] COLUMNS_BASIC = {"_id", "title", "episode_title", "poster_art_uri", "thumbnail_uri", "intent_uri"};
    private static final int MAX_SUGGESTIONS = 12;
    private static final long SUGGESTION_TTL_MS = 30000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final MediaController.Callback CHANGES = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { RaySocket.stateChanged(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { RaySocket.stateChanged(); }
        @Override public void onSessionDestroyed() { RaySocket.stateChanged(); }
    };
    private static final List<MediaController> watched = new ArrayList<>();
    private static boolean listening;
    private static volatile byte[] art;
    private static volatile String artId = "";
    private static String suggestionsPackage = "";
    private static long suggestionsAt;
    private static boolean refreshing;
    /** The two lists of one app: Continue watching (Watch Next) and Suggested (preview programs); ids are stable per item. */
    static final class Lists {
        final List<RayWatchHistory.Entry> cont = new ArrayList<>(), sugg = new ArrayList<>();
    }
    private static Lists suggestions = new Lists();
    private static final java.util.HashMap<String, byte[]> itemArt = new java.util.HashMap<>();
    /** Last foreground package seen by the accessibility service, when it is enabled. */
    static volatile String windowPackage = "";

    private RayMedia() { }

    private static ComponentName listener(Context context) {
        return new ComponentName(context, RayMediaListener.class);
    }

    /** Sessions of every app, or null without notification access. Starts following their changes once. */
    private static List<MediaController> sessions(final Context context) {
        MediaSessionManager manager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (manager == null) return null;
        try {
            List<MediaController> list = manager.getActiveSessions(listener(context));
            synchronized (watched) {
                if (!listening) {
                    manager.addOnActiveSessionsChangedListener(new MediaSessionManager.OnActiveSessionsChangedListener() {
                        @Override public void onActiveSessionsChanged(List<MediaController> controllers) {
                            follow(controllers);
                            RaySocket.stateChanged();
                        }
                    }, listener(context), MAIN);
                    listening = true;
                }
            }
            follow(list);
            return list;
        } catch (SecurityException noAccess) {
            return null;
        }
    }

    private static void follow(List<MediaController> list) {
        if (list == null) return;
        synchronized (watched) {
            if (watched.equals(list)) return;
            for (MediaController old : watched) old.unregisterCallback(CHANGES);
            watched.clear();
            for (MediaController controller : list) {
                controller.registerCallback(CHANGES, MAIN);
                watched.add(controller);
            }
        }
    }

    /** The playing session, else the first one; for a given package when it is not null. */
    private static MediaController current(Context context, String pkg) {
        List<MediaController> list = sessions(context);
        if (list == null) return null;
        MediaController first = null;
        for (MediaController controller : list) {
            if (pkg != null && !pkg.equals(controller.getPackageName())) continue;
            PlaybackState state = controller.getPlaybackState();
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) return controller;
            if (first == null) first = controller;
        }
        return first;
    }

    static boolean hasAccess(Context context) { return sessions(context) != null; }

    /** Best guess of the app on screen: usage stats, then the accessibility service, then the playing session. */
    static String foreground(Context context) {
        if (RayLauncherActivity.isHomeActive()) return context.getPackageName();
        UsageStatsManager usage = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usage != null) {
            String last = null;
            try {
                long now = System.currentTimeMillis();
                UsageEvents events = usage.queryEvents(now - 3600_000L, now);
                UsageEvents.Event event = new UsageEvents.Event();
                while (events != null && events.hasNextEvent()) {
                    events.getNextEvent(event);
                    if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) last = event.getPackageName();
                }
            } catch (RuntimeException noUsageAccess) { }
            if (last != null) return last;
        }
        String window = windowPackage;
        if (!window.isEmpty() && !window.equals("com.android.systemui")) return window;
        MediaController playing = current(context, null);
        return playing == null ? "" : playing.getPackageName();
    }

    static JSONObject state(Context context, String pkg) throws JSONException {
        JSONObject result = new JSONObject().put("access", false).put("package", pkg);
        if (!pkg.isEmpty() && !pkg.equals(context.getPackageName())) {
            try {
                PackageManager pm = context.getPackageManager();
                result.put("app", pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString());
            } catch (PackageManager.NameNotFoundException gone) { }
            Lists lists = suggestions(context, pkg);
            result.put("continue", items(lists.cont)).put("suggestions", items(lists.sugg));
            result.put("searchable", searchIntent(context, pkg, "") != null);
        }
        MediaController controller = current(context, pkg.isEmpty() ? null : pkg);
        if (controller == null) {
            if (hasAccess(context)) result.put("access", true);
            return result;
        }
        result.put("access", true).put("session", true);
        MediaMetadata metadata = controller.getMetadata();
        PlaybackState state = controller.getPlaybackState();
        if (metadata != null) {
            String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            if (title == null) title = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            String subtitle = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (subtitle == null) subtitle = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            if (title == null && metadata.getDescription() != null && metadata.getDescription().getTitle() != null)
                title = metadata.getDescription().getTitle().toString();
            result.put("title", title == null ? "" : title).put("subtitle", subtitle == null ? "" : subtitle)
                    .put("duration", metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
            String uri = metadata.getString(MediaMetadata.METADATA_KEY_ART_URI);
            if (uri == null) uri = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
            if (uri == null) uri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
            if (uri != null && uri.startsWith("http")) result.put("art", uri);
            else if (keepArt(metadata)) result.put("art", "/media-art?id=" + artId);
        }
        if (state != null) {
            long position = state.getPosition();
            if (state.getState() == PlaybackState.STATE_PLAYING && position >= 0)
                position += (long) ((SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime()) * state.getPlaybackSpeed());
            long actions = state.getActions();
            result.put("playing", state.getState() == PlaybackState.STATE_PLAYING
                            || state.getState() == PlaybackState.STATE_BUFFERING)
                    .put("position", position).put("speed", state.getPlaybackSpeed())
                    .put("canSeek", (actions & PlaybackState.ACTION_SEEK_TO) != 0)
                    .put("canNext", (actions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0)
                    .put("canPrevious", (actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0);
        }
        return result;
    }

    /** Keeps the session's artwork bitmap as a small JPEG for /media-art; false when there is none. */
    private static boolean keepArt(MediaMetadata metadata) {
        Bitmap bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (bitmap == null) bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (bitmap == null) bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (bitmap == null || bitmap.isRecycled() || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) return false;
        String id = Integer.toHexString(System.identityHashCode(bitmap));
        if (id.equals(artId)) return true;
        try {
            float scale = Math.min(1f, 480f / Math.max(bitmap.getWidth(), bitmap.getHeight()));
            Bitmap small = scale < 1f ? Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * scale)),
                    Math.max(1, Math.round(bitmap.getHeight() * scale)), true) : bitmap;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            small.compress(Bitmap.CompressFormat.JPEG, 80, out);
            if (small != bitmap) small.recycle();
            art = out.toByteArray();
            artId = id;
            return true;
        } catch (RuntimeException | OutOfMemoryError tooBig) {
            return false;
        }
    }

    static byte[] art() { return art; }

    private static JSONArray items(List<RayWatchHistory.Entry> entries) throws JSONException {
        JSONArray list = new JSONArray();
        for (RayWatchHistory.Entry entry : entries) {
            String art = RayWatchHistory.remoteArt && entry.artUri != null && entry.artUri.startsWith("https:") || entry.artwork != null || entry.artUri != null && (entry.artUri.startsWith("content:") || entry.artUri.startsWith("image:"))
                    ? "/item-art?id=" + Uri.encode(entry.id) : "";
            list.put(new JSONObject().put("id", entry.id).put("title", entry.title).put("sub", entry.description)
                    .put("art", art).put("progress", entry.progress).put("minutes", entry.minutesLeft));
        }
        return list;
    }

    /** Both lists of the app, newest first. Queried off the UI thread; the cached lists are returned. */
    private static synchronized Lists suggestions(final Context context, final String pkg) {
        if (!pkg.equals(suggestionsPackage)) {
            suggestionsPackage = pkg;
            suggestions = new Lists();
            suggestionsAt = 0;
            itemArt.clear();
        }
        long now = SystemClock.uptimeMillis();
        if (!refreshing && (suggestionsAt == 0 || now - suggestionsAt >= SUGGESTION_TTL_MS)) {
            refreshing = true;
            Thread thread = new Thread(new Runnable() {
                @Override public void run() {
                    Lists found;
                    try {
                        found = RayKodi.matches(pkg) ? RayKodi.query(pkg)
                                : RayDemo.on(context) ? RayDemo.appLists(context, pkg) : query(context, pkg);
                    } catch (RuntimeException failed) {
                        RayDebug.log("app lists: " + failed);
                        found = new Lists();
                    }
                    boolean changed;
                    synchronized (RayMedia.class) {
                        refreshing = false;
                        if (!pkg.equals(suggestionsPackage)) return;
                        changed = !sameItems(found.cont, suggestions.cont) || !sameItems(found.sugg, suggestions.sugg);
                        suggestions = found;
                        suggestionsAt = SystemClock.uptimeMillis();
                    }
                    if (changed) RaySocket.stateChanged();
                }
            }, "ray-suggestions");
            thread.setDaemon(true);
            thread.start();
        }
        return suggestions;
    }

    private static boolean sameItems(List<RayWatchHistory.Entry> a, List<RayWatchHistory.Entry> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++)
            if (!a.get(i).id.equals(b.get(i).id) || !a.get(i).title.equals(b.get(i).title) || a.get(i).progress != b.get(i).progress) return false;
        return true;
    }

    /** What an app publishes to the Android TV provider: Watch Next (continue) and preview programs (suggested). An app that publishes nothing gives two empty lists. */
    private static Lists query(Context context, String pkg) {
        Lists found = new Lists();
        read(context, WATCH_NEXT, "wn", pkg, found.cont);
        read(context, PREVIEW, "pp", pkg, found.sugg);
        for (java.util.Iterator<RayWatchHistory.Entry> it = found.sugg.iterator(); it.hasNext(); ) { // one item, one list
            RayWatchHistory.Entry s = it.next();
            for (RayWatchHistory.Entry c : found.cont) if (c.intentUri.equals(s.intentUri)) { it.remove(); break; }
        }
        return found;
    }

    private static void read(Context context, Uri uri, String prefix, String pkg, List<RayWatchHistory.Entry> into) {
        for (String[] columns : new String[][]{COLUMNS, COLUMNS_BASIC}) { // a provider may reject the position columns
            Cursor cursor = null;
            try {
                cursor = RayAdb.query(context, uri, columns, pkg, "_id DESC");
                if (cursor == null) continue;
                while (cursor.moveToNext() && into.size() < MAX_SUGGESTIONS) {
                    String title = cursor.getString(1), episode = cursor.getString(2);
                    String poster = cursor.getString(3), thumbnail = cursor.getString(4), intent = cursor.getString(5);
                    if (title == null || intent == null) continue;
                    boolean again = false; // apps publish one row per play: the same title once is enough
                    for (RayWatchHistory.Entry seen : into) if (seen.title.equals(title)) again = true;
                    if (again) continue;
                    String image = thumbnail != null && !thumbnail.isEmpty() ? thumbnail : poster == null ? "" : poster;
                    RayWatchHistory.Entry entry = new RayWatchHistory.Entry(title, pkg, intent, null, image);
                    entry.id = prefix + ":" + cursor.getLong(0);
                    if (episode != null) entry.description = episode;
                    if (columns.length > 6) {
                        long done = cursor.getLong(6), total = cursor.getLong(7);
                        if (total > 0 && done > 0 && done < total) {
                            entry.progress = (float) done / total;
                            entry.minutesLeft = (int) Math.max(1, (total - done) / 60000);
                        }
                    }
                    into.add(entry);
                }
                return;
            } catch (RuntimeException unavailable) {
                into.clear();
            } finally {
                if (cursor != null) cursor.close();
            }
        }
    }

    private static synchronized RayWatchHistory.Entry find(String pkg, String id) {
        if (!pkg.equals(suggestionsPackage) || id == null) return null;
        for (RayWatchHistory.Entry e : suggestions.cont) if (e.id.equals(id)) return e;
        for (RayWatchHistory.Entry e : suggestions.sugg) if (e.id.equals(id)) return e;
        return null;
    }

    /** Opens an item of the app on screen by its id; the intent comes from the TV provider (checked against the app's package), never from the phone. */
    static boolean openSuggestion(Context context, String pkg, String id) {
        RayWatchHistory.Entry e = find(pkg, id);
        if (e == null) return false;
        return RayKodi.matches(pkg) ? RayKodi.open(e) : RayWatchHistory.launch(context, e);
    }

    /** Artwork of an item of the app on screen (decoded on request, cached), or null. */
    static byte[] itemArt(Context context, String id) {
        String pkg = suggestionsPackage;
        RayWatchHistory.Entry e = find(pkg, id);
        if (e == null) return null;
        synchronized (RayMedia.class) { byte[] hit = itemArt.get(id); if (hit != null) return hit; }
        byte[] bytes = null;
        Bitmap bitmap = e.artwork;
        if (bitmap == null && e.artUri != null && (e.artUri.startsWith("content:") || RayWatchHistory.remoteArt && e.artUri.startsWith("https:"))) bitmap = RayWatchHistory.decodeUri(context, e.artUri);
        if (bitmap != null) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out);
            bytes = out.toByteArray();
        } else if (RayKodi.matches(pkg)) bytes = RayKodi.image(e.artUri);
        if (bytes != null) synchronized (RayMedia.class) { if (pkg.equals(suggestionsPackage)) itemArt.put(id, bytes); }
        return bytes;
    }

    static boolean control(Context context, String pkg, String action, long amount) {
        MediaController controller = current(context, pkg.isEmpty() ? null : pkg);
        if (controller == null) return false;
        MediaController.TransportControls controls = controller.getTransportControls();
        PlaybackState state = controller.getPlaybackState();
        switch (action) {
            case "play": controls.play(); return true;
            case "pause": controls.pause(); return true;
            case "toggle":
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) controls.pause();
                else controls.play();
                return true;
            case "next": controls.skipToNext(); return true;
            case "previous": controls.skipToPrevious(); return true;
            case "stop": controls.stop(); return true;
            case "seek":
                if (state == null) return false;
                long position = state.getPosition();
                if (state.getState() == PlaybackState.STATE_PLAYING)
                    position += (long) ((SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime()) * state.getPlaybackSpeed());
                controls.seekTo(Math.max(0, position + amount));
                return true;
            case "seekTo":
                controls.seekTo(Math.max(0, amount));
                return true;
            default: return false;
        }
    }

    private static Intent searchIntent(Context context, String pkg, String query) {
        android.content.pm.PackageManager pm = context.getPackageManager();
        Intent search = new Intent(Intent.ACTION_SEARCH).setPackage(pkg).putExtra(SearchManager.QUERY, query);
        if (search.resolveActivity(pm) != null) return search;
        String web = pkg.startsWith("com.netflix.") ? "https://www.netflix.com/search?q=" : pkg.startsWith("com.google.android.youtube") ? "https://www.youtube.com/results?search_query=" : null;
        if (web != null) {
            Intent view = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(web + android.net.Uri.encode(query))).setPackage(pkg);
            if (view.resolveActivity(pm) != null) return view;
        }
        return null;
    }

    /** Whether "search in this app" can work: the app takes a search intent, or a search link RAY knows. */
    static boolean canSearch(Context context, String pkg) {
        return searchIntent(context, pkg, "x") != null;
    }

    /** Opens the app's own search with the query (apps with a searchable activity, e.g. SmartTube). */
    static boolean search(Context context, String pkg, String query) {
        if (query == null || query.trim().isEmpty() || query.length() > 200) return false;
        Intent search = searchIntent(context, pkg, query.trim());
        if (search == null) return false;
        try {
            context.startActivity(search.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException failed) {
            return false;
        }
    }
}
