package dev.raylauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import java.io.InputStream;
import java.util.HashMap;

/** Themed icon loader: &lt;skin folder&gt;/icons/&lt;name&gt;.png, null when missing. */
final class RayIcons {
    private static final HashMap<String, Bitmap> CACHE = new HashMap<String, Bitmap>();
    private static final Bitmap MISSING = Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8);

    private RayIcons() {}

    static synchronized void clear() { CACHE.clear(); }

    static synchronized Drawable get(Context c, RaySkin skin, String name) {
        String key = skin.dir + "/" + skin.iconDir + "/" + name;
        Bitmap b = CACHE.get(key);
        if (b == null) {
            try {
                InputStream in;
                try { in = skin.open(c, skin.iconDir + "/" + name + ".png"); } catch (java.io.IOException none) { in = skin.open(c, "icons/" + name + ".png"); }
                try { b = BitmapFactory.decodeStream(in); } finally { in.close(); }
            } catch (Exception e) { b = null; }
            CACHE.put(key, b == null ? MISSING : b);
        }
        if (b == null || b == MISSING) return null;
        return new BitmapDrawable(c.getResources(), b);
    }
}
