package io.github.asteroidb612zs.hondatadash.data;

/**
 * 数据源抽象接口。
 * Phase 1: BluetoothSource (蓝牙 SPP)
 * Phase 2: WiFiSource (ESP32 WiFi)
 */
public interface DataSource {

    interface Callback {
        void onConnected();
        void onDisconnected();
        void onDataReceived(SensorData data);
        void onError(String message);
    }

    void setCallback(Callback callback);
    /** Connect to a selected Bluetooth address; non-Bluetooth implementations may ignore it. */
    void connect(String address);
    void disconnect();
    void startPolling();
    void stopPolling();
    boolean isConnected();
    String getName();
}
