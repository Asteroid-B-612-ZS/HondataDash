package io.github.asteroidb612zs.hondatadash;

/** Render-only mapping. Semantic colours and getCurrentTextColor() remain unchanged. */
final class NightPalette {
    static boolean active;
    private NightPalette() { }

    static int color(int day) {
        if (!active) return day;
        switch (day) {
            case 0xFF030609: return 0xFF000000;
            case 0xFF091017: return 0xFF020304;
            case 0xFF15222D: return 0xFF070B0F;
            case 0xFF71808C: return 0xFF2A3540;
            case 0xFF2A3947: return 0xFF111820;
            case 0xFFFFFFFF:
            case 0xFFF2F5F7: return 0xFFDCE6EC;
            case 0xFFE6EEF3: return 0xFFA9B7C1;
            case 0xFFC0CCD5: return 0xFF8999A5;
            case 0xFFADBDC9: return 0xFF7D8C98;
            default: return day; // Amber/red, HOLD/SYNC/confidence and shift lamps keep their meaning.
        }
    }
}
