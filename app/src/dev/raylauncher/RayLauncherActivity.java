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
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
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
    private int CYAN;
    private int TEXT;
    private int MUTED;
    private static volatile RayLauncherActivity active;
    private static volatile List<AppEntry> cachedApps = Collections.emptyList();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService watchHistoryWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService appLoader = Executors.newSingleThreadExecutor();
    private int appsGeneration;
    private boolean appsLoaded;
    private int insetX, insetY;
    private final ArrayList<AppEntry> apps = new ArrayList<>();
    private final ArrayList<View> appViews = new ArrayList<>();
    private final ArrayList<View> surfaces = new ArrayList<>();
    private final ArrayList<TextView> labels = new ArrayList<>();
    private SharedPreferences prefs;
    private final ArrayList<TextView> rowHeadings = new ArrayList<>();
    private RaySkin skin;
    private TextView status;
    private TextView clock;
    private TextView deviceInfo;
    private View focusedApp;
    private Button resumeButton, discoverButton;
    private RayHero hero;
    /** Focusable views and their rectangle (fractions of the screen), for explicit D-pad routes. */
    private final HashMap<View, float[]> spots = new HashMap<>();
    private Object heroItem;
    private long lastMinute = -1;
    private final HashMap<String, Bitmap> blurCache = new HashMap<>();
    private int tileW, tileH, tileHoleX, tileHoleY;
    private static final PorterDuffColorFilter DIM = new PorterDuffColorFilter(Color.argb(70, 0, 0, 0), PorterDuff.Mode.SRC_ATOP);
    private Button modeButton;
    private FrameLayout root;
    private FrameLayout cardLayer;
    /** Frameless ("stock") skin: drawn by code; unit = one dp of the 1280x720 reference in pixels. */
    private boolean stock;
    private float unit = 1;
    private RayStockHero stockHero;
    private TextView dateView, connectSub, pillText, vpnBadge, weatherView;
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
    private LinearLayout qrRow, pill;
    private View pillDot;
    private final HashSet<View> freeHoriz = new HashSet<>();
    private final HashMap<String, Integer> tints = new HashMap<>();
    private final HashMap<String, Boolean> blanks = new HashMap<>();
    private boolean stockFocused;
    private long wpStamp = -1;
    private int wpGeneration;
    private final ExecutorService wallpaperWorker = Executors.newSingleThreadExecutor();
    private LinearLayout connectPanel;
    private ImageView connectQr;
    private TextView connectText;
    private String connectUrl;
    private boolean connectedShown;
    private int connectFullHeight;
    private RayFluidBackdrop backdrop;
    private AlertDialog showingDialog;
    private boolean dayMode;
    private RayGlobalControl.Pointer pointer;
    private View pointedView;
    private final ArrayList<Button> tabs = new ArrayList<>();
    private String category = "home";
    private String appSearch = "";
    private boolean receiverRegistered;
    private List<RayWatchHistory.Entry> watchHistory = Collections.emptyList();
    private int watchHistoryRequest;
    private final PackageChanges packageChanges = new PackageChanges(this);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
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
        applySavedTheme();
    }

    @Override
    protected void onResume() {
        super.onResume();
        active = this;
        applySavedTheme();
        if (backdrop != null) backdrop.start();
        if (wpStamp != RayWallpaper.stamp(this)) applyWallpaper();
        try { Compat.startForegroundService(this, new Intent(this, DimService.class).putExtra("remote", true)); }
        catch (RuntimeException ignored) { }
        registerPackageChanges();
        updateWeather();
        main.removeCallbacks(weatherTick);
        weatherTick.run();
        requestWatchHistoryPermission();
        refreshApps();
        run();
        RaySocket.stateChanged();
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

    private void requestWatchHistoryPermission() {
        if (Build.VERSION.SDK_INT < 23 || checkSelfPermission(READ_TV_LISTINGS) == PackageManager.PERMISSION_GRANTED
                || prefs.getBoolean("ray_watch_history_permission_asked", false)) return;
        prefs.edit().putBoolean("ray_watch_history_permission_asked", true).apply();
        requestPermissions(new String[]{READ_TV_LISTINGS}, REQUEST_WATCH_HISTORY_PERMISSION);
    }

    @Override
    protected void onPause() {
        if (active == this) active = null;
        if (backdrop != null) backdrop.stop();
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
        backdrop = new RayFluidBackdrop(this, dayMode);
        applySavedTheme();
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        stock = skin.stock;
        unit = height / 720f;
        if (stock) { buildStock(width, height); return; }

        TextView wordmark = label("RAY", 23, skin.raw ? skin.accent : TEXT);
        wordmark.setLetterSpacing(0.18f);
        wordmark.setGravity(Gravity.CENTER_VERTICAL);
        if (skin.raw) labels.remove(wordmark);
        addAt(root, wordmark, width, height, skin.logo);
        for (int i = 0; i < skin.nav.length; i++) {
            Button tab = button(navLabel(skin.nav[i]));
            tab.setTag("tab:" + skin.nav[i]);
            tab.setOnClickListener(this);
            tab.setOnFocusChangeListener(this);
            if (skin.navRects[i][3] > skin.navRects[i][2] / 3) tab.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
            tab.setPadding(dp(16), 0, dp(8), 0);
            addAt(root, tab, width, height, skin.navRects[i]);
            tabs.add(tab);
        }
        if (skin.device != null) {
            deviceInfo = label(Build.MODEL, 12, MUTED);
            deviceInfo.setSingleLine(true);
            deviceInfo.setGravity(Gravity.CENTER_VERTICAL);
            deviceInfo.setEllipsize(android.text.TextUtils.TruncateAt.END);
            addAt(root, deviceInfo, width, height, skin.device);
        }
        clock = label("", skin.raw ? 26 : 18, skin.raw ? skin.accent : TEXT);
        if (skin.raw) labels.remove(clock);
        clock.setGravity(Gravity.CENTER);
        addAt(root, clock, width, height, skin.clock);
        if (skin.weather != null) {
            weatherView = label("", 14, skin.weatherColor != 0 ? skin.weatherColor : skin.raw ? skin.accent : TEXT);
            if (skin.raw || skin.weatherColor != 0) labels.remove(weatherView);
            weatherView.setGravity(Gravity.CENTER);
            weatherView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            addAt(root, weatherView, width, height, skin.weather);
        }
        if (skin.mode != null) {
            modeButton = button(modeLabel());
            modeButton.setTag("mode");
            modeButton.setOnClickListener(this);
            modeButton.setOnFocusChangeListener(this);
            addAt(root, modeButton, width, height, skin.mode);
        }
        if (skin.pair != null) {
            Button pairButton = button("Abbina");
            pairButton.setTag("pair");
            pairButton.setOnClickListener(this);
            pairButton.setOnFocusChangeListener(this);
            addAt(root, pairButton, width, height, skin.pair);
        }

        resumeButton = button(copy("Riprendi", "Riprendi"));
        resumeButton.setTag("resume");
        resumeButton.setOnClickListener(this);
        resumeButton.setOnFocusChangeListener(this);
        addAt(root, resumeButton, width, height, skin.resume);
        discoverButton = button(copy("Scopri", "Scopri"));
        discoverButton.setTag("discover");
        discoverButton.setOnClickListener(this);
        discoverButton.setOnFocusChangeListener(this);
        addAt(root, discoverButton, width, height, skin.discover);

        for (float[] rect : skin.rowTitles) {
            TextView heading = sectionTitle("");
            heading.setGravity(Gravity.CENTER_VERTICAL);
            rowHeadings.add(heading);
            addAt(root, heading, width, height, rect);
        }
        cardLayer = new RaySceneLayer(this, backdrop);
        root.addView(cardLayer, new FrameLayout.LayoutParams(-1, -1));
        if (skin.hero != null) addHero(width, height);

        for (int i = 0; i < skin.actions.length; i++)
            addAction(width, height, skin.actions[i], skin.actionRects[i], skin.actionPad == null ? 0 : skin.actionPad[i]);

        connectPanel = new LinearLayout(this);
        connectPanel.setOrientation(LinearLayout.VERTICAL);
        connectPanel.setGravity(Gravity.CENTER);
        connectPanel.setPadding(dp(10), dp(10), dp(10), dp(10));
        connectPanel.setBackgroundColor(Color.argb(200, 0, 0, 0));
        connectQr = new ImageView(this);
        int qrSize = Math.round(Math.min(skin.connect[2] * width, skin.connect[3] * height * .72f));
        connectPanel.addView(connectQr, new LinearLayout.LayoutParams(qrSize, qrSize));
        connectText = label("", 12, TEXT);
        connectText.setMaxLines(2);
        connectText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        connectText.setGravity(Gravity.CENTER);
        connectText.setPadding(0, dp(6), 0, 0);
        connectPanel.addView(connectText, new LinearLayout.LayoutParams(-1, -2));
        addAt(root, connectPanel, width, height, skin.connect);
        connectPanel.setVisibility(View.GONE);

        status = label("", 14, MUTED);
        float[] firstCard = skin.rows[0][0];
        addAt(root, status, width, height, new float[]{firstCard[0], firstCard[1], .5f, firstCard[3]});
        status.setVisibility(View.GONE);
        pointer = new RayGlobalControl.Pointer(this);
        pointer.setVisibility(View.GONE);
        root.addView(pointer, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        Fonts.apply(root);
        for (TextView t : new TextView[]{clock, resumeButton, discoverButton}) t.setTypeface(Fonts.bold(this));
        for (TextView t : rowHeadings) t.setTypeface(Fonts.bold(this));
        if (skin.hero != null) {
            resumeButton.setTextSize(15);
            discoverButton.setTextSize(15);
            resumeButton.setBackground(pill(true, false));
            discoverButton.setBackground(pill(false, false));
            resumeButton.setTextColor(Color.rgb(20, 14, 6));
            for (Button b : new Button[]{resumeButton, discoverButton}) { // small slots (e.g. 0.05 of the height): fit the text instead of clipping it
                b.setMinHeight(0); b.setMinimumHeight(0); b.setMinWidth(0); b.setMinimumWidth(0);
                b.setPadding(dp(6), 0, dp(6), 0);
                b.setSingleLine(true);
                b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Math.min(15 * getResources().getDisplayMetrics().scaledDensity, skin.resume[3] * height * .45f));
            }
        }
        updateDeviceInfo();
        selectCategory("home", false);
        if (!tabs.isEmpty()) tabs.get(0).requestFocus(); else resumeButton.requestFocus();
        applyWallpaper();
    }
    /** The hero lives in the scene layer like the cards: it bleeds under the frame and the skin trims it. */
    private void addHero(int width, int height) {
        float[] r = skin.hero;
        float gx = r[2] * CARD_BLEED, gy = r[3] * CARD_BLEED;
        int vw = Math.round(width * (r[2] + 2 * gx)), vh = Math.round(height * (r[3] + 2 * gy));
        float hx = width * gx, hy = height * gy, unit = height / 720f;
        float ix = width * r[2] * skin.heroInset[0], iy = height * r[3] * skin.heroInset[1];
        float[] hole = {hx + ix, hy + iy, hx + width * r[2] - ix, hy + height * r[3] - iy};
        float textBottom = (skin.resume[1] - (r[1] - gy)) * height - 12 * unit;
        hero = new RayHero(this, vw, vh, hole, textBottom, unit, accent());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(vw, vh);
        lp.leftMargin = Math.round(width * (r[0] - gx));
        lp.topMargin = Math.round(height * (r[1] - gy));
        cardLayer.addView(hero, lp);
    }

    private int accent() {
        return skin.raw ? skin.accent : Color.parseColor(prefs.getString("ray_theme_color", "#24dcff"));
    }

    private Drawable pill(boolean primary, boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(40));
        if (primary) {
            g.setColor(accent());
            if (focused) g.setStroke(dp(2), Color.WHITE);
        } else {
            g.setColor(Color.argb(focused ? 160 : 110, 10, 16, 26));
            g.setStroke(dp(focused ? 2 : 1), focused ? accent() : Color.argb(110, 255, 255, 255));
        }
        return g;
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
                        -dx > .5f ? -dx + 3 * Math.abs(dy) : -1, dx > .5f ? dx + 3 * Math.abs(dy) : -1};
                for (int d = 0; d < 4; d++) if (cost[d] >= 0 && cost[d] < score[d]) { score[d] = cost[d]; best[d] = o; }
            }
            v.setNextFocusUpId(best[0] == null ? v.getId() : best[0].getId());
            v.setNextFocusDownId(best[1] == null ? v.getId() : best[1].getId());
            if (freeHoriz.contains(v)) continue; // scrolling rows: the framework moves along them
            v.setNextFocusLeftId(best[2] == null ? v.getId() : best[2].getId());
            v.setNextFocusRightId(best[3] == null ? v.getId() : best[3].getId());
        }
    }
    private String navLabel(String key) {
        switch (key) {
            case "home": return "Home";
            case "video": return "Video";
            case "games": return copy("Games", "Giochi");
            case "music": return copy("Music", "Musica");
            case "apps": return "App";
            default: return copy("Results", "Risultati");
        }
    }
    private void addAction(int width, int height, String tag, float[] rect, float padLeft) {
        String title = "search".equals(tag) ? copy("Search", "Cerca") : "apps".equals(tag) ? copy("Your apps", "Le tue app")
                : "sources".equals(tag) ? copy("Sources", "Sorgenti") : "audio".equals(tag) ? "Audio"
                : "pair".equals(tag) ? "Abbina" : copy("Settings", "Impostazioni");
        Button action = button(title);
        action.setTag(tag);
        action.setOnClickListener(this);
        action.setOnFocusChangeListener(this);
        action.setTextSize(14);
        android.graphics.drawable.Drawable themed = skin.nav.length == 0 ? RayIcons.get(this, skin, tag) : null;
        float bw = width * rect[2], bh = height * rect[3];
        if (themed != null && bw < bh * 2.2f) {
            // small round hole: the themed badge alone, the name goes to accessibility
            int size = Math.round(Math.min(bw, bh) * .78f);
            themed.setBounds(0, 0, size, size);
            action.setText("");
            action.setContentDescription(title);
            action.setCompoundDrawables(null, themed, null, null);
            action.setGravity(Gravity.CENTER);
            action.setPadding(0, Math.round((bh - size) / 2), 0, 0);
        } else {
            if (themed != null) {
                int size = Math.round(bh * .72f);
                themed.setBounds(0, 0, size, size);
                action.setCompoundDrawables(themed, null, null, null);
            } else {
                int icon = getResources().getIdentifier("ray_" + tag, "drawable", getPackageName());
                if (icon != 0) action.setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0);
            }
            action.setCompoundDrawablePadding(dp(8));
            action.setPadding(padLeft > 0 ? Math.round(width * padLeft) : dp(themed != null ? 8 : 16), 0, dp(12), 0);
        }
        action.setBackgroundColor(Color.TRANSPARENT);
        addAt(root, action, width, height, rect);
    }
    private TextView sectionTitle(String text) {
        TextView title = label(text, 17, TEXT);
        title.setLetterSpacing(0.04f);
        return title;
    }

    /** Package scan and icon loading run off the UI thread; a newer call drops the older result. */
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
        updateDeviceInfo();
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

    private void updateRows() {
        if (stock) { updateStockRows(); return; }
        View oldFocus = getCurrentFocus();
        int oldCardIndex = -1;
        for (int i = 0; i < appViews.size() && oldCardIndex < 0; i++) {
            View ancestor = oldFocus;
            while (ancestor != null) {
                if (ancestor == appViews.get(i)) { oldCardIndex = i; break; }
                android.view.ViewParent parent = ancestor.getParent();
                ancestor = parent instanceof View ? (View) parent : null;
            }
        }
        for (Button tab : tabs) {
            String key = ((String) tab.getTag()).substring(4);
            boolean any = key.equals("home") || key.equals("apps") || key.equals(category);
            for (AppEntry app : apps) if (!any && categoryMatches(app, key)) any = true;
            tab.setVisibility(any ? View.VISIBLE : View.GONE);
        }
        // visible tabs take the first slots, so hidden categories leave no empty pills
        int slot = 0;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        for (Button tab : tabs) {
            if (tab.getVisibility() != View.VISIBLE) continue;
            float[] r = skin.navRects[slot++];
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tab.getLayoutParams();
            lp.leftMargin = Math.round(dm.widthPixels * r[0]);
            lp.topMargin = Math.round(dm.heightPixels * r[1]);
            lp.width = Math.round(dm.widthPixels * r[2]);
            lp.height = Math.round(dm.heightPixels * r[3]);
            tab.setLayoutParams(lp);
            spots.put(tab, r);
        }
        for (View tile : appViews) { removeTileReferences(tile); cardLayer.removeView(tile); spots.remove(tile); }
        appViews.clear();
        int firstRow = skin.rows[0].length;
        ArrayList<Object> visible = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int historyCount = 0;
        if (category.equals("home")) {
            for (RayWatchHistory.Entry item : watchHistory) {
                visible.add(item);
                if (visible.size() == firstRow) break;
            }
            historyCount = visible.size();
            // no hole stays empty: the first row is completed with the most recent apps, then the others
            for (AppEntry app : apps) {
                if (visible.size() >= firstRow) break;
                if (seen.add(app.packageName)) visible.add(app);
            }
            for (AppEntry app : apps) if (seen.add(app.packageName)) visible.add(app);
        } else {
            for (AppEntry app : apps) if (categoryMatches(app, category)) visible.add(app);
        }
        int real = visible.size();
        int cardIndex = 0;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        for (float[][] row : skin.rows) for (float[] rect : row) {
            // beyond the real items: suggested actions, never an empty hole
            Object item = cardIndex < real ? visible.get(cardIndex) : (Object) Integer.valueOf(cardIndex - real);
            cardIndex++;
            // cards reach under the frame on every side: the skin trims their edges
            float gx = rect[2] * CARD_BLEED, gy = rect[3] * CARD_BLEED;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(screenWidth * (rect[2] + 2 * gx)),
                    Math.round(screenHeight * (rect[3] + 2 * gy)));
            // captions stay clear of the feathered edge so the text is fully opaque
            tileW = lp.width;
            tileH = lp.height;
            tileHoleX = Math.round(lp.width * CARD_BLEED / (1 + 2 * CARD_BLEED));
            tileHoleY = Math.round(lp.height * CARD_BLEED / (1 + 2 * CARD_BLEED));
            insetX = Math.round(lp.width * RaySceneLayer.FEATHER) + dp(3);
            insetY = Math.round(lp.height * RaySceneLayer.FEATHER) + dp(3);
            View tile = item instanceof RayWatchHistory.Entry ? watchTile((RayWatchHistory.Entry) item)
                    : item instanceof AppEntry ? appTile((AppEntry) item) : placeholderTile((Integer) item, real == 0);
            lp.leftMargin = Math.round(screenWidth * (rect[0] - gx));
            lp.topMargin = Math.round(screenHeight * (rect[1] - gy));
            cardLayer.addView(tile, lp);
            appViews.add(tile);
            spot(tile, rect);
        }
        wireFocus();
        String title = category.equals("home") ? (historyCount == firstRow ? copy("Continue watching", "Continua a guardare")
                : historyCount > 0 ? copy("Continue", "Continua")
                : !prefs.getString("ray_recent", "").isEmpty() ? copy("Recent apps", "App recenti") : copy("Your apps", "Le tue app"))
                : category.equals("search") ? copy("Results", "Risultati") : navLabel(category);
        if (!rowHeadings.isEmpty()) rowHeadings.get(0).setText(title);
        if (rowHeadings.size() > 1) rowHeadings.get(1).setText(category.equals("home")
                ? (real > firstRow ? copy("For you", "Per te") : copy("Suggested", "Suggeriti")) : "");
        status.setVisibility(View.GONE);
        View first = appViews.isEmpty() ? null : appViews.get(0);
        focusedApp = null;
        if (first != null && oldFocus == null) first.requestFocus();
        else if (first != null && oldCardIndex >= 0)
            appViews.get(Math.min(oldCardIndex, appViews.size() - 1)).requestFocus();
        if (getCurrentFocus() == null) { // the D-pad must always have a target
            View fallback = first;
            for (Button tab : tabs) if (fallback == null && ("tab:" + category).equals(tab.getTag())) fallback = tab;
            if (fallback == null && !tabs.isEmpty()) fallback = tabs.get(0);
            if (fallback != null) fallback.requestFocus();
        }
        View focused = getCurrentFocus();
        updateHero(focused != null && appViews.contains(focused) ? tileItem(focused) : null, true);
    }

    private Object tileItem(View tile) {
        Object tag = tile.getTag();
        return tag instanceof String ? findApp((String) tag) : tag;
    }

    private static String remaining(int minutes) {
        return minutes >= 60 ? (minutes / 60) + "h " + String.format(Locale.ROOT, "%02d", minutes % 60) + "min" : minutes + "min";
    }

    /** What the hero shows: the focused card; with nothing focused, the latest Watch Next entry or a greeting. */
    private void updateHero(Object item, boolean force) {
        if (item == null && !watchHistory.isEmpty() && category.equals("home")) item = watchHistory.get(0);
        if (!force && item == heroItem && item != null) return;
        heroItem = item;
        String primary = "\u25b6  Riprendi";
        Object tag;
        if (item instanceof RayWatchHistory.Entry) {
            RayWatchHistory.Entry e = (RayWatchHistory.Entry) item;
            tag = e;
            AppEntry owner = findApp(e.packageName);
            String meta = owner == null ? "" : owner.name;
            if (e.minutesLeft > 0) meta += (meta.isEmpty() ? "" : "  |  ") + remaining(e.minutesLeft) + " rimanenti";
            int count = Math.min(watchHistory.size(), skin.rows[0].length);
            if (hero != null) hero.show(e.artwork, meta, e.title, e.description, count, Math.max(0, watchHistory.indexOf(e)));
            if (stockHero != null) stockHero.show(e.artwork, (owner == null ? "" : owner.name + "  \u00b7  ") + copy("Continue watching", "Continua a guardare"),
                    e.title, e.minutesLeft > 0 ? remaining(e.minutesLeft) + " rimanenti" : "", e.progress);
        } else if (item instanceof AppEntry) {
            AppEntry a = (AppEntry) item;
            tag = a.packageName;
            primary = "\u25b6  Apri";
            if (hero != null) hero.show(blurred(a), "App", a.name, "", 0, 0);
            if (stockHero != null) stockHero.show(null, "App", a.name, "", -1);
        } else {
            AppEntry recent = findApp(prefs.getString("ray_recent", "").split("\\n", 2)[0]);
            tag = recent == null ? "resume" : recent.packageName;
            if (recent == null) primary = "\u25b6  Le tue app";
            resumeButton.setContentDescription(recent == null ? "Le tue app" : "Riprendi " + recent.name);
            if (stockHero != null) {
                int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
                stockHero.show(null, hour >= 5 && hour < 13 ? "Buongiorno" : hour >= 13 && hour < 18 ? "Buon pomeriggio"
                        : hour >= 18 && hour < 23 ? "Buonasera" : "Buonanotte", copy("What shall we watch?", "Cosa guardiamo?"), "", -1);
            }
            if (hero != null) {
                int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
                String hello = hour >= 5 && hour < 13 ? "Buongiorno" : hour >= 13 && hour < 18 ? "Buon pomeriggio"
                        : hour >= 18 && hour < 23 ? "Buonasera" : "Buonanotte";
                String date = new java.text.SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(new java.util.Date());
                if (!date.isEmpty()) date = Character.toUpperCase(date.charAt(0)) + date.substring(1);
                hero.showGreeting(hello, date, android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date()));
            }
        }
        resumeButton.setText(primary);
        resumeButton.setTag(tag);
        int secondary = item == null && "resume".equals(tag) ? View.GONE : View.VISIBLE; // would duplicate "Le tue app"
        if (discoverButton.getVisibility() != secondary) {
            discoverButton.setVisibility(secondary);
            wireFocus();
        }
        if (item != null) resumeButton.setContentDescription(primary.substring(3) + " " + (item instanceof AppEntry
                ? ((AppEntry) item).name : ((RayWatchHistory.Entry) item).title));
    }

    /** Cheap blur: the banner redrawn at 64x36 and stretched back up with filtering. Cached per package. */
    private Bitmap blurred(AppEntry app) {
        Bitmap cached = blurCache.get(app.packageName);
        if (cached != null) return cached;
        Drawable d = app.banner != null ? app.banner : app.icon;
        if (d == null) return null;
        Bitmap small = Bitmap.createBitmap(64, 36, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(small);
        c.drawColor(Color.rgb(12, 18, 28));
        int dw = d.getIntrinsicWidth(), dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) { dw = 64; dh = 36; }
        float scale = Math.max(64f / dw, 36f / dh);
        int bw = Math.round(dw * scale), bh = Math.round(dh * scale);
        d.setBounds((64 - bw) / 2, (36 - bh) / 2, (64 + bw) / 2, (36 + bh) / 2);
        d.draw(c);
        blurCache.put(app.packageName, small);
        return small;
    }

    /** One home card: artwork, bottom scrim, title + caption, optional thin progress bar. Focus look is drawn by the card. */
    private CardView card(Object tag, Bitmap bitmap, Drawable drawable, boolean icon, String title, String sub, float progress,
                          boolean showCaption) {
        CardView tile = new CardView(this, accent(), dp(7), dp(12), tileHoleX, tileHoleY);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setBackgroundColor(Color.TRANSPARENT);
        tile.setTag(tag);
        tile.setOnClickListener(this);
        tile.setOnFocusChangeListener(this);
        ImageView artwork = new ImageView(this);
        if (bitmap != null) artwork.setImageBitmap(bitmap);
        else if (drawable != null) artwork.setImageDrawable(drawable);
        artwork.setScaleType(icon ? ImageView.ScaleType.CENTER_INSIDE : ImageView.ScaleType.CENTER_CROP);
        if (bitmap == null && (icon || drawable == null)) artwork.setBackground(new RayGlassDrawable(dayMode, false, false));
        if (icon) artwork.setPadding(dp(24), dp(10), dp(24), captionHeight() / 2 + dp(8));
        tile.art = artwork;
        tile.setContentDescription(title);
        tile.addView(artwork, new FrameLayout.LayoutParams(-1, -1));
        if (!showCaption) { // the artwork already carries the name
            tile.setFocusLook(false);
            return tile;
        }
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.TRANSPARENT, Color.argb(235, 2, 6, 12)}));
        tile.addView(scrim, new FrameLayout.LayoutParams(-1, Math.round(tileH * .6f), Gravity.BOTTOM));
        LinearLayout caption = new LinearLayout(this);
        caption.setOrientation(LinearLayout.VERTICAL);
        caption.setGravity(Gravity.BOTTOM);
        TextView name = label(title, 14, Color.WHITE);
        name.setTypeface(Fonts.bold(this));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setShadowLayer(4, 0, 1, Color.BLACK);
        caption.addView(name, new LinearLayout.LayoutParams(-1, -2));
        if (sub != null && !sub.isEmpty()) {
            TextView line = label(sub, 11, Color.argb(215, 255, 255, 255));
            line.setTypeface(Fonts.regular(this));
            line.setSingleLine(true);
            line.setEllipsize(android.text.TextUtils.TruncateAt.END);
            caption.addView(line, new LinearLayout.LayoutParams(-1, -2));
        }
        FrameLayout.LayoutParams capLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        int bar = progress >= 0 ? dp(9) : dp(2);
        capLp.setMargins(insetX, 0, insetX, insetY + bar);
        tile.addView(caption, capLp);
        tile.progress = progress;
        tile.barX = insetX;
        tile.barY = insetY;
        tile.setFocusLook(false);
        return tile;
    }

    /** Card with its own focus look: dim when idle, accent glow ring around the hole when focused. */
    private static final class CardView extends FrameLayout {
        ImageView art;
        float progress = -1;
        int barX, barY;
        private final int accent, ring, radius, hx, hy;
        private boolean glow;
        private RaySkin stockSkin;
        private float u;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();

        CardView(Context c, int accent, int ring, int radius, int hx, int hy) {
            super(c);
            this.accent = accent;
            this.ring = ring;
            this.radius = radius;
            this.hx = hx;
            this.hy = hy;
            setWillNotDraw(false);
        }

        /** Frameless look: rounded clip, real shadow when focused, dimmed otherwise, ring and progress drawn inside. */
        void stockInit(RaySkin s, float unit) {
            stockSkin = s;
            u = unit;
            final float r = s.cardRadius * unit;
            setClipToOutline(true);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
            });
        }

        void setFocusLook(boolean focused) {
            glow = focused;
            if (stockSkin != null) {
                setElevation(focused ? 18 * u : 0);
                setAlpha(focused ? 1f : stockSkin.dim);
                invalidate();
                return;
            }
            if (art != null) art.setColorFilter(focused ? null : DIM);
            invalidate();
        }

        @Override protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            if (stockSkin != null) {
                if (progress >= 0) {
                    float bar = 3 * u, w = getWidth(), h = getHeight();
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(Color.argb(64, 255, 255, 255));
                    canvas.drawRect(0, h - bar, w, h, paint);
                    paint.setColor(Color.WHITE);
                    canvas.drawRect(0, h - bar, w * Math.min(1f, progress), h, paint);
                }
                if (glow) {
                    float rw = stockSkin.ringDp * u, r = stockSkin.cardRadius * u;
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(rw);
                    paint.setColor(stockSkin.ring);
                    box.set(rw / 2, rw / 2, getWidth() - rw / 2, getHeight() - rw / 2);
                    canvas.drawRoundRect(box, r - rw / 2, r - rw / 2, paint);
                    if (stockSkin.light) { // dark ring vanishes on dark covers: thin light ring inside it
                        paint.setStrokeWidth(u);
                        paint.setColor(Color.argb(153, 255, 255, 255));
                        box.set(rw + u / 2, rw + u / 2, getWidth() - rw - u / 2, getHeight() - rw - u / 2);
                        canvas.drawRoundRect(box, r - rw, r - rw, paint);
                    }
                }
                return;
            }
            if (progress >= 0) {
                float y = getHeight() - barY - ring * .6f, x0 = barX, x1 = getWidth() - barX;
                box.set(x0, y - ring * .22f, x1, y + ring * .22f);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.argb(90, 255, 255, 255));
                canvas.drawRoundRect(box, ring, ring, paint);
                box.right = x0 + (x1 - x0) * Math.min(1f, progress);
                paint.setColor(accent);
                canvas.drawRoundRect(box, ring, ring, paint);
            }
            if (glow) {
                paint.setStyle(Paint.Style.STROKE);
                for (int i = 0; i < 3; i++) {
                    paint.setStrokeWidth(ring * (2.4f - i * .8f));
                    paint.setColor(Color.argb(i == 2 ? 255 : i == 1 ? 110 : 45, Color.red(accent), Color.green(accent), Color.blue(accent)));
                    box.set(hx + ring, hy + ring, getWidth() - hx - ring, getHeight() - hy - ring);
                    canvas.drawRoundRect(box, radius, radius, paint);
                }
            }
        }
    }

    private View watchTile(RayWatchHistory.Entry item) {
        AppEntry owner = findApp(item.packageName);
        String sub = item.minutesLeft > 0 ? remaining(item.minutesLeft) + " rimanenti" : owner == null ? "" : owner.name;
        return card(item, item.artwork, null, false, item.title, sub, item.progress, true);
    }

    private void removeTileReferences(View view) {
        surfaces.remove(view);
        if (view instanceof TextView) labels.remove(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) removeTileReferences(group.getChildAt(i));
        }
    }

    /** Suggested action in a hole with no content: first "all apps", then search, pairing, settings, add apps. */
    private View placeholderTile(int n, boolean noApps) {
        String[] tags = {"ph:apps", "ph:search", "ph:pair", "ph:settings"};
        String[] titles = {"Tutte le app", "Cerca", "Abbina il telefono", "Impostazioni"};
        CardView tile = card(n < 4 ? tags[n] : "ph:add", null, null, false, n < 4 ? titles[n] : "Aggiungi app",
                n == 0 && noApps ? "Nessuna app in questa categoria" : "", -1, true);
        int accent = accent();
        tile.art.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.argb(120, Color.red(accent), Color.green(accent), Color.blue(accent)), Color.argb(235, 6, 12, 22)}));
        TextView plus = label("+", 34, accent);
        plus.setTypeface(Fonts.bold(this));
        plus.setGravity(Gravity.CENTER);
        tile.addView(plus, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        return tile;
    }

    private View appTile(AppEntry app) {
        boolean banner = app.banner != null && !blank(app); // a missing or near-uniform banner: icon + name
        return card(app.packageName, null, banner ? app.banner : app.icon, !banner, app.name, "", -1, !banner);
    }

    // ---- frameless ("stock") skins: layout and look in docs/SKIN_FORMAT.md ----

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

    private Button stockButton(String tag) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setTransformationMethod(null);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 15 * unit);
        b.setIncludeFontPadding(false);
        b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(0); b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setGravity(Gravity.CENTER);
        b.setPadding(Math.round(28 * unit), 0, Math.round(28 * unit), 0);
        b.setTag(tag);
        b.setFocusable(true);
        b.setOnClickListener(this);
        b.setOnFocusChangeListener(this);
        tintShadow(b);
        return b;
    }

    /** Primary: filled, with a shadow. Secondary: translucent, takes the primary look when focused; focus adds a ring. */
    private void styleStockButton(Button b, boolean focused) {
        boolean primary = b == resumeButton || focused;
        Drawable fill = new InsetDrawable(roundRect(primary ? skin.btnBg : skin.btn2Bg, 24), Math.round(4 * unit));
        if (focused) {
            GradientDrawable ring = roundRect(Color.TRANSPARENT, 24);
            ring.setStroke(Math.round(skin.ringDp * unit), skin.ring);
            b.setBackground(new LayerDrawable(new Drawable[]{ring, fill}));
        } else b.setBackground(fill);
        b.setPadding(Math.round(28 * unit), 0, Math.round(28 * unit), 0); // setBackground took the drawable's padding
        b.setTextColor(primary ? skin.btnFg : skin.btn2Fg);
        b.setElevation((focused ? 12 : b == resumeButton ? 6 : 0) * unit);
    }

    private void buildStock(int width, int height) {
        Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL), reg = Typeface.create("sans-serif", Typeface.NORMAL),
                med = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        stockHero = new RayStockHero(this, skin, width, height);
        root.addView(stockHero, new FrameLayout.LayoutParams(-1, -1));
        cardLayer = new FrameLayout(this);
        cardLayer.setClipChildren(false);
        root.addView(cardLayer, new FrameLayout.LayoutParams(-1, -1));
        clock = stockText("", 44, light, skin.textColor);
        clock.setLetterSpacing(-.0114f);
        addAt(root, clock, width, height, skin.clock);
        dateView = stockText("", 15, reg, skin.mutedColor);
        addAt(root, dateView, width, height, new float[]{.05f, .126f, .24f, .04f});
        weatherView = stockText("", 15, reg, skin.mutedColor);
        addAt(root, weatherView, width, height, new float[]{.05f, .163f, .3f, .04f});
        for (float[] r : skin.rowTitles) {
            TextView heading = stockText("", 17, med, skin.textColor);
            heading.setGravity(Gravity.CENTER_VERTICAL);
            rowHeadings.add(heading);
            addAt(root, heading, width, height, r);
        }
        for (int i = 0; i < skin.actions.length; i++) {
            String tag = skin.actions[i];
            StockAction a = new StockAction(this, tag, RayIcons.get(this, skin, tag), skin, unit);
            a.setFocusable(true);
            a.setClickable(true);
            a.setTag(tag);
            a.setOnClickListener(this);
            a.setOnFocusChangeListener(this);
            a.setContentDescription("search".equals(tag) ? copy("Search", "Cerca") : "sources".equals(tag) ? copy("Sources", "Sorgenti") : copy("Settings", "Impostazioni"));
            tintShadow(a);
            addAt(root, a, width, height, skin.actionRects[i]);
        }
        resumeButton = stockButton("resume");
        discoverButton = stockButton("discover");
        discoverButton.setText(copy("Discover", "Scopri"));
        styleStockButton(resumeButton, false);
        styleStockButton(discoverButton, false);
        LinearLayout buttons = new LinearLayout(this);
        buttons.addView(resumeButton, new LinearLayout.LayoutParams(-2, Math.round(48 * unit)));
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(-2, Math.round(48 * unit));
        second.leftMargin = Math.round(4 * unit);
        buttons.addView(discoverButton, second);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, -2);
        bl.leftMargin = Math.round(width * .05f - 4 * unit);
        bl.topMargin = Math.round(height * .415f - 4 * unit);
        root.addView(buttons, bl);
        spot(resumeButton, skin.resume);
        spot(discoverButton, skin.discover);

        // connect: the QR box, or a pill once a phone (or the VPN) is there
        connectPanel = new LinearLayout(this);
        connectPanel.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        qrRow = new LinearLayout(this);
        qrRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        connectText = stockText(copy("Pair your phone", "Collega il telefono"), 13, med, skin.textColor);
        connectText.setGravity(Gravity.END);
        connectSub = stockText(copy("Scan to use it as a remote", "Inquadra per usarlo come telecomando"), 12, reg, skin.mutedColor);
        connectSub.setGravity(Gravity.END);
        connectSub.setMaxLines(2);
        connectSub.setMaxWidth(Math.round(170 * unit));
        col.addView(connectText, new LinearLayout.LayoutParams(-1, -2));
        col.addView(connectSub, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(-2, -2);
        colLp.rightMargin = Math.round(12 * unit);
        qrRow.addView(col, colLp);
        connectQr = new ImageView(this);
        int qr = Math.round(72 * unit);
        connectQr.setBackground(roundRect(Color.WHITE, 10));
        connectQr.setPadding(Math.round(6 * unit), Math.round(6 * unit), Math.round(6 * unit), Math.round(6 * unit));
        connectQr.setElevation(8 * unit);
        tintShadow(connectQr);
        qrRow.addView(connectQr, new LinearLayout.LayoutParams(qr, qr));
        connectPanel.addView(qrRow, new LinearLayout.LayoutParams(-2, -2));
        pill = new LinearLayout(this);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(roundRect(skin.panelFill, 20));
        pill.setPadding(Math.round(16 * unit), 0, Math.round(8 * unit), 0);
        pillDot = new View(this);
        pillDot.setBackground(roundRect(skin.ok, 4));
        pill.addView(pillDot, new LinearLayout.LayoutParams(Math.round(8 * unit), Math.round(8 * unit)));
        pillText = stockText(copy("Phone connected", "Telefono collegato"), 14, reg, skin.textColor);
        LinearLayout.LayoutParams pt = new LinearLayout.LayoutParams(-2, -2);
        pt.leftMargin = Math.round(8 * unit);
        pill.addView(pillText, pt);
        vpnBadge = stockText("VPN", 11, Typeface.create("sans-serif-medium", Typeface.BOLD), skin.mutedColor);
        vpnBadge.setLetterSpacing(.07f);
        vpnBadge.setBackground(roundRect(skin.panelStrong, 12));
        vpnBadge.setPadding(Math.round(9 * unit), Math.round(4 * unit), Math.round(9 * unit), Math.round(4 * unit));
        LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(-2, -2);
        vl.leftMargin = Math.round(10 * unit);
        pill.addView(vpnBadge, vl);
        connectPanel.addView(pill, new LinearLayout.LayoutParams(-2, Math.round(40 * unit)));
        addAt(root, connectPanel, width, height, skin.connect);
        connectPanel.setVisibility(View.GONE);

        pointer = new RayGlobalControl.Pointer(this);
        pointer.setVisibility(View.GONE);
        root.addView(pointer, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        selectCategory("home", false);
        if (!appViews.isEmpty()) appViews.get(0).requestFocus(); else resumeButton.requestFocus();
        applyWallpaper();
    }

    private void updateStockConnect() {
        boolean phone = RaySocket.clientCount() > 0, vpn = RayVpn.state(this).optBoolean("active");
        String url = phone ? null : pairingUrl();
        String signature = phone + "|" + vpn + "|" + url;
        if (signature.equals(connectUrl)) { connectPanel.setVisibility(View.VISIBLE); return; }
        connectUrl = signature;
        boolean asPill = phone; // the VPN badge rides on the phone pill; alone it must not hide the QR
        qrRow.setVisibility(asPill ? View.GONE : View.VISIBLE);
        pill.setVisibility(asPill ? View.VISIBLE : View.GONE);
        pillDot.setVisibility(phone ? View.VISIBLE : View.GONE);
        pillText.setVisibility(phone ? View.VISIBLE : View.GONE);
        vpnBadge.setVisibility(vpn ? View.VISIBLE : View.GONE);
        ((LinearLayout.LayoutParams) vpnBadge.getLayoutParams()).leftMargin = phone ? Math.round(10 * unit) : 0;
        if (!asPill) {
            connectQr.setVisibility(url == null ? View.GONE : View.VISIBLE);
            if (url != null) connectQr.setImageBitmap(qrBitmap(url));
            connectText.setText(url == null ? copy("Connect the TV to Wi-Fi", "Connetti la TV al Wi-Fi") : copy("Pair your phone", "Collega il telefono"));
            connectSub.setVisibility(url == null ? View.GONE : View.VISIBLE);
        }
        connectPanel.setVisibility(View.VISIBLE);
    }

    /** Watch Next row (only when there is something to continue) above the app row; both scroll sideways. */
    private void updateStockRows() {
        View oldFocus = getCurrentFocus();
        int oldIndex = appViews.indexOf(oldFocus);
        Object oldTag = oldIndex >= 0 ? oldFocus.getTag() : null;
        for (View tile : appViews) { removeTileReferences(tile); spots.remove(tile); freeHoriz.remove(tile); }
        appViews.clear();
        cardLayer.removeAllViews();
        ArrayList<Object> watch = new ArrayList<>(), items = new ArrayList<>();
        if (category.equals("home")) for (RayWatchHistory.Entry e : watchHistory) if (watch.size() < 10) watch.add(e);
        for (AppEntry app : apps) if (categoryMatches(app, category)) items.add(app);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = dm.widthPixels, h = dm.heightPixels, gap = Math.round(.0125f * w), pad = Math.round(.05f * w), padV = Math.round(36 * unit);
        boolean top = !watch.isEmpty();
        if (top) addStockRow(watch, .595f, 5, gap, pad, padV, w, h);
        addStockRow(items, top ? .86f : .62f, top ? 8 : 6, gap, pad, padV, w, h);
        TextView first = rowHeadings.get(0), second = rowHeadings.get(1);
        first.setVisibility(top ? View.VISIBLE : View.GONE);
        first.setText(copy("Continue watching", "Continua a guardare"));
        second.setText(category.equals("search") ? copy("Results", "Risultati") : "App");
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) second.getLayoutParams();
        lp.topMargin = Math.round((top ? .81f : .57f) * h);
        second.setLayoutParams(lp);
        View target = null;
        if (oldTag != null) for (View t : appViews) if (oldTag.equals(t.getTag())) target = t;
        if (target == null && (oldFocus == null || oldIndex >= 0) && !appViews.isEmpty()) target = appViews.get(0);
        if (oldIndex == 0 && !appViews.isEmpty()) target = appViews.get(0); // the first card stays the first when Watch Next arrives
        if (!stockFocused && !appViews.isEmpty()) { target = appViews.get(0); stockFocused = true; }
        if (target != null) target.requestFocus();
        if (getCurrentFocus() == null) { if (!appViews.isEmpty()) appViews.get(0).requestFocus(); else resumeButton.requestFocus(); }
        wireFocus();
        View focused = getCurrentFocus();
        updateHero(focused != null && appViews.contains(focused) ? tileItem(focused) : null, true);
    }

    private void addStockRow(List<Object> list, float topFrac, int per, int gap, int pad, int padV, int w, int h) {
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
            Object item = list.get(i);
            CardView tile;
            if (item instanceof RayWatchHistory.Entry) {
                RayWatchHistory.Entry e = (RayWatchHistory.Entry) item;
                AppEntry owner = findApp(e.packageName);
                tile = stockCard(e, e.artwork, null, 0, e.title, e.minutesLeft > 0 ? remaining(e.minutesLeft) + " rimanenti" : owner == null ? "" : owner.name, e.progress, ch, true);
            } else {
                AppEntry a = (AppEntry) item;
                tile = a.banner != null && !blank(a) ? stockCard(a.packageName, null, a.banner, tint(a), a.name, "", -1, ch, false)
                        : stockCard(a.packageName, null, null, tint(a), a.name, "", -1, ch, false);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cw, ch);
            lp.rightMargin = i < list.size() - 1 ? gap : 0;
            row.addView(tile, lp);
            appViews.add(tile);
            freeHoriz.add(tile);
            if (i < per) spot(tile, new float[]{(pad + i * (float) (cw + gap)) / w, topFrac, (float) cw / w, (float) ch / h});
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, ch + 2 * padV);
        lp.topMargin = Math.round(topFrac * h) - padV;
        cardLayer.addView(scroller, lp);
    }

    /** Rounded card: artwork (or the app colour), scrim + name for Watch Next, centered name for apps without a banner. */
    private CardView stockCard(Object tag, Bitmap bitmap, Drawable drawable, int tint, String title, String sub, float progress, int ch, boolean watch) {
        CardView tile = new CardView(this, skin.ring, 0, 0, 0, 0);
        tile.stockInit(skin, unit);
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
        tile.art = art;
        tile.addView(art, new FrameLayout.LayoutParams(-1, -1));
        Typeface med = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        if (watch) {
            View scrim = new View(this);
            scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{Color.TRANSPARENT, Color.argb(209, 6, 8, 16)}));
            tile.addView(scrim, new FrameLayout.LayoutParams(-1, Math.round(ch * .62f), Gravity.BOTTOM));
            LinearLayout caption = new LinearLayout(this);
            caption.setOrientation(LinearLayout.VERTICAL);
            TextView name = stockText(title, 13, med, Color.WHITE);
            TextView line = stockText(sub, 11, Typeface.create("sans-serif", Typeface.NORMAL), Color.argb(184, 255, 255, 255));
            for (TextView t : new TextView[]{name, line}) { t.setSingleLine(true); t.setEllipsize(android.text.TextUtils.TruncateAt.END); }
            caption.addView(name, new LinearLayout.LayoutParams(-1, -2));
            if (!sub.isEmpty()) caption.addView(line, new LinearLayout.LayoutParams(-1, -2));
            FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            cl.leftMargin = cl.rightMargin = Math.round(12 * unit);
            cl.bottomMargin = Math.round((progress >= 0 ? 13 : 10) * unit);
            tile.addView(caption, cl);
            tile.progress = progress;
        } else if (image) {
            View veil = new View(this);
            veil.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{Color.argb(41, 255, 255, 255), Color.TRANSPARENT}));
            tile.addView(veil, new FrameLayout.LayoutParams(-1, Math.round(ch * .55f), Gravity.TOP));
        } else {
            TextView name = stockText(title, 15, Typeface.create("sans-serif-medium", Typeface.BOLD), Color.WHITE);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setPadding(Math.round(8 * unit), 0, Math.round(8 * unit), 0);
            AppEntry owner = tag instanceof String ? findApp((String) tag) : null;
            if (owner != null && owner.icon != null) { // no usable banner: the app icon, then its name
                LinearLayout stack = new LinearLayout(this);
                stack.setOrientation(LinearLayout.VERTICAL);
                stack.setGravity(Gravity.CENTER_HORIZONTAL);
                ImageView icon = new ImageView(this);
                icon.setImageDrawable(owner.icon);
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

    /** Rebuilds the wallpaper-based scene off the UI thread: the frameless scene, or the landscape of framed skins. */
    private void applyWallpaper() {
        if (backdrop == null || wallpaperWorker.isShutdown()) return;
        wpStamp = RayWallpaper.stamp(this);
        final boolean framedUser = !stock && "user".equals(skin.wallpaper);
        if (!stock && !framedUser) return;
        final int generation = ++wpGeneration;
        final RaySkin forSkin = skin;
        try {
            wallpaperWorker.execute(new Runnable() {
                @Override public void run() {
                    final Bitmap scene = stock ? RayWallpaper.stockScene(RayLauncherActivity.this, forSkin) : null;
                    final Bitmap background = framedUser ? RayWallpaper.cover(RayLauncherActivity.this, RayWallpaper.W, RayWallpaper.H, Bitmap.Config.RGB_565) : null;
                    main.post(new Runnable() {
                        @Override public void run() {
                            boolean live = generation == wpGeneration && !isFinishing() && !isDestroyed();
                            if (!live) { if (scene != null) scene.recycle(); if (background != null) background.recycle(); return; }
                            if (scene != null) backdrop.setScene(scene);
                            else if (framedUser && cardLayer instanceof RaySceneLayer) ((RaySceneLayer) cardLayer).setBackground(background);
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
            setElevation(focused ? 10 * u : 0);
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            boolean f = isFocused();
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
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(ink);
            if ("search".equals(kind)) {
                canvas.drawCircle(cx - 1.5f * u, cy - 1.5f * u, 6.5f * u, paint);
                canvas.drawLine(cx + 3.2f * u, cy + 3.2f * u, cx + 8.5f * u, cy + 8.5f * u, paint);
            } else if ("sources".equals(kind)) { // arrow into a door
                android.graphics.Path p = new android.graphics.Path();
                p.moveTo(cx + 2 * u, cy - 8.5f * u); p.lineTo(cx + 8.5f * u, cy - 8.5f * u);
                p.lineTo(cx + 8.5f * u, cy + 8.5f * u); p.lineTo(cx + 2 * u, cy + 8.5f * u);
                p.moveTo(cx - 9.5f * u, cy); p.lineTo(cx + 4 * u, cy);
                p.moveTo(cx - 1 * u, cy - 5 * u); p.lineTo(cx + 4 * u, cy); p.lineTo(cx - 1 * u, cy + 5 * u);
                canvas.drawPath(p, paint);
            } else { // sun / settings
                canvas.drawCircle(cx, cy, 3.6f * u, paint);
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4;
                    float c = (float) Math.cos(a), s = (float) Math.sin(a);
                    canvas.drawLine(cx + c * 6.6f * u, cy + s * 6.6f * u, cx + c * 9.4f * u, cy + s * 9.4f * u, paint);
                }
            }
        }
    }

    private static final float CARD_BLEED = .10f; // each side: cards are 1.2x their hole in the skin

    private int captionHeight() { return dp(40); }

    private AppEntry findApp(String packageName) {
        for (AppEntry app : apps) if (app.packageName.equals(packageName)) return app;
        return null;
    }

    private boolean categoryMatches(AppEntry app, String selected) {
        String key = (app.name + " " + app.packageName).toLowerCase(Locale.ROOT);
        if (selected.equals("search")) return key.contains(appSearch);
        if (selected.equals("home") || selected.equals("apps")) return true;
        if (selected.equals("video")) return app.category == ApplicationInfo.CATEGORY_VIDEO;
        if (selected.equals("games")) return app.category == ApplicationInfo.CATEGORY_GAME
                || (app.flags & ApplicationInfo.FLAG_IS_GAME) != 0;
        if (selected.equals("music")) return app.category == ApplicationInfo.CATEGORY_AUDIO;
        return false;
    }
    private boolean containsAny(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }

    private void setPalette(boolean day) {
        BG = day ? Color.rgb(0, 67, 82) : Color.rgb(3, 14, 31);
        CYAN = day ? Color.rgb(76, 242, 255) : Color.rgb(31, 205, 248);
        TEXT = Color.rgb(247, 253, 255);
        MUTED = day ? Color.rgb(190, 237, 243) : Color.rgb(151, 192, 211);
    }

    /** "Auto" / "Giorno" / "Notte" for the mode button. */
    private String modeLabel() {
        String m = RayDayNight.mode(this);
        return "auto".equals(m) ? "Auto" : "day".equals(m) ? "Giorno" : "Notte";
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

    private void applyDayMode(boolean day) {
        dayMode = day;
        setPalette(day);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        root.setBackgroundColor(BG);
        if (backdrop != null) backdrop.setDayMode(day);
        if (modeButton != null) modeButton.setText(modeLabel());
        for (TextView label : labels) label.setTextColor(label == deviceInfo || label == status ? MUTED : TEXT);
        for (View surface : surfaces) {
            Object tag = surface.getTag();
            if (skin.hero != null && (surface == resumeButton || surface == discoverButton)) {
                surface.setBackground(pill(surface == resumeButton, surface.hasFocus()));
            } else if (tag instanceof String && ((String) tag).startsWith("tab:")) {
                boolean selected = ("tab:" + category).equals(tag);
                surface.setBackground(surface.hasFocus() ? focusedPanel() : selected ? selectedTab() : transparentBackground());
                ((TextView) surface).setTextColor(selected ? TEXT : MUTED);
            } else surface.setBackground(surface.hasFocus() ? focusedPanel() : transparentBackground());
        }
    }

    private void selectCategory(String value, boolean focusFirst) {
        category = value;
        for (Button tab : tabs) {
            boolean selected = ("tab:" + category).equals(tab.getTag());
            tab.setTextColor(selected ? TEXT : MUTED);
            tab.setBackground(selected ? selectedTab() : transparentBackground());
        }
        updateRows();
        if (focusFirst && !appViews.isEmpty()) appViews.get(0).requestFocus();
    }

    private void updateDeviceInfo() {
        if (deviceInfo != null) deviceInfo.setText(Build.MODEL);
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
        if ("mode".equals(view.getTag())) {
            setDayNight(this, RayDayNight.next(RayDayNight.mode(this)));
        } else if ("pair".equals(view.getTag())) {
            showPairingDialog();
        } else if ("search".equals(view.getTag())) {
            showSearch();
        } else if ("apps".equals(view.getTag())) {
            selectCategory("apps", true);
            showAppCatalog("");
        } else if ("sources".equals(view.getTag())) {
            Intent intent = new Intent("android.media.tv.action.SETUP_INPUTS");
            if (intent.resolveActivity(getPackageManager()) == null) intent = new Intent(Settings.ACTION_SETTINGS);
            startActivity(intent);
        } else if ("settings".equals(view.getTag())) {
            showSettings();
        } else if ("audio".equals(view.getTag())) {
            openSoundSettings();
        } else if ("discover".equals(view.getTag())) {
            selectCategory("apps", true);
            showAppCatalog("");
        } else if ("resume".equals(view.getTag())) {
            selectCategory("apps", true);
            showAppCatalog("");
        } else if (view.getTag() instanceof String && ((String) view.getTag()).startsWith("tab:")) {
            String selected = ((String) view.getTag()).substring(4);
            selectCategory(selected, true);
            if ("apps".equals(selected)) showAppCatalog("");
        } else if (view.getTag() instanceof RayWatchHistory.Entry) {
            RayWatchHistory.Entry item = (RayWatchHistory.Entry) view.getTag();
            if (!RayWatchHistory.launch(this, item)) launch(item.packageName);
        } else if (view.getTag() instanceof String && ((String) view.getTag()).startsWith("ph:")) {
            placeholderAction(((String) view.getTag()).substring(3));
        } else if (view.getTag() instanceof String) {
            launch((String) view.getTag());
        }
    }

    private void openSoundSettings() {
        try { startActivity(new Intent(Settings.ACTION_SOUND_SETTINGS)); }
        catch (RuntimeException unavailable) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (RuntimeException ignored) { }
        }
    }

    private void placeholderAction(String what) {
        if ("apps".equals(what)) { selectCategory("apps", true); showAppCatalog(""); }
        else if ("search".equals(what)) showSearch();
        else if ("pair".equals(what)) showPairingDialog();
        else if ("settings".equals(what)) showSettings();
        else try { startActivity(new Intent(Settings.ACTION_APPLICATION_SETTINGS)); }
        catch (RuntimeException unavailable) { openSoundSettings(); }
    }

    private void showSearch() {
        final EditText query = new EditText(this);
        query.setSingleLine(true);
        query.setHint("Cerca app installate");
        query.setTextColor(TEXT);
        query.setHintTextColor(MUTED);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Cerca")
                .setView(query).setNegativeButton("Annulla", null).create();
        dialog.setButton(AlertDialog.BUTTON_POSITIVE, "Apri", new android.content.DialogInterface.OnClickListener() {
            @Override public void onClick(android.content.DialogInterface dialog, int which) {
                String value = query.getText().toString().trim().toLowerCase(Locale.ROOT);
                appSearch = value;
                selectCategory("search", false);
                showAppCatalog(value);
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

    private void showAppCatalog(String query) {
        final String filter = query == null ? "" : query.toLowerCase(Locale.ROOT);
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(filter.isEmpty() ? "Le tue app" : "Risultati")
                .setView(scroll).setNegativeButton("Chiudi", null).create();
        showingDialog = dialog;
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface dismissed) {
                if (showingDialog == dialog) showingDialog = null;
            }
        });
        for (final AppEntry app : apps) {
            if (!filter.isEmpty() && !app.name.toLowerCase(Locale.ROOT).contains(filter)
                    && !app.packageName.toLowerCase(Locale.ROOT).contains(filter)) continue;
            Button item = new Button(this);
            item.setText(app.name);
            item.setTextColor(TEXT);
            item.setAllCaps(false);
            item.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
            item.setCompoundDrawablePadding(dp(14));
            if (app.icon != null) item.setCompoundDrawablesWithIntrinsicBounds(app.icon, null, null, null);
            item.setBackgroundColor(Color.TRANSPARENT);
            item.setFocusable(true);
            item.setOnFocusChangeListener(this);
            item.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    launch(app.packageName);
                    dialog.dismiss();
                }
            });
            list.addView(item, new LinearLayout.LayoutParams(-1, dp(54)));
        }
        if (list.getChildCount() == 0) {
            TextView empty = label("Nessuna app trovata", 15, MUTED);
            empty.setPadding(dp(20), dp(16), dp(20), dp(16));
            list.addView(empty);
        }
        showStyled(dialog);
    }

    @Override
    public void onFocusChange(View view, boolean hasFocus) {
        if (view.getTag() instanceof String && ((String) view.getTag()).startsWith("tab:")) {
            if (hasFocus) view.setBackground(focusedPanel());
            else view.setBackground(("tab:" + category).equals(view.getTag()) ? selectedTab() : transparentBackground());
            return;
        }
        if (stock && (view instanceof StockAction || view == resumeButton || view == discoverButton)) {
            if (view instanceof StockAction) {
                ((StockAction) view).focusLook(hasFocus);
                view.animate().scaleX(hasFocus ? 1.1f : 1f).scaleY(hasFocus ? 1.1f : 1f).setDuration(160)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            } else styleStockButton((Button) view, hasFocus);
            return;
        }
        if (view instanceof CardView) ((CardView) view).setFocusLook(hasFocus);
        else if (skin.hero != null && (view == resumeButton || view == discoverButton))
            view.setBackground(pill(view == resumeButton, hasFocus));
        else view.setBackground(hasFocus ? focusedPanel() : transparentBackground());
        view.animate().scaleX(hasFocus ? (stock ? skin.focusScale : 1.07f) : 1f).scaleY(hasFocus ? (stock ? skin.focusScale : 1.07f) : 1f).setDuration(160)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        if (hasFocus && appViews.contains(view)) {
            focusedApp = view;
            updateHero(tileItem(view), false);
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
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_HOME) return true;
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) return true;
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
            if (activity != null && activity.backdrop != null) {
                activity.applySavedTheme();
                activity.applyDayMode(activity.dayMode);
            }
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

    private void applySavedTheme() {
        if (backdrop == null || prefs == null || skin == null) return;
        int color = skin.raw ? skin.accent : Color.parseColor(prefs.getString("ray_theme_color", "#24dcff"));
        int style = skin.stock ? 7 : skin.raw ? 6 : themeStyleIndex(prefs.getString("ray_theme_style", "glass"));
        RayGlassDrawable.configure(this, color, style);
        backdrop.setTheme(color, style, skin.shell);
    }

    private void showSettings() {
        final boolean it = "it".equals(Locale.getDefault().getLanguage());
        final List<RaySkin> skins = RaySkin.list(this);
        final String[] skinNames = new String[skins.size()];
        for (int i = 0; i < skinNames.length; i++) skinNames[i] = skins.get(i).title(it);
        String remote = prefs.getString("ray_remote_skin", "match");
        RaySkin remoteSkin = "match".equals(remote) ? null : RaySkin.get(this, remote);
        String[] items = {copy("Skin", "Skin") + ": " + skin.title(it),
                copy("Remote skin", "Skin telecomando") + ": " + (remoteSkin == null ? copy("same as the TV", "come la TV") : remoteSkin.title(it)),
                copy("Day/night", "Giorno/notte") + ": " + ("auto".equals(RayDayNight.mode(this)) ? copy("Automatic", "Automatico") : dayMode ? copy("Day", "Giorno") : copy("Night", "Notte")),
                copy("Wallpaper", "Sfondo") + ": " + (RayWallpaper.failed ? copy("not available, using the default", "non disponibile, uso quello predefinito")
                        : RayWallpaper.file(this).exists() ? copy("custom", "personalizzato") : copy("default", "predefinito")),
                copy("Upload wallpaper from the phone", "Carica sfondo dal telefono"), copy("Restore default wallpaper", "Ripristina sfondo predefinito"),
                copy("Pair a phone", "Abbina il telefono"), copy("Pointer over apps", "Puntatore sopra le app"),
                copy("Screen and audio controls", "Controlli schermo e audio"),
                copy("App companion on the phone (media access)", "Companion app sul telefono (accesso media)"),
                copy("Weather", "Meteo") + ": " + (!RayWeather.consent(this) ? copy("off", "spento") : prefs.getString("ray_weather_city", "").isEmpty() ? copy("not set", "non impostato") : prefs.getString("ray_weather_city", "")),
                "VPN: " + (RayVpn.state(this).optBoolean("active") ? copy("on", "attiva") : copy("off", "non attiva")),
                copy("Android settings", "Impostazioni Android")};
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(copy("Settings", "Impostazioni"))
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) chooseSkin(skins, skinNames);
                        else if (which == 1) chooseRemoteSkin(skins, skinNames);
                        else if (which == 2) { setDayNight(RayLauncherActivity.this, RayDayNight.next(RayDayNight.mode(RayLauncherActivity.this))); if (!isFinishing()) showSettings(); }
                        else if (which == 3) showSettings();
                        else if (which == 4) showPairingDialog(copy("On the phone: Settings > TV wallpaper > choose a picture", "Dal telefono: Impostazioni > Sfondo TV > scegli un\u2019immagine"));
                        else if (which == 5) {
                            RayWallpaper.reset(RayLauncherActivity.this);
                            applyWallpaper();
                            android.widget.Toast.makeText(RayLauncherActivity.this, copy("Default wallpaper restored", "Sfondo predefinito ripristinato"), android.widget.Toast.LENGTH_SHORT).show();
                        }
                        else if (which == 6) showPairingDialog();
                        else if (which == 7) openPointerPermission();
                        else if (which == 8) startActivity(new Intent(RayLauncherActivity.this, MainActivity.class));
                        else if (which == 9) openMediaAccess();
                        else if (which == 10) { if (RayWeather.consent(RayLauncherActivity.this)) showWeather(); else askWeatherConsent(); }
                        else if (which == 11) showVpn();
                        else try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (RuntimeException ignored) { }
                    }
                }).setNegativeButton(copy("Close", "Chiudi"), null).create();
        trackDialog(dialog);
        showStyled(dialog);
    }

    private void chooseSkin(final List<RaySkin> skins, String[] names) {
        int checked = 0;
        for (int i = 0; i < skins.size(); i++) if (skins.get(i).id.equals(skin.id)) checked = i;
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Skin")
                .setSingleChoiceItems(names, checked,
                        new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        d.dismiss();
                        setSkin(RayLauncherActivity.this, skins.get(which).id);
                    }
                }).create();
        trackDialog(dialog);
        showStyled(dialog);
    }

    /** Id of the skin in use (an unknown saved id, e.g. the removed "classic", counts as the default). */
    private static String current(Context context) {
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

    private void chooseRemoteSkin(List<RaySkin> skins, String[] names) {
        final String[] ids = new String[skins.size() + 1];
        final String[] labels = new String[ids.length];
        ids[0] = "match";
        labels[0] = copy("Same as the TV", "Come la TV");
        for (int i = 0; i < skins.size(); i++) ids[i + 1] = skins.get(i).id;
        System.arraycopy(names, 0, labels, 1, names.length);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(copy("Remote skin", "Skin telecomando"))
                .setSingleChoiceItems(labels, java.util.Arrays.asList(ids).indexOf(prefs.getString("ray_remote_skin", "match")),
                        new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        d.dismiss();
                        setRemoteSkin(RayLauncherActivity.this, ids[which]);
                    }
                }).create();
        trackDialog(dialog);
        showStyled(dialog);
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
        int accent = accent();
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(238, 8, 16, 30));
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), Color.argb(130, Color.red(accent), Color.green(accent), Color.blue(accent)));
        win.setBackgroundDrawable(bg);
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
        if (list != null) list.setSelector(focusRing(accent, 70, 10));
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            Button b = d.getButton(which);
            if (b == null) continue;
            StateListDrawable states = new StateListDrawable();
            states.addState(new int[]{android.R.attr.state_focused}, focusRing(accent, 90, 24));
            states.addState(new int[]{android.R.attr.state_pressed}, focusRing(accent, 90, 24));
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

    private void trackDialog(final AlertDialog dialog) {
        showingDialog = dialog;
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface dismissed) {
                if (showingDialog == dialog) showingDialog = null;
            }
        });
    }
    private static int themeStyleIndex(String style) {
        return "metal".equals(style) ? 1 : "glow".equals(style) ? 2 : "plain".equals(style) ? 3
                : "flood".equals(style) ? 4 : "horror".equals(style) ? 5 : 0;
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

    private void showPairingDialog(String hint) {
        String url = pairingUrl();
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(dp(20), dp(12), dp(20), dp(8));
        final ArrayList<TextView> dialogLabels = new ArrayList<>();
        TextView message = label(url == null ? "Connetti la TV al Wi-Fi per abbinarla" : hint != null ? hint : "Scansiona il codice QR con il telefono", 16, TEXT);
        dialogLabels.add(message);
        message.setGravity(Gravity.CENTER);
        content.addView(message, new LinearLayout.LayoutParams(-1, -2));
        if (url != null) {
            ImageView qr = new ImageView(this);
            qr.setImageBitmap(qrBitmap(url));
            LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(dp(192), dp(192));
            qrLp.topMargin = dp(12);
            content.addView(qr, qrLp);
            TextView address = label(url, 11, MUTED);
            dialogLabels.add(address);
            address.setGravity(Gravity.CENTER);
            address.setPadding(0, dp(8), 0, 0);
            content.addView(address, new LinearLayout.LayoutParams(-1, -2));
        }
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Abbina il telefono").setView(content)
                .setPositiveButton("Chiudi", null)
                .setNeutralButton("Puntatore sopra le app", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        openPointerPermission();
                    }
                }).create();
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface dismissed) {
                labels.removeAll(dialogLabels);
                if (showingDialog == dialog) showingDialog = null;
            }
        });
        showingDialog = dialog;
        showStyled(dialog);
    }

    /** Notification access lets RAY see and control other apps' media sessions; usage access tells which app is on screen. */
    private void openMediaAccess() {
        for (String action : new String[]{"android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS",
                Settings.ACTION_USAGE_ACCESS_SETTINGS}) {
            try {
                startActivity(new Intent(action));
                return;
            } catch (RuntimeException unavailable) { }
        }
        android.widget.Toast.makeText(this, "adb shell cmd notification allow_listener "
                + getPackageName() + "/" + RayMediaListener.class.getName(), android.widget.Toast.LENGTH_LONG).show();
    }

    private void openPointerPermission() {
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName())));
                return;
            } catch (RuntimeException ignored) { }
        }
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (RuntimeException ignored) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
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

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(TEXT);
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setFocusable(true);
        button.setBackgroundColor(Color.TRANSPARENT);
        surfaces.add(button);
        return button;
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        labels.add(view);
        return view;
    }

    private Drawable focusedPanel() {
        return new RayGlassDrawable(dayMode, true, false);
    }

    /** Selected, not focused: an accent bar at the left edge, distinct from the focus ring. */
    private Drawable selectedTab() {
        final int color = accent(), width = dp(4), margin = dp(6);
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF bar = new RectF();
            @Override public void draw(Canvas canvas) {
                Rect b = getBounds();
                float half = b.height() * .24f;
                bar.set(b.left + margin, b.centerY() - half, b.left + margin + width, b.centerY() + half);
                paint.setColor(color);
                canvas.drawRoundRect(bar, width, width, paint);
            }
            @Override public void setAlpha(int alpha) { }
            @Override public void setColorFilter(android.graphics.ColorFilter filter) { }
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
    }

    private Drawable transparentBackground() {
        return new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private String copy(String en, String it) { return "it".equals(Locale.getDefault().getLanguage()) ? it : en; }

    @Override
    public void run() {
        long minute = System.currentTimeMillis() / 60000;
        if (minute != lastMinute) { // the clock only changes once a minute
            lastMinute = minute;
            clock.setText(android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date()));
            if (dateView != null) dateView.setText(new java.text.SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(new java.util.Date()));
            if (heroItem == null && (hero != null || stockHero != null)) updateHero(null, true);
            if (checkDayNight()) return; // the skin is rebuilt in its other variant
        }
        updateConnectPanel();
        main.postDelayed(this, 2000);
    }

    /** Paints the weather: a text line under the date (stock skins) or the big temperature inside the skin's weather window. */
    private void updateWeather() {
        if (weatherView == null) return;
        boolean demo = RayDemo.on(this);
        JSONObject w = demo ? RayDemo.weather() : RayWeather.cached(this);
        String city = demo ? "Roma" : prefs.getString("ray_weather_city", "");
        if (stock) {
            weatherView.setText(w == null ? "" : RayWeather.glyph(w.optInt("code"), w.optBoolean("day", true)) + " " + w.optInt("temp") + "\u00b0 \u00b7 " + city);
            return;
        }
        float[] r = skin.weather;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float hw = r[2] * dm.widthPixels, hh = r[3] * dm.heightPixels;
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        if (city.isEmpty()) {
            sb.append(RayWeather.consent(this) ? copy("Weather: set the city", "Meteo: imposta la citt\u00e0") : copy("Weather: off", "Meteo: spento"));
            sb.setSpan(new android.text.style.AbsoluteSizeSpan(Math.round(Math.min(hh * .1f, hw * .1f)), false), 0, sb.length(), 0);
        } else {
            int big = Math.round(Math.min(hh * .36f, hw * .34f)), mid = Math.round(Math.min(hh * .11f, hw * .1f));
            String temp = w == null ? "\u2026" : w.optInt("temp") + "\u00b0";
            sb.append(temp).append('\n');
            sb.setSpan(new android.text.style.AbsoluteSizeSpan(big, false), 0, temp.length(), 0);
            sb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), 0, temp.length(), 0);
            int a = sb.length();
            if (w != null) sb.append(RayWeather.glyph(w.optInt("code"), w.optBoolean("day", true))).append(' ')
                    .append(RayWeather.label(w.optInt("code"))).append('\n');
            sb.setSpan(new android.text.style.AbsoluteSizeSpan(mid, false), a, sb.length(), 0);
            a = sb.length();
            sb.append(city);
            sb.setSpan(new android.text.style.AbsoluteSizeSpan(Math.round(mid * .85f), false), a, sb.length(), 0);
        }
        weatherView.setMaxLines(4);
        weatherView.setText(sb);
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

    private void showWeather() {
        final String[] names = {"Open-Meteo (" + copy("no key", "senza chiave") + ")", "OpenWeatherMap"};
        final int[] sel = {Math.max(0, java.util.Arrays.asList(RayWeather.PROVIDERS).indexOf(prefs.getString("ray_weather_provider", "openmeteo")))};
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        final EditText city = new EditText(this);
        city.setSingleLine(true);
        city.setHint(copy("City", "Citt\u00e0"));
        city.setText(prefs.getString("ray_weather_city", ""));
        final Button prov = new Button(this);
        prov.setAllCaps(false);
        final EditText key = new EditText(this);
        key.setSingleLine(true);
        key.setHint(copy("API key (empty keeps the saved one)", "Chiave API (vuota = tieni quella salvata)"));
        key.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final Runnable sync = new Runnable() { @Override public void run() { prov.setText(names[sel[0]]); key.setVisibility(sel[0] == 0 ? View.GONE : View.VISIBLE); } };
        sync.run();
        prov.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sel[0] = (sel[0] + 1) % names.length; sync.run(); }
        });
        box.addView(city, new LinearLayout.LayoutParams(-1, -2));
        box.addView(prov, new LinearLayout.LayoutParams(-1, -2));
        box.addView(key, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(copy("Weather", "Meteo")).setView(box)
                .setPositiveButton(copy("Save", "Salva"), new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        final String c = city.getText().toString(), k = key.getText().toString(), pv = RayWeather.PROVIDERS[sel[0]];
                        try { weatherWorker.execute(new Runnable() { @Override public void run() {
                            final boolean ok = applyWeather(RayLauncherActivity.this, c, pv, k);
                            main.post(new Runnable() { @Override public void run() {
                                if (!ok) android.widget.Toast.makeText(RayLauncherActivity.this,
                                        copy("Unknown city or invalid key", "Citt\u00e0 sconosciuta o chiave non valida"), android.widget.Toast.LENGTH_LONG).show();
                            } });
                        } }); } catch (RuntimeException ignored) { }
                    }
                }).setNeutralButton(copy("Turn off", "Disattiva"), new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        RayWeather.setConsent(RayLauncherActivity.this, false);
                        updateWeather();
                        RaySocket.stateChanged();
                    }
                }).setNegativeButton(copy("Close", "Chiudi"), null).create();
        trackDialog(dialog);
        showStyled(dialog);
    }

    /** The weather stays off until this is accepted: no request leaves the TV before. */
    private void askWeatherConsent() {
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(copy("Weather", "Meteo")).setMessage(RayWeather.CONSENT)
                .setPositiveButton("Accetta", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        RayWeather.setConsent(RayLauncherActivity.this, true);
                        RaySocket.stateChanged();
                        showWeather();
                    }
                }).setNegativeButton("Annulla", null).create();
        trackDialog(dialog);
        showStyled(dialog);
    }

    /** VPN: status, the installed VPN apps (last used first), and the system VPN settings. */
    private void showVpn() {
        JSONObject v = RayVpn.state(this);
        JSONArray apps = v.optJSONArray("apps");
        final int n = apps == null ? 0 : apps.length();
        final String[] pkgs = new String[n + 1], items = new String[n + 1];
        String preferred = v.optString("preferred");
        int k = 0;
        for (int pass = 0; pass < 2; pass++)
            for (int i = 0; i < n; i++) {
                JSONObject a = apps.optJSONObject(i);
                if ((a.optString("pkg").equals(preferred)) == (pass == 0)) { pkgs[k] = a.optString("pkg"); items[k++] = copy("Open ", "Apri ") + a.optString("name"); }
            }
        pkgs[n] = "";
        items[n] = copy("VPN settings", "Impostazioni VPN");
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("VPN: " + (v.optBoolean("active") ? copy("the TV is using a VPN", "la TV usa una VPN") : copy("the TV is not using a VPN", "la TV non usa una VPN")))
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) { RayVpn.open(RayLauncherActivity.this, pkgs[which]); }
                }).setNegativeButton(copy("Close", "Chiudi"), null).create();
        trackDialog(dialog);
        showStyled(dialog);
    }

    /** No phone connected: a box with the pairing QR. It opens the web remote, which also offers the APK. */
    private void updateConnectPanel() {
        if (connectPanel == null) return;
        if (stock) { updateStockConnect(); return; }
        if (RaySocket.clientCount() > 0) {
            if (!connectedShown) {
                connectedShown = true;
                connectUrl = null;
                connectQr.setVisibility(View.GONE);
                connectText.setText(copy("Phone connected", "Telefono collegato"));
                connectText.setTextColor(Color.WHITE);
                connectText.setTextSize(13);
                connectText.setPadding(dp(14), dp(7), dp(14), dp(7)); // a pill in the accent, centered in the hole
                GradientDrawable pillBg = new GradientDrawable();
                pillBg.setCornerRadius(dp(40));
                pillBg.setColor(Color.argb(140, 0, 0, 0));
                pillBg.setStroke(dp(2), accent());
                connectText.setBackground(pillBg);
                connectText.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
                connectPanel.setBackgroundColor(Color.TRANSPARENT);
            }
            connectPanel.setVisibility(View.VISIBLE);
            return;
        }
        if (connectedShown) {
            connectedShown = false;
            connectText.setTextColor(TEXT);
            connectText.setPadding(0, dp(6), 0, 0);
            connectText.setBackground(null);
            connectText.setTextSize(12);
            connectText.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
            connectPanel.setBackgroundColor(Color.argb(200, 0, 0, 0));
        }
        String url = pairingUrl();
        if (url == null) {
            connectUrl = null;
            connectQr.setVisibility(View.GONE);
            connectText.setText(copy("Connect the TV to Wi-Fi", "Connetti la TV al Wi-Fi"));
        } else if (!url.equals(connectUrl)) {
            connectUrl = url;
            connectQr.setImageBitmap(qrBitmap(url));
            connectQr.setVisibility(View.VISIBLE);
            connectText.setText(copy("Scan to pair your phone", "Inquadra per abbinare il telefono"));
        }
        connectPanel.setVisibility(View.VISIBLE);
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
