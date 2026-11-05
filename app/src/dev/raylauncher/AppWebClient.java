package dev.raylauncher;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebResourceResponse;
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
        if (url.startsWith("raylauncher-app://")) {
            if (activity.isRayPageUrl(view.getUrl()) || origin.startsWith("http://127.0.0.1:"))
                activity.appAction(url.substring("raylauncher-app://".length()));
            return true;
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return true; // a page opens web links, nothing else
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (RuntimeException ignored) {
        }
        return true;
    }

    /** Only the page itself: a failing image or script must not reload anything. */
    @Override
    public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
        if (request.isForMainFrame() && request.getUrl().toString().startsWith(origin)) activity.loadFailed();
    }

    /** 403 on the page: the TV does not accept this phone's key; 404/5xx would show raw text. */
    @Override
    public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
        if (!request.isForMainFrame() || !request.getUrl().toString().startsWith(origin)) return;
        if (response.getStatusCode() == 403) activity.keyRejected();
        else if (response.getStatusCode() >= 400) activity.loadFailed();
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
