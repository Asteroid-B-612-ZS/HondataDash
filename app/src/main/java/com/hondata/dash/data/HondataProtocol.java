package com.hondata.dash.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hondata FlashPro 通信协议处理器。
 * 基于 Hondata Bluetooth Protocol V1.6 (May 2020) 官方文档。
 * 文档来源: https://www.hondata.com/downloads/Bluetooth.pdf
 *
 * 命令格式: [Protocol=0x00] [ID] [Size_LE_short] (固定 4 字节头 TDLHeader)
 * 连接方式: Bluetooth SPP (RFCOMM)
 *
 * 属性字节 ChannelSize:
 *   高2位 (CS_MASK=0xC0) = 数据大小:
 *     0x40 = CS_BYTE  (8 bits, 1字节)
 *     0x80 = CS_WORD  (16 bits, 2字节, LE)
 *     0xC0 = CS_DWORD (32 bits, 4字节, LE)
 *   低6位 (CT_MASK=0x3F) = 数据类型/缩放公式
 */
public class HondataProtocol {

    // === 命令定义 (TDLHeader: Protocol=0x00, ID, Size=0x0004 LE) ===
    public static final byte[] CMD_IGNITION      = {0x00, 0x02, 0x04, 0x00};
    public static final byte[] CMD_GET_DTCS       = {0x00, 0x20, 0x04, 0x00};
    public static final byte[] CMD_CLEAR_DTCS     = {0x00, 0x21, 0x04, 0x00};
    public static final byte[] CMD_INIT           = {0x00, 0x30, 0x04, 0x00};
    public static final byte[] CMD_SENSOR_DEF     = {0x00, 0x31, 0x04, 0x00};
    public static final byte[] CMD_SENSOR_DATA    = {0x00, 0x35, 0x04, 0x00};

    // === 响应 ID (byte[1]) ===
    private static final int RESP_IGNITION = 0x02;
    private static final int RESP_DTCS     = 0x20;
    private static final int RESP_INIT     = 0x30;
    private static final int RESP_DEF      = 0x31;
    private static final int RESP_DATA     = 0x35;

    // === Channel Size 常量 (属性字节高2位) ===
    public static final int CS_MASK  = 0xC0;
    public static final int CS_BYTE  = 0x40; // 8 bits
    public static final int CS_WORD  = 0x80; // 16 bits
    public static final int CS_DWORD = 0xC0; // 32 bits

    // === Channel Type 常量 (属性字节低6位) ===
    public static final int CT_MASK    = 0x3F;
    public static final int CT_UNKNOWN     = 0x00;
    public static final int CT_BIT         = 0x01; // byte 0=off, 1=on
    public static final int CT_NUMBER      = 0x02; // byte or word, 直通
    public static final int CT_RPM         = 0x03; // word, raw * 0.25 rpm
    public static final int CT_SPEED       = 0x04; // word, raw * 0.01 kph
    public static final int CT_MBAR        = 0x05; // word, raw / 10.0 kPa
    public static final int CT_KPA         = 0x06; // byte, raw * 0.5 kPa
    public static final int CT_TPS         = 0x07; // byte, raw / 2.0 - 10.0 %
    public static final int CT_INJ         = 0x08; // word, raw / 1000.0 ms
    public static final int CT_IGN         = 0x09; // byte, (raw - 20) / 2.0 度
    public static final int CT_RETARD      = 0x0B; // byte, raw / 2.0 度
    public static final int CT_TEMP        = 0x10; // byte, raw °F (直通!)
    public static final int CT_PCT         = 0x11; // byte, raw / 2.56 %
    public static final int CT_PCT_SIGNED  = 0x12; // byte, (raw - 128) / 1.28 %
    public static final int CT_PCT_CHG     = 0x13; // word, raw * 0.01 %
    public static final int CT_MASSFLOW    = 0x16; // mg/s
    public static final int CT_5V          = 0x18; // byte, raw * 5.0 / 256.0 V
    public static final int CT_19V         = 0x19; // byte, 6.0 + raw / 20.0 V
    public static final int CT_LAMBDA      = 0x1E; // word, raw / 32768.0
    public static final int CT_BAR         = 0x20; // word, raw bar
    public static final int CT_MM          = 0x21; // word, raw * 0.001 mm
    public static final int CT_GFORCE      = 0x22; // word
    public static final int CT_SIGNED      = 0x23; // signed byte or word
    public static final int CT_SIGNED100   = 0x24; // signed / 100

