package dev.raylauncher;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Keeps navigation inside the app page, passes raylauncher-app:// links to the activity; any other link opens in the browser. A top-level class, see MainActivity. */
final class AppWebClient extends WebViewClient {
    private final PhoneActivity activity;
    String origin = "";

    AppWebClient(PhoneActivity activity) {
        this.activity = activity;
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        Uri uri = Uri.parse(url);
        if ("ray-app".equals(uri.getScheme())) {
            String path = uri.getPath();
            if (("motion".equals(uri.getHost()) || "settings".equals(uri.getHost()) || "devices".equals(uri.getHost()))
                    && path != null && path.length() > 1
                    && activity.isRayPageUrl(view.getUrl()))
                activity.appAction(uri.getHost() + path);
            return true;
        }
        if (url.equals(origin) || url.startsWith(origin + "/")) return false;
        if ("raylauncher".equals(uri.getScheme())) { // pairing link from the RAY connect screen (found TV + its code)
            activity.addPairingLink(url);
            return true;
        }
        if (url.startsWith("raylauncher-app://")) {
            activity.appAction(Uri.parse(url).getHost());
            return true;
        }
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException ignored) {
        }
        return true;
    }

    @Override
    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
        if (failingUrl != null && failingUrl.startsWith(origin)) activity.loadFailed();
    }

    /** A 404/5xx on the page itself (server not up yet, stale server) would show its raw text: retry instead. */
    @Override
    public void onReceivedHttpError(WebView view, android.webkit.WebResourceRequest request, android.webkit.WebResourceResponse response) {
        if (request.isForMainFrame() && response.getStatusCode() >= 400) activity.loadFailed();
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        activity.pageDone(url);
    }

    @Override
    public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
        activity.pageStarted();
    }
}
