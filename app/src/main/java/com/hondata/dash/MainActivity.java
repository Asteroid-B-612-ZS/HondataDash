package com.hondata.dash;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.os.Bundle;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.util.TypedValue;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ReplacementSpan;

import com.hondata.dash.data.BluetoothSource;
import com.hondata.dash.data.DataSource;
import com.hondata.dash.data.DemoSource;
import com.hondata.dash.data.EngineSemanticState;
import com.hondata.dash.data.EngineStateTracker;
import com.hondata.dash.data.SensorData;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import android.os.SystemClock;

/**
 * 主界面 - 硬核科技风格车载仪表盘 V2.6.2。
 *
 * 4x2 HUD 网格 (英文缩写 + 刻度进度条 + MAX/MIN):
 *   Ethanol | ECT | IAT | BAT
 *   MAP     | IGN | A/F | L.TRIM
 *
 * 底部: KNOCK CTRL + CYL1-4 + L.TRIM + AFM + FUEL + WheelView
 * 页脚: REC | LOG | ECU | Time
 */
public class MainActivity extends Activity implements DataSource.Callback {

    private static final boolean USE_DEMO = false;

    private DataSource dataSource;
    private TextView statusText;
    private TextView sourceName;

    // 主卡片 (4x2 = 8个)
    private final TextView[] labelEnViews = new TextView[8];
    private final TextView[] valueIntViews = new TextView[8];
    private final TextView[] valueDecViews = new TextView[8];
    private final TextView[] unitViews = new TextView[8];
    private final ScaleBarView[] scaleBars = new ScaleBarView[8];

    // MAX / MIN 追踪
    private final TextView[] maxValueViews = new TextView[8];
    private final TextView[] minValueViews = new TextView[8];
    private final float[] maxTrack = new float[8];      // Session Peak (内部)
    private final float[] minTrack = new float[8];
    private final float[] recentMax = new float[8];     // Recent Peak (显示用, 30s衰减)
    private final float[] recentMin = new float[8];
    private final long[] lastMaxTime = new long[8];     // Cooldown 时间戳
    private final long[] lastMinTime = new long[8];
    private final long[] recentMaxTime = new long[8];   // Recent Peak 记录时间
    private final long[] recentMinTime = new long[8];
    private final boolean[] hasValue = new boolean[8];

    // V2.6: 显示适配 — 固定几何, 单 TextView, 无缓存
    private final View[] valueAreaViews = new View[8];
    private final View[] extremePanelViews = new View[8];
    private final boolean[] semanticMode = new boolean[8];

    // V2.6.2: 独立测量 Paint, 避免 TextView 当前 textScaleX 污染 measureText()
    private final TextPaint mainMeasurePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint extremeMeasurePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    // V2.6.3: 主数据横向缩放显示态 — 变窄立即响应，变宽阻尼释放
    private final float[] displayedMainScaleX = new float[8];

    // V2.6.5: 所有主数据使用统一固定字高，只做宽度缩放
    private static final float MAIN_VALUE_SP = 112f;

    // V2.6.4/V2.6.5: Compact +/- sign — fixed size via ReplacementSpan
    private static final boolean ENABLE_COMPACT_SIGN = true;
    private static final float SIGN_RELATIVE_SIZE = 0.70f;
    private static final float SIGN_TARGET_WIDTH_SCALE = 0.82f;
    private static final int SIGN_GAP_DP = 1;

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
    private static final long RECENT_PEAK_HOLD_MS = 30000; // 30s 保持后开始衰减
    private static final float RECENT_DECAY_RATE = 0.02f;  // 每帧衰减率

    // V2.2: Session Extreme 参数分类 — true=全程极值, false=近期动态极值
    private static final boolean[] SHOW_SESSION_EXTREME = {
        true,   // 0 Ethanol — 乙醇含量慢变量
        true,   // 1 ECT — 本次驾驶最高水温
        true,   // 2 IAT — 本次驾驶最高进气温度
        true,   // 3 L.TRIM — 长期燃油修正全程范围
        true,   // 4 MAP — 最大增压/最大真空
        false,  // 5 A/F — 瞬态多, 用 recent
        false,  // 6 IGN — 低负荷极值多, 用 recent
        false   // 7 S.TRIM — 快速动态量, 用 recent
    };

    // V2.3 Display Truth Pass — combustionInvalid / SYNC 门控
    private static final long DFCO_EXIT_IGN_MIN_SYNC_MS = 250L;
    private static final long DFCO_EXIT_AF_MIN_SYNC_MS = 300L;
    private static final long DFCO_EXIT_STRIM_MIN_SYNC_MS = 500L;
    private static final long DFCO_EXIT_MAX_SYNC_MS = 800L;
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

    // V2.6: per-card 字号和最小压缩
    private float getBaseSpForMain(int i) {
        return MAIN_VALUE_SP;
    }

    private float getMinScaleXForMain(int i) {
        // 保留兼容, 但实际使用 getHardMinScaleXForMain
        return getHardMinScaleXForMain(i);
    }

    private float getHardMinScaleXForMain(int i) {
        switch (i) {
            case 0: return 0.28f; // E100
            case 1: return 0.34f; // 120
            case 2: return 0.34f; // 120
            case 3: return 0.22f; // +25.0
            case 4: return 0.26f; // -1.0 / +2.0
            case 5: return 0.28f; // 18.0
            case 6: return 0.22f; // +45.0
            case 7: return 0.22f; // -25.0
            default: return 0.25f;
        }
    }

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

    // 彩虹转速灯条
    private ShiftLightView shiftLight;

    // 温度 & A/F 闪烁控制
    private final Handler flashHandler = new Handler(Looper.getMainLooper());
    private boolean flashVisible = true;
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

    // 引擎状态检测
    private final EngineStateTracker engineState = new EngineStateTracker();

    // 上一次 ScaleBar 输入值 (用于计算 delta 驱动情绪)
    private final float[] lastScaleVal = new float[8];

    // 最后有效值缓存 (DFCO 冻结 + 丢包保留)
    private final float[] lastValidValue = new float[8];
    private final boolean[] hasValidValue = new boolean[8];

    // DFCO 状态跟踪 (用于退出 DFCO 时即时恢复显示)
    private boolean lastDfcoState = false;
    private long dfcoExitTime = 0;  // DFCO 退出时间戳 (用于 History Admission 500ms 窗口)

