# 保持协议和数据类 (排除 DemoSource, 由 R8 在 release 构建中移除)
-keep class io.github.asteroidb612zs.hondatadash.data.HondataProtocol { *; }
-keep class io.github.asteroidb612zs.hondatadash.data.SensorData { *; }
-keep class io.github.asteroidb612zs.hondatadash.data.DataSource { *; }
-keep class io.github.asteroidb612zs.hondatadash.data.BluetoothSource { *; }
-keep class io.github.asteroidb612zs.hondatadash.data.EngineSemanticState { *; }
-keep class io.github.asteroidb612zs.hondatadash.data.EngineStateTracker { *; }

# 保持自定义 View (XML 引用)
-keep class io.github.asteroidb612zs.hondatadash.ScaleBarView { *; }
-keep class io.github.asteroidb612zs.hondatadash.FittedTextView { *; }
-keep class io.github.asteroidb612zs.hondatadash.ShiftLightView { *; }
-keep class io.github.asteroidb612zs.hondatadash.AlignedRowLayout { *; }
-keep class io.github.asteroidb612zs.hondatadash.DashboardGridLayout { *; }
-keep class io.github.asteroidb612zs.hondatadash.HeaderLayout { *; }
-keep class io.github.asteroidb612zs.hondatadash.HondaBrandView { *; }
-keep class io.github.asteroidb612zs.hondatadash.StartupOverlayView { *; }

# 保持 MainActivity (Manifest 引用)
-keep class io.github.asteroidb612zs.hondatadash.MainActivity { *; }

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
-keep class io.github.asteroidb612zs.hondatadash.MiniIconView { public <init>(...); }
