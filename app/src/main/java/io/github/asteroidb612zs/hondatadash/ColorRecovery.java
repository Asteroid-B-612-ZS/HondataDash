package io.github.asteroidb612zs.hondatadash;

import java.util.Arrays;

/**
 * Small severity latch used by the instrument colour layer.
 *
 * Recovery is deliberately slower than escalation. RC7 also supports an
 * optional attack persistence so highly dynamic health indicators (A/F colour,
 * S.TRIM colour) do not react to a single frame while true sustained problems
 * still surface quickly.
 */
final class ColorRecovery {
    static final long RECOVERY_MS = 400L;
    private final int[] current = new int[8];
    private final int[] candidate = new int[8];
    private final long[] since = new long[8];

    ColorRecovery() {
        Arrays.fill(current, -1);
        Arrays.fill(candidate, -1);
    }

    int update(int card, int severity, long now) {
        return update(card, severity, now, 0L);
    }

    int update(int card, int severity, long now, long attackMs) {
        if (current[card] < 0) {
            if (severity <= 0 || attackMs <= 0L) {
                current[card] = severity;
                candidate[card] = -1;
                return current[card];
            }
            // A newly seen warning starts from normal and must earn escalation.
            current[card] = 0;
            candidate[card] = severity;
            since[card] = now;
            return current[card];
        }

        if (severity == current[card]) {
            candidate[card] = -1;
            since[card] = 0L;
            return current[card];
        }

        if (severity > current[card]) {
            if (attackMs <= 0L) {
                current[card] = severity;
                candidate[card] = -1;
                since[card] = 0L;
            } else {
                if (candidate[card] < 0) {
                    candidate[card] = severity;
                    since[card] = now;
                } else if (severity > candidate[card]) {
                    // A more severe level must earn its own continuous attack time.
                    candidate[card] = severity;
                    since[card] = now;
                } else if (severity < candidate[card]) {
                    // Still abnormal, only less severe: preserve the elapsed
                    // warning persistence instead of resetting on threshold chatter.
                    candidate[card] = severity;
                }
                if (now - since[card] >= attackMs) {
                    current[card] = severity;
                    candidate[card] = -1;
                    since[card] = 0L;
                }
            }
        } else {
            if (candidate[card] != severity) {
                candidate[card] = severity;
                since[card] = now;
            }
            if (now - since[card] >= RECOVERY_MS) {
                current[card] = severity;
                candidate[card] = -1;
                since[card] = 0L;
            }
        }
        return current[card];
    }

    void reset(int card) {
        current[card] = -1;
        candidate[card] = -1;
        since[card] = 0L;
    }
}
