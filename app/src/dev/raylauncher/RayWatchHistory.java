package dev.raylauncher;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class RayWatchHistory {
    private static final Uri WATCH_NEXT = Uri.parse("content://android.media.tv/watch_next_program");
    private static final String[] COLUMNS = {"watch_next_type", "last_engagement_time_utc_millis", "poster_art_uri",
            "thumbnail_uri", "title", "intent_uri", "package_name"};
    private static final String[] COLUMNS_EXT = {"watch_next_type", "last_engagement_time_utc_millis", "poster_art_uri",
            "thumbnail_uri", "title", "intent_uri", "package_name", "short_description",
            "last_playback_position_millis", "duration_millis"};
    private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;

    static final class Entry {
        final String title;
        final String packageName;
        final String intentUri;
        final Bitmap artwork;
        final String artUri;
        /** Short description from the publisher, or "". */
        String description = "";
        /** Watched fraction 0..1, or -1 when the publisher gives no position/duration. */
        float progress = -1;
        /** Minutes left, or -1. */
        int minutesLeft = -1;

        Entry(String title, String packageName, String intentUri, Bitmap artwork) {
            this(title, packageName, intentUri, artwork, "");
        }

        Entry(String title, String packageName, String intentUri, Bitmap artwork, String artUri) {
            this.title = title;
            this.packageName = packageName;
            this.intentUri = intentUri;
            this.artwork = artwork;
            this.artUri = artUri;
        }
    }

    private static final class Row {
        final String title, poster, thumbnail, intentUri, packageName;
        final long engagement;
        String description = "";
        float progress = -1;
        int minutesLeft = -1;

        Row(String title, String poster, String thumbnail, String intentUri, String packageName, long engagement) {
            this.title = title;
            this.poster = poster;
            this.thumbnail = thumbnail;
            this.intentUri = intentUri;
            this.packageName = packageName;
            this.engagement = engagement;
        }
    }

    static List<Entry> load(Context context) {
        ArrayList<Row> rows = new ArrayList<>();
        // the extended projection is standard TvContract, but a odd provider may reject it: retry with the basics
        if (!query(context, COLUMNS_EXT, rows)) {
            rows.clear();
            if (!query(context, COLUMNS, rows)) return Collections.emptyList();
        }
        return build(context, rows);
    }

    private static boolean query(Context context, String[] columns, ArrayList<Row> rows) {
        try (Cursor cursor = context.getContentResolver().query(WATCH_NEXT, columns, null, null, null)) {
            if (cursor == null) return false;
            int description = cursor.getColumnIndex("short_description");
            int position = cursor.getColumnIndex("last_playback_position_millis");
            int duration = cursor.getColumnIndex("duration_millis");
            int type = cursor.getColumnIndex("watch_next_type");
            int engaged = cursor.getColumnIndex("last_engagement_time_utc_millis");
            int poster = cursor.getColumnIndex("poster_art_uri");
            int thumbnail = cursor.getColumnIndex("thumbnail_uri");
            int title = cursor.getColumnIndex("title");
            int intent = cursor.getColumnIndex("intent_uri");
            int publisher = cursor.getColumnIndex("package_name");
            if (type < 0 || engaged < 0 || poster < 0 || thumbnail < 0 || title < 0 || intent < 0 || publisher < 0)
                return false;
            while (cursor.moveToNext()) {
                String name = cursor.getString(title), uri = cursor.getString(intent);
                String posterUri = cursor.getString(poster), thumbnailUri = cursor.getString(thumbnail);
                String packageName = cursor.getString(publisher);
                long time = cursor.getLong(engaged);
                if (cursor.getInt(type) != 0 || time <= 0 || name == null || name.trim().isEmpty()
                        || uri == null || uri.isEmpty() || empty(packageName)
                        || empty(posterUri) && empty(thumbnailUri)) continue;
                Row row = new Row(name, posterUri, thumbnailUri, uri, packageName, time);
                if (description >= 0 && cursor.getString(description) != null) row.description = cursor.getString(description).trim();
                if (position >= 0 && duration >= 0) {
                    long done = cursor.getLong(position), total = cursor.getLong(duration);
                    if (total > 0 && done > 0 && done < total) {
                        row.progress = (float) done / total;
                        row.minutesLeft = (int) Math.max(1, (total - done) / 60000);
                    }
                }
                rows.add(row);
            }
        } catch (Exception unavailable) {
            return false;
        }
        return true;
    }

    private static List<Entry> build(Context context, ArrayList<Row> rows) {
        Collections.sort(rows, new Comparator<Row>() {
            @Override public int compare(Row a, Row b) { return Long.compare(b.engagement, a.engagement); }
        });
        long deadline = SystemClock.elapsedRealtime() + 15000;
        ArrayList<Entry> result = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        int attempts = 0;
        // shortcut: try 12 recent artwork rows; raise when provider history often has unavailable art.
        for (Row row : rows) {
            if (result.size() == 6 || attempts == 14 || expired(deadline)) break;
            String key = contentKey(row.intentUri);
            if (key == null || !seen.add(key)) continue;
            attempts++;
            Bitmap image = decode(context.getContentResolver(), row.thumbnail, deadline);
            if (image == null && !expired(deadline)) image = decode(context.getContentResolver(), row.poster, deadline);
            if (image != null) {
                Entry entry = new Entry(row.title, row.packageName, row.intentUri, image);
                entry.description = row.description;
                entry.progress = row.progress;
                entry.minutesLeft = row.minutesLeft;
                result.add(entry);
            }
        }
        return result;
    }

    static boolean launch(Context context, Entry entry) {
        try {
            Intent parsed = Intent.parseUri(entry.intentUri, Intent.URI_INTENT_SCHEME);
            String componentPackage = parsed.getComponent() == null ? null : parsed.getComponent().getPackageName();
            String intentPackage = parsed.getPackage();
            if (componentPackage != null && !entry.packageName.equals(componentPackage)
                    || intentPackage != null && !entry.packageName.equals(intentPackage)) return false;
            Intent safe = new Intent(parsed);
            safe.replaceExtras((android.os.Bundle) null);
            safe.setFlags(context instanceof android.app.Activity ? 0 : Intent.FLAG_ACTIVITY_NEW_TASK);
            safe.setClipData(null);
            safe.setSelector(null);
            safe.setPackage(entry.packageName);
            ResolveInfo resolved = context.getPackageManager().resolveActivity(safe, PackageManager.MATCH_DEFAULT_ONLY);
            if (resolved == null || resolved.activityInfo == null
                    || !entry.packageName.equals(resolved.activityInfo.packageName)) return false;
            context.startActivity(safe);
            return true;
        } catch (Exception invalid) {
            return false;
        }
    }

    private static String contentKey(String uri) {
        try {
            Intent parsed = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
            return parsed.getDataString() == null ? uri : parsed.getDataString();
        } catch (Exception invalid) {
            return uri;
        }
    }

    private static Bitmap decode(ContentResolver resolver, String value, long deadline) {
        if (empty(value) || expired(deadline)) return null;
        try {
            Uri uri = Uri.parse(value);
            String scheme = uri.getScheme();
            // note: only local content:// artwork is read; remote image URLs are ignored so that no third-party host is contacted
            if (!"content".equalsIgnoreCase(scheme)) return null;
            InputStream input = resolver.openInputStream(uri);
            if (input == null) return null;
            byte[] bytes;
            try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count, total = 0;
                while ((count = stream.read(buffer)) != -1) {
                    if (expired(deadline)) return null;
                    total += count;
                    if (total > MAX_IMAGE_BYTES) return null;
                    output.write(buffer, 0, count);
                }
                bytes = output.toByteArray();
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            if (expired(deadline)) return null;
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            int largest = Math.max(bounds.outWidth, bounds.outHeight);
            while (largest / options.inSampleSize > 512) options.inSampleSize *= 2;
            if (expired(deadline)) return null;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (Exception unavailable) {
            return null;
        }
    }

    private static boolean expired(long deadline) {
        return Thread.currentThread().isInterrupted() || SystemClock.elapsedRealtime() >= deadline;
    }

    private static boolean empty(String value) { return value == null || value.isEmpty(); }
}
