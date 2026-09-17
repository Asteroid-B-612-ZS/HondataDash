package io.github.asteroidb612zs.hondatadash;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

/**
 * Horizontal dashboard rows share cumulative, rounded proportional boundaries.
 * Android's sequential weight rounding can put 3/12 one pixel before 1/4.
 * These rows contain only zero-width, positive-weight cells with no margins;
 * other layouts retain LinearLayout's normal measurement and layout behavior.
 */
public final class AlignedRowLayout extends LinearLayout {
    public AlignedRowLayout(Context c) { super(c); }
    public AlignedRowLayout(Context c, AttributeSet a) { super(c, a); }
    public AlignedRowLayout(Context c, AttributeSet a, int style) { super(c, a, style); }

    static int boundary(int width, double completedWeight, double totalWeight) {
        if (width <= 0 || totalWeight <= 0) return 0;
        return Math.max(0, Math.min(width, (int) Math.round(width * completedWeight / totalWeight)));
    }

    private double alignedWeight() {
        if (getOrientation() != HORIZONTAL) return 0;
        double total = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            LayoutParams p = (LayoutParams) child.getLayoutParams();
            if (!(p.weight > 0) || Float.isInfinite(p.weight) || p.width != 0
                    || p.leftMargin != 0 || p.rightMargin != 0
                    || p.topMargin != 0 || p.bottomMargin != 0) return 0;
            total += p.weight;
        }
        return total;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        double total = alignedWeight();
        if (total <= 0 || MeasureSpec.getMode(widthSpec) != MeasureSpec.EXACTLY) return;
        int available = Math.max(0, getMeasuredWidth() - getPaddingLeft() - getPaddingRight());
        double used = 0;
        int left = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            used += ((LayoutParams) child.getLayoutParams()).weight;
            int right = boundary(available, used, total);
            child.measure(MeasureSpec.makeMeasureSpec(right - left, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(child.getMeasuredHeight(), MeasureSpec.EXACTLY));
            left = right;
        }
    }

    // LinearLayout lays out these measured widths consecutively, preserving its
    // normal vertical gravity and baseline handling without a second layout pass.
}
