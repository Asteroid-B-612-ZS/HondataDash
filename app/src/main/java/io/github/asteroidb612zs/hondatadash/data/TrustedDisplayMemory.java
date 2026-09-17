package io.github.asteroidb612zs.hondatadash.data;

/**
 * Tiny allocation-free history used only to choose a trustworthy hold value for
 * A/F, IGN and S.TRIM. It stores already-filtered/admitted display candidates,
 * not raw ECU truth, so entering HOLD cannot accidentally freeze the last
 * contaminated edge frame.
 */
public final class TrustedDisplayMemory {
    private static final int SLOTS = 3;          // card 5..7
    private static final int CAPACITY = 24;      // < 1 KiB of primitive storage
    private static final long MIN_RECORD_MS = 40L;
    private static final long MAX_LOOKBACK_MS = 700L;
    private static final long[] LOOKBACK_MS = {160L, 100L, 160L}; // AF, IGN, S.TRIM

    private final long[][] time = new long[SLOTS][CAPACITY];
    private final float[][] value = new float[SLOTS][CAPACITY];
    private final int[] head = new int[SLOTS];
    private final int[] count = new int[SLOTS];
    private final long[] lastRecord = new long[SLOTS];
    private final float[] held = new float[SLOTS];
    private final boolean[] hasHeld = new boolean[SLOTS];

    public void record(int cardIndex, float displayValue, long now, boolean trusted) {
        int slot = slot(cardIndex);
        if (slot < 0 || !trusted || Float.isNaN(displayValue) || Float.isInfinite(displayValue)) return;
        if (lastRecord[slot] > 0L && now - lastRecord[slot] < MIN_RECORD_MS) return;
        int p = head[slot];
        time[slot][p] = now;
        value[slot][p] = displayValue;
        head[slot] = (p + 1) % CAPACITY;
        if (count[slot] < CAPACITY) count[slot]++;
        lastRecord[slot] = now;
    }

    /** Capture once on HOLD entry; repeated held frames never chase newer raw data. */
    public boolean captureHold(int cardIndex, long now) {
        int slot = slot(cardIndex);
        if (slot < 0) return false;
        if (hasHeld[slot]) return true;
        int n = count[slot];
        if (n <= 0) return false;

        float fallback = Float.NaN;
        long targetAge = LOOKBACK_MS[slot];
        long bestAge = Long.MAX_VALUE;
        float best = Float.NaN;
        for (int k = 1; k <= n; k++) {
            int p = head[slot] - k;
            if (p < 0) p += CAPACITY;
            long ts = time[slot][p];
            if (ts <= 0L) continue;
            long age = now - ts;
            if (age < 0L || age > MAX_LOOKBACK_MS) continue;
            if (Float.isNaN(fallback)) fallback = value[slot][p];
            if (age >= targetAge && age < bestAge) {
                bestAge = age;
                best = value[slot][p];
            }
        }
        float selected = !Float.isNaN(best) ? best : fallback;
        if (Float.isNaN(selected) || Float.isInfinite(selected)) return false;
        held[slot] = selected;
        hasHeld[slot] = true;
        return true;
    }

    public boolean hasHoldValue(int cardIndex) {
        int slot = slot(cardIndex);
        return slot >= 0 && hasHeld[slot];
    }

    public float getHoldValue(int cardIndex) {
        int slot = slot(cardIndex);
        return slot >= 0 && hasHeld[slot] ? held[slot] : Float.NaN;
    }

    public void releaseHold(int cardIndex) {
        int slot = slot(cardIndex);
        if (slot < 0) return;
        hasHeld[slot] = false;
        held[slot] = 0f;
    }

    public void reset() {
        for (int s = 0; s < SLOTS; s++) {
            head[s] = 0;
            count[s] = 0;
            lastRecord[s] = 0L;
            held[s] = 0f;
            hasHeld[s] = false;
            for (int i = 0; i < CAPACITY; i++) {
                time[s][i] = 0L;
                value[s][i] = 0f;
            }
        }
    }

    private static int slot(int cardIndex) {
        return cardIndex >= 5 && cardIndex <= 7 ? cardIndex - 5 : -1;
    }
}
