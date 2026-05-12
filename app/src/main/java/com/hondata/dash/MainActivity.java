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
 * 主界面 - 硬核科技风格车载仪表盘。
 *
 * 4x2 HUD 网格 (每个卡片带刻度进度条):
 *   乙醇含量 | 水温    | 进气温度 | 电压
 *   涡轮压力 | 点火角度 | 空燃比   | 长期燃油调整
 *
 * 底部: 爆震控制值 + CYL1-4 + 四轮图标 (左3/4) | 辅助数据 (右1/4)
 * 页脚: REC | LOG | ECU | Time
 */
public class MainActivity extends Activity implements DataSource.Callback {

    private static final boolean USE_DEMO = true;

    private DataSource dataSource;
    private TextView statusText;
    private TextView sourceName;
    private TextView timeText;

    // 主卡片 (4x2 = 8个)
    private final TextView[] labelCnViews = new TextView[8];
    private final TextView[] labelEnViews = new TextView[8];
    private final TextView[] valueViews = new TextView[8];
    private final TextView[] unitViews = new TextView[8];
    private final ScaleBarView[] scaleBars = new ScaleBarView[8];

    // 爆震缸 (4个)
    private final TextView[] knockValues = new TextView[4];
    private final ScaleBarView[] knockBars = new ScaleBarView[4];

    // 爆震控制值 (底部)
    private TextView knockRetValue;
    private ScaleBarView knockRetBar;

    // 四轮图标
    private WheelView wheelView;

    // 底部右侧文本
    private TextView throttleValue;
    private TextView mapTextValue;
    private TextView afrTextValue;
    private TextView loadTextValue;

    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    // ===== 卡片配置 =====
    // 第2行第3张卡片 (index 6): 原KNOCK RETARD → 现在改为 AFR 空燃比
    private static final int[] CARD_PIDS = {
        0xB03, 0x160, 0x150, 0x180,   // 第1行
        0x114, 0x140, 0x320, 0x332    // 第2行 (card6=AFR)
    };

    private static final String[] CARD_CN = {
        "乙醇含量", "水温", "进气温度", "电压",
        "涡轮压力", "点火角度", "空燃比", "长期燃油调整"
    };

    private static final String[] CARD_EN = {
        "ETHANOL", "WATER TEMP", "IAT", "VOLTAGE",
        "BOOST", "IGNITION ADV", "AIR FUEL RATIO", "LT FUEL TRIM"
    };

    private static final String[] CARD_FMT = {
        "%.0f", "%.0f", "%.0f", "%.1f",
        "%.2f", "%.1f", "%.1f", "%+.1f"
    };

    private static final String[] CARD_UNIT = {
        "%", "\u00B0C", "\u00B0C", "V",
        "BAR", "\u00B0", "", "%"
    };

    // 爆震缸 PID
    private static final int[] KNOCK_PIDS = {0x604, 0x605, 0x606, 0x607};

    // 轮速差 PID: FL, FR, RL, RR
    private static final int[] WHEEL_SLIP_PIDS = {0x710, 0x711, 0x712, 0x713};