    // === Device Type 常量 (byte[4] of ignition response) ===
    public static final int DT_S300     = 0xC0;
    public static final int DT_KPRO     = 0xC1;
    public static final int DT_FLASHPRO = 0xC2;

    // === Channel ID (PID) 常量 ===
    // Engine (0x0100)
    public static final int CID_RPM            = 0x0100;
    public static final int CID_Speed          = 0x0101;
    public static final int CID_Gear           = 0x0102;
    public static final int CID_MAP            = 0x0110;
    public static final int CID_MAPVoltage     = 0x0111;
    public static final int CID_AFMVoltage     = 0x0112;
    public static final int CID_AFMFlow        = 0x0113;
    public static final int CID_BP             = 0x0114;
    public static final int CID_BPCMD          = 0x0115;
    public static final int CID_AirCharge      = 0x0116;
    public static final int CID_AFMFreq        = 0x0117;
    public static final int CID_TPS            = 0x0120;
    public static final int CID_TPSVoltage     = 0x0121;
    public static final int CID_ThrottlePlate  = 0x0122;
    public static final int CID_Inj            = 0x0130;
    public static final int CID_InjPhase       = 0x0131;
    public static final int CID_Duty           = 0x0132;
    public static final int CID_Inj_2          = 0x0133;
    public static final int CID_Ign            = 0x0140;
    public static final int CID_IgnDwell       = 0x0141;
    public static final int CID_IAT            = 0x0150;
    public static final int CID_IAT2           = 0x0151;
    public static final int CID_ECT            = 0x0160;
    public static final int CID_ECT2           = 0x0161;
    public static final int CID_PA             = 0x0170;
    public static final int CID_BatteryVoltage = 0x0180;
    public static final int CID_FuelPressureCmd = 0x0190;
    public static final int CID_FuelPressure   = 0x0191;
    public static final int CID_FuelDuty       = 0x0192;
    public static final int CID_OilPressure    = 0x0198;
    public static final int CID_WGCMD          = 0x01A0;
    public static final int CID_WG             = 0x01A1;
    // VTEC/VTC (0x0200)
    public static final int CID_VTS            = 0x0200;
    public static final int CID_VTC_Cmd        = 0x0210;
    public static final int CID_VTC_Actual     = 0x0211;
    public static final int CID_EXVTC_Cmd      = 0x0214;
    public static final int CID_EXVTC_Actual   = 0x0215;
    // Lambda (0x0300)
    public static final int CID_O2A_V          = 0x0300;
    public static final int CID_Lambda         = 0x0320;
    public static final int CID_CorrLambda     = 0x0321;
    public static final int CID_TargetLambda   = 0x0322;
    public static final int CID_WidebandV      = 0x0328;
    public static final int CID_WidebandLambda = 0x0329;
    public static final int CID_STrim          = 0x0330;
    public static final int CID_MTrim          = 0x0331;
    public static final int CID_LTrim          = 0x0332;
    public static final int CID_ClosedLoop     = 0x0340;
    // Knock (0x0400)
    public static final int CID_KnockLevel     = 0x0400;
    public static final int CID_KnockVoltage   = 0x0401;
    public static final int CID_KnockThreshold = 0x0402;
    public static final int CID_KnockRetard    = 0x0410;
    public static final int CID_KnockLimit     = 0x0411;
    public static final int CID_KnockControl   = 0x0412;
    public static final int CID_KnockCount     = 0x0420;
    // Inputs (0x0500)
    public static final int CID_ACSwitch       = 0x0501;
    public static final int CID_BrakeSwitch    = 0x0503;
    public static final int CID_ClutchIn       = 0x0504;
    // Outputs (0x0600)
    public static final int CID_CheckEngine    = 0x0602;
    public static final int CID_FuelPump       = 0x0603;
    public static final int CID_RadFan         = 0x0607;
    // ECU (0x0700)
    public static final int CID_BoostDuty      = 0x0730;
    public static final int CID_FTP            = 0x0740;
    // Misc (0x0B00)
    public static final int CID_FuelLevel      = 0x0B00;
    public static final int CID_Ethanol        = 0x0B03;
    public static final int CID_ABS_LF         = 0x0B05;
    public static final int CID_ABS_RF         = 0x0B06;
    public static final int CID_ABS_LR         = 0x0B07;
    public static final int CID_ABS_RR         = 0x0B08;
    public static final int CID_SteerAngle     = 0x0B10;
    public static final int CID_BrakePress     = 0x0B12;
    public static final int CID_ClutchPos      = 0x0B13;
    public static final int CID_GLat           = 0x0B20;
    public static final int CID_GLong          = 0x0B21;
    public static final int CID_YawRate        = 0x0B22;

