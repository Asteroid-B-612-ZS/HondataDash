package io.github.asteroidb612zs.hondatadash.diagnostic;

import android.os.SystemClock;

import io.github.asteroidb612zs.hondatadash.data.CombustionDisplayAdmission;
import io.github.asteroidb612zs.hondatadash.data.DiagnosticObserver;
import io.github.asteroidb612zs.hondatadash.data.EngineSemanticState;
import io.github.asteroidb612zs.hondatadash.data.HondataProtocol;
import io.github.asteroidb612zs.hondatadash.data.SensorData;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * V2.0.T3 observer-only flight recorder.
 *
 * Invariants:
 *  - never sends Bluetooth commands;
 *  - never changes protocol parsing or semantic decisions;
 *  - never performs file I/O on Bluetooth/UI hot paths;
 *  - queue overflow drops diagnostic evidence instead of blocking production work;
 *  - raw frames are retained so future decoders can reinterpret historical sessions.
 */
public final class FlightRecorder implements DiagnosticObserver {
    public static final String APP_VERSION = "2.0.1-internal.3";
    public static final int VERSION_CODE = 47;
    public static final String SOURCE_BASE =
            "04e952d18b00bee8a7a83019b7e1b49e2e9024e5";
    public static final int RECORDER_VERSION = 2;

    private static final int RAW_QUEUE_CAPACITY = 1024;
    private static final int PRE_DRIVE_RAW_FRAMES = 64;
    private static final int TRACE_QUEUE_CAPACITY = 256;
    private static final int EVENT_QUEUE_CAPACITY = 256;
    private static final int EXTREMA_QUEUE_CAPACITY = 256;
    private static final int MAX_CHANNELS = 256;
    private static final long TRACE_INTERVAL_MS = 50L;       // 20 Hz
    private static final long FLUSH_INTERVAL_MS = 2000L;
    private static final long STATS_CHECKPOINT_MS = 10000L;
    private static final long MIN_FREE_BYTES = 200L * 1024L * 1024L;
    private static final long MAX_TOTAL_BYTES = 1024L * 1024L * 1024L;
    private static final int MAX_COMPLETE_SESSIONS = 30;
    private static final int RAW_MAGIC = 0x48444652; // HDFR

    private static final String[] MAIN_NAMES = {"DFCO","WOT","WARMUP","IDLE","NORMAL"};
    private static final String[] THERMAL_NAMES = {"UNKNOWN","COLD","WARMING","READY"};
    private static final String[] SUB_NAMES = {"SPOOL","PEAK","HOLD","DFCO_ENTER","DFCO_HOLD","NONE"};
    private static final String[] MODIFIER_NAMES = {"TIP_IN","TIP_OUT","BOOST_SURGE","SHIFT","COAST","NONE"};
    private static final String[] SHIFT_NAMES = {"NONE","SHIFT_ARMED","SHIFT_CONFIRMED"};
    private static final String[] COMBUSTION_NAMES =
            {"FIRING_VALID","SHIFT_FUEL_CUT","DFCO_FUEL_CUT","OTHER_FUEL_CUT","RECOVERY"};
    private static final String[] CARD_NAMES =
            {"ETHANOL","ECT","IAT","L.TRIM","MAP","A/F","IGN","S.TRIM"};

    private final File rootDir;
    private final Object wakeLock = new Object();
    private final Object rawLock = new Object();
    private final Object traceLock = new Object();
    private final Object eventLock = new Object();
    private final Object extremaLock = new Object();

    private volatile boolean enabled;
    private volatile boolean stopping;
    private volatile boolean ioFailed;
    private volatile boolean protocolReady;
    // IT3 drive-session lifecycle: ignition-only/copy-only power-up never creates
    // a session. A session is committed only after a plausible RPM-running sample.
    private volatile boolean driveQualified;
    private volatile boolean engineSessionEndRequested;
    private volatile boolean appForeground = true;
    private volatile int manifestHash;
    private volatile int sensorCount;
    private volatile int dataFrameLength;
    private volatile int[] manifestPids = new int[0];
    private volatile int[] manifestCs = new int[0];
    private volatile int[] manifestCt = new int[0];
    private volatile int[] manifestSize = new int[0];
    private volatile String[] manifestNames = new String[0];

    private byte[][] rawFrames;
    private long[] rawSequence;
    private long[] rawElapsed;
    private int[] rawLength;
    private int[] rawManifestHash;
    private int rawHead;
    private int rawTail;
    private int rawCount;

    private final TraceSlot[] traceQueue = new TraceSlot[TRACE_QUEUE_CAPACITY];
    private int traceHead;
    private int traceTail;
    private int traceCount;

    private final EventSlot[] eventQueue = new EventSlot[EVENT_QUEUE_CAPACITY];
    private int eventHead;
    private int eventTail;
    private int eventCount;

    private final ExtremaSlot[] extremaQueue = new ExtremaSlot[EXTREMA_QUEUE_CAPACITY];
    private int extremaHead;
    private int extremaTail;
    private int extremaCount;

    private long lastTraceQueuedMs;
    private volatile long lastFrameElapsedMs;

    private long rawReceived;
    private long rawWritten;
    private long rawDropped;
    private long traceWritten;
    private long traceDropped;
    private long eventWritten;
    private long eventDropped;
    private long extremaWritten;
    private long extremaDropped;
    private long writeErrors;
    private long rejectedSemanticFrames;
    private int maxRawQueueDepth;
    private int maxTraceQueueDepth;
    private int maxEventQueueDepth;
    private int maxExtremaQueueDepth;

    private int lastMain = -1;
    private int lastThermal = -1;
    private int lastSub = -1;
    private int lastModifier = -1;
    private int lastShift = -1;
    private int lastCombustion = -1;
    private boolean lastHoldAf;
    private boolean lastHoldIgn;
    private boolean lastHoldStrim;
    private boolean lastFrontGuard;
    private boolean lastFpAlert;
    private boolean transitionBaselineReady;

