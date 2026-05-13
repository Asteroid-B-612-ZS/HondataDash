package com.hondata.dash;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
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

    private static final boolean USE_DEMO = true;

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

    // 四轮图标
    private WheelView wheelView;

    // 彩虹转速灯条
    private ShiftLightView shiftLight;

    // 底部数据文本
    private TextView bottomTrimValue;
    private TextView bottomAfmValue;
    private TextView bottomFuelValue;

    // ===== 卡片配置 (BAT和MAP互换位置) =====
    private static final int[] CARD_PIDS = {
        0xB03, 0x161, 0x151, 0x110,   // 第1行: Ethanol, ECT2, IAT2, MAP
        0x180, 0x140, 0x320, 0x332    // 第2行: BAT, IGN, A/F, LTFT
    };

    private static final String[] CARD_EN = {
        "Ethanol", "ECT", "IAT", "MAP",
        "BAT", "IGN", "A/F", "L.TRIM"
    };

    private static final String[] CARD_FMT = {
        "%.0f", "%.0f", "%.0f", "%.0f",
        "%.1f", "%.1f", "%.1f", "%+.1f"
    };

    private static final String[] CARD_UNIT = {
        "%", "\u00B0C", "\u00B0C", "kPa",
        "V", "\u00B0", "", "%"
    };

    // 爆震缸 PID
    private static final int[] KNOCK_PIDS = {0x604, 0x605, 0x606, 0x607};

    // 爆震控制 PID (Knock Control %)
    private static final int KNOCK_CTRL_PID = 0x601;

    // 轮速差 PID: FL, FR, RL, RR
    private static final int[] WHEEL_SLIP_PIDS = {0x710, 0x711, 0x712, 0x713};

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
            if (unitViews[i] != null) unitViews[i].setText(CARD_UNIT[i]);

            // 第1行(0-3): 无小数, 字体放大; 第2行(4-7): 有小数, 字体缩小
            if (i < 4) {
                if (valueIntViews[i] != null) valueIntViews[i].setTextSize(75);
                if (valueDecViews[i] != null) valueDecViews[i].setTextSize(75);
            } else {
                if (valueIntViews[i] != null) valueIntViews[i].setTextSize(60);
                if (valueDecViews[i] != null) valueDecViews[i].setTextSize(60);
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

        // 四轮图标
        wheelView = (WheelView) findViewById(R.id.wheelView);

        // 彩虹转速灯条
        shiftLight = (ShiftLightView) findViewById(R.id.shiftLight);

        // 底部数据文本
        bottomTrimValue = (TextView) findViewById(R.id.bottomTrimValue);
        bottomAfmValue = (TextView) findViewById(R.id.bottomAfmValue);
        bottomFuelValue = (TextView) findViewById(R.id.bottomFuelValue);

        // 数据源
        if (USE_DEMO) {
            dataSource = new DemoSource();
        } else {
            dataSource = new BluetoothSource();
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
            case 0: // Ethanol: 0-100%, 绿色
                bar.setRange(0, 100);
                bar.setTicks(
                    new float[]{0, 50, 100},
                    new String[]{"0", "50", "100"});
                bar.addZone(0, 100, 0xFF3FB950);
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

            case 3: // MAP: -30到250 kPa, 负压区间加大
                bar.setRange(-30, 250);
                bar.setTicks(
                    new float[]{-30, 0, 50, 100, 150, 200, 250},
                    new String[]{"-30", "0", "50", "100", "150", "200", "250"});
                bar.addZone(-30, 0, 0xFF00D8FF);
                bar.addZone(0, 100, 0xFF0088FF);
                bar.addZone(100, 250, 0xFFFF4444);
                bar.setAnchor(0);
                break;

            case 4: // BAT: 10-18V, 蓝色
                bar.setRange(10, 18);
                bar.setTicks(
                    new float[]{10, 12, 14, 16, 18},
                    new String[]{"10", "12", "14", "16", "18"});
                bar.addZone(10, 18, 0xFF00D8FF);
                bar.setAnchor(10);
                break;

            case 5: // IGN: -40到40
                bar.setRange(-40, 40);
                bar.setTicks(
                    new float[]{-40, -20, 0, 20, 40},
                    new String[]{"-40", "-20", "0", "20", "40"});
                bar.addZone(-40, 40, 0xFF00D8FF);
                bar.setAnchor(0);
                break;

            case 6: // A/F: 9-18, 14.7居中
                bar.setRange(9, 18);
                bar.setTicks(
                    new float[]{9, 14.7f, 18},
                    new String[]{"9", "14.7", "18"});
                bar.addZone(9, 14.7f, 0xFF3FB950);  // 富油 (安全)
                bar.addZone(14.7f, 18, 0xFFFF4444);   // 稀油 (危险)
                bar.setAnchor(14.7f);
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

    @Override
    public void onDataReceived(final SensorData data) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                // 更新 8 个主卡片
                for (int i = 0; i < 8; i++) {
                    if (valueIntViews[i] == null) continue;

                    int pid = CARD_PIDS[i];
                    String fmt = CARD_FMT[i];
                    Double raw = data.get(pid);

                    if (raw != null) {
                        double val = raw;
                        float fVal = (float) val;

                        // 格式化并拆分整数/小数部分
                        String formatted = String.format(Locale.US, fmt, val);
                        splitSetValue(i, formatted);

                        if (scaleBars[i] != null) {
                            scaleBars[i].setValue(fVal);
                        }

                        // MAX / MIN 追踪
                        if (!hasValue[i]) {
                            maxTrack[i] = fVal;
                            minTrack[i] = fVal;
                            hasValue[i] = true;
                        } else {
                            if (fVal > maxTrack[i]) maxTrack[i] = fVal;
                            if (fVal < minTrack[i]) minTrack[i] = fVal;
                        }

                        if (maxValueViews[i] != null) {
                            maxValueViews[i].setText(String.format(Locale.US,
                                fmt, maxTrack[i]));
                        }
                        if (minValueViews[i] != null) {
                            minValueViews[i].setText(String.format(Locale.US,
                                fmt, minTrack[i]));
                        }

                        // Ethanol: 整数部分前加E标识, 亮绿色
                        if (i == 0) {
                            valueIntViews[i].setText("E" + formatted);
                            valueIntViews[i].setTextColor(0xFF3FB950);
                            if (valueDecViews[i] != null) {
                                valueDecViews[i].setTextColor(0xFF3FB950);
                            }
                        }
                    } else {
                        valueIntViews[i].setText("--");
                        if (valueDecViews[i] != null) valueDecViews[i].setText("");
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

                    if (pct < 30) {
                        knockRetValue.setTextColor(0xFF3FB950);
                    } else if (pct < 60) {
                        knockRetValue.setTextColor(0xFFD29922);
                    } else {
                        knockRetValue.setTextColor(0xFFFF4444);
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
                // S.TRIM 短期燃油调整 (PID 0x330)
                Double stft = data.get(0x330);
                if (stft != null && bottomTrimValue != null) {
                    bottomTrimValue.setText(String.format(Locale.US, "%+.0f", stft));
                }

                // AFM (PID 0x112)
                Double afm = data.get(0x112);
                if (afm != null && bottomAfmValue != null) {
                    bottomAfmValue.setText(String.format(Locale.US, "%.0f", afm));
                }

                // FUEL 燃油剩余 (PID 0xB04)
                Double fuel = data.get(0xB04);
                if (fuel != null && bottomFuelValue != null) {
                    bottomFuelValue.setText(String.format(Locale.US, "%.0f", fuel));
                }

                // 更新四轮图标
                if (wheelView != null) {
                    float fl = getFloat(data, WHEEL_SLIP_PIDS[0]);
                    float fr = getFloat(data, WHEEL_SLIP_PIDS[1]);
                    float rl = getFloat(data, WHEEL_SLIP_PIDS[2]);
                    float rr = getFloat(data, WHEEL_SLIP_PIDS[3]);
                    wheelView.setSlip(fl, fr, rl, rr);
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
        String addr = BluetoothSource.findFlashPro();
        if (addr != null) {
            dataSource.connect(addr);
        } else {
            statusText.setText("未找到FlashPro");
            Toast.makeText(this, "请先在蓝牙设置中配对 FlashPro", Toast.LENGTH_LONG).show();
        }
    }
}