    // 时钟更新
    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            if (timeText != null) {
                timeText.setText(timeFmt.format(new Date()));
            }
            clockHandler.postDelayed(this, 1000);
        }
    };

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
        timeText = (TextView) findViewById(R.id.timeText);

        // 初始化 8 个主卡片
        int[] cardIds = {
            R.id.card0, R.id.card1, R.id.card2, R.id.card3,
            R.id.card4, R.id.card5, R.id.card6, R.id.card7
        };

        for (int i = 0; i < cardIds.length; i++) {
            View card = findViewById(cardIds[i]);

            labelCnViews[i] = (TextView) card.findViewById(R.id.labelCn);
            labelEnViews[i] = (TextView) card.findViewById(R.id.labelEn);
            valueViews[i] = (TextView) card.findViewById(R.id.value);
            unitViews[i] = (TextView) card.findViewById(R.id.unit);
            scaleBars[i] = (ScaleBarView) card.findViewById(R.id.scaleBar);

            if (labelCnViews[i] != null) labelCnViews[i].setText(CARD_CN[i]);
            if (labelEnViews[i] != null) labelEnViews[i].setText(CARD_EN[i]);
            if (unitViews[i] != null) unitViews[i].setText(CARD_UNIT[i]);

            configureScaleBar(i);
        }

        // 初始化底部: 爆震控制值
        knockRetValue = (TextView) findViewById(R.id.knockRetValue);
        knockRetBar = (ScaleBarView) findViewById(R.id.knockRetBar);
        if (knockRetBar != null) {
            knockRetBar.setRange(-15, 5);
            knockRetBar.setTicks(
                new float[]{-15, -10, -5, 0, 5},
                new String[]{"", "", "", "0", ""});
            knockRetBar.addZone(-15, 0, 0xFF3FB950);
            knockRetBar.addZone(0, 5, 0xFF3FB950);
            knockRetBar.setAnchor(0);
            knockRetBar.setShowLabels(false);
        }

        // 初始化 4 个爆震缸
        int[] knockIds = {R.id.knock0, R.id.knock1, R.id.knock2, R.id.knock3};
        String[] knockLabels = {"CYL 1", "CYL 2", "CYL 3", "CYL 4"};

        for (int i = 0; i < knockIds.length; i++) {
            View k = findViewById(knockIds[i]);
            knockValues[i] = (TextView) k.findViewById(R.id.knockValue);
            knockBars[i] = (ScaleBarView) k.findViewById(R.id.knockBar);

            TextView kl = (TextView) k.findViewById(R.id.knockLabel);
            if (kl != null) kl.setText(knockLabels[i]);

            knockBars[i].setRange(0, 20);
            knockBars[i].setAnchor(0);
            knockBars[i].setShowLabels(false);
        }

        // 四轮图标
        wheelView = (WheelView) findViewById(R.id.wheelView);

        // 底部右侧文本
        throttleValue = (TextView) findViewById(R.id.throttleValue);
        mapTextValue = (TextView) findViewById(R.id.mapValue);
        afrTextValue = (TextView) findViewById(R.id.afrValue);
        loadTextValue = (TextView) findViewById(R.id.loadValue);

        // 启动时钟
        clockHandler.post(clockTick);

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
            case 0: // 乙醇含量: 0-100%, 绿色
                bar.setRange(0, 100);
                bar.setTicks(
                    new float[]{0, 50, 100},
                    new String[]{"0", "50", "100"});
                bar.addZone(0, 100, 0xFF3FB950);
                bar.setAnchor(0);
                break;

            case 1: // 水温: 40-120C, 蓝<100, 红>100
                bar.setRange(40, 120);
                bar.setTicks(
                    new float[]{40, 60, 80, 100, 120},
                    new String[]{"40", "60", "80", "100", "120"});
                bar.addZone(40, 100, 0xFF00D8FF);
                bar.addZone(100, 120, 0xFFFF4444);
                bar.setAnchor(40);
                break;

            case 2: // 进气温度: 20-100C, 蓝色
                bar.setRange(20, 100);
                bar.setTicks(
                    new float[]{20, 40, 60, 80, 100},
                    new String[]{"20", "40", "60", "80", "100"});
                bar.addZone(20, 100, 0xFF00D8FF);
                bar.setAnchor(20);
                break;

            case 3: // 电压: 10-18V, 蓝色
                bar.setRange(10, 18);
                bar.setTicks(
                    new float[]{10, 12, 14, 16, 18},
                    new String[]{"10", "12", "14", "16", "18"});
                bar.addZone(10, 18, 0xFF00D8FF);
                bar.setAnchor(10);
                break;

            case 4: // 涡轮压力: -1到2 BAR
                bar.setRange(-1, 2);
                bar.setTicks(
                    new float[]{-1, 0, 0.5f, 1, 1.5f, 2},
                    new String[]{"-1", "0", ".5", "1", "1.5", "2"});
                bar.addZone(-1, 0, 0xFF00D8FF);
                bar.addZone(0, 1, 0xFF0088FF);
                bar.addZone(1, 2, 0xFFFF4444);
                bar.setAnchor(0);
                break;

            case 5: // 点火角度: -10到40
                bar.setRange(-10, 40);
                bar.setTicks(
                    new float[]{-10, 0, 10, 20, 30, 40},
                    new String[]{"-10", "0", "10", "20", "30", "40"});
                bar.addZone(-10, 40, 0xFF00D8FF);
                bar.setAnchor(-10);
                break;

            case 6: // 空燃比: 10-18, 富油绿/稀油红
                bar.setRange(10, 18);
                bar.setTicks(
                    new float[]{10, 12, 14, 14.7f, 16, 18},
                    new String[]{"10", "12", "14", "14.7", "16", "18"});
                bar.addZone(10, 14.7f, 0xFF3FB950);  // 富油 (安全)
                bar.addZone(14.7f, 18, 0xFFFF4444);   // 稀油 (危险)
                bar.setAnchor(10);
                break;

            case 7: // 长期燃油调整: -25到+25%, 负红正蓝
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
            // 防御性重新应用全屏 (车载机休眠/唤醒后状态栏可能恢复)
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
        clockHandler.removeCallbacks(clockTick);
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
                    if (valueViews[i] == null) continue;

                    int pid = CARD_PIDS[i];
                    String fmt = CARD_FMT[i];
                    Double raw = data.get(pid);

                    if (raw != null) {
                        double val = raw;

                        // 涡轮压力: kPa -> BAR
                        if (i == 4) {
                            val = raw / 100.0;
                        }

                        valueViews[i].setText(String.format(Locale.US, fmt, val));

                        if (scaleBars[i] != null) {
                            scaleBars[i].setValue((float) val);
                        }

                        // 乙醇含量: 数值用亮绿色
                        if (i == 0) {
                            valueViews[i].setTextColor(0xFF3FB950);
                        }
                    } else {
                        valueViews[i].setText("--");
                        if (scaleBars[i] != null) {
                            scaleBars[i].setValue(Float.NaN);
                        }
                    }
                }

                // 更新底部: 爆震控制值 (KNOCK RETARD)
                Double kr = data.get(0x603);
                if (kr != null && knockRetValue != null) {
                    knockRetValue.setText(String.format(Locale.US, "%.1f\u00B0", kr));
                    if (knockRetBar != null) {
                        knockRetBar.setValue(kr.floatValue());
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

                        if (knockBars[i] != null) {
                            knockBars[i].setValue((float) knock);
                        }
                    } else {
                        knockValues[i].setText("--");
                        knockValues[i].setTextColor(0xFF555555);
                    }
                }

                // 更新四轮图标
                if (wheelView != null) {
                    float fl = getFloat(data, WHEEL_SLIP_PIDS[0]);
                    float fr = getFloat(data, WHEEL_SLIP_PIDS[1]);
                    float rl = getFloat(data, WHEEL_SLIP_PIDS[2]);
                    float rr = getFloat(data, WHEEL_SLIP_PIDS[3]);
                    wheelView.setSlip(fl, fr, rl, rr);
                }

                // 更新底部右侧文本
                Double tp = data.get(0x122);
                if (tp != null) {
                    throttleValue.setText(String.format(Locale.US, "%.1f%%", tp));
                }

                Double map = data.get(0x110);
                if (map != null) {
                    mapTextValue.setText(String.format(Locale.US, "%.0f kPa", map));
                }

                Double afr = data.get(0x320);
                if (afr != null) {
                    afrTextValue.setText(String.format(Locale.US, "%.1f", afr));
                }

                Double load = data.get(0x116);
                if (load != null) {
                    loadTextValue.setText(String.format(Locale.US, "%.0f%%", load));
                }
            }
        });
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
