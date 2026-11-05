package dev.raylauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.regex.Pattern;

import static dev.raylauncher.MainActivity.t;

/**
 * Phone remote, hosted in the app WebView. One screen decides what is shown: the RAY page of the chosen TV when it
 * answers, otherwise the RAY connect screen (assets/ray.html on this phone, #local mode) where TVs are chosen and
 * paired. The local device manager (app.html) is reached from a link on that screen.
 */
public class PhoneActivity extends Activity implements DialogInterface.OnClickListener, SensorEventListener {
    static final String UA_SUFFIX = " RayApp/1";
    static final String LAST_DEVICE = "ray_phone_device";
    private static final int SCAN = 1;
    private static final String LOOPBACK = "http://127.0.0.1:" + RemoteServer.PORT;
    private static final int RAY_BG = Color.rgb(2, 6, 13);
    private static final int RAY_TEXT = Color.rgb(231, 245, 250);
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.\\-]{1,253}");
    private static final Pattern KEY = Pattern.compile("[0-9a-f]{1,128}");

    private final Handler main = new Handler(Looper.getMainLooper());
    private final float[] rotation = new float[9];
    private final float[] orientation = new float[3];
    private SharedPreferences prefs;
    private WebView web;
    private AppWebClient client;
    private Discovery discovery;
    private SeekBar sensitivityInput;
    private SensorManager sensors;
    private Sensor rotationSensor;
    private Sensor gyroSensor;
    private Sensor accelSensor;
    /** The user left the TV: connect screen (or the manager). false: the saved TV, if it answers. */
    private boolean localPage;
    private boolean manager;
    private boolean started;
    /** The TV page is loaded (not the connect screen). */
    private boolean onTv;
    private boolean failed;
    /** Extra for the next connect screen: "&rejected=id". */
    private String notice = "";
    private volatile int gen; // a newer open() drops the probe of an older one
    private boolean resumed;
    private boolean rayPage;
    private boolean sensorRunning;
    private volatile boolean calibrated;
    private boolean motionEnabled;
    private final RayMotionMath.Integrator integrator = new RayMotionMath.Integrator(); // sensor thread only
    private volatile int sens = 100;
    private RayPointerSender udp;
    private android.os.HandlerThread sensorThread;
    private Handler sensorHandler;
    private final Runnable reopen = new Runnable() {
        @Override public void run() {
            open();
        }
    };

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (resumed && rayPage && motionEnabled // Motion on: the volume keys are its buttons, always
                && (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                RayDebug.log(code == KeyEvent.KEYCODE_VOLUME_DOWN ? "volume key: select" : "volume key: recenter");
                if (code == KeyEvent.KEYCODE_VOLUME_DOWN)
                    web.evaluateJavascript("window.RayNativeSelect&&window.RayNativeSelect()", null);
                else recenter();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        RayDebug.init(this);
        current = this;
        I18n.init(this);
        if (Compat.isTv(this)) {
            startActivity(new Intent(this, RayLauncherActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }
        prefs = getSharedPreferences(DimService.PREFS, MODE_PRIVATE);
        getWindow().setStatusBarColor(RAY_BG);
        getWindow().setNavigationBarColor(RAY_BG);

        FrameLayout body = new FrameLayout(this);
        body.setBackgroundColor(RAY_BG);
        web = new WebView(this);
        web.setBackgroundColor(RAY_BG);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setUserAgentString(settings.getUserAgentString() + UA_SUFFIX);
        client = new AppWebClient(this);
        web.setWebViewClient(client);
        body.addView(web, new FrameLayout.LayoutParams(-1, -1));
        Compat.padForSystemBars(body);
        setContentView(body);

        // a saved TV seen at a new address: the open page follows it
        discovery = new Discovery(this, prefs, new Runnable() {
            @Override public void run() {
                main.post(new Runnable() {
                    @Override public void run() {
                        if (started && !localPage) open();
                    }
                });
            }
        });
        sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
        // air mouse: raw gyroscope; the game rotation vector (no magnetometer) only when there is no gyroscope
        rotationSensor = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        gyroSensor = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        accelSensor = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web == null) { finish(); return; }
        resumed = true;
        Compat.startForegroundService(this, new Intent(this, DimService.class).putExtra("local", true));
        if (!started) {
            started = true;
            open();
        }
        web.onResume();
        discovery.start();
        startMotion();
        syncMotionState();
    }

    @Override
    protected void onPause() {
        if (web == null) { super.onPause(); return; }
        resumed = false;
        stopMotion();
        motionEnabled = false; // the page is told too: page and field stay in step
        syncMotionState();
        web.onPause();
        main.removeCallbacks(reopen);
        discovery.stop();
        Compat.startForegroundService(this, new Intent(this, DimService.class).putExtra("local", false));
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }

    /** From a TV or the manager back to the connect screen (its own steps first), then out. */
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.RayBack&&window.RayBack()", new ValueCallback<String>() {
            @Override public void onReceiveValue(String consumed) {
                if ("true".equals(consumed)) return;
                if (onTv || manager) showConnect();
                else PhoneActivity.super.onBackPressed();
            }
        });
    }

    private static boolean reach(String host, int port, int timeout) {
        java.net.Socket s = new java.net.Socket();
        try {
            s.connect(new java.net.InetSocketAddress(host, port), timeout);
            return true;
        } catch (java.io.IOException e) {
            return false;
        } finally {
            try { s.close(); } catch (java.io.IOException ignored) { }
        }
    }

    /**
     * The one place that loads a page: probes the chosen TV (1.2 s) off the main thread, then shows its remote, or the
     * connect screen (after the loopback server, started by DimService, accepts) with that TV listed as not reachable.
     */
    private void open() {
        final int g = ++gen;
        main.removeCallbacks(reopen);
        web.setVisibility(View.INVISIBLE);
        final JSONObject device = selectedDevice();
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean up = device != null && reach(device.optString("host"), device.optInt("port"), 1200);
                for (int i = 0; !up && i < 25 && g == gen && !reach("127.0.0.1", RemoteServer.PORT, 200); i++) {
                    try { Thread.sleep(200); } catch (InterruptedException x) { return; }
                    // a quick pause and resume can lose the service's start: ask again once
                    if (i == 4) Compat.startForegroundService(PhoneActivity.this, new Intent(PhoneActivity.this, DimService.class).putExtra("local", true));
                }
                main.post(new Runnable() {
                    @Override public void run() {
                        if (g == gen && !isDestroyed()) show(device, up);
                    }
                });
            }
        }).start();
    }

    private void show(JSONObject device, boolean up) {
        failed = false;
        stopMotion();
        motionEnabled = false;
        calibrated = false;
        onTv = up;
        rayPage = false;
        if (up) {
            client.origin = "http://" + device.optString("host") + ":" + device.optInt("port");
            web.loadUrl(target(device));
            return;
        }
        client.origin = LOOPBACK;
        String key = DimService.pairingKey(prefs);
        String extra = !notice.isEmpty() ? notice : device != null ? "&down=" + Uri.encode(id(device)) : "";
        notice = "";
        web.loadUrl(manager ? LOOPBACK + "/app.html?r=" + System.currentTimeMillis() + "#k=" + key
                : LOOPBACK + "/ray#local=" + key + extra);
    }

    private JSONObject selectedDevice() {
        if (localPage) return null;
        JSONObject device = Devices.byId(prefs, prefs.getString(LAST_DEVICE, ""));
        if (device == null) {
            JSONArray all = Devices.load(prefs);
            for (int i = 0; i < all.length(); i++) {
                JSONObject candidate = all.optJSONObject(i);
                if (candidate != null && target(candidate) != null) {
                    prefs.edit().putString(LAST_DEVICE, id(candidate)).apply();
                    return candidate;
                }
            }
        }
        return device != null && target(device) != null ? device : null;
    }

    private static String id(JSONObject device) {
        return device.optString("host") + ":" + device.optInt("port");
    }

    private static String target(JSONObject device) {
        String host = device.optString("host", "");
        String key = device.optString("key", "");
        int port = device.optInt("port", -1);
        if (!HOST.matcher(host).matches() || !KEY.matcher(key).matches() || port < 1 || port > 65535) return null;
        return "http://" + host + ":" + port + "/ray#k=" + key;
    }

    private void showConnect() {
        localPage = true;
        manager = false;
        open();
    }

    private void showLocalManager() {
        localPage = true;
        manager = true;
        open();
    }

    /** Makes a saved TV the chosen one and loads it; asks it for its name and network id when they are not known yet. */
    private void openDevice(String id) {
        JSONObject device = Devices.byId(prefs, id);
        if (device == null || target(device) == null) return;
        prefs.edit().putString(LAST_DEVICE, id).apply();
        localPage = false;
        manager = false;
        if (device.optString("did").isEmpty() || device.optString("name").equals(device.optString("host")))
            new Thread(new InfoFetch(this, main, device.optString("host"), device.optInt("port"), device.optString("key"))).start();
        if (started) open();
    }

    /** The TV refused this phone's key: connect screen with that TV marked, to pair it again with the code. */
    void keyRejected() {
        if (!onTv) return;
        onTv = false; // the TV page keeps reporting until it is replaced
        notice = "&rejected=" + Uri.encode(prefs.getString(LAST_DEVICE, ""));
        showConnect();
    }

    /** Main frame failed to load: the TV went away after the probe (connect screen), or the loopback server is not up yet. */
    void loadFailed() {
        failed = true;
        if (onTv) show(selectedDevice(), false);
        else main.postDelayed(reopen, 2000);
    }

    void pageDone(String url) {
        rayPage = isRayPageUrl(url);
        if (rayPage && sharedUrl != null) {
            web.evaluateJavascript("window.RayShare&&window.RayShare(" + JSONObject.quote(sharedUrl) + ")", null);
            sharedUrl = null;
        }
        if (!failed && url != null && url.startsWith(client.origin)) web.setVisibility(View.VISIBLE);
        if (!rayPage) stopMotion();
        syncMotionState();
        startMotion();
    }

    void pageStarted() {
        failed = false;
        rayPage = false;
        stopMotion();
    }

    boolean isRayPageUrl(String url) {
        if (!onTv || url == null) return false;
        Uri page = Uri.parse(url);
        Uri origin = Uri.parse(client.origin);
        return origin.getScheme().equals(page.getScheme()) && origin.getHost().equals(page.getHost())
                && origin.getPort() == page.getPort() && "/ray".equals(page.getPath());
    }

    void appAction(String what) {
        try {
            if ("settings/motion".equals(what)) showSensitivity();
            else if ("devices/select".equals(what)) showConnect();
            else if ("manager".equals(what)) showLocalManager();
            else if (what.startsWith("open/")) openDevice(Uri.decode(what.substring(5)));
            else if ("rejected".equals(what)) keyRejected();
            else if ("scan".equals(what)) scan();
            else if (what.startsWith("share/"))
                android.widget.Toast.makeText(this, "ok".equals(what.substring(6)) ? t("Sent to the TV", "Inviato alla TV")
                        : t("The TV could not open it", "La TV non riesce ad aprirlo"), android.widget.Toast.LENGTH_SHORT).show();
            else if ("overlay".equals(what)) startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            else if ("accessibility".equals(what)) {
                Compat.enableOrOpenAccessibility(this);
                web.evaluateJavascript("setTimeout(function(){document.dispatchEvent(new Event('visibilitychange'))},1500)", null);
            } else if ("motion/start".equals(what)) {
                setMotionEnabled(true);
            } else if ("motion/stop".equals(what)) {
                setMotionEnabled(false);
            } else if ("motion/recenter".equals(what)) {
                recenter();
            }
        } catch (RuntimeException failed) { RayDebug.log("app action " + what + ": " + failed); }
    }

    private void scan() {
        Intent scan = new Intent("com.google.zxing.client.android.SCAN").putExtra("SCAN_MODE", "QR_CODE_MODE");
        if (scan.resolveActivity(getPackageManager()) != null) {
            startActivityForResult(scan, SCAN);
            return;
        }
        try { startActivity(new Intent("android.media.action.STILL_IMAGE_CAMERA")); }
        catch (RuntimeException e) { web.evaluateJavascript("window.mbNotice && window.mbNotice('noCamera')", null); }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != SCAN || resultCode != RESULT_OK || data == null) return;
        String text = data.getStringExtra("SCAN_RESULT");
        String id = text == null ? null : Devices.addLink(prefs, Uri.parse(text.trim()));
        if (id == null) {
            web.evaluateJavascript("window.mbNotice && window.mbNotice('badLink')", null);
            return;
        }
        openDevice(id);
    }

    /** Link waiting for the TV page to load; the page sends it (url.open) once it is signed in. */
    private String sharedUrl;
    private static final Pattern LINK = Pattern.compile("https?://[^\\s<>\"]+");

    /** ACTION_SEND text/plain: the first http(s) link in the text goes to the chosen TV; no TV saved: a toast and nothing else. */
    private void share(Intent intent) {
        setIntent(new Intent());
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        java.util.regex.Matcher link = LINK.matcher(text == null ? "" : text);
        if (!link.find()) {
            android.widget.Toast.makeText(this, t("No link to send", "Nessun link da inviare"), android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        JSONArray all = Devices.load(prefs);
        boolean any = false;
        for (int i = 0; i < all.length(); i++) any |= all.optJSONObject(i) != null && target(all.optJSONObject(i)) != null;
        if (!any) {
            android.widget.Toast.makeText(this, t("Add a TV in RAY first", "Aggiungi prima una TV in RAY"), android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        sharedUrl = link.group();
        if (localPage) { localPage = false; manager = false; if (started) open(); }
        else if (rayPage) {
            web.evaluateJavascript("window.RayShare&&window.RayShare(" + JSONObject.quote(sharedUrl) + ")", null);
            sharedUrl = null;
        }
    }

    private void handleIntent(Intent intent) {
        if (intent != null && Intent.ACTION_SEND.equals(intent.getAction()) && "text/plain".equals(intent.getType())) {
            share(intent);
            return;
        }
        Uri uri = intent == null ? null : intent.getData();
        if (uri == null) return;
        String id = Devices.addLink(prefs, uri);
        setIntent(new Intent());
        if (id != null) openDevice(id);
    }

    void deviceNamed(String host, int port, String name, String did) {
        JSONObject device = Devices.byId(prefs, host + ":" + port);
        if (device == null) return;
        if (did.matches("[0-9a-f]{1,32}")) Devices.setField(prefs, host, port, "did", did);
        if (device.optString("name").equals(host)) Devices.rename(prefs, host, port, Devices.cleanName(name));
        web.evaluateJavascript("window.mbDevicesChanged && window.mbDevicesChanged()", null);
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        if (which != DialogInterface.BUTTON_NEGATIVE && sensitivityInput != null) {
            sens = Math.max(1, sensitivityInput.getProgress());
            prefs.edit().putInt("ray_motion_sensitivity", sens).apply();
        }
        sensitivityInput = null;
    }

    private void showSensitivity() {
        sensitivityInput = new SeekBar(this);
        sensitivityInput.setMax(400);
        sensitivityInput.setProgress(Math.max(1, Math.min(400, prefs.getInt("ray_motion_sensitivity", 100))));
        LinearLayout settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.VERTICAL);
        settings.setPadding(dp(16), 0, dp(16), 0);
        settings.addView(sensitivityInput);
        TextView volumeHelp = new TextView(this);
        volumeHelp.setText(t("Volume −: select · Volume +: recenter", "Volume −: seleziona · Volume +: ricentra"));
        volumeHelp.setTextColor(RAY_TEXT);
        settings.addView(volumeHelp);
        new AlertDialog.Builder(this).setTitle(t("Motion sensitivity", "Sensibilità movimento"))
                .setView(settings).setNegativeButton(t("Cancel", "Annulla"), this)
                .setPositiveButton(t("Done", "Fatto"), this).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void setMotionEnabled(boolean enabled) {
        motionEnabled = enabled;
        if (motionEnabled) startMotion(); else stopMotion();
        syncMotionState();
    }

    private void syncMotionState() {
        if (rayPage) {
            boolean available = rotationSensor != null || gyroSensor != null;
            web.evaluateJavascript("window.RayNativeMotionEnabled&&window.RayNativeMotionEnabled(" + motionEnabled
                    + "," + available + ")", null);
        }
    }

    /** Pointer back to the centre of the TV (absolute, over the WebSocket) and the integrator restarted. */
    private void recenter() {
        calibrated = false;
        web.evaluateJavascript("window.RayNativeRecenter&&window.RayNativeRecenter()", null);
    }

    private void startMotion() {
        if (!resumed || !rayPage || !motionEnabled || sensors == null || sensorRunning) return;
        sens = Math.max(1, Math.min(400, prefs.getInt("ray_motion_sensitivity", 100)));
        JSONObject device = selectedDevice();
        if (device != null && udp == null) {
            udp = new RayPointerSender(device.optString("host"), device.optString("key"), new RayPointerSender.Fallback() {
                @Override public void send(final int dx, final int dy) {
                    main.post(new Runnable() { @Override public void run() {
                        web.evaluateJavascript("window.RayNativeDelta&&window.RayNativeDelta(" + dx + "," + dy + ")", null);
                    } });
                }
            });
            udp.start();
        }
        // own thread at the sensor's full rate: a busy UI thread (WebView) can no longer delay or batch the samples
        sensorThread = new android.os.HandlerThread("ray-sensor", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
        sensorThread.start();
        sensorHandler = new Handler(sensorThread.getLooper());
        final int fastest = SensorManager.SENSOR_DELAY_FASTEST;
        if (gyroSensor != null) {
            sensorRunning = sensors.registerListener(this, gyroSensor, fastest, sensorHandler);
            if (accelSensor != null) sensors.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME, sensorHandler);
        } else if (rotationSensor != null) sensorRunning = sensors.registerListener(this, rotationSensor, fastest, sensorHandler);
        calibrated = false;
    }

    private void stopMotion() {
        if (sensorRunning && sensors != null) sensors.unregisterListener(this);
        sensorRunning = false;
        if (sensorThread != null) { sensorThread.quitSafely(); sensorThread = null; }
        if (udp != null) { udp.stop(); udp = null; }
    }

    private static volatile PhoneActivity current;
    private volatile double movedX, movedY;
    private volatile long sensorEvents;

    /** This phone as it is now, for the debug channel: page, motion, where "up" is and how far the pointer was sent. */
    static org.json.JSONObject debugPhone() throws org.json.JSONException {
        PhoneActivity a = current;
        if (a == null) return new org.json.JSONObject().put("active", false);
        float[] up = a.integrator.up();
        return new org.json.JSONObject().put("active", a.resumed).put("rayPage", a.rayPage).put("localPage", a.localPage)
                .put("motion", a.motionEnabled).put("sensor", a.sensorRunning).put("sensorEvents", a.sensorEvents)
                .put("up", up == null ? "unknown" : up[0] + "," + up[1] + "," + up[2]).put("sens", a.sens)
                .put("movedX", a.movedX).put("movedY", a.movedY).put("udpAlive", a.udp != null && a.udp.alive())
                .put("savedDevices", Devices.load(a.prefs).length());
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        int type = event.sensor.getType();
        float[] v = event.values;
        if (type == Sensor.TYPE_ACCELEROMETER) { integrator.gravity(v[0], v[1], v[2]); return; }
        if (!calibrated) {
            calibrated = true;
            integrator.reset();
            RayPointerSender u = udp;
            if (u != null) u.reset();
        }
        boolean moved;
        if (type == Sensor.TYPE_GYROSCOPE) moved = integrator.gyro(v[0], v[1], v[2], event.timestamp, sens);
        else {
            SensorManager.getRotationMatrixFromVector(rotation, v);
            SensorManager.getOrientation(rotation, orientation);
            moved = integrator.angles(orientation[0], orientation[1], event.timestamp, sens);
        }
        RayPointerSender u = udp;
        if (moved && u != null) u.add(integrator.dx, integrator.dy);
        if (RayDebug.open) { sensorEvents++; if (moved) { movedX += integrator.dx; movedY += integrator.dy; } }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
}
