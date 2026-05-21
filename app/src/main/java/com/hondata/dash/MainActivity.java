package com.hondata.dash;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import com.hondata.dash.data.BluetoothSource;
import com.hondata.dash.data.DataSource;
import com.hondata.dash.data.DemoSource;
import com.hondata.dash.data.EngineStateTracker;
import com.hondata.dash.data.SensorData;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 主界面 - 硬核科技风格车载仪表盘 V2.3。
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
    private final float[] maxTrack = new float[8];
    private final float[] minTrack = new float[8];
    private final boolean[] hasValue = new boolean[8];

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
    private float lastEctVal = 0;
    private float lastIatVal = 0;
    private float lastAfVal = 14.7f;
    private float lastTpPlate = 0;  // 节气门开度, 用于排除滑行/换挡
    private float lastBaro = 101.3f; // 大气压力 kPa, 用于 Boost 相对压力

    // 引擎状态检测
    private final EngineStateTracker engineState = new EngineStateTracker();

    // 最后有效值缓存 (DFCO 冻结 + 丢包保留)
    private final float[] lastValidValue = new float[8];
    private final boolean[] hasValidValue = new boolean[8];
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
            // A/F闪烁 (红色区间 + 踩油门状态)
            if (afFlashing) {
                int alpha = flashVisible ? 255 : 40;
                int color = 0xFFFF4444;
                valueIntViews[4].setTextColor(color);
                valueIntViews[4].setAlpha(alpha / 255f);
                if (valueDecViews[4] != null) {
                    valueDecViews[4].setTextColor(color);
                    valueDecViews[4].setAlpha(alpha / 255f);
                }
            }
            // K.C闪烁 (>65)
            if (kcFlashing && knockRetValue != null) {
                int alpha = flashVisible ? 255 : 40;
                knockRetValue.setTextColor(0xFFFF4444);
                knockRetValue.setAlpha(alpha / 255f);
            }
            if (ectFlashing || iatFlashing || afFlashing || kcFlashing) {
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
    private static final int[] CARD_PIDS = {
        0xB03, 0x160, 0x151, 0x110,   // 第1行: Ethanol, ECT, IAT2, MAP
        0x320, 0x140, 0x330, 0x332    // 第2行: A/F, IGN, S.TRIM, L.TRIM
    };

    private static final String[] CARD_EN = {
        "Ethanol", "ECT", "IAT", "MAP",
        "A/F", "IGN", "S.TRIM", "L.TRIM"
    };

    private static final String[] CARD_FMT = {
        "%.0f", "%.0f", "%.0f", "%+.1f",
        "%.1f", "%+.1f", "%+.1f", "%+.1f"
    };

    private static final String[] CARD_UNIT = {
        "%", "\u00B0C", "\u00B0C", "bar",
        "", "\u00B0", "%", "%"
    };

    // 爆震缸 PID (Knock Count 1-4)
    private static final int[] KNOCK_PIDS = {0x421, 0x422, 0x423, 0x424};

    // 爆震控制 PID (Knock Control %)
    private static final int KNOCK_CTRL_PID = 0x412;

    // 底部 PID
    private static final int BAT_PID = 0x180;
    private static final int FP_PID = 0x191;    // Fuel Pressure kPa
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

            if (labelEnViews[i] != null) labelEnViews[i].setText(CARD_EN[i]);
            if (unitViews[i] != null) {
                String u = CARD_UNIT[i];
                unitViews[i].setText(u.isEmpty() ? "" : "(" + u + ")");
            }

            // 第1行(0-2): 字高×1.2; 第1行(3) Boost带小数用小字号; 第2行(4-7): 字高×1.4
            if (i < 3) {
                float scale = 1.2f;
                if (valueIntViews[i] != null) { valueIntViews[i].setTextSize(75 * scale); valueIntViews[i].setTextScaleX(1f / scale); }
                if (valueDecViews[i] != null) { valueDecViews[i].setTextSize(75 * scale); valueDecViews[i].setTextScaleX(1f / scale); }
            } else if (i == 3) {
                // Boost: 和第2行一样的小字号, 留空间给小数部分
                float scale = 1.4f;
                if (valueIntViews[i] != null) { valueIntViews[i].setTextSize(60 * scale); valueIntViews[i].setTextScaleX(1f / scale); }
                if (valueDecViews[i] != null) { valueDecViews[i].setTextSize(60 * scale); valueDecViews[i].setTextScaleX(1f / scale); }
                if (maxValueViews[i] != null) maxValueViews[i].setTextSize(21);
                if (minValueViews[i] != null) minValueViews[i].setTextSize(21);
            } else {
                float scale = 1.4f;
                if (valueIntViews[i] != null) { valueIntViews[i].setTextSize(60 * scale); valueIntViews[i].setTextScaleX(1f / scale); }
                if (valueDecViews[i] != null) { valueDecViews[i].setTextSize(60 * scale); valueDecViews[i].setTextScaleX(1f / scale); }
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
    }

    /**
     * 配置每个卡片的刻度进度条。
     */
    private void configureScaleBar(int i) {
        ScaleBarView bar = scaleBars[i];
        if (bar == null) return;

        switch (i) {
            case 0: // Ethanol: 0-100%, 分区颜色
                bar.setRange(0, 100);
                bar.setTicks(
                    new float[]{0, 20, 40, 60, 100},
                    new String[]{"0", "20", "40", "60", "100"});
                bar.addZone(0, 20, 0xFF888888);      // 灰 低乙醇
                bar.addZone(20, 40, 0xFF3FB950);     // 绿 E20-40
                bar.addZone(40, 60, 0xFFD29922);     // 黄 E40-60
                bar.addZone(60, 100, 0xFFFF4444);    // 红 >E60
                bar.setAnchor(0);
                break;

            case 1: // ECT: 40-120C, 蓝<100, 红>100
                bar.setRange(40, 120);
                bar.setTicks(
                    new float[]{40, 60, 80, 100, 120},
                    new String[]{"40", "60", "80", "100", "120"});
                bar.addZone(40, 100, 0xFF00D8FF);
                bar.addZone(100, 120, 0xFFFF4444);
                bar.setAnchor(40);
                break;

            case 2: // IAT: 20-100C, 蓝色
                bar.setRange(20, 100);
                bar.setTicks(
                    new float[]{20, 40, 60, 80, 100},
                    new String[]{"20", "40", "60", "80", "100"});
                bar.addZone(20, 100, 0xFF00D8FF);
                bar.setAnchor(20);
                break;

            case 3: // Boost: -1.0到2.0 bar (相对压力)
                bar.setRange(-1.0f, 2.0f);
                bar.setTicks(
                    new float[]{-1.0f, 0, 0.5f, 1.0f, 1.5f, 2.0f},
                    new String[]{"-1.0", "0", "0.5", "1.0", "1.5", "2.0"});
                bar.addZone(-1.0f, 0, 0xFF00D8FF);    // 负压 蓝色
                bar.addZone(0, 0.5f, 0xFF0088FF);     // 低增压
                bar.addZone(0.5f, 1.5f, 0xFF3FB950);  // 中增压 绿色
                bar.addZone(1.5f, 2.0f, 0xFFFF4444);  // 高增压 红色
                bar.setAnchor(0);
                break;

            case 4: // A/F: 9-18
                bar.setRange(9, 18);
                bar.setTicks(
                    new float[]{9, 11, 14.5f, 15.5f, 18},
                    new String[]{"9", "11", "14.5", "15.5", "18"});
                bar.addZone(9, 11, 0xFFFF4444);       // 极浓 红色
                bar.addZone(11, 14.5f, 0xFFD29922);   // 偏浓 黄色
                bar.addZone(14.5f, 15.5f, 0xFF3FB950);// 理想 绿色
                bar.addZone(15.5f, 18, 0xFFFF4444);   // 偏稀 红色
                bar.setAnchor(14.7f);
                bar.setExpand(14.5f, 15.5f, 2.5f);   // 绿色区间放大2.5倍
                break;

            case 5: // IGN: -40到40
                bar.setRange(-40, 40);
                bar.setTicks(
                    new float[]{-40, -20, 0, 20, 40},
                    new String[]{"-40", "-20", "0", "20", "40"});
                bar.addZone(-40, 40, 0xFF00D8FF);
                bar.setAnchor(0);
                break;

            case 6: // S.TRIM: -25到+25%
                bar.setRange(-25, 25);
                bar.setTicks(
                    new float[]{-25, 0, 25},
                    new String[]{"-25", "0", "25"});
                bar.addZone(-25, 0, 0xFFFF4444);
                bar.addZone(0, 25, 0xFF0088FF);
                bar.setAnchor(0);
                break;

            case 7: // L.TRIM: -25到+25%, 负红正蓝
                bar.setRange(-25, 25);
                bar.setTicks(
                    new float[]{-25, -12.5f, 0, 12.5f, 25},
                    new String[]{"-25", "", "0", "", "25"});
                bar.addZone(-25, 0, 0xFFFF4444);
                bar.addZone(0, 25, 0xFF0088FF);
                bar.setAnchor(0);
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
        runOnUiThread(new Runnable() {
            @Override public void run() {
                statusText.setText("已断开");
                statusText.setTextColor(0xFFFF4444);
            }
        });
    }

    // Rate Limit: 各参数更新间隔 (ms) — A/F/IGN 由自适应动态覆盖
    private static final long[] UPDATE_INTERVAL = {
        500,   // Ethanol: 2Hz (Flex Fuel 传感器更新慢)
        500,   // ECT: 2Hz
        500,   // IAT: 2Hz
        50,    // Boost: 20Hz
        100,   // A/F: 10Hz (WOT 自适应提升至 20Hz)
        100,   // IGN: 10Hz (WOT 自适应提升至 20Hz)
        200,   // S.TRIM: 5Hz
        1000   // L.TRIM: 1Hz (ECU 长期学习值, 极慢)
    };
    private final long[] lastUpdateTime = new long[8];

    // EMA 滤波: 各参数 alpha 系数 — A/F 由自适应动态覆盖
    private static final float[] EMA_ALPHA = {
        0.05f, // Ethanol: 极慢 (Flex Fuel 传感器更新慢, 稳定显示)
        0.1f,  // ECT: 极慢
        0.05f, // IAT: 极慢
        0f,    // Boost: 用不对称滤波
        0.3f,  // A/F: 中等 (WOT 自适应 α=0.7)
        0.4f,  // IGN: 中等
        0.2f,  // S.TRIM: 较慢
        1.0f   // L.TRIM: 不过滤 (ECU 长期学习值本身已平滑)
    };
    private final float[] filteredValue = new float[8];
    private final boolean[] hasFiltered = new boolean[8];

    // P1: 范围校验 (转换后的合法范围, 超出视为传感器异常)
    private static final float[][] VALID_RANGE = {
        {0, 100},       // 0: Ethanol %
        {-20, 130},     // 1: ECT °C
        {-20, 130},     // 2: IAT °C
        {-1.5f, 3.0f},  // 3: MAP relative bar
        {8, 25},        // 4: A/F ratio
        {-25, 55},      // 5: IGN °
        {-30, 30},      // 6: S.TRIM %
        {-30, 30},      // 7: L.TRIM %
    };

    @Override
    public void onDataReceived(final SensorData data) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                // 更新大气压力 (用于 Boost 相对压力计算)
                Double baro = data.get(0x170);
                if (baro != null) lastBaro = baro.floatValue();

                // 检测引擎状态
                EngineStateTracker.State state = engineState.update(data);
                boolean isDfco = (state == EngineStateTracker.State.DFCO);
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
                        if (i == 4) {
                            val = val * 14.7;
                        }

                        // Boost 卡片: kPa → bar (相对压力, 减去大气压)
                        if (i == 3) {
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

                        // DFCO 冻结: A/F / S.TRIM / L.TRIM
                        if (isDfco && (i == 4 || i == 6 || i == 7)) {
                            // A/F 显示 DFCCO 标识
                            if (i == 4) {
                                valueIntViews[i].setText("DFCO");
                                valueIntViews[i].setTextColor(0xFF888888);
                                valueIntViews[i].setAlpha(1f);
                                if (valueDecViews[i] != null) {
                                    valueDecViews[i].setText("");
                                }
                                if (scaleBars[i] != null) {
                                    scaleBars[i].setValue(Float.NaN);
                                }
                            }
                            // S.TRIM / L.TRIM 冻结最后有效值
                            continue;
                        }

                        // NaN 保护: 传感器异常值使用上次有效滤波值
                        if (Float.isNaN(fVal) && hasFiltered[i]) {
                            fVal = filteredValue[i];
                        }

                        // 自适应参数: 根据引擎工况动态调整滤波和刷新率
                        float effectiveAlpha = EMA_ALPHA[i];
                        long effectiveInterval = UPDATE_INTERVAL[i];
                        boolean isWot = (state == EngineStateTracker.State.WOT);
                        boolean isTransient = (state == EngineStateTracker.State.TRANSIENT);
                        boolean needFast = isWot || isTransient;
                        // A/F: WOT/TRANSIENT 快速响应 α=0.7/20Hz, 巡航稳定 α=0.3/10Hz
                        if (i == 4) {
                            effectiveAlpha = needFast ? 0.7f : 0.3f;
                            effectiveInterval = needFast ? 50L : 100L;
                        }
                        // IGN: WOT/TRANSIENT 高速刷新 20Hz
                        if (i == 5) {
                            effectiveInterval = needFast ? 50L : 100L;
                        }

                        // EMA 滤波
                        if (effectiveAlpha > 0 && effectiveAlpha < 1f) {
                            if (hasFiltered[i]) {
                                fVal = ema(fVal, filteredValue[i], effectiveAlpha);
                            }
                            filteredValue[i] = fVal;
                            hasFiltered[i] = true;
                        }
                        // Boost 不对称滤波
                        else if (i == 3) {
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

                        // MAX / MIN 追踪 (始终更新)
                        if (!hasValue[i]) {
                            maxTrack[i] = fVal;
                            minTrack[i] = fVal;
                            hasValue[i] = true;
                        } else {
                            if (fVal > maxTrack[i]) maxTrack[i] = fVal;
                            if (fVal < minTrack[i]) minTrack[i] = fVal;
                        }

                        if (updateDisplay) {
                            lastUpdateTime[i] = now;

                            // 格式化并拆分整数/小数部分
                            String formatted = String.format(Locale.US, fmt, fVal);
                            splitSetValue(i, formatted);
                            valueIntViews[i].setAlpha(1f);

                            if (scaleBars[i] != null) {
                                scaleBars[i].setValue(fVal);
                            }

                            if (maxValueViews[i] != null) {
                                String maxFmt = (i < 3) ? "%.0f" : (i >= 5 ? "%+.1f" : "%.1f");
                                if (i == 3) maxFmt = "%+.1f"; // MAP 带符号
                                maxValueViews[i].setText(String.format(Locale.US, maxFmt, maxTrack[i]));
                                maxValueViews[i].setTextColor(0xFFFF4444); // 红色
                            }
                            if (minValueViews[i] != null) {
                                String minFmt = (i < 3) ? "%.0f" : (i >= 5 ? "%+.1f" : "%.1f");
                                if (i == 3) minFmt = "%+.1f"; // MAP 带符号
                                minValueViews[i].setText(String.format(Locale.US, minFmt, minTrack[i]));
                                minValueViews[i].setTextColor(0xFFD29922); // 黄色
                            }

                            // Ethanol: 整数部分前加E标识, 按浓度变色
                            if (i == 0) {
                                valueIntViews[i].setText("E" + formatted);
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

                            // A/F: 9~11红(极浓) 11~14.5黄(偏浓) 14.5~15.5绿(理想) 15.5~18红(偏稀)
                            // 红色区间 + 踩油门状态才闪烁
                            if (i == 4) {
                                lastAfVal = fVal;
                                boolean inRedZone = fVal < 11 || fVal > 15.5f;
                                boolean isOnThrottle = lastTpPlate > 5; // TP>5% 视为踩油门
                                boolean shouldFlash = inRedZone && isOnThrottle;
                                if (shouldFlash != afFlashing) {
                                    afFlashing = shouldFlash;
                                    updateFlashState();
                                }
                                if (!afFlashing) {
                                    int color;
                                    if (fVal < 11) {
                                        color = 0xFFFF4444; // 红色 极浓
                                    } else if (fVal < 14.5f) {
                                        color = 0xFFD29922; // 黄色 偏浓
                                    } else if (fVal <= 15.5f) {
                                        color = 0xFF3FB950; // 绿色 理想
                                    } else {
                                        color = 0xFFFF4444; // 红色 偏稀
                                    }
                                    valueIntViews[i].setTextColor(color);
                                    valueIntViews[i].setAlpha(1f);
                                    if (valueDecViews[i] != null) {
                                        valueDecViews[i].setTextColor(color);
                                        valueDecViews[i].setAlpha(1f);
                                    }
                                }
                            }

                            // P2: IGN 负载门控 — 低负载时点火角无参考意义, 灰显
                            if (i == 5 && lastTpPlate < 3) {
                                valueIntViews[i].setAlpha(0.35f);
                                if (valueDecViews[i] != null) valueDecViews[i].setAlpha(0.35f);
                                if (scaleBars[i] != null) scaleBars[i].setAlpha(0.35f);
                            }

                            // P3: 置信度系统 — TRANSIENT 时 A/F, S.TRIM, L.TRIM 降低可信度
                            if (isTransient && (i == 4 || i == 6 || i == 7)) {
                                float conf = 0.45f;
                                valueIntViews[i].setAlpha(conf);
                                if (valueDecViews[i] != null) valueDecViews[i].setAlpha(conf);
                                if (scaleBars[i] != null) scaleBars[i].setAlpha(conf);
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
                for (int i = 0; i < 4; i++) {
                    if (knockValues[i] == null) continue;

                    Double kv = data.get(KNOCK_PIDS[i]);
                    if (kv != null) {
                        int knock = kv.intValue();
                        knockValues[i].setText(String.valueOf(knock));

                        if (knock == 0) {
                            knockValues[i].setTextColor(0xFF3FB950);
                        } else if (knock == 1) {
                            knockValues[i].setTextColor(0xFFD29922);
                        } else {
                            knockValues[i].setTextColor(0xFFFF4444);
                        }
                    } else {
                        knockValues[i].setText("--");
                        knockValues[i].setTextColor(0xFF555555);
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

                // F.P Fuel Pressure (PID 0x191 kPa → bar)
                Double fp = data.get(FP_PID);
                if (fp != null && bottomFpValue != null) {
                    bottomFpValue.setText(String.format(Locale.US, "%.1f", fp / 100.0));
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

    /**
     * 管理温度闪烁定时器的启停。
     */
    private void updateFlashState() {
        flashHandler.removeCallbacks(flashTick);
        if (ectFlashing || iatFlashing || afFlashing || kcFlashing) {
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

    /**
     * Boost 不对称滤波: 增压快响应, 泄压按工况动态调整
     * NORMAL: release=0.15 (正常响应)
     * TRANSIENT: release=0.05 (换挡时丝滑衰减)
     * DFCO: release=0.02 (泄压极慢, 避免 boost→vacuum 跳变)
     */
    private float boostFilter(float raw, float last, EngineStateTracker.State state) {
        if (raw > last) {
            return ema(raw, last, 0.6f);   // 快攻击 (不变)
        } else {
            float release;
            if (state == EngineStateTracker.State.DFCO) release = 0.02f;
            else if (state == EngineStateTracker.State.TRANSIENT) release = 0.05f;
            else release = 0.15f;
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
