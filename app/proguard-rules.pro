# 保持协议和数据类 (排除 DemoSource, 由 R8 在 release 构建中移除)
-keep class com.hondata.dash.data.HondataProtocol { *; }
-keep class com.hondata.dash.data.SensorData { *; }
-keep class com.hondata.dash.data.DataSource { *; }
-keep class com.hondata.dash.data.BluetoothSource { *; }
-keep class com.hondata.dash.data.EngineSemanticState { *; }
-keep class com.hondata.dash.data.EngineStateTracker { *; }

# 保持自定义 View (XML 引用)
-keep class com.hondata.dash.ScaleBarView { *; }

# 保持 MainActivity (Manifest 引用)
-keep class com.hondata.dash.MainActivity { *; }

# 移除日志 (release 构建)
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# 优化选项
-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose
