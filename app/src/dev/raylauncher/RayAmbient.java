package dev.raylauncher;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

/**
 * Very quiet background music for the home screen: the CC0 tracks in assets/music (credits in CREDITS.txt there),
 * one after the other, faded in and out. Played only while the home is in front, never asking for audio focus.
 * Pref "ray_music": true/false, absent until the first-run question has been answered. Main thread only.
 */
final class RayAmbient {
    static final String PREF = "ray_music";
    static final String[] TRACKS = {"music/heavenly.ogg", "music/slowstride.ogg", "music/calmtrack.ogg"};
    private static final float VOLUME = .35f; // under the TV's own volume: ambience, not a soundtrack
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static MediaPlayer player;
    private static Context app;
    private static int track;
    private static float level, target;

    private RayAmbient() {
    }

    static boolean enabled(Context c) {
        return c.getSharedPreferences(DimService.PREFS, Context.MODE_PRIVATE).getBoolean(PREF, false);
    }

    static boolean asked(Context c) {
        return c.getSharedPreferences(DimService.PREFS, Context.MODE_PRIVATE).contains(PREF);
    }

    static void set(Context c, boolean on) {
        c.getSharedPreferences(DimService.PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF, on).apply();
    }

    /** Starts (fading in) if not already playing; resumes the track where the last one stopped. */
    static void start(Context c) {
        app = c.getApplicationContext();
        target = 1;
        if (player == null) play();
        MAIN.removeCallbacks(FADE);
        MAIN.post(FADE);
    }

    /** Fades out over about a second, then releases the player. */
    static void stop() {
        target = 0;
        MAIN.removeCallbacks(FADE);
        MAIN.post(FADE);
    }

    static boolean isPlaying() { return player != null && target > 0; }

    private static void play() {
        release();
        MediaPlayer p = new MediaPlayer();
        try (AssetFileDescriptor fd = app.getAssets().openFd(TRACKS[track % TRACKS.length])) {
            p.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            p.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            p.setVolume(level * VOLUME, level * VOLUME);
            p.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer mp) { track++; if (target > 0) play(); }
            });
            p.prepare();
            p.start();
            player = p;
        } catch (java.io.IOException | RuntimeException unplayable) {
            p.release();
        }
    }

    private static void release() {
        if (player != null) { player.release(); player = null; }
    }

    /** 20 steps a second towards the target level; at silence the player is released. */
    private static final Runnable FADE = new Runnable() {
        @Override public void run() {
            level += Math.signum(target - level) * Math.min(Math.abs(target - level), .05f);
            if (player != null) player.setVolume(level * VOLUME, level * VOLUME);
            if (level <= 0 && target == 0) { release(); return; }
            if (level != target) MAIN.postDelayed(this, 50);
        }
    };
}
