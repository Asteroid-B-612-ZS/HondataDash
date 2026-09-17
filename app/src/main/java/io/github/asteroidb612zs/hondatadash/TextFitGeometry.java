package io.github.asteroidb612zs.hondatadash;

/** Pixel geometry shared by the Android renderer and JVM boundary tests. */
final class TextFitGeometry {
    static final class Result {
        float scaleX, scaleY, x, baseline;
    }

    private TextFitGeometry() { }

    static void fit(Result out, float left, float top, float width, float height,
                    float actualWidth, float referenceWidth, float fontTop, float fontBottom,
                    float horizontalAlignment, float verticalAlignment) {
        fit(out, left, top, width, height, actualWidth, referenceWidth, fontTop, fontBottom,
                horizontalAlignment, verticalAlignment, .72f);
    }

    static void fit(Result out, float left, float top, float width, float height,
                    float actualWidth, float referenceWidth, float fontTop, float fontBottom,
                    float horizontalAlignment, float verticalAlignment, float aspectPreference) {
        float lineHeight = fontBottom - fontTop;
        float fitWidth = Math.max(actualWidth, referenceWidth);
        if (width <= 0f || height <= 0f || fitWidth <= 0f || lineHeight <= 0f) {
            out.scaleX = out.scaleY = 0f;
            out.x = left;
            out.baseline = top;
            return;
        }
        float sy = Math.min(1f, height / lineHeight);
        float sx = Math.min(sy, width / fitWidth);
        // Avoid extremely stretched tall glyphs for unexpected long strings. This is
        // an aspect-ratio preference, NEVER a minimum scale that can force overflow.
        sy = Math.min(sy, sx / aspectPreference);
        out.scaleX = sx;
        out.scaleY = sy;
        out.x = left + Math.max(0f, width - actualWidth * sx) * horizontalAlignment;
        out.baseline = top + Math.max(0f, height - lineHeight * sy) * verticalAlignment
                - fontTop * sy;
    }
}
