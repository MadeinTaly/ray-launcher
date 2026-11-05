package dev.raylauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The TV settings screen, made for a D-pad at 3 metres: sections on the left, the rows of the focused section on the
 * right. UP/DOWN move, RIGHT enters the rows, LEFT/BACK goes back, OK acts. Colours, head font and corner radius
 * come from the active skin. No real view focus is used: this class tracks the selection and draws it.
 */
public class MainActivity extends Activity implements Runnable {
    /** UI language: see I18n. */
    static String t(String en, String it) {
        return I18n.t(en, it);
    }

    // The on-screen display (Osd) still draws with these.
    static final int PRIMARY = Color.rgb(0xFF, 0x2B, 0x2B);
    static final int LIGHT = Color.rgb(0xFF, 0xD9, 0xB3);

    private static final String READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS";
    private static volatile MainActivity shown;

    /** kind: a action, t toggle, c choice, s slider, i read only. frac is the slider position, 0..1. */
    static final class Row {
        final char kind;
        final String id, label, value, sub;
        float frac = -1;

        Row(char kind, String id, String label, String value, String sub) {
            this.kind = kind;
            this.id = id;
            this.label = label;
            this.value = value == null ? "" : value;
            this.sub = sub;
        }
    }

    static final class Section {
        final String id, name;
        final ArrayList<Row> rows = new ArrayList<>();

        Section(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<Section> sections = new ArrayList<>();
    private final ArrayList<TextView> sectionViews = new ArrayList<>();
    private final ArrayList<View> rowViews = new ArrayList<>();
    private SharedPreferences prefs;
    private RaySkin skin;
    private float unit;
    private int bg, text, muted, ring, fill, strong;
    private FrameLayout root;
    private LinearLayout left, rows;
    private ScrollView scroll;
    private int sec, row, tickSignature;
    private boolean inRows, resetScroll, confirmForget, confirmNight, updates;
    private boolean filterOn, remoteOn;
    private int red, bright, temp;
    private volatile String[] dbgRows = new String[0];
    private volatile String dbgSection = "";
    private volatile int dbgFocus = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        I18n.init(this);
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
        updates = Updater.available(this);
        unit = getResources().getDisplayMetrics().heightPixels / 720f;
        int width = getResources().getDisplayMetrics().widthPixels;

        root = new FrameLayout(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.HORIZONTAL);
        int px = Math.round(72 * unit), py = Math.round(48 * unit);
        page.setPadding(px, py, px, py);
        left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        page.addView(left, new LinearLayout.LayoutParams(Math.round(width * .30f) - px, -1));
        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(false);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0, 0, 0, Math.round(24 * unit));
        scroll.addView(rows, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, -1, 1f);
        rightLp.leftMargin = Math.round(40 * unit);
        page.addView(scroll, rightLp);
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (prefs == null) return;
        shown = this;
        filterOn = !prefs.getBoolean("off", false);
        red = prefs.getInt("red", 0);
        bright = prefs.getInt("bright", 100);
        temp = prefs.getInt("temp", DimService.DEFAULT_TEMP);
        remoteOn = prefs.getBoolean("remote", true);
        // the phone remote is on by default on TVs: make sure its server runs
        if (remoteOn) Compat.startForegroundService(this, new Intent(this, DimService.class).putExtra("remote", true));
        refresh();
        main.postDelayed(this, 2000);
        if (RayAdb.enabled(this)) new Thread(new Runnable() { // Full access is on: does it still answer?
            @Override public void run() {
                RayAdb.probe();
                main.post(new Runnable() { @Override public void run() { if (shown == MainActivity.this) refresh(); } });
            }
        }, "ray-adb-probe").start();
    }

    @Override
    protected void onPause() {
        if (shown == this) shown = null;
        main.removeCallbacks(this);
        super.onPause();
    }

    /** Every 2 s: repaint when the number of phones or the accessibility service changed. */
    @Override
    public void run() {
        if (tickSignature != signature()) refresh();
        main.postDelayed(this, 2000);
    }

