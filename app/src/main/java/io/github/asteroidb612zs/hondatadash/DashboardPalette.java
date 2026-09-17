package io.github.asteroidb612zs.hondatadash;

/**
 * Dashboard presentation tokens.
 *
 * Product rule:
 *   - main digits own safety/severity;
 *   - scale bars describe direction / operating region with lower saturation;
 *   - rev lights are the only intentionally high-energy decorative element.
 *
 * Legacy semantic colours are still accepted by common()/main() so threshold
 * logic elsewhere does not need to change.
 */
final class DashboardPalette {
    static final int BACKGROUND = 0xFF050A0D, CARD = 0xFF071014;
    static final int PRIMARY = 0xFFF0F3F2, SECONDARY = 0xFFA8BDC8;
    static final int LINE = 0xFF3A505B, LINE_INNER = 0xFF23353F;

    // Main-value / alert colours. Keep these familiar and high contrast.
    static final int CYAN = 0xFF27DCE6, GREEN = 0xFF70DD48;
    static final int AMBER = 0xFFFFC21A, ORANGE = 0xFFFF7A22, RED = 0xFFFF3045;
    static final int HONDA_RED = 0xFFE6002D, LIVE_RED = 0xFFFF2345;
    static final int PURPLE = 0xFFB040FF;

    // OEM scale-bar colours: intentionally darker and less saturated than the
    // main digits. The bar should support a glance, never compete with the value.
    static final int SCALE_CYAN = 0xFF397F91;
    static final int SCALE_BLUE = 0xFF3E677B;
    static final int SCALE_NEUTRAL = 0xFF667A84;
    static final int SCALE_GREEN = 0xFF5A8860;
    // Progressive performance-load ramp used by MAP. These are deliberately
    // less saturated than the main-value safety colours: high load should feel
    // alive, while amber/red remain reserved for caution/danger.
    static final int SCALE_LOAD_LOW = 0xFF456A58;
    static final int SCALE_LOAD_MID = 0xFF5D8E62;
    static final int SCALE_LOAD_HIGH = 0xFF82A85C;
    static final int SCALE_LOAD_PEAK = 0xFFA9B85B;
    static final int SCALE_WARM = 0xFF8F7552;
    static final int SCALE_AMBER = 0xFFA27D39;
    static final int SCALE_RED = 0xFF984650;
    static final int SCALE_PURPLE = 0xFF715A8A;
    static final int SCALE_TARGET = 0xFF55CDD6;

    // Kept for older preview/test code that references track tokens directly.
    static final int TRACK_CYAN = SCALE_CYAN, TRACK_BLUE = SCALE_BLUE;
    static final int TRACK_DEEP_BLUE = 0xFF31566B, TRACK_DARK = 0xFF30434D;
    static final int TRACK_GREEN = SCALE_GREEN, TRACK_YELLOW = 0xFF9C9348;
    static final int TRACK_ORANGE = 0xFFA2673A, TRACK_RED = SCALE_RED;
    static final int TRACK_DARK_RED = 0xFF66343B, TRACK_NEUTRAL = SCALE_NEUTRAL;

    /**
     * Symmetric 5+5 rev indicator. No green: the progression is intentionally
     * cool -> white -> amber -> red, closer to Honda's understated performance UI.
     */
    static final int[] RPM_PAIR = {
            0xFF5B7A8F,  // outer pair: cool steel blue
            0xFF82AABD,  // light cool blue
            0xFFD6E3E5,  // cold white
            AMBER,       // pre-shift amber
            RED          // central shift pair
    };

    private DashboardPalette() { }

    static int main(int card, int semanticColor) {
        if (semanticColor == 0xFF3FB950) {
            // IAT and MAP keep green when it is physically meaningful.
            // TRIM/IGN/A-F normal states remain cold white for long-term calmness.
            if (card == 1) return CYAN;
            return card == 2 || card == 4 ? GREEN : PRIMARY;
        }
        return common(semanticColor);
    }

    static int common(int color) {
        if (color == 0xFFE8EEF2 || color == 0xFFFFFFFF) return PRIMARY;
        if (color == 0xFF00D8FF) return CYAN;
        if (color == 0xFFD29922) return AMBER;
        if (color == 0xFFFF4444) return RED;
        if (color == 0xFF3FB950 || color == 0xFF55FF55) return GREEN;
        return color;
    }
}
