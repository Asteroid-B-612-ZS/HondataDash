package io.github.asteroidb612zs.hondatadash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/**
 * Single-line, non-editable instrument text for API 17+. Measures the actual
 * fallback typeface's ink AND line box, not only TextView's advance width.
 * Fixed digit/sign cells and reference strings prevent breathing as values change.
 * TextView still owns text, accessibility, colors, alpha and touch handling.
 * No scrolling, ellipsis, lost sign or value rounding is used to make text fit.
 */
public class FittedTextView extends TextView {
    private final Paint drawPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect ink = new Rect();
    private final Map<Character, Glyph> glyphs = new HashMap<Character, Glyph>();
    private final TextFitGeometry.Result fit = new TextFitGeometry.Result();
    private boolean tabularDigits;
    private boolean compactSign;
    private char scaledPrefix;
    private float prefixSizeScale = 1f;
    private String[] references = new String[0];
    private Typeface measuredTypeface;
    private float measuredSize = -1f, measuredSkew;
    private boolean measuredBold;
    private float digitWidth, digitTop, digitBottom, signWidth, fontTop, fontBottom, referenceWidth;
    private float numericTop, numericBottom;
    private boolean metricsDirty = true;

    private static final class Glyph {
        final String text;
        final float width, origin, sizeScale, xScale, baselineOffset;
        Glyph(String text, float width, float origin, float sizeScale, float xScale, float baselineOffset) {
            this.text = text;
            this.width = width;
            this.origin = origin;
            this.sizeScale = sizeScale;
            this.xScale = xScale;
            this.baselineOffset = baselineOffset;
        }
    }

