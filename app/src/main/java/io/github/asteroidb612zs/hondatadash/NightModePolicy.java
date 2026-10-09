package io.github.asteroidb612zs.hondatadash;

/** Read-only Android uiMode selection, with a local-clock fallback for older head units. */
final class NightModePolicy {
    // Configuration constants (API 8); kept Android-free for boundary replay.
    static final int UNDEFINED = 0, DAY = 0x10, NIGHT = 0x20, MASK = 0x30;
    private boolean systemNightObserved;
    private boolean night, followingSystem;

    NightModePolicy(boolean observed) { systemNightObserved = observed; }

    void update(int uiMode, int localHour) {
        int mode = uiMode & MASK;
        if (mode == NIGHT) systemNightObserved = true;
        // A constant DAY is also Android's default on unsupported car radios.
        // It cannot prove illumination integration. Learn only from a real NIGHT.
        followingSystem = mode == NIGHT || (mode == DAY && systemNightObserved);
        night = followingSystem ? mode == NIGHT : localHour >= 18 || localHour < 7;
    }

    boolean isNight() { return night; }
    boolean followsSystem() { return followingSystem; }
    boolean hasObservedSystemNight() { return systemNightObserved; }
}
