package com.hondata.dash.data;

import java.util.ArrayList;
import java.util.List;

/**
 * Hondata FlashPro 通信协议处理器。
 * 基于 Hondata Dash App V1.6.0.0 逆向分析。
 *
 * 命令格式: [CMD_ID] [CMD_TYPE] 0x04 0x00 (固定 4 字节)
 * 连接方式: Bluetooth SPP (RFCOMM)
 */
public class HondataProtocol {

    // === 命令定义 (固定 4 字节) ===
    public static final byte[] CMD_IGNITION    = {0x00, 0x02, 0x04, 0x00};
    public static final byte[] CMD_INIT        = {0x00, 0x30, 0x04, 0x00};
    public static final byte[] CMD_SENSOR_DEF  = {0x00, 0x31, 0x04, 0x00};
    public static final byte[] CMD_SENSOR_DATA = {0x00, 0x35, 0x04, 0x00};

    // === 响应标识 (byte[1]) ===
    private static final int RESP_IGNITION = 0x02;
    private static final int RESP_INIT     = 0x30;
    private static final int RESP_DEF      = 0x31;
    private static final int RESP_DATA     = 0x35;

    // === 传感器定义 (从 0x31 响应动态获取) ===
    public static class SensorDef {
        public final int pid;
        public final int dataLength;  // 1 或 2 字节
        public final int dataType;    // 0=ubyte, 1=sbyte, 2=short

        public SensorDef(int pid, int dataLength, int dataType) {
            this.pid = pid;
            this.dataLength = dataLength;
            this.dataType = dataType;
        }
    }

    // === 会话状态 ===
    private int sensorCount;
    private int frameLength;
    private List<SensorDef> sensorDefs;

    public int getSensorCount() { return sensorCount; }
    public List<SensorDef> getSensorDefs() { return sensorDefs; }

    // === 点火状态 ===
    public static class IgnitionResult {
        public boolean ignitionOn;
        public int deviceType; // 0-3
    }

    /**
     * 解析点火状态响应 (期望 6 字节)
     * byte[4]+256 → 设备类型码
     */
    public IgnitionResult parseIgnition(byte[] resp) {
        if (resp == null || resp.length < 6) return null;
        if ((resp[1] & 0xFF) != RESP_IGNITION) return null;

        IgnitionResult r = new IgnitionResult();
        int typeCode = (resp[4] & 0xFF) + 256;
        r.ignitionOn = (resp[3] != 0);
        if (typeCode == 0xC0)      r.deviceType = 0;
        else if (typeCode == 0xC1) r.deviceType = 1;
        else if (typeCode == 0xC2) r.deviceType = 2;
        else                       r.deviceType = 3;
        return r;
    }

    /**
     * 解析初始化响应 (期望 8 字节)
     * byte[4..5] = sensorCount (LE short)
     * byte[6..7] = frameLength (LE short)
     */
    public boolean parseInit(byte[] resp) {
        if (resp == null || resp.length < 8) return false;
        if ((resp[1] & 0xFF) != RESP_INIT) return false;

        sensorCount = ((resp[5] & 0xFF) << 8) | (resp[4] & 0xFF);
        frameLength = ((resp[7] & 0xFF) << 8) | (resp[6] & 0xFF);
        return true;
    }

    /**
     * 解析传感器定义响应
     * 每个 3 字节: PID(LE short) + 属性字节
     *   属性字节 bits[5:0] = dataLength, bits[7:6] = dataType
     */
    public boolean parseSensorDefinitions(byte[] resp) {
        if (resp == null || resp.length < 4) return false;
        if ((resp[1] & 0xFF) != RESP_DEF) return false;

        sensorDefs = new ArrayList<>();
        int offset = 4;
        for (int i = 0; i < sensorCount; i++) {
            if (offset + 3 > resp.length) break;
            int pid = ((resp[offset + 1] & 0xFF) << 8) | (resp[offset] & 0xFF);
            int attr = resp[offset + 2] & 0xFF;
            int dataLen = attr & 0x3F;
            int dataType = (attr >> 6) & 0x03;
            sensorDefs.add(new SensorDef(pid, dataLen, dataType));
            offset += 3;
        }
        return !sensorDefs.isEmpty();
    }

    /**
     * 解析实时传感器数据
     * 逐个传感器按 SensorDef 读取原始值，通过 PidRegistry 缩放后写入 SensorData。
     */
    public SensorData parseSensorData(byte[] resp) {
        if (resp == null || resp.length < 4) return null;
        if ((resp[1] & 0xFF) != RESP_DATA) return null;
        if (sensorDefs == null) return null;

        SensorData data = new SensorData();
        data.timestamp = System.currentTimeMillis();

        int offset = 4;
        for (int i = 0; i < sensorDefs.size(); i++) {
            SensorDef def = sensorDefs.get(i);
            if (offset + def.dataLength > resp.length) break;

            double raw = readRaw(resp, offset, def);
            double scaled = PidRegistry.scale(def.pid, raw);
            data.put(def.pid, scaled);

            offset += def.dataLength;
        }
        return data;
    }

    /**
     * 按 dataType 读取原始值
     */
    private double readRaw(byte[] buf, int off, SensorDef def) {
        switch (def.dataType) {
            case 0: // unsigned byte
                return buf[off] & 0xFF;
            case 1: // signed byte → 无符号化
                int b = buf[off];
                return b < 0 ? b + 256 : b;
            case 2: // 2-byte LE short
                int lo = buf[off] & 0xFF;
                int hi = buf[off + 1] & 0xFF;
                return (short) (lo | (hi << 8));
            default:
                return buf[off] & 0xFF;
        }
    }

    /**
     * 期望响应长度 (用于缓冲区预分配)
     */
    public int getExpectedLength(int responseType) {
        switch (responseType) {
            case RESP_IGNITION: return 6;
            case RESP_INIT:     return 8;
            case RESP_DEF:      return sensorCount * 3 + 4;
            case RESP_DATA:     return frameLength > 0 ? frameLength : sensorCount + 4;
            default:            return -1;
        }
    }
}
