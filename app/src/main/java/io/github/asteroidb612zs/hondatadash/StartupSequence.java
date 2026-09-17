package io.github.asteroidb612zs.hondatadash;

/** Pure visual clock. No data-source, engine, connection or real RPM writes. */
final class StartupSequence {
    static final long DURATION_MS = 2900;
    static final String SIGNATURE = "Designed by ZhouQiZhi";
    static float ease(long elapsed, long start, long duration) {
        float x = Math.max(0f, Math.min(1f, (elapsed - start) / (float) duration));
        float inv = 1f - x;
        return 1f - inv * inv * inv;
    }
    static float brand(long t) { return 1f - ease(t, 1250, 320); }
    static float reveal(long t) { return ease(t, 2300, 600); }
    static float shell(long t, int group) {
        return ease(t, group == 0 ? 1400 : group == 1 ? 1550 : 1700, 260);
    }
    static float labels(long t) { return ease(t, 1770, 260); }
    static float scales(long t) { return ease(t, 1900, 250); }
    // Retire stand-in text BEFORE the curtain reveals real values/status.
    // Crossfading both strings together would put -- across live numerals.
    static float placeholders(long t) { return labels(t) * (1f - ease(t, 2180, 120)); }
    static float checkStatus(long t) { return shell(t, 0) * (1f - ease(t, 2180, 120)); }
    /** V2.0 5+5 self-check sweep: stage 0→1→2→3→4→5→4→3→2→1→0 in the same 550ms window. */
    static int lampStage(long t) {
        if (t < 1750 || t >= 2300) return 0;
        return t < 2080 ? Math.min(5, (int) ((t - 1750) * 5 / 330) + 1)
                : Math.max(0, 5 - (int) ((t - 2080) * 5 / 220));
    }
}
