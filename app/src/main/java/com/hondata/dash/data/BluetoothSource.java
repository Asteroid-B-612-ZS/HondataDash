package com.hondata.dash.data;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * 蓝牙 SPP 数据源 — V1.4 fullReset 重连策略。
 * 连接 Hondata FlashPro (MAC 硬编码)，断线后自动重连。
 *
 * 重连策略: fullReset (等同重启 App 数据层，UI 不退出)
 * - 完全停止旧 pollThread + 关闭 Socket
 * - 等待蓝牙栈释放 RFCOMM channel (1s)
 * - 新建 HondataProtocol (清零所有协议状态)
 * - 全新线程从零连接 (等同 App 启动流程)
 * - 首次重连 1s 后尝试，后续指数退避
 */
public class BluetoothSource implements DataSource {

    private static final String TAG = "BTSource";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int POLL_INTERVAL_MS = 20; // 50Hz
    private static final int READ_TIMEOUT_MS = 3000;
    private static final int RECONNECT_BASE_MS = 1000;
    private static final int RECONNECT_MAX_MS = 8000;
    private static final int BT_STACK_CLEANUP_MS = 1000; // 蓝牙栈释放 channel 等待时间

    // FlashPro MAC 地址 (硬编码)
    public static final String FLASHPRO_MAC = "XX:XX:XX:XX:XX:XX"; // 替换为你的 FlashPro MAC

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private HondataProtocol protocol;

    private Callback callback;
    private android.content.Context exportContext;
    private BluetoothSocket socket;
    private InputStream inputStream;
    private OutputStream outputStream;
    private Thread pollThread;
    private volatile boolean running;
    private volatile boolean connected;
    private volatile boolean intentionalDisconnect;
    private volatile boolean reconnecting; // V1.4: 标记重连中，防止重复触发

    public BluetoothSource() {
        protocol = new HondataProtocol();
    }

    @Override public String getName() { return "Bluetooth SPP"; }
    @Override public void setCallback(Callback cb) { this.callback = cb; }
    public void setExportContext(android.content.Context ctx) { this.exportContext = ctx; }
    @Override public boolean isConnected() { return connected; }

    @Override
    public void connect(final String address) {
        intentionalDisconnect = false;
        reconnecting = false;
        new Thread(new Runnable() {
            @Override
            public void run() {
                doConnect();
            }
        }).start();
    }

    /**
     * V1.4: fullReset — 断线后自动重连 (等同重启 App 数据层)
     *
     * 与 V1.3 autoReconnect 的区别:
     * 1. 完全销毁旧 pollThread (而非在同一线程内重连)
     * 2. 等待蓝牙栈释放 RFCOMM channel (1s)
     * 3. 全新线程执行连接 (等同 App 启动)
     *
     * 由 UI Handler 延迟 1s 后调用，期间显示 "重连中..."
     */
    public void fullReset() {
        if (reconnecting || intentionalDisconnect) return;
        reconnecting = true;

        Log.i(TAG, "fullReset: 开始重建数据层...");

        // 1. 完全停止旧线程
        running = false;
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }

        // 2. 关闭所有连接
        closeSocket();
        connected = false;

        // 3. 在后台线程执行: 等待蓝牙栈清理 → 重新连接
        Thread resetThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 等待蓝牙栈释放 RFCOMM channel
                    Log.i(TAG, "fullReset: 等待蓝牙栈清理 (" + BT_STACK_CLEANUP_MS + "ms)...");
                    Thread.sleep(BT_STACK_CLEANUP_MS);

                    if (intentionalDisconnect) {
                        Log.i(TAG, "fullReset: 用户主动断开，取消重连");
                        reconnecting = false;
                        return;
                    }

                    // 重建 Protocol
                    protocol = new HondataProtocol();
                    Log.i(TAG, "fullReset: Protocol 已重建");

                    // 通知 UI
                    uiHandler.post(new Runnable() {
                        @Override public void run() {
                            if (callback != null) callback.onError("重连中...");
                        }
                    });

                    // 全新连接 (等同 App 启动)
                    doConnect();