    private int signature() {
        return RaySocket.clientCount() * 2 + (DimAccessibilityService.instance != null ? 1 : 0);
    }

    /** A key from the phone remote while this screen is in front; no accessibility service needed. False when it is not. */
    static boolean remoteKey(final int code) {
        final MainActivity a = shown;
        if (a == null) return false;
        a.main.post(new Runnable() {
            @Override public void run() {
                a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
                a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
            }
        });
        return true;
    }

    static JSONObject debugSettings() throws JSONException {
        MainActivity a = shown;
        if (a == null) return new JSONObject().put("active", false);
        JSONArray list = new JSONArray();
        for (String r : a.dbgRows) list.put(r);
        return new JSONObject().put("active", true).put("section", a.dbgSection).put("focusedRow", a.dbgFocus).put("rows", list);
    }

    // ---- State to rows ----

    private static String onOff(boolean on) {
        return on ? t("on", "attivo") : t("off", "spento");
    }

    private RaySkin baseSkin() {
        RaySkin s = RaySkin.get(this, RayLauncherActivity.current(this));
        return s != null ? s : RaySkin.get(this, RaySkin.DEFAULT);
    }

    private Row add(Section s, char kind, String id, String label, String value, String sub) {
        Row r = new Row(kind, id, label, value, sub);
        s.rows.add(r);
        return r;
    }

    private Row toggle(Section s, String id, String label, boolean on, String sub) {
        return add(s, 't', id, label, onOff(on), sub);
    }

    private Row slider(Section s, String id, String label, int v, int min, int max, String unitText) {
        Row r = add(s, 's', id, label, v + unitText, null);
        r.frac = Math.max(0f, Math.min(1f, (v - min) / (float) (max - min)));
        return r;
    }

