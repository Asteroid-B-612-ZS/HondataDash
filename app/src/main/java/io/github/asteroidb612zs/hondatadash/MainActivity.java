package io.github.asteroidb612zs.hondatadash;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.graphics.PorterDuff;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import io.github.asteroidb612zs.hondatadash.data.BluetoothSource;
import io.github.asteroidb612zs.hondatadash.data.DataSource;
import io.github.asteroidb612zs.hondatadash.data.HondataProtocol;
import io.github.asteroidb612zs.hondatadash.data.DemoSource;
import io.github.asteroidb612zs.hondatadash.data.EngineSemanticState;
import io.github.asteroidb612zs.hondatadash.data.EngineStateTracker;
import io.github.asteroidb612zs.hondatadash.data.CombustionDisplayAdmission;
import io.github.asteroidb612zs.hondatadash.data.FuelPressureAlertTracker;
import io.github.asteroidb612zs.hondatadash.data.TrustedDisplayMemory;
import io.github.asteroidb612zs.hondatadash.data.EphemeralDiagnosticMemory;
import io.github.asteroidb612zs.hondatadash.data.SensorData;
import io.github.asteroidb612zs.hondatadash.diagnostic.FlightRecorder;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import android.os.SystemClock;

/**
 * 主界面 - 固定像素槽位车载仪表盘 V2.0。
 *
 * 4x2 HUD 网格 (英文缩写 + 刻度进度条 + MAX/MIN):
 *   Ethanol | ECT | IAT | L.TRIM
 *   MAP     | A/F | IGN | S.TRIM
 *
 * 底部: K.C + CYL1-4 + K.R + K.L + BAT + F.P + W.G + T.P
 */
public class MainActivity extends Activity implements DataSource.Callback {

    private static final boolean USE_DEMO = false;
    private static final int REQUEST_ENABLE_BLUETOOTH = 1001;
    private static final int REQUEST_DIAGNOSTIC_STORAGE = 1002;
    private static final String BLUETOOTH_PREFS = "bluetooth_device";
    private static final String PREF_DEVICE_ADDRESS = "address";
    private static final String PREF_DEVICE_NAME = "name";

    private DataSource dataSource;
    // IT2 observer-only flight recorder. It never participates in display decisions.
    private FlightRecorder flightRecorder;
    private TextView statusText;
    private TextView sourceName;
    private View connectionStatus;
    private View statusDot;
    private StartupOverlayView startupOverlay;
    private static boolean startupShown;
    private long connectedSinceMs;
    private String selectedBluetoothAddress;
    private boolean bluetoothDialogShowing;
    private boolean bluetoothEnableRequestInFlight;
    private boolean bluetoothEnableDeclined;
    private boolean returningFromBluetoothSettings;

    // 主卡片 (4x2 = 8个)
    private final TextView[] labelEnViews = new TextView[8];
    private final TextView[] valueIntViews = new TextView[8];
    private final TextView[] valueDecViews = new TextView[8];
    private final TextView[] unitViews = new TextView[8];
    private final ScaleBarView[] scaleBars = new ScaleBarView[8];

    // MAX / MIN 追踪
    private final TextView[] maxValueViews = new TextView[8];
    private final TextView[] minValueViews = new TextView[8];
    private int extremeTextColor;
    private final float[] maxTrack = new float[8];      // Session Peak (内部)
    private final float[] minTrack = new float[8];
    private final float[] recentMax = new float[8];     // Recent Peak (显示用, 30s衰减)
    private final float[] recentMin = new float[8];
    private final long[] lastMaxTime = new long[8];     // Cooldown 时间戳
    private final long[] lastMinTime = new long[8];
    private final long[] recentMaxTime = new long[8];   // Recent Peak 记录时间
    private final long[] recentMinTime = new long[8];
    private final boolean[] hasValue = new boolean[8];

    // Geometry belongs to FittedTextView, including relayout with unchanged data.
    private final View[] extremePanelViews = new View[8];
    private final boolean[] semanticMode = new boolean[8];
    private static final String[][] MAIN_WIDTH_REFERENCE = {
        {"E888", "--"}, {"888", "-88", "--"}, {"888", "-88", "--"},
        {"+88.8", "-88.8", "--"}, {"+8.88", "-8.88", "--"}, {"88.8", "--"},
        {"+88.8", "-88.8", "--"}, {"+88.8", "-88.8", "--"}
    };
    private final ColorRecovery colorRecovery = new ColorRecovery();
    private final int[] resolvedMainColors = new int[8];
    private long lastAuxiliaryUpdateMs;

    // RC8 S.TRIM interpretability-weighted presentation. The number stays live when
    // possible; visual authority changes continuously instead of binary LIVE/HOLD text.
    private float strimPresentationWeight = 1f;
    private long strimPresentationUpdatedMs = 0L;
    private static final float STRIM_WEIGHT_TRUSTED = 1.00f;
    private static final float STRIM_WEIGHT_CONTEXT = 0.82f;
    private static final float STRIM_WEIGHT_LOW = 0.62f;

    // Design ceiling, not an unconditional height: actual ink must fit both axes.
    private static final float MAIN_VALUE_SP = 106f;

    private boolean isSignedMainCard(int i) {
        return i == 3 || i == 4 || i == 6 || i == 7;
    }

    // History Admission 参数表: [MAX cooldown, MIN cooldown] (ms)
    private static final long[][] COOLDOWN_MS = {
        {3000, 3000},  // 0 Ethanol
        {2000, 2000},  // 1 ECT
        {1500, 1500},  // 2 IAT
        {3000, 3000},  // 3 L.TRIM
        {500,  1500},  // 4 Boost (MIN=真空, 低优先级)
        {300,  600},   // 5 A/F (MAX=Lean危险→快, MIN=Rich保护→慢)
        {1000, 500},   // 6 IGN (MIN=退角事件→快)
        {500,  500},   // 7 S.TRIM
    };
    // WOT 时缩短的 cooldown
    private static final long[][] COOLDOWN_WOT_MS = {
        {3000, 3000},  // Ethanol — 不变
        {2000, 2000},  // ECT — 不变
        {1500, 1500},  // IAT — 不变
        {3000, 3000},  // L.TRIM — 不变
        {250,  750},   // Boost
        {150,  300},   // A/F
        {500,  250},   // IGN
        {250,  250},   // S.TRIM
    };
    // Breakthrough 语义阈值 (绝对值): [MAX threshold, MIN threshold]
    private static final float[][] BREAKTHROUGH = {
        {5f,    5f},    // Ethanol
        {3f,    3f},    // ECT
        {5f,    5f},    // IAT
        {2f,    2f},    // L.TRIM
        {0.15f, 0.15f}, // Boost
        {0.5f,  1.0f},  // A/F (MAX: lean+0.5, MIN: rich-1.0)
        {5f,    3f},    // IGN (MAX: +5°, MIN: -3°)
        {4f,    4f},    // S.TRIM
    };
    // RC8 semantic retention: visual geometry remains exactly RC7-style with two
    // extreme slots on every card. What changes is only which samples are admitted
    // and how long recent extrema remain authoritative. Session-extreme cards do not
    // use these hold values.
    private static final long[] RECENT_PEAK_HOLD_MS = {
        0L,      // Ethanol: settled session MAX/MIN
        0L,      // ECT: warmed-session MAX/MIN
        0L,      // IAT: warmed-session MAX/MIN
        0L,      // L.TRIM: stable closed-loop session range
        8000L,   // MAP: recent meaningful boost/vacuum range
        8000L,   // A/F: recent valid-combustion HI/LO
        10000L,  // IGN: recent meaningful advance/retard range
        12000L   // S.TRIM: recent stable-closed-loop range
    };
    private static final int EXTREME_NONE = 0;
    private static final int EXTREME_MAX = 1;
    private static final int EXTREME_MIN = 2;
    private static final int[] EXTREME_POLICY = {
        EXTREME_MAX | EXTREME_MIN,   // Ethanol: settled concentration range
        EXTREME_MAX | EXTREME_MIN,   // ECT: warmed-session thermal range
        EXTREME_MAX | EXTREME_MIN,   // IAT: warmed-session thermal range
        EXTREME_MAX | EXTREME_MIN,   // L.TRIM: stable learned range
        EXTREME_MAX | EXTREME_MIN,   // MAP: meaningful boost and vacuum extrema
        EXTREME_MAX | EXTREME_MIN,   // A/F: only valid-combustion extrema are admitted
        EXTREME_MAX | EXTREME_MIN,   // IGN: context-qualified advance/retard extrema
        EXTREME_MAX | EXTREME_MIN    // S.TRIM: only stable closed-loop samples are admitted
    };
    // V2.0: decay is time-based (τ = 3 s, dt clamped to 1 s) so 20 Hz and 50 Hz
    // inputs regress at the same rate. MAX and MIN keep independent decay clocks:
    // one side sitting inside its hold window must not reset the other's clock.
    private static final float RECENT_DECAY_TAU_MS = 3000f;
    private final long[] recentMaxDecayTimeMs = new long[8];
    private final long[] recentMinDecayTimeMs = new long[8];

    // IT3: MAP MAX is no longer a rolling/decaying maximum. It is the exact peak
    // from the most recent meaningful boost event and remains latched until the
    // next boost event begins. MAP MIN keeps the existing recent-vacuum behaviour.
    private static final float BOOST_EVENT_START_BAR = 0.20f;
    private static final float BOOST_EVENT_KEEPALIVE_BAR = 0.10f;
    private static final float BOOST_EVENT_START_TP = 35f;
    private static final float BOOST_EVENT_KEEPALIVE_TP = 20f;
    private static final long BOOST_EVENT_END_GRACE_MS = 1800L;
    private boolean boostEventActive = false;
    private boolean hasLastBoostEventPeak = false;
    private float lastBoostEventPeak = Float.NaN;
    private long boostEventLastDemandMs = 0L;

    // Session vs recent storage is independent from the visible label geometry.
    // RC7's visual labels are preserved exactly: cards 0..4 use MAX/MIN, cards 5..7
    // use HI/LO. MAP keeps RC7 MAX/MIN labels while its values now use recent semantic
    // retention rather than an all-session mathematical extreme.
    private static final boolean[] SHOW_SESSION_EXTREME = {
        true,   // 0 Ethanol — settled session range
        true,   // 1 ECT — warmed-session thermal range
        true,   // 2 IAT — warmed-session thermal range
        true,   // 3 L.TRIM — stable learned session range
        false,  // 4 MAP — recent meaningful boost/vacuum range
        false,  // 5 A/F — recent valid-combustion range
        false,  // 6 IGN — recent context-qualified range
        false   // 7 S.TRIM — recent stable-closed-loop range
    };
    private static final boolean[] RC7_EXTREME_LABEL_SESSION_STYLE = {
        true, true, true, true, true, false, false, false
    };

    // RC6: legacy semantic text renderer remains only for backward visual compatibility;
    // live combustion validity is owned by CombustionDisplayAdmission.
    private static final int COLOR_SEMANTIC_SYNC = 0xFFA0A0A0;
    private static final float ALPHA_SEMANTIC_SYNC = 1.0f;
    // Lambda-based A/F thresholds
    // V2.6.8 (M3): WOT A/F 阈值收紧 (原 0.86/0.83 → 0.84/0.81), 更早发现过稀
    // 逻辑顺序: lambda>DANGER(高)→红, lambda>WARN(低)→黄
    // 必须 DANGER > WARN, 否则 WARN 永远到不了
    private static final float WOT_LAMBDA_DANGER_LEAN = 0.84f;  // ~12.3 AFR (危险红)
    private static final float WOT_LAMBDA_WARN_LEAN   = 0.81f;  // ~11.9 AFR (偏稀黄)
    private static final float WOT_LAMBDA_WARN_RICH   = 0.68f;  // ~10.0 AFR (过浓黄)
    private static final float CL_LAMBDA_ERR_GREEN = 0.03f;
    private static final float CL_LAMBDA_ERR_WARN  = 0.06f;

    // V2.2: 数据新鲜度追踪 — 独立 Handler 250ms 刷新, 不依赖 onDataReceived
    private long lastValidFrameTimeMs = 0L;
    private final Handler freshnessHandler = new Handler(Looper.getMainLooper());
    private final Runnable freshnessRunnable = new Runnable() {
        @Override public void run() {
            updateFreshnessStatus();
            freshnessHandler.postDelayed(this, 250);
        }
    };

    // 爆震缸 (4个)
    private final TextView[] knockValues = new TextView[4];

    // 爆震控制值
    private TextView knockRetValue;

    // Honda-style 5+5 转速提示灯
    private ShiftLightView shiftLight;

    // 温度 & A/F 闪烁控制
    private final Handler flashHandler = new Handler(Looper.getMainLooper());
    private boolean flashVisible = true;
    private boolean flashScheduled;
    private boolean foreground;
    private boolean activityDestroyed;
    private boolean displayDataStale = true;
    private final boolean[] frameValid = new boolean[8];
    private final boolean[] auxiliaryValid = new boolean[11];
    private TextView[] auxiliaryViews;
    private static final int[] AUXILIARY_PIDS = {
        0x412, 0x421, 0x422, 0x423, 0x424, 0x410, 0x411, 0x180, 0x191, 0x1A0, 0x122
    };
    private boolean rpmFrameValid;
    private boolean ectFlashing = false;
    private boolean iatFlashing = false;
    private boolean afFlashing = false;
    private boolean kcFlashing = false;
    private boolean fpFlashing = false;
    // CYL 爆震闪烁: 记录每个缸的 knock count, 检测增量
    // V2.6.8 (BG1): 用 -1 表示首帧基线未建立, 避免首帧 ECU 累积值触发假爆震闪烁
    private final int[] lastKnockCount = new int[4];
    private final long[] cylYellowEnd = new long[4];    // 黄色闪烁截止时间
    private boolean cylRedFlashing = false;
    private int cylRapidAccum = 0;                        // 快速累积计数
    private long cylRapidStart = 0;                       // 快速累积起始时间
    private float lastEctVal = 0;
    private float lastIatVal = 0;
    private float lastAfVal = 14.7f;
    private float lastTpPlate = 0;  // 节气门开度, 用于排除滑行/换挡
    private float lastBaro = 101.3f; // 大气压力 kPa, 用于 Boost 相对压力

    // RC6: engine semantics + combustion display admission are separate layers.
    private final EngineStateTracker engineState = new EngineStateTracker();
    private final CombustionDisplayAdmission combustionAdmission = new CombustionDisplayAdmission();
    private final FuelPressureAlertTracker fuelPressureAlert = new FuelPressureAlertTracker();
    // RC7: tiny RAM-only memories. No disk logging, no frame-path allocation.
    private final TrustedDisplayMemory trustedDisplayMemory = new TrustedDisplayMemory();
    private final EphemeralDiagnosticMemory diagnosticMemory = new EphemeralDiagnosticMemory();

    // 上一次 ScaleBar 输入值 (用于计算 delta 驱动情绪)
    private final float[] lastScaleVal = new float[8];

    // Last-valid cache is the visible truth source while combustion PIDs are inadmissible.
    private final float[] lastValidValue = new float[8];
    private final boolean[] hasValidValue = new boolean[8];
    private final boolean[] displayHoldMode = new boolean[8];
    private final Runnable flashTick = new Runnable() {
        @Override public void run() {
            flashScheduled = false;
            if (!foreground || !isDataFresh()) return;
            flashVisible = !flashVisible;
            applyAlertAndFreshnessVisuals();
            updateFlashState();
        }
    };

    // 底部数据文本
    private TextView bottomTrimValue;   // K.R
    private TextView bottomAfmValue;    // K.L
    private TextView bottomBatValue;    // BAT
    private TextView bottomFpValue;     // F.P (Fuel Pressure)
    private TextView bottomWgValue;     // W.G (Wastegate)
    private TextView bottomTpValue;     // T.P (Throttle Plate)

    // V2.6.4: 发动机运转极值门控 — RPM≥500 持续 1s 后才记录 L.TRIM/MAP/A.F/IGN/S.TRIM 极值
    private static final float ENGINE_RUNNING_RPM_THRESHOLD = 500f;
    private static final long ENGINE_RUNNING_STABLE_MS = 1000L;
    private long engineRunningSinceMs = 0L;
    private boolean engineRunningStable = false;

    // V2.6.6: 发动机极值 session — 每次发动机运行周期重置发动机相关极值
    private static final float ENGINE_STOPPED_RPM_THRESHOLD = 300f;
    private static final long ENGINE_STOPPED_STABLE_MS = 5000L;
    private boolean engineExtremeSessionActive = false;
    private long engineStoppedSinceMs = 0L;

    // V2.6.6: Ethanol 连接后爬升门控 — 防止 CANFlex 初始化 0→真实值 污染 MIN
    private static final long ETHANOL_SETTLE_MIN_MS = 3000L;
    private static final long ETHANOL_STABLE_MS = 1500L;
    private static final float ETHANOL_STABLE_DELTA = 0.3f;
    private long ethanolFirstSeenMs = 0L;
    private long ethanolStableSinceMs = 0L;
    private float lastEthanolForGate = Float.NaN;
    private boolean ethanolExtremeReady = false;

    // V2.6.7: A/F / IGN / S.TRIM 低置信度灰色模式 — 固定颜色+固定 alpha
    private static final float LOW_CONFIDENCE_THRESHOLD = 0.78f;
    private static final int COLOR_LOW_CONFIDENCE = 0xFF888888;
    private static final float LOW_CONFIDENCE_ALPHA = 1.0f;
    private static final float WARMUP_ECT_THRESHOLD = 70f;
    private static final long HOT_START_CONFIDENCE_SUPPRESS_MS = 3000L;

    // V2.7.0: 主数据语义颜色 — 复用现有 UI 色号，避免风格割裂
    private static final int COLOR_TEXT_NORMAL = 0xFFE8EEF2;
    private static final int COLOR_SAFE        = 0xFF3FB950;
    private static final int COLOR_WARN        = 0xFFD29922;
    private static final int COLOR_DANGER      = 0xFFFF4444;
    private static final int COLOR_INFO_BLUE   = 0xFF00D8FF;

