package dev.raylauncher;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** The app's typefaces: the system's own (no bundled font files). */
final class Fonts {
    private Fonts() {
    }

    static Typeface regular(Context c) { return Typeface.create("sans-serif", Typeface.NORMAL); }

    /** Bold-ish text: the medium weight. */
    static Typeface bold(Context c) { return Typeface.create("sans-serif-medium", Typeface.NORMAL); }

    static Typeface light(Context c) { return Typeface.create("sans-serif-light", Typeface.NORMAL); }

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