    // V2.3: combustionInvalid 显示层门控状态
    private boolean lastCombustionInvalid = false;
    private long combustionInvalidExitTimeMs = 0L;
    private boolean lastAfSync = false;
    private boolean lastIgnSync = false;
    private boolean lastStrimSync = false;
    private final Runnable flashTick = new Runnable() {
        @Override
        public void run() {
            flashVisible = !flashVisible;
            // ECT闪烁 (>100)
            if (ectFlashing) {
                int alpha = flashVisible ? 255 : 40;
                int color = 0xFFB040FF;
                valueIntViews[1].setTextColor(color);
                valueIntViews[1].setAlpha(alpha / 255f);
            }
            // IAT闪烁 (>=65)
            if (iatFlashing) {
                int alpha = flashVisible ? 255 : 40;
                int color = 0xFFB040FF;
                valueIntViews[2].setTextColor(color);
                valueIntViews[2].setAlpha(alpha / 255f);
            }
            // A/F闪烁 — V2.6.3: 语义模式下硬保护, 禁止闪烁残留
            if (semanticMode[5]) {
                if (afFlashing) {
                    afFlashing = false;
                    updateFlashState();
                }
                valueIntViews[5].setAlpha(ALPHA_SEMANTIC_SYNC);
                valueIntViews[5].setTextColor(COLOR_SEMANTIC_SYNC);
            } else if (afFlashing) {
                int alpha = flashVisible ? 255 : 40;
                int color = 0xFFFF4444;
                valueIntViews[5].setTextColor(color);
                valueIntViews[5].setAlpha(alpha / 255f);
            }
            // K.C闪烁 (>65)
            if (kcFlashing && knockRetValue != null) {
                int alpha = flashVisible ? 255 : 40;
                knockRetValue.setTextColor(0xFFFF4444);
                knockRetValue.setAlpha(alpha / 255f);
            }
            // F.P闪烁 (油压不足)
            if (fpFlashing && bottomFpValue != null) {
                int alpha = flashVisible ? 255 : 40;
                bottomFpValue.setTextColor(0xFFFF4444);
                bottomFpValue.setAlpha(alpha / 255f);
            }
            // CYL红色闪烁 (快速累积>+10)
            if (cylRedFlashing) {
                int alpha = flashVisible ? 255 : 40;
                for (int j = 0; j < 4; j++) {
                    if (knockValues[j] != null) {
                        knockValues[j].setTextColor(0xFFFF4444);
                        knockValues[j].setAlpha(alpha / 255f);
                    }
                }
            }
            if (ectFlashing || iatFlashing || afFlashing || kcFlashing || fpFlashing || cylRedFlashing) {
                flashHandler.postDelayed(this, 500); // 1Hz闪烁
            }
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
    private static final int COLOR_TEXT_NORMAL = 0xFFFFFFFF;
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
        "Ethanol", "ECT", "IAT", "L.TRIM",
        "MAP", "A/F", "IGN", "S.TRIM"
    };

    private static final String[] CARD_FMT = {
        "%.0f", "%.0f", "%.0f", "%+.1f",
        "%+.1f", "%.1f", "%+.1f", "%+.1f"
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

        statusText = (TextView) findViewById(R.id.statusText);
        sourceName = (TextView) findViewById(R.id.sourceName);

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
            valueAreaViews[i] = card.findViewById(R.id.valueArea);
            extremePanelViews[i] = card.findViewById(R.id.extremePanel);

            if (labelEnViews[i] != null) labelEnViews[i].setText(CARD_EN[i]);
            if (unitViews[i] != null) {
                String u = CARD_UNIT[i];
                unitViews[i].setText(u.isEmpty() ? "" : "(" + u + ")");
            }

            if (valueDecViews[i] != null) {
                valueDecViews[i].setVisibility(View.GONE);
                valueDecViews[i].setText("");
            }

            if (valueIntViews[i] != null) {
                valueIntViews[i].setSingleLine(true);
                valueIntViews[i].setEllipsize(null);
                valueIntViews[i].setIncludeFontPadding(false);
                valueIntViews[i].setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                valueIntViews[i].setTextScaleX(1f);
            }

            if (maxValueViews[i] != null) {
                maxValueViews[i].setSingleLine(true);
                maxValueViews[i].setEllipsize(null);
                maxValueViews[i].setIncludeFontPadding(false);
                maxValueViews[i].setGravity(Gravity.END);
                maxValueViews[i].setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
            }

            if (minValueViews[i] != null) {
                minValueViews[i].setSingleLine(true);
                minValueViews[i].setEllipsize(null);
                minValueViews[i].setIncludeFontPadding(false);
                minValueViews[i].setGravity(Gravity.END);
                minValueViews[i].setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
            }

            configureScaleBar(i);
        }

        // 初始化底部: 爆震控制值
        knockRetValue = (TextView) findViewById(R.id.knockRetValue);

        // 初始化 4 个爆震缸
        int[] knockIds = {R.id.knock0, R.id.knock1, R.id.knock2, R.id.knock3};
        String[] knockLabels = {"CYL 1", "CYL 2", "CYL 3", "CYL 4"};

        for (int i = 0; i < knockIds.length; i++) {
            View k = findViewById(knockIds[i]);
            knockValues[i] = (TextView) k.findViewById(R.id.knockValue);

            TextView kl = (TextView) k.findViewById(R.id.knockLabel);
            if (kl != null) kl.setText(knockLabels[i]);
        }

        // 彩虹转速灯条
        shiftLight = (ShiftLightView) findViewById(R.id.shiftLight);

        // 底部数据文本
        bottomTrimValue = (TextView) findViewById(R.id.bottomTrimValue);
        bottomAfmValue = (TextView) findViewById(R.id.bottomAfmValue);
        bottomBatValue = (TextView) findViewById(R.id.bottomBatValue);
        bottomFpValue = (TextView) findViewById(R.id.bottomFpValue);
        bottomWgValue = (TextView) findViewById(R.id.bottomWgValue);
        bottomTpValue = (TextView) findViewById(R.id.bottomTpValue);

        // 数据源
        if (USE_DEMO) {
            dataSource = new DemoSource();
        } else {
            BluetoothSource btSource = new BluetoothSource();
            btSource.setExportContext(this);
            dataSource = btSource;
        }
        dataSource.setCallback(this);
        sourceName.setText(dataSource.getName());

        if (statusText != null) {
            statusText.setText("CONNECTING");
            statusText.setTextColor(0xFFD29922);
        }

        freshnessHandler.post(freshnessRunnable);

        // V2.6.7: 直接连接，无自检
        if (USE_DEMO) {
            dataSource.connect(null);
        } else {
            connectBluetooth();
        }
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
    private void configureScaleBar(int i) {
        ScaleBarView bar = scaleBars[i];
        if (bar == null) return;

        switch (i) {
            case 0: // Ethanol: STATIC — 锁定态, 无能量系统
                bar.setRange(0, 100);
                bar.setTicks(
                    new float[]{0, 20, 40, 60, 100},
                    new String[]{"0", "20", "40", "60", "100"});
                bar.addZone(0, 20, 0xFF888888);
                bar.addZone(20, 40, 0xFF3FB950);
                bar.addZone(40, 60, 0xFFD29922);
                bar.addZone(60, 100, 0xFFFF4444);
                bar.setAnchor(0);
                bar.setExpand(0, 40, 2.0f);
                bar.setStatic();
                break;

            case 1: // ECT: THERMAL — 牛顿冷却, 热积累/非线性散热
                bar.setRange(40, 120);
                bar.setTicks(
                    new float[]{40, 60, 80, 100, 120},
                    new String[]{"40", "60", "80", "100", "120"});
                bar.addZone(40, 100, 0xFF00D8FF);
                bar.addZone(100, 120, 0xFFFF4444);
                bar.setAnchor(40);
                // gain=0.5 吸热, cooling=0.3 散热慢, memory=0.2 峰值漂移慢
                bar.setThermal(0.5f, 0.3f, 0.2f);
                break;

            case 2: // IAT: THERMAL — 热浸, 散热比ECT略快
                bar.setRange(20, 100);
                bar.setTicks(
                    new float[]{20, 40, 60, 80, 100},
                    new String[]{"20", "40", "60", "80", "100"});
                bar.addZone(20, 100, 0xFF00D8FF);
                bar.setAnchor(20);
                bar.setThermal(0.6f, 0.4f, 0.25f);
                break;

            case 3: // L.TRIM: THERMAL — ECU学习值, 极慢热容
                bar.setRange(-25, 25);
                bar.setTicks(
                    new float[]{-25, -12.5f, 0, 12.5f, 25},
                    new String[]{"-25", "", "0", "", "25"});
                // V2.7.0: 语义色区 — 按偏离 0 的绝对值
                bar.addZone(-25, -15, COLOR_DANGER);
                bar.addZone(-15, -5, COLOR_WARN);
                bar.addZone(-5, 5, COLOR_SAFE);
                bar.addZone(5, 15, COLOR_WARN);
                bar.addZone(15, 25, COLOR_DANGER);
                bar.setAnchor(0);
                bar.setThermal(0.2f, 0.1f, 0.08f);  // 极慢吸热, 极慢散热, 极慢漂移
                break;

            case 4: // Boost: MECHANICAL — Spring-Damper + Peak Hold
                bar.setRange(-1.0f, 2.0f);
                bar.setTicks(
                    new float[]{-1.0f, 0, 0.5f, 1.0f, 1.5f, 2.0f},
                    new String[]{"-1.0", "0", "0.5", "1.0", null, "2.0"});
                // V2.7.0: 语义色区 — 真空蓝色, 正压按相对增压 bar
                bar.addZone(-1.0f, 0, COLOR_INFO_BLUE);
                bar.addZone(0, MAP_GREEN_MAX, COLOR_SAFE);
                bar.addZone(MAP_GREEN_MAX, MAP_WARN_MAX, COLOR_WARN);
                bar.addZone(MAP_WARN_MAX, 2.0f, COLOR_DANGER);
                bar.setAnchor(0);
                bar.setExpand(0, 1.5f, 2.0f);
                // stiffness=5 中速建压, damping=0.45 欠阻尼, peakRetention=0.70 半衰期~3s
                bar.setMechanical(5.0f, 0.45f, 0.70f);
                break;

            case 5: // A/F: TRANSIENT — Oscillation Envelope, 燃烧事件
                bar.setRange(9, 18);
                bar.setTicks(
                    new float[]{9, 11, 14.5f, 15.5f, 18},
                    new String[]{"9", "11", "14.5", "15.5", "18"});
                bar.addZone(9, 11, 0xFFFF4444);
                bar.addZone(11, 14.5f, 0xFFD29922);
                bar.addZone(14.5f, 15.5f, 0xFF3FB950);
                bar.addZone(15.5f, 18, 0xFFFF4444);
                bar.setAnchor(14.7f);
                bar.setExpand(14.5f, 15.5f, 2.5f);
                // decay=0.80 慢衰减 (半衰期~3.1s, 负值方向同步改善)
                bar.setTransient(3.0f, 0.80f);
                break;

            case 6: // IGN: MECHANICAL — Spring-Damper + Peak Hold, 机械联动
                bar.setRange(-40, 40);
                bar.setTicks(
                    new float[]{-40, -20, 0, 20, 40},
                    new String[]{"-40", "-20", "0", "20", "40"});
                bar.addZone(-40, 40, 0xFF00D8FF);
                bar.setAnchor(0);
                // stiffness=2.5 软弹簧, damping=0.40 欠阻尼, peakRetention=0.85 半衰期~4.6s
                bar.setMechanical(2.5f, 0.40f, 0.85f);
                break;

            case 7: // S.TRIM: TRANSIENT — Oscillation Envelope, 修正振荡
                bar.setRange(-25, 25);
                bar.setTicks(
                    new float[]{-25, 0, 25},
                    new String[]{"-25", "0", "25"});
                // V2.7.0: 语义色区 — 按偏离 0 的绝对值
                bar.addZone(-25, -15, COLOR_DANGER);
                bar.addZone(-15, -5, COLOR_WARN);
                bar.addZone(-5, 5, COLOR_SAFE);
                bar.addZone(5, 15, COLOR_WARN);
                bar.addZone(15, 25, COLOR_DANGER);
                bar.setAnchor(0);
                // gain=2.5 高敏感, decay=0.65 慢衰减 (半衰期~5.3s)
                bar.setTransient(2.5f, 0.65f);
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
        if (dataSource.isConnected()) {
            dataSource.startPolling();
        } else if (!USE_DEMO) {
            // V2.6.9 (P0-2 配套): 后台期间蓝牙可能被系统断开, 回前台时若已断开则重新连接
            // 轻量方案: 不用 BroadcastReceiver, 只在 onResume 兜底一次
            // BluetoothSource.connect 内部有重连仲裁, 不会重复连接
            dataSource.connect(null);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        dataSource.stopPolling();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        freshnessHandler.removeCallbacks(freshnessRunnable);
        // V2.6.8 (BG3): 清理 flashHandler, 防止 flashTick 持有 Activity 引用泄漏
        flashHandler.removeCallbacks(flashTick);
        // V2.6.9 (P2-3): 先解绑 callback, 防止 disconnect 异步回调到已销毁的 Activity
        dataSource.setCallback(null);
        dataSource.disconnect();
    }

    // ===== DataSource.Callback =====

    @Override
    public void onConnected() {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                statusText.setText("已连接");
                statusText.setTextColor(0xFF3FB950);
                dataSource.startPolling();
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

        runOnUiThread(new Runnable() {
            @Override public void run() {
                statusText.setText("BT LOST");
                statusText.setTextColor(0xFFFF4444);
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
        {8, 25},        // 5: A/F ratio
        {-25, 55},      // 6: IGN °
        {-30, 30},      // 7: S.TRIM %
    };

    @Override
    public void onDataReceived(final SensorData data) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                // V2.2: 数据新鲜度追踪
                lastValidFrameTimeMs = SystemClock.elapsedRealtime();

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

                // 检测引擎状态 (V2 语义模型)
                EngineSemanticState state = engineState.update(data);
                // V2.6.4: 更新发动机运转门控
                updateEngineRunningGate(data);
                // V2.6.6: 更新发动机极值 session
                updateEngineExtremeSession(data);
                boolean isDfco = state.isDfco();
                boolean dfcoJustEnded = lastDfcoState && !isDfco;
                lastDfcoState = isDfco;
                // V2.6.8 (BG7): 改用 elapsedRealtime, 避免 NTP/手动改时间导致 DFCO 窗口失准
                if (dfcoJustEnded) dfcoExitTime = SystemClock.elapsedRealtime();

                // V2.3: combustionInvalid 显示层门控
                // V2.6.8 (BG6): 与状态机对齐 — state.isDfco() 时强制 combustionInvalid=true
                // 避免 isCombustionInvalid 阈值 (speed>5||rpm>1400) 与 DFCO 状态机 (speed>15&&rpm>1400)
                // 不一致导致显示层与状态机层语义割裂
                boolean combustionInvalid = isCombustionInvalid(data) || state.isDfco();
                updateCombustionInvalidTransition(combustionInvalid);
                long sinceDfcoExitMs = combustionInvalidExitTimeMs > 0L
                    ? SystemClock.elapsedRealtime() - combustionInvalidExitTimeMs
                    : Long.MAX_VALUE;
                boolean ignSync = shouldSyncIgnAfterDfco(data, combustionInvalid, sinceDfcoExitMs);
                boolean afSync = shouldSyncAfAfterDfco(data, combustionInvalid, sinceDfcoExitMs);
                boolean strimSync = shouldSyncStrimAfterDfco(data, combustionInvalid, sinceDfcoExitMs);
                lastIgnSync = ignSync;
                lastAfSync = afSync;
                lastStrimSync = strimSync;

                // V2.6.8 (BG7): 改用 elapsedRealtime, 与 dfcoExitTime/recentMaxTime 等时钟源统一
                // 避免 NTP/手动改时间导致极值计时和 DFCO 窗口失准
                long now = SystemClock.elapsedRealtime();

                // 更新 8 个主卡片
                for (int i = 0; i < 8; i++) {
                    if (valueIntViews[i] == null) continue;

                    int pid = CARD_PIDS[i];
                    String fmt = CARD_FMT[i];
                    Double raw = data.get(pid);

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

                        // V2.6.3: A/F / IGN / S.TRIM 语义门控必须早于 VALID_RANGE
                        // A/F 在 DFCO 中可能显示为 29.4 AFR, 不能先走 invalid alpha=0.4
                        if (i == 5) { // A/F
                            if (combustionInvalid) {
                                clearAfSemanticContamination();
                                renderSemanticCard(i, "DFCO");
                                continue;
                            }
                            if (afSync) {
                                clearAfSemanticContamination();
                                renderSemanticCard(i, "SYNC");
                                continue;
                            }
                        }
                        if (i == 6) { // IGN
                            if (combustionInvalid) {
                                renderSemanticCard(i, "DFCO");
                                continue;
                            }
                            if (ignSync) {
                                renderSemanticCard(i, "SYNC");
                                continue;
                            }
                        }
                        if (i == 7) { // S.TRIM
                            if (combustionInvalid) {
                                renderSemanticCard(i, "DFCO");
                                continue;
                            }
                            if (strimSync || !isStrimDisplayValid(data, combustionInvalid, strimSync)) {
                                renderSemanticCard(i, "SYNC");
                                continue;
                            }
                        }

                        // 非语义状态才做范围校验
                        float[] range = VALID_RANGE[i];
                        if (fVal < range[0] || fVal > range[1]) {
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

                        // DFCO 退出即时恢复: 重置滤波器, 踩油门立刻显示正确值
                        // V1.4: Boost 也重置 (DFCO 期间 release=0.02 极慢, 退出后需立即追上)
                        if (dfcoJustEnded) {
                            hasFiltered[i] = false;
                            lastUpdateTime[i] = 0; // 跳过 Rate Limit, 立即刷新显示
                        }

                        // 自适应参数: V2 置信度驱动连续调制
                        float effectiveAlpha = EMA_ALPHA[i];
                        long effectiveInterval = UPDATE_INTERVAL[i];
                        boolean isWot = state.isWot();
                        boolean needFast = isWot || state.hasModifier();
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

                        // 缓存有效值
                        lastValidValue[i] = fVal;
                        hasValidValue[i] = true;

                        // V2.6.6: Ethanol 连接后爬升门控
                        if (i == 0) {
                            updateEthanolSettlingGate(fVal);
                        }

                        // History Admission System: V2.6.7 通电临时极值 + 发动机稳定后一次性 baseline 覆盖
                        float valueForExtreme = (i == 4) ? rawValueForExtreme : fVal;

                        boolean canRecordExtreme = canRecordExtremeNow(i);

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
                                // Session Extreme 路径: 只做可信值过滤, 无 cooldown/decay
                                if (isTrustedForSessionExtreme(i, valueForExtreme)) {
                                    if (valueForExtreme > maxTrack[i]) maxTrack[i] = valueForExtreme;
                                    if (valueForExtreme < minTrack[i]) minTrack[i] = valueForExtreme;
                                }
                                // recent 数组也保持同步
                                if (fVal > recentMax[i]) { recentMax[i] = fVal; recentMaxTime[i] = now; }
                                if (fVal < recentMin[i]) { recentMin[i] = fVal; recentMinTime[i] = now; }
                            } else if (!baselineAppliedNow && isEligibleForHistory(i, state)) {
                                // Recent Extreme 路径: 三层准入 + cooldown + breakthrough + decay
                                boolean maxExpired = (now - lastMaxTime[i] >= getCooldown(i, true, isWot));
                                boolean minExpired = (now - lastMinTime[i] >= getCooldown(i, false, isWot));

                                if (fVal > maxTrack[i]) {
                                    if (maxExpired || isBreakthrough(i, true, fVal, maxTrack[i])) {
                                        maxTrack[i] = fVal;
                                        recentMax[i] = fVal;
                                        lastMaxTime[i] = now;
                                        recentMaxTime[i] = now;
                                    }
                                }
                                if (fVal < minTrack[i]) {
                                    if (minExpired || isBreakthrough(i, false, fVal, minTrack[i])) {
                                        minTrack[i] = fVal;
                                        recentMin[i] = fVal;
                                        lastMinTime[i] = now;
                                        recentMinTime[i] = now;
                                    }
                                }
                                // Recent Peak 30s 衰减 (每帧)
                                updateRecentPeak(i, fVal, now);
                            }
                        }

                        if (updateDisplay) {
                            lastUpdateTime[i] = now;

                            // V2.5: 单 TextView 完整字符串 + overlay 语义恢复
                            String mainText = formatMainText(i, fVal);
                            renderMainText(i, mainText);
                            // 默认白色, 后面各卡片按条件覆盖颜色
                            valueIntViews[i].setTextColor(COLOR_TEXT_NORMAL);
                            valueIntViews[i].setAlpha(1f);

                            if (scaleBars[i] != null) {
                                // 更新情绪状态 (在 setValue 之前, setValue 内部会渐变)
                                updateEmotion(i, fVal, state);
                                scaleBars[i].setValue(fVal);
                            }

                            if (maxValueViews[i] != null) {
                                if (hasValue[i]) {
                                    float displayMax = SHOW_SESSION_EXTREME[i] ? maxTrack[i] : recentMax[i];
                                    String maxText = formatExtremeText(i, displayMax);
                                    renderExtremeText(maxValueViews[i], maxText);
                                    maxValueViews[i].setTextColor(0xFFCCCCCC);
                                } else {
                                    renderExtremeText(maxValueViews[i], "");
                                }
                            }
                            if (minValueViews[i] != null) {
                                if (hasValue[i]) {
                                    float displayMin = SHOW_SESSION_EXTREME[i] ? minTrack[i] : recentMin[i];
                                    String minText = formatExtremeText(i, displayMin);
                                    renderExtremeText(minValueViews[i], minText);
                                    minValueViews[i].setTextColor(0xFFCCCCCC);
                                } else {
                                    renderExtremeText(minValueViews[i], "");
                                }
                            }

                            // Ethanol: 按浓度变色 (E前缀已由 fitSplitValueText 设置)
                            if (i == 0) {
                                int ethColor;
                                if (fVal < 20) ethColor = 0xFFFFFFFF;       // 白色
                                else if (fVal < 40) ethColor = 0xFF3FB950;  // 绿色
                                else if (fVal < 60) ethColor = 0xFFD29922;  // 黄色
                                else ethColor = 0xFFFF4444;                  // 红色
                                valueIntViews[i].setTextColor(ethColor);
                            }

                            // ECT: 温度变色 <80蓝 80~95白 96~100红 >100紫闪烁
                            if (i == 1) {
                                lastEctVal = fVal;
                                boolean shouldFlash = fVal > 100;
                                if (shouldFlash != ectFlashing) {
                                    ectFlashing = shouldFlash;
                                    updateFlashState();
                                }
                                if (!ectFlashing) {
                                    int color;
                                    if (fVal < 80) {
                                        color = 0xFF00D8FF; // 蓝色
                                    } else if (fVal <= 95) {
                                        color = 0xFFFFFFFF; // 白色
                                    } else {
                                        color = 0xFFFF4444; // 红色 (96~100)
                                    }
                                    valueIntViews[i].setTextColor(color);
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
                                    int color;
                                    if (fVal < 35) {
                                        color = 0xFF3FB950; // 绿色
                                    } else if (fVal < 45) {
                                        color = 0xFFFFFFFF; // 白色
                                    } else if (fVal < 55) {
                                        color = 0xFFD29922; // 黄色
                                    } else {
                                        color = 0xFFFF4444; // 红色 (55~64)
                                    }
                                    valueIntViews[i].setTextColor(color);
                                    valueIntViews[i].setAlpha(1f);
                                }
                            }

                            // A/F: V2.3 Lambda 语义报警 — 使用 measured/target lambda 而非固定 AFR 阈值
                            if (i == 5) {
                                lastAfVal = fVal;
                                float measuredLambda = (float) data.getDouble(0x0320);
                                float targetLambda = (float) data.getDouble(0x0322);

                                // 闪烁判定: 只有 WOT 过稀才红闪, DFCO/SYNC 已被前置门控拦截
                                boolean shouldFlash = false;
                                if (!combustionInvalid && !afSync) {
                                    if (isWot && !Double.isNaN(data.getDouble(0x0320))) {
                                        shouldFlash = measuredLambda > WOT_LAMBDA_DANGER_LEAN;
                                    }
                                }
                                if (shouldFlash != afFlashing) {
                                    afFlashing = shouldFlash;
                                    updateFlashState();
                                }

                                // 颜色判定: lambda 语义
                                if (!afFlashing) {
                                    int color = getAfColorByLambda(measuredLambda, targetLambda, state);
                                    valueIntViews[i].setTextColor(color);
                                    valueIntViews[i].setAlpha(1f);
                                }
                            }

                            // V2.7.0: L.TRIM / MAP / IGN / S.TRIM 主数据语义颜色
                            // 必须在 DFCO/SYNC 前置门控之后, 且在低置信度灰显之前
                            applyMainValueSemanticColor(i, fVal);

                            // V2.6.7: A/F / IGN / S.TRIM 低置信度灰色模式
                            applyConfidenceVisual(i, state, data);

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

                // 更新爆震控制值 (Knock Control %)
                Double kc = data.get(KNOCK_CTRL_PID);
                if (kc != null && knockRetValue != null) {
                    int pct = kc.intValue();
                    knockRetValue.setText(String.valueOf(pct));

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
                        knockValues[i].setText(String.valueOf(knock));

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
                        knockValues[i].setText("--");
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
                    bottomTrimValue.setText(String.format(Locale.US, "%.1f", kr));
                }

                // Knock Limit (PID 0x411)
                Double kl = data.get(0x411);
                if (kl != null && bottomAfmValue != null) {
                    bottomAfmValue.setText(String.format(Locale.US, "%.1f", kl));
                }

                // BAT 电压 (PID 0x180)
                Double bat = data.get(BAT_PID);
                if (bat != null && bottomBatValue != null) {
                    bottomBatValue.setText(String.format(Locale.US, "%.1f", bat));
                }

                // F.P Fuel Pressure (PID 0x191 kPa → bar) vs 目标 (0x190)
                Double fp = data.get(FP_PID);
                if (fp != null && bottomFpValue != null) {
                    bottomFpValue.setText(String.format(Locale.US, "%.1f", fp / 100.0));

                    Double fpTarget = data.get(FP_TARGET_PID);
                    boolean wasFpFlash = fpFlashing;
                    if (fpTarget != null && fpTarget > 0) {
                        // 实际油压低于目标10%以上视为不足
                        fpFlashing = fp < fpTarget * 0.90;
                    } else {
                        // 无目标值时, 油压低于200kPa(2.0bar)视为异常
                        fpFlashing = fp < 200;
                    }
                    if (fpFlashing != wasFpFlash) updateFlashState();
                    if (!fpFlashing) {
                        bottomFpValue.setTextColor(0xFFFFFFFF);
                        bottomFpValue.setAlpha(1f);
                    }
                }

                // W.G Wastegate (PID 0x1A0 %)
                Double wg = data.get(WG_PID);
                if (wg != null && bottomWgValue != null) {
                    bottomWgValue.setText(String.format(Locale.US, "%.0f", wg));
                }

                // T.P Throttle Plate (PID 0x122 %)
                Double tp = data.get(TP_PID);
                if (tp != null) {
                    lastTpPlate = tp.floatValue();
                    if (bottomTpValue != null) {
                        bottomTpValue.setText(String.format(Locale.US, "%.0f", tp));
                    }
                }

                // 更新彩虹转速灯条
                if (shiftLight != null) {
                    Double rpmVal = data.get(0x100);
                    if (rpmVal != null) {
                        shiftLight.setRpm(rpmVal.floatValue());
                    }
                }
            }
        });
    }

    /** V2.2: 数据新鲜度状态更新 (由独立 Handler 每 250ms 调用) */
    private void updateFreshnessStatus() {
        if (lastValidFrameTimeMs <= 0) return;
        long age = SystemClock.elapsedRealtime() - lastValidFrameTimeMs;
        if (age < 500) {
            statusText.setText("LIVE");
            statusText.setTextColor(0xFF3FB950);
        } else if (age < 1500) {
            statusText.setText("STALE");
            statusText.setTextColor(0xFFD29922);
        } else if (age < 3000) {
            statusText.setText("DATA LOST");
            statusText.setTextColor(0xFFFF4444);
        } else {
            statusText.setText("BT LOST");
            statusText.setTextColor(0xFFFF4444);
        }
    }

    /**
     * 管理温度闪烁定时器的启停。
     */
    private void updateFlashState() {
        flashHandler.removeCallbacks(flashTick);
        if (ectFlashing || iatFlashing || afFlashing || kcFlashing || fpFlashing || cylRedFlashing) {
            flashVisible = true;
            flashHandler.post(flashTick);
        }
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

    // === History Admission System ===

    /** Semantic Admission: 当前工况下该参数是否有分析意义 */
    private boolean isEligibleForHistory(int i, EngineSemanticState state) {
        boolean isDfco = state.isDfco();
        boolean isWarmup = state.isWarmup();
        boolean isSpool = state.sub == EngineSemanticState.SubState.SPOOL;
        boolean inDfcoRecovery = (dfcoExitTime > 0)
            && (SystemClock.elapsedRealtime() - dfcoExitTime < 500);

        switch (i) {
            case 0: // Ethanol — 配置参数, 永远有效
            case 1: // ECT — 热参数, 所有温度都有意义
            case 2: // IAT — 同上
            case 4: // Boost — 正压负压都有意义
                return true;
            case 3: // L.TRIM — 只记闭环稳态修正
                return !isDfco && !isWarmup && !inDfcoRecovery;
            case 5: // A/F — 只记有效燃烧 AFR
                return !isDfco && !isWarmup && !isSpool && !inDfcoRecovery;
            case 6: // IGN — 只记正常温度下的点火角
                return !isDfco && !isWarmup;
            case 7: // S.TRIM — 只记有效反馈修正
                return !isDfco && !isWarmup && !isSpool && !inDfcoRecovery;
            default:
                return true;
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

    /** Recent Peak 30s 自动衰减: 超过保持时间后指数回归当前值 */
    private void updateRecentPeak(int i, float currentVal, long now) {
        if (now - recentMaxTime[i] > RECENT_PEAK_HOLD_MS) {
            float diff = recentMax[i] - currentVal;
            if (diff > 0.01f) {
                recentMax[i] -= diff * RECENT_DECAY_RATE;
            } else {
                recentMax[i] = currentVal;
            }
        }
        if (now - recentMinTime[i] > RECENT_PEAK_HOLD_MS) {
            float diff = currentVal - recentMin[i];
            if (diff > 0.01f) {
                recentMin[i] += diff * RECENT_DECAY_RATE;
            } else {
                recentMin[i] = currentVal;
            }
        }
    }

    // === V2.3 Display Truth Pass: combustionInvalid + SYNC ===

    /**
     * 显示层 DFCO / overrun fuel cut 检测。
     * 不依赖单一 TPlate < 2 阈值，使用多源语义：INJ=0 + lambda/target 极稀 + Open Loop + 低负荷。
     * PID 0x0340 = ClosedLoop (0=open loop, 1=closed loop)
     */
    private boolean isCombustionInvalid(SensorData data) {
        float rpm = (float) data.getDouble(0x0100);
        float speed = (float) data.getDouble(0x0101);
        float map = (float) data.getDouble(0x0110);
        float pedal = (float) data.getDouble(0x0120);
        float tp = (float) data.getDouble(0x0122);
        float inj = (float) data.getDouble(0x0130);
        float lambda = (float) data.getDouble(0x0320);
        float target = (float) data.getDouble(0x0322);
        float closedLoop = (float) data.getDouble(0x0340);

        boolean fuelCut = inj <= 0.05f;
        boolean overrunTarget = target >= 1.80f || lambda >= 1.80f;
        boolean openLoopDriving = closedLoop < 0.5f;
        boolean closedThrottleLike = pedal <= 0.5f || tp <= 3.0f || map < 35.0f;
        // V2.6.8 (M2): 与 DFCO 状态机 RPM_DFCO_MIN=1400 对齐
        boolean movingOrHighRpm = speed > 5.0f || rpm > 1400.0f;

        return fuelCut && overrunTarget && openLoopDriving && closedThrottleLike && movingOrHighRpm;
    }

    /** 记录 combustionInvalid → valid 的退出时刻 */
    private void updateCombustionInvalidTransition(boolean combustionInvalid) {
        long now = SystemClock.elapsedRealtime();
        if (lastCombustionInvalid && !combustionInvalid) {
            combustionInvalidExitTimeMs = now;
        }
        lastCombustionInvalid = combustionInvalid;
    }

    /** IGN SYNC: 喷油恢复即可较早释放, 最短 250ms */
    private boolean shouldSyncIgnAfterDfco(SensorData data, boolean combustionInvalid, long sinceExitMs) {
        if (combustionInvalid) return true;
        if (sinceExitMs < DFCO_EXIT_IGN_MIN_SYNC_MS) return true;

        float inj = (float) data.getDouble(0x0130);
        float closedLoop = (float) data.getDouble(0x0340);
        return inj <= 0.20f || closedLoop < 0.5f;
    }

    /** A/F SYNC: 等待 lambda 脱离极稀状态, 最短 300ms, 最长 800ms */
    private boolean shouldSyncAfAfterDfco(SensorData data, boolean combustionInvalid, long sinceExitMs) {
        if (combustionInvalid) return true;
        if (sinceExitMs < DFCO_EXIT_AF_MIN_SYNC_MS) return true;

        float inj = (float) data.getDouble(0x0130);
        float lambda = (float) data.getDouble(0x0320);
        float target = (float) data.getDouble(0x0322);
        float closedLoop = (float) data.getDouble(0x0340);

        boolean lambdaStillInvalid = lambda > 1.35f || target > 1.30f;
        boolean fuelStillInvalid = inj <= 0.20f || closedLoop < 0.5f;

        if (sinceExitMs > DFCO_EXIT_MAX_SYNC_MS) {
            return lambdaStillInvalid && fuelStillInvalid;
        }
        return lambdaStillInvalid || fuelStillInvalid;
    }

    /** S.TRIM SYNC: 最保守, 等 closed loop 稳定, 最短 500ms */
    private boolean shouldSyncStrimAfterDfco(SensorData data, boolean combustionInvalid, long sinceExitMs) {
        if (combustionInvalid) return true;
        if (sinceExitMs < DFCO_EXIT_STRIM_MIN_SYNC_MS) return true;
        return !isStrimDisplayValid(data, combustionInvalid, false);
    }

    /** S.TRIM 只在 closed loop + stoich target + injector active 时显示真实值 */
    private boolean isStrimDisplayValid(SensorData data, boolean combustionInvalid, boolean strimSync) {
        if (combustionInvalid || strimSync) return false;

        float closedLoop = (float) data.getDouble(0x0340);
        float target = (float) data.getDouble(0x0322);
        float inj = (float) data.getDouble(0x0130);

        boolean isClosedLoop = closedLoop > 0.5f;
        boolean stoichTarget = target > 0.90f && target < 1.10f;
        boolean injectorActive = inj > 0.30f;

        return isClosedLoop && stoichTarget && injectorActive;
    }

    // === V2.4: Display Fit — 工具函数 ===

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private float spToPx(float sp) {
        return sp * getResources().getDisplayMetrics().scaledDensity;
    }

    /** V2.6.2: 独立测量 — 清除上一帧 textScaleX 污染 */
    private float measureTextUnscaled(TextView tv, TextPaint paint, String text, float sp) {
        if (tv == null || text == null) return 0f;

        paint.set(tv.getPaint());
        paint.setTextScaleX(1.0f);      // 关键: 清除上一帧横向缩放
        paint.setTextSize(spToPx(sp));  // 关键: 使用本次准备显示的字号

        return paint.measureText(text);
    }

    // ===== V2.6: 轻量格式函数 =====
    private String fmt1NoSign(float v) {
        int x = Math.round(v * 10f);
        int abs = Math.abs(x);
        return (x < 0 ? "-" : "") + (abs / 10) + "." + (abs % 10);
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
            case 5: return fmt1NoSign(v);   // A/F
            default: return fmtSigned1(v);  // L.TRIM, MAP, IGN, S.TRIM
        }
    }

    /** V2.6: 格式化极值 */
    private String formatExtremeText(int i, float v) {
        if (i < 3) return String.valueOf(Math.round(v));
        if (i == 5) return fmt1NoSign(v);
        return fmtSigned1(v);
    }

    /** V2.6.3: 主数据 scaleX 快收慢放 — 变窄立即, 变宽阻尼 */
    private float smoothMainScaleX(int i, float targetScaleX) {
        float current = displayedMainScaleX[i];

        if (current <= 0f) {
            displayedMainScaleX[i] = targetScaleX;
            return targetScaleX;
        }

        // 文字变长需要更窄: 立即响应, 防止裁切
        if (targetScaleX < current) {
            displayedMainScaleX[i] = targetScaleX;
            return targetScaleX;
        }

        // 文字变短可以更宽: 缓慢放大
        float diff = targetScaleX - current;
        if (diff < 0.012f) {
            displayedMainScaleX[i] = targetScaleX;
            return targetScaleX;
        }

        float next = current + diff * 0.22f;
        displayedMainScaleX[i] = next;
        return next;
    }

    /** V2.6.4: 固定尺寸符号 Span — 不受 TextView textScaleX 影响 */
    private static class FixedSignSpan extends ReplacementSpan {
        private final float signTextSizePx;
        private final float targetSignWidthPx;
        private final float gapPx;

        FixedSignSpan(float signTextSizePx, float targetSignWidthPx, float gapPx) {
            this.signTextSizePx = signTextSizePx;
            this.targetSignWidthPx = targetSignWidthPx;
            this.gapPx = gapPx;
        }

        private float getScaleForChar(Paint paint, CharSequence text, int start, int end) {
            float oldSize = paint.getTextSize();
            float oldScaleX = paint.getTextScaleX();

            paint.setTextSize(signTextSizePx);
            paint.setTextScaleX(1.0f);

            float rawWidth = paint.measureText(text, start, end);

            paint.setTextSize(oldSize);
            paint.setTextScaleX(oldScaleX);

            if (rawWidth <= 0f) {
                return 1.0f;
            }

            return targetSignWidthPx / rawWidth;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            // 无论 '+' 还是 '-'，占位宽度完全一致
            return Math.round(targetSignWidthPx + gapPx);
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end,
                         float x, int top, int y, int bottom, Paint paint) {
            float oldSize = paint.getTextSize();
            float oldScaleX = paint.getTextScaleX();

            float signScaleX = getScaleForChar(paint, text, start, end);

            paint.setTextSize(signTextSizePx);
            paint.setTextScaleX(signScaleX);

            canvas.drawText(text, start, end, x, y, paint);

            paint.setTextSize(oldSize);
            paint.setTextScaleX(oldScaleX);
        }
    }

    /** V2.6.3: 是否为带符号主卡片 (L.TRIM, MAP, IGN, S.TRIM) */
    private boolean isSignedMainCard(int i) {
        return i == 3 || i == 4 || i == 6 || i == 7;
    }

    /** V2.6.5: 测量符号目标宽度 — 以 '-' 为基准 */
    private float measureFixedSignTargetWidth(float baseSp) {
        TextPaint p = mainMeasurePaint;

        p.setTextSize(spToPx(baseSp * SIGN_RELATIVE_SIZE));
        p.setTextScaleX(1.0f);

        // 以 '-' 的原始宽度为基准，让 '+' 压缩到这个宽度
        float minusRaw = p.measureText("-");

        return minusRaw * SIGN_TARGET_WIDTH_SCALE;
    }

    /** V2.6.5: 构建 +/- 固定等宽 SpannableString */
    private CharSequence buildMainDisplayText(int i, String plainText) {
        if (!ENABLE_COMPACT_SIGN) return plainText;
        if (plainText == null || plainText.length() == 0) return "";

        char c = plainText.charAt(0);

        if (isSignedMainCard(i) && (c == '+' || c == '-')) {
            SpannableString ss = new SpannableString(plainText);

            float baseSp = getBaseSpForMain(i);
            float signPx = spToPx(baseSp * SIGN_RELATIVE_SIZE);
            float targetSignWidthPx = measureFixedSignTargetWidth(baseSp);
            float gapPx = dp(SIGN_GAP_DP);

            ss.setSpan(
                    new FixedSignSpan(signPx, targetSignWidthPx, gapPx),
                    0,
                    1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );

            return ss;
        }

        return plainText;
    }

    /** V2.6.5: 测量固定符号占位宽度（与 FixedSignSpan.getSize 一致） */
    private float measureFixedSignWidth(float baseSp) {
        return measureFixedSignTargetWidth(baseSp) + dp(SIGN_GAP_DP);
    }

    /** V2.6.4: 测量数字主体宽度 */
    private float measureMainBodyWidth(TextView tv, String text, float sp, int startIndex) {
        if (text == null || startIndex >= text.length()) return 0f;

        TextPaint p = mainMeasurePaint;

        p.set(tv.getPaint());
        p.setTextScaleX(1.0f);
        p.setTextSize(spToPx(sp));

        return p.measureText(text, startIndex, text.length());
    }

    /** V2.6.4: 固定符号 + 主体分开计算 targetScaleX */
    private float calculateMainTargetScaleX(TextView tv, String text, float baseSp, int cardIndex, int available) {
        if (text == null || text.length() == 0 || available <= 0) return 1f;

        char c = text.charAt(0);

        if (ENABLE_COMPACT_SIGN
                && isSignedMainCard(cardIndex)
                && (c == '+' || c == '-')
                && text.length() > 1) {

            float signW = measureFixedSignWidth(baseSp);
            float bodyW = measureMainBodyWidth(tv, text, baseSp, 1);

            float bodyAvailable = available - signW;

            if (bodyAvailable <= 1f || bodyW <= 0f) {
                return getHardMinScaleXForMain(cardIndex);
            }

            return bodyAvailable / bodyW;
        }

        float rawWidth = measureTextUnscaled(tv, mainMeasurePaint, text, baseSp);

        if (rawWidth <= 0f) return 1f;

        return available / rawWidth;
    }

    /** V2.6: 唯一主值渲染 — V2.6.3: smooth scale + compact sign */
    private void renderMainText(final int i, final String text) {
        final TextView tv = valueIntViews[i];
        final View area = valueAreaViews[i];

        if (tv == null || area == null) return;

        // V2.6.3: 记录是否从语义状态恢复
        boolean wasSemantic = semanticMode[i];
        semanticMode[i] = false;

        // valueDec 永久不用
        if (valueDecViews[i] != null) {
            valueDecViews[i].setVisibility(View.GONE);
            valueDecViews[i].setText("");
        }

        // 恢复 extremePanel
        if (extremePanelViews[i] != null) {
            extremePanelViews[i].setVisibility(View.VISIBLE);
        }

        tv.setVisibility(View.VISIBLE);
        tv.setSingleLine(true);
        tv.setEllipsize(null);
        tv.setIncludeFontPadding(false);
        tv.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);

        final float baseSp = getBaseSpForMain(i);

        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSp);

        // V2.6.3: compact +/- sign
        CharSequence displayText = buildMainDisplayText(i, text);
        tv.setText(displayText);

        final int available = area.getWidth()
                - area.getPaddingLeft()
                - area.getPaddingRight()
                - dp(4);

        if (available <= 0) {
            area.post(new Runnable() {
                @Override public void run() {
                    renderMainText(i, text);
                }
            });
            return;
        }

        // V2.6.4: 固定符号 + 主体数字分开测量
        float targetScaleX = calculateMainTargetScaleX(tv, text, baseSp, i, available);
        if (targetScaleX > 1.0f) targetScaleX = 1.0f;

        float hardMin = getHardMinScaleXForMain(i);
        if (targetScaleX < hardMin) targetScaleX = hardMin;

        // V2.6.3: 从语义状态恢复时直接用正确 scale, 不拖旧值过渡
        float displayScaleX;
        if (wasSemantic) {
            displayedMainScaleX[i] = targetScaleX;
            displayScaleX = targetScaleX;
        } else {
            displayScaleX = smoothMainScaleX(i, targetScaleX);
        }

        tv.setTextScaleX(displayScaleX);
    }

    /** V2.6: 渲染 DFCO/SYNC — 复用 valueInt, INVISIBLE extremePanel */
    private void renderSemanticCard(final int i, final String label) {
        final TextView tv = valueIntViews[i];
        final View area = valueAreaViews[i];

        if (tv == null || area == null) return;

        semanticMode[i] = true;

        // A/F 进入语义模式时, 强制清理闪烁和半透明残留
        if (i == 5) {
            clearAfSemanticContamination();
        }

        if (valueDecViews[i] != null) {
            valueDecViews[i].setVisibility(View.GONE);
            valueDecViews[i].setText("");
            valueDecViews[i].setAlpha(ALPHA_SEMANTIC_SYNC);
        }

        // INVISIBLE, 不改变布局
        if (extremePanelViews[i] != null) {
            extremePanelViews[i].setVisibility(View.INVISIBLE);
        }

        tv.setVisibility(View.VISIBLE);
        tv.setSingleLine(true);
        tv.setEllipsize(null);
        tv.setIncludeFontPadding(false);
        tv.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        tv.setTextColor(COLOR_SEMANTIC_SYNC);
        tv.setAlpha(ALPHA_SEMANTIC_SYNC);

        final float semanticSp = 84f;

        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, semanticSp);
        tv.setText(label);

        final int available = area.getWidth()
                - area.getPaddingLeft()
                - area.getPaddingRight()
                - dp(4);

        if (available <= 0) {
            area.post(new Runnable() {
                @Override public void run() {
                    renderSemanticCard(i, label);
                }
            });
            return;
        }

        // V2.6.2 核心: 用独立 Paint 测量
        float rawWidth = measureTextUnscaled(tv, mainMeasurePaint, label, semanticSp);
        if (rawWidth <= 0f) return;

        float targetScaleX = available / rawWidth;
        if (targetScaleX > 1.0f) targetScaleX = 1.0f;
        if (targetScaleX < 0.28f) targetScaleX = 0.28f;

        tv.setTextScaleX(targetScaleX);

        // 防止 SYNC/DFCO 的 scale 影响下一帧普通数字
        displayedMainScaleX[i] = 0f;

        if (scaleBars[i] != null) {
            scaleBars[i].setValue(Float.NaN);
        }
    }

    /** V2.6: MAX/MIN 独立 fit — 无缓存, 每帧重算 */
    private void renderExtremeText(final TextView tv, final String text) {
        if (tv == null) return;

        final float extremeSp = 20f;

        tv.setSingleLine(true);
        tv.setEllipsize(null);
        tv.setIncludeFontPadding(false);
        tv.setGravity(Gravity.END);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, extremeSp);
        tv.setText(text);

        int available = tv.getWidth()
                - tv.getPaddingLeft()
                - tv.getPaddingRight()
                - dp(1);

        if (available <= 0) {
            tv.post(new Runnable() {
                @Override public void run() {
                    renderExtremeText(tv, text);
                }
            });
            return;
        }

        // V2.6.2 核心: 不要用 tv.getPaint().measureText()
        float rawWidth = measureTextUnscaled(tv, extremeMeasurePaint, text, extremeSp);
        if (rawWidth <= 0f) return;

        float scaleX = available / rawWidth;
        if (scaleX > 1.0f) scaleX = 1.0f;
        if (scaleX < 0.35f) scaleX = 0.35f;

        tv.setTextScaleX(scaleX);
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
    private void endEngineExtremeSession() {
        engineExtremeSessionActive = false;
        engineExtremeSessionStartMs = 0L;
        engineStoppedSinceMs = 0L;

        for (int i = 0; i < 8; i++) {
            engineBaselineApplied[i] = false;
        }

        resetEthanolSettlingGate();
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
        }

        if (rpm <= ENGINE_STOPPED_RPM_THRESHOLD) {
            if (engineStoppedSinceMs <= 0L) {
                engineStoppedSinceMs = now;
            }

            if (now - engineStoppedSinceMs >= ENGINE_STOPPED_STABLE_MS) {
                endEngineExtremeSession();
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
        return i == 5 || i == 6 || i == 7; // A/F, IGN, S.TRIM
    }

    // V2.6.7: 冷启动/暖机判定 — 不用 closedLoop 短暂 open loop 作为依据
    private boolean isWarmupLowReference(EngineSemanticState state, SensorData data) {
        float ect = (float) data.getDouble(0x0160);
        boolean coldEngine = ect < WARMUP_ECT_THRESHOLD;
        boolean warmupState = state.isWarmup();
        return coldEngine || (warmupState && ect < 80f);
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
    private void applyMainValueSemanticColor(int i, float fVal) {
        if (valueIntViews[i] == null) return;
        if (semanticMode[i]) return;

        switch (i) {
            case 3: // L.TRIM
                valueIntViews[i].setTextColor(getTrimSemanticColor(fVal));
                valueIntViews[i].setAlpha(1f);
                break;
            case 4: // MAP: fVal 已经是相对增压 bar
                valueIntViews[i].setTextColor(getMapSemanticColor(fVal));
                valueIntViews[i].setAlpha(1f);
                break;
            case 6: // IGN
                valueIntViews[i].setTextColor(getIgnSemanticColor(fVal));
                valueIntViews[i].setAlpha(1f);
                break;
            case 7: // S.TRIM
                valueIntViews[i].setTextColor(getTrimSemanticColor(fVal));
                valueIntViews[i].setAlpha(1f);
                break;
        }
    }

    /** V2.3: Lambda 语义 A/F 颜色判定 — WOT 用绝对 lambda, 闭环用 lambda error */
    private int getAfColorByLambda(float lambda, float targetLambda, EngineSemanticState state) {
        boolean lambdaValid = !Float.isNaN(lambda);
        boolean targetValid = !Float.isNaN(targetLambda);

        if (state != null && state.isWot()) {
            // WOT: 防过稀优先, 使用绝对 lambda 阈值
            if (lambdaValid) {
                if (lambda > WOT_LAMBDA_DANGER_LEAN) return 0xFFFF4444;   // 过稀: 危险红
                if (lambda > WOT_LAMBDA_WARN_LEAN)   return 0xFFD29922;   // 偏稀: 警告黄
                if (lambdaValid && lambda < WOT_LAMBDA_WARN_RICH) return 0xFFD29922;  // 过浓: 警告黄
            }
            return 0xFF3FB950;  // WOT 安全: 绿色
        }

        // 闭环 / NORMAL: 使用 lambda error 距离目标的偏差
        if (lambdaValid && targetValid) {
            float err = Math.abs(lambda - targetLambda);
            if (err <= CL_LAMBDA_ERR_GREEN) return 0xFF3FB950;   // 绿色: 紧跟目标
            if (err <= CL_LAMBDA_ERR_WARN)  return 0xFFD29922;   // 黄色: 轻微偏差
            return 0xFFFF4444;                                     // 红色: 严重偏差
        }

        // lambda 数据缺失时退化到固定 AFR 阈值
        float af = lambdaValid ? lambda * 14.7f : lastAfVal;
        if (af < 12.5f || af > 16.0f) return 0xFFFF4444;
        if (af < 14.0f || af > 15.5f) return 0xFFD29922;
        return 0xFF3FB950;
    }

    /**
     * Boost 不对称滤波: 增压快响应, 泄压按工况动态调整
     * V2: 释放率由 EngineSemanticState.boostRelease() 连续提供
     */
    private float boostFilter(float raw, float last, EngineSemanticState state) {
        if (raw > last) {
            return ema(raw, last, 0.6f);   // 快攻击 (不变)
        } else {
            float release = state.boostRelease();
            return ema(raw, last, release);
        }
    }

    @Override
    public void onError(final String msg) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
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
            Toast.makeText(this, "设备不支持蓝牙", Toast.LENGTH_LONG).show();
            return;
        }
        if (!bt.isEnabled()) {
            startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            return;
        }

        statusText.setText("连接中...");
        dataSource.connect(BluetoothSource.FLASHPRO_MAC);
    }
}
