package com.hondata.dash.data;

import android.os.Handler;
import android.os.Looper;

import java.util.Random;

/**
 * 模拟数据源 - 用于模拟器 UI 调试。
 * 生成覆盖全部 ScaleBar 量程的传感器数据。
 * 6 阶段状态循环: IDLE → NORMAL → WOT → TRANSIENT → DFCO → EXTREME → 循环
 */
public class DemoSource implements DataSource {

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Random rng = new Random();
    private Callback callback;
    private volatile boolean connected;
    private volatile boolean running;

    private long startTime;

    // 状态循环周期 (ms)
    private static final long CYCLE = 36000;
    // 阶段时间节点 (ms)
    private static final long T_IDLE_END    = 5000;
    private static final long T_NORMAL_END  = 8000;
    private static final long T_WOT_END     = 15000;
    private static final long T_TRANS_END   = 18000;
    private static final long T_DFCO_END    = 23000;
    private static final long T_EXTREME_END = 32000;
    // EXTREME 阶段: 扫满全部 ScaleBar 量程的极端值

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (callback != null) callback.onDataReceived(generate());
            uiHandler.postDelayed(this, 50); // 20Hz
        }
    };

    @Override public String getName() { return "Demo (模拟)"; }
    @Override public void setCallback(Callback cb) { this.callback = cb; }
    @Override public boolean isConnected() { return connected; }

    @Override
    public void connect(String address) {
        connected = true;
        startTime = System.currentTimeMillis();
        uiHandler.post(new Runnable() {
            @Override public void run() {
                if (callback != null) callback.onConnected();
            }
        });
    }

    @Override
    public void disconnect() {
        running = false;
        connected = false;
        uiHandler.removeCallbacks(tick);
        uiHandler.post(new Runnable() {
            @Override public void run() {
                if (callback != null) callback.onDisconnected();
            }
        });
    }

    @Override
    public void startPolling() {
        running = true;
        uiHandler.post(tick);
    }

    @Override
    public void stopPolling() {
        running = false;
        uiHandler.removeCallbacks(tick);
    }

    private SensorData generate() {
        SensorData d = new SensorData();
        d.timestamp = System.currentTimeMillis();

        long elapsed = (System.currentTimeMillis() - startTime) % CYCLE;
        int phase;
        if (elapsed < T_IDLE_END)          phase = 0; // IDLE
        else if (elapsed < T_NORMAL_END)   phase = 1; // NORMAL 起步
        else if (elapsed < T_WOT_END)      phase = 2; // WOT 全油门
        else if (elapsed < T_TRANS_END)    phase = 3; // TRANSIENT
        else if (elapsed < T_DFCO_END)     phase = 4; // DFCO
        else if (elapsed < T_EXTREME_END)  phase = 5; // EXTREME 量程扫描
        else                               phase = 6; // NORMAL 巡航

        float rpm = 0, speed = 0, tpPlate = 0, tpPedal = 0, mapVal = 0, inj = 0;
        float lambda = 0, stft = 0, ltft = 0, ign = 0;
        float ect = 0, iat = 0, ethanol = 0, bat = 0, wg = 0, fp = 0;
        float knockCtrl = 0, knockRet = 0, knockLim = 0;
        int[] knockCyl = new int[4];

        switch (phase) {
            case 0: // IDLE: 怠速
                rpm = 750 + rng.nextInt(30);
                speed = 0;
                tpPlate = 0.5f;
                tpPedal = 0;
                mapVal = 35 + rng.nextInt(5);
                inj = 1.5f + rng.nextFloat() * 0.5f;
                lambda = 0.98f + rng.nextFloat() * 0.04f;
                stft = -1 + rng.nextFloat() * 2;
                ltft = -3 + rng.nextFloat() * 2;
                ign = 22 + rng.nextFloat() * 3;
                ect = 85 + rng.nextFloat() * 3;
                iat = 30 + rng.nextFloat() * 2;
                ethanol = 30;
                wg = rng.nextFloat() * 2;
                fp = 350 + rng.nextFloat() * 20;
                knockCtrl = rng.nextFloat() * 5;
                knockRet = rng.nextFloat() * 0.3f;
                knockLim = 5 + rng.nextFloat() * 3;
                knockCyl[0] = 0; knockCyl[1] = 0; knockCyl[2] = 0; knockCyl[3] = 0;
                break;

            case 1: // NORMAL 起步
                float t1 = (elapsed - T_IDLE_END) / (float)(T_NORMAL_END - T_IDLE_END);
                rpm = 800 + t1 * 3000 + rng.nextInt(100);
                speed = t1 * 40 + rng.nextFloat() * 2;
                tpPlate = 15 + t1 * 30 + rng.nextFloat() * 3;
                tpPedal = 20 + t1 * 25;
                mapVal = 50 + t1 * 60 + rng.nextFloat() * 10;
                inj = 2 + t1 * 3 + rng.nextFloat() * 0.5f;
                lambda = 0.95f + rng.nextFloat() * 0.06f;
                stft = -3 + rng.nextFloat() * 6;
                ltft = -4 + rng.nextFloat() * 3;
                ign = 18 + rng.nextFloat() * 8;
                ect = 86 + rng.nextFloat() * 4;
                iat = 33 + rng.nextFloat() * 3;
                ethanol = 30;
                wg = 10 + t1 * 20 + rng.nextFloat() * 5;
                fp = 380 + t1 * 60 + rng.nextFloat() * 10;
                knockCtrl = 5 + rng.nextFloat() * 15;
                knockRet = rng.nextFloat() * 1;
                knockLim = 6 + rng.nextFloat() * 4;
                knockCyl[0] = rng.nextInt(2); knockCyl[1] = rng.nextInt(2);
                knockCyl[2] = rng.nextInt(2); knockCyl[3] = rng.nextInt(2);
                break;

            case 2: // WOT 全油门
                float t2 = (elapsed - T_NORMAL_END) / (float)(T_WOT_END - T_NORMAL_END);
                rpm = 3800 + t2 * 3200 + rng.nextInt(150);
                speed = 40 + t2 * 120 + rng.nextFloat() * 5;
                tpPlate = 85 + rng.nextFloat() * 10;
                tpPedal = 90 + rng.nextFloat() * 8;
                mapVal = 180 + t2 * 80 + rng.nextFloat() * 15;
                inj = 5 + t2 * 3 + rng.nextFloat() * 0.3f;
                lambda = 0.78f + rng.nextFloat() * 0.08f;
                stft = -5 + rng.nextFloat() * 3;
                ltft = -8 + rng.nextFloat() * 4;
                ign = 5 + t2 * 5 + rng.nextFloat() * 2;
                ect = 88 + t2 * 10 + rng.nextFloat() * 2;
                iat = 38 + t2 * 15 + rng.nextFloat() * 3;
                ethanol = 30;
                wg = 50 + t2 * 30 + rng.nextFloat() * 5;
                fp = 500 + t2 * 100 + rng.nextFloat() * 20;
                knockCtrl = 20 + t2 * 30 + rng.nextFloat() * 10;
                knockRet = rng.nextFloat() * 3;
                knockLim = 8 + rng.nextFloat() * 6;
                knockCyl[0] = rng.nextInt(3); knockCyl[1] = rng.nextInt(3);
                knockCyl[2] = rng.nextInt(3); knockCyl[3] = rng.nextInt(3);
                break;

            case 3: // TRANSIENT
                float t3 = (elapsed - T_WOT_END) / (float)(T_TRANS_END - T_WOT_END);
                rpm = 5500 - t3 * 2500 + rng.nextInt(300);
                speed = 130 - t3 * 40 + rng.nextFloat() * 5;
                tpPlate = 30 + 60 * (0.5f + 0.5f * (float)Math.sin(t3 * 20));
                tpPedal = tpPlate + rng.nextFloat() * 5;
                mapVal = 80 + 80 * (0.5f + 0.5f * (float)Math.sin(t3 * 15)) + rng.nextFloat() * 10;
                inj = 3 + rng.nextFloat() * 4;
                lambda = 0.85f + rng.nextFloat() * 0.15f;
                stft = -8 + rng.nextFloat() * 16;
                ltft = -6 + rng.nextFloat() * 4;
                ign = 10 + rng.nextFloat() * 12;
                ect = 92 + rng.nextFloat() * 5;
                iat = 45 + rng.nextFloat() * 8;
                ethanol = 30;
                wg = 30 + rng.nextFloat() * 30;
                fp = 400 + rng.nextFloat() * 150;
                knockCtrl = 15 + rng.nextFloat() * 25;
                knockRet = rng.nextFloat() * 2;
                knockLim = 6 + rng.nextFloat() * 5;
                knockCyl[0] = rng.nextInt(2); knockCyl[1] = rng.nextInt(2);
                knockCyl[2] = rng.nextInt(2); knockCyl[3] = rng.nextInt(2);
                break;

            case 4: // DFCO
                float t4 = (elapsed - T_TRANS_END) / (float)(T_DFCO_END - T_TRANS_END);
                rpm = 3500 - t4 * 1500 + rng.nextInt(50);
                speed = 70 - t4 * 30 + rng.nextFloat() * 3;
                tpPlate = 0.5f;
                tpPedal = 0;
                mapVal = 30 + rng.nextInt(5);
                inj = 0;
                lambda = 1.0f + rng.nextFloat() * 0.5f;
                stft = 0;
                ltft = -4 + rng.nextFloat() * 2;
                ign = 35 + rng.nextFloat() * 5;
                ect = 90 + rng.nextFloat() * 3;
                iat = 35 + rng.nextFloat() * 3;
                ethanol = 30;
                wg = rng.nextFloat() * 2;
                fp = 300 + rng.nextFloat() * 30;
                knockCtrl = rng.nextFloat() * 3;
                knockRet = 0;
                knockLim = 5 + rng.nextFloat() * 3;
                knockCyl[0] = 0; knockCyl[1] = 0; knockCyl[2] = 0; knockCyl[3] = 0;
                break;

            case 5: // EXTREME: 扫满全部 ScaleBar 量程
                float t5 = (elapsed - T_DFCO_END) / (float)(T_EXTREME_END - T_DFCO_END);
                // 正弦波扫描, 覆盖各参数 ScaleBar 全量程
                float sweep = (float)Math.sin(t5 * Math.PI * 4); // -1 ~ +1, 4个周期

                // Ethanol: 0~100 (ScaleBar 0-100)
                ethanol = 50 + 50 * sweep;
                // ECT: 40~120 (ScaleBar 40-120)
                ect = 80 + 40 * sweep;
                // IAT: 20~100 (ScaleBar 20-100)
                iat = 60 + 40 * sweep;
                // MAP kPa: 要覆盖 Boost -1.0~2.0 bar = MAP 0~300 kPa (相对Baro 101)
                mapVal = 150 + 150 * sweep; // 0 ~ 300 kPa
                // A/F Lambda: 9~18 AFR = Lambda 0.61~1.22
                lambda = 0.915f + 0.306f * sweep;
                // IGN: -40~40 (ScaleBar -40~40)
                ign = 40 * sweep;
                // S.TRIM: -25~+25 (ScaleBar -25~25)
                stft = 25 * sweep;
                // L.TRIM: -25~+25 (ScaleBar -25~25)
                ltft = 25 * sweep;

                rpm = 3000 + 3000 * (0.5f + 0.5f * sweep);
                speed = 60 + 40 * sweep;
                tpPlate = 50 + 40 * sweep;
                tpPedal = tpPlate;
                inj = 3 + 3 * (0.5f + 0.5f * sweep);
                wg = 30 + 30 * (0.5f + 0.5f * sweep);
                fp = 350 + 200 * (0.5f + 0.5f * sweep);
                bat = 13.5f;
                knockCtrl = 30 * (0.5f + 0.5f * sweep);
                knockRet = 3 * (0.5f + 0.5f * sweep);
                knockLim = 10 + 5 * (0.5f + 0.5f * sweep);
                knockCyl[0] = rng.nextInt(3); knockCyl[1] = rng.nextInt(3);
                knockCyl[2] = rng.nextInt(3); knockCyl[3] = rng.nextInt(3);
                break;

            default: // NORMAL 巡航
                rpm = 2500 + rng.nextInt(200);
                speed = 55 + rng.nextFloat() * 5;
                tpPlate = 20 + rng.nextFloat() * 8;
                tpPedal = 25 + rng.nextFloat() * 8;
                mapVal = 70 + rng.nextFloat() * 15;
                inj = 3 + rng.nextFloat() * 1;
                lambda = 0.95f + rng.nextFloat() * 0.05f;
                stft = -2 + rng.nextFloat() * 4;
                ltft = -3 + rng.nextFloat() * 2;
                ign = 20 + rng.nextFloat() * 5;
                ect = 86 + rng.nextFloat() * 4;
                iat = 32 + rng.nextFloat() * 3;
                ethanol = 30;
                wg = 15 + rng.nextFloat() * 10;
                fp = 380 + rng.nextFloat() * 30;
                bat = 13.1f + rng.nextFloat() * 0.8f;
                knockCtrl = 5 + rng.nextFloat() * 10;
                knockRet = rng.nextFloat() * 0.5f;
                knockLim = 6 + rng.nextFloat() * 4;
                knockCyl[0] = rng.nextInt(2); knockCyl[1] = rng.nextInt(2);
                knockCyl[2] = rng.nextInt(2); knockCyl[3] = rng.nextInt(2);
                break;
        }

        // 写入 SensorData
        d.put(0x100, rpm);
        d.put(0x101, speed);
        d.put(0x102, 3);
        d.put(0x110, mapVal);
        d.put(0x112, 20 + rng.nextDouble() * 80);
        d.put(0x114, Math.max(0, mapVal - 101));
        d.put(0x170, 101.0 + rng.nextDouble());
        d.put(0x120, tpPedal);
        d.put(0x122, tpPlate);
        d.put(0x150, iat - 1);
        d.put(0x151, iat);
        d.put(0x160, ect);
        d.put(0x161, ect + 1);
        d.put(0x140, ign);
        d.put(0x130, inj);
        d.put(0x132, 20 + rng.nextDouble() * 40);
        d.put(0x320, lambda);
        d.put(0x329, lambda + 0.01 * rng.nextDouble());
        d.put(0x330, stft);
        d.put(0x332, ltft);
        d.put(0x180, bat);
        d.put(0x116, 30 + rng.nextDouble() * 60);
        d.put(0x412, knockCtrl);
        d.put(0x410, knockRet);
        d.put(0x411, knockLim);
        d.put(0x421, (double) knockCyl[0]);
        d.put(0x422, (double) knockCyl[1]);
        d.put(0x423, (double) knockCyl[2]);
        d.put(0x424, (double) knockCyl[3]);
        d.put(0xB03, ethanol);
        d.put(0xB04, 45 + rng.nextDouble() * 10);
        d.put(0x191, fp);
        d.put(0x192, 30 + rng.nextDouble() * 40);
        d.put(0x1A0, wg);
        d.put(0x1A1, wg - 2 + rng.nextDouble() * 4);

        return d;
    }
}
