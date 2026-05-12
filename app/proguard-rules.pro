# 保持协议和数据类
-keep class com.hondata.dash.data.** { *; }

# 保持自定义 View (XML 引用)
-keep class com.hondata.dash.ScaleBarView { *; }
-keep class com.hondata.dash.WheelView { *; }

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
