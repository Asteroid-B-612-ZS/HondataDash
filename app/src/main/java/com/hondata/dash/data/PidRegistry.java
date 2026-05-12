package com.hondata.dash.data;

import java.util.HashMap;
import java.util.Map;

/**
 * 完整 PID 注册表。
 * 来源: Hondata Dash App V1.6.0.0 逆向 + KManager/FlashPro Manager 全量 PID。
 *
 * 每个 PID 条目包含: 名称、单位、缩放类型。
 * 未知 PID 降级为 RAW (原始值)。
 */
public class PidRegistry {

    // === 缩放类型 ===
    public static final int SCALE_RAW        = 0;  // 无缩放
    public static final int SCALE_PRESSURE    = 1;  // raw * 10.0 → kPa
    public static final int SCALE_TEMP        = 2;  // raw - 40 → °C
    public static final int SCALE_LAMBDA      = 3;  // raw / 128 + 0.5 → Lambda
    public static final int SCALE_WIDEBAND    = 4;  // (raw - 500) / 128 → Lambda
    public static final int SCALE_TRIM        = 5;  // raw * 100 / 128 - 100 → %
    public static final int SCALE_VOLTAGE     = 6;  // raw * 0.01 * 4 → V
    public static final int SCALE_ANGLE       = 7;  // raw * 0.5 → °
    public static final int SCALE_DUTY        = 8;  // raw * 100 / 255 → %
    public static final int SCALE_INJECTOR    = 9;  // raw * 0.001 → ms
    public static final int SCALE_DWELL       = 10; // raw * 0.001 → ms
    public static final int SCALE_PERCENT     = 11; // raw → % (直接)
    public static final int SCALE_FREQUENCY   = 12; // raw → Hz
    public static final int SCALE_VOLTAGE_LOW = 13; // raw * 0.0196 → V (0-5V)
    public static final int SCALE_KNOCK_LEVEL = 14; // raw → 爆震等级 0-255
    public static final int SCALE_KNOCK_VOLT  = 15; // raw * 0.001 → V
    public static final int SCALE_KNOCK_RET   = 16; // raw * 0.5 → °
    public static final int SCALE_BOOL        = 17; // 0=OFF, 1=ON

    // === PID 条目 ===
    public static class PidInfo {
        public final int pid;
        public final String name;
        public final String unit;
        public final int scaleType;
        public final String category;

        public PidInfo(int pid, String name, String unit, int scaleType, String category) {
            this.pid = pid;
            this.name = name;
            this.unit = unit;
            this.scaleType = scaleType;
            this.category = category;
        }
    }

    private static final Map<Integer, PidInfo> registry = new HashMap<>();

