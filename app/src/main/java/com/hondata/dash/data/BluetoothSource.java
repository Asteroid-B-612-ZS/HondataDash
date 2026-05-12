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
import java.util.UUID;

/**
 * 蓝牙 SPP 数据源。
 * 连接 Hondata FlashPro 盒子，使用逆向的 Hondata 协议读取传感器数据。
 */
public class BluetoothSource implements DataSource {

    private static final String TAG = "BTSource";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int POLL_INTERVAL_MS = 20; // 50Hz 轮询

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final HondataProtocol protocol = new HondataProtocol();

    private Callback callback;
    private BluetoothSocket socket;
    private InputStream inputStream;
    private OutputStream outputStream;
    private Thread pollThread;
    private volatile boolean running;
    private volatile boolean connected;

    @Override
    public String getName() { return "Bluetooth SPP"; }

    @Override
    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    /**
     * 连接指定 MAC 地址的蓝牙设备。
     * 在后台线程执行，完成后通过 callback 通知。
     */
    @Override
    public void connect(final String address) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                    if (adapter == null || !adapter.isEnabled()) {
                        postError("蓝牙未启用");
                        return;
                    }

                    BluetoothDevice device = adapter.getRemoteDevice(address);
                    socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                    adapter.cancelDiscovery();
                    socket.connect();

                    inputStream = socket.getInputStream();
                    outputStream = socket.getOutputStream();
                    connected = true;

                    // 初始化协议握手
                    if (!handshake()) {
                        disconnect();
                        postError("协议握手失败");
                        return;
                    }

                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (callback != null) callback.onConnected();
                        }
                    });

                } catch (IOException e) {
                    Log.e(TAG, "连接失败", e);
                    postError("连接失败: " + e.getMessage());
                }
            }
        }).start();
    }

    /**
     * 协议握手: 点火检测 → 初始化 → 传感器定义
     */
    private boolean handshake() throws IOException {
        // Step 1: 点火状态
        sendCommand(HondataProtocol.CMD_IGNITION);
        byte[] ignitionResp = readExact(6);
        HondataProtocol.IgnitionResult ign = protocol.parseIgnition(ignitionResp);
        if (ign == null) return false;
        Log.i(TAG, "点火: " + ign.ignitionOn + " 设备类型: " + ign.deviceType);

        // Step 2: 初始化 (传感器数量)
        sendCommand(HondataProtocol.CMD_INIT);
        byte[] initResp = readExact(8);
        if (!protocol.parseInit(initResp)) return false;
        Log.i(TAG, "传感器数量: " + protocol.getSensorCount());

        // Step 3: 传感器定义
        sendCommand(HondataProtocol.CMD_SENSOR_DEF);
        int defLen = protocol.getSensorCount() * 3 + 4;
        byte[] defResp = readExact(defLen);
        if (!protocol.parseSensorDefinitions(defResp)) return false;
        Log.i(TAG, "传感器定义已获取");

        return true;
    }

    /**
     * 开始轮询传感器数据 (50Hz)
     */
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

                while (running && connected) {
                    try {
                        sendCommand(HondataProtocol.CMD_SENSOR_DATA);
                        byte[] resp = readExact(dataLen);
                        final SensorData data = protocol.parseSensorData(resp);

                        if (data != null) {
                            uiHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (callback != null) callback.onDataReceived(data);
                                }
                            });
                        }

                        if (POLL_INTERVAL_MS > 0) {
                            Thread.sleep(POLL_INTERVAL_MS);
                        }

                    } catch (IOException e) {
                        Log.e(TAG, "读取数据失败", e);
                        connected = false;
                        uiHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (callback != null) callback.onDisconnected();
                            }
                        });
                        break;
                    } catch (InterruptedException e) {
                        break;
                    }
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
        running = false;
        connected = false;

        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }

        try {
            if (inputStream != null) inputStream.close();
            if (outputStream != null) outputStream.close();
            if (socket != null) socket.close();
        } catch (IOException ignored) {}

        inputStream = null;
        outputStream = null;
        socket = null;

        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                if (callback != null) callback.onDisconnected();
            }
        });
    }

    // === 内部工具方法 ===

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

    private void postError(final String msg) {
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                if (callback != null) callback.onError(msg);
            }
        });
    }

    // === 辅助: 查找已配对的 FlashPro 设备 ===

    /**
     * 在已配对设备中查找 FlashPro
     * @return MAC 地址，未找到返回 null
     */
    public static String findFlashPro() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
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
}
