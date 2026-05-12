package com.hondata.dash.data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 传感器数据模型 - 基于 Map 的动态存储。
 * 支持 FlashPro 提供的全部 PID，不受原版 Dash App 21 个限制。
 */
public class SensorData {

    public long timestamp;
    private final Map<Integer, Double> values = new LinkedHashMap<>();

    public void put(int pid, double value) {
        values.put(pid, value);
    }

    public Double get(int pid) {
        return values.get(pid);
    }

    public boolean has(int pid) {
        return values.containsKey(pid);
    }

    public Map<Integer, Double> getAll() {
        return Collections.unmodifiableMap(values);
    }

    /**
     * 获取格式化的显示值
     */
    public String format(int pid, String pattern) {
        Double v = values.get(pid);
        if (v == null) return "--";
        return String.format(pattern, v);
    }

    /**
     * 便捷: 直接取 double，不存在返回 NaN
     */
    public double getDouble(int pid) {
        Double v = values.get(pid);
        return v != null ? v : Double.NaN;
    }
}
