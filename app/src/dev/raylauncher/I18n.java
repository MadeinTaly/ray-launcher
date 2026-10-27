package dev.raylauncher;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * UI language: the texts are written in the code in English and Italian; any other system language is looked up in
 * assets/i18n/&lt;language&gt;.json, an object from the English text to its translation. A missing file or text falls
 * back to English. Adding a language = adding one file.
 */
final class I18n {
    private static volatile Context app;
    private static String loaded;
    private static Map<String, String> table = new HashMap<>();

    private I18n() {
    }

    /** Any activity or service calls this once, so the catalogues can be read. */
    static void init(Context context) {
        if (app == null) app = context.getApplicationContext();
    }

    static String t(String en, String it) {
        String lang = Locale.getDefault().getLanguage();
        if ("it".equals(lang)) return it;
        if ("en".equals(lang)) return en;
        String v = table(lang).get(en);
        return v != null ? v : en;
    }

    private static synchronized Map<String, String> table(String lang) {
        if (lang.equals(loaded)) return table;
        Map<String, String> m = new HashMap<>();
        Context c = app;
        if (c != null) try (InputStream in = c.getAssets().open("i18n/" + lang + ".json")) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            JSONObject o = new JSONObject(b.toString("UTF-8"));
            for (Iterator<String> k = o.keys(); k.hasNext(); ) { String key = k.next(); m.put(key, o.optString(key, key)); }
        } catch (Exception noCatalogue) { }
        if (c != null) loaded = lang; // without a context, try again next time
        table = m;
        return m;
    }
}
