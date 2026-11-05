package dev.raylauncher;

import android.app.Activity;
import android.content.Context;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.Outline;
import android.view.ViewOutlineProvider;
import android.widget.HorizontalScrollView;
import android.graphics.drawable.StateListDrawable;
import android.widget.ListView;
import java.util.HashMap;
import android.util.DisplayMetrics;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.app.AlertDialog;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.nayuki.qrcodegen.QrCode;

public class RayLauncherActivity extends Activity implements View.OnClickListener, View.OnFocusChangeListener, Runnable {
    private static final int REQUEST_WATCH_HISTORY_PERMISSION = 203;
    private static final String READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS";
    private int BG;
    private int TEXT;
    private int MUTED;
    private static volatile RayLauncherActivity active;
    private static volatile List<AppEntry> cachedApps = Collections.emptyList();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService watchHistoryWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService appLoader = Executors.newSingleThreadExecutor();
    private int appsGeneration;
    private boolean appsLoaded;
    private final ArrayList<AppEntry> apps = new ArrayList<>();
    private final ArrayList<View> appViews = new ArrayList<>();
    private SharedPreferences prefs;
    private RaySkin skin;
    private TextView status;
    private View focusedApp;
    /** Focusable views and their rectangle (fractions of the screen), for explicit D-pad routes. */
    private final HashMap<View, float[]> spots = new HashMap<>();
    private Object heroItem;
    private long lastMinute = -1;
    private final HashMap<String, Bitmap> blurCache = new HashMap<>();
    private FrameLayout root;
    private FrameLayout cardLayer;
    /** One dp of the 1280x720 reference, in pixels. */
    private float unit = 1;
    private RayStockHero stockHero;
    private TextView clock, dateView, vpnBadge, weatherView;
    private final ExecutorService weatherWorker = Executors.newSingleThreadExecutor();
    private final Runnable weatherUpdate = new Runnable() { @Override public void run() { updateWeather(); } };
    /** Every 30 min (and on resume): fetch off the UI thread, then repaint. RayWeather.refresh skips a fresh cache. */
    private final Runnable weatherTick = new Runnable() {
        @Override public void run() {
            try { weatherWorker.execute(new Runnable() { @Override public void run() { RayWeather.refresh(RayLauncherActivity.this); main.post(weatherUpdate); } }); }
            catch (RuntimeException destroyed) { return; }
            main.postDelayed(this, 30 * 60 * 1000L);
        }
    };
    private final HashSet<View> freeHoriz = new HashSet<>();
    private final HashMap<String, Integer> tints = new HashMap<>();
    private final HashMap<String, Boolean> blanks = new HashMap<>();
    private boolean homeFocused;
    private long wpStamp = -1;
    private int wpGeneration;
    private final ExecutorService wallpaperWorker = Executors.newSingleThreadExecutor();
    private LinearLayout connectPanel;
    private RayBackdrop backdrop;
    private RaySynthScene scene;
    private AlertDialog showingDialog;
    private boolean dayMode;
    private RayGlobalControl.Pointer pointer;
    private View pointedView;
    private String category = "home";
    private String appSearch = "";
    private boolean receiverRegistered;
    private List<RayWatchHistory.Entry> watchHistory = Collections.emptyList();
    private int watchHistoryRequest;
    private final PackageChanges packageChanges = new PackageChanges(this);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        I18n.init(this);
        RayDebug.init(this);
        getPackageManager().setComponentEnabledSetting(
                new android.content.ComponentName(getPackageName(), getPackageName() + ".RayHome"),
                Compat.isTv(this) ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
        if (!Compat.isTv(this)) {
            startActivity(new Intent(this, PhoneActivity.class));
            finish();
            return;
        }
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        prefs = getSharedPreferences(DimService.PREFS, MODE_PRIVATE);
        dayMode = RayDayNight.isDay(this);
        setPalette(dayMode);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        DimService.pairListener = pairWindow;
        if (scene != null) scene.onResume();
        if ((scene != null) != (skin != null && skin.scene != null && RayGraphics.animated(this) && RaySynthScene.supported(this))) { recreate(); return; } // changed in Settings, or found too slow
        active = this;
        if (skin != null && (!current(this).equals(skin.id) || RayDayNight.isDay(this) != dayMode)) { recreate(); return; } // changed in the settings screen
        if (wpStamp != RayWallpaper.stamp(this)) applyWallpaper();
        refreshApps();
        run();
        root.post(afterFirstFrame);
        if (getIntent().getBooleanExtra("pair", false)) { // asked by the settings screen
            String hint = getIntent().getStringExtra("pairHint");
            getIntent().removeExtra("pair");
            getIntent().removeExtra("pairHint");
            showPairingDialog(hint);
        }
    }

    /** The home as it is now, for the debug channel. Null when it is not in front. */
    static JSONObject debugHome() throws org.json.JSONException {
        RayLauncherActivity a = active;
        if (a == null) return new JSONObject().put("active", false);
        JSONArray row = new JSONArray();
        for (View v : a.appViews) {
            Object tag = v.getTag();
            row.put(tag instanceof RayWatchHistory.Entry ? "watch:" + ((RayWatchHistory.Entry) tag).title + " (" + ((RayWatchHistory.Entry) tag).packageName + ")" : String.valueOf(tag));
        }
        View f = a.getCurrentFocus();
        if (a.category.equals("search")) row.put("searched:" + (a.appSearch.isEmpty() ? "no" : "yes"));
        Object focus = f == null ? null : f.getTag();
        return new JSONObject().put("active", true).put("skin", a.skin.id).put("layout", a.skin.layout).put("day", a.dayMode)
                .put("category", a.category).put("apps", a.apps.size()).put("watch", a.watchHistory.size()).put("row", row)
                .put("focus", focus == null ? "none" : focus instanceof RayWatchHistory.Entry ? "watch:" + ((RayWatchHistory.Entry) focus).title : String.valueOf(focus))
                .put("focusIndex", a.appViews.indexOf(f)).put("stage", a.heroItem == null ? "none" : a.heroItem instanceof AppEntry ? ((AppEntry) a.heroItem).name : String.valueOf(a.heroItem))
                .put("dialog", a.showingDialog != null && a.showingDialog.isShowing())
                .put("pairOpen", a.pairDialog != null).put("scene", a.scene != null).put("connectVisible", a.connectPanel != null && a.connectPanel.getVisibility() == View.VISIBLE);
    }

    /** Nothing here shows on the first picture of the home: it waits for it, the start is what people feel. */
    private final Runnable afterFirstFrame = new Runnable() {
        @Override public void run() {
            if (active != RayLauncherActivity.this) return;
            // Android switches accessibility off at every update of the app: with Full access on, RAY puts it back by itself
            if (DimAccessibilityService.instance == null && RayAdb.enabled(RayLauncherActivity.this)) Compat.enableAccessibility(RayLauncherActivity.this);
            try { Compat.startForegroundService(RayLauncherActivity.this, new Intent(RayLauncherActivity.this, DimService.class).putExtra("remote", true)); }
            catch (RuntimeException ignored) { }
            registerPackageChanges();
            updateWeather();
            main.removeCallbacks(weatherTick);
            weatherTick.run();
            boolean asking = requestWatchHistoryPermission();
            if (RayAmbient.enabled(RayLauncherActivity.this)) RayAmbient.start(RayLauncherActivity.this);
            if (!asking && showingDialog == null) Updater.check(RayLauncherActivity.this);
            RayDebug.attach(RayLauncherActivity.this, root);
            RaySocket.stateChanged();
        }
    };

    /** The animated background gave up on this device: rebuild the home without it. Any thread. */
    static void graphicsChanged() {
        final RayLauncherActivity a = active;
        if (a != null) a.main.post(new Runnable() { @Override public void run() { if (!a.isFinishing() && !a.isDestroyed()) a.recreate(); } });
    }