    private final float[] lastExtremeMax = new float[8];
    private final float[] lastExtremeMin = new float[8];
    private final boolean[] lastExtremeValid = new boolean[8];

    private File sessionDir;
    private int sessionManifestHash;
    private long sessionStartElapsedMs;
    private long sessionStartWallMs;
    private DataOutputStream rawOut;
    private BufferedWriter traceOut;
    private BufferedWriter eventOut;
    private BufferedWriter extremaOut;
    private long lastFlushElapsedMs;
    private long lastStatsCheckpointMs;
    private boolean startupMaintenanceDone;

    private final Thread writerThread;

    public FlightRecorder(File rootDir) {
        this.rootDir = rootDir;
        for (int i = 0; i < traceQueue.length; i++) traceQueue[i] = new TraceSlot();
        for (int i = 0; i < eventQueue.length; i++) eventQueue[i] = new EventSlot();
        for (int i = 0; i < extremaQueue.length; i++) extremaQueue[i] = new ExtremaSlot();
        Arrays.fill(lastExtremeMax, Float.NaN);
        Arrays.fill(lastExtremeMin, Float.NaN);
        writerThread = new Thread(new Runnable() {
            @Override public void run() { writerLoop(); }
        }, "Hondata-FlightRecorder");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            // Do not clear queues from the caller thread: the writer may currently
            // own a queue-head slot outside the short queue lock. Wake the single
            // writer and let it discard/close in one place without racing counters.
            synchronized (wakeLock) { wakeLock.notifyAll(); }
        } else {
            ioFailed = false;
            signalWriter();
        }
    }

    public boolean isEnabled() {
        return enabled && !ioFailed;
    }

    @Override
    public void onProtocolReady(int count, int frameLength,
            List<HondataProtocol.SensorDef> defs) {
        if (count <= 0 || count > MAX_CHANNELS || frameLength <= 4 || frameLength > 4096
                || defs == null || defs.size() != count) {
            protocolReady = false;
            return;
        }

        int[] pids = new int[count];
        int[] cs = new int[count];
        int[] ct = new int[count];
        int[] sizes = new int[count];
        String[] names = new String[count];
        int hash = 17;
        hash = 31 * hash + count;
        hash = 31 * hash + frameLength;
        for (int i = 0; i < count; i++) {
            HondataProtocol.SensorDef def = defs.get(i);
            pids[i] = def.pid;
            cs[i] = def.csBits;
            ct[i] = def.ctType;
            sizes[i] = def.dataLength;
            names[i] = def.getName();
            hash = 31 * hash + def.pid;
            hash = 31 * hash + def.csBits;
            hash = 31 * hash + def.ctType;
            hash = 31 * hash + def.dataLength;
        }

        manifestPids = pids;
        manifestCs = cs;
        manifestCt = ct;
        manifestSize = sizes;
        manifestNames = names;
        sensorCount = count;
        dataFrameLength = frameLength;
        manifestHash = hash;

        synchronized (rawLock) {
            if (rawFrames == null || rawFrames[0].length != frameLength) {
                rawFrames = new byte[RAW_QUEUE_CAPACITY][frameLength];
                rawSequence = new long[RAW_QUEUE_CAPACITY];
                rawElapsed = new long[RAW_QUEUE_CAPACITY];
                rawLength = new int[RAW_QUEUE_CAPACITY];
                rawManifestHash = new int[RAW_QUEUE_CAPACITY];
                rawHead = rawTail = rawCount = 0;
            }
        }
        protocolReady = true;
        signalWriter();
    }

    @Override
    public void onRawFrame(long frameSequence, long receivedAtElapsedMs, byte[] frame) {
        if (!isEnabled() || !protocolReady || frame == null) return;
        byte[][] frames = rawFrames;
        if (frames == null || frame.length > frames[0].length) return;

        synchronized (rawLock) {
            if (!driveQualified && rawCount >= PRE_DRIVE_RAW_FRAMES) {
                // Ignition-only / file-copy mode: retain only a short pre-roll and
                // never create disk evidence until the engine actually runs.
                rawHead = (rawHead + 1) % RAW_QUEUE_CAPACITY;
                rawCount--;
            } else if (driveQualified && rawCount >= RAW_QUEUE_CAPACITY) {
                rawDropped++;
                return;
            }
            byte[] target = rawFrames[rawTail];
            System.arraycopy(frame, 0, target, 0, frame.length);
            rawSequence[rawTail] = frameSequence;
            rawElapsed[rawTail] = receivedAtElapsedMs;
            rawLength[rawTail] = frame.length;
            rawManifestHash[rawTail] = manifestHash;
            rawTail = (rawTail + 1) % RAW_QUEUE_CAPACITY;
            rawCount++;
            if (driveQualified) rawReceived++;
            if (rawCount > maxRawQueueDepth) maxRawQueueDepth = rawCount;
        }
        lastFrameElapsedMs = receivedAtElapsedMs;
        signalWriter();
    }

    /**
     * Called on the UI path after the production semantic/display logic has run.
     * The method copies primitives into bounded queues only; no file I/O occurs here.
     */
    public void recordSemantic(SensorData data, EngineSemanticState state,
            CombustionDisplayAdmission.Snapshot admission,
            float afEffective, float ignEffective, float strimEffective, float strimWeight,
            boolean fpObservable, long fpLowObservedMs, long fpPauseAgeMs,
            boolean fpEvidenceActive, boolean fpAlert,
            boolean boostEventActive, boolean hasLastBoostEventPeak, float lastBoostEventPeak) {
        if (!isEnabled() || !driveQualified || data == null || state == null
                || admission == null || !protocolReady) return;
        final long now = data.receivedAtElapsedMs > 0L
                ? data.receivedAtElapsedMs : SystemClock.elapsedRealtime();

        observeTransitions(now, state, admission, fpAlert);

        if (lastTraceQueuedMs > 0L && now - lastTraceQueuedMs < TRACE_INTERVAL_MS) return;
        lastTraceQueuedMs = now;

        int[] pids = manifestPids;
        int count = pids.length;
        synchronized (traceLock) {
            if (traceCount >= TRACE_QUEUE_CAPACITY) {
                traceDropped++;
                return;
            }
            TraceSlot slot = traceQueue[traceTail];
            slot.sequence = data.frameSequence;
            slot.elapsedMs = now;
            slot.wallMs = data.timestamp;
            slot.manifestHash = manifestHash;
            slot.channelCount = count;
            for (int i = 0; i < count; i++) slot.channels[i] = data.getDouble(pids[i]);
            slot.main = state.main.ordinal();
            slot.thermal = state.thermal.ordinal();
            slot.sub = state.sub.ordinal();
            slot.modifier = state.modifier.ordinal();
            slot.shift = state.shiftPhase.ordinal();
            slot.combustion = state.combustion.ordinal();
            slot.confidence = state.confidence;
            slot.holdAf = admission.holdAf;
            slot.holdIgn = admission.holdIgn;
            slot.holdStrim = admission.holdStrim;
            slot.releasedAf = admission.releasedAf;
            slot.releasedIgn = admission.releasedIgn;
            slot.releasedStrim = admission.releasedStrim;
            slot.frontGuard = admission.frontGuard;
            slot.afEffective = afEffective;
            slot.ignEffective = ignEffective;
            slot.strimEffective = strimEffective;
            slot.strimWeight = strimWeight;
            slot.fpObservable = fpObservable;
            slot.fpLowObservedMs = fpLowObservedMs;
            slot.fpPauseAgeMs = fpPauseAgeMs;
            slot.fpEvidenceActive = fpEvidenceActive;
            slot.fpAlert = fpAlert;
            slot.boostEventActive = boostEventActive;
            slot.hasLastBoostEventPeak = hasLastBoostEventPeak;
            slot.lastBoostEventPeak = lastBoostEventPeak;
            traceTail = (traceTail + 1) % TRACE_QUEUE_CAPACITY;
            traceCount++;
            if (traceCount > maxTraceQueueDepth) maxTraceQueueDepth = traceCount;
        }
        signalWriter();
    }

    /** Records accepted extrema changes only; rejected candidates are reconstructed from trace context. */
    public void recordExtrema(long now, EngineSemanticState state,
            float[] maxTrack, float[] minTrack, boolean[] hasValue) {
        if (!isEnabled() || !driveQualified || state == null
                || maxTrack == null || minTrack == null || hasValue == null) return;
        for (int i = 0; i < 8; i++) {
            if (!hasValue[i]) continue;
            float max = maxTrack[i];
            float min = minTrack[i];
            if (!lastExtremeValid[i]) {
                enqueueExtrema(now, i, true, Float.NaN, max, state, true);
                enqueueExtrema(now, i, false, Float.NaN, min, state, true);
                lastExtremeMax[i] = max;
                lastExtremeMin[i] = min;
                lastExtremeValid[i] = true;
                continue;
            }
            if (Float.compare(max, lastExtremeMax[i]) != 0) {
                enqueueExtrema(now, i, true, lastExtremeMax[i], max, state, false);
                lastExtremeMax[i] = max;
            }
            if (Float.compare(min, lastExtremeMin[i]) != 0) {
                enqueueExtrema(now, i, false, lastExtremeMin[i], min, state, false);
                lastExtremeMin[i] = min;
            }
        }
    }

    public void recordTransportEvent(String type, long now) {
        if (!isEnabled() || !driveQualified || type == null) return;
        enqueueEvent(now, type, "", "", manifestHash);
    }

    public void recordRejectedSemanticFrame(long now) {
        if (driveQualified) rejectedSemanticFrames++;
    }

    public void recordBoostEvent(String type, float peak, long now) {
        if (!isEnabled() || !driveQualified || type == null) return;
        enqueueEvent(now, type, "", floatText(peak), manifestHash);
    }

    public void onExtremaReset(int card, long now) {
        if (card < 0 || card >= lastExtremeValid.length) return;
        lastExtremeValid[card] = false;
        lastExtremeMax[card] = Float.NaN;
        lastExtremeMin[card] = Float.NaN;
        if (driveQualified) enqueueEvent(now, "EXTREMA_RESET", CARD_NAMES[card], "", manifestHash);
    }

    public void onAppForegroundChanged(boolean foreground) {
        appForeground = foreground;
        if (driveQualified) {
            enqueueEvent(SystemClock.elapsedRealtime(),
                    foreground ? "UI_FOREGROUND" : "UI_BACKGROUND", "", "", manifestHash);
        }
        signalWriter();
    }

    public void onEngineRunningSample(boolean running, long now) {
        if (!isEnabled() || !protocolReady || !running || driveQualified) return;
        driveQualified = true;
        engineSessionEndRequested = false;
        synchronized (rawLock) {
            // Count the retained pre-roll as part of this new session.
            rawReceived = rawCount;
        }
        resetSessionCountersExceptRawReceived();
        enqueueEvent(now, "DRIVE_SESSION_QUALIFIED", "", "", manifestHash);
        signalWriter();
    }

    public void onEngineSessionEnded(long now) {
        if (!driveQualified) return;
        enqueueEvent(now, "ENGINE_SESSION_END", "", "", manifestHash);
        engineSessionEndRequested = true;
        signalWriter();
    }

    public void shutdown() {
        stopping = true;
        signalWriter();
        try {
            writerThread.join(1500L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void observeTransitions(long now, EngineSemanticState state,
            CombustionDisplayAdmission.Snapshot admission, boolean fpAlert) {
        int main = state.main.ordinal();
        int thermal = state.thermal.ordinal();
        int sub = state.sub.ordinal();
        int modifier = state.modifier.ordinal();
        int shift = state.shiftPhase.ordinal();
        int combustion = state.combustion.ordinal();

        if (!transitionBaselineReady) {
            lastMain = main;
            lastThermal = thermal;
            lastSub = sub;
            lastModifier = modifier;
            lastShift = shift;
            lastCombustion = combustion;
            lastHoldAf = admission.holdAf;
            lastHoldIgn = admission.holdIgn;
            lastHoldStrim = admission.holdStrim;
            lastFrontGuard = admission.frontGuard;
            lastFpAlert = fpAlert;
            transitionBaselineReady = true;
            enqueueEvent(now, "SEMANTIC_BASELINE", "", stateSummary(state), manifestHash);
            return;
        }

        if (main != lastMain) enqueueEvent(now, "MAIN_STATE", mainName(lastMain), mainName(main), manifestHash);
        if (thermal != lastThermal) enqueueEvent(now, "THERMAL", thermalName(lastThermal), thermalName(thermal), manifestHash);
        if (sub != lastSub) enqueueEvent(now, "SUB_STATE", subName(lastSub), subName(sub), manifestHash);
        // IT3: per-frame Modifier remains in semantic_trace. Do not mirror every
        // 40-100 ms TIP/BOOST_SURGE oscillation into events.csv.
        if (shift != lastShift) enqueueEvent(now, "SHIFT_PHASE", shiftName(lastShift), shiftName(shift), manifestHash);
        if (combustion != lastCombustion) enqueueEvent(now, "COMBUSTION", combustionName(lastCombustion), combustionName(combustion), manifestHash);
        if (admission.holdAf != lastHoldAf) enqueueEvent(now, "AF_ADMISSION", lastHoldAf ? "HOLD" : "LIVE", admission.holdAf ? "HOLD" : "LIVE", manifestHash);
        if (admission.holdIgn != lastHoldIgn) enqueueEvent(now, "IGN_ADMISSION", lastHoldIgn ? "HOLD" : "LIVE", admission.holdIgn ? "HOLD" : "LIVE", manifestHash);
        if (admission.holdStrim != lastHoldStrim) enqueueEvent(now, "STRIM_ADMISSION", lastHoldStrim ? "HOLD" : "LIVE", admission.holdStrim ? "HOLD" : "LIVE", manifestHash);
        if (admission.frontGuard != lastFrontGuard) enqueueEvent(now, "TIP_OUT_FRONT_GUARD", lastFrontGuard ? "ON" : "OFF", admission.frontGuard ? "ON" : "OFF", manifestHash);
        if (fpAlert != lastFpAlert) enqueueEvent(now, "FUEL_PRESSURE_ALERT", lastFpAlert ? "ON" : "OFF", fpAlert ? "ON" : "OFF", manifestHash);

        lastMain = main;
        lastThermal = thermal;
        lastSub = sub;
        lastModifier = modifier;
        lastShift = shift;
        lastCombustion = combustion;
        lastHoldAf = admission.holdAf;
        lastHoldIgn = admission.holdIgn;
        lastHoldStrim = admission.holdStrim;
        lastFrontGuard = admission.frontGuard;
        lastFpAlert = fpAlert;
    }

    private void enqueueEvent(long now, String type, String from, String to, int hash) {
        synchronized (eventLock) {
            if (eventCount >= EVENT_QUEUE_CAPACITY) {
                eventDropped++;
                return;
            }
            EventSlot slot = eventQueue[eventTail];
            slot.elapsedMs = now;
            slot.wallMs = System.currentTimeMillis();
            slot.manifestHash = hash;
            slot.type = type;
            slot.from = from;
            slot.to = to;
            eventTail = (eventTail + 1) % EVENT_QUEUE_CAPACITY;
            eventCount++;
            if (eventCount > maxEventQueueDepth) maxEventQueueDepth = eventCount;
        }
        signalWriter();
    }

    private void enqueueExtrema(long now, int card, boolean max, float oldValue, float newValue,
            EngineSemanticState state, boolean initialization) {
        synchronized (extremaLock) {
            if (extremaCount >= EXTREMA_QUEUE_CAPACITY) {
                extremaDropped++;
                return;
            }
            ExtremaSlot slot = extremaQueue[extremaTail];
            slot.elapsedMs = now;
            slot.wallMs = System.currentTimeMillis();
            slot.manifestHash = manifestHash;
            slot.card = card;
            slot.max = max;
            slot.oldValue = oldValue;
            slot.newValue = newValue;
            slot.initialization = initialization;
            slot.main = state.main.ordinal();
            slot.thermal = state.thermal.ordinal();
            slot.shift = state.shiftPhase.ordinal();
            slot.combustion = state.combustion.ordinal();
            extremaTail = (extremaTail + 1) % EXTREMA_QUEUE_CAPACITY;
            extremaCount++;
            if (extremaCount > maxExtremaQueueDepth) maxExtremaQueueDepth = extremaCount;
        }
        signalWriter();
    }

    private void writerLoop() {
        while (!stopping || (driveQualified && hasQueuedWork())) {
            boolean didWork = false;
            if (enabled && !ioFailed) {
                try {
                    if (!startupMaintenanceDone) {
                        // Copy-only ignition must be able to recover the previous
                        // drive even if FlashPro has not finished handshaking yet.
                        // This runs on the recorder writer, never the UI thread.
                        markInterruptedSessions();
                        enforceRetention();
                        startupMaintenanceDone = true;
                    }

                    if (driveQualified) {
                        // Drain derived evidence first so state/display rows keep the
                        // session that produced them if a reconnect changes manifest.
                        didWork |= drainTrace(32);
                        didWork |= drainEvents(64);
                        didWork |= drainExtrema(64);
                        didWork |= drainRaw(64);

                        long now = SystemClock.elapsedRealtime();
                        if (sessionDir != null && now - lastFlushElapsedMs >= FLUSH_INTERVAL_MS) {
                            flushAll(now);
                        }
                        if (sessionDir != null
                                && now - lastStatsCheckpointMs >= STATS_CHECKPOINT_MS) {
                            writeStatsAtomic();
                            lastStatsCheckpointMs = now;
                        }

                        if (engineSessionEndRequested && !hasQueuedWork()) {
                            closeSession(true);
                            driveQualified = false;
                            engineSessionEndRequested = false;
                            clearQueues();
                        }
                    }
                } catch (Throwable t) {
                    writeErrors++;
                    ioFailed = true;
                    closeSession(false);
                    driveQualified = false;
                    engineSessionEndRequested = false;
                    clearQueues();
                }
            } else if (!enabled) {
                clearQueues();
                if (sessionDir != null) closeSession(true);
                driveQualified = false;
                engineSessionEndRequested = false;
            }

            if (!didWork) {
                synchronized (wakeLock) {
                    try { wakeLock.wait(100L); }
                    catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }
            }
        }
        if (!driveQualified) clearQueues();
        if (sessionDir != null) closeSession(true);
    }

    private boolean drainRaw(int limit) throws IOException {
        boolean did = false;
        for (int n = 0; n < limit; n++) {
            long seq;
            long elapsed;
            int len;
            int hash;
            byte[] frame;
            synchronized (rawLock) {
                if (rawCount <= 0) break;
                seq = rawSequence[rawHead];
                elapsed = rawElapsed[rawHead];
                len = rawLength[rawHead];
                hash = rawManifestHash[rawHead];
                frame = rawFrames[rawHead];
            }

            ensureSession(hash, elapsed);
            if (sessionDir == null || hash != sessionManifestHash) {
                rawDropped++;
            } else {
                rawOut.writeLong(seq);
                rawOut.writeLong(elapsed);
                rawOut.writeInt(len);
                rawOut.write(frame, 0, len);
                rawWritten++;
            }

            synchronized (rawLock) {
                rawHead = (rawHead + 1) % RAW_QUEUE_CAPACITY;
                rawCount--;
            }
            did = true;
        }
        return did;
    }

    private boolean drainTrace(int limit) throws IOException {
        boolean did = false;
        for (int n = 0; n < limit; n++) {
            TraceSlot slot;
            synchronized (traceLock) {
                if (traceCount <= 0) break;
                slot = traceQueue[traceHead];
            }
            ensureSession(slot.manifestHash, slot.elapsedMs);
            if (traceOut != null && slot.manifestHash == sessionManifestHash) {
                writeTrace(slot);
                traceWritten++;
            } else {
                traceDropped++;
            }
            synchronized (traceLock) {
                traceHead = (traceHead + 1) % TRACE_QUEUE_CAPACITY;
                traceCount--;
            }
            did = true;
        }
        return did;
    }

    private boolean drainEvents(int limit) throws IOException {
        boolean did = false;
        for (int n = 0; n < limit; n++) {
            EventSlot slot;
            synchronized (eventLock) {
                if (eventCount <= 0) break;
                slot = eventQueue[eventHead];
            }
            ensureSession(slot.manifestHash, slot.elapsedMs);
            if (eventOut != null && slot.manifestHash == sessionManifestHash) {
                eventOut.write(Long.toString(slot.elapsedMs));
                eventOut.write(',');
                eventOut.write(Long.toString(slot.wallMs));
                eventOut.write(',');
                eventOut.write(csv(slot.type));
                eventOut.write(',');
                eventOut.write(csv(slot.from));
                eventOut.write(',');
                eventOut.write(csv(slot.to));
                eventOut.newLine();
                eventWritten++;
            } else {
                eventDropped++;
            }
            synchronized (eventLock) {
                eventHead = (eventHead + 1) % EVENT_QUEUE_CAPACITY;
                eventCount--;
            }
            did = true;
        }
        return did;
    }

    private boolean drainExtrema(int limit) throws IOException {
        boolean did = false;
        for (int n = 0; n < limit; n++) {
            ExtremaSlot slot;
            synchronized (extremaLock) {
                if (extremaCount <= 0) break;
                slot = extremaQueue[extremaHead];
            }
            ensureSession(slot.manifestHash, slot.elapsedMs);
            if (extremaOut != null && slot.manifestHash == sessionManifestHash) {
                extremaOut.write(Long.toString(slot.elapsedMs));
                extremaOut.write(',');
                extremaOut.write(Long.toString(slot.wallMs));
                extremaOut.write(',');
                extremaOut.write(CARD_NAMES[slot.card]);
                extremaOut.write(',');
                extremaOut.write(slot.max ? "MAX_TRACK" : "MIN_TRACK");
                extremaOut.write(',');
                extremaOut.write(slot.initialization ? "INITIALIZE" : "ADMITTED_UPDATE");
                extremaOut.write(',');
                extremaOut.write(floatText(slot.oldValue));
                extremaOut.write(',');
                extremaOut.write(floatText(slot.newValue));
                extremaOut.write(',');
                extremaOut.write(mainName(slot.main));
                extremaOut.write(',');
                extremaOut.write(thermalName(slot.thermal));
                extremaOut.write(',');
                extremaOut.write(shiftName(slot.shift));
                extremaOut.write(',');
                extremaOut.write(combustionName(slot.combustion));
                extremaOut.newLine();
                extremaWritten++;
            } else {
                extremaDropped++;
            }
            synchronized (extremaLock) {
                extremaHead = (extremaHead + 1) % EXTREMA_QUEUE_CAPACITY;
                extremaCount--;
            }
            did = true;
        }
        return did;
    }

    private void ensureSession(int hash, long firstElapsed) throws IOException {
        if (sessionDir != null && sessionManifestHash == hash) return;
        if (sessionDir != null) closeSession(true);
        if (!driveQualified || !protocolReady || hash != manifestHash) return;
        if (!rootDir.exists() && !rootDir.mkdirs()) throw new IOException("Cannot create diagnostic root");
        if (rootDir.getUsableSpace() > 0L && rootDir.getUsableSpace() < MIN_FREE_BYTES) {
            ioFailed = true;
            return;
        }

        sessionStartWallMs = System.currentTimeMillis();
        sessionStartElapsedMs = firstElapsed;
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date(sessionStartWallMs));
        File candidate = new File(rootDir, stamp + "_IT3");
        int suffix = 1;
        while (candidate.exists()) {
            candidate = new File(rootDir, stamp + "_IT3_" + suffix);
            suffix++;
        }
        if (!candidate.mkdirs()) throw new IOException("Cannot create session directory");
        sessionDir = candidate;
        sessionManifestHash = hash;
        lastStatsCheckpointMs = SystemClock.elapsedRealtime();

        writeSessionMetadataAtomic();
        writeManifest();
        new File(sessionDir, "ACTIVE").createNewFile();

        rawOut = new DataOutputStream(new BufferedOutputStream(
                new FileOutputStream(new File(sessionDir, "raw_frames.bin")), 65536));
        rawOut.writeInt(RAW_MAGIC);
        rawOut.writeInt(RECORDER_VERSION);
        rawOut.writeInt(dataFrameLength);
        rawOut.writeInt(sensorCount);

        traceOut = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(new File(sessionDir, "semantic_trace.csv")), "UTF-8"), 65536);
        writeTraceHeader();

        eventOut = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(new File(sessionDir, "events.csv")), "UTF-8"), 16384);
        eventOut.write("elapsed_ms,wall_ms,event,from,to");
        eventOut.newLine();

        extremaOut = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(new File(sessionDir, "extrema.csv")), "UTF-8"), 16384);
        extremaOut.write("elapsed_ms,wall_ms,card,slot,decision,old_value,new_value,main,thermal,shift_phase,combustion");
        extremaOut.newLine();

        resetSessionTransitionMemory();
        lastFlushElapsedMs = SystemClock.elapsedRealtime();
    }

    private void writeSessionMetadataAtomic() throws IOException {
        File target = new File(sessionDir, "session.json");
        File tmp = new File(sessionDir, "session.json.tmp");
        FileOutputStream fos = new FileOutputStream(tmp);
        BufferedWriter out = new BufferedWriter(new OutputStreamWriter(fos, "UTF-8"));
        try {
            String iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
                    .format(new Date(sessionStartWallMs));
            out.write("{\n");
            out.write("  \"appVersion\": \"" + APP_VERSION + "\",\n");
            out.write("  \"versionCode\": " + VERSION_CODE + ",\n");
            out.write("  \"sourceBase\": \"" + SOURCE_BASE + "\",\n");
            out.write("  \"semanticChangeSet\": \"IT3\",\n");
            out.write("  \"recorderVersion\": " + RECORDER_VERSION + ",\n");
            out.write("  \"startTime\": \"" + iso + "\",\n");
            out.write("  \"startElapsedMs\": " + sessionStartElapsedMs + ",\n");
            out.write("  \"channelCount\": " + sensorCount + ",\n");
            out.write("  \"dataFrameLength\": " + dataFrameLength + ",\n");
            out.write("  \"manifestHash\": " + sessionManifestHash + ",\n");
            out.write("  \"traceIntervalMs\": " + TRACE_INTERVAL_MS + ",\n");
            out.write("  \"rawFormat\": \"big-endian header: magic/version/frameLength/channelCount; records: int64 sequence, int64 elapsedMs, int32 length, raw frame bytes\"\n");
            out.write("}\n");
            out.flush();
            fos.getFD().sync();
        } finally {
            out.close();
        }
        if (target.exists() && !target.delete()) throw new IOException("Cannot replace session metadata");
        if (!tmp.renameTo(target)) throw new IOException("Cannot commit session metadata");
    }

    private void writeManifest() throws IOException {
        int[] pids = manifestPids;
        int[] cs = manifestCs;
        int[] ct = manifestCt;
        int[] sizes = manifestSize;
        String[] names = manifestNames;
        BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(new File(sessionDir, "channel_manifest.csv")), "UTF-8"));
        try {
            out.write("index,pid_hex,name,cs_hex,ct_hex,size_bytes");
            out.newLine();
            for (int i = 0; i < pids.length; i++) {
                out.write(Integer.toString(i));
                out.write(",0x");
                out.write(hex4(pids[i]));
                out.write(',');
                out.write(csv(names[i]));
                out.write(",0x");
                out.write(hex2(cs[i]));
                out.write(",0x");
                out.write(hex2(ct[i]));
                out.write(',');
                out.write(Integer.toString(sizes[i]));
                out.newLine();
            }
        } finally {
            out.close();
        }
    }

    private void writeTraceHeader() throws IOException {
        traceOut.write("elapsed_ms,wall_ms,frame_seq");
        int[] pids = manifestPids;
        for (int i = 0; i < pids.length; i++) {
            traceOut.write(",pid_");
            traceOut.write(hex4(pids[i]));
        }
        traceOut.write(",main,thermal,sub,modifier,shift_phase,combustion,confidence");
        traceOut.write(",hold_af,hold_ign,hold_strim,released_af,released_ign,released_strim,front_guard");
        traceOut.write(",af_effective,ign_effective,strim_effective,strim_weight");
        traceOut.write(",fp_observable,fp_low_observed_ms,fp_pause_age_ms,fp_evidence_active,fp_alert");
        traceOut.write(",boost_event_active,has_last_boost_peak,last_boost_peak");
        traceOut.newLine();
    }

    private void writeTrace(TraceSlot s) throws IOException {
        traceOut.write(Long.toString(s.elapsedMs));
        traceOut.write(',');
        traceOut.write(Long.toString(s.wallMs));
        traceOut.write(',');
        traceOut.write(Long.toString(s.sequence));
        for (int i = 0; i < s.channelCount; i++) {
            traceOut.write(',');
            traceOut.write(doubleText(s.channels[i]));
        }
        traceOut.write(',');
        traceOut.write(mainName(s.main));
        traceOut.write(',');
        traceOut.write(thermalName(s.thermal));
        traceOut.write(',');
        traceOut.write(subName(s.sub));
        traceOut.write(',');
        traceOut.write(modifierName(s.modifier));
        traceOut.write(',');
        traceOut.write(shiftName(s.shift));
        traceOut.write(',');
        traceOut.write(combustionName(s.combustion));
        traceOut.write(',');
        traceOut.write(Float.toString(s.confidence));
        traceOut.write(',');
        traceOut.write(bool(s.holdAf));
        traceOut.write(',');
        traceOut.write(bool(s.holdIgn));
        traceOut.write(',');
        traceOut.write(bool(s.holdStrim));
        traceOut.write(',');
        traceOut.write(bool(s.releasedAf));
        traceOut.write(',');
        traceOut.write(bool(s.releasedIgn));
        traceOut.write(',');
        traceOut.write(bool(s.releasedStrim));
        traceOut.write(',');
        traceOut.write(bool(s.frontGuard));
        traceOut.write(',');
        traceOut.write(floatText(s.afEffective));
        traceOut.write(',');
        traceOut.write(floatText(s.ignEffective));
        traceOut.write(',');
        traceOut.write(floatText(s.strimEffective));
        traceOut.write(',');
        traceOut.write(Float.toString(s.strimWeight));
        traceOut.write(',');
        traceOut.write(bool(s.fpObservable));
        traceOut.write(',');
        traceOut.write(Long.toString(s.fpLowObservedMs));
        traceOut.write(',');
        traceOut.write(Long.toString(s.fpPauseAgeMs));
        traceOut.write(',');
        traceOut.write(bool(s.fpEvidenceActive));
        traceOut.write(',');
        traceOut.write(bool(s.fpAlert));
        traceOut.write(',');
        traceOut.write(bool(s.boostEventActive));
        traceOut.write(',');
        traceOut.write(bool(s.hasLastBoostEventPeak));
        traceOut.write(',');
        traceOut.write(floatText(s.lastBoostEventPeak));
        traceOut.newLine();
    }

    private void flushAll(long now) throws IOException {
        if (rawOut != null) rawOut.flush();
        if (traceOut != null) traceOut.flush();
        if (eventOut != null) eventOut.flush();
        if (extremaOut != null) extremaOut.flush();
        lastFlushElapsedMs = now;
    }

    private void closeSession(boolean complete) {
        if (sessionDir == null) return;
        try { flushAll(SystemClock.elapsedRealtime()); } catch (Throwable t) { writeErrors++; complete = false; }
        closeQuietly(rawOut);
        closeQuietly(traceOut);
        closeQuietly(eventOut);
        closeQuietly(extremaOut);
        rawOut = null;
        traceOut = null;
        eventOut = null;
        extremaOut = null;

        try { writeStatsAtomic(); }
        catch (Throwable t) { writeErrors++; complete = false; }

        File active = new File(sessionDir, "ACTIVE");
        if (active.exists()) active.delete();
        if (complete) {
            try { new File(sessionDir, "COMPLETE").createNewFile(); }
            catch (Throwable t) { writeErrors++; }
        } else {
            try { new File(sessionDir, "INCOMPLETE").createNewFile(); }
            catch (Throwable ignored) { }
        }

        sessionDir = null;
        sessionManifestHash = 0;
        sessionStartElapsedMs = 0L;
        sessionStartWallMs = 0L;
        resetSessionTransitionMemory();
    }

    private void writeStatsAtomic() throws IOException {
        if (sessionDir == null) return;
        File target = new File(sessionDir, "recorder_stats.json");
        File tmp = new File(sessionDir, "recorder_stats.json.tmp");
        FileOutputStream fos = new FileOutputStream(tmp);
        BufferedWriter out = new BufferedWriter(new OutputStreamWriter(fos, "UTF-8"));
        try {
            out.write("{\n");
            out.write("  \"rawReceived\": " + rawReceived + ",\n");
            out.write("  \"rawWritten\": " + rawWritten + ",\n");
            out.write("  \"rawDropped\": " + rawDropped + ",\n");
            out.write("  \"traceWritten\": " + traceWritten + ",\n");
            out.write("  \"traceDropped\": " + traceDropped + ",\n");
            out.write("  \"eventWritten\": " + eventWritten + ",\n");
            out.write("  \"eventDropped\": " + eventDropped + ",\n");
            out.write("  \"extremaWritten\": " + extremaWritten + ",\n");
            out.write("  \"extremaDropped\": " + extremaDropped + ",\n");
            out.write("  \"writeErrors\": " + writeErrors + ",\n");
            out.write("  \"rejectedSemanticFrames\": " + rejectedSemanticFrames + ",\n");
            out.write("  \"maxRawQueueDepth\": " + maxRawQueueDepth + ",\n");
            out.write("  \"maxTraceQueueDepth\": " + maxTraceQueueDepth + ",\n");
            out.write("  \"maxEventQueueDepth\": " + maxEventQueueDepth + ",\n");
            out.write("  \"maxExtremaQueueDepth\": " + maxExtremaQueueDepth + "\n");
            out.write("}\n");
            out.flush();
            fos.getFD().sync();
        } finally {
            out.close();
        }
        if (target.exists() && !target.delete()) throw new IOException("Cannot replace recorder stats");
        if (!tmp.renameTo(target)) throw new IOException("Cannot commit recorder stats");
    }

    private void resetSessionCountersExceptRawReceived() {
        rawWritten = 0L;
        rawDropped = 0L;
        traceWritten = 0L;
        traceDropped = 0L;
        eventWritten = 0L;
        eventDropped = 0L;
        extremaWritten = 0L;
        extremaDropped = 0L;
        writeErrors = 0L;
        rejectedSemanticFrames = 0L;
        maxRawQueueDepth = rawCount;
        maxTraceQueueDepth = 0;
        maxEventQueueDepth = 0;
        maxExtremaQueueDepth = 0;
    }

    private void resetSessionTransitionMemory() {
        lastMain = lastThermal = lastSub = lastModifier = lastShift = lastCombustion = -1;
        lastHoldAf = lastHoldIgn = lastHoldStrim = lastFrontGuard = lastFpAlert = false;
        transitionBaselineReady = false;
        Arrays.fill(lastExtremeMax, Float.NaN);
        Arrays.fill(lastExtremeMin, Float.NaN);
        Arrays.fill(lastExtremeValid, false);
        lastTraceQueuedMs = 0L;
    }

    private boolean hasQueuedWork() {
        synchronized (rawLock) { if (rawCount > 0) return true; }
        synchronized (traceLock) { if (traceCount > 0) return true; }
        synchronized (eventLock) { if (eventCount > 0) return true; }
        synchronized (extremaLock) { return extremaCount > 0; }
    }

    private void clearQueues() {
        synchronized (rawLock) { rawHead = rawTail = rawCount = 0; }
        synchronized (traceLock) { traceHead = traceTail = traceCount = 0; }
        synchronized (eventLock) { eventHead = eventTail = eventCount = 0; }
        synchronized (extremaLock) { extremaHead = extremaTail = extremaCount = 0; }
    }

    private void signalWriter() {
        synchronized (wakeLock) { wakeLock.notifyAll(); }
    }

    private void markInterruptedSessions() {
        if (!rootDir.exists()) return;
        File[] dirs = rootDir.listFiles();
        if (dirs == null) return;
        for (File dir : dirs) {
            if (!dir.isDirectory()) continue;
            File complete = new File(dir, "COMPLETE");
            File incomplete = new File(dir, "INCOMPLETE");
            File recovered = new File(dir, "POWER_CUT_RECOVERED");
            File active = new File(dir, "ACTIVE");
            if (active.exists()) {
                // Direct head-unit power loss after ignition-off is a normal vehicle
                // lifecycle, not an app crash. Finalize it on next power-up so the
                // user can copy it without starting a new drive session.
                active.delete();
                try { recovered.createNewFile(); } catch (IOException ignored) { }
            } else if (!complete.exists() && !incomplete.exists() && !recovered.exists()) {
                // Backward-compatible handling for IT2/unmarked interrupted folders.
                try { incomplete.createNewFile(); } catch (IOException ignored) { }
            }
        }
    }

    private void enforceRetention() {
        if (!rootDir.exists()) return;
        File[] dirs = rootDir.listFiles();
        if (dirs == null) return;
        Arrays.sort(dirs, new Comparator<File>() {
            @Override public int compare(File a, File b) { return a.getName().compareTo(b.getName()); }
        });
        long total = directorySize(rootDir);
        int completeCount = 0;
        for (File dir : dirs) {
            if (dir.isDirectory() && (new File(dir, "COMPLETE").exists()
                    || new File(dir, "POWER_CUT_RECOVERED").exists())) completeCount++;
        }
        for (File dir : dirs) {
            if (completeCount <= MAX_COMPLETE_SESSIONS && total <= MAX_TOTAL_BYTES) break;
            if (!dir.isDirectory() || !(new File(dir, "COMPLETE").exists()
                    || new File(dir, "POWER_CUT_RECOVERED").exists())) continue;
            long size = directorySize(dir);
            if (deleteRecursively(dir)) {
                completeCount--;
                total = Math.max(0L, total - size);
            }
        }
    }

    private static long directorySize(File file) {
        if (file == null || !file.exists()) return 0L;
        if (file.isFile()) return file.length();
        long total = 0L;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) total += directorySize(child);
        }
        return total;
    }

    private static boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) return true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) {
                if (!deleteRecursively(child)) return false;
            }
        }
        return file.delete();
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try { closeable.close(); } catch (IOException ignored) { }
    }

    private static String stateSummary(EngineSemanticState state) {
        return mainName(state.main.ordinal()) + "|" + thermalName(state.thermal.ordinal())
                + "|" + modifierName(state.modifier.ordinal()) + "|"
                + shiftName(state.shiftPhase.ordinal()) + "|"
                + combustionName(state.combustion.ordinal());
    }

    private static String mainName(int i) { return name(MAIN_NAMES, i); }
    private static String thermalName(int i) { return name(THERMAL_NAMES, i); }
    private static String subName(int i) { return name(SUB_NAMES, i); }
    private static String modifierName(int i) { return name(MODIFIER_NAMES, i); }
    private static String shiftName(int i) { return name(SHIFT_NAMES, i); }
    private static String combustionName(int i) { return name(COMBUSTION_NAMES, i); }

    private static String name(String[] names, int i) {
        return i >= 0 && i < names.length ? names[i] : "UNKNOWN";
    }

    private static String bool(boolean v) { return v ? "1" : "0"; }

    private static String doubleText(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? "" : Double.toString(v);
    }

    private static String floatText(float v) {
        return Float.isNaN(v) || Float.isInfinite(v) ? "" : Float.toString(v);
    }

    private static String csv(String value) {
        if (value == null) return "";
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0) return value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String hex2(int value) {
        String s = Integer.toHexString(value & 0xFF).toUpperCase(Locale.US);
        return s.length() < 2 ? "0" + s : s;
    }

    private static String hex4(int value) {
        String s = Integer.toHexString(value & 0xFFFF).toUpperCase(Locale.US);
        while (s.length() < 4) s = "0" + s;
        return s;
    }

    private static final class TraceSlot {
        long sequence;
        long elapsedMs;
        long wallMs;
        int manifestHash;
        int channelCount;
        final double[] channels = new double[MAX_CHANNELS];
        int main;
        int thermal;
        int sub;
        int modifier;
        int shift;
        int combustion;
        float confidence;
        boolean holdAf;
        boolean holdIgn;
        boolean holdStrim;
        boolean releasedAf;
        boolean releasedIgn;
        boolean releasedStrim;
        boolean frontGuard;
        float afEffective;
        float ignEffective;
        float strimEffective;
        float strimWeight;
        boolean fpObservable;
        long fpLowObservedMs;
        long fpPauseAgeMs;
        boolean fpEvidenceActive;
        boolean fpAlert;
        boolean boostEventActive;
        boolean hasLastBoostEventPeak;
        float lastBoostEventPeak;
    }

    private static final class EventSlot {
        long elapsedMs;
        long wallMs;
        int manifestHash;
        String type;
        String from;
        String to;
    }

    private static final class ExtremaSlot {
        long elapsedMs;
        long wallMs;
        int manifestHash;
        int card;
        boolean max;
        float oldValue;
        float newValue;
        boolean initialization;
        int main;
        int thermal;
        int shift;
        int combustion;
    }
}
