package com.hondata.dash.data;

import android.os.Handler;
import android.os.Looper;

import java.util.Random;

/**
 * 模拟数据源 - 用于模拟器 UI 调试。
 * 生成逼真的传感器数据，方便在 Emulator 中调整界面。
 * 使用: MainActivity 中将 BluetoothSource 替换为 DemoSource 即可。
 */
public class DemoSource implements DataSource {

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Random rng = new Random();
    private Callback callback;
    private volatile boolean connected;
    private volatile boolean running;

    private float phase = 0;

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
        phase += 0.04f;

        // RPM: 800 ~ 7200 波动 (覆盖全部灯条阶段, 包括≥6400闪烁)
        double rpm = 800 + 5800 * (0.5 + 0.5 * Math.sin(phase)) + rng.nextInt(100);
        d.put(0x100, rpm);
        d.put(0x101, rpm / 65.0);                 // Speed km/h
        d.put(0x102, 3);                           // Gear

        // 压力
        double mapVal = rpm > 3000 ? 120 + (rpm - 3000) * 0.04 + rng.nextDouble() * 30 : 40 + rng.nextDouble() * 60;
        d.put(0x110, mapVal);                       // MAP kPa
        d.put(0x112, 20 + rng.nextDouble() * 180);  // AFM g/s
        double boost = Math.max(0, (rpm - 2000) * 0.08 + rng.nextDouble() * 8);
        d.put(0x114, boost);                        // Boost kPa
        d.put(0x170, 101 + rng.nextDouble());       // 大气压力

        // 节气门
        d.put(0x120, 20 + rng.nextDouble() * 55);   // TP Pedal %
        d.put(0x122, 18 + rng.nextDouble() * 50);   // TP Plate %

        // 温度
        d.put(0x150, 30 + 6 * Math.sin(phase * 0.3) + rng.nextDouble()); // IAT 1
        d.put(0x151, 32 + 5 * Math.sin(phase * 0.3) + rng.nextDouble()); // IAT 2
        d.put(0x160, 85 + 4 * Math.sin(phase * 0.1) + rng.nextDouble()); // ECT
        d.put(0x161, 86 + 3 * Math.sin(phase * 0.1) + rng.nextDouble()); // ECT 2

        // 点火 (确保始终有小数)
        d.put(0x140, 12.5 + rng.nextDouble() * 22);   // Ignition °

        // 喷油
        d.put(0x130, 2 + rng.nextDouble() * 6);      // Injector ms
        d.put(0x132, 20 + rng.nextDouble() * 60);    // Inj Duty %

        // 空燃比 (Lambda)
        double lambda = rpm > 4000 ? (0.78 + rng.nextDouble() * 0.10) : (0.97 + rng.nextDouble() * 0.06);
        d.put(0x320, lambda);                          // Lambda (MainActivity 会 ×14.7)
        d.put(0x329, lambda + 0.01 * rng.nextDouble());  // Wideband Lambda
        d.put(0x330, -5 + rng.nextDouble() * 10);    // STFT %
        d.put(0x332, -2.5 + rng.nextDouble() * 5);   // LTFT %

        // 电气 (确保始终有小数)
        d.put(0x180, 13.1 + rng.nextDouble() * 0.8); // Battery V

        // 负荷
        d.put(0x116, 30 + rng.nextDouble() * 60);    // Air Charge %

        // 爆震 (PID 与 MainActivity 一致)
        d.put(0x412, rng.nextDouble() * 50);            // Knock Control % (K.C)
        d.put(0x410, rng.nextDouble() * 2);             // Knock Retard ° (K.R)
        d.put(0x411, 2 + rng.nextDouble() * 8);         // Knock Limit ° (K.L)
        d.put(0x421, (double) rng.nextInt(3));          // Knock CYL1
        d.put(0x422, (double) rng.nextInt(3));          // Knock CYL2
        d.put(0x423, (double) rng.nextInt(3));          // Knock CYL3
        d.put(0x424, (double) rng.nextInt(3));          // Knock CYL4

        // 燃油
        d.put(0xB03, 85);                            // Ethanol %
        d.put(0xB04, 45 + rng.nextDouble() * 10);     // Fuel Level %
        d.put(0x191, 200 + rng.nextDouble() * 200);  // DI FP kPa
        d.put(0x192, 30 + rng.nextDouble() * 40);    // Fuel Duty %

        // 涡轮
        d.put(0x1A0, 40 + rng.nextDouble() * 50);    // WG CMD %
        d.put(0x1A1, 38 + rng.nextDouble() * 48);    // WG Pos %

        // 牵引力控制 / 轮速差 (模拟前驱车)
        double frontSlip = rng.nextDouble() < 0.08 ? rng.nextDouble() * 18 : rng.nextDouble() * 0.5;
        d.put(0x710, frontSlip + rng.nextDouble() * 2);  // FL slip %
        d.put(0x711, frontSlip + rng.nextDouble() * 2);  // FR slip %
        d.put(0x712, rng.nextDouble() * 0.3);             // RL slip %
        d.put(0x713, rng.nextDouble() * 0.3);             // RR slip %

        return d;
    }
}
