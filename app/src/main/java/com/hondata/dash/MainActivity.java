package com.hondata.dash;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.os.Bundle;
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
import android.text.style.ScaleXSpan;

import com.hondata.dash.data.BluetoothSource;
import com.hondata.dash.data.DataSource;
import com.hondata.dash.data.DemoSource;
import com.hondata.dash.data.EngineSemanticState;
import com.hondata.dash.data.EngineStateTracker;
import com.hondata.dash.data.SensorData;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import android.os.SystemClock;

/**
 * 主界面 - 硬核科技风格车载仪表盘 V2.4.2。
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

    // V2.4: 显示适配 — 语义模式控制 + auto-fit 缓存
    private final View[] valueAreaViews = new View[8];
    private final View[] extremePanelViews = new View[8];
    private final boolean[] semanticMode = new boolean[8];
    private final String[] lastMainCombinedText = new String[8];
    private final String[] lastSemanticText = new String[8];
    private final int[] lastFitWidth = new int[8];
    private final float[] lastMainScaleX = new float[8]; // V2.4.2: scaleX hysteresis

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
    private static final float WOT_LAMBDA_DANGER_LEAN = 0.86f;  // ~12.6 AFR
    private static final float WOT_LAMBDA_WARN_LEAN   = 0.83f;  // ~12.2 AFR
    private static final float WOT_LAMBDA_WARN_RICH   = 0.68f;  // ~10.0 AFR
    private static final float CL_LAMBDA_ERR_GREEN = 0.03f;
    private static final float CL_LAMBDA_ERR_WARN  = 0.06f;

    // V2.4.2: Font constants — per-row base sizes (maximize font, width-only adaptive scaleX)
    private static final float ROW1_VALUE_SP = 112f;     // Ethanol, ECT, IAT, L.TRIM
    private static final float ROW2_VALUE_SP = 102f;     // MAP, A/F, IGN, S.TRIM
    private static final float SEMANTIC_VALUE_SP = 92f;  // DFCO/SYNC labels

    private static float getBaseSp(int i) {
        return i < 4 ? ROW1_VALUE_SP : ROW2_VALUE_SP;
    }

    // Per-card minimum scaleX (no max cap — let it go up to 1.0)
    private static final float[] MIN_SCALE_X = {
        0.40f,  // 0 Ethanol
        0.42f,  // 1 ECT
        0.42f,  // 2 IAT
        0.34f,  // 3 L.TRIM
        0.38f,  // 4 MAP
        0.38f,  // 5 A/F
        0.30f,  // 6 IGN
        0.30f   // 7 S.TRIM
    };

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
                if (valueDecViews[1] != null) {
                    valueDecViews[1].setTextColor(color);
                    valueDecViews[1].setAlpha(alpha / 255f);
                }
            }
            // IAT闪烁 (>=65)
            if (iatFlashing) {
                int alpha = flashVisible ? 255 : 40;
                int color = 0xFFB040FF;
                valueIntViews[2].setTextColor(color);
                valueIntViews[2].setAlpha(alpha / 255f);
                if (valueDecViews[2] != null) {
                    valueDecViews[2].setTextColor(color);
                    valueDecViews[2].setAlpha(alpha / 255f);
                }
            }
            // A/F闪烁 (红色区间) — V2.4: 语义模式期间不压暗 alpha
            if (afFlashing && !semanticMode[5]) {
                int alpha = flashVisible ? 255 : 40;
                int color = 0xFFFF4444;
                valueIntViews[5].setTextColor(color);
                valueIntViews[5].setAlpha(alpha / 255f);
                if (valueDecViews[5] != null) {
                    valueDecViews[5].setTextColor(color);
                    valueDecViews[5].setAlpha(alpha / 255f);
                }
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

            // 第1行(0-2): 整数, 字高×1.5; 第1行(3) + 第2行(4-7): 小数, 字高×1.65
            if (i < 3) {
                float scale = 1.5f;
                // Ethanol(0): "E" 前缀多一个字符, 需要更窄 scaleX 避免溢出容器
                float scaleX = (i == 0) ? 0.5f : 1f / scale;
                if (valueIntViews[i] != null) { valueIntViews[i].setTextSize(75 * scale); valueIntViews[i].setTextScaleX(scaleX); }
                if (valueDecViews[i] != null) { valueDecViews[i].setTextSize(75 * scale); valueDecViews[i].setTextScaleX(scaleX); }
            } else {
                float scale = 1.65f;
                // IGN(6), S.TRIM(7): 压窄字体避免带符号两位数+小数溢出
                float scaleX = (i == 6 || i == 7) ? 0.5f : 1f / scale;
                if (valueIntViews[i] != null) { valueIntViews[i].setTextSize(60 * scale); valueIntViews[i].setTextScaleX(scaleX); }
                if (valueDecViews[i] != null) { valueDecViews[i].setTextSize(60 * scale); valueDecViews[i].setTextScaleX(scaleX); }
                if (maxValueViews[i] != null) maxValueViews[i].setTextSize(21);
                if (minValueViews[i] != null) minValueViews[i].setTextSize(21);
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

        if (USE_DEMO) {
            dataSource.connect(null);
        } else {
            connectBluetooth();
        }

        // V2.2: 启动数据新鲜度独立刷新 (250ms)
        freshnessHandler.post(freshnessRunnable);
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
                bar.addZone(-25, 0, 0xFFFF4444);
                bar.addZone(0, 25, 0xFF0088FF);
                bar.setAnchor(0);
                bar.setThermal(0.2f, 0.1f, 0.08f);  // 极慢吸热, 极慢散热, 极慢漂移
                break;

            case 4: // Boost: MECHANICAL — Spring-Damper + Peak Hold
                bar.setRange(-1.0f, 2.0f);
                bar.setTicks(
                    new float[]{-1.0f, 0, 0.5f, 1.0f, 1.5f, 2.0f},
                    new String[]{"-1.0", "0", "0.5", "1.0", null, "2.0"});
                bar.addZone(-1.0f, 0, 0xFF00D8FF);
                bar.addZone(0, 0.5f, 0xFF0088FF);
                bar.addZone(0.5f, 1.5f, 0xFF3FB950);
                bar.addZone(1.5f, 2.0f, 0xFFFF4444);
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
                bar.addZone(-25, 0, 0xFFFF4444);
                bar.addZone(0, 25, 0xFF0088FF);
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
        if (dataSource.isConnected()) {
            dataSource.startPolling();
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
        engineState.reset();
        for (int i = 0; i < 8; i++) {
            // V2.2: Session Extreme 参数 (Ethanol/ECT/IAT/L.TRIM/MAP) 不清空全程极值
            if (!SHOW_SESSION_EXTREME[i]) {
                hasValue[i] = false;
                lastMaxTime[i] = 0;
                lastMinTime[i] = 0;
                recentMaxTime[i] = 0;
                recentMinTime[i] = 0;
            }
            // 滤波和显示有效标记可重置 (不是 session 历史)
            hasFiltered[i] = false;
            hasValidValue[i] = false;
        }
        dfcoExitTime = 0;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                statusText.setText("已断开");
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

                // 更新大气压力 (用于 Boost 相对压力计算)
                Double baro = data.get(0x170);
                if (baro != null) lastBaro = baro.floatValue();

                // 检测引擎状态 (V2 语义模型)
                EngineSemanticState state = engineState.update(data);
                boolean isDfco = state.isDfco();
                boolean dfcoJustEnded = lastDfcoState && !isDfco;
                lastDfcoState = isDfco;
                if (dfcoJustEnded) dfcoExitTime = System.currentTimeMillis();

                // V2.3: combustionInvalid 显示层门控
                boolean combustionInvalid = isCombustionInvalid(data);
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

                long now = System.currentTimeMillis();

                // 更新 8 个主卡片
                for (int i = 0; i < 8; i++) {
                    if (valueIntViews[i] == null) continue;

                    int pid = CARD_PIDS[i];
                    String fmt = CARD_FMT[i];
                    Double raw = data.get(pid);

                    if (raw != null) {
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

                        // P1: 范围校验 — 超出物理范围视为异常, 跳过处理
                        float[] range = VALID_RANGE[i];
                        if (fVal < range[0] || fVal > range[1]) {
                            if (hasValidValue[i]) {
                                valueIntViews[i].setAlpha(0.4f);
                                if (valueDecViews[i] != null) valueDecViews[i].setAlpha(0.4f);
                            } else {
                                valueIntViews[i].setText("--");
                                if (valueDecViews[i] != null) valueDecViews[i].setText("");
                            }
                            if (scaleBars[i] != null) scaleBars[i].setValue(Float.NaN);
                            continue;
                        }

                        // V2.3: combustionInvalid / SYNC 显示层门控
                        // 替代旧 isDfco 单一判断, 使用多源语义检测 + 独立 SYNC 释放
                        if (i == 5) { // A/F
                            if (combustionInvalid) {
                                clearAfFlashIfNeeded();
                                renderSemanticCard(i, "DFCO");
                                continue;
                            }
                            if (afSync) {
                                clearAfFlashIfNeeded();
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

                        // History Admission System: V2.2 双路径
                        // Session Extreme: 全程极值, 不衰减; Recent Extreme: 近期动态, 可衰减
                        float valueForExtreme = (i == 4) ? rawValueForExtreme : fVal;

                        if (!hasValue[i]) {
                            // V2.2: Session Extreme 初始化用 valueForExtreme (MAP 用原始值)
                            float initVal = SHOW_SESSION_EXTREME[i] ? valueForExtreme : fVal;
                            if (!SHOW_SESSION_EXTREME[i] || isTrustedForSessionExtreme(i, initVal)) {
                                maxTrack[i] = initVal;
                                minTrack[i] = initVal;
                                recentMax[i] = fVal;
                                recentMin[i] = fVal;
                                lastMaxTime[i] = lastMinTime[i] = now;
                                recentMaxTime[i] = recentMinTime[i] = now;
                                hasValue[i] = true;
                            }
                        } else if (SHOW_SESSION_EXTREME[i]) {
                            // Session Extreme 路径: 只做可信值过滤, 无 cooldown/decay
                            if (isTrustedForSessionExtreme(i, valueForExtreme)) {
                                if (valueForExtreme > maxTrack[i]) maxTrack[i] = valueForExtreme;
                                if (valueForExtreme < minTrack[i]) minTrack[i] = valueForExtreme;
                            }
                            // recent 数组也保持同步 (ScaleBar 可能引用)
                            if (fVal > recentMax[i]) { recentMax[i] = fVal; recentMaxTime[i] = now; }
                            if (fVal < recentMin[i]) { recentMin[i] = fVal; recentMinTime[i] = now; }
                            // Session Extreme 不做 recent decay
                        } else if (isEligibleForHistory(i, state)) {
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

                        if (updateDisplay) {
                            lastUpdateTime[i] = now;

                            // V2.4: 从语义模式恢复为正常数值模式
                            setSemanticLayoutMode(i, false);

                            // 格式化并拆分整数/小数部分
                            String formatted = String.format(Locale.US, fmt, fVal);
                            String intPart, decPart;
                            int dotPos = formatted.indexOf('.');
                            if (dotPos >= 0) {
                                intPart = formatted.substring(0, dotPos);
                                decPart = formatted.substring(dotPos);
                            } else {
                                intPart = formatted;
                                decPart = "";
                            }
                            // Ethanol: E 前缀
                            if (i == 0) intPart = "E" + intPart;

                            // V2.4.1: width-only auto-fit — 高度固定, 只动态 textScaleX
                            renderMainValueMaxFit(i, intPart, decPart);
                            // 默认白色, 后面各卡片按条件覆盖颜色
                            valueIntViews[i].setTextColor(0xFFFFFFFF);
                            valueIntViews[i].setAlpha(1f);
                            if (valueDecViews[i] != null) {
                                valueDecViews[i].setTextColor(0xFFFFFFFF);
                                valueDecViews[i].setAlpha(1f);
                            }

                            if (scaleBars[i] != null) {
                                // 更新情绪状态 (在 setValue 之前, setValue 内部会渐变)
                                updateEmotion(i, fVal, state);
                                scaleBars[i].setValue(fVal);
                            }

                            if (maxValueViews[i] != null) {
                                String maxFmt;
                                if (i < 3) maxFmt = "%.0f";        // Ethanol, ECT, IAT
                                else if (i == 5) maxFmt = "%.1f";  // A/F
                                else maxFmt = "%+.1f";             // L.TRIM, MAP, IGN, S.TRIM
                                float displayMax = SHOW_SESSION_EXTREME[i] ? maxTrack[i] : recentMax[i];
                                maxValueViews[i].setText(String.format(Locale.US, maxFmt, displayMax));
                                maxValueViews[i].setTextColor(0xFFCCCCCC);
                            }
                            if (minValueViews[i] != null) {
                                String minFmt;
                                if (i < 3) minFmt = "%.0f";        // Ethanol, ECT, IAT
                                else if (i == 5) minFmt = "%.1f";  // A/F
                                else minFmt = "%+.1f";             // L.TRIM, MAP, IGN, S.TRIM
                                float displayMin = SHOW_SESSION_EXTREME[i] ? minTrack[i] : recentMin[i];
                                minValueViews[i].setText(String.format(Locale.US, minFmt, displayMin));
                                minValueViews[i].setTextColor(0xFFCCCCCC);
                            }

                            // Ethanol: 按浓度变色 (E前缀已由 fitSplitValueText 设置)
                            if (i == 0) {
                                int ethColor;
                                if (fVal < 20) ethColor = 0xFFFFFFFF;       // 白色
                                else if (fVal < 40) ethColor = 0xFF3FB950;  // 绿色
                                else if (fVal < 60) ethColor = 0xFFD29922;  // 黄色
                                else ethColor = 0xFFFF4444;                  // 红色
                                valueIntViews[i].setTextColor(ethColor);
                                if (valueDecViews[i] != null) {
                                    valueDecViews[i].setTextColor(ethColor);
                                }
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
                                    if (valueDecViews[i] != null) {
                                        valueDecViews[i].setTextColor(color);
                                        valueDecViews[i].setAlpha(1f);
                                    }
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
                                    if (valueDecViews[i] != null) {
                                        valueDecViews[i].setTextColor(color);
                                        valueDecViews[i].setAlpha(1f);
                                    }
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
                                    if (valueDecViews[i] != null) {
                                        valueDecViews[i].setTextColor(color);
                                        valueDecViews[i].setAlpha(1f);
                                    }
                                }
                            }

                        }
                    } else {
                        // Last-Valid 缓存: 数据缺失时保留最后有效值 (半透明)
                        if (hasValidValue[i]) {
                            valueIntViews[i].setAlpha(0.4f);
                            if (valueDecViews[i] != null) {
                                valueDecViews[i].setAlpha(0.4f);
                            }
                        } else {
                            valueIntViews[i].setText("--");
                            valueIntViews[i].setAlpha(1f);
                            if (valueDecViews[i] != null) valueDecViews[i].setText("");
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
                        int delta = knock - lastKnockCount[i];
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

    /**
     * 将格式化后的字符串拆分为整数部分和小数部分, 分别设置到两个TextView。
     * 例如 "14.2" -> valueInt="14", valueDec=".2"
     * 例如 "85" -> valueInt="85", valueDec=""
     * 例如 "-3.5" -> valueInt="-3", valueDec=".5"
     */
    private void splitSetValue(int i, String formatted) {
        int dotPos = formatted.indexOf('.');
        if (dotPos >= 0) {
            valueIntViews[i].setText(formatted.substring(0, dotPos));
            if (valueDecViews[i] != null) {
                valueDecViews[i].setText(formatted.substring(dotPos));
            }
        } else {
            valueIntViews[i].setText(formatted);
            if (valueDecViews[i] != null) {
                valueDecViews[i].setText("");
            }
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
            && (System.currentTimeMillis() - dfcoExitTime < 500);

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
    private boolean isTrustedForSessionExtreme(int index, float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return false;
        switch (index) {
            case 0: return value >= 0f && value <= 100f;        // Ethanol %
            case 1: return value >= -40f && value <= 130f;       // ECT °C
            case 2: return value >= -40f && value <= 120f;       // IAT °C
            case 3: return value >= -30f && value <= 30f;        // L.TRIM %
            case 4: return value >= -1.0f && value <= 2.5f;      // MAP/Boost bar
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
        boolean movingOrHighRpm = speed > 5.0f || rpm > 1200.0f;

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

    /** 语义模式: 全宽显示 DFCO/SYNC, 隐藏小数和极值面板 */
    private void setSemanticLayoutMode(int i, boolean enable) {
        if (semanticMode[i] == enable) return;
        semanticMode[i] = enable;

        if (valueDecViews[i] != null) {
            valueDecViews[i].setVisibility(enable ? View.GONE : View.VISIBLE);
            if (enable) valueDecViews[i].setText("");
        }

        if (extremePanelViews[i] != null) {
            extremePanelViews[i].setVisibility(enable ? View.GONE : View.VISIBLE);
        }

        if (valueAreaViews[i] != null) {
            LinearLayout.LayoutParams lp =
                    (LinearLayout.LayoutParams) valueAreaViews[i].getLayoutParams();
            lp.weight = enable ? 1f : 4.0f;
            lp.width = 0;
            valueAreaViews[i].setLayoutParams(lp);
        }

        if (valueIntViews[i] != null) {
            valueIntViews[i].setIncludeFontPadding(false);
            valueIntViews[i].setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        }

        // 切换模式后清空缓存, 退出语义模式时强制重新 fit
        lastMainCombinedText[i] = null;
        lastSemanticText[i] = null;
        if (!enable) lastFitWidth[i] = -1;
    }

    /** V2.4.1: 语义标签 width-only fit — 高度固定, 只动态 textScaleX */
    private void fitSingleTextWidthOnly(
            final TextView tv,
            final View area,
            final String text,
            final float baseSp,
            final float minScaleX,
            final float maxScaleX
    ) {
        if (tv == null || area == null) return;

        int available = area.getWidth()
                - area.getPaddingLeft()
                - area.getPaddingRight()
                - dp(4);

        if (available <= 0) {
            area.post(new Runnable() {
                @Override public void run() {
                    fitSingleTextWidthOnly(tv, area, text, baseSp, minScaleX, maxScaleX);
                }
            });
            return;
        }

        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSp);
        tv.setSingleLine(true);
        tv.setIncludeFontPadding(false);
        tv.setEllipsize(null);
        tv.setText(text);

        float rawWidth = tv.getPaint().measureText(text);
        if (rawWidth <= 0f) return;

        float scaleX = 1.0f;
        if (rawWidth > available) {
            scaleX = (float) available / rawWidth;
        }
        if (scaleX < minScaleX) scaleX = minScaleX;
        if (scaleX > maxScaleX) scaleX = maxScaleX;

        tv.setTextScaleX(scaleX);
    }

    /** V2.4.2: 主数据最大化字体 — 测量当前文本, scaleX hysteresis 防跳动 */
    private void renderMainValueMaxFit(
            final int i,
            final String intText,
            final String decText
    ) {
        final TextView intView = valueIntViews[i];
        final TextView decView = valueDecViews[i];
        final View area = valueAreaViews[i];

        if (intView == null || area == null) return;

        // 安全余量 dp(4): 两个 TextView 间距 + 右侧微量不贴边
        int available = area.getWidth()
                - area.getPaddingLeft()
                - area.getPaddingRight()
                - dp(4);

        if (available <= 0) {
            area.post(new Runnable() {
                @Override public void run() {
                    renderMainValueMaxFit(i, intText, decText);
                }
            });
            return;
        }

        // 缓存检查: 文本+宽度没变则跳过
        String combined = intText + "|" + (decText == null ? "" : decText);
        int currentWidth = area.getWidth();
        if (combined.equals(lastMainCombinedText[i]) && currentWidth == lastFitWidth[i]) {
            return;
        }
        lastMainCombinedText[i] = combined;
        lastFitWidth[i] = currentWidth;

        float baseSp = getBaseSp(i);

        // 高度固定: 恢复 baseSp
        intView.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSp);
        intView.setSingleLine(true);
        intView.setIncludeFontPadding(false);
        intView.setEllipsize(null);

        if (decView != null) {
            decView.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSp);
            decView.setSingleLine(true);
            decView.setIncludeFontPadding(false);
            decView.setEllipsize(null);
        }

        // 设置文本
        intView.setText(intText);
        if (decView != null) {
            decView.setText(decText == null ? "" : decText);
        }

        // 测量当前文本宽度 (不是 worst-case)
        android.text.TextPaint paint = intView.getPaint();
        float wInt = paint.measureText(intText);
        float wDec = (decView != null && decText != null && !decText.isEmpty())
                ? paint.measureText(decText) : 0f;
        float rawWidth = wInt + wDec;
        if (rawWidth <= 0f) return;

        // 计算目标 scaleX — 不设上限, 最大限度利用空间
        float targetScaleX = 1.0f;
        if (rawWidth > available) {
            targetScaleX = (float) available / rawWidth;
        }
        float minSx = MIN_SCALE_X[i];
        if (targetScaleX < minSx) targetScaleX = minSx;

        // Hysteresis: 缩窄立即应用, 放宽需 gap > 0.06 才生效
        float current = lastMainScaleX[i];
        if (targetScaleX < current) {
            lastMainScaleX[i] = targetScaleX;
        } else {
            if (targetScaleX - current > 0.06f) {
                lastMainScaleX[i] = targetScaleX;
            }
        }

        intView.setTextScaleX(lastMainScaleX[i]);
        if (decView != null) {
            decView.setTextScaleX(lastMainScaleX[i]);
        }
    }

    /** 渲染 DFCO / SYNC 语义标签 — V2.4.1: 全宽模式 + width-only fit */
    private void renderSemanticCard(int i, String label) {
        setSemanticLayoutMode(i, true);

        if (valueIntViews[i] == null) return;

        if (label == null) label = "";

        String cache = label;
        if (!cache.equals(lastSemanticText[i])) {
            lastSemanticText[i] = cache;
            View area = valueAreaViews[i] != null
                    ? valueAreaViews[i]
                    : (View) valueIntViews[i].getParent();
            fitSingleTextWidthOnly(valueIntViews[i], area, label, SEMANTIC_VALUE_SP, 0.40f, 1.0f);
        }

        valueIntViews[i].setTextColor(COLOR_SEMANTIC_SYNC);
        valueIntViews[i].setAlpha(1f);

        if (valueDecViews[i] != null) {
            valueDecViews[i].setText("");
            valueDecViews[i].setVisibility(View.GONE);
        }

        if (scaleBars[i] != null) {
            scaleBars[i].setValue(Float.NaN);
        }
    }

    /** DFCO/SYNC 期间清除 A/F 闪烁状态, 防止残留 flash 压暗 alpha */
    private void clearAfFlashIfNeeded() {
        if (!afFlashing) return;
        afFlashing = false;
        if (valueIntViews[5] != null) valueIntViews[5].setAlpha(1f);
        if (valueDecViews[5] != null) valueDecViews[5].setAlpha(1f);
        updateFlashState();
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