    private void collect() {
        sections.clear();
        final boolean italian = "it".equals(Locale.getDefault().getLanguage());
        RaySkin base = baseSkin();

        Section look = new Section("look", t("Look", "Aspetto"));
        add(look, 'c', "skin", t("Theme", "Tema"), base.title(italian), null);
        String dn = RayDayNight.mode(this);
        add(look, 'c', "daynight", t("Day and night", "Giorno e notte"),
                "auto".equals(dn) ? t("Automatic", "Automatico") : "day".equals(dn) ? t("Day", "Giorno") : t("Night", "Notte"), null);
        if (skin.scene != null) {
            String anim = prefs.getString(RayGraphics.PREF, "auto");
            add(look, 'c', "anim", t("Animated background", "Sfondo animato"), "on".equals(anim) ? t("On", "Acceso") : "off".equals(anim) ? t("Off", "Spento")
                    : RayGraphics.animated(this) ? t("Automatic: on", "Automatico: acceso") : t("Automatic: off, for this device", "Automatico: spento per questo dispositivo"), null);
        }
        if ("cinema".equals(skin.layout)) {
            add(look, 'a', "wp", t("Wallpaper from phone", "Sfondo dal telefono"),
                    RayWallpaper.failed ? t("not available", "non disponibile")
                            : RayWallpaper.file(this).exists() ? t("custom", "personalizzato") : t("default", "predefinito"),
                    t("On the phone: Settings > TV wallpaper > choose a picture", "Dal telefono: Impostazioni > Sfondo TV > scegli un’immagine"));
            add(look, 'a', "wpreset", t("Restore default wallpaper", "Ripristina sfondo predefinito"), null, null);
        }
        String rs = prefs.getString("ray_remote_skin", "match");
        RaySkin remoteSkin = "match".equals(rs) ? null : RaySkin.get(this, rs);
        add(look, 'c', "rskin", t("Phone remote theme", "Tema del telecomando"),
                remoteSkin == null ? t("Same as the TV", "Come la TV") : remoteSkin.title(italian), null);

        Section screen = new Section("screen", t("Screen", "Schermo"));
        toggle(screen, "filter", t("Screen filter", "Filtro schermo"), filterOn, null);
        slider(screen, "red", t("Warm filter", "Filtro caldo"), red, 0, 100, "%");
        slider(screen, "bright", t("Brightness", "Luminosità"), bright, 5, 100, "%");
        slider(screen, "temp", t("Colour", "Colore"), temp, 1000, 4000, " K");
        int night = nightKind();
        add(screen, 't', "night", t("Night filter", "Filtro di notte"), confirmNight ? t("press OK again", "premi di nuovo OK") : onOff(night == 1 && prefs.getBoolean("schedule_on", false)),
                night == 2 ? t("Set from the phone", "Impostato dal telefono") : null);
        add(screen, 'c', "nfrom", t("From", "Dalle"), hhmm(prefs.getInt("night_from", 22 * 60)), null);
        add(screen, 'c', "nto", t("To", "Alle"), hhmm(prefs.getInt("night_to", 7 * 60)), null);
        boolean dark = DimAccessibilityService.instance != null;
        add(screen, 'a', "a11y", t("Cover the whole screen", "Copri tutto lo schermo"), onOff(dark), dark ? null
                : t("To cover the screensaver and the system bars too (and for full darkness), enable RAY in Accessibility",
                "Per coprire anche lo screensaver e le barre di sistema (e per il buio totale), attiva RAY in Accessibilità"));

        Section sound = new Section("sound", t("Sound", "Audio"));
        toggle(sound, "music", t("Background music", "Musica di sottofondo"), RayAmbient.enabled(this), null);
        add(sound, 'i', "audiofx", t("Audio effects", "Effetti audio"), onOff(prefs.getBoolean("audio_on", false)),
                t("Set them from the phone remote", "Impostali dal telefono"));
        add(sound, 'a', "sound", t("Android sound settings", "Impostazioni audio di Android"), null, null);

        Section phone = new Section("phone", t("Phone", "Telefono"));
        toggle(phone, "remote", t("Phone remote", "Telecomando dal telefono"), remoteOn, null);
        add(phone, 'a', "pair", t("Pair", "Abbina"), null, null);
        add(phone, 'i', "phones", t("Connected phones", "Telefoni collegati"), String.valueOf(RaySocket.clientCount()), null);
        add(phone, 'a', "forget", t("Forget", "Dimentica"),
                confirmForget ? t("press OK again", "premi di nuovo OK") : null, null);

        Section home = new Section("home", t("Home", "Home"));
        add(home, 'a', "adb", t("Full access", "Accesso completo"), RayAdb.enabled(this) && RayAdb.broken ? t("Unavailable, try again", "Non disponibile, riprova") : onOff(RayAdb.enabled(this)), t(
                "One press, if this device allows it: continue watching from every app, what is playing, the phone working over apps. It also downloads the posters those apps publish.",
                "Una pressione, se il dispositivo lo consente: continua a guardare di tutte le app, cosa è in riproduzione, il telefono che comanda sopra le app. Scarica anche le locandine pubblicate da quelle app."));
        if (checkSelfPermission(READ_TV_LISTINGS) != PackageManager.PERMISSION_GRANTED)
            add(home, 'a', "watch", t("Watch Next access", "Accesso a Guarda dopo"), t("not allowed", "non consentito"), null);
        boolean consent = RayWeather.consent(this);
        toggle(home, "weather", t("Weather", "Meteo"), consent, consent ? null : RayWeather.consentText());
        if (consent) {
            String city = prefs.getString("ray_weather_city", "");
            add(home, 'a', "city", t("City", "Città"), city.isEmpty() ? t("not set", "non impostata") : city, null);
        }
        add(home, 'a', "apps", t("Manage apps", "Gestisci le app"), null, null);
        add(home, 'a', "inputs", t("TV inputs", "Ingressi TV"), null, null);

        Section about = new Section("about", t("Advanced", "Avanzate"));
        add(about, 'a', "pointer", t("Pointer over apps", "Puntatore sopra le app"), onOff(Settings.canDrawOverlays(this)), null);
        add(about, 'a', "media", t("App companion access", "Accesso della companion app"), null, null);
        JSONObject vpn = RayVpn.state(this);
        add(about, 'i', "vpnstate", "VPN", vpn.optBoolean("active") ? t("in use", "in uso") : t("not in use", "non in uso"), null);
        JSONArray apps = vpn.optJSONArray("apps");
        String preferred = vpn.optString("preferred");
        for (int pass = 0; pass < 2 && apps != null; pass++)
            for (int i = 0; i < apps.length(); i++) {
                JSONObject a = apps.optJSONObject(i);
                if (a.optString("pkg").equals(preferred) == (pass == 0))
                    add(about, 'a', "vpn:" + a.optString("pkg"), t("Open ", "Apri ") + a.optString("name"), null, null);
            }
        add(about, 'a', "vpn:", t("VPN settings", "Impostazioni VPN"), null, null);
        if (updates) {
            toggle(about, "upd", t("Updates", "Aggiornamenti"), Updater.enabled(this), null);
            toggle(about, "beta", t("Beta", "Beta"), Updater.beta(this),
                    !Updater.beta(this) && Updater.versionName(this).contains("-")
                            ? t("Stays on this beta until a newer stable is out", "Resta su questa beta finché non esce una stabile più nuova") : null);
        }
        boolean mcp = RayMcp.enabled(this);
        toggle(about, "mcp", t("AI assistants", "Assistenti AI"), mcp,
                t("Lets an AI assistant on your network control this TV like the phone remote: "
                + "open apps, press keys, change the skin, the filter and the music. It needs the pairing key.",
                "Permette a un assistente AI nella tua rete di comandare questa TV come il telecomando: "
                + "aprire app, premere tasti, cambiare skin, filtro e musica. Serve la chiave di abbinamento."));
        String command = mcp ? RayMcp.command(this) : null;
        // the command carries the pairing key: on screen only when asked for
        if (command != null) add(about, 'a', "mcpcommand", t("Show the command to paste", "Mostra il comando da incollare"), null, null);
        if (RayDebug.open) toggle(about, "debug", t("Debug", "Debug"), RayDebug.enabled(this), null);
        add(about, 'a', "android", t("Android settings", "Impostazioni Android"), null, null);
        add(about, 'i', "version", t("Version", "Versione"), Updater.versionName(this), null);
        add(about, 'i', "support", t("Support the project", "Sostieni il progetto"), "linktr.ee/made_in_Taly", null);

        for (Section s : new Section[]{look, screen, sound, phone, home, about}) if (!s.rows.isEmpty()) sections.add(s);
        tickSignature = signature();
    }

