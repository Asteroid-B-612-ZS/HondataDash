package com.hondata.dash.data;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 蓝牙 SPP 数据源。
 * 连接 Hondata FlashPro 盒子，使用逆向的 Hondata 协议读取传感器数据。
 * 支持已配对设备 + 蓝牙扫描发现。
 */
public class BluetoothSource implements DataSource {

    private static final String TAG = "BTSource";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int POLL_INTERVAL_MS = 20; // 50Hz 轮询

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final HondataProtocol protocol = new HondataProtocol();

    private Callback callback;
    private android.content.Context exportContext;
    private BluetoothSocket socket;
    private InputStream inputStream;
    private OutputStream outputStream;
    private Thread pollThread;
    private volatile boolean running;
    private volatile boolean connected;

    // 扫描发现的设备
    private volatile String discoveredAddress;
    private volatile boolean scanComplete;

    @Override
    public String getName() { return "Bluetooth SPP"; }

    @Override
    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public void setExportContext(android.content.Context ctx) {
        this.exportContext = ctx;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    /**
     * 连接: 先查已配对, 没有则启动蓝牙扫描发现
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

                    String targetAddr = address;

                    // 如果没有指定地址, 尝试查找
                    if (targetAddr == null || targetAddr.isEmpty()) {
                        // 1. 先在已配对设备中查找
                        targetAddr = findFlashPro(adapter);
                        if (targetAddr != null) {
                            Log.i(TAG, "在已配对列表中找到: " + targetAddr);
                        } else {
                            // 2. 启动蓝牙扫描
                            Log.i(TAG, "已配对列表未找到, 开始蓝牙扫描...");
                            targetAddr = scanForFlashPro(adapter);
                            if (targetAddr == null) {
                                postError("扫描未找到 FlashPro");
                                return;
                            }
                            Log.i(TAG, "扫描发现: " + targetAddr);
                        }
                    }

                    final String addr = targetAddr;
                    BluetoothDevice device = adapter.getRemoteDevice(addr);
                    Log.i(TAG, "目标设备: " + device.getName() + " [" + addr + "]");

                    // 连接策略: 先不安全SPP, 失败则扫描通道
                    BluetoothSocket connectedSocket = null;
                    adapter.cancelDiscovery();

                    // 方式1: 不安全SPP (官方App方式)
                    connectedSocket = tryConnect(device, 2);

                    // 方式2: 扫描所有通道
                    if (connectedSocket == null) {
                        Log.i(TAG, "标准SPP失败, 开始扫描RFCOMM通道...");
                        connectedSocket = scanChannels(device);
                    }

                    if (connectedSocket == null) {
                        postError("所有连接方式均失败");
                        return;
                    }
                    socket = connectedSocket;

                    Log.i(TAG, "蓝牙Socket连接成功");
                    Thread.sleep(500);
                    inputStream = socket.getInputStream();
                    outputStream = socket.getOutputStream();
                    connected = true;
                    Log.i(TAG, "Socket已连接，开始握手...");

                    if (!handshake()) {
                        disconnect();
                        postError("协议握手失败(点火/初始化/传感器定义)");
                        return;
                    }
                    Log.i(TAG, "握手成功! 传感器:" + protocol.getSensorCount()
                        + " 帧长:" + protocol.getExpectedLength(0x35));

                    // 打印调试信息: frameLength + 传感器定义 (含 CS/CT)
                    StringBuilder sb = new StringBuilder();
                    sb.append("传感器: ").append(protocol.getSensorCount())
                      .append(" 帧长: ").append(protocol.getExpectedLength(0x35)).append("\n");
                    int dataPayloadLen = 0;
                    for (int i = 0; i < protocol.getSensorDefs().size(); i++) {
                        HondataProtocol.SensorDef sd = protocol.getSensorDefs().get(i);
                        dataPayloadLen += sd.dataLength;
                        sb.append(String.format("%s(%03X) len=%d ct=%02X\n",
                            sd.getName(), sd.pid, sd.dataLength, sd.ctType));
                    }
                    sb.append("数据载荷: ").append(dataPayloadLen).append("字节");
                    Log.i(TAG, sb.toString());

                    // TODO: 导出功能暂时禁用，先确认基础连接正常
                    // try {
                    //     exportData();
                    // } catch (Exception ex) {
                    //     Log.e(TAG, "导出失败(不影响连接): " + ex.getMessage());
                    // }

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
        }).start();
    }

    /**
     * 蓝牙扫描 (由 MainActivity 调用, 不在此处实现)
     */
    private String scanForFlashPro(BluetoothAdapter adapter) {
        return null;
    }

    /**
     * 尝试不同的连接方式
     */
    private BluetoothSocket tryConnect(BluetoothDevice device, int method) {
        BluetoothSocket sock = null;
        try {
            switch (method) {
                case 1: // 标准 SPP
                    Log.i(TAG, "尝试标准 SPP...");
                    sock = device.createRfcommSocketToServiceRecord(SPP_UUID);
                    break;
                case 2: // 不安全 SPP
                    Log.i(TAG, "尝试不安全 SPP...");
                    sock = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
                    break;
                default: // 反射 channel N (method=10+channel)
                    int channel = method - 10;
                    if (channel < 0 || channel > 30) return null;
                    Log.i(TAG, "尝试反射 (ch" + channel + ")...");
                    Method m = device.getClass().getMethod("createRfcommSocket", int.class);
                    sock = (BluetoothSocket) m.invoke(device, channel);
                    break;
            }
            if (sock != null) {
                sock.connect();
                Log.i(TAG, "方法 " + method + " 成功!");
                return sock;
            }
        } catch (Exception e) {
            Log.w(TAG, "方法 " + method + " 失败: " + e.getMessage());
            if (sock != null) {
                try { sock.close(); } catch (IOException ignored) {}
            }
        }
        return null;
    }

    /**
     * 扫描所有RFCOMM通道, 找到能读到实时数据的通道
     * 返回已连接的socket, 或null
     */
    private BluetoothSocket scanChannels(BluetoothDevice device) throws InterruptedException {
        Method m;
        try {
            m = device.getClass().getMethod("createRfcommSocket", int.class);
        } catch (Exception e) {
            Log.e(TAG, "反射方法不可用", e);
            return null;
        }

        // 先尝试不安全SPP (官方App方式)
        BluetoothSocket sock = tryConnect(device, 2);
        if (sock != null) return sock;

        // 扫描通道 0-10
        for (int ch = 0; ch <= 10; ch++) {
            Log.i(TAG, "扫描通道 " + ch + "...");
            try {
                BluetoothSocket s = (BluetoothSocket) m.invoke(device, ch);
                s.connect();
                Log.i(TAG, "通道 " + ch + " 连接成功! 快速验证...");

                // 快速验证: 发送点火命令, 读取响应, 检查数据
                try {
                    Thread.sleep(300);
                    OutputStream os = s.getOutputStream();
                    InputStream is = s.getInputStream();

                    // 发送点火检测
                    os.write(HondataProtocol.CMD_IGNITION);
                    os.flush();

                    // 读取6字节
                    byte[] buf = new byte[6];
                    int total = 0;
                    long deadline = System.currentTimeMillis() + 3000;
                    while (total < 6 && System.currentTimeMillis() < deadline) {
                        int n = is.read(buf, total, 6 - total);
                        if (n < 0) throw new IOException("断开");
                        total += n;
                    }

                    if (total == 6 && buf[1] == 0x02) {
                        int voltageByte = -1;
                        boolean hasLiveData = false;

                        // 如果点火为true, 尝试读一帧数据验证
                        if ((buf[4] & 0xFF) > 0) {
                            // 发送CMD_INIT
                            os.write(HondataProtocol.CMD_INIT);
                            os.flush();
                            byte[] initBuf = new byte[8];
                            total = 0;
                            deadline = System.currentTimeMillis() + 3000;
                            while (total < 8 && System.currentTimeMillis() < deadline) {
                                int n = is.read(initBuf, total, 8 - total);
                                if (n < 0) throw new IOException("断开");
                                total += n;
                            }

                            if (total == 8) {
                                int sc = ((initBuf[5] & 0xFF) << 8) | (initBuf[4] & 0xFF);
                                int fl = ((initBuf[7] & 0xFF) << 8) | (initBuf[6] & 0xFF);
                                Log.i(TAG, "通道" + ch + ": INIT成功 sensorCount=" + sc + " frameLen=" + fl);

                                // 发送CMD_SENSOR_DATA读一帧 (fl是PacketSize, 需要读 fl+4 包含头)
                                int fullFrame = fl + 4;
                                if (fl > 0 && fl < 500) {
                                    os.write(HondataProtocol.CMD_SENSOR_DATA);
                                    os.flush();
                                    byte[] dataBuf = new byte[fullFrame];
                                    total = 0;
                                    deadline = System.currentTimeMillis() + 3000;
                                    while (total < fullFrame && System.currentTimeMillis() < deadline) {
                                        int n = is.read(dataBuf, total, fullFrame - total);
                                        if (n < 0) throw new IOException("断开");
                                        total += n;
                                    }

                                    if (total == fullFrame) {
                                        Log.i(TAG, "通道" + ch + ": 数据帧" + fullFrame + "B获取成功");
                                        // 打印前几个字节
                                        StringBuilder hex = new StringBuilder();
                                        for (int i = 0; i < Math.min(20, fl); i++) {
                                            hex.append(String.format("%02X ", dataBuf[i]));
                                        }
                                        Log.i(TAG, "通道" + ch + " 数据: " + hex);
                                    }
                                }
                            }
                        }

                        Log.i(TAG, "通道" + ch + ": 有效FlashPro响应! 点火=" + ((buf[4] & 0xFF) > 0));
                        return s; // 返回这个socket
                    }
                    // 不是FlashPro, 关闭继续
                    s.close();
                } catch (Exception e) {
                    Log.w(TAG, "通道" + ch + " 验证失败: " + e.getMessage());
                    try { s.close(); } catch (IOException ignored) {}
                }
            } catch (Exception e) {
                // 连接失败, 正常, 继续下一个通道
                Log.d(TAG, "通道" + ch + " 连接失败: " + e.getMessage());
            }
        }
        return null;
    }

    /**
     * 协议握手: 点火检测(带重试) → 初始化 → 传感器定义
     * FlashPro 蓝牙连接后需要时间同步 OBD2，点火检测可能需要多次重试
     */
    private boolean handshake() throws IOException {
        // 点火检测: 最多重试 10 次, 每次间隔 2 秒
        HondataProtocol.IgnitionResult ign = null;
        for (int attempt = 1; attempt <= 10; attempt++) {
            sendCommand(HondataProtocol.CMD_IGNITION);
            byte[] ignitionResp = readExact(6);

            // 打印原始响应字节
            StringBuilder hex = new StringBuilder("点火响应原始字节: ");
            for (byte b : ignitionResp) hex.append(String.format("%02X ", b));
            Log.i(TAG, hex.toString());

            ign = protocol.parseIgnition(ignitionResp);
            if (ign == null) return false;
            Log.i(TAG, "点火检测 #" + attempt + ": ignition=" + ign.ignitionOn
                + " typeCode=" + ((ignitionResp[4] & 0xFF) + 256)
                + " deviceType=" + ign.deviceType);

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
                                @Override public void run() {
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
                            @Override public void run() {
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
            @Override public void run() {
                if (callback != null) callback.onDisconnected();
            }
        });
    }

    // === 内部工具 ===

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
        Log.e(TAG, "错误: " + msg);
        uiHandler.post(new Runnable() {
            @Override public void run() {
                if (callback != null) callback.onError(msg);
            }
        });
    }

    /**
     * 在已配对设备中查找 FlashPro
     */
    public static String findFlashPro(BluetoothAdapter adapter) {
        if (adapter == null) return null;
        for (BluetoothDevice device : adapter.getBondedDevices()) {
            String name = device.getName();
            Log.d(TAG, "已配对: " + name + " [" + device.getAddress() + "]");
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

    public static String listBondedDevices() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return "无蓝牙";
        StringBuilder sb = new StringBuilder();
        for (BluetoothDevice device : adapter.getBondedDevices()) {
            sb.append(device.getName()).append(" [").append(device.getAddress()).append("]\n");
        }
        return sb.length() > 0 ? sb.toString() : "无已配对设备";
    }

    /**
     * 自动导出: 采集10帧原始数据 + 传感器定义写入文件
     * 文件保存到 /sdcard/Download/hondata_export.txt
     */
    private void exportData() {
        if (exportContext == null) {
            Log.w(TAG, "exportContext 为空, 跳过导出");
            return;
        }
        if (!connected || outputStream == null || inputStream == null) {
            return;
        }

        try {
            File dir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS);
            File file = new File(dir, "hondata_export.txt");
            if (!dir.exists()) dir.mkdirs();
            PrintWriter pw = new PrintWriter(new FileOutputStream(file));

            pw.println("=== Hondata Dash 数据导出 ===");
            pw.println("时间: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                java.util.Locale.US).format(new java.util.Date()));
            pw.println();

            // 1. 传感器定义
            List<HondataProtocol.SensorDef> defs = protocol.getSensorDefs();
            pw.println("=== 传感器定义 ===");
            pw.println("传感器数量: " + protocol.getSensorCount());
            pw.println("帧长度: " + protocol.getExpectedLength(0x35));
            pw.println();
            pw.println("序号\tPID\t名称\t数据长度\tCS\tCT");
            int totalBytes = 0;
            for (int i = 0; i < defs.size(); i++) {
                HondataProtocol.SensorDef sd = defs.get(i);
                pw.printf("%d\t0x%03X\t%s\t%d\t0x%02X\t0x%02X\n",
                    i, sd.pid, sd.getName(), sd.dataLength, sd.csBits, sd.ctType);
                totalBytes += sd.dataLength;
            }
            pw.println("\n数据载荷总字节: " + totalBytes);
            pw.println();

            // 2. 采集10帧原始数据
            int dataLen = protocol.getExpectedLength(0x35);
            pw.println("=== 原始数据帧 (10帧) ===");
            pw.println("格式: 帧号 | 原始HEX | PID:raw→scaled(sc类型)");
            pw.println();

            for (int frame = 1; frame <= 10; frame++) {
                sendCommand(HondataProtocol.CMD_SENSOR_DATA);
                byte[] resp = readExact(dataLen);

                // 原始HEX
                pw.printf("帧%d (%d字节): ", frame, resp.length);
                for (int b = 0; b < resp.length; b++) {
                    pw.printf("%02X ", resp[b]);
                }
                pw.println();

                // 逐传感器解析 raw→scaled
                int off = 4;
                for (int i = 0; i < defs.size(); i++) {
                    HondataProtocol.SensorDef sd = defs.get(i);
                    if (off + sd.dataLength > resp.length) break;

                    // 读取raw值
                    double rawVal;
                    if (sd.csBits == HondataProtocol.CS_WORD) {
                        rawVal = (resp[off] & 0xFF) | ((resp[off + 1] & 0xFF) << 8);
                    } else if (sd.csBits == HondataProtocol.CS_DWORD) {
                        rawVal = (resp[off] & 0xFFL)
                            | ((resp[off + 1] & 0xFFL) << 8)
                            | ((resp[off + 2] & 0xFFL) << 16)
                            | ((resp[off + 3] & 0xFFL) << 24);
                    } else {
                        rawVal = resp[off] & 0xFF;
                    }

                    double scaled = HondataProtocol.scale(sd.ctType, rawVal, sd.dataLength);
                    pw.printf("  %s(0x%03X): raw=%.0f → scaled=%.4f (ct=0x%02X)\n",
                        sd.getName(), sd.pid, rawVal, scaled, sd.ctType);

                    off += sd.dataLength;
                }
                pw.println();

                Thread.sleep(50); // 50ms间隔
            }

            pw.println("=== 导出完成 ===");
            pw.close();

            final String path = file.getAbsolutePath();
            Log.i(TAG, "数据已导出: " + path);
            uiHandler.post(new Runnable() {
                @Override public void run() {
                    if (callback != null) callback.onError("导出完成: " + path);
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "导出失败", e);
            postError("导出失败: " + e.getMessage());
        }
    }
}
