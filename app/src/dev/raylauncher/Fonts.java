package dev.raylauncher;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * The app's typefaces: Figtree for text, Instrument Sans for the clock and the heads (both SIL OFL, in
 * assets/fonts as variable fonts). Falls back to the system sans when a file cannot be read.
 */
final class Fonts {
    private Fonts() {
    }

    private static final java.util.HashMap<String, Typeface> cache = new java.util.HashMap<>();

    private static synchronized Typeface load(Context c, String file, int weight, String fallback) {
        String key = file + weight;
        Typeface t = cache.get(key);
        if (t != null) return t;
        try {
            t = new Typeface.Builder(c.getAssets(), "fonts/" + file).setFontVariationSettings("'wght' " + weight).build();
        } catch (RuntimeException unreadable) {
            t = null;
        }
        if (t == null) t = Typeface.create(fallback, Typeface.NORMAL);
        cache.put(key, t);
        return t;
    }

    static Typeface regular(Context c) { return load(c, "Figtree.ttf", 400, "sans-serif"); }

    /** Bold-ish text: the medium weight. */
    static Typeface bold(Context c) { return load(c, "Figtree.ttf", 500, "sans-serif-medium"); }

    static Typeface light(Context c) { return load(c, "Figtree.ttf", 300, "sans-serif-light"); }

    /** Clock and heads: 400, or 500 for small titles. */
    static Typeface display(Context c, int weight) { return load(c, "InstrumentSans.ttf", weight, "sans-serif"); }

    static Typeface mono(Context c) { return Typeface.MONOSPACE; }

    /** Sets the app typeface on every text of a view tree, keeping bold where it was bold. */
    static void apply(View v) {
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            Typeface current = t.getTypeface();
            boolean isBold = current != null && current.isBold();
            t.setTypeface(isBold ? bold(v.getContext()) : regular(v.getContext()));
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) apply(g.getChildAt(i));
        }
    }
}