    // ---- Actions ----

    private void press(Row r) {
        String id = r.id;
        switch (id) {
            case "skin": case "rskin": case "daynight": case "anim": step(r, 1); return;
            case "wp": openLauncher(t("On the phone: Settings > TV wallpaper > choose a picture", "Dal telefono: Impostazioni > Sfondo TV > scegli un’immagine")); return;
            case "wpreset":
                RayWallpaper.reset(this);
                Toast.makeText(this, t("Default wallpaper restored", "Sfondo predefinito ripristinato"), Toast.LENGTH_SHORT).show();
                break;
            case "filter": filterOn = !filterOn; applyFilter(); break;
            case "night": {
                int kind = nightKind();
                if (kind == 2 && !confirmNight) { confirmNight = true; break; } // replaces the schedule made on the phone
                confirmNight = false;
                setNight(!(kind == 1 && prefs.getBoolean("schedule_on", false)));
                return;
            }
            case "a11y":
                Compat.enableOrOpenAccessibility(this);
                break;
            case "adb":
                if (RayAdb.enabled(this) && !RayAdb.broken) { RayAdb.disable(this); break; }
                new Thread(new Runnable() {
                    @Override public void run() {
                        final boolean ok = RayAdb.setup(MainActivity.this);
                        main.post(new Runnable() {
                            @Override public void run() {
                                if (isFinishing() || isDestroyed()) return;
                                if (ok && DimAccessibilityService.instance == null) Compat.enableOrOpenAccessibility(MainActivity.this);
                                Toast.makeText(MainActivity.this, ok ? t("Full access on", "Accesso completo attivo")
                                        : t("This device does not allow it", "Questo dispositivo non lo consente"), Toast.LENGTH_LONG).show();
                                refresh();
                            }
                        });
                    }
                }, "ray-adb").start();
                return;
            case "music": RayLauncherActivity.setMusic(this, !RayAmbient.enabled(this)); break;
            case "sound": RayLauncherActivity.openSoundSettings(this); return;
            case "remote":
                remoteOn = !remoteOn;
                if (remoteOn) DimService.pairingKey(prefs);
                Compat.startForegroundService(this, new Intent(this, DimService.class).putExtra("remote", remoteOn));
                break;
            case "pair": openLauncher(null); return;
            case "forget":
                if (confirmForget) {
                    DimService.newPairingKey(prefs); // phones paired with the old code stop working
                    confirmForget = false;
                    Toast.makeText(this, t("All paired phones forgotten", "Tutti i telefoni abbinati dimenticati"), Toast.LENGTH_SHORT).show();
                } else confirmForget = true;
                break;
            case "pointer": RayLauncherActivity.openPointerPermission(this); return;
            case "media": RayLauncherActivity.openMediaAccess(this); return;
            case "watch": requestPermissions(new String[]{READ_TV_LISTINGS}, 1); return;
            case "weather":
                RayWeather.setConsent(this, !RayWeather.consent(this));
                RaySocket.stateChanged();
                break;
            case "city": askCity(); return;
            case "apps": startActivity(new Intent(this, RayLauncherActivity.class).putExtra("manage", true)); return;
            case "inputs": {
                Intent intent = new Intent("android.media.tv.action.SETUP_INPUTS");
                if (intent.resolveActivity(getPackageManager()) == null) intent = new Intent(Settings.ACTION_SETTINGS);
                startActivity(intent);
                return;
            }
            case "upd": Updater.setEnabled(this, !Updater.enabled(this)); break;
            case "beta": Updater.setBeta(this, !Updater.beta(this)); break;
            case "mcp":
                RayMcp.set(this, !RayMcp.enabled(this));
                RayDebug.log("mcp " + (RayMcp.enabled(this) ? "on" : "off"));
                break;
            case "mcpcommand": {
                String line = RayMcp.command(this);
                if (line != null) new AlertDialog.Builder(this).setTitle(t("AI assistants", "Assistenti AI")).setMessage(line)
                        .setPositiveButton(t("Close", "Chiudi"), null).show();
                break;
            }
            case "debug": RayDebug.toggle(this); break;
            case "android":
                try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (RuntimeException ignored) { }
                return;
            default:
                if (id.startsWith("vpn:")) { RayVpn.open(this, id.substring(4)); return; }
        }
        refresh();
    }