                    if (connected) {
                        Log.i(TAG, "fullReset: 重连成功!");
                        reconnecting = false;
                        // 自动开始轮询
                        startPolling();
                    } else {
                        Log.w(TAG, "fullReset: 首次重连失败，开始指数退避...");
                        reconnectWithBackoff(RECONNECT_BASE_MS);
                    }
                } catch (InterruptedException e) {
                    Log.i(TAG, "fullReset: 被中断，取消重连");
                    reconnecting = false;
                }
            }
        });
        resetThread.setDaemon(true);
        resetThread.start();
    }

    /**
     * 指数退避重连 (仅在 fullReset 首次失败后使用)
     */
    private void reconnectWithBackoff(int delay) {
        while (running || !intentionalDisconnect) {
            if (intentionalDisconnect) {
                reconnecting = false;
                return;
            }

            try {
                Log.i(TAG, "重连失败, " + (delay / 1000) + "s 后重试...");
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                reconnecting = false;
                return;
            }

            if (intentionalDisconnect) {
                reconnecting = false;
                return;
            }

            // 关闭残留连接
            closeSocket();
            connected = false;
            protocol = new HondataProtocol();

            // 通知 UI
            uiHandler.post(new Runnable() {
                @Override public void run() {
                    if (callback != null) callback.onError("重连中...");
                }
            });

            try {
                Log.i(TAG, "开始重连 (指数退避)...");
                doConnect();

                if (connected) {
                    Log.i(TAG, "重连成功!");
                    reconnecting = false;
                    startPolling();
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "重连异常: " + e.getMessage());
            }

            // 指数退避: 1s → 2s → 4s → 8s
            delay = Math.min(delay * 2, RECONNECT_MAX_MS);
        }
        reconnecting = false;
    }

    /**
     * 核心连接逻辑 (可被重连复用)
     */
    private void doConnect() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                postError("蓝牙未启用");
                return;
            }

            adapter.cancelDiscovery();
            BluetoothDevice device = adapter.getRemoteDevice(FLASHPRO_MAC);
            Log.i(TAG, "目标设备: " + device.getName() + " [" + FLASHPRO_MAC + "]");

            BluetoothSocket connectedSocket = null;

            // 方式1: 反射 channel 1
            connectedSocket = tryReflectChannel(device, 1);

            // 方式2: 不安全 SPP
            if (connectedSocket == null) {
                connectedSocket = tryInsecureSPP(device);
            }

            // 方式3: 标准 SPP
            if (connectedSocket == null) {
                connectedSocket = tryStandardSPP(device);
            }

            if (connectedSocket == null) {
                postError("连接失败");
                return;
            }
            socket = connectedSocket;

            Log.i(TAG, "蓝牙Socket连接成功");
            Thread.sleep(500);
            inputStream = socket.getInputStream();
            outputStream = socket.getOutputStream();
            connected = true;

            Log.i(TAG, "开始握手...");
            if (!handshake()) {
                disconnect();
                postError("协议握手失败");
                return;
            }
            Log.i(TAG, "握手成功! 传感器:" + protocol.getSensorCount()
                + " 帧长:" + protocol.getExpectedLength(0x35));

            uiHandler.post(new Runnable() {
                @Override public void run() {
                    if (callback != null) callback.onConnected();
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "连接异常", e);
            postError("连接失败: " + e.getMessage());
        }
    }

    // === 连接方式 ===

    private BluetoothSocket tryReflectChannel(BluetoothDevice device, int channel) {
        try {
            Log.i(TAG, "尝试反射 ch" + channel + "...");
            Method m = device.getClass().getMethod("createRfcommSocket", int.class);
            BluetoothSocket sock = (BluetoothSocket) m.invoke(device, channel);
            sock.connect();
            Log.i(TAG, "反射 ch" + channel + " 成功!");
            return sock;
        } catch (Exception e) {
            Log.w(TAG, "反射 ch" + channel + " 失败: " + e.getMessage());
            return null;
        }
    }

    private BluetoothSocket tryInsecureSPP(BluetoothDevice device) {
        try {
            Log.i(TAG, "尝试不安全SPP...");
            BluetoothSocket sock = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
            sock.connect();
            Log.i(TAG, "不安全SPP成功!");
            return sock;
        } catch (Exception e) {
            Log.w(TAG, "不安全SPP失败: " + e.getMessage());
            return null;
        }
    }

    private BluetoothSocket tryStandardSPP(BluetoothDevice device) {
        try {
            Log.i(TAG, "尝试标准SPP...");
            BluetoothSocket sock = device.createRfcommSocketToServiceRecord(SPP_UUID);
            sock.connect();
            Log.i(TAG, "标准SPP成功!");
            return sock;
        } catch (Exception e) {
            Log.w(TAG, "标准SPP失败: " + e.getMessage());
            return null;
        }
    }

    // === 协议握手 ===

    private boolean handshake() throws IOException {
        // 点火检测: 最多重试 10 次, 间隔 2 秒
        HondataProtocol.IgnitionResult ign = null;
        for (int attempt = 1; attempt <= 10; attempt++) {
            sendCommand(HondataProtocol.CMD_IGNITION);
            byte[] ignitionResp = readExact(6);

            ign = protocol.parseIgnition(ignitionResp);
            if (ign == null) return false;
            Log.i(TAG, "点火检测 #" + attempt + ": ignition=" + ign.ignitionOn);

            if (ign.ignitionOn) break;
            if (attempt < 10) {
                try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
            }
        }

        sendCommand(HondataProtocol.CMD_INIT);
        byte[] initResp = readExact(8);
        if (!protocol.parseInit(initResp)) return false;
        Log.i(TAG, "传感器数量: " + protocol.getSensorCount());

        sendCommand(HondataProtocol.CMD_SENSOR_DEF);
        int defLen = protocol.getSensorCount() * 3 + 4;
        byte[] defResp = readExact(defLen);
        if (!protocol.parseSensorDefinitions(defResp)) return false;
        Log.i(TAG, "传感器定义已获取");

        return true;
    }

    // === 轮询 (V1.4: 断线触发 fullReset 而非内循环重连) ===

    @Override
    public void startPolling() {
        if (pollThread != null && pollThread.isAlive()) return;
        running = true;

        pollThread = new Thread(new Runnable() {
            @Override
            public void run() {
                int dataLen = protocol.getExpectedLength(0x35);
                if (dataLen <= 0) {
                    postError("数据帧长度无效");
                    return;
                }

                while (running && !intentionalDisconnect) {
                    try {
                        sendCommand(HondataProtocol.CMD_SENSOR_DATA);
                        byte[] resp = readExactWithTimeout(dataLen, READ_TIMEOUT_MS);
                        final SensorData data = protocol.parseSensorData(resp);

                        if (data != null) {
                            uiHandler.post(new Runnable() {
                                @Override public void run() {
                                    if (callback != null) callback.onDataReceived(data);
                                }
                            });
                        }

                        if (POLL_INTERVAL_MS > 0) {
                            Thread.sleep(POLL_INTERVAL_MS);
                        }

                    } catch (IOException e) {
                        Log.w(TAG, "数据读取异常: " + e.getMessage());
                        connected = false;

                        // 通知 UI 断线
                        uiHandler.post(new Runnable() {
                            @Override public void run() {
                                if (callback != null) callback.onDisconnected();
                            }
                        });

                        if (intentionalDisconnect) break;

                        // V1.4: 退出轮询循环，由 fullReset 接管
                        Log.i(TAG, "断线检测，退出轮询，触发 fullReset");
                        break;

                    } catch (InterruptedException e) {
                        break;
                    }
                }

                // 轮询退出后触发 fullReset (非主动断线时)
                if (!intentionalDisconnect && !reconnecting) {
                    uiHandler.post(new Runnable() {
                        @Override public void run() {
                            fullReset();
                        }
                    });
                }
            }
        });
        pollThread.setDaemon(true);
        pollThread.start();
    }

    @Override
    public void stopPolling() {
        running = false;
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
    }

    @Override
    public void disconnect() {
        intentionalDisconnect = true;
        running = false;
        connected = false;
        reconnecting = false;

        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }

        closeSocket();

        uiHandler.post(new Runnable() {
            @Override public void run() {
                if (callback != null) callback.onDisconnected();
            }
        });
    }

    private void closeSocket() {
        try {
            if (inputStream != null) inputStream.close();
        } catch (IOException ignored) {}
        try {
            if (outputStream != null) outputStream.close();
        } catch (IOException ignored) {}
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {}
        inputStream = null;
        outputStream = null;
        socket = null;
    }

    // === IO 工具 ===

    private void sendCommand(byte[] cmd) throws IOException {
        if (outputStream == null) throw new IOException("未连接");
        outputStream.write(cmd);
        outputStream.flush();
    }

    private byte[] readExact(int len) throws IOException {
        if (inputStream == null) throw new IOException("未连接");
        byte[] buf = new byte[len];
        int total = 0;
        while (total < len) {
            int n = inputStream.read(buf, total, len - total);
            if (n < 0) throw new IOException("连接断开");
            total += n;
        }
        return buf;
    }

    private byte[] readExactWithTimeout(int len, int timeoutMs) throws IOException {
        if (inputStream == null) throw new IOException("未连接");
        byte[] buf = new byte[len];
        int total = 0;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (total < len) {
            if (System.currentTimeMillis() > deadline) {
                throw new IOException("读取超时 (" + timeoutMs + "ms)");
            }
            int available = inputStream.available();
            if (available > 0) {
                int n = inputStream.read(buf, total, Math.min(available, len - total));
                if (n < 0) throw new IOException("连接断开");
                total += n;
            } else {
                try { Thread.sleep(1); } catch (InterruptedException e) {
                    throw new IOException("中断");
                }
            }
        }
        return buf;
    }

    private void postError(final String msg) {
        Log.e(TAG, "错误: " + msg);
        uiHandler.post(new Runnable() {
            @Override public void run() {
                if (callback != null) callback.onError(msg);
            }
        });
    }

    public static String findFlashPro(BluetoothAdapter adapter) {
        if (adapter == null) return null;
        for (BluetoothDevice device : adapter.getBondedDevices()) {
            String name = device.getName();
            if (name != null && (name.toLowerCase().contains("flashpro")
                    || name.toLowerCase().contains("hondata"))) {
                return device.getAddress();
            }
        }
        return null;
    }

    public static String findFlashPro() {
        return findFlashPro(BluetoothAdapter.getDefaultAdapter());
    }
}
