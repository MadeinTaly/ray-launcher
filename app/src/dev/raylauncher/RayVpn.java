package dev.raylauncher;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import org.json.JSONObject;

final class RayVpn {
    private static ConnectivityManager manager;
    private static ConnectivityManager.NetworkCallback callback;

    static final String PREF = "ray_vpn_app";

    /** {known, active (a VPN is the TV's network), apps: [{pkg, name}] installed VPN apps, preferred: the one opened last}. */
    static JSONObject state(Context context) {
        JSONObject state = new JSONObject();
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            state.put("known", true).put("active", caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
            org.json.JSONArray apps = new org.json.JSONArray();
            java.util.HashSet<String> seen = new java.util.HashSet<String>();
            android.content.pm.PackageManager pm = context.getPackageManager();
            for (android.content.pm.ResolveInfo r : pm.queryIntentServices(new android.content.Intent("android.net.VpnService"), 0)) {
                String pkg = r.serviceInfo.packageName;
                if (pkg.equals(context.getPackageName()) || !seen.add(pkg)) continue;
                if (pm.getLeanbackLaunchIntentForPackage(pkg) == null && pm.getLaunchIntentForPackage(pkg) == null) continue;
                apps.put(new JSONObject().put("pkg", pkg).put("name", String.valueOf(r.serviceInfo.applicationInfo.loadLabel(pm))));
            }
            String preferred = context.getSharedPreferences(DimService.PREFS, Context.MODE_PRIVATE).getString(PREF, "");
            state.put("apps", apps).put("preferred", seen.contains(preferred) ? preferred : "");
        } catch (Exception unavailable) {
            try { state.put("known", false).put("active", false).put("apps", new org.json.JSONArray()).put("preferred", ""); } catch (Exception ignored) { }
        }
        return state;
    }

    /** Opens an installed VPN app (and remembers it), or the system VPN settings when pkg is empty. */
    static boolean open(Context context, String pkg) {
        try {
            android.content.Intent intent;
            if (pkg == null || pkg.isEmpty()) intent = new android.content.Intent(android.provider.Settings.ACTION_VPN_SETTINGS);
            else {
                boolean listed = false;
                org.json.JSONArray apps = state(context).optJSONArray("apps");
                for (int i = 0; apps != null && i < apps.length(); i++) listed |= pkg.equals(apps.getJSONObject(i).optString("pkg"));
                if (!listed) return false;
                android.content.pm.PackageManager pm = context.getPackageManager();
                intent = pm.getLeanbackLaunchIntentForPackage(pkg);
                if (intent == null) intent = pm.getLaunchIntentForPackage(pkg);
                if (intent == null) return false;
                context.getSharedPreferences(DimService.PREFS, Context.MODE_PRIVATE).edit().putString(PREF, pkg).apply();
            }
            context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Exception unavailable) { // ActivityNotFound, Security...
            return false;
        }
    }

    static void start(Context context) {
        stop();
        manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { RaySocket.stateChanged(); }
            @Override public void onLost(Network network) { RaySocket.stateChanged(); }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) { RaySocket.stateChanged(); }
        };
        try {
            manager.registerNetworkCallback(new NetworkRequest.Builder()
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback);
        } catch (RuntimeException unavailable) { callback = null; }
    }

    static void stop() {
        if (manager != null && callback != null) {
            try { manager.unregisterNetworkCallback(callback); } catch (RuntimeException ignored) { }
        }
        callback = null;
        manager = null;
    }
}
