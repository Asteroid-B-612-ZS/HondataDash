package io.github.asteroidb612zs.hondatadash;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/**
 * V2.0: the 5+5 rev indicator owns the full header width so the two lamp
 * groups stay exactly symmetric about the header's physical centre; the wider
 * capsule renderer uses an approximately 416dp optical span at 160dpi. The
 * clickable link status keeps an 18% overlay at the right edge and can never be
 * covered by the lamp geometry.
 */
public final class HeaderLayout extends FrameLayout {
    public HeaderLayout(Context c) { super(c); }
    public HeaderLayout(Context c, AttributeSet a) { super(c, a); }
    public HeaderLayout(Context c, AttributeSet a, int s) { super(c, a, s); }

    @Override protected void onMeasure(int w, int h) {
        super.onMeasure(w, h);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            if (child.getId() == R.id.connectionStatus) {
                int width = Math.round(getMeasuredWidth() * .18f);
                child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY));
            }
        }
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            int id = child.getId();
            if (id == R.id.shiftLight) {
                child.layout(0, 0, getWidth(), getHeight());
            } else if (id == R.id.connectionStatus) {
                int width = child.getMeasuredWidth();
                child.layout(getWidth() - width, 0, getWidth(), getHeight());
            }
        }
    }
}
