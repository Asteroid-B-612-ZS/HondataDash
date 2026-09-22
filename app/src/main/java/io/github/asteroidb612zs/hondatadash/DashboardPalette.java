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
    static final int BACKGROUND = 0xFF030609, CARD = 0xFF091017;
    static final int PRIMARY = 0xFFF2F5F7, SECONDARY = 0xFFADBDC9;
    static final int LINE = 0xFF71808C, LINE_INNER = 0xFF2A3947;

    // Main-value / alert colours. Keep these familiar and high contrast.
    static final int CYAN = 0xFF65C9E8, GREEN = 0xFF70D65B;
    static final int AMBER = 0xFFFFBF47, ORANGE = 0xFFFF7A22, RED = 0xFFF34A43;
    static final int HONDA_RED = 0xFFE6002D, LIVE = GREEN;
    // Keep PURPLE as the legacy semantic input; CRITICAL is its final presentation colour.
    static final int PURPLE = 0xFFB040FF, CRITICAL = 0xFFFF8173;

    // OEM scale-bar colours: intentionally darker and less saturated than the
    // main digits. The bar should support a glance, never compete with the value.
    static final int SCALE_CYAN = 0xFF447F9A;
    static final int SCALE_BLUE = 0xFF3B6480;
    static final int SCALE_NEUTRAL = 0xFF718591;
    static final int SCALE_GREEN = 0xFF598566;
    // Progressive performance-load ramp used by MAP. These are deliberately
    // less saturated than the main-value safety colours: high load should feel
    // alive, while amber/red remain reserved for caution/danger.
    static final int SCALE_LOAD_LOW = 0xFF3F6658;
    static final int SCALE_LOAD_MID = 0xFF548B69;
    static final int SCALE_LOAD_HIGH = 0xFF76A873;
    static final int SCALE_LOAD_PEAK = 0xFF9DBB79;
    static final int SCALE_WARM = 0xFF947953;
    static final int SCALE_AMBER = 0xFFAD8445;
    static final int SCALE_RED = 0xFFA0524C;
    static final int SCALE_PURPLE = 0xFFB56C61;
    static final int SCALE_TARGET = 0xFF59B9D8;

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
            0xFF5E849A,  // outer pair: cool steel blue
            0xFF82B8CF,  // light cool blue
            0xFFE5F1F7,  // cold white
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
        if (color == PURPLE) return CRITICAL;
        return color;
    }
}