    // === PID 名称查找表 ===
    private static final Map<Integer, String> PID_NAMES = new HashMap<>();
    static {
        PID_NAMES.put(CID_RPM,            "RPM");
        PID_NAMES.put(CID_Speed,          "Speed");
        PID_NAMES.put(CID_Gear,           "Gear");
        PID_NAMES.put(CID_MAP,            "MAP");
        PID_NAMES.put(CID_MAPVoltage,     "MAP V");
        PID_NAMES.put(CID_AFMVoltage,     "AFM V");
        PID_NAMES.put(CID_AFMFlow,        "AFM Flow");
        PID_NAMES.put(CID_BP,             "Boost");
        PID_NAMES.put(CID_BPCMD,          "Boost CMD");
        PID_NAMES.put(CID_AirCharge,      "Air Charge");
        PID_NAMES.put(CID_AFMFreq,        "AFM Hz");
        PID_NAMES.put(CID_TPS,            "TPS");
        PID_NAMES.put(CID_TPSVoltage,     "TPS V");
        PID_NAMES.put(CID_ThrottlePlate,  "Throttle Plate");
        PID_NAMES.put(CID_Inj,            "Injector");
        PID_NAMES.put(CID_InjPhase,       "Inj Phase");
        PID_NAMES.put(CID_Duty,           "Inj Duty");
        PID_NAMES.put(CID_Inj_2,          "Injector B2");
        PID_NAMES.put(CID_Ign,            "IGN Advance");
        PID_NAMES.put(CID_IgnDwell,       "IGN Dwell");
        PID_NAMES.put(CID_IAT,            "IAT");
        PID_NAMES.put(CID_IAT2,           "IAT #2");
        PID_NAMES.put(CID_ECT,            "ECT");
        PID_NAMES.put(CID_ECT2,           "ECT #2");
        PID_NAMES.put(CID_PA,             "Baro");
        PID_NAMES.put(CID_BatteryVoltage, "Battery");
        PID_NAMES.put(CID_FuelPressureCmd,"Fuel P CMD");
        PID_NAMES.put(CID_FuelPressure,   "Fuel Press");
        PID_NAMES.put(CID_FuelDuty,       "Fuel Duty");
        PID_NAMES.put(CID_OilPressure,    "Oil Press");
        PID_NAMES.put(CID_WGCMD,          "WG CMD");
        PID_NAMES.put(CID_WG,             "Wastegate");
        PID_NAMES.put(CID_VTS,            "VTEC Spool");
        PID_NAMES.put(CID_VTC_Cmd,        "VTC CMD");
        PID_NAMES.put(CID_VTC_Actual,     "VTC Actual");
        PID_NAMES.put(CID_EXVTC_Cmd,      "EX VTC CMD");
        PID_NAMES.put(CID_EXVTC_Actual,   "EX VTC Actual");
        PID_NAMES.put(CID_O2A_V,          "O2 V");
        PID_NAMES.put(CID_Lambda,         "Lambda");
        PID_NAMES.put(CID_CorrLambda,     "Corr Lambda");
        PID_NAMES.put(CID_TargetLambda,   "Target Lambda");
        PID_NAMES.put(CID_WidebandV,      "Wideband V");
        PID_NAMES.put(CID_WidebandLambda, "Wideband Lam");
        PID_NAMES.put(CID_STrim,          "S.Trim");
        PID_NAMES.put(CID_MTrim,          "M.Trim");
        PID_NAMES.put(CID_LTrim,          "L.Trim");
        PID_NAMES.put(CID_ClosedLoop,     "Closed Loop");
        PID_NAMES.put(CID_KnockLevel,     "Knock Level");
        PID_NAMES.put(CID_KnockVoltage,   "Knock V");
        PID_NAMES.put(CID_KnockThreshold, "Knock Thresh");
        PID_NAMES.put(CID_KnockRetard,    "Knock Retard");
        PID_NAMES.put(CID_KnockLimit,     "Knock Limit");
        PID_NAMES.put(CID_KnockControl,   "Knock Ctrl");
        PID_NAMES.put(CID_KnockCount,     "Knock Count");
        PID_NAMES.put(CID_ACSwitch,       "AC Switch");
        PID_NAMES.put(CID_BrakeSwitch,    "Brake");
        PID_NAMES.put(CID_ClutchIn,       "Clutch");
        PID_NAMES.put(CID_CheckEngine,    "Check Engine");
        PID_NAMES.put(CID_FuelPump,       "Fuel Pump");
        PID_NAMES.put(CID_RadFan,         "Rad Fan");
        PID_NAMES.put(CID_BoostDuty,      "Boost Duty");
        PID_NAMES.put(CID_FTP,            "Fuel Tank P");
        PID_NAMES.put(CID_FuelLevel,      "Fuel Level");
        PID_NAMES.put(CID_Ethanol,        "Ethanol");
        PID_NAMES.put(CID_ABS_LF,         "ABS LF");
        PID_NAMES.put(CID_ABS_RF,         "ABS RF");
        PID_NAMES.put(CID_ABS_LR,         "ABS LR");
        PID_NAMES.put(CID_ABS_RR,         "ABS RR");
        PID_NAMES.put(CID_SteerAngle,     "Steer Angle");
        PID_NAMES.put(CID_BrakePress,     "Brake Press");
        PID_NAMES.put(CID_ClutchPos,      "Clutch Pos");
        PID_NAMES.put(CID_GLat,           "G Lat");
        PID_NAMES.put(CID_GLong,          "G Long");
        PID_NAMES.put(CID_YawRate,        "Yaw");
    }