    /** Choices and sliders: d is -1 or +1. */
    private void step(Row r, int d) {
        switch (r.id) {
            case "skin": {
                List<RaySkin> list = RaySkin.list(this);
                int at = 0;
                for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(baseSkin().id)) at = i;
                RayLauncherActivity.setSkin(this, list.get(wrap(at + d, list.size())).id);
                break;
            }
            case "rskin": {
                ArrayList<String> ids = new ArrayList<>();
                ids.add("match");
                for (RaySkin s : RaySkin.list(this)) ids.add(s.id);
                RayLauncherActivity.setRemoteSkin(this, ids.get(wrap(Math.max(0, ids.indexOf(prefs.getString("ray_remote_skin", "match"))) + d, ids.size())));
                break;
            }
            case "daynight": {
                List<String> modes = Arrays.asList("auto", "day", "night");
                RayLauncherActivity.setDayNight(this, modes.get(wrap(modes.indexOf(RayDayNight.mode(this)) + d, 3)));
                break;
            }
            case "anim": {
                List<String> modes = Arrays.asList("auto", "on", "off");
                String next = modes.get(wrap(Math.max(0, modes.indexOf(prefs.getString(RayGraphics.PREF, "auto"))) + d, 3));
                prefs.edit().putString(RayGraphics.PREF, next).putBoolean(RayGraphics.SLOW, false).apply();
                break;
            }
            case "nfrom": case "nto": {
                String k = r.id.equals("nfrom") ? "night_from" : "night_to";
                prefs.edit().putInt(k, wrap(prefs.getInt(k, r.id.equals("nfrom") ? 22 * 60 : 7 * 60) + d * 30, 1440)).apply();
                if (nightKind() == 1 && prefs.getBoolean("schedule_on", false)) setNight(true);
                break;
            }
            case "red": red = Math.max(0, Math.min(100, red + d * 5)); applyFilter(); break;
            case "bright": bright = Math.max(5, Math.min(100, bright + d * 5)); applyFilter(); break;
            case "temp": temp = Math.max(1000, Math.min(4000, temp + d * 100)); applyFilter(); break;
        }
        refresh();
    }

    private static int wrap(int i, int n) {
        return (i % n + n) % n;
    }

    private static String hhmm(int minutes) {
        return String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }

    /** 1 the TV's own night filter is the schedule, 2 a schedule made on the phone is, 0 none. */
    private int nightKind() {
        try {
            JSONArray slots = new JSONArray(DimService.todaySlots(prefs.getString("schedule", "[]")));
            for (int i = 0; i < slots.length(); i++) if (!"tv".equals(slots.getJSONObject(i).optString("model"))) return 2;
            return slots.length() > 0 ? 1 : 0;
        } catch (JSONException bad) { return 0; }
    }

    /** The night filter as two daily slots through the schedule of the service: the current look from "From", off from "To". */
    private void setNight(boolean on) {
        try {
            JSONArray slots = new JSONArray()
                    .put(new JSONObject().put("time", hhmm(prefs.getInt("night_from", 22 * 60))).put("on", true)
                            .put("red", red == 0 ? 40 : red).put("bright", bright).put("temp", temp).put("model", "tv"))
                    .put(new JSONObject().put("time", hhmm(prefs.getInt("night_to", 7 * 60))).put("on", false).put("model", "tv"));
            Compat.startForegroundService(this, new Intent(this, DimService.class)
                    .putExtra("schedule", new JSONObject().put("resolved", slots).toString()).putExtra("scheduleOn", on));
        } catch (JSONException impossible) { }
        main.postDelayed(new Runnable() { @Override public void run() { if (shown == MainActivity.this) refresh(); } }, 500);
    }

    /** Sends the filter to the service; moving a slider also turns the filter on. */
    private void applyFilter() {
        Intent i = new Intent(this, DimService.class);
        if (filterOn) i.putExtra("red", red).putExtra("bright", bright).putExtra("temp", temp);
        else i.putExtra("off", true);
        Compat.startForegroundService(this, i);
    }

    /** The home shows the pairing dialog (a hint replaces the usual line). */
    private void openLauncher(String hint) {
        startActivity(new Intent(this, RayLauncherActivity.class).putExtra("pair", true).putExtra("pairHint", hint));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (prefs != null) refresh();
    }

    /** City, weather service and key, applied off the UI thread (it geocodes over the network). */
    private void askCity() {
        final String[] names = {"Open-Meteo (" + t("no key", "senza chiave") + ")", "OpenWeatherMap"};
        final int[] sel = {Math.max(0, Arrays.asList(RayWeather.PROVIDERS).indexOf(prefs.getString("ray_weather_provider", "openmeteo")))};
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * unit);
        box.setPadding(pad, pad / 2, pad, 0);
        final EditText city = new EditText(this);
        city.setSingleLine(true);
        city.setHint(t("City", "Città"));
        city.setText(prefs.getString("ray_weather_city", ""));
        final Button provider = new Button(this);
        provider.setAllCaps(false);
        final EditText key = new EditText(this);
        key.setSingleLine(true);
        key.setHint(t("API key (empty keeps the saved one)", "Chiave API (vuota = tieni quella salvata)"));
        key.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final Runnable sync = new Runnable() {
            @Override public void run() {
                provider.setText(names[sel[0]]);
                key.setVisibility(sel[0] == 0 ? View.GONE : View.VISIBLE);
            }
        };
        sync.run();
        provider.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                sel[0] = (sel[0] + 1) % names.length;
                sync.run();
            }
        });
        box.addView(city, new LinearLayout.LayoutParams(-1, -2));
        box.addView(provider, new LinearLayout.LayoutParams(-1, -2));
        box.addView(key, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this).setTitle(t("Weather", "Meteo")).setView(box)
                .setPositiveButton(t("Save", "Salva"), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        final String c = city.getText().toString(), k = key.getText().toString(), p = RayWeather.PROVIDERS[sel[0]];
                        final android.content.Context app = getApplicationContext();
                        new Thread(new Runnable() {
                            @Override public void run() {
                                final boolean ok = RayLauncherActivity.applyWeather(app, c, p, k);
                                main.post(new Runnable() {
                                    @Override public void run() {
                                        if (!ok) Toast.makeText(app, t("Unknown city or invalid key", "Città sconosciuta o chiave non valida"), Toast.LENGTH_LONG).show();
                                        if (shown == MainActivity.this) refresh();
                                    }
                                });
                            }
                        }, "weather-set").start();
                    }
                }).setNegativeButton(t("Cancel", "Annulla"), null).show();
    }

    // ---- Keys ----

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (prefs == null || sections.isEmpty() || !(ok || k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_DPAD_UP
                || k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT))
            return super.dispatchKeyEvent(e);
        RayDebug.key(e);
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        Section s = sections.get(sec);
        Row r = inRows ? s.rows.get(row) : null;
        boolean adjustable = r != null && (r.kind == 'c' || r.kind == 's');
        if (confirmForget && !(ok && r != null && "forget".equals(r.id)) || confirmNight && !(ok && r != null && "night".equals(r.id))) {
            confirmForget = confirmNight = false;
            refresh();
            s = sections.get(sec);
            r = inRows ? s.rows.get(row) : null;
        }
        if (k == KeyEvent.KEYCODE_BACK) {
            if (inRows) { inRows = false; paint(); } else finish();
        } else if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN) {
            int d = k == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
            if (inRows) {
                row = Math.max(0, Math.min(s.rows.size() - 1, row + d));
                paint();
            } else {
                sec = Math.max(0, Math.min(sections.size() - 1, sec + d));
                row = 0;
                resetScroll = true;
                refresh();
            }
        } else if (k == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (!inRows) { inRows = true; paint(); }
            else if (adjustable) step(r, 1);
        } else if (k == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (adjustable) step(r, -1);
            else if (inRows) { inRows = false; paint(); }
        } else if (inRows && e.getRepeatCount() == 0) {
            press(r);
        } else if (!inRows) {
            inRows = true;
            paint();
        }
        return true;
    }

    // ---- Drawing ----

    private GradientDrawable box(int color, boolean outline) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(skin.cardRadius * unit);
        if (outline) g.setStroke(Math.round(3 * unit), ring);
        return g;
    }

    private static boolean dark(int c) {
        return Color.red(c) + Color.green(c) + Color.blue(c) < 384;
    }

    private TextView label(String s, float size, int color) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * unit);
        v.setTextColor(color);
        return v;
    }

    /** Recomputes everything from the current state and redraws both columns. */
    private void refresh() {
        RaySkin base = baseSkin();
        skin = base.forMode(RayDayNight.isDay(this));
        text = skin.textColor;
        muted = skin.mutedColor;
        ring = skin.ring;
        fill = skin.panelFill;
        strong = skin.panelStrong;
        int surface = skin.surface | 0xff000000; // a light text needs a dark page and the other way round
        bg = skin.light == dark(surface) ? (skin.light ? 0xfff5f1e9 : 0xff0a0a0c) : surface;
        root.setBackgroundColor(bg);
        collect();
        sec = Math.min(sec, sections.size() - 1);
        row = Math.min(row, sections.get(sec).rows.size() - 1);

        left.removeAllViews();
        sectionViews.clear();
        android.graphics.Typeface head = "cinema".equals(skin.layout) ? Fonts.display(this, 400) : Fonts.serif();
        for (Section s : sections) {
            TextView v = label(s.name, 24, text);
            v.setTypeface(head);
            v.setGravity(Gravity.CENTER_VERTICAL);
            v.setMinHeight(Math.round(64 * unit));
            v.setPadding(Math.round(20 * unit), 0, Math.round(12 * unit), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = Math.round(8 * unit);
            left.addView(v, lp);
            sectionViews.add(v);
        }

        int keep = resetScroll ? 0 : scroll.getScrollY();
        resetScroll = false;
        rows.removeAllViews();
        rowViews.clear();
        for (Row r : sections.get(sec).rows) {
            View v = rowView(r);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = Math.round(8 * unit);
            rows.addView(v, lp);
            rowViews.add(v);
        }
        Fonts.apply(rows);
        paint();
        final int y = keep;
        scroll.post(new Runnable() {
            @Override public void run() {
                scroll.scrollTo(0, y);
                reveal();
            }
        });
    }

    private View rowView(Row r) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setMinimumHeight(Math.round(64 * unit));
        v.setPadding(Math.round(20 * unit), Math.round(10 * unit), Math.round(20 * unit), Math.round(10 * unit));
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(label(r.label, 22, text));
        if (r.sub != null) {
            TextView sub = label(r.sub, 18, muted);
            sub.setPadding(0, Math.round(4 * unit), 0, 0);
            column.addView(sub);
        }
        v.addView(column, new LinearLayout.LayoutParams(0, -2, 1f));
        if (r.frac >= 0) {
            int w = Math.round(240 * unit), h = Math.round(8 * unit);
            FrameLayout bar = new FrameLayout(this);
            GradientDrawable track = new GradientDrawable();
            track.setColor((muted & 0x00ffffff) | 0x66000000);
            track.setCornerRadius(h / 2f);
            bar.setBackground(track);
            View done = new View(this);
            GradientDrawable on = new GradientDrawable();
            on.setColor(ring);
            on.setCornerRadius(h / 2f);
            done.setBackground(on);
            bar.addView(done, new FrameLayout.LayoutParams(Math.max(h, Math.round(w * r.frac)), h));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            lp.leftMargin = Math.round(24 * unit);
            v.addView(bar, lp);
        }
        if (!r.value.isEmpty()) {
            TextView value = label(r.value, 22, muted);
            value.setGravity(Gravity.END);
            value.setPadding(Math.round(24 * unit), 0, 0, 0);
            v.addView(value, new LinearLayout.LayoutParams(-2, -2));
        }
        return v;
    }

    /** Selection look only (nothing is rebuilt): the focused row or section gets the ring. */
    private void paint() {
        for (int i = 0; i < sectionViews.size(); i++) {
            boolean on = i == sec;
            TextView v = sectionViews.get(i);
            v.setTextColor(on ? text : muted);
            v.setBackground(on ? box(strong, !inRows) : null);
        }
        for (int i = 0; i < rowViews.size(); i++) {
            boolean on = inRows && i == row;
            rowViews.get(i).setBackground(box(on ? strong : fill, on));
        }
        Section s = sections.get(sec);
        dbgSection = s.id;
        dbgFocus = inRows ? row : -1;
        String[] list = new String[s.rows.size()];
        for (int i = 0; i < list.length; i++) list[i] = s.rows.get(i).label + "=" + s.rows.get(i).value;
        dbgRows = list;
        reveal();
    }

    /** Keeps the focused row inside the visible part of the list. */
    private void reveal() {
        if (!inRows || row >= rowViews.size()) return;
        View v = rowViews.get(row);
        int margin = Math.round(24 * unit), y = scroll.getScrollY(), h = scroll.getHeight();
        if (v.getTop() - margin < y) scroll.scrollTo(0, Math.max(0, v.getTop() - margin));
        else if (h > 0 && v.getBottom() + margin > y + h) scroll.scrollTo(0, v.getBottom() + margin - h);
    }
}
