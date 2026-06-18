package com.hondata.dash.data;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
    private static final int BT_STACK_CLEANUP_MS = 2500; // V2.6.7: 1s→2.5s，老 Android SPP 释放更稳

    // FlashPro MAC 地址 (硬编码)
    public static final String FLASHPRO_MAC = "XX:XX:XX:XX:XX:XX";

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
    // V2.6.9 (P0-2): 区分"生命周期暂停"与"蓝牙真实断线"
    // stopPolling() 设 true, startPolling() 清 false; pollThread 退出时若为 true 则不触发重连
    private volatile boolean pollingPausedByLifecycle = false;
    // V2.6.8: 重连入口/出口用同一把 lock 保护, 防止多线程并发触发重连
    private final Object reconnectLock = new Object();

    public BluetoothSource() {
        protocol = new HondataProtocol();
    }

    // V2.6.8: 统一的重连入口仲裁
    private boolean tryEnterReconnecting() {
        synchronized (reconnectLock) {
            if (reconnecting || intentionalDisconnect) return false;
            reconnecting = true;
            return true;
        }
    }

    // V2.6.8: 统一的重连出口释放
    private void exitReconnecting() {
        synchronized (reconnectLock) { reconnecting = false; }
    }

    @Override public String getName() { return "Bluetooth SPP"; }
    @Override public void setCallback(Callback cb) { this.callback = cb; }
    public void setExportContext(android.content.Context ctx) { this.exportContext = ctx; }
    @Override public boolean isConnected() { return connected; }

    @Override
    public void connect(final String address) {
        intentionalDisconnect = false;
        // V2.6.9 (P0-2): 新连接清除生命周期暂停标志
        pollingPausedByLifecycle = false;
        // V2.6.8: 用 lock 保护
        synchronized (reconnectLock) { reconnecting = false; }
        // V2.6.8 (BG11): connect 线程设为 daemon, 与 resetThread/pollThread 一致
        Thread connectThread = new Thread(new Runnable() {
            @Override
            public void run() {
                // V2.6.9 (P0-1): 首次连接失败必走重连, 不再卡死
                if (!tryConnectOnce()) {
                    scheduleReconnect();
                }
            }
        }, "BT-Connect");
        connectThread.setDaemon(true);
        connectThread.start();
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
        // V2.6.8: 用 lock 仲裁, 防止多线程并发触发
        if (!tryEnterReconnecting()) return;
        // 故意不放在 try/finally: 进入失败时不占用 reconnecting 标志

        Log.i(TAG, "fullReset: 开始重建数据层...");

        // V2.6.7: 先完整释放旧资源
        closeAllBluetoothResources();

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
                    // V2.6.9 (P0-1): 用 tryConnectOnce 返回值判断, 不再依赖 connected 副作用
                    if (tryConnectOnce()) {
                        Log.i(TAG, "fullReset: 重连成功!");
                        // 自动开始轮询
                        startPolling();
                    } else {
                        Log.w(TAG, "fullReset: 首次重连失败，开始指数退避...");
                        reconnectWithBackoff(RECONNECT_BASE_MS);
                    }
                } catch (InterruptedException e) {
                    Log.i(TAG, "fullReset: 被中断，取消重连");
                } finally {
                    // V2.6.8: 通过 exitReconnecting 统一释放
                    exitReconnecting();
                }
            }
        });
        resetThread.setDaemon(true);
        resetThread.start();
    }

    /**
     * 指数退避重连 (仅在 fullReset 首次失败后使用)
     * V2.6.8 (BG2): 循环条件改为 !intentionalDisconnect, 并检查蓝牙可用性
     *              蓝牙被用户关闭时停止重连, 避免无限重连风暴
     */
    private void reconnectWithBackoff(int delay) {
        while (!intentionalDisconnect) {
            // V2.6.8 (BG2): 蓝牙被关闭/不可用时停止重连
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                Log.w(TAG, "蓝牙未启用, 停止重连");
                postError("蓝牙未启用");
                exitReconnecting();
                return;
            }

            try {
                Log.i(TAG, "重连失败, " + (delay / 1000) + "s 后重试...");
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                exitReconnecting();
                return;
            }

            if (intentionalDisconnect) {
                exitReconnecting();
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
                // V2.6.9 (P0-1): tryConnectOnce 内部不触发重连, 避免嵌套
                if (tryConnectOnce()) {
                    Log.i(TAG, "重连成功!");
                    exitReconnecting();
                    startPolling();
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "重连异常: " + e.getMessage());
            }

            // 指数退避: 1s → 2s → 4s → 8s
            delay = Math.min(delay * 2, RECONNECT_MAX_MS);
        }
        exitReconnecting();
    }

    /**
     * 核心连接逻辑 (可被重连复用)
     * V2.6.9 (P0-1): 重构为返回 boolean, 失败不再内部 scheduleReconnect (避免 backoff 嵌套)
     * V2.6.9 (P1-5): connected=true 移到握手成功后, 避免 isConnected() 在协议未就绪时返回 true
     *
     * @return true = socket + 握手全成功, 可开始轮询; false = 任意阶段失败, 由调用方决定重连
     */
    private boolean tryConnectOnce() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                postError("蓝牙未启用");
                return false;
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
                return false;
            }
            socket = connectedSocket;

            Log.i(TAG, "蓝牙Socket连接成功");
            Thread.sleep(500);
            inputStream = socket.getInputStream();
            outputStream = socket.getOutputStream();
            // V2.6.9 (P1-5): 不在此设 connected=true, 等握手成功后再设

            Log.i(TAG, "开始握手...");
            if (!handshake()) {
                // V2.6.7: 握手失败不再调用 disconnect()（会误设 intentionalDisconnect，阻断自动重连）
                cleanupFailedConnection();
                postError("协议握手失败");
                return false;
            }
            // V2.6.9 (P1-5): 握手成功才算真正连接
            connected = true;
            Log.i(TAG, "握手成功! 传感器:" + protocol.getSensorCount()
                + " 帧长:" + protocol.getExpectedLength(0x35));

            uiHandler.post(new Runnable() {
                @Override public void run() {
                    if (callback != null) callback.onConnected();
                }
            });
            return true;

        } catch (Exception e) {
            Log.e(TAG, "连接异常", e);
            // V2.6.7: 连接异常也走 cleanup，不设 intentionalDisconnect
            cleanupFailedConnection();
            postError("连接失败: " + e.getMessage());
            return false;
        }
    }

    // === 连接方式 ===

    private BluetoothSocket tryReflectChannel(BluetoothDevice device, int channel) {
        BluetoothSocket sock = null;
        try {
            Log.i(TAG, "尝试反射 ch" + channel + "...");
            Method m = device.getClass().getMethod("createRfcommSocket", int.class);
            sock = (BluetoothSocket) m.invoke(device, channel);
            sock.connect();
            Log.i(TAG, "反射 ch" + channel + " 成功!");
            return sock;
        } catch (Exception e) {
            Log.w(TAG, "反射 ch" + channel + " 失败: " + e.getMessage());
            closeQuietly(sock);
            return null;
        }
    }

    private BluetoothSocket tryInsecureSPP(BluetoothDevice device) {
        BluetoothSocket sock = null;
        try {
            Log.i(TAG, "尝试不安全SPP...");
            sock = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
            sock.connect();
            Log.i(TAG, "不安全SPP成功!");
            return sock;
        } catch (Exception e) {
            Log.w(TAG, "不安全SPP失败: " + e.getMessage());
            closeQuietly(sock);
            return null;
        }
    }

    private BluetoothSocket tryStandardSPP(BluetoothDevice device) {
        BluetoothSocket sock = null;
        try {
            Log.i(TAG, "尝试标准SPP...");
            sock = device.createRfcommSocketToServiceRecord(SPP_UUID);
            sock.connect();
            Log.i(TAG, "标准SPP成功!");
            return sock;
        } catch (Exception e) {
            Log.w(TAG, "标准SPP失败: " + e.getMessage());
            closeQuietly(sock);
            return null;
        }
    }

    // === 协议握手 ===

    private boolean handshake() throws IOException {
        // 点火检测: 最多重试 10 次, 间隔 2 秒
        // V2.6.8: 单次读用 2s 超时, 避免蓝牙断开时 readExact 永久阻塞
        // V2.6.8 (BG4): parseIgnition==null (协议解析失败) 时清空缓冲重试, 而非立即失败
        HondataProtocol.IgnitionResult ign = null;
        boolean parsedOk = false;
        for (int attempt = 1; attempt <= 10; attempt++) {
            sendCommand(HondataProtocol.CMD_IGNITION);
            byte[] ignitionResp = readExactWithTimeout(6, 2000);

            HondataProtocol.IgnitionResult result = protocol.parseIgnition(ignitionResp);
            if (result == null) {
                // V2.6.8 (BG4): 协议错位 (字节偏移/残留帧), 清空输入缓冲后重试
                // V2.6.9 (P2-1): InputStream.skip 不保证一次跳完, 改用循环 read 直到缓冲空
                Log.w(TAG, "点火检测 #" + attempt + ": 解析失败, 清缓冲重试");
                flushInputStream();
                continue;  // 重试, 不立即 return false
            }
            ign = result;
            parsedOk = true;
            Log.i(TAG, "点火检测 #" + attempt + ": ignition=" + ign.ignitionOn);

            if (ign.ignitionOn) break;
            if (attempt < 10) {
                try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
            }
        }

        // 10 次重试后仍未解析成功 (协议持续错位)
        // 注意: 点火关 (ign.ignitionOn=false) 不算失败, 原行为是继续走 INIT
        if (!parsedOk || ign == null) return false;

        sendCommand(HondataProtocol.CMD_INIT);
        // V2.6.8: INIT/SENSOR_DEF 也加 2s 超时
        byte[] initResp = readExactWithTimeout(8, 2000);
        if (!protocol.parseInit(initResp)) return false;
        Log.i(TAG, "传感器数量: " + protocol.getSensorCount());

        sendCommand(HondataProtocol.CMD_SENSOR_DEF);
        int defLen = protocol.getSensorCount() * 3 + 4;
        // V2.6.8: 传感器定义读取加 3s 超时
        byte[] defResp = readExactWithTimeout(defLen, 3000);
        if (!protocol.parseSensorDefinitions(defResp)) return false;
        Log.i(TAG, "传感器定义已获取");

        return true;
    }

    // === 轮询 (V1.4: 断线触发 fullReset 而非内循环重连) ===

    @Override
    public void startPolling() {
        // V2.6.9 (P0-2): 无论是否启动新线程, 恢复轮询都清除生命周期暂停标志
        pollingPausedByLifecycle = false;
        if (pollThread != null && pollThread.isAlive()) return;
        running = true;

        pollThread = new Thread(new Runnable() {
            @Override
            public void run() {
                int dataLen = protocol.getExpectedLength(0x35);
                if (dataLen <= 0) {
                    // V2.6.9 (P1-2): 帧长无效 = 协议初始化异常, 走完整失败路径, 不死锁
                    Log.e(TAG, "数据帧长度无效, 走完整失败路径");
                    connected = false;
                    cleanupFailedConnection();
                    uiHandler.post(new Runnable() {
                        @Override public void run() {
                            if (callback != null) callback.onDisconnected();
                        }
                    });
                    scheduleReconnect();
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

                        // V2.6.9 (P0-2): 生命周期暂停 (onPause) 导致的退出不算断线
                        if (intentionalDisconnect || pollingPausedByLifecycle) {
                            break;
                        }

                        // 通知 UI 断线 (真实 I/O 异常)
                        uiHandler.post(new Runnable() {
                            @Override public void run() {
                                if (callback != null) callback.onDisconnected();
                            }
                        });

                        // V1.4: 退出轮询循环，由 fullReset 接管
                        Log.i(TAG, "断线检测，退出轮询，触发 fullReset");
                        break;

                    } catch (InterruptedException e) {
                        // V2.6.9 (P0-2): 中断可能是 stopPolling (生命周期暂停), 不算断线
                        break;
                    } catch (RuntimeException e) {
                        // V2.6.7: 捕获协议解析等 RuntimeException，防止线程静默死亡
                        Log.e(TAG, "数据处理异常: " + e.getMessage(), e);
                        connected = false;

                        if (intentionalDisconnect || pollingPausedByLifecycle) {
                            break;
                        }

                        uiHandler.post(new Runnable() {
                            @Override public void run() {
                                if (callback != null) callback.onDisconnected();
                            }
                        });

                        break;
                    }
                }

                // V2.6.9 (P0-2): 轮询退出后, 只在"真实断线"(非主动断开 + 非生命周期暂停)时触发 fullReset
                if (!intentionalDisconnect && !pollingPausedByLifecycle && !reconnecting) {
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
        // V2.6.9 (P0-2): 标记为生命周期暂停, pollThread 退出时不触发 fullReset/onDisconnected
        pollingPausedByLifecycle = true;
        running = false;
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
    }

    @Override
    public void disconnect() {
        intentionalDisconnect = true;
        closeAllBluetoothResources();
        // V2.6.8: 用统一出口释放
        exitReconnecting();

        uiHandler.post(new Runnable() {
            @Override public void run() {
                if (callback != null) callback.onDisconnected();
            }
        });
    }

    // V2.6.7: 统一资源释放 — 中断 pollThread + 关闭所有流/socket
    private synchronized void closeAllBluetoothResources() {
        running = false;
        connected = false;

        Thread t = pollThread;
        pollThread = null;

        if (t != null && t != Thread.currentThread()) {
            try {
                t.interrupt();
            } catch (Exception ignored) {}
        }

        closeSocket();
    }

    // V2.6.7: 连接/握手失败后清理 — 不设 intentionalDisconnect，允许自动重连
    private void cleanupFailedConnection() {
        running = false;
        connected = false;

        closeQuietly(inputStream);
        closeQuietly(outputStream);
        closeQuietly(socket);

        inputStream = null;
        outputStream = null;
        socket = null;
    }

    // V2.6.7: 统一触发重连（外部失败路径调用）
    // V2.6.8: volatile 读本身就足够做 best-effort 防重, 最终仲裁由 fullReset 内部 tryEnterReconnecting 完成
    private void scheduleReconnect() {
        if (intentionalDisconnect) return;
        if (reconnecting) return;

        uiHandler.post(new Runnable() {
            @Override public void run() {
                fullReset();
            }
        });
    }

    private void closeSocket() {
        closeQuietly(inputStream);
        closeQuietly(outputStream);
        closeQuietly(socket);
        inputStream = null;
        outputStream = null;
        socket = null;
    }

    // V2.6.9 (P2-1): 可靠地清空输入缓冲 (skip 不保证一次跳完, 用循环 read)
    // 设置最大清理字节数, 防止恶意/异常大数据导致无限循环
    private void flushInputStream() {
        if (inputStream == null) return;
        byte[] buf = new byte[256];
        int maxFlush = 4096;
        int flushed = 0;
        try {
            while (inputStream.available() > 0 && flushed < maxFlush) {
                int n = inputStream.read(buf, 0, Math.min(buf.length, inputStream.available()));
                if (n <= 0) break;
                flushed += n;
            }
        } catch (IOException ignored) {}
    }

    // V2.6.7: 统一安全关闭方法
    private void closeQuietly(InputStream in) {
        if (in != null) { try { in.close(); } catch (Exception ignored) {} }
    }

    private void closeQuietly(OutputStream out) {
        if (out != null) { try { out.close(); } catch (Exception ignored) {} }
    }

    private void closeQuietly(BluetoothSocket s) {
        if (s != null) { try { s.close(); } catch (Exception ignored) {} }
    }

    // === IO 工具 ===

    private void sendCommand(byte[] cmd) throws IOException {
        if (outputStream == null) throw new IOException("未连接");
        outputStream.write(cmd);
        outputStream.flush();
    }

    /**
     * V2.6.8 (BG8): 已废弃 — 无超时, 蓝牙断开时可能永久阻塞
     * 全部调用已改为 readExactWithTimeout。保留定义仅作历史参考, 请勿使用。
     */
    @Deprecated
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
        // V2.6.8 (L2): 用 elapsedRealtime 替代 currentTimeMillis
        // 避免 NTP 同步导致 deadline 突然过期
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (total < len) {
            if (SystemClock.elapsedRealtime() > deadline) {
                throw new IOException("读取超时 (" + timeoutMs + "ms)");
            }
            int available = inputStream.available();
            if (available > 0) {
                int n = inputStream.read(buf, total, Math.min(available, len - total));
                if (n < 0) throw new IOException("连接断开");
                total += n;
            } else {
                // V2.6.8 (L3): Thread.sleep(1) 是必要的, 不能改为 sleep(0)
                // 原因: sleep(0) 在 JVM 等价于 yield(), 不保证让出 CPU 切片
                // 50Hz 轮询下 1ms 睡眠不会显著影响帧率, 但能避免忙循环
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