    public static String getChannelName(int cid) {
        String name = PID_NAMES.get(cid);
        return name != null ? name : String.format("PID_%03X", cid);
    }

    // === 传感器定义 ===
    public static class SensorDef {
        public final int pid;
        public final int csBits;      // CS_BYTE, CS_WORD, CS_DWORD
        public final int dataLength;  // 1, 2, or 4 bytes
        public final int ctType;      // CT_* constant

        public SensorDef(int pid, int csBits, int dataLength, int ctType) {
            this.pid = pid;
            this.csBits = csBits;
            this.dataLength = dataLength;
            this.ctType = ctType;
        }

        public String getName() {
            return getChannelName(pid);
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
        public int deviceType; // DT_S300, DT_KPRO, DT_FLASHPRO
        public String deviceName;
    }

    /**
     * 解析点火状态响应 (TDeviceInfoReply, 6 字节)
     * byte[4] = DeviceType: 0xC0=S300, 0xC1=KPro, 0xC2=FlashPro
     * byte[5] = IgnitionOn: 0=off, 1=on
     */
    public IgnitionResult parseIgnition(byte[] resp) {
        if (resp == null || resp.length < 6) return null;
        if ((resp[1] & 0xFF) != RESP_IGNITION) return null;

        IgnitionResult r = new IgnitionResult();
        r.deviceType = resp[4] & 0xFF;
        r.ignitionOn = (resp[5] & 0xFF) != 0;

        switch (r.deviceType) {
            case DT_S300:     r.deviceName = "S300"; break;
            case DT_KPRO:     r.deviceName = "KPro"; break;
            case DT_FLASHPRO: r.deviceName = "FlashPro"; break;
            default:          r.deviceName = "Unknown"; break;
        }
        return r;
    }

    /**
     * 解析初始化响应 (TDatalogInfoReply, 8 字节)
     * byte[4..5] = ChannelCount (LE short)
     * byte[6..7] = PacketSize (LE short) — 包含4字节头的数据帧总长
     */
    public boolean parseInit(byte[] resp) {
        if (resp == null || resp.length < 8) return false;
        if ((resp[1] & 0xFF) != RESP_INIT) return false;

        sensorCount = ((resp[5] & 0xFF) << 8) | (resp[4] & 0xFF);
        frameLength = ((resp[7] & 0xFF) << 8) | (resp[6] & 0xFF);
        // V2.6.8 (L1): 边界检查, 防止异常值导致后续 OOM / 长阻塞
        if (sensorCount <= 0 || sensorCount > 256) return false;
        if (frameLength <= 0 || frameLength > 4096) return false;
        return true;
    }