    /** The debug pref changed (RayDebug.set): show or hide the panel now. */
    static void debugChanged() {
        RayLauncherActivity a = active;
        if (a != null && a.root != null) RayDebug.attach(a, a.root);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        requestHome();
        refreshApps();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_WATCH_HISTORY_PERMISSION && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) refreshApps();
    }

    /** True when the system question is now on screen. */
    private boolean requestWatchHistoryPermission() {
        if (Build.VERSION.SDK_INT < 23 || checkSelfPermission(READ_TV_LISTINGS) == PackageManager.PERMISSION_GRANTED
                || prefs.getBoolean("ray_watch_history_permission_asked", false)) return false;
        prefs.edit().putBoolean("ray_watch_history_permission_asked", true).apply();
        requestPermissions(new String[]{READ_TV_LISTINGS}, REQUEST_WATCH_HISTORY_PERMISSION);
        return true;
    }

    /** Saves the background music choice and applies it now (it only plays while the home is in front). */
    static void setMusic(Context context, boolean on) {
        RayAmbient.set(context, on);
        if (on && active != null) RayAmbient.start(context); else if (!on) RayAmbient.stop();
        RaySocket.stateChanged();
    }

    @Override
    protected void onPause() {
        DimService.pairListener = null;
        if (scene != null) scene.onPause();
        if (pairDialog != null) pairDialog.dismiss();
        if (active == this) active = null;
        if (root != null) root.removeCallbacks(afterFirstFrame);
        RayAmbient.stop();
        RayDebug.detach();
        main.removeCallbacks(this);
        main.removeCallbacks(weatherTick);
        appsGeneration++;
        unregisterPackageChanges();
        RaySocket.stateChanged();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (showingDialog != null) showingDialog.dismiss();
        if (backdrop != null) backdrop.release();
        watchHistoryRequest++;
        appsGeneration++;
        main.removeCallbacksAndMessages(null);
        watchHistoryWorker.shutdownNow();
        appLoader.shutdownNow();
        wallpaperWorker.shutdownNow();
        weatherWorker.shutdownNow();
        wpGeneration++;
        super.onDestroy();
    }

    private void build() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int width = metrics.widthPixels, height = metrics.heightPixels;
        RaySkin base = RaySkin.get(this, prefs.getString("ray_skin", RaySkin.DEFAULT)); // unknown or removed ("classic", "chiaro"): the default
        if (base == null) base = RaySkin.get(this, RaySkin.DEFAULT);
        skin = base.forMode(dayMode); // its day or night variant (day/night follows ray_daynight)
        root = new FrameLayout(this);
        backdrop = new RayBackdrop(this);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        scene = null;
        if (skin.scene != null && RayGraphics.animated(this) && RaySynthScene.supported(this)) {
            scene = new RaySynthScene(this, dayMode); // sun by day, moon by night
            root.addView(scene, new FrameLayout.LayoutParams(-1, -1));
        }
        unit = height / 720f;
        buildHome(width, height);
    }

    private void addAt(FrameLayout parent, View view, int width, int height, float[] r) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(width * r[2]), Math.round(height * r[3]), Gravity.TOP | Gravity.LEFT);
        lp.leftMargin = Math.round(width * r[0]);
        lp.topMargin = Math.round(height * r[1]);
        parent.addView(view, lp);
        if (view.isFocusable()) spot(view, r);
    }

    private void spot(View view, float[] rect) {
        if (view.getId() == View.NO_ID) view.setId(View.generateViewId());
        spots.put(view, rect);
    }

    /**
     * D-pad routes by geometry instead of the framework's guess: the nearest focusable view in each direction,
     * the vertical or horizontal offset weighted so that rows stay rows. No neighbour: the view keeps the focus.
     */
    private void wireFocus() {
        ArrayList<View> list = new ArrayList<>();
        for (View v : spots.keySet()) if (v.getVisibility() != View.GONE) list.add(v);
        for (View v : list) {
            float[] a = spots.get(v);
            float cx = (a[0] + a[2] / 2) * 16, cy = (a[1] + a[3] / 2) * 9;
            View[] best = new View[4]; // up, down, left, right
            float[] score = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
            for (View o : list) {
                if (o == v) continue;
                float[] b = spots.get(o);
                float dx = (b[0] + b[2] / 2) * 16 - cx, dy = (b[1] + b[3] / 2) * 9 - cy;
                float[] cost = {-dy > .3f ? -dy + 2 * Math.abs(dx) : -1, dy > .3f ? dy + 2 * Math.abs(dx) : -1,
                        Math.abs(dy) < 1.5f && -dx > .5f ? -dx + 3 * Math.abs(dy) : -1, Math.abs(dy) < 1.5f && dx > .5f ? dx + 3 * Math.abs(dy) : -1}; // left and right stay on their line
                for (int d = 0; d < 4; d++) if (cost[d] >= 0 && cost[d] < score[d]) { score[d] = cost[d]; best[d] = o; }
            }
            v.setNextFocusUpId(best[0] == null ? v.getId() : best[0].getId());
            v.setNextFocusDownId(best[1] == null ? v.getId() : best[1].getId());
            if (freeHoriz.contains(v)) continue; // scrolling rows: the framework moves along them
            v.setNextFocusLeftId(best[2] == null ? v.getId() : best[2].getId());
            v.setNextFocusRightId(best[3] == null ? v.getId() : best[3].getId());
        }
    }
    /** Package scan and icon loading run off the UI thread; a newer call drops the older result. */
    private volatile List<String> recentlyUsed = Collections.emptyList();
    private volatile Set<String> searchable = Collections.emptySet();

    private void refreshApps() {
        final int gen = ++appsGeneration;
        if (!appsLoaded && apps.isEmpty() && !cachedApps.isEmpty()) applyApps(cachedApps);
        try {
            appLoader.execute(new Runnable() {
                @Override public void run() {
                    final ArrayList<AppEntry> loaded = new ArrayList<>();
                    try {
                        PackageManager pm = getPackageManager();
                        Set<String> seen = new HashSet<>();
                        addApps(pm, new Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_LAUNCHER"), seen, loaded);
                        addApps(pm, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), seen, loaded);
                        if (RayDemo.on(RayLauncherActivity.this)) {
                            loaded.clear();
                            for (int i = 0; i < RayDemo.APPS.length; i++) {
                                Drawable art = RayDemo.app(i);
                                loaded.add(new AppEntry("demo.app" + i, RayDemo.APPS[i], art, art, 0, -1));
                            }
                        } else Collections.sort(loaded);
                        // binder calls, slow on a TV box: done here, the home only reads the results
                        recentlyUsed = usedRecently();
                        HashSet<String> canSearch = new HashSet<>();
                        for (AppEntry a : loaded) if (RayDemo.on(RayLauncherActivity.this) || RayMedia.canSearch(RayLauncherActivity.this, a.packageName)) canSearch.add(a.packageName);
                        searchable = canSearch;
                    } catch (RuntimeException failed) {
                        return;
                    }
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (gen == appsGeneration && !isFinishing() && !isDestroyed()) {
                                appsLoaded = true;
                                applyApps(loaded);
                            }
                        }
                    });
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException destroyed) { }
    }

    private void applyApps(List<AppEntry> sorted) {
        sorted = new ArrayList<>(sorted);
        focusedApp = null;
        apps.clear();
        apps.addAll(sorted);
        String recent = prefs.getString("ray_recent", "");
        if (!recent.isEmpty()) {
            ArrayList<AppEntry> ordered = new ArrayList<>();
            for (String name : recent.split("\\n")) for (AppEntry app : sorted) if (app.packageName.equals(name)) ordered.add(app);
            for (AppEntry app : sorted) if (!ordered.contains(app)) ordered.add(app);
            apps.clear();
            apps.addAll(ordered);
        }
        // published after the recent-first ordering: the companion app list reads this
        cachedApps = Collections.unmodifiableList(new ArrayList<>(apps));
        updateRows();
        loadWatchHistory();
    }

    private void loadWatchHistory() {
        final int request = ++watchHistoryRequest;
        watchHistoryWorker.execute(new Runnable() {
            @Override public void run() {
                final List<RayWatchHistory.Entry> history = RayDemo.on(RayLauncherActivity.this) ? RayDemo.watch() : RayWatchHistory.load(RayLauncherActivity.this);
                main.post(new Runnable() {
                    @Override public void run() {
                        if (request != watchHistoryRequest || active != RayLauncherActivity.this || isFinishing()) return;
                        watchHistory = history;
                        if (category.equals("home")) updateRows();
                    }
                });
            }
        });
    }

    private Object tileItem(View tile) {
        Object tag = tile.getTag();
        return tag instanceof String && ((String) tag).startsWith("ph:") ? tag : tag instanceof String ? findApp((String) tag) : tag;
    }

    private static String remaining(int minutes) {
        String left = "it".equals(Locale.getDefault().getLanguage()) ? " rimanenti" : " left";
        return (minutes >= 60 ? (minutes / 60) + "h " + String.format(Locale.ROOT, "%02d", minutes % 60) + "min" : minutes + "min") + left;
    }

    /** What the stage shows for the focused card. */
    private void updateStage(Object item, boolean force) {
        if (!force && item == heroItem && item != null) return;
        heroItem = item;
        if (!category.equals("home")) {
            int n = 0;
            for (AppEntry a : apps) if (categoryMatches(a, category)) n++;
            if (category.equals("search")) stockHero.showGrid("\u201c" + appSearch + "\u201d", item instanceof String && ((String) item).startsWith("ph:find:") ? copy("Press OK to search inside this app", "Premi OK per cercare dentro questa app") : copy("Press OK to open", "Premi OK per aprire"));
            else stockHero.showGrid(copy("All apps", "Tutte le app"), n + " app");
        } else if (item instanceof RayWatchHistory.Entry) {
            RayWatchHistory.Entry e = (RayWatchHistory.Entry) item;
            AppEntry owner = findApp(e.packageName);
            String left = e.minutesLeft > 0 ? remaining(e.minutesLeft) : "";
            String meta = (owner == null ? "" : owner.name + ", ") + (left.isEmpty() ? "" : left + ", ") + copy("press OK to continue", "premi OK per continuare");
            stockHero.show(e.artwork, e.title, meta, e.progress, 0);
        } else if (item instanceof AppEntry) {
            AppEntry a = (AppEntry) item;
            stockHero.show(skin.scene != null ? null : stageCard(a), a.name, copy("Press OK to open. Hold OK for more", "Premi OK per aprire. Tieni premuto OK per altre opzioni"), -1, skin.scene != null ? 0 : tint(a));
        } else if ("ph:apps".equals(item)) {
            stockHero.show(null, copy("All apps", "Tutte le app"), copy("Press OK to see every app", "Premi OK per vederle tutte"), -1, 0);
            stockHero.initial(String.valueOf(apps.size()));
        } else stockHero.show(null, "", "", -1, 0);
    }

    /** The app's picture on the stage: its banner, or its icon on its colour. Cached per package. */
    private Bitmap stageCard(AppEntry app) {
        Bitmap cached = blurCache.get(app.packageName);
        if (cached != null) return cached;
        int cw = 640, chh = 360;
        Bitmap card = Bitmap.createBitmap(cw, chh, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(card);
        c.drawColor(mix(tint(app), Color.BLACK, .35f));
        Drawable d = app.banner != null && !blank(app) ? app.banner : app.icon;
        if (d != null) {
            boolean banner = d == app.banner;
            int dw = Math.max(1, d.getIntrinsicWidth()), dh = Math.max(1, d.getIntrinsicHeight());
            float k = banner ? Math.max((float) cw / dw, (float) chh / dh) : chh * .5f / Math.max(dw, dh);
            int bw = Math.round(dw * k), bh = Math.round(dh * k);
            Rect shared = d.copyBounds(); // the card on the row draws this same drawable
            d.setBounds((cw - bw) / 2, (chh - bh) / 2, (cw + bw) / 2, (chh + bh) / 2);
            d.draw(c);
            d.setBounds(shared);
        }
        if (blurCache.size() > 6) blurCache.clear();
        blurCache.put(app.packageName, card);
        return card;
    }

    private static final class CardView extends FrameLayout {
        float progress = -1;
        private boolean glow;
        private RaySkin stockSkin;
        private float u;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();

        CardView(Context c, RaySkin skin, float unit) {
            super(c);
            setWillNotDraw(false);
            stockSkin = skin;
            u = unit;
            final float r = skin.cardRadius * unit;
            if (Build.VERSION.SDK_INT >= 28) { setOutlineSpotShadowColor(skin.ring); setOutlineAmbientShadowColor(skin.ring); }
            setClipToOutline(true);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
            });
        }

        void setFocusLook(boolean focused) {
            glow = focused;
            setElevation(focused ? 18 * u : 0);
            setAlpha(focused ? 1f : stockSkin.dim);
            invalidate();
        }

        @Override protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            if (progress >= 0) {
                float bar = 5 * u, w = getWidth(), h = getHeight();
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.argb(110, 0, 0, 0));
                canvas.drawRect(0, h - bar, w, h, paint);
                paint.setColor(stockSkin.ring);
                canvas.drawRect(0, h - bar, w * Math.min(1f, progress), h, paint);
            }
            if (glow) {
                float rw = stockSkin.ringDp * u, r = stockSkin.cardRadius * u;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(rw);
                paint.setColor(stockSkin.ring);
                if (stockSkin.cubeA != 0) paint.setShader(new android.graphics.LinearGradient(0, getHeight(), getWidth(), 0, stockSkin.cubeA, stockSkin.cubeB, android.graphics.Shader.TileMode.CLAMP));
                box.set(rw / 2, rw / 2, getWidth() - rw / 2, getHeight() - rw / 2);
                canvas.drawRoundRect(box, r - rw / 2, r - rw / 2, paint);
                paint.setShader(null);
                if (stockSkin.light) { // dark ring vanishes on dark covers: thin light ring inside it
                    paint.setStrokeWidth(u);
                    paint.setColor(Color.argb(153, 255, 255, 255));
                    box.set(rw + u / 2, rw + u / 2, getWidth() - rw - u / 2, getHeight() - rw - u / 2);
                    canvas.drawRoundRect(box, r - rw, r - rw, paint);
                }
            }
        }
    }

    // ---- the home: layout and look in docs/SKIN_FORMAT.md ----

    private TextView stockText(String text, float dp, Typeface face, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dp * unit);
        t.setTypeface(face);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private GradientDrawable roundRect(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusDp * unit);
        return g;
    }

    private static int mix(int a, int b, float t) {
        return Color.rgb(Math.round(Color.red(a) * (1 - t) + Color.red(b) * t),
                Math.round(Color.green(a) * (1 - t) + Color.green(b) * t), Math.round(Color.blue(a) * (1 - t) + Color.blue(b) * t));
    }

    private void tintShadow(View v) {
        if (Build.VERSION.SDK_INT >= 28) { v.setOutlineSpotShadowColor(skin.shadow); v.setOutlineAmbientShadowColor(skin.shadow); }
    }

    private void buildHome(int width, int height) {
        Typeface reg = Fonts.regular(this);
        stockHero = new RayStockHero(this, skin, width, height);
        root.addView(stockHero, new FrameLayout.LayoutParams(-1, -1));
        cardLayer = new FrameLayout(this);
        cardLayer.setClipChildren(false);
        root.addView(cardLayer, new FrameLayout.LayoutParams(-1, -1));
        clock = stockText("", 58, skin.cubeA != 0 ? Fonts.chakra(this) : "cinema".equals(skin.layout) ? Fonts.display(this, 400) : Fonts.serif(), skin.textColor);
        clock.setLetterSpacing(-.035f);
        addAt(root, clock, width, height, new float[]{.05f, .055f, .24f, .12f});
        dateView = stockText("", 19, reg, skin.mutedColor);
        addAt(root, dateView, width, height, new float[]{.05f, .165f, .3f, .04f});
        weatherView = stockText("", 19, reg, skin.mutedColor);
        addAt(root, weatherView, width, height, new float[]{.05f, .202f, .3f, .04f});
        String[] actions = {"search", "pair", "sources", "settings"};
        float[][] actionRects = new float[4][];
        for (int i = 0; i < 4; i++) actionRects[i] = new float[]{(956 + 72 * i) / 1280f, 48 / 720f, 44 / 1280f, 44 / 720f};
        for (int i = 0; i < actions.length; i++) {
            String tag = actions[i];
            StockAction a = new StockAction(this, tag, actionIcon(tag), skin, unit);
            a.setFocusable(true);
            a.setClickable(true);
            a.setTag(tag);
            a.setOnClickListener(this);
            a.setOnFocusChangeListener(this);
            a.setContentDescription("search".equals(tag) ? copy("Search", "Cerca") : "pair".equals(tag) ? copy("Pair a phone", "Collega un telefono") : "sources".equals(tag) ? copy("TV inputs", "Ingressi TV") : copy("Settings", "Impostazioni"));
            tintShadow(a);
            addAt(root, a, width, height, actionRects[i]);
        }
        for (int i = 0; i < actions.length; i++) {
            String tag = actions[i];
            TextView word = stockText("search".equals(tag) ? copy("Search", "Cerca") : "pair".equals(tag) ? copy("Phone", "Telefono")
                    : "sources".equals(tag) ? copy("Inputs", "Ingressi") : copy("Settings", "Impostazioni"), 13, Fonts.regular(this), skin.textColor);
            word.setGravity(Gravity.CENTER_HORIZONTAL);
            float[] r = actionRects[i];
            addAt(root, word, width, height, new float[]{r[0] - .02f, r[1] + r[3] + .012f, r[2] + .04f, .035f});
        }
        // connect: a pill while a phone is here (a phone is optional), with a VPN badge
        connectPanel = new LinearLayout(this);
        connectPanel.setGravity(Gravity.TOP | Gravity.END);
        LinearLayout pill = new LinearLayout(this);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(roundRect(skin.panelFill, 20));
        pill.setPadding(Math.round(16 * unit), 0, Math.round(8 * unit), 0);
        View pillDot = new View(this);
        pillDot.setBackground(roundRect(skin.ok, 4));
        pill.addView(pillDot, new LinearLayout.LayoutParams(Math.round(8 * unit), Math.round(8 * unit)));
        TextView pillText = stockText(copy("Phone connected", "Telefono collegato"), 14, reg, skin.textColor);
        LinearLayout.LayoutParams pt = new LinearLayout.LayoutParams(-2, -2);
        pt.leftMargin = Math.round(8 * unit);
        pill.addView(pillText, pt);
        vpnBadge = stockText("VPN", 11, Fonts.bold(this), skin.mutedColor);
        vpnBadge.setBackground(roundRect(skin.panelStrong, 12));
        vpnBadge.setPadding(Math.round(9 * unit), Math.round(4 * unit), Math.round(9 * unit), Math.round(4 * unit));
        LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(-2, -2);
        vl.leftMargin = Math.round(10 * unit);
        pill.addView(vpnBadge, vl);
        connectPanel.addView(pill, new LinearLayout.LayoutParams(-2, Math.round(40 * unit)));
        addAt(root, connectPanel, width, height, new float[]{.45f, .07f, .28f, .06f});
        connectPanel.setVisibility(View.GONE);

        pointer = new RayGlobalControl.Pointer(this);
        pointer.setVisibility(View.GONE);
        root.addView(pointer, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        selectCategory("home", false);
        if (!appViews.isEmpty()) appViews.get(0).requestFocus();
        applyWallpaper();
    }

    /** A phone is optional: the pill (and the VPN badge) shows only while one is connected. */
    private void updateConnectPanel() {
        if (connectPanel == null) return;
        boolean phone = RaySocket.clientCount() > 0;
        connectPanel.setVisibility(phone ? View.VISIBLE : View.GONE);
        if (phone) vpnBadge.setVisibility(RayVpn.state(this).optBoolean("active") ? View.VISIBLE : View.GONE);
    }

    /** Home: one row mixing Watch Next with the apps. Every other category: a whole-screen grid. */
    private void updateRows() {
        View oldFocus = getCurrentFocus();
        int oldIndex = appViews.indexOf(oldFocus);
        Object oldTag = oldIndex >= 0 ? oldFocus.getTag() : null;
        for (View tile : appViews) { spots.remove(tile); freeHoriz.remove(tile); }
        appViews.clear();
        cardLayer.removeAllViews();
        boolean home = category.equals("home");
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = dm.widthPixels, h = dm.heightPixels, gap = Math.round(.0125f * w), pad = Math.round(.05f * w), padV = Math.round(36 * unit);
        ArrayList<Object> items = new ArrayList<>();
        if (home) {
            // what you were watching above, the apps below; with nothing to continue the apps have the screen
            items.addAll(homeApps());
            ArrayList<Object> watch = new ArrayList<>();
            for (RayWatchHistory.Entry e : watchHistory) if (watch.size() < 10) watch.add(e);
            boolean two = !watch.isEmpty();
            if (two) addRow(watch, WATCH_TOP, 6, gap, pad, padV, w, h);
            addRow(items, two ? APPS_TOP : ROW_TOP, 6, gap, pad, padV, w, h);
            if (two) rowTitle(copy("Continue watching", "Continua a guardare"), WATCH_TOP, pad, h);
            rowTitle(copy("Your apps", "Le tue app"), two ? APPS_TOP : ROW_TOP, pad, h);
            stockHero.textBottom(two ? .535f : .69f);
        } else {
            if (category.equals("search")) for (RayWatchHistory.Entry e : watchHistory) if (RaySearch.matches(e.title, appSearch)) items.add(e);
            final int firstApp = items.size();
            for (AppEntry app : apps) if (categoryMatches(app, category)) items.add(app);
            final java.text.Collator order = java.text.Collator.getInstance();
            Collections.sort(items.subList(firstApp, items.size()), new java.util.Comparator<Object>() {
                @Override public int compare(Object a, Object b) { return order.compare(((AppEntry) a).name, ((AppEntry) b).name); }
            });
            if (category.equals("search")) for (AppEntry app : apps) // then the same words inside every app that takes them
                if (searchable.contains(app.packageName)) items.add("ph:find:" + app.packageName);
            if (items.isEmpty()) items.add("ph:apps"); // a search with no result: the D-pad still has a target
            addGrid(items, GRID_TOP, 6, gap, pad, w, h);
        }
        int chrome = home ? View.VISIBLE : View.GONE;
        clock.setVisibility(chrome);
        dateView.setVisibility(chrome);
        weatherView.setVisibility(chrome);
        View target = null;
        if (oldTag != null) for (View t : appViews) if (oldTag.equals(t.getTag())) target = t;
        if (target == null && (oldFocus == null || oldIndex >= 0) && !appViews.isEmpty()) target = appViews.get(0);
        if (oldIndex == 0 && !appViews.isEmpty()) target = appViews.get(0); // the first card stays the first when Watch Next arrives
        if (!homeFocused && appViews.size() > 1) { target = appViews.get(0); homeFocused = true; } // not the lone All apps of an empty list
        if (target != null) target.requestFocus();
        if (getCurrentFocus() == null && !appViews.isEmpty()) appViews.get(0).requestFocus();
        if (!appViews.isEmpty()) appViews.get(0).setFocusedByDefault(true); // leaving touch mode lands here, not on a disc
        wireFocus();
        View focused = getCurrentFocus();
        updateStage(focused != null && appViews.contains(focused) ? tileItem(focused) : appViews.isEmpty() ? null : tileItem(appViews.get(0)), true); // touch mode has no focus: the stage is never empty
    }

    private void addRow(List<Object> list, float topFrac, int per, int gap, int pad, int padV, int w, int h) {
        int cw = Math.round((w - 2f * pad - (per - 1) * gap) / per), ch = Math.round(cw * 9f / 16);
        HorizontalScrollView scroller = new RowScroller(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroller.setClipChildren(false);
        scroller.setClipToPadding(false);
        scroller.setHorizontalFadingEdgeEnabled(true); // never drawn (RowScroller), but its length is the margin a scrolled-to card keeps from the edge
        scroller.setFadingEdgeLength(pad);
        scroller.setPadding(pad, padV, pad, padV);
        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        scroller.addView(row, new FrameLayout.LayoutParams(-2, -1));
        for (int i = 0; i < list.size(); i++) {
            CardView tile = itemCard(list.get(i), ch);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cw, ch);
            lp.rightMargin = i < list.size() - 1 ? gap : 0;
            row.addView(tile, lp);
            appViews.add(tile);
            freeHoriz.add(tile);
            if (i < per) spot(tile, new float[]{(pad + i * (float) (cw + gap)) / w, topFrac, (float) cw / w, (float) ch / h});
        }
        if (row.getChildCount() > 0) { // the ends of a row stay in the row
            View first = row.getChildAt(0), last = row.getChildAt(row.getChildCount() - 1);
            if (first.getId() == View.NO_ID) first.setId(View.generateViewId());
            if (last.getId() == View.NO_ID) last.setId(View.generateViewId());
            first.setNextFocusLeftId(first.getId());
            last.setNextFocusRightId(last.getId());
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, ch + 2 * padV);
        lp.topMargin = Math.round(topFrac * h) - padV;
        cardLayer.addView(scroller, lp);
    }

    private void rowTitle(String text, float top, int pad, int h) {
        TextView title = stockText(text, 18, skin.cubeA != 0 ? Fonts.chakra(this) : Fonts.bold(this), skin.textColor);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2);
        lp.leftMargin = pad;
        lp.topMargin = Math.round(top * h - 25 * unit);
        cardLayer.addView(title, lp);
    }

    private static final float ROW_TOP = .76f, WATCH_TOP = .585f, APPS_TOP = .79f, GRID_TOP = .2f;

    private static final int HOME_APPS = 12;

    /** The app row: pinned, then by last use (usage access if granted, else launches from RAY); All apps last. */
    private List<Object> homeApps() {
        java.util.LinkedHashSet<String> order = new java.util.LinkedHashSet<>();
        for (String p : prefs.getString("ray_pinned", "").split("\\n")) if (!p.isEmpty()) order.add(p);
        order.addAll(recentlyUsed);
        for (String p : prefs.getString("ray_recent", "").split("\\n")) if (!p.isEmpty()) order.add(p);
        ArrayList<Object> out = new ArrayList<>();
        for (String p : order) { AppEntry a = findApp(p); if (a != null && out.size() < HOME_APPS) out.add(a); }
        // a new TV: never an empty home, and what people watch before the utilities
        ArrayList<AppEntry> ranked = new ArrayList<>(apps);
        Collections.sort(ranked, new java.util.Comparator<AppEntry>() {
            @Override public int compare(AppEntry a, AppEntry b) { return Integer.compare(firstRank(a), firstRank(b)); }
        });
        for (AppEntry a : ranked) if (out.size() < 8 && !out.contains(a)) out.add(a);
        out.add("ph:apps");
        return out;
    }

    private static final java.util.regex.Pattern MEDIA_APP = java.util.regex.Pattern.compile("(?i)netflix|primevideo|amazon\\.avod|amazon\\.firetv\\.youtube|youtube|smarttube|kodi|xbmc|cloudstream|plex|stremio|disney|vlc|dazn|pluto|raiplay|infinity");

    private static int firstRank(AppEntry a) {
        if (a.category == ApplicationInfo.CATEGORY_VIDEO || MEDIA_APP.matcher(a.packageName).find()) return 0;
        return a.banner != null ? 1 : 2;
    }

    /** Packages used in the last 30 days, most recent first; empty without usage access. */
    private List<String> usedRecently() {
        ArrayList<String> out = new ArrayList<>();
        try {
            android.app.usage.UsageStatsManager u = (android.app.usage.UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            List<android.app.usage.UsageStats> stats = u.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_BEST, now - 30L * 86400000L, now);
            Collections.sort(stats, new java.util.Comparator<android.app.usage.UsageStats>() {
                @Override public int compare(android.app.usage.UsageStats a, android.app.usage.UsageStats b) { return Long.compare(b.getLastTimeUsed(), a.getLastTimeUsed()); }
            });
            for (android.app.usage.UsageStats st : stats)
                if (st.getTotalTimeInForeground() > 0 && !st.getPackageName().equals(getPackageName()) && !out.contains(st.getPackageName())) out.add(st.getPackageName());
        } catch (RuntimeException unavailable) { }
        return out;
    }

    /** Long press (or Menu) on an app card: everything you can do with it, in words. */
    private void showAppMenu(final AppEntry a) {
        boolean pinned = java.util.Arrays.asList(prefs.getString("ray_pinned", "").split("\\n")).contains(a.packageName);
        String[] items = {copy("Open", "Apri"), pinned ? copy("Remove from the start of the row", "Togli dall'inizio della riga") : copy("Move to the start of the row", "Metti all'inizio della riga"),
                copy("App info", "Informazioni app"), copy("Uninstall", "Disinstalla")};
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(a.name).setItems(items, new android.content.DialogInterface.OnClickListener() {
            @Override public void onClick(android.content.DialogInterface d, int which) {
                Uri uri = Uri.parse("package:" + a.packageName);
                try {
                    if (which == 0) launch(a.packageName);
                    else if (which == 1) togglePin(a.packageName);
                    else startActivity(new Intent(which == 2 ? Settings.ACTION_APPLICATION_DETAILS_SETTINGS : Intent.ACTION_DELETE, uri));
                } catch (RuntimeException unavailable) { RayDebug.log("app menu: " + unavailable); }
            }
        }).setNegativeButton(copy("Close", "Chiudi"), null).create();
        showingDialog = dialog;
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface dismissed) { if (showingDialog == dismissed) showingDialog = null; }
        });
        showStyled(dialog);
    }

    /** Pins an app to the start of the row, or unpins it. */
    private void togglePin(String pkg) {
        ArrayList<String> pinned = new ArrayList<>();
        for (String p : prefs.getString("ray_pinned", "").split("\\n")) if (!p.isEmpty()) pinned.add(p);
        boolean pin = !pinned.remove(pkg);
        if (pin) pinned.add(0, pkg);
        prefs.edit().putString("ray_pinned", android.text.TextUtils.join("\n", pinned)).apply();
        AppEntry a = findApp(pkg);
        android.widget.Toast.makeText(this, (a == null ? pkg : a.name) + ": " + (pin ? copy("pinned to the home", "fissata in home")
                : copy("unpinned", "non pi\u00f9 fissata")), android.widget.Toast.LENGTH_SHORT).show();
        RayDebug.log((pin ? "pin " : "unpin ") + pkg);
        updateRows();
    }

    private CardView itemCard(Object item, int ch) {
        if (item instanceof String && ((String) item).startsWith("ph:find:")) {
            AppEntry target = findApp(((String) item).substring(8));
            return homeCard(item, null, null, tint(target), copy("Search in ", "Cerca in ") + target.name, "", -1, ch, false);
        }
        if ("ph:apps".equals(item)) {
            return homeCard("ph:apps", null, null, mix(skin.ring, skin.light ? Color.WHITE : Color.BLACK, .68f), copy("All apps", "Tutte le app"), "", -1, ch, false);
        }
        if (item instanceof RayWatchHistory.Entry) {
            RayWatchHistory.Entry e = (RayWatchHistory.Entry) item;
            AppEntry owner = findApp(e.packageName);
            return homeCard(e, e.artwork, null, 0, e.title, e.minutesLeft > 0 ? remaining(e.minutesLeft) : owner == null ? "" : owner.name, e.progress, ch, true);
        }
        final AppEntry a = (AppEntry) item;
        CardView card = homeCard(a.packageName, null, a.banner != null && !blank(a) ? a.banner : null, tint(a), a.name, "", -1, ch, false);
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { showAppMenu(a); return true; }
        });
        return card;
    }

    /** Every app as a grid of big cards, scrolling down; used when there is nothing to continue. */
    private void addGrid(List<Object> list, float topFrac, int per, int gap, int pad, int w, int h) {
        int cw = Math.round((w - 2f * pad - (per - 1) * gap) / per), ch = Math.round(cw * 9f / 16), padV = Math.round(24 * unit), rowGap = 2 * gap;
        ScrollView scroller = new ScrollView(this);
        scroller.setFocusable(false);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroller.setClipChildren(false);
        scroller.setClipToPadding(false);
        scroller.setPadding(pad, padV, pad, padV);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        scroller.addView(column, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout row = null;
        for (int i = 0; i < list.size(); i++) {
            int r = i / per, c = i % per;
            if (c == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
                rl.topMargin = r == 0 ? 0 : rowGap;
                column.addView(row, rl);
            }
            CardView tile = itemCard(list.get(i), ch);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cw, ch);
            lp.rightMargin = c < per - 1 ? gap : 0;
            row.addView(tile, lp);
            appViews.add(tile);
            spot(tile, new float[]{(pad + c * (float) (cw + gap)) / w, topFrac + r * (float) (ch + rowGap) / h, (float) cw / w, (float) ch / h});
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h - Math.round(topFrac * h) + padV);
        lp.topMargin = Math.round(topFrac * h) - padV;
        cardLayer.addView(scroller, lp);
    }

    /** Rounded card: artwork (or the app colour), scrim + name for Watch Next, centered name for apps without a banner. */
    private CardView homeCard(Object tag, Bitmap bitmap, Drawable drawable, int tint, String title, String sub, float progress, int ch, boolean watch) {
        CardView tile = new CardView(this, skin, unit);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setTag(tag);
        tile.setOnClickListener(this);
        tile.setOnFocusChangeListener(this);
        tile.setContentDescription(title);
        ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        boolean image = bitmap != null || drawable != null;
        if (bitmap != null) art.setImageBitmap(bitmap);
        else {
            if (drawable != null) art.setImageDrawable(drawable);
            int base = tint != 0 ? tint : 0xff3c4a63;
            art.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{mix(base, Color.WHITE, .14f), base}));
        }
        tile.addView(art, new FrameLayout.LayoutParams(-1, -1));
        if (watch) {
            tile.progress = progress; // the title is on the stage when it has the focus
        } else if (!image) {
            TextView name = stockText(title, 15, Fonts.bold(this), Color.WHITE);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setPadding(Math.round(8 * unit), 0, Math.round(8 * unit), 0);
            AppEntry owner = tag instanceof String ? findApp(((String) tag).replace("ph:find:", "")) : null;
            Drawable glyph = "ph:apps".equals(tag) ? actionIcon("apps") : owner == null ? null : owner.icon;
            if (glyph != null && !image) { // no usable banner: the app icon, then its name
                LinearLayout stack = new LinearLayout(this);
                stack.setOrientation(LinearLayout.VERTICAL);
                stack.setGravity(Gravity.CENTER_HORIZONTAL);
                ImageView icon = new ImageView(this);
                icon.setImageDrawable(glyph);
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                stack.addView(icon, new LinearLayout.LayoutParams(Math.round(ch * .42f), Math.round(ch * .42f)));
                stack.addView(name, new LinearLayout.LayoutParams(-1, -2));
                tile.addView(stack, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
            } else tile.addView(name, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        }
        tile.setFocusLook(false);
        return tile;
    }

    /** A banner that is transparent or one flat colour carries no name: such an app gets the tinted card with its name. */
    private boolean blank(AppEntry app) {
        Boolean cached = blanks.get(app.packageName);
        if (cached != null) return cached;
        boolean blank = false;
        try {
            Bitmap b = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888);
            Rect old = app.banner.copyBounds();
            app.banner.setBounds(0, 0, 16, 9);
            app.banner.draw(new Canvas(b));
            app.banner.setBounds(old);
            double sum = 0, sq = 0, alpha = 0;
            for (int y = 0; y < 9; y++) for (int x = 0; x < 16; x++) {
                int p = b.getPixel(x, y);
                double l = .299 * Color.red(p) + .587 * Color.green(p) + .114 * Color.blue(p);
                sum += l; sq += l * l; alpha += Color.alpha(p);
            }
            b.recycle();
            double mean = sum / 144;
            blank = alpha / 144 < 90 || Math.sqrt(Math.max(0, sq / 144 - mean * mean)) < 10;
        } catch (RuntimeException | OutOfMemoryError ignored) { }
        blanks.put(app.packageName, blank);
        return blank;
    }

    /** Colour of an app card without banner: the icon's dominant colour, made dark (or light) enough for white text. */
    private int tint(AppEntry app) {
        Integer cached = tints.get(app.packageName);
        if (cached != null) return cached;
        int out = 0xff3c4a63;
        Drawable d = app.icon != null ? app.icon : app.banner;
        if (d != null) try {
            Bitmap b = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
            Rect old = d.copyBounds();
            d.setBounds(0, 0, 16, 16);
            d.draw(new Canvas(b));
            d.setBounds(old);
            double r = 0, g = 0, bl = 0, wsum = 0;
            float[] hsv = new float[3];
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
                int p = b.getPixel(x, y);
                if (Color.alpha(p) < 200) continue;
                Color.colorToHSV(p, hsv);
                double wt = hsv[1] * hsv[2] + .02;
                r += Color.red(p) * wt; g += Color.green(p) * wt; bl += Color.blue(p) * wt; wsum += wt;
            }
            b.recycle();
            if (wsum > 0) {
                Color.colorToHSV(Color.rgb((int) (r / wsum), (int) (g / wsum), (int) (bl / wsum)), hsv);
                if (hsv[1] > .15f) {
                    hsv[1] = Math.max(hsv[1], .45f);
                    hsv[2] = skin.light ? Math.max(.62f, Math.min(.85f, hsv[2])) : Math.max(.42f, Math.min(.62f, hsv[2]));
                    out = Color.HSVToColor(hsv);
                }
            }
        } catch (RuntimeException | OutOfMemoryError ignored) { }
        tints.put(app.packageName, out);
        return out;
    }

    /** Rebuilds the wallpaper-based scene off the UI thread. */
    /** The skin's own icon if it has one, otherwise the bundled line icon (res/drawable/ray_ic_*). */
    private Drawable actionIcon(String tag) {
        Drawable themed = RayIcons.get(this, skin, tag);
        if (themed != null) return themed;
        int id = getResources().getIdentifier("ray_ic_" + tag, "drawable", getPackageName());
        return id == 0 ? null : getDrawable(id).mutate();
    }

    private void applyWallpaper() {
        if (backdrop == null || wallpaperWorker.isShutdown()) return;
        wpStamp = RayWallpaper.stamp(this);
        final int generation = ++wpGeneration;
        final RaySkin forSkin = skin;
        try {
            wallpaperWorker.execute(new Runnable() {
                @Override public void run() {
                    final Bitmap scene = RayWallpaper.stockScene(RayLauncherActivity.this, forSkin);
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (generation == wpGeneration && !isFinishing() && !isDestroyed()) backdrop.setScene(scene);
                            else if (scene != null) scene.recycle();
                        }
                    });
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException destroyed) { }
    }

    /** Called by the remote server after the wallpaper changed. */
    static void wallpaperChanged() {
        final RayLauncherActivity activity = active;
        if (activity != null) activity.main.post(new Runnable() {
            @Override public void run() { if (!activity.isFinishing() && !activity.isDestroyed()) activity.applyWallpaper(); }
        });
    }

    /** Sideways row of cards: not focusable itself, no fade drawn. */
    private static final class RowScroller extends HorizontalScrollView {
        RowScroller(Context c) { super(c); setFocusable(false); }
        @Override protected float getLeftFadingEdgeStrength() { return 0; }
        @Override protected float getRightFadingEdgeStrength() { return 0; }
    }

    /** Round action button of the frameless skins: translucent disc, icon from the skin or drawn here. */
    private static final class StockAction extends View {
        private final String kind;
        private final Drawable icon;
        private final RaySkin skin;
        private final float u;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        StockAction(Context c, String kind, Drawable icon, RaySkin skin, float u) {
            super(c);
            this.kind = kind;
            this.icon = icon;
            this.skin = skin;
            this.u = u;
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(1.8f * u);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); }
            });
        }

        void focusLook(boolean focused) {
            if (skin.cubeA == 0) setElevation(focused ? 10 * u : 0);
            invalidate();
        }

        /** The button of the owner's portfolio: a wireframe box, front plus left and bottom faces, stroked with a 45 degree gradient; focused, the faces fill. */
        private void drawCube(Canvas canvas, boolean f) {
            float w = getWidth(), h = getHeight(), d = w * .2f, stroke = 2 * u;
            paint.setStrokeWidth(stroke);
            paint.setColor(Color.WHITE); // full alpha: the shader is multiplied by it
            paint.setShader(new android.graphics.LinearGradient(0, h, w, 0, skin.cubeA, skin.cubeB, android.graphics.Shader.TileMode.CLAMP));
            android.graphics.Path left = new android.graphics.Path(), bottom = new android.graphics.Path();
            left.moveTo(d, 0); left.lineTo(0, d); left.lineTo(0, h); left.lineTo(d, h - d); left.close();
            bottom.moveTo(d, h - d); bottom.lineTo(0, h); bottom.lineTo(w - d, h); bottom.lineTo(w, h - d); bottom.close();
            paint.setStyle(f ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
            canvas.drawPath(left, paint);
            canvas.drawPath(bottom, paint);
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawRect(d, stroke / 2, w - stroke / 2, h - d, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x14ffffff);
            canvas.drawRect(d, 0, w, h - d, paint);
            if (icon != null) {
                float cx = (d + w) / 2, cy = (h - d) / 2;
                int s = Math.round(20 * u);
                icon.setBounds(Math.round(cx) - s / 2, Math.round(cy) - s / 2, Math.round(cx) + s / 2, Math.round(cy) + s / 2);
                icon.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
                icon.draw(canvas);
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            boolean f = isFocused();
            if (skin.cubeA != 0) { drawCube(canvas, f); return; }
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(f ? skin.ring : skin.panelFill);
            canvas.drawCircle(cx, cy, cx, paint);
            int ink = f ? skin.btnFg : skin.textColor;
            if (icon != null) {
                int s = Math.round(22 * u);
                icon.setBounds(Math.round(cx) - s / 2, Math.round(cy) - s / 2, Math.round(cx) + s / 2, Math.round(cy) + s / 2);
                icon.setColorFilter(ink, PorterDuff.Mode.SRC_IN);
                icon.draw(canvas);
                return;
            }
        }
    }

    private AppEntry findApp(String packageName) {
        for (AppEntry app : apps) if (app.packageName.equals(packageName)) return app;
        return null;
    }

    private boolean categoryMatches(AppEntry app, String selected) {
        String key = (app.name + " " + app.packageName).toLowerCase(Locale.ROOT);
        if (selected.equals("search")) return RaySearch.matches(key, appSearch);
        if (selected.equals("home") || selected.equals("apps")) return true;
        if (selected.equals("video")) return app.category == ApplicationInfo.CATEGORY_VIDEO;
        if (selected.equals("games")) return app.category == ApplicationInfo.CATEGORY_GAME
                || (app.flags & ApplicationInfo.FLAG_IS_GAME) != 0;
        if (selected.equals("music")) return app.category == ApplicationInfo.CATEGORY_AUDIO;
        return false;
    }
    private void setPalette(boolean day) {
        BG = day ? Color.rgb(0, 67, 82) : Color.rgb(3, 14, 31);
        TEXT = Color.rgb(247, 253, 255);
        MUTED = day ? Color.rgb(190, 237, 243) : Color.rgb(151, 192, 211);
    }

    /** "Auto" / "Giorno" / "Notte" for the mode button. */
    private String modeLabel() {
        String m = RayDayNight.mode(this);
        return "auto".equals(m) ? "Auto" : "day".equals(m) ? copy("Day", "Giorno") : copy("Night", "Notte");
    }

    /** Rebuilds the home when day or night changed (sunrise, sunset, or the pref); true if it did. A rebuild reads the same answer, so it cannot loop. */
    private boolean checkDayNight() {
        if (RayDayNight.isDay(this) == dayMode || isFinishing() || isDestroyed()) return false;
        recreate();
        return true;
    }

    /** Saves ray_daynight (auto, day, night) and applies it now. */
    static void setDayNight(Context context, String mode) {
        RayDayNight.set(context, mode);
        final RayLauncherActivity activity = active;
        if (activity != null) activity.main.post(new Runnable() { @Override public void run() { activity.checkDayNight(); activity.showModeToast(); } });
        RaySocket.stateChanged();
    }

    private void showModeToast() {
        android.widget.Toast.makeText(this, copy("Day/night: ", "Giorno/notte: ") + modeLabel(), android.widget.Toast.LENGTH_SHORT).show();
    }

    private void selectCategory(String value, boolean focusFirst) {
        category = value;
        RayDebug.log("category " + value);
        updateRows();
        if (focusFirst && !appViews.isEmpty()) appViews.get(0).requestFocus();
    }

    private void addApps(PackageManager pm, Intent query, Set<String> seen, List<AppEntry> out) {
        for (ResolveInfo item : pm.queryIntentActivities(query, 0)) {
            if (item.activityInfo == null) continue;
            String pkg = item.activityInfo.packageName;
            if (pkg == null || pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
            try {
                Drawable banner = null;
                try { banner = item.activityInfo.loadBanner(pm); } catch (RuntimeException ignored) { }
                if (banner == null) {
                    try { banner = pm.getApplicationBanner(item.activityInfo.applicationInfo); }
                    catch (RuntimeException ignored) { }
                }
                CharSequence label = item.loadLabel(pm);
                out.add(new AppEntry(pkg, label == null ? pkg : label.toString(),
                        shrink(item.loadIcon(pm), 256), shrink(banner, 640), item.activityInfo.applicationInfo.flags,
                        item.activityInfo.applicationInfo.category));
            } catch (RuntimeException | OutOfMemoryError broken) {
                seen.remove(pkg);
            }
        }
    }

    /** Oversized artwork is redrawn small: the scene layer repaints it up to 25 times a second. */
    private Drawable shrink(Drawable drawable, int max) {
        if (drawable == null) return null;
        int w = drawable.getIntrinsicWidth(), h = drawable.getIntrinsicHeight();
        if (w <= 0 || h <= 0 || w <= max && h <= max) return drawable;
        try {
            float scale = (float) max / Math.max(w, h);
            Bitmap bitmap = Bitmap.createBitmap(Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
            drawable.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight());
            drawable.draw(canvas);
            return new android.graphics.drawable.BitmapDrawable(getResources(), bitmap);
        } catch (RuntimeException | OutOfMemoryError tooBig) {
            return null;
        }
    }

    @Override
    public void onClick(View view) {
        if ("pair".equals(view.getTag())) {
            showPairingDialog();
        } else if ("search".equals(view.getTag())) {
            showSearch();
        } else if ("sources".equals(view.getTag())) {
            Intent intent = new Intent("android.media.tv.action.SETUP_INPUTS");
            if (intent.resolveActivity(getPackageManager()) == null) intent = new Intent(Settings.ACTION_SETTINGS);
            startActivity(intent);
        } else if ("settings".equals(view.getTag())) {
            showSettings();
        } else if (view.getTag() instanceof RayWatchHistory.Entry) {
            RayWatchHistory.Entry item = (RayWatchHistory.Entry) view.getTag();
            if (!RayWatchHistory.launch(this, item)) launch(item.packageName);
        } else if (view.getTag() instanceof String && ((String) view.getTag()).startsWith("ph:")) {
            placeholderAction(((String) view.getTag()).substring(3));
        } else if (view.getTag() instanceof String) {
            launch((String) view.getTag());
        }
    }

    static void openSoundSettings(Context context) {
        try { context.startActivity(new Intent(Settings.ACTION_SOUND_SETTINGS)); }
        catch (RuntimeException unavailable) {
            try { context.startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (RuntimeException ignored) { }
        }
    }

    private void placeholderAction(String what) {
        if (what.startsWith("find:")) {
            if (!RayMedia.search(this, what.substring(5), appSearch)) android.widget.Toast.makeText(this, copy("This app did not take the search", "Questa app non ha accettato la ricerca"), android.widget.Toast.LENGTH_SHORT).show();
        } else if ("apps".equals(what)) selectCategory("apps", true);
        else if ("search".equals(what)) showSearch();
        else if ("pair".equals(what)) showPairingDialog();
        else if ("settings".equals(what)) showSettings();
        else try { startActivity(new Intent(Settings.ACTION_APPLICATION_SETTINGS)); }
        catch (RuntimeException unavailable) { openSoundSettings(this); }
    }

    private static Intent voiceIntent() {
        return new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
    }

    /** The microphone of the remote, where the device has a speech service; otherwise the search box. */
    private void startVoice() {
        try { startActivityForResult(voiceIntent(), REQUEST_VOICE); }
        catch (RuntimeException none) { showSearch(); }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQUEST_VOICE || result != RESULT_OK || data == null) return;
        ArrayList<String> said = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
        if (said != null && !said.isEmpty()) runSearch(said.get(0));
    }

    private static final int REQUEST_VOICE = 42;

    private void runSearch(String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) return;
        appSearch = q;
        RayDebug.log("search");
        selectCategory("search", true);
    }

    /** A search typed or spoken on the phone. Any thread. */
    static boolean remoteSearch(final String query) {
        final RayLauncherActivity a = active;
        if (a == null || query == null || query.trim().isEmpty() || query.length() > 200) return false;
        a.main.post(new Runnable() { @Override public void run() { if (a.showingDialog != null) a.showingDialog.dismiss(); a.runSearch(query); } });
        return true;
    }

    private void showSearch() {
        final EditText query = new EditText(this);
        query.setSingleLine(true);
        query.setHint(copy("A film, a show, an app", "Un film, una serie, un'app"));
        query.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        query.setTextColor(TEXT);
        query.setHintTextColor(MUTED);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(copy("Search", "Cerca"))
                .setView(query).setNegativeButton(copy("Cancel", "Annulla"), null).create();
        dialog.setButton(AlertDialog.BUTTON_POSITIVE, copy("Search", "Cerca"), new android.content.DialogInterface.OnClickListener() {
            @Override public void onClick(android.content.DialogInterface d, int which) { runSearch(query.getText().toString()); }
        });
        if (voiceIntent().resolveActivity(getPackageManager()) != null)
            dialog.setButton(AlertDialog.BUTTON_NEUTRAL, copy("Speak", "Parla"), new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface d, int which) { startVoice(); }
            });
        query.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int action, KeyEvent e) {
                runSearch(v.getText().toString());
                dialog.dismiss();
                return true;
            }
        });
        showingDialog = dialog;
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface dismissed) {
                if (showingDialog == dialog) showingDialog = null;
            }
        });
        showStyled(dialog);
    }

    @Override
    public void onFocusChange(View view, boolean hasFocus) {
        if (view instanceof StockAction) {
            ((StockAction) view).focusLook(hasFocus);
            float grow = skin.cubeA != 0 || !hasFocus ? 1f : 1.1f, lift = skin.cubeA != 0 && hasFocus ? 4 * unit : 0; // cubes lift, discs grow
            view.animate().scaleX(grow).scaleY(grow).translationX(lift).translationY(-lift).setDuration(160)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            return;
        }
        if (view instanceof CardView) ((CardView) view).setFocusLook(hasFocus);
        view.animate().scaleX(hasFocus ? skin.focusScale : 1f).scaleY(hasFocus ? skin.focusScale : 1f).setDuration(160)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        if (hasFocus && appViews.contains(view)) {
            focusedApp = view;
            updateStage(tileItem(view), false);
            Object tag = view.getTag();
            String pkg = tag instanceof String ? (String) tag
                    : tag instanceof RayWatchHistory.Entry ? ((RayWatchHistory.Entry) tag).packageName : null;
            if (pkg != null && !pkg.startsWith("ph:")) {
                prefs.edit().putString("ray_focused", pkg).apply();
                RaySocket.stateChanged();
            }
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        RayDebug.key(event);
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_HOME) return true;
            if (event.getKeyCode() == KeyEvent.KEYCODE_SEARCH || event.getKeyCode() == KeyEvent.KEYCODE_VOICE_ASSIST) {
                if (voiceIntent().resolveActivity(getPackageManager()) != null) startVoice(); else showSearch();
                return true;
            }
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) { if (!category.equals("home")) { selectCategory("home", true); appViews.get(appViews.size() - 1).requestFocus(); } return true; }
            View f = getCurrentFocus();
            if (event.getKeyCode() == KeyEvent.KEYCODE_MENU && f != null && f.getTag() instanceof String
                    && findApp((String) f.getTag()) != null) { showAppMenu(findApp((String) f.getTag())); return true; }
        }
        return super.dispatchKeyEvent(event);
    }

    private void launch(String packageName) {
        if (!launchPackage(this, packageName)) {
            android.widget.Toast.makeText(this, copy("App not available", "App non disponibile"), android.widget.Toast.LENGTH_SHORT).show();
            refreshApps(); // probably uninstalled
        }
    }

    private static boolean launchPackage(Context context, String packageName) {
        PackageManager pm = context.getPackageManager();
        Intent intent = pm.getLeanbackLaunchIntentForPackage(packageName);
        if (intent == null) intent = pm.getLaunchIntentForPackage(packageName);
        if (intent == null) return false;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (RuntimeException unavailable) { // ActivityNotFound, Security...
            return false;
        }
        RayDebug.log("launch " + packageName);
        SharedPreferences prefs = context.getSharedPreferences(DimService.PREFS, Context.MODE_PRIVATE);
        String[] current = prefs.getString("ray_recent", "").split("\\n");
        StringBuilder recent = new StringBuilder(packageName);
        for (String name : current) if (!name.isEmpty() && !name.equals(packageName) && recent.length() < 160) recent.append('\n').append(name);
        prefs.edit().putString("ray_recent", recent.toString()).putString("ray_focused", packageName)
                .putString("ray_launched", packageName).apply();
        return true;
    }

    public static JSONObject rayState(Context context) {
        JSONObject result = new JSONObject();
        JSONArray list = new JSONArray();
        RayLauncherActivity activity = active;
        SharedPreferences prefs = context.getSharedPreferences(DimService.PREFS, Context.MODE_PRIVATE);
        for (AppEntry app : cachedApps) {
            try { list.put(new JSONObject().put("package", app.packageName).put("name", app.name)); }
            catch (JSONException ignored) { }
        }
        try {
            result.put("device", Build.MODEL).put("manufacturer", Build.MANUFACTURER)
                    .put("active", activity != null).put("apps", list)
                    .put("focusedPackage", prefs.getString("ray_focused", ""))
                    .put("lastLaunchedPackage", prefs.getString("ray_launched", ""))
                    .put("foregroundKnown", true);
            String foreground = RayMedia.foreground(context);
            result.put("foregroundPackage", foreground).put("media", RayMedia.state(context, foreground));
            result.put("theme", new JSONObject()
                    .put("color", prefs.getString("ray_theme_color", "#24dcff"))
                    .put("style", prefs.getString("ray_theme_style", "glass"))
                    .put("skin", current(context))
                    .put("remoteSkin", prefs.getString("ray_remote_skin", "match"))
                    .put("daynight", RayDayNight.mode(context))
                    .put("isDay", RayDayNight.isDay(context)));
            JSONArray skins = new JSONArray();
            for (RaySkin s : RaySkin.list(context))
                skins.put(new JSONObject().put("id", s.id).put("name", new JSONObject().put("it", s.title(true)).put("en", s.title(false)))
                        .put("accent", String.format("#%06x", s.forMode(RayDayNight.isDay(context)).accent & 0xffffff)).put("hasRemote", s.hasRemote).put("user", s.user));
            result.getJSONObject("theme").put("skins", skins);
            result.put("vpn", RayVpn.state(context));
            result.put("music", RayAmbient.enabled(context)).put("musicPlaying", RayAmbient.isPlaying());
            // never the API key: only what the phone needs to show
            result.put("weather", RayWeather.cached(context)).put("weatherConsent", RayWeather.consent(context)).put("weatherCity", prefs.getString("ray_weather_city", ""))
                    .put("weatherProvider", prefs.getString("ray_weather_provider", "openmeteo"));
            JSONObject control = RayGlobalControl.state(context);
            result.put("rootChecked", control.optBoolean("rootChecked"))
                    .put("rootAvailable", control.optBoolean("rootAvailable"))
                    .put("globalInput", control.optBoolean("globalInput"))
                    .put("motionPointer", control.optBoolean("motionPointer"))
                    .put("pointerActive", control.optBoolean("pointerActive")
                            || activity != null && activity.pointer != null
                            && activity.pointer.getVisibility() == View.VISIBLE);
        } catch (JSONException ignored) { }
        return result;
    }

    public static JSONObject command(Context context, JSONObject command) {
        RayLauncherActivity activity = active;
        if (command == null) return response(false);
        String type = command.optString("type", "");
        if ("theme.set".equals(type)) {
            boolean hasColor = command.has("color"), hasStyle = command.has("style");
            if (command.has("daynight")) {
                String m = command.optString("daynight");
                if (!m.equals("auto") && !m.equals("day") && !m.equals("night")) return response(false);
                setDayNight(context, m);
                if (!command.has("skin") && !command.has("remoteSkin") && !hasColor && !hasStyle) return response(true);
            }
            if (command.has("remoteSkin")) {
                if (!setRemoteSkin(context, command.optString("remoteSkin"))) return response(false);
                if (!command.has("skin") && !hasColor && !hasStyle) return response(true);
            }
            if (command.has("skin")) {
                if (!setSkin(context, command.optString("skin"))) return response(false);
                if (!hasColor && !hasStyle) return response(true);
            }
            if (!hasColor && !hasStyle) return response(false);
            String color = null, style = null;
            if (hasColor) {
                Object value = command.opt("color");
                if (!(value instanceof String)) return response(false);
                color = (String) value;
                if (!color.matches("#[0-9a-fA-F]{6}")) return response(false);
            }
            if (hasStyle) {
                Object value = command.opt("style");
                if (!(value instanceof String)) return response(false);
                style = (String) value;
                if (!(style.equals("glass") || style.equals("metal") || style.equals("glow") || style.equals("plain")
                        || style.equals("flood") || style.equals("horror"))) return response(false);
            }
            SharedPreferences prefs = context.getSharedPreferences(DimService.PREFS, Context.MODE_PRIVATE);
            String savedColor = color == null ? prefs.getString("ray_theme_color", "#24dcff") : color;
            String savedStyle = style == null ? prefs.getString("ray_theme_style", "glass") : style;
            SharedPreferences.Editor editor = prefs.edit();
            if (color != null) editor.putString("ray_theme_color", color);
            if (style != null) editor.putString("ray_theme_style", style);
            editor.apply();
        } else if ("music.set".equals(type)) {
            if (!(command.opt("on") instanceof Boolean)) return response(false);
            setMusic(context, command.optBoolean("on"));
        } else if ("vpn.open".equals(type)) {
            return response(RayVpn.open(context, command.optString("package", "")));
        } else if ("weather.consent".equals(type)) {
            RayWeather.setConsent(context, command.optBoolean("accepted"));
            if (activity != null) activity.main.post(activity.weatherUpdate);
            RaySocket.stateChanged();
        } else if ("weather.set".equals(type)) {
            return response(applyWeather(context, command.optString("city", ""), command.optString("provider", "openmeteo"), command.optString("key", "")));
        } else if ("launcher.home".equals(type)) {
            if (activity != null) activity.requestHome();
            else context.startActivity(new Intent(context, RayLauncherActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } else if ("app.launch".equals(type)) {
            String pkg = command.optString("package", "");
            boolean installed = false;
            for (AppEntry app : cachedApps) if (app.packageName.equals(pkg)) installed = true;
            if (!installed || !launchPackage(context, pkg)) return response(false);
        } else if ("remote.key".equals(type)) {
            if (activity == null) return response(false);
            String key = command.optString("key", "").toUpperCase(Locale.ROOT);
            if (key.startsWith("DPAD_")) key = key.substring(5);
            int code = "UP".equals(key) ? KeyEvent.KEYCODE_DPAD_UP : "DOWN".equals(key) ? KeyEvent.KEYCODE_DPAD_DOWN
                    : "LEFT".equals(key) ? KeyEvent.KEYCODE_DPAD_LEFT : "RIGHT".equals(key) ? KeyEvent.KEYCODE_DPAD_RIGHT
                    : "BACK".equals(key) ? KeyEvent.KEYCODE_BACK : "HOME".equals(key) ? KeyEvent.KEYCODE_HOME
                    : "OK".equals(key) || "ENTER".equals(key) ? KeyEvent.KEYCODE_DPAD_CENTER
                    : "MENU".equals(key) ? KeyEvent.KEYCODE_MENU : 0;
            if (code == 0) return response(false);
            if (code == KeyEvent.KEYCODE_HOME) {
                activity.requestHome();
            } else if (code == KeyEvent.KEYCODE_MENU) {
                if (activity.showingDialog != null) activity.showingDialog.dismiss();
                activity.startActivity(new Intent(activity, MainActivity.class));
            } else if (activity.showingDialog != null && activity.showingDialog.isShowing()) {
                AlertDialog dialog = activity.showingDialog;
                if (code == KeyEvent.KEYCODE_BACK) dialog.cancel();
                else {
                    dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
                    dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
                }
            }
            else {
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
            }
        } else if ("remote.pointer".equals(type) || "remote.motion".equals(type)) {
            if (command.has("visible") && !command.optBoolean("visible")) {
                RayGlobalControl.hidePointer();
                if (activity != null) activity.pointer.hide();
                return response(true);
            }
            double x = command.optDouble("x", -1), y = command.optDouble("y", -1);
            boolean forceWake = "remote.pointer".equals(type);
            boolean overlay = RayGlobalControl.move(context, x, y, forceWake);
            if (activity != null) {
                if (!command.optBoolean("nofocus") && !activity.focusAt(x, y)) return response(false);
                if (overlay) activity.pointer.hide();
                else activity.pointer.setPosition((float) x, (float) y, forceWake);
            } else if (!overlay) return response(false);
        } else return response(false);
        return response(true);
    }

    private static JSONObject response(boolean ok) {
        JSONObject result = new JSONObject();
        try { result.put("ok", ok); } catch (JSONException ignored) { }
        return result;
    }

    private void showSettings() {
        startActivity(new Intent(this, MainActivity.class));
    }

    /** Id of the skin in use (an unknown saved id, e.g. the removed "classic", counts as the default). */
    static String current(Context context) {
        RaySkin s = RaySkin.get(context, context.getSharedPreferences(DimService.PREFS, Context.MODE_PRIVATE).getString("ray_skin", RaySkin.DEFAULT));
        return s == null ? RaySkin.DEFAULT : s.id;
    }

    /** The phone remote's skin: "match" follows the TV, otherwise a listed skin id. Phones get it on the next push. */
    static boolean setRemoteSkin(Context context, String id) {
        if (!"match".equals(id) && RaySkin.get(context, id) == null) return false;
        context.getSharedPreferences(DimService.PREFS, Context.MODE_PRIVATE).edit().putString("ray_remote_skin", id).apply();
        RaySocket.stateChanged();
        return true;
    }

    /** Saves the skin and rebuilds the home screen with its layout. */
    static boolean setSkin(Context context, String id) {
        if (RaySkin.get(context, id) == null) return false;
        context.getSharedPreferences(DimService.PREFS, Context.MODE_PRIVATE).edit().putString("ray_skin", id).apply();
        final RayLauncherActivity activity = active;
        if (activity != null) activity.main.post(new Runnable() {
            @Override public void run() { if (!activity.isFinishing() && !activity.isDestroyed()) activity.recreate(); }
        });
        return true;
    }

    private void showStyled(AlertDialog dialog) {
        dialog.show();
        styleDialog(dialog);
    }

    /** Translucent dark glass panel, accent focus highlight. */
    private void styleDialog(AlertDialog d) {
        android.view.Window win = d.getWindow();
        if (win == null) return;
        int accent = Color.WHITE; // the home's focus look: a white ring
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(248, 20, 22, 28));
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1), Color.argb(28, 255, 255, 255));
        win.setBackgroundDrawable(bg);
        final android.view.Window shown = win;
        win.getDecorView().post(new Runnable() { @Override public void run() { // a long list must not touch the screen edges
            int max = Math.round(getResources().getDisplayMetrics().heightPixels * .86f);
            if (shown.getDecorView().getHeight() > max) shown.setLayout(shown.getAttributes().width, max);
        } });
        View decor = win.getDecorView();
        Fonts.apply(decor);
        View title = decor.findViewById(getResources().getIdentifier("alertTitle", "id", "android"));
        if (title instanceof TextView) {
            ((TextView) title).setTextColor(TEXT);
            ((TextView) title).setTextSize(22);
            ((TextView) title).setTypeface(Fonts.bold(this));
        }
        View message = decor.findViewById(android.R.id.message);
        if (message instanceof TextView) ((TextView) message).setTextColor(TEXT);
        ListView list = d.getListView();
        if (list != null) list.setSelector(focusRing(accent, 26, 12));
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            Button b = d.getButton(which);
            if (b == null) continue;
            StateListDrawable states = new StateListDrawable();
            states.addState(new int[]{android.R.attr.state_focused}, focusRing(accent, 26, 24));
            states.addState(new int[]{android.R.attr.state_pressed}, focusRing(accent, 26, 24));
            states.addState(new int[0], new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            b.setBackground(states);
            b.setAllCaps(false);
            b.setTextColor(TEXT);
            b.setTypeface(Fonts.bold(this));
        }
    }

    private GradientDrawable focusRing(int accent, int alpha, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.argb(alpha, Color.red(accent), Color.green(accent), Color.blue(accent)));
        g.setStroke(dp(2), accent);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private void requestHome() {
        if (showingDialog != null) showingDialog.dismiss();
        if (!category.equals("home")) selectCategory("home", false);
        View target = focusedApp != null ? focusedApp : (appViews.isEmpty() ? getWindow().getDecorView() : appViews.get(0));
        target.requestFocus();
    }

    /** UI thread: focus whatever is under the air-mouse pointer (the render thread decides when). */
    static void focusUnderPointer() {
        RayLauncherActivity a = active;
        if (a != null) a.focusAt(RayGlobalControl.x(), RayGlobalControl.y());
    }

    private boolean focusAt(double x, double y) {
        if (Double.isNaN(x) || Double.isInfinite(x) || Double.isNaN(y) || Double.isInfinite(y)
                || x < 0 || x > 1 || y < 0 || y > 1) return false;
        int[] rootPos = new int[2];
        root.getLocationOnScreen(rootPos);
        float px = (float) x * root.getWidth();
        float py = (float) y * root.getHeight();
        int screenX = Math.round(rootPos[0] + px);
        int screenY = Math.round(rootPos[1] + py);
        View nearest = null;
        View underPointer = null;
        double best = Double.MAX_VALUE;
        double bestUnderPointer = Double.MAX_VALUE;
        Rect visible = new Rect();
        List<View> touchables = showingDialog != null && showingDialog.isShowing()
                && showingDialog.getWindow() != null
                ? showingDialog.getWindow().getDecorView().getTouchables() : root.getTouchables();
        for (View candidate : touchables) {
            if (!candidate.isShown() || !candidate.isFocusable() || !candidate.getGlobalVisibleRect(visible)) continue;
            int[] pos = new int[2];
            candidate.getLocationOnScreen(pos);
            double dx = pos[0] - rootPos[0] + candidate.getWidth() / 2.0 - px;
            double dy = pos[1] - rootPos[1] + candidate.getHeight() / 2.0 - py;
            double distance = dx * dx + dy * dy;
            if (distance < best) { best = distance; nearest = candidate; }
            if (visible.contains(screenX, screenY) && distance < bestUnderPointer) {
                bestUnderPointer = distance;
                underPointer = candidate;
            }
        }
        if (underPointer != null) nearest = underPointer;
        if (nearest != null) nearest.requestFocus();
        return nearest != null;
    }

    static boolean isHomeActive() { return active != null; }

    private String pairingUrl() {
        String ip = RemoteServer.localIp();
        return ip == null ? null : "http://" + ip + ":" + RemoteServer.PORT + "/ray#k=" + DimService.pairingKey(prefs);
    }

    private void showPairingDialog() { showPairingDialog(null); }

    /**
     * The pairing popup: a big QR and the 4-digit code, side by side. The code works only while this is open and is
     * renewed if the popup outlives it. Opened from here or by a phone that tapped this TV (DimService.pairListener).
     */
    private void showPairingDialog(String hint) {
        String url = pairingUrl();
        String code = DimService.PAIR.startLocal(System.currentTimeMillis());
        if (showingDialog != null) showingDialog.dismiss();
        LinearLayout content = new LinearLayout(this);
        content.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(24 * unit);
        content.setPadding(pad, pad, pad, pad / 2);
        if (url != null) {
            ImageView qr = new ImageView(this);
            qr.setBackgroundColor(Color.WHITE);
            int quiet = Math.round(10 * unit);
            qr.setPadding(quiet, quiet, quiet, quiet);
            qr.setImageBitmap(qrBitmap(url));
            content.addView(qr, new LinearLayout.LayoutParams(Math.round(240 * unit), Math.round(240 * unit)));
        }
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(url == null ? 0 : Math.round(32 * unit), 0, 0, 0);
        TextView message = stockText(url == null ? copy("Connect the TV to Wi-Fi to pair a phone", "Connetti la TV al Wi-Fi per collegare un telefono")
                : hint != null ? hint : copy("Scan with the phone, or open RAY on the phone, choose this TV and type the code",
                "Inquadra col telefono, oppure apri RAY sul telefono, scegli questa TV e scrivi il codice"), 19, Fonts.regular(this), TEXT);
        message.setMaxWidth(Math.round(360 * unit));
        side.addView(message);
        pairCodeView = stockText(url == null || code == null ? "" : code, 72, Fonts.display(this, 500), TEXT);
        pairCodeView.setLetterSpacing(.12f);
        side.addView(pairCodeView);
        content.addView(side);
        final AlertDialog dialog = new AlertDialog.Builder(this).setTitle(copy("Pair a phone", "Collega un telefono")).setView(content)
                .setPositiveButton(copy("Close", "Chiudi"), null).create();
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface dismissed) {
                if (showingDialog == dialog) showingDialog = null;
                if (pairDialog == dialog) {
                    pairDialog = null;
                    pairCodeView = null;
                    main.removeCallbacks(pairRenew);
                    DimService.PAIR.close();
                }
            }
        });
        showingDialog = dialog;
        pairDialog = dialog;
        main.removeCallbacks(pairRenew);
        main.postDelayed(pairRenew, PinGate.TTL + 200);
        showStyled(dialog);
    }

    private AlertDialog pairDialog;
    private TextView pairCodeView;
    private final Runnable pairRenew = new Runnable() {
        @Override public void run() {
            if (pairDialog == null) return;
            String code = DimService.PAIR.startLocal(System.currentTimeMillis());
            if (pairCodeView != null) pairCodeView.setText(code);
            main.postDelayed(this, PinGate.TTL + 200);
        }
    };
    /** A phone opened or finished the pairing window: follows it with the popup, shown over whatever is on the home. */
    private final Runnable pairWindow = new Runnable() {
        @Override public void run() {
            if (active != RayLauncherActivity.this || isFinishing() || isDestroyed()) return;
            String code = DimService.pairCode();
            if (code == null) { if (pairDialog != null) pairDialog.dismiss(); }
            else if (pairDialog == null) showPairingDialog(null);
            else if (pairCodeView != null) pairCodeView.setText(code);
        }
    };

    /** Notification access lets RAY see and control other apps' media sessions; usage access tells which app is on screen. */
    static void openMediaAccess(Context context) {
        for (String action : new String[]{"android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS",
                Settings.ACTION_USAGE_ACCESS_SETTINGS}) {
            try {
                context.startActivity(new Intent(action));
                return;
            } catch (RuntimeException unavailable) { }
        }
        android.widget.Toast.makeText(context, "adb shell cmd notification allow_listener "
                + context.getPackageName() + "/" + RayMediaListener.class.getName(), android.widget.Toast.LENGTH_LONG).show();
    }

    private void openPointerPermission() { openPointerPermission(this); }

    static void openPointerPermission(Context context) {
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                context.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + context.getPackageName())));
                return;
            } catch (RuntimeException ignored) { }
        }
        try {
            context.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (RuntimeException ignored) {
            try { context.startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            catch (RuntimeException unavailable) { }
        }
    }

    private Bitmap qrBitmap(String value) {
        if (RayDemo.on(this)) value = "https://example.com/ray"; // demo: the pairing code must not carry the real address
        QrCode code = QrCode.encodeText(value, QrCode.Ecc.MEDIUM);
        int border = 4, size = code.size + border * 2;
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++)
            pixels[y * size + x] = code.getModule(x - border, y - border) ? Color.BLACK : Color.WHITE;
        return Bitmap.createScaledBitmap(Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888), size * 5, size * 5, false);
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private String copy(String en, String it) { return I18n.t(en, it); }

    /** Weekday, day and month in the system language's own order, capitalised. */
    private static String longDate() {
        Locale l = Locale.getDefault();
        String d = new java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(l, "EEEEdMMMM"), l).format(new java.util.Date());
        return d.isEmpty() ? d : d.substring(0, 1).toUpperCase(l) + d.substring(1);
    }

    private String greeting() {
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return hour >= 5 && hour < 13 ? copy("Good morning", "Buongiorno") : hour >= 13 && hour < 18 ? copy("Good afternoon", "Buon pomeriggio")
                : hour >= 18 && hour < 23 ? copy("Good evening", "Buonasera") : copy("Good night", "Buonanotte");
    }

    @Override
    public void run() {
        long minute = System.currentTimeMillis() / 60000;
        if (minute != lastMinute) { // the clock only changes once a minute
            lastMinute = minute;
            clock.setText(android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date()));
            if (dateView != null) dateView.setText(longDate());
            if (heroItem == null && stockHero != null) updateStage(null, true);
            if (checkDayNight()) return; // the skin is rebuilt in its other variant
        }
        updateConnectPanel();
        main.postDelayed(this, 2000);
    }

    /** Paints the weather: a text line under the date. */
    private void updateWeather() {
        if (weatherView == null) return;
        boolean demo = RayDemo.on(this);
        JSONObject w = demo ? RayDemo.weather() : RayWeather.cached(this);
        String city = demo ? "Roma" : prefs.getString("ray_weather_city", "");
        weatherView.setText(w == null ? "" : RayWeather.glyph(w.optInt("code"), w.optBoolean("day", true)) + "︎ " + w.optInt("temp") + "°  " + city);
    }

    /** Sets provider, key and city (network: never on the UI thread). Empty key keeps the stored one for the same provider. */
    static boolean applyWeather(Context context, String city, String provider, String key) {
        city = city == null ? "" : city.trim();
        SharedPreferences p = context.getSharedPreferences(DimService.PREFS, Context.MODE_PRIVATE);
        if ((key == null || key.trim().isEmpty()) && provider.equals(p.getString("ray_weather_provider", "openmeteo")))
            key = p.getString("ray_weather_key", "");
        boolean ok = RayWeather.setProvider(context, provider, key) && RayWeather.setCity(context, city);
        RayLauncherActivity a = active;
        if (a != null) a.main.post(a.weatherUpdate);
        RaySocket.stateChanged();
        return ok;
    }

    private void registerPackageChanges() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        registerReceiver(packageChanges, filter);
        receiverRegistered = true;
    }

    private void unregisterPackageChanges() {
        if (receiverRegistered) unregisterReceiver(packageChanges);
        receiverRegistered = false;
    }

    private static final class PackageChanges extends BroadcastReceiver {
        private final RayLauncherActivity activity;
        PackageChanges(RayLauncherActivity activity) { this.activity = activity; }
        @Override public void onReceive(Context context, Intent intent) { activity.refreshApps(); }
    }

    private static final class AppEntry implements Comparable<AppEntry> {
        final String packageName;
        final String name;
        final Drawable icon;
        final Drawable banner;
        final int flags;
        final int category;
        AppEntry(String packageName, String name, Drawable icon, Drawable banner, int flags, int category) {
            this.category = category;
            this.packageName = packageName;
            this.name = name;
            this.icon = icon;
            this.banner = banner;
            this.flags = flags;
        }
        @Override public int compareTo(AppEntry other) { return name.compareToIgnoreCase(other.name); }
    }
}
