package io.github.asteroidb612zs.hondatadash;

import android.content.Context;
import android.graphics.Typeface;

/** Pinned instrument fonts, shared by all views; compatible with API 17. */
final class DashboardTypeface {
    private static Typeface bold, boldItalic, scale;

    private DashboardTypeface() { }

    /** Small upright scale numerals use real, lighter outlines, never synthetic thinning. */
    static synchronized Typeface getScale(Context context) {
        if (scale != null) return scale;
        try {
            scale = Typeface.createFromAsset(context.getAssets(), "fonts/RobotoCondensed-Regular.ttf");
        } catch (RuntimeException unavailable) {
            scale = Typeface.create("sans-serif-condensed", Typeface.NORMAL);
        }
        return scale;
    }

    static synchronized Typeface get(Context context, boolean italic) {
        Typeface cached = italic ? boldItalic : bold;
        if (cached != null) return cached;
        try {
            cached = Typeface.createFromAsset(context.getAssets(), italic
                    ? "fonts/RobotoCondensed-BoldItalic.ttf" : "fonts/RobotoCondensed-Bold.ttf");
        } catch (RuntimeException unavailable) {
            // Keep telemetry usable if an OEM cannot load an asset font. The same
            // ink fitting and sign/digit cells also cover this fallback.
            cached = Typeface.create("sans-serif-condensed", italic ? Typeface.BOLD_ITALIC : Typeface.BOLD);
        }
        if (italic) boldItalic = cached; else bold = cached;
        return cached;
    }
}