    /**
     * 解析传感器定义响应 (TGetChannelIdListReply)
     * 每条目 3 字节: ChannelID (LE short) + ChannelSize (属性字节)
     *   ChannelSize 高2位 (CS_MASK): CS_BYTE=0x40(1B), CS_WORD=0x80(2B), CS_DWORD=0xC0(4B)
     *   ChannelSize 低6位 (CT_MASK): 数据类型/缩放公式
     */
    public boolean parseSensorDefinitions(byte[] resp) {
        if (resp == null || resp.length < 4) return false;
        if ((resp[1] & 0xFF) != RESP_DEF) return false;

        sensorDefs = new ArrayList<>();
        int offset = 4;
        for (int i = 0; i < sensorCount; i++) {
            if (offset + 3 > resp.length) break;
            int pid = ((resp[offset + 1] & 0xFF) << 8) | (resp[offset] & 0xFF);
            int channelSize = resp[offset + 2] & 0xFF;

            // 高2位 → 数据大小
            int csBits = channelSize & CS_MASK;
            int dataLen;
            switch (csBits) {
                case CS_WORD:  dataLen = 2; break;
                case CS_DWORD: dataLen = 4; break;
                case CS_BYTE:  dataLen = 1; break;
                default:       dataLen = 1; break; // spare/bit
            }

            // 低6位 → 数据类型
            int ctType = channelSize & CT_MASK;

            sensorDefs.add(new SensorDef(pid, csBits, dataLen, ctType));
            offset += 3;
        }
        // V2.6.9 (P1-6): 必须精确解析 sensorCount 条; 不完整定义视为协议错误
        return sensorDefs.size() == sensorCount;
    }

    /**
     * 解析实时传感器数据帧 (TGetDatalogReply)
     * 紧凑二进制包，按 SensorDef 逐个读取，通过 CT 缩放后写入 SensorData。
     */
    public SensorData parseSensorData(byte[] resp) {
        if (resp == null || resp.length < 4) return null;
        if ((resp[1] & 0xFF) != RESP_DATA) return null;
        if (sensorDefs == null) return null;

        // V2.6.9 (P1-6): 计算期望 payload 长度, 长度不足视为协议错误, 不返回部分帧
        int expectedPayload = 0;
        for (SensorDef def : sensorDefs) {
            expectedPayload += def.dataLength;
        }
        if (resp.length < 4 + expectedPayload) return null;

        SensorData data = new SensorData();
        data.timestamp = System.currentTimeMillis();

        int offset = 4;
        for (int i = 0; i < sensorDefs.size(); i++) {
            SensorDef def = sensorDefs.get(i);
            // 长度已在上面校验过, 这里 break 仅作防御
            if (offset + def.dataLength > resp.length) break;

            double raw = readRaw(resp, offset, def);
            double scaled = scale(def.ctType, raw, def.dataLength);
            // V2.6.8: 过滤 NaN/Infinity, 防止污染 EMA 与极值初始化
            if (Double.isNaN(scaled) || Double.isInfinite(scaled)) {
                offset += def.dataLength;
                continue;
            }
            data.put(def.pid, scaled);

            offset += def.dataLength;
        }
        return data;
    }

    /**
     * 按 csBits 读取原始值 (无符号)
     */
    private double readRaw(byte[] buf, int off, SensorDef def) {
        switch (def.csBits) {
            case CS_WORD: // 2字节 LE unsigned short
                return (double)((buf[off] & 0xFF) | ((buf[off + 1] & 0xFF) << 8));
            case CS_DWORD: // 4字节 LE unsigned int
                return (double)(
                    (buf[off] & 0xFFL)
                    | ((buf[off + 1] & 0xFFL) << 8)
                    | ((buf[off + 2] & 0xFFL) << 16)
                    | ((buf[off + 3] & 0xFFL) << 24)
                );
            case CS_BYTE:
            default: // 1字节 unsigned byte
                return (double)(buf[off] & 0xFF);
        }
    }