    // V2.7.0: L.TRIM / S.TRIM 语义阈值 (按偏离 0 的绝对值)
    private static final float TRIM_GREEN_ABS_MAX = 5f;
    private static final float TRIM_WARN_ABS_MAX  = 15f;

    // V2.7.0: IGN 语义阈值
    private static final float IGN_GREEN_MIN = 0f;
    private static final float IGN_WARN_MIN  = -5f;

    // V2.7.0: MAP 主卡片显示为相对增压 bar，不是 absolute kPa
    private static final float MAP_GREEN_MAX = 1.45f;
    private static final float MAP_WARN_MAX  = 1.60f;

    // V2.6.7: 每个发动机运行周期，每个主卡片只允许一次 engine baseline 覆盖
    private final boolean[] engineBaselineApplied = new boolean[8];
    private long engineExtremeSessionStartMs = 0L;

    // V2.6.7: 蓝牙短断 session 保护 — 5分钟内重连不清极值
    private static final long BT_SESSION_PRESERVE_MS = 5 * 60 * 1000L;
    private long lastBtDisconnectedAtMs = 0L;
    private boolean btWasDisconnected = false;

    // V2.6.9 (P0-3): 长断重连但发动机在跑时的视觉抑制窗口
    // 此期间灰显逻辑跳过 (给数据一个稳定窗口), 但不动 MAX/MIN/baseline
    private static final long RECONNECT_VISUAL_SUPPRESS_MS = 3000L;
    private long reconnectVisualSuppressUntilMs = 0L;

    // ===== 卡片配置 =====
    // 第1行 (慢数据): Ethanol, ECT, IAT, L.TRIM
    // 第2行 (快数据): MAP, A/F, IGN, S.TRIM
    private static final int[] CARD_PIDS = {
        0xB03, 0x160, 0x151, 0x332,   // 第1行: Ethanol, ECT, IAT, L.TRIM
        0x110, 0x320, 0x140, 0x330    // 第2行: MAP, A/F, IGN, S.TRIM
    };

    private static final String[] CARD_EN = {
        "ETHANOL", "ECT", "IAT", "L.TRIM",
        "MAP", "A/F", "IGN", "S.TRIM"
    };

    private static final String[] CARD_UNIT = {
        "%", "\u00B0C", "\u00B0C", "%",
        "bar", "", "\u00B0", "%"
    };

    // 爆震缸 PID (Knock Count 1-4)
    private static final int[] KNOCK_PIDS = {0x421, 0x422, 0x423, 0x424};

    // 爆震控制 PID (Knock Control %)
    private static final int KNOCK_CTRL_PID = 0x412;

