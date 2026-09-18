package io.github.asteroidb612zs.hondatadash.data;

import java.util.List;

/**
 * Observer-only tap for V2.0.T2 flight recording.
 *
 * This interface must never participate in Bluetooth request scheduling, protocol
 * parsing decisions, semantic state transitions, or UI admission. Implementations
 * receive copies/snapshots of data that the production path already obtained.
 */
public interface DiagnosticObserver {
    /**
     * Called after CMD_INIT + CMD_SENSOR_DEF have been parsed successfully.
     * Implementations must copy anything they retain; the protocol instance may
     * be replaced during reconnect.
     */
    void onProtocolReady(int sensorCount, int dataFrameLength,
            List<HondataProtocol.SensorDef> sensorDefs);

    /**
     * Called for a valid decoded 0x35 frame. The observer must return quickly and
     * must not perform file I/O on the Bluetooth polling thread.
     */
    void onRawFrame(long frameSequence, long receivedAtElapsedMs, byte[] frame);
}