    /**
     * 基于 CT 类型的缩放转换 (从 Hondata Bluetooth Protocol V1.6 官方文档)
     *
     * CT_BIT        (0x01): byte 0=off, 1=on
     * CT_NUMBER     (0x02): 直通
     * CT_RPM        (0x03): word, raw * 0.25
     * CT_SPEED      (0x04): word, raw * 0.01 kph
     * CT_MBAR       (0x05): word, raw / 10.0 kPa
     * CT_KPA        (0x06): byte, raw * 0.5 kPa
     * CT_TPS        (0x07): byte, raw / 2.0 - 10.0 %
     * CT_INJ        (0x08): word, raw / 1000.0 ms
     * CT_IGN        (0x09): byte, (raw - 20) / 2.0 度
     * CT_RETARD     (0x0B): byte, raw / 2.0 度
     * CT_TEMP       (0x10): byte, raw °F
     * CT_PCT        (0x11): byte, raw / 2.56 %
     * CT_PCT_SIGNED (0x12): byte, (raw - 128) / 1.28 %
     * CT_PCT_CHG    (0x13): word, raw * 0.01 %
     * CT_MASSFLOW   (0x16): mg/s 直通
     * CT_5V         (0x18): byte, raw * 5.0 / 256.0 V
     * CT_19V        (0x19): byte, 6.0 + raw / 20.0 V
     * CT_LAMBDA     (0x1E): word, raw / 32768.0
     * CT_BAR        (0x20): word, raw bar 直通
     * CT_MM         (0x21): word, raw * 0.001 mm
     * CT_GFORCE     (0x22): word 直通
     * CT_SIGNED     (0x23): 有符号 byte/word
     * CT_SIGNED100  (0x24): 有符号 / 100
     */
    public static double scale(int ctType, double raw, int dataLength) {
        switch (ctType) {
            case CT_BIT:     // 0=off, 1=on
                return raw > 0 ? 1.0 : 0.0;

            case CT_NUMBER:  // 直通
            case CT_UNKNOWN:
                return raw;

            case CT_RPM:     // raw * 0.25
                return raw * 0.25;

            case CT_SPEED:   // raw * 0.01 kph
                return raw * 0.01;

            case CT_MBAR:    // raw / 10.0 kPa
                return raw / 10.0;

            case CT_KPA:     // raw * 0.5 kPa
                return raw * 0.5;

            case CT_TPS:     // raw / 2.0 - 10.0 %
                return raw / 2.0 - 10.0;

            case CT_INJ:     // raw / 1000.0 ms
                return raw / 1000.0;

            case CT_IGN:     // (raw - 20) / 2.0 度 (也用于 cam angle)
                return (raw - 20.0) / 2.0;

            case CT_RETARD:  // raw / 2.0 度
                return raw / 2.0;

            case CT_TEMP:    // raw °F → 转换为 °C
                return (raw - 32.0) / 1.8;

            case CT_PCT:     // raw / 2.56 %
                return raw / 2.56;

            case CT_PCT_SIGNED: // (raw - 128) / 1.28 %
                return (raw - 128.0) / 1.28;

            case CT_PCT_CHG: // raw * 0.01 %
                return raw * 0.01;

            case CT_MASSFLOW: // mg/s 直通
                return raw;

            case CT_5V:      // raw * 5.0 / 256.0 V
                return raw * 5.0 / 256.0;

            case CT_19V:     // 6.0 + raw / 20.0 V
                return 6.0 + raw / 20.0;

            case CT_LAMBDA:  // raw / 32768.0
                return raw / 32768.0;

            case CT_BAR:     // raw bar
                return raw;

            case CT_MM:      // raw * 0.001 mm
                return raw * 0.001;

            case CT_GFORCE:  // 直通
                return raw;

            case CT_SIGNED: {
                // 有符号: byte 范围 0-255 → -128~127, word 范围 0-65535 → -32768~32767
                double signed = raw;
                if (dataLength == 1 && raw > 127) signed = raw - 256;
                else if (dataLength == 2 && raw > 32767) signed = raw - 65536;
                return signed;
            }

            case CT_SIGNED100: {
                double signed = raw;
                if (dataLength == 1 && raw > 127) signed = raw - 256;
                else if (dataLength == 2 && raw > 32767) signed = raw - 65536;
                return signed / 100.0;
            }

            default:
                return raw;
        }
    }

    /**
     * 华氏→摄氏转换 (用于 CT_TEMP 显示)
     */
    public static double fToC(double f) {
        return (f - 32.0) / 1.8;
    }

    /**
     * 期望响应长度 (用于缓冲区预分配)
     */
    public int getExpectedLength(int responseType) {
        switch (responseType) {
            case RESP_IGNITION: return 6;
            case RESP_DTCS:     return -1; // variable
            case RESP_INIT:     return 8;
            case RESP_DEF:      return sensorCount * 3 + 4;
            case RESP_DATA:
                // frameLength = PacketSize (数据载荷), 完整响应 = 头(4) + 载荷
                return frameLength > 0 ? frameLength + 4 : sensorCount + 4;
            default:            return -1;
        }
    }
}