    public FittedTextView(Context context) { super(context); init(); }
    public FittedTextView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public FittedTextView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle); init();
    }

    private void init() {
        setSingleLine(true);
        setHorizontallyScrolling(false);
        setEllipsize(null);
        setIncludeFontPadding(false);
        Typeface original = getTypeface();
        boolean italic = (original != null && original.isItalic()) || getPaint().getTextSkewX() != 0f;
        setTypeface(DashboardTypeface.get(getContext(), italic));
        // XML synthetic styling must not be added on top of a real bold/italic face.
        getPaint().setFakeBoldText(false);
        getPaint().setTextSkewX(0f);
    }

    /** TextView's unscaled internal Layout must never scroll our fitted drawing. */
    @Override public void scrollTo(int x, int y) {
        super.scrollTo(0, 0);
    }

    /** Configure only when entering a different numeric/semantic display profile. */
    public void setFitReference(boolean tabular, boolean compact, String... samples) {
        tabularDigits = tabular;
        compactSign = compact;
        references = samples == null ? new String[0] : samples.clone();
        metricsDirty = true;
        invalidate();
    }


    /** Optional optical prefix hierarchy (RC7 Ethanol "E"): digits remain full-size. */
    public void setPrefixScale(char prefix, float scale) {
        float safe = Math.max(0.70f, Math.min(1.0f, scale));
        if (scaledPrefix == prefix && Math.abs(prefixSizeScale - safe) < 0.001f) return;
        scaledPrefix = prefix;
        prefixSizeScale = safe;
        metricsDirty = true;
        invalidate();
    }

    public void clearPrefixScale() {
        if (scaledPrefix == 0 && prefixSizeScale == 1f) return;
        scaledPrefix = 0;
        prefixSizeScale = 1f;
        metricsDirty = true;
        invalidate();
    }

    private void prepareMetrics() {
        Paint source = getPaint();
        if (!metricsDirty && measuredTypeface == source.getTypeface()
                && measuredSize == getTextSize() && measuredSkew == source.getTextSkewX()
                && measuredBold == source.isFakeBoldText()) return;
        measuredTypeface = source.getTypeface();
        measuredSize = getTextSize();
        measuredSkew = source.getTextSkewX();
        measuredBold = source.isFakeBoldText();
        drawPaint.set(source);
        drawPaint.setTextScaleX(1f);
        drawPaint.setTextAlign(Paint.Align.LEFT);
        Paint.FontMetrics fm = drawPaint.getFontMetrics();
        fontTop = fm.top;
        fontBottom = fm.bottom;
        glyphs.clear();
        digitWidth = signWidth = referenceWidth = 0f;
        digitTop = Float.POSITIVE_INFINITY;
        digitBottom = Float.NEGATIVE_INFINITY;
        numericTop = Float.POSITIVE_INFINITY;
        numericBottom = Float.NEGATIVE_INFINITY;
        // Also covers fonts with proportional digits and different +/- advances.
        for (char c = '0'; c <= '9'; c++) digitWidth = Math.max(digitWidth, glyph(c).width);
        signWidth = Math.max(glyph('+').width, glyph('-').width);
        for (String reference : references) referenceWidth = Math.max(referenceWidth, measure(reference));
        metricsDirty = false;
    }

    private Glyph glyph(char c) {
        Glyph result = glyphs.get(Character.valueOf(c));
        if (result != null) return result;
        boolean smallSign = compactSign && (c == '+' || c == '-');
        boolean tightPoint = compactSign && c == '.';
        boolean opticalPrefix = scaledPrefix != 0 && c == scaledPrefix && prefixSizeScale < 1f;
        // Keep every integer/fraction digit full-size. The Ethanol E prefix can
        // be optically subordinate without shrinking the important number.
        float sizeScale = opticalPrefix ? prefixSizeScale
                : smallSign ? 0.78f : tightPoint ? 0.62f : 1f;
        float xScale = 1f;
        drawPaint.setTextSize(measuredSize * sizeScale);
        drawPaint.setTextScaleX(xScale);
        String text = smallSign && c == '-' ? "−" : String.valueOf(c);
        drawPaint.getTextBounds(text, 0, text.length(), ink);
        // Include negative bearings and the italic overhang beyond the advance.
        float left = Math.min(0f, ink.left);
        float right = Math.max(drawPaint.measureText(text), ink.right);
        if ((smallSign || tightPoint) && ink.right > ink.left) {
            float air = measuredSize * .025f;
            left = ink.left - air / 2f;
            right = ink.right + air / 2f;
        }
        if (c >= '0' && c <= '9') {
            digitTop = Math.min(digitTop, ink.top);
            digitBottom = Math.max(digitBottom, ink.bottom);
        }
        // Small signs/prefix optically center on the digits, not low on baseline.
        float offset = (smallSign || opticalPrefix) && digitBottom > digitTop
                ? (digitTop + digitBottom - ink.top - ink.bottom) / 2f : 0f;
        result = new Glyph(text, right - left, -left, sizeScale, xScale, offset);
        glyphs.put(Character.valueOf(c), result);
        fontTop = Math.min(fontTop, ink.top + offset);
        fontBottom = Math.max(fontBottom, ink.bottom + offset);
        if (ink.right > ink.left && ink.bottom > ink.top) {
            numericTop = Math.min(numericTop, ink.top + offset);
            numericBottom = Math.max(numericBottom, ink.bottom + offset);
        }
        return result;
    }

    private float cellWidth(char c, Glyph glyph) {
        if (tabularDigits && c >= '0' && c <= '9') return digitWidth;
        if (tabularDigits && (c == '+' || c == '-')) return signWidth;
        return glyph.width;
    }

    private float measure(CharSequence text) {
        float width = 0f;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            width += cellWidth(c, glyph(c));
        }
        return width;
    }

    @Override protected void onDraw(Canvas canvas) {
        CharSequence text = getText();
        if (text == null || text.length() == 0) return;
        prepareMetrics();
        float actualWidth = measure(text);
        // A pixel guard protects anti-aliased edges, including slanted trailing 7/9.
        float guard = Math.max(1f, getResources().getDisplayMetrics().density);
        float left = getPaddingLeft() + guard;
        float top = getPaddingTop() + guard;
        float width = getWidth() - getPaddingLeft() - getPaddingRight() - guard * 2f;
        float height = getHeight() - getPaddingTop() - getPaddingBottom() - guard * 2f;
        int gravity = Gravity.getAbsoluteGravity(getGravity(), getLayoutDirection());
        int horizontal = gravity & Gravity.HORIZONTAL_GRAVITY_MASK;
        int vertical = gravity & Gravity.VERTICAL_GRAVITY_MASK;
        float hAlign = horizontal == Gravity.RIGHT ? 1f : horizontal == Gravity.CENTER_HORIZONTAL ? .5f : 0f;
        float vAlign = vertical == Gravity.BOTTOM ? 1f : vertical == Gravity.CENTER_VERTICAL ? .5f : 0f;
        // Fixed numeric profiles reserve every digit/sign/reference's actual ink,
        // instead of the empty ascender/descender space of an entire font. Unknown
        // characters were also measured above; they still cannot escape the slot.
        boolean optical = tabularDigits && numericBottom > numericTop;
        TextFitGeometry.fit(fit, left, top, width, height, actualWidth, referenceWidth,
                optical ? numericTop : fontTop, optical ? numericBottom : fontBottom, hAlign, vAlign,
                compactSign ? .66f : .72f);
        if (fit.scaleX <= 0f || fit.scaleY <= 0f) return;

        drawPaint.setColor(getCurrentTextColor());
        int save = canvas.save();
        // This uses the current measured width AND height on every draw, including
        // navigation-bar/configuration changes with no new telemetry frame.
        canvas.translate(fit.x, fit.baseline);
        canvas.scale(fit.scaleX, fit.scaleY);
        float cursor = 0f;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            Glyph glyph = glyph(c);
            float cell = cellWidth(c, glyph);
            drawPaint.setTextSize(measuredSize * glyph.sizeScale);
            drawPaint.setTextScaleX(glyph.xScale);
            canvas.drawText(glyph.text, cursor + (cell - glyph.width) / 2f + glyph.origin,
                    glyph.baselineOffset, drawPaint);
            cursor += cell;
        }
        canvas.restoreToCount(save);
    }
}
