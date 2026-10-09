package io.github.asteroidb612zs.hondatadash;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import java.util.Calendar;

/** Foreground-only presentation controller. Does not change system night mode or brightness. */
final class DashboardNightController {
    private final Activity activity;
    private final NightModePolicy policy;
    private final SharedPreferences preferences;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean started, applied;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!started) return;
            refresh();
            handler.postDelayed(this, 60000L - System.currentTimeMillis() % 60000L);
        }
    };
    private final BroadcastReceiver clockChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            handler.removeCallbacks(tick);
            if (started) tick.run();
        }
    };

    DashboardNightController(Activity activity) {
        this.activity = activity;
        preferences = activity.getSharedPreferences("dashboard_night", Context.MODE_PRIVATE);
        boolean sameFirmware = Build.FINGERPRINT.equals(preferences.getString("firmware", ""));
        policy = new NightModePolicy(sameFirmware && preferences.getBoolean("system_night_observed", false));
        refresh();
        // Read-only test diagnostics; no manual theme selection is needed or exposed.
        activity.findViewById(R.id.header).setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View view) {
                refresh();
                new AlertDialog.Builder(DashboardNightController.this.activity)
                        .setTitle("自动昼夜 · 3.0.1-test.1")
                        .setMessage("当前：" + (policy.isNight() ? "夜间" : "白天")
                                + "\n来源：" + (policy.followsSystem() ? "Android 系统昼夜状态" : "车机本地时间（18:00–07:00 夜间）")
                                + "\n系统夜间信号：" + (policy.hasObservedSystemNight() ? "已观察到" : "尚未观察到")
                                + "\nuiMode：0x" + Integer.toHexString(activity.getResources().getConfiguration().uiMode)
                                + "\n时区：" + java.util.TimeZone.getDefault().getID()
                                + "\n请停车后开关车灯，确认来源与画面是否跟随。")
                        .setPositiveButton("关闭", null).show();
                return true;
            }
        });
    }

    void start() {
        if (started) return;
        started = true;
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        activity.registerReceiver(clockChanged, filter);
        tick.run();
    }

    void stop() {
        handler.removeCallbacks(tick);
        if (!started) return;
        started = false;
        activity.unregisterReceiver(clockChanged);
    }

    void refresh() {
        boolean wasObserved = policy.hasObservedSystemNight();
        policy.update(activity.getResources().getConfiguration().uiMode,
                Calendar.getInstance().get(Calendar.HOUR_OF_DAY));
        if (!wasObserved && policy.hasObservedSystemNight()) {
            preferences.edit().putBoolean("system_night_observed", true)
                    .putString("firmware", Build.FINGERPRINT).apply();
        }
        if (applied && NightPalette.active == policy.isNight()) return;
        applied = true;
        NightPalette.active = policy.isNight();
        activity.findViewById(R.id.appRoot).setBackgroundColor(NightPalette.color(DashboardPalette.BACKGROUND));
        background(activity.findViewById(R.id.header), R.drawable.bg_header, R.drawable.bg_header_night);
        int[] cards = {R.id.card0, R.id.card1, R.id.card2, R.id.card3,
                R.id.card4, R.id.card5, R.id.card6, R.id.card7};
        for (int id : cards) background(activity.findViewById(id), R.drawable.bg_card, R.drawable.bg_card_night);
        ViewGroup bottom = (ViewGroup) activity.findViewById(R.id.bottomRow);
        for (int i = 0; i < bottom.getChildCount(); i++) {
            background(bottom.getChildAt(i), R.drawable.bg_bottom_cell, R.drawable.bg_bottom_cell_night);
        }
        invalidateTree(activity.findViewById(R.id.appRoot));
    }

    private void background(View view, int day, int night) {
        // Drawable replacement must not change the established instrument geometry.
        int l = view.getPaddingLeft(), t = view.getPaddingTop();
        int r = view.getPaddingRight(), b = view.getPaddingBottom();
        view.setBackgroundResource(policy.isNight() ? night : day);
        view.setPadding(l, t, r, b);
    }

    private void invalidateTree(View view) {
        view.invalidate();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) invalidateTree(group.getChildAt(i));
        }
    }
}