    static {
        // =============================
        // 基础引擎 (0x100-0x102)
        // =============================
        reg(0x100, "RPM",        "RPM",  SCALE_RAW,     "引擎");
        reg(0x101, "Speed",      "km/h", SCALE_RAW,     "引擎");
        reg(0x102, "Gear",       "",     SCALE_RAW,     "引擎");

        // =============================
        // 压力/流量 (0x110-0x116)
        // =============================
        reg(0x110, "MAP",        "kPa",  SCALE_PRESSURE,"压力");
        reg(0x111, "MAP Volts",  "V",    SCALE_VOLTAGE_LOW, "压力");
        reg(0x112, "AFM",        "g/s",  SCALE_RAW,     "压力");
        reg(0x113, "AFM Volts",  "V",    SCALE_VOLTAGE_LOW, "压力");
        reg(0x114, "Boost Press","kPa",  SCALE_PRESSURE,"压力");
        reg(0x115, "BP CMD",     "kPa",  SCALE_PRESSURE,"压力");
        reg(0x116, "Air Charge", "%",    SCALE_PERCENT, "压力");

        // =============================
        // 节气门 (0x120-0x122)
        // =============================
        reg(0x120, "TP Pedal",   "%",    SCALE_PERCENT, "节气门");
        reg(0x121, "TPS Volts",  "V",    SCALE_VOLTAGE_LOW, "节气门");
        reg(0x122, "TP Plate",   "%",    SCALE_PERCENT, "节气门");

        // =============================
        // 喷油 (0x130-0x133)
        // =============================
        reg(0x130, "Injector",   "ms",   SCALE_INJECTOR,"喷油");
        reg(0x131, "Inj Phase",  "°",    SCALE_ANGLE,   "喷油");
        reg(0x132, "Inj Duty",   "%",    SCALE_DUTY,    "喷油");
        reg(0x133, "Injector 2", "ms",   SCALE_INJECTOR,"喷油");

        // =============================
        // 点火 (0x140-0x141)
        // =============================
        reg(0x140, "Ignition",   "°",    SCALE_ANGLE,   "点火");
        reg(0x141, "Ign Dwell",  "ms",   SCALE_DWELL,   "点火");

        // =============================
        // 进气温度 (0x150-0x151)
        // =============================
        reg(0x150, "IAT",        "°C",   SCALE_TEMP,    "温度");
        reg(0x151, "IAT 2",      "°C",   SCALE_TEMP,    "温度");

        // =============================
        // 冷却液温度 (0x160-0x163)
        // =============================
        reg(0x160, "ECT",        "°C",   SCALE_TEMP,    "温度");
        reg(0x161, "ECT 2",      "°C",   SCALE_TEMP,    "温度");
        reg(0x162, "ECT comp",   "°C",   SCALE_TEMP,    "温度");
        reg(0x163, "IAT comp",   "°C",   SCALE_TEMP,    "温度");

        // =============================
        // 大气压力 (0x170)
        // =============================
        reg(0x170, "Atm Press",  "kPa",  SCALE_PRESSURE,"压力");

        // =============================
        // 电气 (0x180)
        // =============================
        reg(0x180, "Battery",    "V",    SCALE_VOLTAGE, "电气");

        // =============================
        // 直喷燃油 (0x190-0x192)
        // =============================
        reg(0x190, "DI FP CMD",  "kPa",  SCALE_PRESSURE,"燃油");
        reg(0x191, "DI FP",      "kPa",  SCALE_PRESSURE,"燃油");
        reg(0x192, "Fuel Duty",  "%",    SCALE_PERCENT, "燃油");

        // =============================
        // 废气旁通阀 (0x1A0-0x1A1)
        // =============================
        reg(0x1A0, "WG CMD",     "%",    SCALE_PERCENT, "涡轮");
        reg(0x1A1, "WG Pos",     "%",    SCALE_PERCENT, "涡轮");

        // =============================
        // VTC / VTEC 凸轮轴 (0x200-0x215)
        // =============================
        for (int i = 0; i <= 0x15; i++) {
            reg(0x200 + i, "CAM " + String.format("%02X", i), "°", SCALE_ANGLE, "VTC");
        }

        // =============================
        // 空燃比 / 氧传感 (0x300-0x341)
        // =============================
        // O2 传感器 (0x300-0x303)
        for (int i = 0; i <= 3; i++) {
            reg(0x300 + i, "O2-" + (i + 1), "V", SCALE_VOLTAGE_LOW, "空燃比");
        }
        // Lambda 组 (0x310-0x334)
        reg(0x310, "Lambda 1",   "λ",    SCALE_LAMBDA,  "空燃比");
        reg(0x311, "Lambda 2",   "λ",    SCALE_LAMBDA,  "空燃比");
        reg(0x320, "Air Fuel",   "λ",    SCALE_LAMBDA,  "空燃比");
        reg(0x323, "Air Fuel 2", "λ",    SCALE_LAMBDA,  "空燃比");
        reg(0x329, "Wideband",   "λ",    SCALE_WIDEBAND,"空燃比");
        reg(0x330, "STFT",       "%",    SCALE_TRIM,    "空燃比");
        reg(0x332, "LTFT",       "%",    SCALE_TRIM,    "空燃比");
        reg(0x333, "STFT 2",     "%",    SCALE_TRIM,    "空燃比");
        reg(0x334, "LTFT 2",     "%",    SCALE_TRIM,    "空燃比");
        // 补全 0x311-0x334 范围中的未列出的
        for (int i = 0x312; i <= 0x31F; i++) {
            if (!registry.containsKey(i)) reg(i, "Lambda " + String.format("0x%03X", i), "λ", SCALE_LAMBDA, "空燃比");
        }
        for (int i = 0x321; i <= 0x328; i++) {
            if (!registry.containsKey(i)) reg(i, "AFR " + String.format("0x%03X", i), "λ", SCALE_LAMBDA, "空燃比");
        }
        for (int i = 0x335; i <= 0x341; i++) {
            if (!registry.containsKey(i)) reg(i, "Trim " + String.format("0x%03X", i), "%", SCALE_TRIM, "空燃比");
        }

        // =============================
        // 开关量 (0x400-0x426)
        // =============================
        reg(0x400, "A/C",        "",     SCALE_BOOL,    "开关");
        reg(0x401, "SCS",        "",     SCALE_BOOL,    "开关");
        reg(0x402, "Brake",      "",     SCALE_BOOL,    "开关");
        for (int i = 0x410; i <= 0x426; i++) {
            if (!registry.containsKey(i)) reg(i, "Clutch " + String.format("0x%03X", i), "", SCALE_BOOL, "开关");
        }

        // =============================
        // 辅助输出 (0x501-0x507)
        // =============================
        reg(0x501, "MIL",        "",     SCALE_BOOL,    "输出");
        reg(0x502, "Fuel Pump",  "",     SCALE_BOOL,    "输出");
        reg(0x503, "Purge",      "",     SCALE_BOOL,    "输出");
        reg(0x504, "EGR",        "",     SCALE_BOOL,    "输出");
        reg(0x505, "Rad Fan",    "",     SCALE_BOOL,    "输出");
        reg(0x506, "Alt FR",     "",     SCALE_BOOL,    "输出");
        reg(0x507, "Fan Ctrl",   "",     SCALE_BOOL,    "输出");

        // =============================
        // 爆震 (0x601-0x607)
        // =============================
        reg(0x601, "Knock Level","",     SCALE_KNOCK_LEVEL,"爆震");
        reg(0x602, "Knock Volt", "V",    SCALE_KNOCK_VOLT, "爆震");
        reg(0x603, "Knock Ret",  "°",    SCALE_KNOCK_RET,  "爆震");
        reg(0x604, "Knock CYL1", "", SCALE_RAW, "爆震");
        reg(0x605, "Knock CYL2", "", SCALE_RAW, "爆震");
        reg(0x606, "Knock CYL3", "", SCALE_RAW, "爆震");
        reg(0x607, "Knock CYL4", "", SCALE_RAW, "爆震");

        // =============================
        // 牵引力控制 (0x700-0x740)
        // =============================
        reg(0x700, "TC Slip",    "%",    SCALE_PERCENT, "TC");
        for (int i = 0x710; i <= 0x740; i++) {
            if (!registry.containsKey(i)) reg(i, "TC Speed " + String.format("0x%03X", i), "km/h", SCALE_RAW, "TC");
        }

        // =============================
        // 氮气喷射 (0x800-0x821)
        // =============================
        for (int i = 0x800; i <= 0x821; i++) {
            reg(i, "Nitrous " + String.format("0x%03X", i), "", SCALE_RAW, "Nitrous");
        }

        // =============================
        // 模拟输入 (0x900-0x927)
        // =============================
        for (int i = 0x900; i <= 0x927; i++) {
            int ch = i - 0x900;
            reg(i, "Analog " + ch, "V", SCALE_VOLTAGE_LOW, "模拟输入");
        }

        // =============================
        // 数字输入 (0xA00-0xA31)
        // =============================
        for (int i = 0xA00; i <= 0xA31; i++) {
            int ch = (i - 0xA00) / 4;
            reg(i, "DI" + ch, "", SCALE_BOOL, "数字输入");
        }

        // =============================
        // 乙醇 / 特殊 (0xB00-0xB09)
        // =============================
        reg(0xB00, "CVT Temp",   "°C",   SCALE_TEMP,    "特殊");
        reg(0xB01, "Fuel Temp",  "°C",   SCALE_TEMP,    "特殊");
        reg(0xB02, "Fuel Freq",  "Hz",   SCALE_FREQUENCY,"特殊");
        reg(0xB03, "Ethanol",    "%",    SCALE_PERCENT, "特殊");
        reg(0xB04, "Fuel Level", "%",    SCALE_PERCENT, "特殊");
        for (int i = 0xB05; i <= 0xB09; i++) {
            if (!registry.containsKey(i)) reg(i, "Special " + String.format("0x%03X", i), "", SCALE_RAW, "特殊");
        }
    }

