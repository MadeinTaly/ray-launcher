package dev.raylauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
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
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.regex.Pattern;

import static dev.raylauncher.MainActivity.t;

/** Phone remote and the existing local device manager, both hosted in the app WebView. */
public class PhoneActivity extends Activity implements View.OnClickListener, DialogInterface.OnClickListener,
        Runnable, SensorEventListener {
    static final String UA_SUFFIX = " RayApp/1";
    private static final int SCAN = 1;
    private static final String LAST_DEVICE = "ray_phone_device";
    private static final int RAY_BG = Color.rgb(2, 6, 13);
    private static final int RAY_TEXT = Color.rgb(231, 245, 250);
    private static final int RAY_CYAN = Color.rgb(60, 226, 239);
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.\\-]{1,253}");
    private static final Pattern KEY = Pattern.compile("[0-9a-f]{1,128}");

    private final Handler main = new Handler(Looper.getMainLooper());
    private final float[] rotation = new float[9];
    private final float[] orientation = new float[3];
    private SharedPreferences prefs;
    private WebView web;
    private AppWebClient client;
    private TextView error;
    private Button selector;
    private Button motionButton;
    private LinearLayout motionRow;
    private LinearLayout header;
    private String[] menuDeviceIds;
    private Discovery discovery;
    private EditText pairingInput;
    private SeekBar sensitivityInput;
    private CheckBox volumeMotionInput;
    private SensorManager sensors;
    private Sensor rotationSensor;
    private Sensor gyroSensor;
    private Sensor accelSensor;
    private boolean localPage = true;
    /** With no TV: the local device manager (app.html) instead of the RAY connect screen. */
    private boolean manager;
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
    private boolean failed;
    private int retries;

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (resumed && rayPage && motionEnabled && prefs.getBoolean("ray_motion_volume_keys", false)
                && (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
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
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        body.addView(layout, new FrameLayout.LayoutParams(-1, -1));
        header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), dp(4), dp(8), dp(4));
        layout.addView(header, new LinearLayout.LayoutParams(-1, -2));
        selector = button(t("Choose TV", "Scegli TV"));
        selector.setOnClickListener(this);
        header.addView(selector, new LinearLayout.LayoutParams(0, -2, 1));
        Button add = button(t("Add", "Aggiungi"));
        add.setTag("add");
        add.setOnClickListener(this);
        header.addView(add);
        Button manage = button(t("Manage", "Gestisci"));
        manage.setTag("manage");
        manage.setOnClickListener(this);
        header.addView(manage);
        motionRow = new LinearLayout(this);
        motionRow.setGravity(Gravity.CENTER);
        layout.addView(motionRow, new LinearLayout.LayoutParams(-1, -2));
        motionButton = button(t("Motion: off", "Movimento: spento"));
        motionButton.setTag("motion");
        motionButton.setOnClickListener(this);
        motionRow.addView(motionButton);
        Button recenter = button(t("Recenter", "Ricentra"));
        recenter.setTag("recenter");
        recenter.setOnClickListener(this);
        motionRow.addView(recenter);
        Button sensitivity = button(t("Sensitivity", "Sensibilità"));
        sensitivity.setTag("sensitivity");
        sensitivity.setOnClickListener(this);
        motionRow.addView(sensitivity);

        web = new WebView(this);
        web.setBackgroundColor(RAY_BG);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setUserAgentString(settings.getUserAgentString() + UA_SUFFIX);
        client = new AppWebClient(this);
        layout.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        web.setWebViewClient(client);

        error = new TextView(this);
        error.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        error.setTextColor(RAY_TEXT);
        error.setGravity(Gravity.CENTER);
        error.setBackgroundColor(RAY_BG);
        int pad = dp(32);
        error.setPadding(pad, pad, pad, pad);
        error.setOnClickListener(this);
        error.setVisibility(View.GONE);
        body.addView(error, new FrameLayout.LayoutParams(-1, -1));
        Compat.padForSystemBars(body);
        setContentView(body);

        discovery = new Discovery(this, prefs);
        sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
        // air mouse: raw gyroscope; the game rotation vector (no magnetometer) only when there is no gyroscope
        rotationSensor = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        gyroSensor = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        accelSensor = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (rotationSensor == null && gyroSensor == null) {
            motionButton.setText(t("Motion unavailable", "Movimento non disponibile"));
            motionButton.setEnabled(false);
        }
        handleIntent(getIntent());
        if (localPage) selectSavedDevice();
        refreshSelector();
        updateMotionControls();
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
        if (web.getUrl() == null) startLoad();
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
        if (rayPage) web.evaluateJavascript("window.RayNativeMotionEnabled&&window.RayNativeMotionEnabled(false,true)", null);
        web.onPause();
        main.removeCallbacks(this);
        discovery.stop();
        Compat.startForegroundService(this, new Intent(this, DimService.class).putExtra("local", false));
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(RAY_CYAN);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setAllCaps(false);
        b.setBackgroundTintList(ColorStateList.valueOf(RAY_BG));
        return b;
    }

    private void startLoad() {
        retries = 0;
        main.removeCallbacks(this);
        main.postDelayed(this, 400);
    }

    @Override
    public void run() {
        failed = false;
        error.setVisibility(View.GONE);
        JSONObject device = selectedDevice();
        if (device == null) {
            localPage = true;
            client.origin = "http://127.0.0.1:" + RemoteServer.PORT;
            rayPage = false;
            stopMotion();
            updateMotionControls();
            loadLocal();
        } else {
            String target = target(device);
            if (target == null) {
                localPage = true;
                Toast.makeText(this, t("Saved TV data is invalid", "Dati TV salvati non validi"), Toast.LENGTH_SHORT).show();
                client.origin = "http://127.0.0.1:" + RemoteServer.PORT;
                loadLocal();
                return;
            }
            localPage = false;
            client.origin = "http://" + device.optString("host") + ":" + device.optInt("port");
            rayPage = true;
            calibrated = false;
            updateMotionControls();
            web.loadUrl(target);
            refreshSelector();
            startMotion();
        }
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

    private void selectSavedDevice() {
        JSONArray all = Devices.load(prefs);
        for (int i = 0; i < all.length(); i++) {
            JSONObject device = all.optJSONObject(i);
            if (device != null && target(device) != null) {
                prefs.edit().putString(LAST_DEVICE, id(device)).apply();
                localPage = false;
                return;
            }
        }
    }

    private void refreshSelector() {
        JSONObject device = selectedDevice();
        selector.setText(device == null ? t("Choose TV", "Scegli TV")
                : t("TV: ", "TV: ") + device.optString("name", device.optString("host")));
    }

    private void showDevices() {
        JSONArray all = Devices.load(prefs);
        java.util.ArrayList<String> labels = new java.util.ArrayList<String>();
        java.util.ArrayList<String> ids = new java.util.ArrayList<String>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject device = all.optJSONObject(i);
            if (device != null && target(device) != null) {
                labels.add(device.optString("name", device.optString("host")));
                ids.add(id(device));
            }
        }
        menuDeviceIds = ids.toArray(new String[ids.size()]);
        labels.add(t("Add TV", "Aggiungi TV"));
        labels.add(t("Local device manager", "Gestione locale dispositivi"));
        new AlertDialog.Builder(this).setItems(labels.toArray(new String[labels.size()]), this).show();
    }

    private void showPairingDialog() {
        pairingInput = new EditText(this);
        pairingInput.setSingleLine(true);
        pairingInput.setHint("http://TV:8766/ray#k=…");
        new AlertDialog.Builder(this).setTitle(t("Add RAY TV", "Aggiungi TV RAY"))
                .setMessage(t("Paste the TV pairing link", "Incolla il link di abbinamento della TV"))
                .setView(pairingInput)
                .setNegativeButton(t("Cancel", "Annulla"), this)
                .setPositiveButton(t("Connect", "Connetti"), this).show();
    }

    /** The RAY connect screen: TVs found on the network, their code or the QR. */
    private void showConnect() {
        localPage = true;
        manager = false;
        refreshSelector();
        startLoad();
    }

    void addPairingLink(String value) {
        Uri uri = Uri.parse(value.trim());
        String deviceId = Devices.addLink(prefs, uri);
        JSONObject device = deviceId == null ? null : Devices.byId(prefs, deviceId);
        if (device == null || target(device) == null) {
            Toast.makeText(this, t("Invalid pairing link", "Link di abbinamento non valido"), Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.edit().putString(LAST_DEVICE, deviceId).apply();
        localPage = false;
        refreshSelector();
        startLoad();
    }

    private void showLocalManager() {
        localPage = true;
        manager = true;
        rayPage = false;
        stopMotion();
        updateMotionControls();
        client.origin = "http://127.0.0.1:" + RemoteServer.PORT;
        loadLocal();
        refreshSelector();
    }

    /** The loopback server is started by DimService asynchronously: wait (up to ~5 s) until it accepts before loading. */
    private volatile int localGen;

    private void loadLocal() {
        final int gen = ++localGen;
        final String url = manager ? client.origin + "/app.html?r=" + System.currentTimeMillis() + "#k=" + DimService.pairingKey(prefs)
                : client.origin + "/ray#local=" + DimService.pairingKey(prefs);
        new Thread(new Runnable() {
            @Override public void run() {
                for (int i = 0; i < 25 && gen == localGen; i++) {
                    java.net.Socket s = new java.net.Socket();
                    try {
                        s.connect(new java.net.InetSocketAddress("127.0.0.1", RemoteServer.PORT), 200);
                        break;
                    } catch (java.io.IOException e) {
                        try { Thread.sleep(200); } catch (InterruptedException x) { return; }
                    } finally {
                        try { s.close(); } catch (java.io.IOException ignored) { }
                    }
                }
                main.post(new Runnable() {
                    @Override public void run() {
                        if (!isDestroyed() && localPage && gen == localGen) web.loadUrl(url);
                    }
                });
            }
        }).start();
    }

    void loadFailed() {
        failed = true;
        if (retries < 5) {
            retries++;
            main.postDelayed(this, 600);
        } else {
            error.setText(t("The page could not load.\n\nTap to retry.", "Impossibile caricare la pagina.\n\nTocca per riprovare."));
            error.setVisibility(View.VISIBLE);
        }
    }

    void pageDone(String url) {
        rayPage = isRayPageUrl(url);
        if (!failed) retries = 0;
        if (!rayPage) stopMotion();
        updateMotionControls();
        syncMotionState();
        startMotion();
    }

    void pageStarted() {
        rayPage = false;
        stopMotion();
        updateMotionControls();
    }

    boolean isRayPageUrl(String url) {
        if (localPage || url == null) return false;
        Uri page = Uri.parse(url);
        Uri origin = Uri.parse(client.origin);
        return origin.getScheme().equals(page.getScheme()) && origin.getHost().equals(page.getHost())
                && origin.getPort() == page.getPort() && "/ray".equals(page.getPath());
    }

    void appAction(String what) {
        try {
            if ("settings/motion".equals(what)) showSensitivity();
            else if ("devices/select".equals(what)) showDevices();
            else if ("scan".equals(what)) scan();
            else if ("add".equals(what)) showPairingDialog();
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
        } catch (RuntimeException ignored) { }
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
        Uri uri = text == null ? null : Uri.parse(text.trim());
        String id = Devices.addLink(prefs, uri);
        JSONObject device = id == null ? null : Devices.byId(prefs, id);
        if (device == null || target(device) == null) {
            web.evaluateJavascript("window.mbNotice && window.mbNotice('badLink')", null);
            return;
        }
        prefs.edit().putString(LAST_DEVICE, id).apply();
        localPage = false;
        refreshSelector();
        startLoad();
    }

    private void handleIntent(Intent intent) {
        Uri uri = intent == null ? null : intent.getData();
        if (uri == null) return;
        String id = Devices.addLink(prefs, uri);
        setIntent(new Intent());
        JSONObject device = id == null ? null : Devices.byId(prefs, id);
        if (device == null || target(device) == null) return;
        String host = device.optString("host");
        int port = device.optInt("port");
        if (device.optString("name").equals(host))
            new Thread(new InfoFetch(this, main, host, port, device.optString("key"))).start();
        prefs.edit().putString(LAST_DEVICE, id).apply();
        localPage = false;
        if (web != null) {
            refreshSelector();
            startLoad();
        }
    }

    void deviceNamed(String host, int port, String name) {
        JSONObject device = Devices.byId(prefs, host + ":" + port);
        if (device != null && device.optString("name").equals(host)) {
            Devices.rename(prefs, host, port, Devices.cleanName(name));
            refreshSelector();
            web.evaluateJavascript("window.mbDevicesChanged && window.mbDevicesChanged()", null);
        }
    }

    @Override
    public void onClick(View view) {
        Object tag = view.getTag();
        if ("add".equals(tag)) showPairingDialog();
        else if ("manage".equals(tag)) showLocalManager();
        else if ("motion".equals(tag)) {
            setMotionEnabled(!motionEnabled);
        } else if ("recenter".equals(tag)) {
            recenter();
        } else if ("sensitivity".equals(tag)) showSensitivity();
        else if (view == selector) showDevices();
        else startLoad();
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        if (which == DialogInterface.BUTTON_NEGATIVE) {
            pairingInput = null;
            sensitivityInput = null;
            volumeMotionInput = null;
            menuDeviceIds = null;
            return;
        }
        if (pairingInput != null) {
            addPairingLink(pairingInput.getText().toString());
            pairingInput = null;
        } else if (sensitivityInput != null) {
            sens = Math.max(1, sensitivityInput.getProgress());
            prefs.edit().putInt("ray_motion_sensitivity", sens)
                    .putBoolean("ray_motion_volume_keys", volumeMotionInput.isChecked()).apply();
            sensitivityInput = null;
            volumeMotionInput = null;
        } else if (menuDeviceIds != null) {
            if (which == menuDeviceIds.length) showConnect();
            else if (which == menuDeviceIds.length + 1) showLocalManager();
            else if (which >= 0 && which < menuDeviceIds.length) {
                prefs.edit().putString(LAST_DEVICE, menuDeviceIds[which]).apply();
                localPage = false;
                refreshSelector();
                startLoad();
            }
            menuDeviceIds = null;
        }
    }

    private void showSensitivity() {
        sensitivityInput = new SeekBar(this);
        sensitivityInput.setMax(400);
        sensitivityInput.setProgress(Math.max(1, Math.min(400, prefs.getInt("ray_motion_sensitivity", 100))));
        LinearLayout settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.VERTICAL);
        settings.setPadding(dp(16), 0, dp(16), 0);
        settings.addView(sensitivityInput);
        volumeMotionInput = new CheckBox(this);
        volumeMotionInput.setText(t("Use volume keys for motion", "Usa tasti volume per Movimento"));
        volumeMotionInput.setTextColor(RAY_TEXT);
        volumeMotionInput.setChecked(prefs.getBoolean("ray_motion_volume_keys", false));
        settings.addView(volumeMotionInput);
        TextView volumeHelp = new TextView(this);
        volumeHelp.setText(t("Volume −: select · Volume +: recenter", "Volume −: seleziona · Volume +: ricentra"));
        volumeHelp.setTextColor(RAY_TEXT);
        settings.addView(volumeHelp);
        new AlertDialog.Builder(this).setTitle(t("Motion sensitivity", "Sensibilità movimento"))
                .setView(settings).setNegativeButton(t("Cancel", "Annulla"), this)
                .setPositiveButton(t("Done", "Fatto"), this).show();
    }

    private void setMotionEnabled(boolean enabled) {
        motionEnabled = enabled;
        if (motionButton.isEnabled())
            motionButton.setText(motionEnabled ? t("Motion: on", "Movimento: attivo") : t("Motion: off", "Movimento: spento"));
        if (motionEnabled) startMotion(); else stopMotion();
        syncMotionState();
    }

    private void updateMotionControls() {
        header.setVisibility(rayPage ? View.GONE : View.VISIBLE);
        motionRow.setVisibility(View.GONE);
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
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
}