    // 底部 PID
    private static final int BAT_PID = 0x180;
    private static final int FP_PID = 0x191;    // Fuel Pressure kPa
    private static final int FP_TARGET_PID = 0x190; // Fuel Pressure Target kPa
    private static final int WG_PID = 0x1A0;    // Wastegate CMD %
    private static final int TP_PID = 0x122;    // Throttle Plate %

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // V2.6.8 (BG1): 初始化 lastKnockCount 为 -1, 表示首帧基线未建立
        Arrays.fill(lastKnockCount, -1);

        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);
        extremeTextColor = getResources().getColor(R.color.dash_extreme_value);

        statusText = (TextView) findViewById(R.id.statusText);
        sourceName = (TextView) findViewById(R.id.sourceName);
        connectionStatus = findViewById(R.id.connectionStatus);
        statusDot = findViewById(R.id.statusDot);
        ((FittedTextView) statusText).setFitReference(false, false,
                "CONNECTING", "INITIALIZING", "RECONNECTING", "DATA LOST");
        if (statusText != null && !USE_DEMO) {
            View.OnClickListener selectBluetoothListener = new View.OnClickListener() {
                @Override public void onClick(View view) {
                    bluetoothEnableDeclined = false;
                    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                    if (adapter != null && adapter.isEnabled()) {
                        showBluetoothDeviceChooser(false);
                    } else {
                        connectBluetooth();
                    }
                }
            };
            // 保留文字本身的点击能力，同时把完整 108dp 顶栏区域作为触控目标。
            statusText.setContentDescription("蓝牙状态，轻触选择或更换 FlashPro");
            statusText.setOnClickListener(selectBluetoothListener);
            if (connectionStatus != null) {
                connectionStatus.setClickable(true);
                connectionStatus.setOnClickListener(selectBluetoothListener);
            }
        }

        // 初始化 MAX/MIN 追踪
        for (int i = 0; i < 8; i++) {
            hasValue[i] = false;
            lastMaxTime[i] = 0;
            lastMinTime[i] = 0;
            recentMaxTime[i] = 0;
            recentMinTime[i] = 0;
        }

        // 初始化 8 个主卡片
        int[] cardIds = {
            R.id.card0, R.id.card1, R.id.card2, R.id.card3,
            R.id.card4, R.id.card5, R.id.card6, R.id.card7
        };

        for (int i = 0; i < cardIds.length; i++) {
            View card = findViewById(cardIds[i]);

            labelEnViews[i] = (TextView) card.findViewById(R.id.labelEn);
            valueIntViews[i] = (TextView) card.findViewById(R.id.valueInt);
            valueDecViews[i] = (TextView) card.findViewById(R.id.valueDec);
            unitViews[i] = (TextView) card.findViewById(R.id.unit);
            scaleBars[i] = (ScaleBarView) card.findViewById(R.id.scaleBar);
            maxValueViews[i] = (TextView) card.findViewById(R.id.maxValue);
            minValueViews[i] = (TextView) card.findViewById(R.id.minValue);
            extremePanelViews[i] = card.findViewById(R.id.extremePanel);

            if (labelEnViews[i] != null) labelEnViews[i].setText(CARD_EN[i]);
            if (unitViews[i] != null) {
                String u = CARD_UNIT[i];
                unitViews[i].setText(u);
            }
            tightenTitleLine(i);

            if (valueDecViews[i] != null) {
                valueDecViews[i].setVisibility(View.GONE);
                valueDecViews[i].setText("");
            }

            configureMainText(i);
            String[] extremeReferences = i == 0 ? new String[] {"888", "--"}
                    : MAIN_WIDTH_REFERENCE[i];
            ((FittedTextView) maxValueViews[i]).setFitReference(true, false, extremeReferences);
            ((FittedTextView) minValueViews[i]).setFitReference(true, false, extremeReferences);

            TextView highLabel = (TextView) card.findViewById(R.id.highLabel);
            TextView lowLabel = (TextView) card.findViewById(R.id.lowLabel);
            if (highLabel != null) {
                highLabel.setText(RC7_EXTREME_LABEL_SESSION_STYLE[i] ? "MAX" : "HI");
                highLabel.setVisibility(View.VISIBLE);
            }
            if (lowLabel != null) {
                lowLabel.setText(RC7_EXTREME_LABEL_SESSION_STYLE[i] ? "MIN" : "LO");
                lowLabel.setVisibility(View.VISIBLE);
            }
            if (maxValueViews[i] != null) maxValueViews[i].setVisibility(View.VISIBLE);
            if (minValueViews[i] != null) minValueViews[i].setVisibility(View.VISIBLE);
            configureScaleBar(i);
        }

        // 初始化底部: 爆震控制值
        knockRetValue = (TextView) findViewById(R.id.knockRetValue);

        // 初始化 4 个爆震缸
        int[] knockIds = {R.id.knock0, R.id.knock1, R.id.knock2, R.id.knock3};
        String[] knockLabels = {"CYL1", "CYL2", "CYL3", "CYL4"};

        for (int i = 0; i < knockIds.length; i++) {
            View k = findViewById(knockIds[i]);
            knockValues[i] = (TextView) k.findViewById(R.id.knockValue);

            TextView kl = (TextView) k.findViewById(R.id.knockLabel);
            if (kl != null) kl.setText(knockLabels[i]);
        }

        // Honda-style 5+5 转速提示灯
        shiftLight = (ShiftLightView) findViewById(R.id.shiftLight);

        // 底部数据文本
        bottomTrimValue = (TextView) findViewById(R.id.bottomTrimValue);
        bottomAfmValue = (TextView) findViewById(R.id.bottomAfmValue);
        bottomBatValue = (TextView) findViewById(R.id.bottomBatValue);
        bottomFpValue = (TextView) findViewById(R.id.bottomFpValue);
        bottomWgValue = (TextView) findViewById(R.id.bottomWgValue);
        bottomTpValue = (TextView) findViewById(R.id.bottomTpValue);
        auxiliaryViews = new TextView[] {knockRetValue, knockValues[0], knockValues[1],
                knockValues[2], knockValues[3], bottomTrimValue, bottomAfmValue,
                bottomBatValue, bottomFpValue, bottomWgValue, bottomTpValue};

        // Fixed normal-range references keep auxiliary numbers from breathing too.
        // Longer genuine values are still shown in full, never truncated to this range.
        String[] auxiliaryReferences = {
            "888", "88888", "88888", "88888", "88888",
            "-88.8", "888.8", "88.8", "888.8", "888", "888"
        };
        for (int i = 0; i < auxiliaryViews.length; i++) {
            ((FittedTextView) auxiliaryViews[i]).setFitReference(true, false,
                    auxiliaryReferences[i], "--");
        }

        // IT2: persistent observer-only evidence under public internal storage.
        // Permission is requested once on API 23+ using reflection so API17 static
        // compilation remains valid. Denial only disables recording, never the dash.
        File diagnosticRoot = new File(Environment.getExternalStorageDirectory(),
                "HondataDash/Diagnostics");
        flightRecorder = new FlightRecorder(diagnosticRoot);
        boolean diagnosticStorageReady = hasDiagnosticStoragePermission();
        flightRecorder.setEnabled(diagnosticStorageReady);
        if (!diagnosticStorageReady) requestDiagnosticStoragePermission();

        // 数据源
        if (USE_DEMO) {
            dataSource = new DemoSource();
        } else {
            BluetoothSource btSource = new BluetoothSource();
            btSource.setDiagnosticObserver(flightRecorder);
            dataSource = btSource;
        }
        dataSource.setCallback(this);
        // Synthetic data must remain unmistakable; production hides the redundant source label.
        sourceName.setText(USE_DEMO ? "DEMO" : "");
        sourceName.setVisibility(USE_DEMO ? View.VISIBLE : View.GONE);

        setConnectionStatus("CONNECTING", COLOR_WARN);

        freshnessHandler.post(freshnessRunnable);

        // Presentation runs in parallel; onResume still owns Bluetooth startup.
        startupOverlay = (StartupOverlayView) findViewById(R.id.startupOverlay);
        boolean playStartup = !startupShown && savedInstanceState == null;
        startupShown = true;
        if (playStartup) startupOverlay.start((ViewGroup) findViewById(R.id.dashboardContent));
    }

    /**
     * 配置每个卡片的刻度进度条 + 动力学原型。
     *
     * 四类原型, 每类完全不同的数学结构:
     *   STATIC:    锁定态, 无能量 (Ethanol)
     *   THERMAL:   牛顿冷却, 非线性散热 (ECT/IAT/L.TRIM)
     *   MECHANICAL: Spring-Damper, 过冲+回弹 (Boost/IGN)
     *   TRANSIENT: Oscillation Envelope, 呼吸包络 (A/F/S.TRIM)
     */
    /**
     * Reference-photo title lock: keep the unit visually attached to the short
     * English label instead of pinning it to the far right of the title row.
     * This only changes measured presentation width; it never touches sensor data.
     */
    private void tightenTitleLine(int i) {
        TextView label = labelEnViews[i];
        TextView unit = unitViews[i];
        if (label == null || unit == null) return;

        final float density = getResources().getDisplayMetrics().density;
        ViewGroup.LayoutParams labelLp = label.getLayoutParams();
        if (labelLp != null) {
            int min = Math.round(20f * density);
            int max = Math.round(105f * density);
            int measured = (int) Math.ceil(label.getPaint().measureText(CARD_EN[i])) + Math.round(2f * density);
            labelLp.width = Math.max(min, Math.min(max, measured));
            label.setLayoutParams(labelLp);
        }

        String unitText = CARD_UNIT[i];
        ViewGroup.LayoutParams unitLp = unit.getLayoutParams();
        if (unitLp != null) {
            unitLp.width = unitText.length() == 0 ? 0
                    : (int) Math.ceil(unit.getPaint().measureText(unitText)) + Math.round(3f * density);
            if (unitLp instanceof ViewGroup.MarginLayoutParams) {
                ((ViewGroup.MarginLayoutParams) unitLp).leftMargin = unitText.length() == 0
                        ? 0 : Math.round(6f * density);
            }
            unit.setLayoutParams(unitLp);
        }
    }

    private void configureScaleBar(int i) {
        ScaleBarView bar = scaleBars[i];
        if (bar == null) return;

        switch (i) {
            case 0: // Ethanol: STATIC — 锁定态, 无能量系统
                bar.setRange(0, 100);
                bar.setTicks(
                    new float[]{0, 20, 40, 60, 80, 100},
                    new String[]{"0", "20", "40", "60", "80", "100"});
                bar.addZone(0, 20, DashboardPalette.SCALE_NEUTRAL);
                bar.addGradientZone(20, 35, DashboardPalette.SCALE_LOAD_MID, DashboardPalette.SCALE_GREEN);
                bar.addGradientZone(35, 60, DashboardPalette.SCALE_GREEN, DashboardPalette.SCALE_CYAN);
                bar.addGradientZone(60, 100, DashboardPalette.SCALE_CYAN, DashboardPalette.SCALE_WARM);
                bar.setAnchor(0);
                bar.setExpand(0, 40, 2.0f);
                bar.setStatic();
                break;

            case 1: // ECT: THERMAL — 牛顿冷却, 热积累/非线性散热
                bar.setRange(20, 120);
                bar.setTicks(
                    new float[]{20, 40, 60, 80, 100, 120},
                    new String[]{"20", "40", "60", "80", "100", "120"});
                bar.addGradientZone(20, 65, DashboardPalette.SCALE_BLUE, DashboardPalette.SCALE_CYAN);
                bar.addZone(65, 92, DashboardPalette.SCALE_CYAN);
                bar.addGradientZone(92, 98, DashboardPalette.SCALE_CYAN, DashboardPalette.SCALE_WARM);
                bar.addGradientZone(98, 105, DashboardPalette.SCALE_WARM, DashboardPalette.SCALE_AMBER);
                bar.addGradientZone(105, 110, DashboardPalette.SCALE_RED, DashboardPalette.SCALE_PURPLE);
                bar.addZone(110, 120, DashboardPalette.SCALE_PURPLE);
                bar.setAnchor(20);
                // V2.0: OEM direct display — value fills straight from the low end.
                // Thermal memory / residual animation retired; alarms live on the digits.
                bar.setStatic();
                break;

            case 2: // IAT: THERMAL — 热浸, 散热比ECT略快
                bar.setRange(-20, 80);
                bar.setTicks(
                    new float[]{-20, -10, 0, 10, 20, 30, 40, 50, 60, 70, 80},
                    new String[]{"−20", null, "0", null, "20", null, "40", null, "60", null, "80"});
                bar.addGradientZone(-20, 10, DashboardPalette.SCALE_BLUE, DashboardPalette.SCALE_CYAN);
                bar.addZone(10, 45, DashboardPalette.SCALE_NEUTRAL);
                bar.addGradientZone(45, 55, DashboardPalette.SCALE_WARM, DashboardPalette.SCALE_AMBER);
                bar.addZone(55, 65, DashboardPalette.SCALE_RED);
                bar.addZone(65, 80, DashboardPalette.SCALE_PURPLE);
                // Fill continuously from the lower endpoint; zero is a scale
                // reference, not the origin of a bipolar correction meter.
                bar.setAnchor(-20);
                bar.setTickReference(0);
                bar.setStatic();
                break;

            case 3: // L.TRIM: THERMAL — ECU学习值, 极慢热容
                bar.setRange(-25, 25);
                bar.setTicks(
                    new float[]{-25, -15, -5, 0, 5, 15, 25},
                    new String[]{"−25", "−15", null, "0", null, "+15", "+25"});
                addTrimScaleZones(bar);
                bar.setAnchor(0);
                bar.setStatic();
                break;

            case 4: // Boost: MECHANICAL — Spring-Damper + Peak Hold
                bar.setRange(-1.0f, 2.0f);
                bar.setTicks(
                    new float[]{-1.0f, 0, 0.5f, 1.0f, 1.5f, 2.0f},
                    new String[]{"−1.0", "0", "+0.5", "+1.0", null, "+2.0"});
                // RC5 load semantics: 0~0.6 bar stays calm; 0.6~1.4 bar
                // progressively gains luminance/saturation as real engine load rises.
                // Amber/red remain reserved for the existing warning thresholds.
                bar.addGradientZone(-1.0f, 0f, DashboardPalette.SCALE_BLUE, DashboardPalette.SCALE_CYAN);
                bar.addZone(0f, 0.60f, DashboardPalette.SCALE_LOAD_LOW);
                bar.addGradientZone(0.60f, 1.00f, DashboardPalette.SCALE_LOAD_LOW, DashboardPalette.SCALE_LOAD_MID);
                bar.addGradientZone(1.00f, 1.20f, DashboardPalette.SCALE_LOAD_MID, DashboardPalette.SCALE_LOAD_HIGH);
                bar.addGradientZone(1.20f, 1.40f, DashboardPalette.SCALE_LOAD_HIGH, DashboardPalette.SCALE_LOAD_PEAK);
                bar.addGradientZone(1.40f, 1.45f, DashboardPalette.SCALE_LOAD_PEAK, DashboardPalette.SCALE_AMBER);
                bar.addZone(1.45f, 1.60f, DashboardPalette.SCALE_AMBER);
                bar.addGradientZone(1.60f, 2.0f, DashboardPalette.SCALE_RED, 0xFFB63C4A);
                bar.setAnchor(0);
                bar.setExpand(0, 1.5f, 2.0f);
                bar.setStatic();
                break;

            case 5: // A/F: V2.8.0 TARGET/ACTUAL gauge — linear 7~20, no 14.7 expansion.
                // Safety stays with getAfColorByLambda(); the bar must NOT imply
                // "14.7 is always right" or paint fixed rainbow danger zones.
                bar.setRange(7.0f, 20.0f);
                bar.setTicks(
                    new float[]{7, 10, 12, 14, 16, 18, 20},
                    new String[]{"7", "10", "12", "14", "16", "18", "20"});
                bar.setAnchor(7.0f);   // plain fill origin only; NOT a "safe centre"
                bar.setTargetTracking(true);
                bar.setStatic();
                break;

            case 6: // IGN: MECHANICAL — Spring-Damper + Peak Hold, 机械联动
                bar.setRange(-40, 40);
                bar.setTicks(
                    new float[]{-40, -20, 0, 20, 40},
                    new String[]{"−40", "−20", "0", "+20", "+40"});
                bar.addZone(-40, -5, DashboardPalette.SCALE_RED);
                bar.addZone(-5, 0, DashboardPalette.SCALE_AMBER);
                bar.addZone(0, 40, DashboardPalette.SCALE_NEUTRAL);
                bar.setAnchor(0);
                bar.setStatic();
                break;

            case 7: // S.TRIM: TRANSIENT — Oscillation Envelope, 修正振荡
                bar.setRange(-25, 25);
                bar.setTicks(
                    new float[]{-25, -15, -5, 0, 5, 15, 25},
                    new String[]{"−25", "−15", null, "0", null, "+15", "+25"});
                addTrimScaleZones(bar);
                bar.setAnchor(0);
                bar.setStatic();
                break;
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        if (flightRecorder != null) flightRecorder.onAppForegroundChanged(true);
        lastAuxiliaryUpdateMs = 0L;
        freshnessHandler.removeCallbacks(freshnessRunnable);
        freshnessHandler.post(freshnessRunnable);
        // V2.6.8 (L4): 如果后台停留过久, 模拟长断重连, 触发会话重置
        // 注意: 不覆盖 lastBtDisconnectedAtMs (避免破坏 onDisconnected 已设置的精确断线时刻)
        long now = SystemClock.elapsedRealtime();
        if (lastValidFrameTimeMs > 0L && (now - lastValidFrameTimeMs) > BT_SESSION_PRESERVE_MS
                && !btWasDisconnected) {
            // 标记为长断, 下次 onDataReceived 时 handleReconnectSessionPolicy 会处理
            btWasDisconnected = true;
            // 用 lastValidFrameTimeMs 作为"断线时刻", 这样 handleReconnectSessionPolicy
            // 能算出正确的 lostMs (即后台停留时间)
            lastBtDisconnectedAtMs = lastValidFrameTimeMs;
        }
        boolean shouldChooseAfterSettings = returningFromBluetoothSettings;
        returningFromBluetoothSettings = false;
        if (dataSource.isConnected()) {
            dataSource.startPolling();
        } else if (USE_DEMO) {
            dataSource.connect(null);
        } else if (shouldChooseAfterSettings) {
            // 返回后等用户确认刚配对的设备，不抢先连接旧记录。
            showBluetoothDeviceChooser(false);
        } else {
            // V2.6.9 (P0-2 配套): 后台期间蓝牙可能被系统断开, 回前台时若已断开则重新连接
            connectBluetooth();
        }
        if (!USE_DEMO && shouldChooseAfterSettings && dataSource.isConnected()) {
            // 用户从系统蓝牙设置返回：即使旧设备仍可连接，也要让用户确认新配对的设备。
            showBluetoothDeviceChooser(false);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (startupOverlay != null) startupOverlay.finish();
        foreground = false;
        if (flightRecorder != null) flightRecorder.onAppForegroundChanged(false);
        freshnessHandler.removeCallbacks(freshnessRunnable);
        updateFlashState();
        if (shiftLight != null) shiftLight.setMonitoringActive(false);
        dataSource.stopPolling();
    }

    @Override
    protected void onDestroy() {
        activityDestroyed = true;
        if (startupOverlay != null) startupOverlay.finish();
        super.onDestroy();
        freshnessHandler.removeCallbacks(freshnessRunnable);
        // V2.6.8 (BG3): 清理 flashHandler, 防止 flashTick 持有 Activity 引用泄漏
        flashHandler.removeCallbacks(flashTick);
        trustedDisplayMemory.reset();
        diagnosticMemory.clear();
        // V2.6.9 (P2-3): 先解绑 callback, 防止 disconnect 异步回调到已销毁的 Activity
        if (flightRecorder != null) {
            flightRecorder.recordTransportEvent("APP_DESTROY", SystemClock.elapsedRealtime());
        }
        if (dataSource instanceof BluetoothSource) {
            ((BluetoothSource) dataSource).setDiagnosticObserver(null);
        }
        dataSource.setCallback(null);
        dataSource.disconnect();
        if (flightRecorder != null) flightRecorder.shutdown();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BLUETOOTH) {
            bluetoothEnableRequestInFlight = false;
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            bluetoothEnableDeclined = adapter == null || !adapter.isEnabled();
            if (bluetoothEnableDeclined) setConnectionStatus("BT OFF", COLOR_DANGER);
        }
    }

    /** API17-safe runtime storage permission query. */
    private boolean hasDiagnosticStoragePermission() {
        if (Build.VERSION.SDK_INT < 23) return true;
        try {
            Method method = Activity.class.getMethod("checkSelfPermission", String.class);
            Object result = method.invoke(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return result instanceof Integer
                    && ((Integer) result).intValue() == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            Log.w("HondataDash", "无法查询诊断存储权限: " + e.getMessage());
            return false;
        }
    }

    /** API17-safe runtime permission request; recorder remains optional on failure/denial. */
    private void requestDiagnosticStoragePermission() {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            Method method = Activity.class.getMethod("requestPermissions",
                    String[].class, int.class);
            method.invoke(this, new Object[] {
                    new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    Integer.valueOf(REQUEST_DIAGNOSTIC_STORAGE)
            });
        } catch (Exception e) {
            Log.w("HondataDash", "无法请求诊断存储权限: " + e.getMessage());
        }
    }

    // Intentionally no @Override: API17 android.jar does not declare this callback,
    // while Android 23+ dispatches to the matching subclass method at runtime.
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_DIAGNOSTIC_STORAGE && flightRecorder != null) {
            boolean granted = grantResults != null && grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            flightRecorder.setEnabled(granted);
        }
    }

    // ===== DataSource.Callback =====

    @Override
    public void onConnected() {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                connectedSinceMs = SystemClock.elapsedRealtime();
                if (flightRecorder != null) {
                    flightRecorder.recordTransportEvent("BLUETOOTH_CONNECTED", connectedSinceMs);
                }
                engineState.reset();
                combustionAdmission.requireReacquire(connectedSinceMs);
                fuelPressureAlert.reset();
                trustedDisplayMemory.reset();
                diagnosticMemory.clear();
                Arrays.fill(displayHoldMode, false);
                invalidateCombustionDisplayForLinkGap();
                Arrays.fill(hasFiltered, false);
                Arrays.fill(lastUpdateTime, 0L);
                setConnectionStatus("INITIALIZING", DashboardPalette.SECONDARY);
                lastAuxiliaryUpdateMs = 0L;
                if (foreground && !activityDestroyed) dataSource.startPolling();
                else dataSource.stopPolling();
            }
        });
    }

    @Override
    public void onDisconnected() {
        // V2.6.7: 蓝牙断开只表示数据链路中断，不代表发动机 session 结束。
        // 不清 maxTrack/minTrack/recentMax/recentMin/hasValue。
        // 不清 engineBaselineApplied。
        // 不清 engineExtremeSessionActive。
        // 不清 ethanol settling gate。
        lastBtDisconnectedAtMs = SystemClock.elapsedRealtime();
        btWasDisconnected = true;
        if (flightRecorder != null) {
            flightRecorder.recordTransportEvent("BLUETOOTH_LOST", lastBtDisconnectedAtMs);
        }

        runOnUiThread(new Runnable() {
            @Override public void run() {
                engineState.reset();
                combustionAdmission.reset();
                fuelPressureAlert.reset();
                // RC7 ephemeral memories have no value once the transport is gone.
                // Purge immediately instead of retaining stale drive data in RAM;
                // reconnect reacquires fresh semantics from scratch.
                trustedDisplayMemory.reset();
                diagnosticMemory.clear();
                Arrays.fill(displayHoldMode, false);
                invalidateCombustionDisplayForLinkGap();
                setConnectionStatus("BT LOST", COLOR_DANGER);
                applyAlertAndFreshnessVisuals();
                updateFlashState();
            }
        });
    }

    // Rate Limit: 各参数更新间隔 (ms) — A/F/IGN 由自适应动态覆盖
    // 第1行(慢): Ethanol, ECT, IAT, L.TRIM | 第2行(快): MAP, A/F, IGN, S.TRIM
    private static final long[] UPDATE_INTERVAL = {
        500,   // 0: Ethanol: 2Hz
        500,   // 1: ECT: 2Hz
        500,   // 2: IAT: 2Hz
        1000,  // 3: L.TRIM: 1Hz (ECU 长期学习值, 极慢)
        50,    // 4: Boost: 20Hz
        100,   // 5: A/F: 10Hz (WOT 自适应提升至 20Hz)
        100,   // 6: IGN: 10Hz (WOT 自适应提升至 20Hz)
        200    // 7: S.TRIM: 5Hz
    };
    private final long[] lastUpdateTime = new long[8];

    // EMA 滤波: 各参数 alpha 系数 — A/F 由自适应动态覆盖
    private static final float[] EMA_ALPHA = {
        0.05f, // 0: Ethanol: 极慢
        0.1f,  // 1: ECT: 极慢
        0.05f, // 2: IAT: 极慢
        1.0f,  // 3: L.TRIM: 不过滤 (ECU 长期学习值本身已平滑)
        0f,    // 4: Boost: 用不对称滤波
        0.3f,  // 5: A/F: 中等 (WOT 自适应 α=0.7)
        0.4f,  // 6: IGN: 中等
        0.2f   // 7: S.TRIM: 较慢
    };
    private final float[] filteredValue = new float[8];
    private final boolean[] hasFiltered = new boolean[8];

    // P1: 范围校验 (转换后的合法范围, 超出视为传感器异常)
    private static final float[][] VALID_RANGE = {
        {0, 100},       // 0: Ethanol %
        {-20, 130},     // 1: ECT °C
        {-20, 130},     // 2: IAT °C
        {-30, 30},      // 3: L.TRIM %
        {-1.5f, 3.0f},  // 4: MAP relative bar
        {6.5f, 25.0f},  // 5: A/F ratio — physical validity range (real lean-out reaches 7.x)
        {-25, 55},      // 6: IGN °
        {-30, 30},      // 7: S.TRIM %
    };

    @Override
    public void onDataReceived(final SensorData data) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                // V2.2: 数据新鲜度追踪
                if (activityDestroyed || !foreground) return;
                // IT3: FlashPro reconnect may emit one placeholder frame (for example
                // negative throttle / zero MAP) before real telemetry. Never allow
                // that transport artifact to seed ThermalContext/MainState.
                if (!isSemanticFramePlausible(data)) {
                    if (flightRecorder != null) {
                        flightRecorder.recordRejectedSemanticFrame(
                                data.receivedAtElapsedMs > 0L
                                        ? data.receivedAtElapsedMs : SystemClock.elapsedRealtime());
                    }
                    return;
                }
                boolean reacquiringAfterStale = displayDataStale;
                long receivedAt = data.receivedAtElapsedMs;
                lastValidFrameTimeMs = receivedAt > 0L ? receivedAt : SystemClock.elapsedRealtime();
                if (reacquiringAfterStale && isDataFresh()) {
                    // A stale gap invalidates rate history and live/held admission semantics.
                    engineState.reset();
                    combustionAdmission.requireReacquire(SystemClock.elapsedRealtime());
                    fuelPressureAlert.reset();
                    trustedDisplayMemory.reset();
                    diagnosticMemory.clear();
                    Arrays.fill(lastUpdateTime, 0L);
                    Arrays.fill(hasFiltered, false);
                    Arrays.fill(displayHoldMode, false);
                    invalidateCombustionDisplayForLinkGap();
                }
                Arrays.fill(frameValid, false);
                for (int j = 0; j < AUXILIARY_PIDS.length; j++) {
                    double value = data.getDouble(AUXILIARY_PIDS[j]);
                    auxiliaryValid[j] = !Double.isNaN(value) && !Double.isInfinite(value);
                }
                double rpmSample = data.getDouble(0x100);
                rpmFrameValid = !Double.isNaN(rpmSample) && !Double.isInfinite(rpmSample);

                // V2.6.7: 蓝牙重连后判断是否需要重置 session
                handleReconnectSessionPolicy(data);

                // 更新大气压力 (用于 Boost 相对压力计算)
                // V2.6.8 (方案C 补强): 过滤 NaN/Infinity, 防止 lastBaro 污染 Boost 计算
                Double baro = data.get(0x170);
                if (baro != null) {
                    float baroVal = baro.floatValue();
                    if (!Float.isNaN(baroVal) && !Float.isInfinite(baroVal)
                            && baroVal >= 50f && baroVal <= 110f) {
                        lastBaro = baroVal;
                    }
                }

                // RC6 semantics: ShiftPhase and CombustionState are orthogonal to MainState.
                EngineSemanticState state = engineState.update(data);
                boolean shiftTransient = state.isShiftActive();
                updateEngineRunningGate(data);
                // IT3 HF1: Recorder drive qualification uses the same proven 1 s
                // engine-running gate as the product session. A single RPM>=500
                // frame is not sufficient to create a persistent drive session.
                if (flightRecorder != null && rpmFrameValid) {
                    flightRecorder.onEngineRunningSample(
                            engineRunningStable, lastValidFrameTimeMs);
                }
                updateEngineExtremeSession(data);
                long now = SystemClock.elapsedRealtime();
                CombustionDisplayAdmission.Snapshot admission = combustionAdmission.update(state, data, now);
                diagnosticMemory.record(state, data, admission, now);

                // 更新 8 个主卡片
                for (int i = 0; i < 8; i++) {
                    if (valueIntViews[i] == null) continue;

                    int pid = CARD_PIDS[i];
                    Double raw = data.get(pid);

                    // Admission is checked before raw-value handling. If a sensitive PID
                    // momentarily disappears during a shift, keep the coherent last-valid
                    // value held rather than falling through to a misleading live/stale mix.
                    if (admission.holdsCard(i)) {
                        if (!displayHoldMode[i]) trustedDisplayMemory.captureHold(i, now);
                        displayHoldMode[i] = true;
                        if (i == 5) clearAfFlashIfNeeded();
                        renderHeldCombustionCard(i);
                        // frameValid describes transport/data validity, not combustion
                        // validity. Preserve it only when this frame actually carries a
                        // finite PID so freshness can still distinguish a missing channel.
                        if (raw != null && !Double.isNaN(raw) && !Double.isInfinite(raw)) {
                            frameValid[i] = true;
                        }
                        continue;
                    }
                    if (admission.releasedCard(i)) {
                        // Do not blend recovered live data with a pre-shift EMA tail.
                        hasFiltered[i] = false;
                        lastUpdateTime[i] = 0L;
                        trustedDisplayMemory.releaseHold(i);
                    }
                    displayHoldMode[i] = false;

                    if (raw != null) {
                        // V2.6.8 (M1): 防御性 NaN/Infinity 过滤, 防止污染 EMA 与极值初始化
                        if (Double.isNaN(raw) || Double.isInfinite(raw)) {
                            continue;
                        }
                        double val = raw;

                        // A/F 卡片: Lambda → A/F ratio (×14.7)
                        if (i == 5) {
                            val = val * 14.7;
                        }

                        // Boost 卡片: kPa → bar (相对压力, 减去大气压)
                        if (i == 4) {
                            val = (val - lastBaro) / 100.0;
                        }

                        float fVal = (float) val;

                        // V2.6.8 (方案C 第二层): 计算后再次过滤 NaN/Infinity
                        // 即使 raw 合法, A/F 的 val*14.7 或 Boost 的 (val-lastBaro)/100 仍可能产生 NaN
                        // (例如 lastBaro 异常时). 防止污染后续 EMA 与极值初始化
                        if (Float.isNaN(fVal) || Float.isInfinite(fVal)) {
                            continue;
                        }

                        frameValid[i] = true;

                        // Admitted live values now pass the ordinary physical-range gate.
                        float[] range = VALID_RANGE[i];
                        if (fVal < range[0] || fVal > range[1]) {
                            frameValid[i] = false;
                            if (hasValidValue[i]) {
                                valueIntViews[i].setAlpha(0.4f);
                            } else {
                                valueIntViews[i].setText("--");
                            }
                            if (scaleBars[i] != null) scaleBars[i].setValue(Float.NaN);
                            continue;
                        }

                        // NaN 保护: 传感器异常值使用上次有效滤波值
                        if (Float.isNaN(fVal) && hasFiltered[i]) {
                            fVal = filteredValue[i];
                        }

                        // 自适应参数: V2 置信度驱动连续调制
                        float effectiveAlpha = EMA_ALPHA[i];
                        long effectiveInterval = UPDATE_INTERVAL[i];
                        boolean isWot = state.isWot();
                        boolean needFast = shouldUseFastCombustionRefresh(state, data);
                        // A/F: WOT alpha 由置信度连续调制 (0.3~0.7), 快速模式 20Hz
                        if (i == 5) {
                            effectiveAlpha = state.afAlpha();
                            effectiveInterval = needFast ? 50L : 100L;
                        }
                        // IGN: WOT/Modifier 高速刷新 20Hz
                        if (i == 6) {
                            effectiveInterval = needFast ? 50L : 100L;
                        }

                        // EMA 滤波
                        // V2.2: 保存 MAP 原始值 (滤波前), 用于 Session Extreme
                        float rawValueForExtreme = fVal;
                        if (effectiveAlpha > 0 && effectiveAlpha < 1f) {
                            if (hasFiltered[i]) {
                                fVal = ema(fVal, filteredValue[i], effectiveAlpha);
                            }
                            filteredValue[i] = fVal;
                            hasFiltered[i] = true;
                        }
                        // Boost 不对称滤波
                        else if (i == 4) {
                            if (hasFiltered[i]) {
                                fVal = boostFilter(fVal, filteredValue[i], state);
                            }
                            filteredValue[i] = fVal;
                            hasFiltered[i] = true;
                            // 近零处理: 避免 "-0" 显示
                            if (Math.abs(fVal) < 0.05f) {
                                fVal = 0.0f;
                                filteredValue[i] = 0.0f;
                            }
                        }

                        // Rate Limit 检查 (MAX/MIN 始终更新, 仅限制显示刷新)
                        boolean updateDisplay = (now - lastUpdateTime[i] >= effectiveInterval);

                        // RC7 trusted hold memory deliberately ignores the edge of
                        // TIP_OUT/SHIFT/fuel-cut/recovery. The visible live value is
                        // still real; only future HOLD selection uses this history.
                        if (i >= 5 && i <= 7) {
                            trustedDisplayMemory.record(i, fVal, now,
                                    isTrustedDisplayCandidate(i, state, data));
                        }

                        // 缓存有效值
                        lastValidValue[i] = fVal;
                        hasValidValue[i] = true;

                        // V2.6.6: Ethanol 连接后爬升门控
                        if (i == 0) {
                            updateEthanolSettlingGate(fVal);
                        }

                        // History Admission System: V2.6.7 通电临时极值 + 发动机稳定后一次性 baseline 覆盖
                        float valueForExtreme = (i == 4) ? rawValueForExtreme : fVal;
                        if (i == 4) {
                            updateLastBoostEventPeak(valueForExtreme, state, data, now);
                        }

                        // RC6: the same semantic admission applies to initialization,
                        // engine-baseline capture and subsequent HI/LO updates. Otherwise a
                        // first frame that happens to land inside SHIFT/DFCO/recovery could
                        // seed an invalid extreme even though later updates are gated.
                        boolean canRecordExtreme = EXTREME_POLICY[i] != EXTREME_NONE
                                && canRecordExtremeNow(i)
                                && isEligibleForHistory(i, state, data);

                        // V2.6.7: 发动机稳定后，每个卡片用当前真实值做一次 engine baseline 覆盖
                        boolean baselineAppliedNow = false;

                        if (canRecordExtreme) {
                            baselineAppliedNow = applyEngineBaselineIfNeeded(i, valueForExtreme, fVal, now);

                            if (!hasValue[i]) {
                                // V2.2: 通电后临时 MAX/MIN 初始化
                                float initVal = SHOW_SESSION_EXTREME[i] ? valueForExtreme : fVal;
                                if (!SHOW_SESSION_EXTREME[i] || isTrustedForSessionExtreme(i, initVal)) {
                                    initializeExtremeForCard(i, initVal, fVal, now);
                                }
                            } else if (!baselineAppliedNow && SHOW_SESSION_EXTREME[i]) {
                                // Session semantic extreme: update only the useful direction(s).
                                if (isTrustedForSessionExtreme(i, valueForExtreme)) {
                                    if ((EXTREME_POLICY[i] & EXTREME_MAX) != 0
                                            && isEligibleForExtreme(i, true, state, data)
                                            && valueForExtreme > maxTrack[i])
                                        maxTrack[i] = valueForExtreme;
                                    if ((EXTREME_POLICY[i] & EXTREME_MIN) != 0
                                            && isEligibleForExtreme(i, false, state, data)
                                            && valueForExtreme < minTrack[i])
                                        minTrack[i] = valueForExtreme;
                                }
                            } else if (!baselineAppliedNow && isEligibleForHistory(i, state, data)) {
                                // Recent semantic extreme: context admission + cooldown + breakthrough.
                                boolean maxExpired = (now - lastMaxTime[i] >= getCooldown(i, true, isWot));
                                boolean minExpired = (now - lastMinTime[i] >= getCooldown(i, false, isWot));

                                if (i != 4 && (EXTREME_POLICY[i] & EXTREME_MAX) != 0
                                        && isEligibleForExtreme(i, true, state, data)
                                        && fVal > maxTrack[i]) {
                                    if (maxExpired || isBreakthrough(i, true, fVal, maxTrack[i])) {
                                        maxTrack[i] = fVal;
                                        recentMax[i] = fVal;
                                        lastMaxTime[i] = now;
                                        recentMaxTime[i] = now;
                                    }
                                }
                                if ((EXTREME_POLICY[i] & EXTREME_MIN) != 0
                                        && isEligibleForExtreme(i, false, state, data)
                                        && fVal < minTrack[i]) {
                                    if (minExpired || isBreakthrough(i, false, fVal, minTrack[i])) {
                                        minTrack[i] = fVal;
                                        recentMin[i] = fVal;
                                        lastMinTime[i] = now;
                                        recentMinTime[i] = now;
                                    }
                                }
                            }
                        }
                        // Recent event peaks must age even after the qualifying event ends.
                        if (hasValue[i] && !SHOW_SESSION_EXTREME[i] && EXTREME_POLICY[i] != EXTREME_NONE) {
                            updateRecentPeak(i, fVal, now);
                        }

                        updateMainColorState(i, fVal, now, state, data);
                        if (updateDisplay) {
                            lastUpdateTime[i] = now;

                            // V2.5: 单 TextView 完整字符串 + overlay 语义恢复
                            String mainText = formatMainText(i, fVal);
                            renderMainText(i, mainText);
                            // 默认白色, 后面各卡片按条件覆盖颜色
                            valueIntViews[i].setTextColor(COLOR_TEXT_NORMAL);
                            valueIntViews[i].setAlpha(1f);

                            if (scaleBars[i] != null) {
                                // V2.0: updateEmotion() retired from the production path —
                                // the main digit colour is the single safety semantics.
                                scaleBars[i].setValue(fVal);
                            }

                            if (maxValueViews[i] != null) {
                                if (hasValue[i]) {
                                    float displayMax = SHOW_SESSION_EXTREME[i] ? maxTrack[i] : recentMax[i];
                                    String maxText = (i == 4 && !hasLastBoostEventPeak)
                                            ? "--" : formatExtremeText(i, displayMax);
                                    renderExtremeText(maxValueViews[i], maxText);
                                    maxValueViews[i].setTextColor(extremeTextColor);
                                } else {
                                    renderExtremeText(maxValueViews[i], "--");
                                }
                            }
                            if (minValueViews[i] != null) {
                                if (hasValue[i]) {
                                    float displayMin = SHOW_SESSION_EXTREME[i] ? minTrack[i] : recentMin[i];
                                    String minText = formatExtremeText(i, displayMin);
                                    renderExtremeText(minValueViews[i], minText);
                                    minValueViews[i].setTextColor(extremeTextColor);
                                } else {
                                    renderExtremeText(minValueViews[i], "--");
                                }
                            }

                            // Ethanol: 按浓度变色 (E前缀已由 fitSplitValueText 设置)
                            if (i == 0) {
                                valueIntViews[i].setTextColor(getEthanolColor(fVal));
                            }

                            // ECT: 82°C cruise / ~92°C fan-management are normal; >108°C critical flash
                            if (i == 1) {
                                lastEctVal = fVal;
                                boolean shouldFlash = fVal > 108;
                                if (shouldFlash != ectFlashing) {
                                    ectFlashing = shouldFlash;
                                    updateFlashState();
                                }
                                if (!ectFlashing) {
                                    valueIntViews[i].setTextColor(getEctColor(fVal));
                                    valueIntViews[i].setAlpha(1f);
                                }
                            }

                            // IAT: 进气温度变色 <35绿 35~45白 45~55黄 55~65红 >=65紫闪烁
                            if (i == 2) {
                                lastIatVal = fVal;
                                boolean shouldFlash = fVal >= 65;
                                if (shouldFlash != iatFlashing) {
                                    iatFlashing = shouldFlash;
                                    updateFlashState();
                                }
                                if (!iatFlashing) {
                                    valueIntViews[i].setTextColor(getIatColor(fVal));
                                    valueIntViews[i].setAlpha(1f);
                                }
                            }

                            // A/F: V2.3 Lambda 语义报警 — 使用 measured/target lambda 而非固定 AFR 阈值
                            if (i == 5) {
                                lastAfVal = fVal;
                                float measuredLambda = (float) data.getDouble(0x0320);
                                float targetLambda = (float) data.getDouble(0x0322);

                                // RC7: colour escalation has attack persistence. The digit
                                // remains live, but a one-frame lambda excursion no longer
                                // impersonates a confirmed warning. WOT danger still attacks fast.
                                boolean afColorContext = isAfColorContext(state, data);
                                int afSeverity = afColorContext
                                        ? getAfSeverity(measuredLambda, targetLambda, state) : 0;
                                int shownAfSeverity;
                                if (afColorContext) {
                                    long afAttack = getAfAttackMs(afSeverity, state);
                                    shownAfSeverity = colorRecovery.update(5, afSeverity, now, afAttack);
                                } else {
                                    colorRecovery.reset(5);
                                    shownAfSeverity = 0;
                                }
                                boolean shouldFlash = afColorContext
                                        && state.combustion == EngineSemanticState.CombustionState.FIRING_VALID
                                        && !shiftTransient && isWot && shownAfSeverity >= 2;
                                if (shouldFlash != afFlashing) {
                                    afFlashing = shouldFlash;
                                    updateFlashState();
                                }

                                int color = !afColorContext ? COLOR_TEXT_NORMAL
                                        : afSeverity > shownAfSeverity
                                            ? COLOR_TEXT_NORMAL : severityColor(shownAfSeverity);
                                if (!afFlashing) {
                                    valueIntViews[i].setTextColor(color);
                                    valueIntViews[i].setAlpha(1f);
                                }
                                // Target marker keeps updating through the alarm flash
                                // so the gauge never freezes on a stale ECU target.
                                updateAfTargetMarker(i, targetLambda, color);
                            }

                            // V2.7.0: L.TRIM / MAP / IGN / S.TRIM 主数据语义颜色
                            // 必须在 DFCO/SYNC 前置门控之后, 且在低置信度灰显之前
                            applyMainValueSemanticColor(i, fVal);

                            // V2.6.7: A/F / IGN / S.TRIM 低置信度灰色模式
                            applyConfidenceVisual(i, state, data);
                            if (i == 7) applyStrimInterpretabilityVisual(state, data, now);

                        }
                    } else {
                        // Last-Valid 缓存: 数据缺失时保留最后有效值 (半透明)
                        if (hasValidValue[i]) {
                            valueIntViews[i].setAlpha(0.4f);
                        } else {
                            valueIntViews[i].setText("--");
                            valueIntViews[i].setAlpha(1f);
                        }
                        if (scaleBars[i] != null) {
                            scaleBars[i].setValue(Float.NaN);
                        }
                    }
                }

                boolean updateAuxiliary = now - lastAuxiliaryUpdateMs >= 200L;
                if (updateAuxiliary) lastAuxiliaryUpdateMs = now;
                // Alarm and knock-event evaluation still runs for every received frame.
                // 更新爆震控制值 (Knock Control %)
                Double kc = data.get(KNOCK_CTRL_PID);
                if (kc != null && knockRetValue != null) {
                    int pct = kc.intValue();
                    setTextIfChanged(knockRetValue, String.valueOf(pct));

                    boolean shouldFlash = pct > 65;
                    if (shouldFlash != kcFlashing) {
                        kcFlashing = shouldFlash;
                        updateFlashState();
                    }
                    if (!kcFlashing) {
                        if (pct < 55) {
                            knockRetValue.setTextColor(0xFF3FB950); // 绿色
                        } else {
                            knockRetValue.setTextColor(0xFFD29922); // 黄色 (55~65)
                        }
                        knockRetValue.setAlpha(1f);
                    }
                }

                // 更新 4 个爆震缸
                int totalDelta = 0;
                for (int i = 0; i < 4; i++) {
                    if (knockValues[i] == null) continue;

                    Double kv = data.get(KNOCK_PIDS[i]);
                    if (kv != null) {
                        int knock = kv.intValue();
                        // V2.6.8 (BG1): 首帧基线 (-1=未建立) 只记录, 不算 delta
                        // 避免 App 首次连接时 ECU 累积值触发假爆震闪烁
                        boolean baselineEstablished = lastKnockCount[i] != -1;
                        int delta = baselineEstablished ? (knock - lastKnockCount[i]) : 0;
                        if (delta > 0) {
                            totalDelta += delta;
                            cylYellowEnd[i] = now + 3000; // 黄色闪烁3秒
                        }
                        lastKnockCount[i] = knock;
                        setTextIfChanged(knockValues[i], String.valueOf(knock));

                        // 颜色优先级: 红色闪烁 > 黄色闪烁 > 静态色
                        boolean inYellowFlash = now < cylYellowEnd[i];
                        if (cylRedFlashing) {
                            // 红色闪烁由 flashTick 管理
                        } else if (inYellowFlash) {
                            knockValues[i].setTextColor(0xFFD29922);
                            knockValues[i].setAlpha(1f);
                        } else {
                            knockValues[i].setAlpha(1f);
                            if (knock == 0) {
                                knockValues[i].setTextColor(0xFF3FB950);
                            } else if (knock == 1) {
                                knockValues[i].setTextColor(0xFFD29922);
                            } else {
                                knockValues[i].setTextColor(0xFFFF4444);
                            }
                        }
                    } else {
                        setTextIfChanged(knockValues[i], "--");
                        knockValues[i].setTextColor(0xFF555555);
                    }
                }

                // CYL 快速累积检测: 5秒内总增量>10则红色闪烁
                if (totalDelta > 0) {
                    if (now - cylRapidStart > 5000) {
                        cylRapidStart = now;
                        cylRapidAccum = totalDelta;
                    } else {
                        cylRapidAccum += totalDelta;
                    }
                    boolean wasRed = cylRedFlashing;
                    cylRedFlashing = cylRapidAccum > 10;
                    if (cylRedFlashing != wasRed) updateFlashState();
                }
                // CYL红色闪烁停止检测 (5秒无新累积)
                if (cylRedFlashing && now - cylRapidStart > 5000) {
                    cylRedFlashing = false;
                    updateFlashState();
                    // 恢复各缸静态色
                    for (int i = 0; i < 4; i++) {
                        if (knockValues[i] != null) {
                            Double kv2 = data.get(KNOCK_PIDS[i]);
                            if (kv2 != null) {
                                int k = kv2.intValue();
                                knockValues[i].setAlpha(1f);
                                if (k == 0) knockValues[i].setTextColor(0xFF3FB950);
                                else if (k == 1) knockValues[i].setTextColor(0xFFD29922);
                                else knockValues[i].setTextColor(0xFFFF4444);
                            }
                        }
                    }
                }

                // 更新底部数据
                // Knock Retard (PID 0x410)
                Double kr = data.get(0x410);
                if (kr != null && bottomTrimValue != null) {
                    if (updateAuxiliary) setTextIfChanged(bottomTrimValue, String.format(Locale.US, "%.1f", kr));
                }

                // Knock Limit (PID 0x411)
                Double kl = data.get(0x411);
                if (kl != null && bottomAfmValue != null) {
                    if (updateAuxiliary) setTextIfChanged(bottomAfmValue, String.format(Locale.US, "%.1f", kl));
                }

                // BAT 电压 (PID 0x180)
                Double bat = data.get(BAT_PID);
                if (bat != null && bottomBatValue != null) {
                    if (updateAuxiliary) setTextIfChanged(bottomBatValue, String.format(Locale.US, "%.1f", bat));
                }

                // F.P Fuel Pressure (PID 0x191 kPa → bar) vs 目标 (0x190)
                Double fp = data.get(FP_PID);
                if (fp != null && bottomFpValue != null) {
                    if (updateAuxiliary) setTextIfChanged(bottomFpValue, String.format(Locale.US, "%.1f", fp / 100.0));

                    Double fpTarget = data.get(FP_TARGET_PID);
                    boolean wasFpFlash = fpFlashing;
                    double fpTargetKpa = fpTarget != null ? fpTarget : Double.NaN;
                    // RC6: the number remains live, but shift/fuel-cut/recovery frames are not
                    // diagnostic evidence of a rail-pressure fault. A real low-pressure event
                    // must persist for 300 ms after FIRING_VALID resumes.
                    fpFlashing = fuelPressureAlert.update(engineRunningStable, state,
                            fp, fpTargetKpa, now);
                    if (fpFlashing != wasFpFlash) updateFlashState();
                    if (!fpFlashing) {
                        bottomFpValue.setTextColor(COLOR_TEXT_NORMAL);
                        bottomFpValue.setAlpha(1f);
                    }
                }

                // W.G Wastegate (PID 0x1A0 %)
                Double wg = data.get(WG_PID);
                if (wg != null && bottomWgValue != null) {
                    if (updateAuxiliary) setTextIfChanged(bottomWgValue, String.format(Locale.US, "%.0f", wg));
                }

                // T.P Throttle Plate (PID 0x122 %)
                Double tp = data.get(TP_PID);
                if (tp != null) {
                    lastTpPlate = tp.floatValue();
                    if (bottomTpValue != null) {
                        if (updateAuxiliary) setTextIfChanged(bottomTpValue, String.format(Locale.US, "%.0f", tp));
                    }
                }

                // IT2 flight recorder: copy the already-decoded input + already-made
                // semantic decisions after fuel-pressure logic has run. No decision below
                // reads recorder state, preserving observer-only directionality.
                if (flightRecorder != null && flightRecorder.isEnabled()) {
                    double fpDiag = data.getDouble(FP_PID);
                    long fpPausedSince = fuelPressureAlert.getEvidencePausedSinceMs();
                    long fpPauseAge = fpPausedSince > 0L ? Math.max(0L, now - fpPausedSince) : 0L;
                    boolean fpObservable = engineRunningStable
                            && !Double.isNaN(fpDiag) && !Double.isInfinite(fpDiag)
                            && !state.isShiftActive()
                            && state.combustion == EngineSemanticState.CombustionState.FIRING_VALID;
                    flightRecorder.recordSemantic(data, state, admission,
                            diagnosticEffectiveValue(5), diagnosticEffectiveValue(6),
                            diagnosticEffectiveValue(7), strimPresentationWeight,
                            fpObservable, fuelPressureAlert.getLowObservedMs(), fpPauseAge,
                            fuelPressureAlert.isLowEvidenceActive(), fpFlashing,
                            boostEventActive, hasLastBoostEventPeak, lastBoostEventPeak);
                    flightRecorder.recordExtrema(now, state, maxTrack, minTrack, hasValue);
                }

                // 更新彩虹转速灯条
                if (shiftLight != null) {
                    Double rpmVal = data.get(0x100);
                    if (rpmVal != null && rpmFrameValid) {
                        shiftLight.setRpm(rpmVal.floatValue());
                    }
                }
                // Resolve alerts after ordinary text updates; freshness has final priority.
                applyAlertAndFreshnessVisuals();
                updateFlashState();
            }
        });
    }

    /** V2.2: 数据新鲜度状态更新 (由独立 Handler 每 250ms 调用) */
    private void updateFreshnessStatus() {
        // 断开时的 PAIR / SELECT / CONNECT / RECONNECT / BT OFF 由连接流程拥有。
        // 新鲜度计时器只在链路已连接时切换 LIVE / STALE / DATA LOST。
        if (dataSource == null || !dataSource.isConnected()) {
            applyAlertAndFreshnessVisuals();
            updateFlashState();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long age = now - lastValidFrameTimeMs;
        if (lastValidFrameTimeMs <= 0 || lastValidFrameTimeMs < connectedSinceMs) {
            setConnectionStatus(now - connectedSinceMs < 5000 ? "INITIALIZING" : "NO DATA", DashboardPalette.SECONDARY);
        } else if (age < 500 && rpmFrameValid && (frameValid[1] || frameValid[2] || frameValid[4])) {
            setConnectionStatus("LIVE", COLOR_SAFE);
        } else if (age < 500) {
            setConnectionStatus("NO DATA", COLOR_WARN);
        } else if (age < 1500) {
            setConnectionStatus("STALE", COLOR_WARN);
        } else {
            setConnectionStatus("DATA LOST", COLOR_DANGER);
        }
        applyAlertAndFreshnessVisuals();
        updateFlashState();
    }

    private boolean isDataFresh() {
        return foreground && lastValidFrameTimeMs > 0L
                && lastValidFrameTimeMs >= connectedSinceMs
                && SystemClock.elapsedRealtime() - lastValidFrameTimeMs < 500L
                && dataSource != null && dataSource.isConnected();
    }

    private void applyAlertAndFreshnessVisuals() {
        boolean fresh = isDataFresh();
        boolean wasStale = displayDataStale;
        displayDataStale = !fresh;
        if (!fresh && !wasStale) {
            engineState.reset();
            combustionAdmission.requireReacquire(SystemClock.elapsedRealtime());
            fuelPressureAlert.reset();
            invalidateCombustionDisplayForLinkGap();
        }
        for (int i = 0; i < 8; i++) {
            if (!fresh || !frameValid[i]) colorRecovery.reset(i);
            if (valueIntViews[i] != null && (!fresh || !frameValid[i])) {
                valueIntViews[i].setAlpha(hasValidValue[i] || semanticMode[i] ? 0.4f : 1f);
            }
            if (scaleBars[i] != null) {
                boolean held = displayHoldMode[i];
                scaleBars[i].setMonitoringActive(fresh && frameValid[i] && !semanticMode[i] && !held);
                scaleBars[i].setAlpha(fresh && frameValid[i] ? (held ? 0.58f : 1f) : 0.35f);
            }
        }
        if (auxiliaryViews != null) {
            for (int i = 0; i < auxiliaryViews.length; i++) {
                if (auxiliaryViews[i] != null) auxiliaryViews[i].setAlpha(fresh && auxiliaryValid[i] ? 1f : 0.4f);
            }
        }
        if (shiftLight != null) shiftLight.setMonitoringActive(fresh && rpmFrameValid);
        if (!fresh) { applyOemPalette(); return; }
        float alertAlpha = flashVisible ? 1f : 0.35f;
        applyCardAlert(1, ectFlashing, 0xFFB040FF, alertAlpha);
        applyCardAlert(2, iatFlashing, 0xFFB040FF, alertAlpha);
        applyCardAlert(5, afFlashing, COLOR_DANGER, alertAlpha);
        if (kcFlashing && auxiliaryValid[0] && knockRetValue != null) {
            knockRetValue.setTextColor(COLOR_DANGER);
            knockRetValue.setAlpha(alertAlpha);
        }
        if (fpFlashing && auxiliaryValid[8] && bottomFpValue != null) {
            bottomFpValue.setTextColor(COLOR_DANGER);
            bottomFpValue.setAlpha(alertAlpha);
        }
        if (cylRedFlashing) {
            for (int i = 0; i < 4; i++) {
                if (auxiliaryValid[i + 1] && knockValues[i] != null) {
                    knockValues[i].setTextColor(COLOR_DANGER);
                    knockValues[i].setAlpha(alertAlpha);
                }
            }
        }
        applyOemPalette();
    }

    /** Final presentation mapping; no change to threshold / severity decisions. */
    private void applyOemPalette() {
        for (int i = 0; i < valueIntViews.length; i++) {
            if (valueIntViews[i] != null) {
                int color = DashboardPalette.main(i, valueIntViews[i].getCurrentTextColor());
                valueIntViews[i].setTextColor(color);
                if (scaleBars[i] != null) scaleBars[i].setLiveColor(color);
            }
        }
        if (auxiliaryViews != null) for (TextView view : auxiliaryViews) {
            if (view != null) view.setTextColor(DashboardPalette.common(view.getCurrentTextColor()));
        }
    }


    private static void addTrimScaleZones(ScaleBarView bar) {
        // RC4 OEM hierarchy: normal trim is deliberately neutral on the bar.
        // The main number owns safety; amber/red only mark larger deviation.
        bar.addZone(-25, -15, DashboardPalette.SCALE_RED);
        bar.addZone(-15, -5, DashboardPalette.SCALE_AMBER);
        bar.addZone(-5, 5, DashboardPalette.SCALE_NEUTRAL);
        bar.addZone(5, 15, DashboardPalette.SCALE_AMBER);
        bar.addZone(15, 25, DashboardPalette.SCALE_RED);
    }

    private void applyCardAlert(int i, boolean active, int color, float alpha) {
        if (active && frameValid[i] && !semanticMode[i] && valueIntViews[i] != null) {
            valueIntViews[i].setTextColor(color);
            valueIntViews[i].setAlpha(alpha);
        }
    }

    /**
     * 管理温度闪烁定时器的启停。
     */
    private void updateFlashState() {
        boolean active = (ectFlashing && frameValid[1]) || (iatFlashing && frameValid[2])
                || (afFlashing && frameValid[5] && !semanticMode[5])
                || (kcFlashing && auxiliaryValid[0]) || (fpFlashing && auxiliaryValid[8])
                || cylRedFlashing;
        if (foreground && isDataFresh() && active) {
            if (!flashScheduled) {
                flashScheduled = true;
                flashHandler.postDelayed(flashTick, 500L);
            }
        } else {
            flashHandler.removeCallbacks(flashTick);
            flashScheduled = false;
            flashVisible = true;
        }
    }

    private float diagnosticEffectiveValue(int card) {
        if (card >= 5 && card <= 7 && displayHoldMode[card]
                && trustedDisplayMemory.hasHoldValue(card)) {
            return trustedDisplayMemory.getHoldValue(card);
        }
        return hasValidValue[card] ? lastValidValue[card] : Float.NaN;
    }

    /**
     * Transport-level plausibility gate. This intentionally uses only impossible
     * physical values on critical channels, so a genuine very-cold start remains valid.
     */
    private boolean isSemanticFramePlausible(SensorData data) {
        if (data == null) return false;
        double rpm = data.getDouble(HondataProtocol.CID_RPM);
        double speed = data.getDouble(HondataProtocol.CID_Speed);
        double map = data.getDouble(HondataProtocol.CID_MAP);
        double tp = data.getDouble(HondataProtocol.CID_ThrottlePlate);
        double inj = data.getDouble(HondataProtocol.CID_Inj);
        if (Double.isNaN(rpm) || Double.isInfinite(rpm)
                || Double.isNaN(speed) || Double.isInfinite(speed)
                || Double.isNaN(map) || Double.isInfinite(map)
                || Double.isNaN(tp) || Double.isInfinite(tp)
                || Double.isNaN(inj) || Double.isInfinite(inj)) return false;
        // Real FC1 BT42 road data reaches 100.5-101.5% T.P at high load.
        // Keep a narrow 105% plausibility headroom while still rejecting the
        // reconnect placeholder sentinel (-10%) and genuinely impossible values.
        return rpm >= 0.0 && rpm <= 10000.0
                && speed >= 0.0 && speed <= 350.0
                && map >= 10.0 && map <= 400.0
                && tp >= 0.0 && tp <= 105.0
                && inj >= 0.0 && inj <= 50.0;
    }

    /**
     * IT3 Last Boost Event Peak.
     *
     * Start: thermally-ready, real boost demand (>0.20 bar), RPM >1500 and TP >35%.
     * Peak: raw relative MAP, not the filtered display value.
     * Bridge: 1.8 s demand grace keeps one multi-gear pull as one event.
     * Retention: latched until the next event begins; there is no time decay.
     */
    private void updateLastBoostEventPeak(float rawBoostBar, EngineSemanticState state,
            SensorData data, long now) {
        if (state == null || data == null || Float.isNaN(rawBoostBar)
                || Float.isInfinite(rawBoostBar)) return;
        float rpm = (float) data.getDouble(HondataProtocol.CID_RPM);
        float tp = (float) data.getDouble(HondataProtocol.CID_ThrottlePlate);
        if (Float.isNaN(rpm) || Float.isNaN(tp)) return;

        boolean canStart = !state.isThermalWarmup()
                && !state.isShiftActive()
                && !state.isFuelCut()
                && rpm > 1500f
                && tp > BOOST_EVENT_START_TP
                && rawBoostBar > BOOST_EVENT_START_BAR;

        if (!boostEventActive && canStart) {
            boostEventActive = true;
            hasLastBoostEventPeak = true;
            lastBoostEventPeak = rawBoostBar;
            boostEventLastDemandMs = now;
            recentMax[4] = rawBoostBar;
            maxTrack[4] = rawBoostBar;
            recentMaxTime[4] = now;
            lastMaxTime[4] = now;
            if (flightRecorder != null) {
                // A new Last-Boost event is a semantic reset of MAP MAX, not a
                // mathematical decrease of the previous event's maximum.
                flightRecorder.onExtremaReset(4, now);
                flightRecorder.recordBoostEvent("BOOST_EVENT_START", rawBoostBar, now);
            }
            return;
        }

        if (!boostEventActive) return;

        boolean peakEligible = !state.isShiftActive() && !state.isFuelCut()
                && rpm > 1000f && rawBoostBar > -0.20f;
        if (peakEligible && rawBoostBar > lastBoostEventPeak) {
            lastBoostEventPeak = rawBoostBar;
            recentMax[4] = rawBoostBar;
            maxTrack[4] = rawBoostBar;
            recentMaxTime[4] = now;
            lastMaxTime[4] = now;
        }

        boolean demandAlive = state.isShiftActive()
                || tp > BOOST_EVENT_KEEPALIVE_TP
                || rawBoostBar > BOOST_EVENT_KEEPALIVE_BAR;
        if (demandAlive) boostEventLastDemandMs = now;

        if (boostEventLastDemandMs > 0L
                && now - boostEventLastDemandMs >= BOOST_EVENT_END_GRACE_MS) {
            boostEventActive = false;
            recentMax[4] = lastBoostEventPeak;
            if (flightRecorder != null) {
                flightRecorder.recordBoostEvent("BOOST_EVENT_END", lastBoostEventPeak, now);
            }
        }
    }

    private void resetLastBoostEventPeak() {
        boostEventActive = false;
        hasLastBoostEventPeak = false;
        lastBoostEventPeak = Float.NaN;
        boostEventLastDemandMs = 0L;
    }

    private float getFloat(SensorData data, int pid) {
        Double v = data.get(pid);
        return v != null ? v.floatValue() : 0f;
    }

    /**
     * EMA 滤波: output = alpha × raw + (1 - alpha) × last
     */
    private float ema(float raw, float last, float alpha) {
        return alpha * raw + (1 - alpha) * last;
    }

    /** RC6 fast refresh is reserved for diagnostically useful high-load combustion. */
    private boolean shouldUseFastCombustionRefresh(EngineSemanticState state, SensorData data) {
        if (state == null || state.isShiftActive()
                || state.combustion != EngineSemanticState.CombustionState.FIRING_VALID) {
            return false;
        }
        if (state.isWot()) return true;
        if (state.modifier != EngineSemanticState.Modifier.TIP_IN) return false;
        float map = (float) data.getDouble(HondataProtocol.CID_MAP);
        float tp = (float) data.getDouble(HondataProtocol.CID_ThrottlePlate);
        return !Float.isNaN(map) && !Float.isNaN(tp) && map > 100f && tp > 10f;
    }

    /**
     * Hold the last admitted combustion value without inventing a semantic label.
     * This is deliberately quiet/OEM-like: digits and scale value stay coherent, while
     * reduced contrast communicates that the value is temporarily not current.
     */
    private void renderHeldCombustionCard(int i) {
        if (i < 5 || i > 7 || valueIntViews[i] == null) return;
        colorRecovery.reset(i);
        if (semanticMode[i]) configureMainText(i);
        semanticMode[i] = false;
        if (extremePanelViews[i] != null) extremePanelViews[i].setVisibility(View.VISIBLE);

        float heldValue = trustedDisplayMemory.hasHoldValue(i)
                ? trustedDisplayMemory.getHoldValue(i)
                : (hasValidValue[i] ? lastValidValue[i] : Float.NaN);
        if (!Float.isNaN(heldValue) && !Float.isInfinite(heldValue)) {
            renderMainText(i, formatMainText(i, heldValue));
            if (scaleBars[i] != null) scaleBars[i].setValue(heldValue);
        } else {
            renderMainText(i, "--");
            if (scaleBars[i] != null) scaleBars[i].setValue(Float.NaN);
        }
        valueIntViews[i].setTextColor(DashboardPalette.SECONDARY);
        valueIntViews[i].setAlpha(0.72f);
        if (valueDecViews[i] != null) {
            valueDecViews[i].setTextColor(DashboardPalette.SECONDARY);
            valueDecViews[i].setAlpha(0.72f);
        }
    }

    /** A stale/disconnected link must never make an old held value look live again. */
    private void invalidateCombustionDisplayForLinkGap() {
        // Any stale/link-gap boundary invalidates recent semantic memory. Old
        // trusted/diagnostic samples must never be reused after transport truth is lost.
        trustedDisplayMemory.reset();
        diagnosticMemory.clear();
        strimPresentationWeight = STRIM_WEIGHT_LOW;
        strimPresentationUpdatedMs = 0L;
        for (int i = 5; i <= 7; i++) {
            hasValidValue[i] = false;
            hasFiltered[i] = false;
            displayHoldMode[i] = false;
            semanticMode[i] = false;
            colorRecovery.reset(i);
            if (valueIntViews[i] != null) {
                configureMainText(i);
                renderMainText(i, "--");
                valueIntViews[i].setTextColor(DashboardPalette.SECONDARY);
                valueIntViews[i].setAlpha(0.4f);
            }
            if (scaleBars[i] != null) {
                scaleBars[i].setValue(Float.NaN);
                scaleBars[i].setMonitoringActive(false);
            }
        }
        if (scaleBars[5] != null) scaleBars[5].clearTargetValue();
        clearAfFlashIfNeeded();
    }

    /**
     * RC7 trust is about semantic timing, not whether a value looks "good". A
     * genuine abnormal value during valid combustion remains eligible; only
     * known transition edges are excluded from future HOLD capture.
     */
    private boolean isTrustedDisplayCandidate(int card, EngineSemanticState state, SensorData data) {
        if (state == null || data == null) return false;
        if (state.shiftPhase != EngineSemanticState.ShiftPhase.NONE
                || state.combustion != EngineSemanticState.CombustionState.FIRING_VALID
                || state.modifier == EngineSemanticState.Modifier.TIP_OUT) return false;
        float inj = (float) data.getDouble(HondataProtocol.CID_Inj);
        if (Float.isNaN(inj) || inj <= 0.20f) return false;
        if (card == 5) {
            float lambda = (float) data.getDouble(HondataProtocol.CID_Lambda);
            float target = (float) data.getDouble(HondataProtocol.CID_TargetLambda);
            return !Float.isNaN(lambda) && !Float.isNaN(target)
                    && lambda > 0.50f && lambda < 1.60f && target > 0.55f && target < 1.30f;
        }
        if (card == 7) {
            float cl = (float) data.getDouble(HondataProtocol.CID_ClosedLoop);
            float target = (float) data.getDouble(HondataProtocol.CID_TargetLambda);
            return !Float.isNaN(cl) && cl > 0.5f && !Float.isNaN(target)
                    && target > 0.90f && target < 1.10f;
        }
        return card == 6;
    }

    // === History Admission System ===

    /** Semantic Admission: every card keeps both RC7 extreme slots; only qualified
     * samples may update them. Direction-specific admission prevents mathematically
     * true but driver-meaningless extrema (cold ECT/IAT minima, transient A/F, etc.). */
    private boolean isEligibleForHistory(int i, EngineSemanticState state, SensorData data) {
        return isEligibleForExtreme(i, true, state, data)
                || isEligibleForExtreme(i, false, state, data);
    }

    private boolean isEligibleForExtreme(int i, boolean isMax,
                                         EngineSemanticState state, SensorData data) {
        if (state == null || data == null) return false;
        boolean thermalWarmup = state.isThermalWarmup();
        boolean isSpool = state.sub == EngineSemanticState.SubState.SPOOL;
        boolean combustionTransient = state.isShiftActive() || state.isFuelCut()
                || state.isCombustionRecovery();
        float rpm = (float) data.getDouble(HondataProtocol.CID_RPM);
        float tp = (float) data.getDouble(HondataProtocol.CID_ThrottlePlate);
        float map = (float) data.getDouble(HondataProtocol.CID_MAP);

        switch (i) {
            case 0: // Ethanol — settling gate is enforced by canRecordExtremeNow().
                return true;
            case 1: // ECT — ignore cold-start minimum; start the range after warm-up.
            case 2: // IAT — same operating-window principle as ECT.
                return state.isThermallyReady() && engineRunningStable;
            case 3: // L.TRIM — learned range only in stable closed-loop context.
            case 7: // S.TRIM — trend extrema only in stable closed-loop context.
                return isStableTrimColorContext(state, data);
            case 4: // MAP — MAX = real turbo demand; MIN = real engine vacuum.
                if (Float.isNaN(rpm) || Float.isNaN(tp) || Float.isNaN(map)
                        || state.isShiftActive()) return false;
                if (isMax) {
                    return !thermalWarmup && rpm > 1500f && tp > 35f && map > lastBaro + 20f;
                }
                return engineRunningStable && rpm > 900f && map < lastBaro - 10f;
            case 5: // A/F — both HI/LO only when combustion itself is interpretable.
                if (thermalWarmup || isSpool || combustionTransient
                        || state.hasModifier()
                        || state.combustion != EngineSemanticState.CombustionState.FIRING_VALID)
                    return false;
                float lambda = (float) data.getDouble(HondataProtocol.CID_Lambda);
                float target = (float) data.getDouble(HondataProtocol.CID_TargetLambda);
                return !Float.isNaN(lambda) && !Float.isNaN(target)
                        && lambda > 0.50f && lambda < 1.60f
                        && target > 0.55f && target < 1.30f;
            case 6: // IGN — HI may describe normal advance; LO requires meaningful load.
                if (thermalWarmup || combustionTransient
                        || state.combustion != EngineSemanticState.CombustionState.FIRING_VALID
                        || Float.isNaN(rpm) || rpm <= 1000f) return false;
                if (isMax) return true;
                return !Float.isNaN(tp) && tp > 25f
                        && !Float.isNaN(map) && map > lastBaro + 10f;
            default:
                return false;
        }
    }

    /** V2.2: Session Extreme 可信值过滤 — 排除 NaN/Infinity 和不可能的物理值 */
    // V2.6.8 (M5): 与 VALID_RANGE 对齐, 避免冷启异常值初始化
    private boolean isTrustedForSessionExtreme(int index, float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return false;
        switch (index) {
            case 0: return value >= 0f && value <= 100f;        // Ethanol % (与 VALID_RANGE 一致)
            case 1: return value >= -20f && value <= 130f;      // ECT °C (与 VALID_RANGE 一致)
            case 2: return value >= -20f && value <= 130f;      // IAT °C (与 VALID_RANGE 一致)
            case 3: return value >= -30f && value <= 30f;       // L.TRIM % (与 VALID_RANGE 一致)
            case 4: return value >= -1.5f && value <= 3.0f;    // MAP/Boost bar (与 VALID_RANGE 一致)
            default: return false;
        }
    }

    /** State-linked Cooldown: WOT 时缩短 */
    private long getCooldown(int i, boolean isMax, boolean isWot) {
        long[][] table = isWot ? COOLDOWN_WOT_MS : COOLDOWN_MS;
        return table[i][isMax ? 0 : 1];
    }

    /** Per-Parameter Breakthrough: 语义阈值突破 (绝对值) */
    private boolean isBreakthrough(int i, boolean isMax, float newVal, float current) {
        float threshold = BREAKTHROUGH[i][isMax ? 0 : 1];
        if (isMax) {
            return (newVal - current) > threshold;
        } else {
            return (current - newVal) > threshold;
        }
    }

    /** RC8 Recent semantic peak decay: retention is parameter-specific, not one-size-fits-all. */
    private void updateRecentPeak(int i, float currentVal, long now) {
        long holdMs = RECENT_PEAK_HOLD_MS[i];
        if (i != 4 && (EXTREME_POLICY[i] & EXTREME_MAX) != 0) {
            if (now - recentMaxTime[i] > holdMs) {
                recentMax[i] = decayToward(recentMax[i], currentVal, recentMaxDecayTimeMs, i, now);
            } else {
                recentMaxDecayTimeMs[i] = now;
            }
        }
        if ((EXTREME_POLICY[i] & EXTREME_MIN) != 0) {
            if (now - recentMinTime[i] > holdMs) {
                recentMin[i] = decayToward(recentMin[i], currentVal, recentMinDecayTimeMs, i, now);
            } else {
                recentMinDecayTimeMs[i] = now;
            }
        }
    }

    /** Frame-rate independent exponential approach towards the current value. */
    private float decayToward(float value, float target, long[] clock, int i, long now) {
        if (Math.abs(target - value) <= 0.01f) {
            clock[i] = now;
            return target;
        }
        float dt = clock[i] == 0L ? 0f
                : Math.max(0f, Math.min((now - clock[i]) / 1000f, 1.0f));
        float alpha = 1f - (float) Math.exp(-dt / (RECENT_DECAY_TAU_MS / 1000f));
        clock[i] = now;
        return value + (target - value) * alpha;
    }

    // === RC6 combustion validity is centralized in CombustionDisplayAdmission ===

    // === Fixed-slot display formatting ===

    private void configureMainText(int i) {
        FittedTextView tv = (FittedTextView) valueIntViews[i];
        if (tv == null) return;
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, MAIN_VALUE_SP);
        if (i == 0) tv.setPrefixScale('E', 1.0f);
        else tv.clearPrefixScale();
        tv.setFitReference(true, isSignedMainCard(i) || i == 1 || i == 2,
                MAIN_WIDTH_REFERENCE[i]);
    }

    /** OEM+ status palette; the legacy dot ID remains hidden for compatibility. */
    private void setConnectionStatus(String label, int color) {
        if ("CONNECT".equals(label)) label = "CONNECTING";
        if ("RECONNECT".equals(label)) label = "RECONNECTING";
        if ("LIVE".equals(label)) color = DashboardPalette.LIVE_RED;
        else if ("CONNECTING".equals(label) || "INITIALIZING".equals(label)
                || "RECONNECTING".equals(label)) color = DashboardPalette.SECONDARY;
        else color = DashboardPalette.AMBER;
        if (statusText != null) {
            statusText.setText(label);
            statusText.setTextColor(color);
        }
        if (statusDot != null && statusDot.getBackground() != null) {
            statusDot.getBackground().mutate().setColorFilter(color, PorterDuff.Mode.SRC_IN);
        }
    }

    /** A/F target marker update: same λ×14.7 conversion as the digit colour path;
     *  runs on every valid frame, including during the alarm flash. */
    private void updateAfTargetMarker(int i, float targetLambda, int color) {
        if (scaleBars[i] == null) return;
        if (Float.isNaN(targetLambda) || targetLambda <= 0f) {
            scaleBars[i].clearTargetValue();
        } else {
            scaleBars[i].setTargetValue(targetLambda * 14.7f, DashboardPalette.common(color));
        }
    }

    // ===== V2.6: 轻量格式函数 =====
    private String fmt1NoSign(float v) {
        int x = Math.round(v * 10f);
        int abs = Math.abs(x);
        return (x < 0 ? "-" : "") + (abs / 10) + "." + (abs % 10);
    }

    private static void setTextIfChanged(TextView view, String text) {
        if (view != null && !text.contentEquals(view.getText())) view.setText(text);
    }

    private String fmtSigned2(float v) {
        int x = Math.round(v * 100f);
        int abs = Math.abs(x);
        int fraction = abs % 100;
        return (x >= 0 ? "+" : "-") + (abs / 100) + "."
                + (fraction < 10 ? "0" : "") + fraction;
    }

    private String fmtSigned1(float v) {
        int x = Math.round(v * 10f);
        int abs = Math.abs(x);
        return (x >= 0 ? "+" : "-") + (abs / 10) + "." + (abs % 10);
    }

    /** V2.6: 格式化主值完整字符串 */
    private String formatMainText(int i, float v) {
        switch (i) {
            case 0: return "E" + Math.round(v);
            case 1:
            case 2: return String.valueOf(Math.round(v));
            case 4: return fmtSigned2(v);   // Boost precision matches 1.45/1.60 boundaries
            case 5: return fmt1NoSign(v);   // A/F
            default: return fmtSigned1(v);  // L.TRIM, IGN, S.TRIM
        }
    }

    /** V2.6: 格式化极值 */
    private String formatExtremeText(int i, float v) {
        if (i < 3) return String.valueOf(Math.round(v));
        if (i == 4) return fmtSigned2(v);
        if (i == 5) return fmt1NoSign(v);
        return fmtSigned1(v);
    }

    /** Formatting does not depend on a later telemetry frame to refit the layout. */
    private void renderMainText(final int i, final String text) {
        TextView tv = valueIntViews[i];
        if (tv == null) return;
        if (semanticMode[i]) configureMainText(i);
        semanticMode[i] = false;
        if (extremePanelViews[i] != null) extremePanelViews[i].setVisibility(View.VISIBLE);
        setTextIfChanged(tv, text);
    }

    /** DFCO/SYNC retain the same main slot; extrema remain INVISIBLE, never GONE. */
    private void renderSemanticCard(final int i, final String label) {
        FittedTextView tv = (FittedTextView) valueIntViews[i];
        if (tv == null) return;
        if (!semanticMode[i]) {
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 80f);
            tv.setFitReference(false, false, "DFCO", "SYNC");
        }
        semanticMode[i] = true;
        colorRecovery.reset(i);
        if (scaleBars[i] != null) scaleBars[i].setValue(Float.NaN);
        if (i == 5) clearAfSemanticContamination();
        if (valueDecViews[i] != null) {
            valueDecViews[i].setVisibility(View.GONE);
            valueDecViews[i].setText("");
            valueDecViews[i].setAlpha(ALPHA_SEMANTIC_SYNC);
        }
        if (extremePanelViews[i] != null) extremePanelViews[i].setVisibility(View.INVISIBLE);
        tv.setVisibility(View.VISIBLE);
        tv.setTextColor(COLOR_SEMANTIC_SYNC);
        tv.setAlpha(ALPHA_SEMANTIC_SYNC);
        setTextIfChanged(tv, label);
    }

    private void renderExtremeText(final TextView tv, final String text) {
        setTextIfChanged(tv, text);
    }

    /** DFCO/SYNC 期间清除 A/F 闪烁状态, 防止残留 flash 压暗 alpha */
    private void clearAfFlashIfNeeded() {
        if (!afFlashing) return;
        afFlashing = false;
        if (valueIntViews[5] != null) valueIntViews[5].setAlpha(1f);
        updateFlashState();
    }

    /** V2.6.3: A/F 进入 DFCO/SYNC 时, 清理 A/F 独有报警、闪烁、半透明残留 */
    private void clearAfSemanticContamination() {
        afFlashing = false;

        if (valueIntViews[5] != null) {
            valueIntViews[5].setAlpha(ALPHA_SEMANTIC_SYNC);
            valueIntViews[5].setTextColor(COLOR_SEMANTIC_SYNC);
        }

        if (valueDecViews[5] != null) {
            valueDecViews[5].setAlpha(ALPHA_SEMANTIC_SYNC);
            valueDecViews[5].setText("");
            valueDecViews[5].setVisibility(View.GONE);
        }

        updateFlashState();
    }

    // === V2.6.4: 发动机运转极值门控 ===

    private void updateEngineRunningGate(SensorData data) {
        float rpm = (float) data.getDouble(0x0100);
        long now = SystemClock.elapsedRealtime();

        if (rpm >= ENGINE_RUNNING_RPM_THRESHOLD) {
            if (engineRunningSinceMs <= 0L) {
                engineRunningSinceMs = now;
            }
            engineRunningStable = (now - engineRunningSinceMs) >= ENGINE_RUNNING_STABLE_MS;
        } else {
            engineRunningSinceMs = 0L;
            engineRunningStable = false;
        }
    }

    private boolean isEngineIndependentExtremeCard(int i) {
        return i == 0 || i == 1 || i == 2; // Ethanol, ECT, IAT
    }

    // V2.6.7: 通电后所有主卡片允许临时 MAX/MIN；发动机稳定后一次性 baseline 覆盖
    private boolean canRecordExtremeNow(int i) {
        if (i == 0) {
            // Ethanol 仍需 settling gate
            return ethanolExtremeReady;
        }
        // 通电阶段 1~7 可临时记录；发动机运行阶段也继续记录
        return true;
    }

    // V2.6.6: 极值重置函数
    private void resetExtremeHistoryForCard(int i) {
        hasValue[i] = false;
        maxTrack[i] = 0f;
        minTrack[i] = 0f;
        recentMax[i] = 0f;
        recentMin[i] = 0f;
        lastMaxTime[i] = 0L;
        lastMinTime[i] = 0L;
        recentMaxTime[i] = 0L;
        recentMinTime[i] = 0L;
        recentMaxDecayTimeMs[i] = 0L;
        recentMinDecayTimeMs[i] = 0L;
        if (i == 4) resetLastBoostEventPeak();
        if (flightRecorder != null) flightRecorder.onExtremaReset(i, SystemClock.elapsedRealtime());
    }

    private void resetAllExtremeHistory() {
        for (int i = 0; i < 8; i++) {
            resetExtremeHistoryForCard(i);
        }
    }

    // V2.6.7: 统一初始化极值卡片
    // V2.6.8 (方案C): 防御性 NaN/Infinity 过滤, 防止异常值污染 maxTrack/minTrack
    private void initializeExtremeForCard(int i, float sessionValue, float recentValue, long now) {
        if (Float.isNaN(sessionValue) || Float.isInfinite(sessionValue)) return;
        if (Float.isNaN(recentValue) || Float.isInfinite(recentValue)) return;
        maxTrack[i] = sessionValue;
        minTrack[i] = sessionValue;
        recentMax[i] = recentValue;
        recentMin[i] = recentValue;
        lastMaxTime[i] = now;
        lastMinTime[i] = now;
        recentMaxTime[i] = now;
        recentMinTime[i] = now;
        hasValue[i] = true;
    }

    // V2.6.7: Ethanol baseline 需等 settling gate
    private boolean shouldDelayEngineBaseline(int i) {
        if (i == 0) {
            return !ethanolExtremeReady;
        }
        return false;
    }

    // V2.6.7: 发动机稳定后一次性 baseline 覆盖
    private boolean applyEngineBaselineIfNeeded(int i, float valueForExtreme, float fVal, long now) {
        if (EXTREME_POLICY[i] == EXTREME_NONE) return false;
        if (!engineRunningStable || !engineExtremeSessionActive) {
            return false;
        }

        if (engineBaselineApplied[i]) {
            return false;
        }

        if (shouldDelayEngineBaseline(i)) {
            return false;
        }

        float sessionValue = SHOW_SESSION_EXTREME[i] ? valueForExtreme : fVal;

        if (SHOW_SESSION_EXTREME[i] && !isTrustedForSessionExtreme(i, sessionValue)) {
            return false;
        }

        initializeExtremeForCard(i, sessionValue, fVal, now);
        engineBaselineApplied[i] = true;
        return true;
    }

    // V2.6.7: 发动机运行周期结束
    private void endEngineExtremeSession(long now) {
        if (flightRecorder != null) flightRecorder.onEngineSessionEnded(now);
        engineExtremeSessionActive = false;
        engineExtremeSessionStartMs = 0L;
        engineStoppedSinceMs = 0L;

        for (int i = 0; i < 8; i++) {
            engineBaselineApplied[i] = false;
        }

        resetEthanolSettlingGate();
        // RC7 bounded ephemeral memory is a single-drive working set only.
        trustedDisplayMemory.reset();
        diagnosticMemory.clear();
        combustionAdmission.reset();
        fuelPressureAlert.reset();
    }

    // V2.6.7: 发动机极值 session — 不清临时极值，只重置 baseline 标记
    private void updateEngineExtremeSession(SensorData data) {
        float rpm = (float) data.getDouble(0x0100);
        long now = SystemClock.elapsedRealtime();

        if (engineRunningStable && !engineExtremeSessionActive) {
            engineExtremeSessionActive = true;
            engineExtremeSessionStartMs = now;
            engineStoppedSinceMs = 0L;

            for (int i = 0; i < 8; i++) {
                engineBaselineApplied[i] = false;
            }

            // 发动机运行周期开始后，Ethanol baseline 需重新等待稳定
            resetEthanolSettlingGate();
            resetLastBoostEventPeak();
        }

        if (rpm <= ENGINE_STOPPED_RPM_THRESHOLD) {
            if (engineStoppedSinceMs <= 0L) {
                engineStoppedSinceMs = now;
            }

            if (now - engineStoppedSinceMs >= ENGINE_STOPPED_STABLE_MS) {
                endEngineExtremeSession(now);
            }
        } else {
            engineStoppedSinceMs = 0L;
        }
    }

    // V2.6.6: Ethanol 爬升门控
    private void resetEthanolSettlingGate() {
        ethanolFirstSeenMs = 0L;
        ethanolStableSinceMs = 0L;
        lastEthanolForGate = Float.NaN;
        ethanolExtremeReady = false;
    }

    private void updateEthanolSettlingGate(float ethanol) {
        if (Float.isNaN(ethanol) || Float.isInfinite(ethanol)) return;
        if (ethanol < 0f || ethanol > 100f) return;

        long now = SystemClock.elapsedRealtime();

        if (ethanolFirstSeenMs <= 0L) {
            ethanolFirstSeenMs = now;
            ethanolStableSinceMs = now;
            lastEthanolForGate = ethanol;
            ethanolExtremeReady = false;
            return;
        }

        if (Float.isNaN(lastEthanolForGate)) {
            lastEthanolForGate = ethanol;
            ethanolStableSinceMs = now;
            return;
        }

        float delta = Math.abs(ethanol - lastEthanolForGate);

        if (delta > ETHANOL_STABLE_DELTA) {
            ethanolStableSinceMs = now;
        }

        lastEthanolForGate = ethanol;

        boolean minObserved = (now - ethanolFirstSeenMs) >= ETHANOL_SETTLE_MIN_MS;
        boolean stableEnough = (now - ethanolStableSinceMs) >= ETHANOL_STABLE_MS;

        if (minObserved && stableEnough) {
            ethanolExtremeReady = true;
        }
    }

    // V2.6.7: A/F / IGN / S.TRIM 低置信度灰色模式 — 固定颜色+固定 alpha
    private boolean isConfidenceSensitiveCard(int i) {
        // RC8: S.TRIM owns a continuous interpretability-weighted presentation path.
        return i == 5 || i == 6; // A/F, IGN
    }

    // V2.6.7: 冷启动/暖机判定 — 不用 closedLoop 短暂 open loop 作为依据
    private boolean isWarmupLowReference(EngineSemanticState state, SensorData data) {
        float ect = (float) data.getDouble(0x0160);
        boolean coldEngine = ect < WARMUP_ECT_THRESHOLD;
        boolean thermalWarmup = state.isThermalWarmup();
        return coldEngine || (thermalWarmup && ect < 80f);
    }

    // V2.6.7: 动态低 confidence 判定 — WOT/Modifier + 低 confidence
    private boolean isDynamicLowReference(EngineSemanticState state) {
        boolean dynamicOrHighLoad = state.hasModifier() || state.isWot();
        return dynamicOrHighLoad && state.confidence < LOW_CONFIDENCE_THRESHOLD;
    }

    // V2.6.7: 灰色显示判定 — 冷启动/暖机灰，热启动水温正常不灰，热车怠速不灰
    private boolean shouldApplyConfidenceGray(int i, EngineSemanticState state, SensorData data) {
        if (!isConfidenceSensitiveCard(i)) return false;
        if (semanticMode[i]) return false;
        if (valueIntViews[i] == null) return false;

        // A/F 红色报警优先
        if (i == 5 && afFlashing) return false;

        // V2.6.9 (P0-3): 长断重连视觉抑制窗口内不灰显 (给数据稳定时间)
        long nowGray = SystemClock.elapsedRealtime();
        if (reconnectVisualSuppressUntilMs > 0L && nowGray < reconnectVisualSuppressUntilMs) {
            return false;
        }

        // 发动机还没稳定运行前，不做灰显
        if (!engineRunningStable || !engineExtremeSessionActive) return false;

        long now = SystemClock.elapsedRealtime();
        long sinceEngineSessionStart = engineExtremeSessionStartMs > 0L
                ? now - engineExtremeSessionStartMs
                : 0L;

        boolean warmupLowReference = isWarmupLowReference(state, data);

        // 冷启动/暖机：应灰显
        if (warmupLowReference) {
            return true;
        }

        // 热启动：水温正常、非暖机，给 3 秒恢复窗口
        if (sinceEngineSessionStart < HOT_START_CONFIDENCE_SUPPRESS_MS) {
            return false;
        }

        // 热车稳定怠速不灰显
        if (state.isIdle()) {
            return false;
        }

        // 正常巡航不灰显；动态/高负荷低 confidence 才灰显
        return isDynamicLowReference(state);
    }

    private void applyConfidenceVisual(int i, EngineSemanticState state, SensorData data) {
        if (!shouldApplyConfidenceGray(i, state, data)) {
            return;
        }

        // V2.6.7: 固定颜色、固定 alpha，避免不同卡片深浅不一致
        valueIntViews[i].setTextColor(COLOR_LOW_CONFIDENCE);
        valueIntViews[i].setAlpha(LOW_CONFIDENCE_ALPHA);

        if (valueDecViews[i] != null) {
            valueDecViews[i].setTextColor(COLOR_LOW_CONFIDENCE);
            valueDecViews[i].setAlpha(LOW_CONFIDENCE_ALPHA);
        }
    }

    /**
     * RC8 S.TRIM presentation: keep the control signal visible, but continuously
     * modulate its visual authority according to interpretability. High-confidence
     * stable closed-loop permits semantic colour; dynamic closed-loop stays live
     * but quieter; open-loop/high-load values are visible only as low-authority context.
     */
    private void applyStrimInterpretabilityVisual(EngineSemanticState state, SensorData data, long now) {
        if (valueIntViews[7] == null || displayHoldMode[7] || semanticMode[7]) return;
        float target = getStrimInterpretabilityTarget(state, data);
        float weight = updateStrimPresentationWeight(target, now);

        if (weight < 0.70f) {
            valueIntViews[7].setTextColor(blendArgb(COLOR_LOW_CONFIDENCE, COLOR_TEXT_NORMAL, 0.20f));
        } else if (weight < 0.95f) {
            // Context-live: neutral colour suppresses false health interpretation.
            valueIntViews[7].setTextColor(COLOR_TEXT_NORMAL);
        }
        valueIntViews[7].setAlpha(weight);
        if (valueDecViews[7] != null) {
            valueDecViews[7].setTextColor(valueIntViews[7].getCurrentTextColor());
            valueDecViews[7].setAlpha(weight);
        }
        if (scaleBars[7] != null) scaleBars[7].setAlpha(0.72f + 0.28f * weight);
    }

    private float getStrimInterpretabilityTarget(EngineSemanticState state, SensorData data) {
        if (state == null || data == null) return STRIM_WEIGHT_LOW;
        if (isStableTrimColorContext(state, data)) return STRIM_WEIGHT_TRUSTED;

        float cl = (float) data.getDouble(HondataProtocol.CID_ClosedLoop);
        float target = (float) data.getDouble(HondataProtocol.CID_TargetLambda);
        float inj = (float) data.getDouble(HondataProtocol.CID_Inj);
        boolean firing = state.combustion == EngineSemanticState.CombustionState.FIRING_VALID
                && !state.isShiftActive() && !state.isFuelCut();
        boolean closedLoopUsable = firing
                && !Float.isNaN(cl) && cl > 0.5f
                && !Float.isNaN(target) && target > 0.90f && target < 1.10f
                && !Float.isNaN(inj) && inj > 0.30f;
        return closedLoopUsable ? STRIM_WEIGHT_CONTEXT : STRIM_WEIGHT_LOW;
    }

    private float updateStrimPresentationWeight(float target, long now) {
        if (strimPresentationUpdatedMs == 0L) {
            strimPresentationUpdatedMs = now;
            strimPresentationWeight = target;
            return target;
        }
        float dt = Math.max(0f, Math.min((now - strimPresentationUpdatedMs) / 1000f, 0.25f));
        strimPresentationUpdatedMs = now;
        float tau = target > strimPresentationWeight ? 0.70f : 0.30f;
        float alpha = 1f - (float) Math.exp(-dt / tau);
        strimPresentationWeight += (target - strimPresentationWeight) * alpha;
        return strimPresentationWeight;
    }

    private int blendArgb(int from, int to, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int a = (int) (((from >>> 24) & 0xff) + ((((to >>> 24) & 0xff) - ((from >>> 24) & 0xff)) * t));
        int r = (int) (((from >>> 16) & 0xff) + ((((to >>> 16) & 0xff) - ((from >>> 16) & 0xff)) * t));
        int g = (int) (((from >>> 8) & 0xff) + ((((to >>> 8) & 0xff) - ((from >>> 8) & 0xff)) * t));
        int b = (int) ((from & 0xff) + (((to & 0xff) - (from & 0xff)) * t));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // V2.6.7: 蓝牙重连后判断是否需要重置 session
    private void handleReconnectSessionPolicy(SensorData data) {
        if (!btWasDisconnected) {
            return;
        }

        long now = SystemClock.elapsedRealtime();
        long lostMs = now - lastBtDisconnectedAtMs;

        // 短断：继续原 session，不重置 MAX/MIN，不重新 baseline
        if (lostMs <= BT_SESSION_PRESERVE_MS) {
            btWasDisconnected = false;
            lastBtDisconnectedAtMs = 0L;
            return;
        }

        // 长时间断开：根据重连后的 RPM 判断旧 session 是否已结束
        double rpmRaw = data.getDouble(0x0100);
        // V2.6.9 (P1-1): RPM 缺失 (NaN) 时不清 btWasDisconnected, 等下一帧再判断
        // 否则 NaN<=300 为 false → 误判为发动机在运行 → 错误保留旧 session
        if (Double.isNaN(rpmRaw) || Double.isInfinite(rpmRaw)) {
            return;
        }
        float rpm = (float) rpmRaw;

        // RPM 有效, 正式处理重连, 清除断线标记
        btWasDisconnected = false;
        lastBtDisconnectedAtMs = 0L;

        if (rpm <= ENGINE_STOPPED_RPM_THRESHOLD) {
            // 长断后重连且发动机已停，才结束 session 并重置极值
            resetAllExtremeHistoryForNewConnectionSession();
        } else {
            // V2.6.9 (P0-3): 长断重连但发动机仍在运转 (服务区不熄火/蓝牙模块故障)
            // 方案 A: 视为同一运行周期 — 完全不动 baseline/session, 真正保留 MAX/MIN
            // 只设独立视觉抑制字段, 给灰显一个重连恢复窗口
            // (旧 BG5 方案会重置 baseline, 导致 1s 后当前值覆盖原 MAX/MIN, 语义错误)
            reconnectVisualSuppressUntilMs = SystemClock.elapsedRealtime() + RECONNECT_VISUAL_SUPPRESS_MS;
        }
    }

    // V2.6.7: 长断重连后发动机已停时，重置全部状态
    private void resetAllExtremeHistoryForNewConnectionSession() {
        for (int i = 0; i < 8; i++) {
            resetExtremeHistoryForCard(i);
            engineBaselineApplied[i] = false;
            hasFiltered[i] = false;
            hasValidValue[i] = false;
        }

        // V2.6.8 (BG1 补充): 重置爆震基线, 避免长断重连后 ECU 计数跳变产生负 delta 噪音
        Arrays.fill(lastKnockCount, -1);
        Arrays.fill(cylYellowEnd, 0L);
        cylRapidAccum = 0;
        cylRapidStart = 0L;
        cylRedFlashing = false;

        engineRunningSinceMs = 0L;
        engineRunningStable = false;
        engineExtremeSessionActive = false;
        engineExtremeSessionStartMs = 0L;
        engineStoppedSinceMs = 0L;

        resetEthanolSettlingGate();
        trustedDisplayMemory.reset();
        diagnosticMemory.clear();
        combustionAdmission.reset();
        fuelPressureAlert.reset();
    }

    // Shared with source-driven previews; boundaries and existing purple alarms are unchanged.
    private int getEthanolColor(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return COLOR_DANGER;
        // Flex-fuel content is capability/context, not a danger scale. E20+ is
        // the user's high-boost enable region; high ethanol is not painted red.
        if (value < 20) return COLOR_TEXT_NORMAL;
        if (value <= 50) return DashboardPalette.GREEN;
        if (value <= 85) return DashboardPalette.CYAN;
        return COLOR_WARN; // unusually high for this calibration, caution only
    }

    private int getEctColor(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return COLOR_DANGER;
        // RC5 thermal semantics: the observed 82°C cruise core and ~92°C fan
        // intervention remain normal. Warning begins only after normal heat
        // management has failed to settle the temperature.
        if (value < 65) return COLOR_INFO_BLUE;
        if (value <= 96) return DashboardPalette.CYAN;
        if (value <= 102) return COLOR_WARN;
        if (value <= 108) return COLOR_DANGER;
        return DashboardPalette.PURPLE;
    }

    private int getIatColor(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return COLOR_DANGER;
        // IAT is context-sensitive; ordinary ambient/heat-soak temperatures stay
        // neutral so green/yellow are not overused during everyday commuting.
        if (value < 10) return DashboardPalette.CYAN;
        if (value < 45) return COLOR_TEXT_NORMAL;
        if (value < 55) return COLOR_WARN;
        if (value >= 65) return DashboardPalette.PURPLE;
        return COLOR_DANGER;
    }

    /** V2.7.0: L.TRIM / S.TRIM 颜色 — 按偏离 0 的绝对值判断 */
    private int getTrimSemanticColor(float trim) {
        float abs = Math.abs(trim);
        if (abs <= TRIM_GREEN_ABS_MAX) return COLOR_SAFE;
        if (abs <= TRIM_WARN_ABS_MAX) return COLOR_WARN;
        return COLOR_DANGER;
    }

    /** V2.7.0: IGN 颜色 — 只负责数字状态；DFCO/SYNC/低置信度由外层原逻辑处理 */
    private int getIgnSemanticColor(float ign) {
        if (ign >= IGN_GREEN_MIN) return COLOR_SAFE;
        if (ign >= IGN_WARN_MIN) return COLOR_WARN;
        return COLOR_DANGER;
    }

    /** V2.7.0: MAP 颜色 — 输入必须是当前主卡片显示的相对增压 bar */
    private int getMapSemanticColor(float boostBar) {
        if (boostBar <= MAP_GREEN_MAX) return COLOR_SAFE;
        if (boostBar <= MAP_WARN_MAX) return COLOR_WARN;
        return COLOR_DANGER;
    }

    /**
     * V2.7.0: 主数据语义颜色入口。
     * 只处理 L.TRIM / MAP / IGN / S.TRIM。
     * 不处理 Ethanol / ECT / IAT / A/F，这些卡片已有独立颜色逻辑。
     * 必须在 DFCO/SYNC 前置门控之后、applyConfidenceVisual 之前调用。
     */
    private void updateMainColorState(int i, float value, long now,
            EngineSemanticState state, SensorData data) {
        if (!isSignedMainCard(i)) return;

        // S.TRIM is a health/trend channel: transient acceleration/deceleration
        // numbers remain visible but do not paint the dashboard yellow/red. Only
        // stable closed-loop cruise is a high-confidence colour context.
        if (i == 7 && !isStableTrimColorContext(state, data)) {
            colorRecovery.reset(i);
            resolvedMainColors[i] = COLOR_TEXT_NORMAL;
            return;
        }

        int desired = i == 4 ? getMapSemanticColor(value)
                : i == 6 ? getIgnSemanticColor(value) : getTrimSemanticColor(value);
        int severity = desired == COLOR_SAFE ? 0 : desired == COLOR_WARN ? 1 : 2;
        long attackMs = 0L;
        if (i == 7) attackMs = severity >= 2 ? 1200L : severity == 1 ? 800L : 0L;
        int shown = colorRecovery.update(i, severity, now, attackMs);
        if (i == 7 && severity > shown) {
            resolvedMainColors[i] = COLOR_TEXT_NORMAL;
        } else {
            resolvedMainColors[i] = severityColor(shown);
        }
    }

    private boolean isStableTrimColorContext(EngineSemanticState state, SensorData data) {
        if (state == null || data == null || !state.isThermallyReady() || state.isShiftActive()
                || state.combustion != EngineSemanticState.CombustionState.FIRING_VALID
                || state.modifier != EngineSemanticState.Modifier.NONE) return false;
        float cl = (float) data.getDouble(HondataProtocol.CID_ClosedLoop);
        float target = (float) data.getDouble(HondataProtocol.CID_TargetLambda);
        float inj = (float) data.getDouble(HondataProtocol.CID_Inj);
        float speed = (float) data.getDouble(HondataProtocol.CID_Speed);
        float rpm = (float) data.getDouble(HondataProtocol.CID_RPM);
        float tp = (float) data.getDouble(HondataProtocol.CID_ThrottlePlate);
        return !Float.isNaN(cl) && cl > 0.5f
                && !Float.isNaN(target) && target > 0.90f && target < 1.10f
                && !Float.isNaN(inj) && inj > 0.30f
                && !Float.isNaN(speed) && speed > 20f
                && !Float.isNaN(rpm) && rpm > 1000f && rpm < 3500f
                && !Float.isNaN(tp) && tp > 2f && tp < 35f;
    }

    private void applyMainValueSemanticColor(int i, float fVal) {
        if (valueIntViews[i] == null) return;
        if (semanticMode[i]) return;

        switch (i) {
            case 3: // L.TRIM
                valueIntViews[i].setTextColor(resolvedMainColors[i]);
                valueIntViews[i].setAlpha(1f);
                break;
            case 4: // MAP: fVal 已经是相对增压 bar
                valueIntViews[i].setTextColor(resolvedMainColors[i]);
                valueIntViews[i].setAlpha(1f);
                break;
            case 6: // IGN
                valueIntViews[i].setTextColor(resolvedMainColors[i]);
                valueIntViews[i].setAlpha(1f);
                break;
            case 7: // S.TRIM
                valueIntViews[i].setTextColor(resolvedMainColors[i]);
                valueIntViews[i].setAlpha(1f);
                break;
        }
    }

    /**
     * A/F colour is a diagnostic layer, not a raw-motion indicator. Keep the
     * number live whenever admitted, but only attach green/amber/red semantics
     * in high-confidence closed-loop or meaningful high-load contexts.
     */
    private boolean isAfColorContext(EngineSemanticState state, SensorData data) {
        if (state == null || data == null || state.isShiftActive()
                || state.combustion != EngineSemanticState.CombustionState.FIRING_VALID) return false;
        if (state.isWot()) return true;
        if (state.modifier == EngineSemanticState.Modifier.TIP_IN) {
            float map = (float) data.getDouble(HondataProtocol.CID_MAP);
            float tp = (float) data.getDouble(HondataProtocol.CID_ThrottlePlate);
            return !Float.isNaN(map) && !Float.isNaN(tp) && map > 100f && tp > 10f;
        }
        if (state.modifier != EngineSemanticState.Modifier.NONE) return false;
        float cl = (float) data.getDouble(HondataProtocol.CID_ClosedLoop);
        float target = (float) data.getDouble(HondataProtocol.CID_TargetLambda);
        float inj = (float) data.getDouble(HondataProtocol.CID_Inj);
        float rpm = (float) data.getDouble(HondataProtocol.CID_RPM);
        return !Float.isNaN(cl) && cl > 0.5f
                && !Float.isNaN(target) && target > 0.90f && target < 1.10f
                && !Float.isNaN(inj) && inj > 0.30f
                && !Float.isNaN(rpm) && rpm > 600f;
    }

    /** RC7 A/F severity; value stays live, only warning colour is debounced. */
    private int getAfSeverity(float lambda, float targetLambda, EngineSemanticState state) {
        boolean lambdaValid = !Float.isNaN(lambda) && !Float.isInfinite(lambda);
        boolean targetValid = !Float.isNaN(targetLambda) && !Float.isInfinite(targetLambda);
        if (!lambdaValid) return 0;
        if (state != null && state.isWot()) {
            if (lambda > WOT_LAMBDA_DANGER_LEAN) return 2;
            if (lambda > WOT_LAMBDA_WARN_LEAN || lambda < WOT_LAMBDA_WARN_RICH) return 1;
            return 0;
        }
        if (!targetValid) return 0;
        float err = Math.abs(lambda - targetLambda);
        if (err <= CL_LAMBDA_ERR_GREEN) return 0;
        if (err <= CL_LAMBDA_ERR_WARN) return 1;
        return 2;
    }

    private long getAfAttackMs(int severity, EngineSemanticState state) {
        if (severity <= 0) return 0L;
        if (state != null && state.isWot()) return severity >= 2 ? 100L : 150L;
        return severity >= 2 ? 250L : 350L;
    }

    private int severityColor(int severity) {
        return severity <= 0 ? COLOR_SAFE : severity == 1 ? COLOR_WARN : COLOR_DANGER;
    }

    /**
     * Boost 不对称滤波: 增压快响应, 泄压按工况动态调整。
     * RC7 does not change MAP display semantics.
     */
    private float boostFilter(float raw, float last, EngineSemanticState state) {
        if (raw > last) {
            return ema(raw, last, 0.6f);
        } else {
            float release = state.boostRelease();
            return ema(raw, last, release);
        }
    }

    @Override
    public void onError(final String msg) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (activityDestroyed || statusText == null) return;
                boolean bluetoothOff = msg != null && msg.contains("蓝牙未启用");
                setConnectionStatus(bluetoothOff ? "BT OFF" : "RECONNECT",
                        bluetoothOff ? COLOR_DANGER : COLOR_WARN);
                applyAlertAndFreshnessVisuals();
                updateFlashState();
            }
        });
    }

    // ===== Dynamics Archetype 情绪引擎 =====

    /**
     * 根据参数类型和当前值/趋势, 设置 ScaleBar 情绪。
     * 每个参数拥有不同的情绪转换逻辑, 情绪强度由 ScaleBar 内部渐变跟随。
     * 原则: "感受到，但不打扰" — 渐变跟随动力学状态
     */
    private void updateEmotion(int i, float val, EngineSemanticState engineSt) {
        ScaleBarView bar = scaleBars[i];
        if (bar == null) return;

        float delta = val - lastScaleVal[i];
        lastScaleVal[i] = val;

        switch (i) {
            case 0: // Ethanol: STATIC — 无情绪, 锁定态
                bar.setEmotion(ScaleBarView.EMOTION_NONE, 0);
                break;

            case 1: // ECT: THERMAL — 热积累情绪
                if (val > 105) {
                    bar.setEmotion(ScaleBarView.EMOTION_DANGER, (val - 105) / 15f);
                } else if (val > 95) {
                    bar.setEmotion(ScaleBarView.EMOTION_WARNING, (val - 95) / 10f);
                } else if (delta > 0.1f) {
                    bar.setEmotion(ScaleBarView.EMOTION_BUILDING, delta / 2f);
                } else {
                    bar.setEmotion(ScaleBarView.EMOTION_STABLE, 0);
                }
                break;

            case 2: // IAT: THERMAL — 热浸/降温
                if (val >= 65) {
                    bar.setEmotion(ScaleBarView.EMOTION_DANGER, (val - 65) / 20f);
                } else if (val >= 50) {
                    bar.setEmotion(ScaleBarView.EMOTION_WARNING, (val - 50) / 15f);
                } else if (delta > 0.1f) {
                    bar.setEmotion(ScaleBarView.EMOTION_BUILDING, delta / 2f);
                } else if (delta < -0.1f) {
                    bar.setEmotion(ScaleBarView.EMOTION_RELEASING, -delta / 2f);
                } else {
                    bar.setEmotion(ScaleBarView.EMOTION_STABLE, 0);
                }
                break;

            case 3: // L.TRIM: THERMAL — 大偏差时警告
                float trimAbs = Math.abs(val);
                if (trimAbs > 20) {
                    bar.setEmotion(ScaleBarView.EMOTION_WARNING, (trimAbs - 20) / 5f);
                } else {
                    bar.setEmotion(ScaleBarView.EMOTION_STABLE, 0);
                }
                break;

            case 4: // Boost: MECHANICAL — BUILDING/STABLE/RELEASE/COLLAPSE
                if (val > 0.3f && delta > 0.01f) {
                    bar.setEmotion(ScaleBarView.EMOTION_BUILDING, Math.min(1f, val / 1.8f));
                } else if (val > 0.3f && delta < -0.02f) {
                    // 泄放: 强度由下降速度驱动
                    bar.setEmotion(ScaleBarView.EMOTION_RELEASING, Math.min(1f, -delta * 5f));
                } else if (val > 1.5f) {
                    bar.setEmotion(ScaleBarView.EMOTION_BUILDING, 1f);
                } else if (val > 0.1f) {
                    bar.setEmotion(ScaleBarView.EMOTION_STABLE, 0);
                } else {
                    bar.setEmotion(ScaleBarView.EMOTION_NONE, 0);
                }
                break;

            case 5: // A/F: TRANSIENT — 燃烧事件
                if (val < 10.5f) {
                    bar.setEmotion(ScaleBarView.EMOTION_DANGER, (10.5f - val) / 1.5f);
                } else if (val > 16.0f && lastTpPlate > 5) {
                    bar.setEmotion(ScaleBarView.EMOTION_DANGER, (val - 16.0f) / 2.0f);
                } else if (val < 12.0f && lastTpPlate > 5) {
                    bar.setEmotion(ScaleBarView.EMOTION_WARNING, (12.0f - val) / 1.5f);
                } else {
                    bar.setEmotion(ScaleBarView.EMOTION_STABLE, 0);
                }
                break;

            case 6: // IGN: MECHANICAL — ECU保护
                if (val < -10 && lastTpPlate > 10) {
                    bar.setEmotion(ScaleBarView.EMOTION_PROTECTION, Math.min(1f, (-val - 10) / 15f));
                } else if (val < -5 && lastTpPlate > 10) {
                    bar.setEmotion(ScaleBarView.EMOTION_WARNING, (-val - 5) / 5f);
                } else {
                    bar.setEmotion(ScaleBarView.EMOTION_STABLE, 0);
                }
                break;

            case 7: // S.TRIM: TRANSIENT — 振荡活跃度
                float stAbs = Math.abs(val);
                if (stAbs > 15 && lastTpPlate > 10) {
                    bar.setEmotion(ScaleBarView.EMOTION_WARNING, (stAbs - 15) / 10f);
                } else if (stAbs > 8 && lastTpPlate > 10) {
                    bar.setEmotion(ScaleBarView.EMOTION_BUILDING, (stAbs - 8) / 7f);
                } else {
                    bar.setEmotion(ScaleBarView.EMOTION_STABLE, 0);
                }
                break;
        }
    }

    // ===== 蓝牙连接 =====

    private void connectBluetooth() {
        BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        if (bt == null) {
            setConnectionStatus("NO BT", COLOR_DANGER);
            Toast.makeText(this, "车机不支持经典蓝牙", Toast.LENGTH_LONG).show();
            return;
        }
        if (!bt.isEnabled()) {
            setConnectionStatus("BT OFF", COLOR_DANGER);
            if (!bluetoothEnableDeclined && !bluetoothEnableRequestInFlight) {
                bluetoothEnableRequestInFlight = true;
                startActivityForResult(
                        new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                        REQUEST_ENABLE_BLUETOOTH);
            }
            return;
        }
        bluetoothEnableDeclined = false;

        List<BluetoothDevice> bondedDevices = getBondedDevices(bt);
        String savedAddress = loadSavedBluetoothAddress();
        BluetoothDevice savedDevice = findDeviceByAddress(bondedDevices, savedAddress);
        if (savedDevice != null) {
            selectedBluetoothAddress = safeDeviceAddress(savedDevice);
            setConnectionStatus("CONNECT", COLOR_WARN);
            dataSource.connect(selectedBluetoothAddress);
            return;
        }

        if (savedAddress != null) {
            // 已保存的设备被系统取消配对，禁止对旧 MAC 进行无限重连。
            clearSavedBluetoothDevice();
        }

        BluetoothDevice automaticCandidate = findUniqueFlashPro(bondedDevices);
        if (automaticCandidate != null) {
            selectBluetoothDevice(automaticCandidate);
        } else {
            showBluetoothDeviceChooser(true);
        }
    }

    /**
     * 只列出系统已配对设备。搜索、PIN 交互和配对由系统蓝牙设置处理，
     * App 不扫描位置、不在源码中保存 MAC。
     */
    private void showBluetoothDeviceChooser(boolean allowAutomaticSelection) {
        if (activityDestroyed || isFinishing() || bluetoothDialogShowing) return;

        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            connectBluetooth();
            return;
        }

        final List<BluetoothDevice> devices = getBondedDevices(adapter);
        if (allowAutomaticSelection) {
            BluetoothDevice automaticCandidate = findUniqueFlashPro(devices);
            if (automaticCandidate != null) {
                selectBluetoothDevice(automaticCandidate);
                return;
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        if (devices.isEmpty()) {
            setConnectionStatus("PAIR", COLOR_WARN);
            builder.setTitle("首次连接 FlashPro")
                    .setMessage("请先在系统蓝牙设置中搜索并配对 FlashPro，默认 PIN 为 1234。\n\n若车机找不到设备，请先用 FlashProManager 开启 2018+ Civic 车机替代配对模式。\n\n配对完成后返回本应用。")
                    .setPositiveButton("打开蓝牙设置", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dialog, int which) {
                            openBluetoothSettings();
                        }
                    })
                    .setNegativeButton("稍后", null);
        } else {
            setConnectionStatus("SELECT", COLOR_WARN);
            final String[] labels = new String[devices.size()];
            for (int i = 0; i < devices.size(); i++) {
                labels[i] = formatBluetoothDeviceLabel(devices.get(i));
            }
            builder.setTitle("选择 FlashPro（也可能显示为 Server）")
                    .setItems(labels, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dialog, int which) {
                            if (which >= 0 && which < devices.size()) {
                                selectBluetoothDevice(devices.get(which));
                            }
                        }
                    })
                    .setPositiveButton("配对新设备", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dialog, int which) {
                            openBluetoothSettings();
                        }
                    })
                    .setNegativeButton("取消", null);
        }

        final AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface ignored) {
                bluetoothDialogShowing = false;
            }
        });
        bluetoothDialogShowing = true;
        dialog.show();
    }

    private void selectBluetoothDevice(BluetoothDevice device) {
        String address = safeDeviceAddress(device);
        if (address == null || !BluetoothAdapter.checkBluetoothAddress(address)) {
            Toast.makeText(this, "无法读取该蓝牙设备", Toast.LENGTH_LONG).show();
            return;
        }

        String normalizedAddress = address.toUpperCase(Locale.US);
        boolean deviceChanged = selectedBluetoothAddress == null
                || !selectedBluetoothAddress.equalsIgnoreCase(normalizedAddress);
        selectedBluetoothAddress = normalizedAddress;
        saveBluetoothDevice(normalizedAddress, safeDeviceName(device));

        if (deviceChanged) {
            // 旧连接线程可能卡在 RFCOMM connect()；更换数据源可彻底取消它，
            // 并防止旧回调覆盖新设备的顶栏状态。
            replaceBluetoothSource();
        }

        setConnectionStatus("CONNECT", COLOR_WARN);
        dataSource.connect(selectedBluetoothAddress);
    }

    private void replaceBluetoothSource() {
        DataSource previous = dataSource;
        if (previous != null) {
            previous.setCallback(null);
            previous.disconnect();
        }
        BluetoothSource replacement = new BluetoothSource();
        replacement.setCallback(this);
        dataSource = replacement;
    }

    private List<BluetoothDevice> getBondedDevices(BluetoothAdapter adapter) {
        List<BluetoothDevice> devices = new ArrayList<BluetoothDevice>();
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) devices.addAll(bonded);
        } catch (SecurityException error) {
            Log.e("HondataDash", "无法读取已配对蓝牙设备", error);
            Toast.makeText(this, "请允许蓝牙访问权限", Toast.LENGTH_LONG).show();
        }
        Collections.sort(devices, new Comparator<BluetoothDevice>() {
            @Override public int compare(BluetoothDevice left, BluetoothDevice right) {
                boolean leftPreferred = isLikelyFlashPro(left);
                boolean rightPreferred = isLikelyFlashPro(right);
                if (leftPreferred != rightPreferred) return leftPreferred ? -1 : 1;
                int byName = safeDeviceName(left).compareToIgnoreCase(safeDeviceName(right));
                if (byName != 0) return byName;
                String leftAddress = safeDeviceAddress(left);
                String rightAddress = safeDeviceAddress(right);
                if (leftAddress == null) leftAddress = "";
                if (rightAddress == null) rightAddress = "";
                return leftAddress.compareToIgnoreCase(rightAddress);
            }
        });
        return devices;
    }

    private BluetoothDevice findUniqueFlashPro(List<BluetoothDevice> devices) {
        BluetoothDevice candidate = null;
        for (BluetoothDevice device : devices) {
            if (!isLikelyFlashPro(device)) continue;
            if (candidate != null) return null;
            candidate = device;
        }
        return candidate;
    }

    private BluetoothDevice findDeviceByAddress(List<BluetoothDevice> devices, String address) {
        if (address == null) return null;
        for (BluetoothDevice device : devices) {
            String candidateAddress = safeDeviceAddress(device);
            if (candidateAddress != null && address.equalsIgnoreCase(candidateAddress)) return device;
        }
        return null;
    }

    private boolean isLikelyFlashPro(BluetoothDevice device) {
        String normalized = safeDeviceName(device).toLowerCase(Locale.US);
        return normalized.contains("flashpro") || normalized.contains("hondata");
    }

    private String safeDeviceName(BluetoothDevice device) {
        try {
            String name = device == null ? null : device.getName();
            return name == null || name.trim().length() == 0 ? "未命名设备" : name.trim();
        } catch (SecurityException ignored) {
            return "未命名设备";
        }
    }

    private String safeDeviceAddress(BluetoothDevice device) {
        try {
            return device == null ? null : device.getAddress();
        } catch (SecurityException ignored) {
            return null;
        }
    }

    private String formatBluetoothDeviceLabel(BluetoothDevice device) {
        String marker = isLikelyFlashPro(device) ? "\u2605 " : "";
        String address = safeDeviceAddress(device);
        String saved = address != null && selectedBluetoothAddress != null
                && address.equalsIgnoreCase(selectedBluetoothAddress) ? "  · 已选" : "";
        return marker + safeDeviceName(device) + saved + "\n" + (address == null ? "--" : address);
    }

    private String loadSavedBluetoothAddress() {
        SharedPreferences preferences = getSharedPreferences(BLUETOOTH_PREFS, MODE_PRIVATE);
        String address = preferences.getString(PREF_DEVICE_ADDRESS, null);
        if (address == null || !BluetoothAdapter.checkBluetoothAddress(address)) return null;
        return address.toUpperCase(Locale.US);
    }

    private void saveBluetoothDevice(String address, String name) {
        getSharedPreferences(BLUETOOTH_PREFS, MODE_PRIVATE).edit()
                .putString(PREF_DEVICE_ADDRESS, address)
                .putString(PREF_DEVICE_NAME, name)
                .apply();
    }

    private void clearSavedBluetoothDevice() {
        selectedBluetoothAddress = null;
        getSharedPreferences(BLUETOOTH_PREFS, MODE_PRIVATE).edit()
                .remove(PREF_DEVICE_ADDRESS)
                .remove(PREF_DEVICE_NAME)
                .apply();
    }

    private void openBluetoothSettings() {
        returningFromBluetoothSettings = true;
        try {
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        } catch (ActivityNotFoundException missingBluetoothSettings) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (ActivityNotFoundException missingSettings) {
                returningFromBluetoothSettings = false;
                Toast.makeText(this, "无法打开系统设置", Toast.LENGTH_LONG).show();
            }
        }
    }
}