    private static void reg(int pid, String name, String unit, int scaleType, String category) {
        registry.put(pid, new PidInfo(pid, name, unit, scaleType, category));
    }

    /**
     * 查询 PID 信息，未知 PID 返回默认 RAW 条目
     */
    public static PidInfo getInfo(int pid) {
        PidInfo info = registry.get(pid);
        if (info != null) return info;
        return new PidInfo(pid, String.format("PID_%03X", pid), "", SCALE_RAW, "未知");
    }

    /**
     * 是否为已知 PID
     */
    public static boolean isKnown(int pid) {
        return registry.containsKey(pid);
    }

    /**
     * 缩放转换
     */
    public static double scale(int pid, double raw) {
        int type = getInfo(pid).scaleType;
        switch (type) {
            case SCALE_RAW:         return raw;
            case SCALE_PRESSURE:    return raw * 10.0;
            case SCALE_TEMP:        return raw - 40;
            case SCALE_LAMBDA:      return raw / 128.0 + 0.5;
            case SCALE_WIDEBAND:    return (raw - 500) / 128.0;
            case SCALE_TRIM:        return raw * 100.0 / 128.0 - 100.0;
            case SCALE_VOLTAGE:     return raw * 0.01 * 4.0;
            case SCALE_ANGLE:       return raw * 0.5;
            case SCALE_DUTY:        return raw * 100.0 / 255.0;
            case SCALE_INJECTOR:    return raw * 0.001;
            case SCALE_DWELL:       return raw * 0.001;
            case SCALE_PERCENT:     return raw;
            case SCALE_FREQUENCY:   return raw;
            case SCALE_VOLTAGE_LOW: return raw * 0.0196;
            case SCALE_KNOCK_LEVEL: return raw;
            case SCALE_KNOCK_VOLT:  return raw * 0.001;
            case SCALE_KNOCK_RET:   return raw * 0.5;
            case SCALE_BOOL:        return raw;
            default:                return raw;
        }
    }
}
