package com.police.smartbadge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 5G警用执法记录仪 - 智能枪托监控后台服务
 * <p>
 * 功能：
 * 1. 前台服务常驻，WakeLock保活
 * 2. BLE Central模式连接枪托
 * 3. 实时RSSI滤波监测与断连报警
 * 4. 枪支状态监测（开枪、跌落）
 * 5. MQTT数据上报
 */
public class GunMonitorService extends Service {

    private static final String TAG = "GunMonitorService";

    // UUID Configuration
    // 服务 UUID: 0000FFE0-0000-1000-8000-00805F9B34FB
    private static final UUID SERVICE_UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB");
    // 特征值 UUID: 0000FFE1...
    private static final UUID CHAR_UUID = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB");
    // Client Characteristic Configuration Descriptor (CCCD) 用于开启Notify
    private static final UUID CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // Protocol Constants (协议常量)
    private static final byte HEADER_BYTE = (byte) 0xA5;
    private static final byte EVENT_SHOT = 0x01; // 开枪
    private static final byte EVENT_DROP = 0x02; // 跌落

    // Thresholds & Timers (阈值与时间参数)
    private static final int RSSI_WARNING_THRESHOLD = -85; // dBm
    private static final int RSSI_WINDOW_SIZE = 5;         // 滑动窗口大小
    private static final long WARNING_DURATION_MS = 5000;  // 预警持续时间阈值 (5s)
    private static final long ALARM_DURATION_MS = 10000;   // 断连报警时间阈值 (10s)
    private static final long RECONNECT_DELAY_MS = 1000;   // 重连延迟 (1s)

    // System Components
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGatt bluetoothGatt;
    private PowerManager.WakeLock wakeLock;
    private Handler mainHandler;

    // State variables
    private boolean isConnected = false;
    private boolean isScanning = false;

    // RSSI Filtering State
    private List<Integer> rssiWindow = new ArrayList<>();
    private long lowRssiStartTime = 0;

    // Runnables for timing logic
    private Runnable rssiPollRunnable;
    private Runnable alarmRunnable;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());

        // 1. 启动前台服务 (Start Foreground Service)
        // 防止系统因内存不足杀掉服务
        startForegroundServiceNotification();

        // 2. 申请WakeLock (Acquire WakeLock)
        // 防止CPU休眠导致蓝牙断连
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GunMonitor:KeepAlive");
            wakeLock.acquire();
        }

        // 3. 初始化蓝牙 (Init Bluetooth)
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
        }

        // 启动扫描
        if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
            startBleScan();
        } else {
            Log.e(TAG, "Bluetooth not enabled or unavailable");
        }
    }

    /**
     * 配置前台服务通知
     */
    private void startForegroundServiceNotification() {
        String channelId = "gun_monitor_channel";
        // Android 8.0+ 需要 NotificationChannel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId,
                    "Gun Monitor Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, channelId);
        } else {
            builder = new Notification.Builder(this);
        }

        Notification notification = builder.setContentTitle("智能枪托监控中")
                .setContentText("正在保护连接...")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .build();

        startForeground(1, notification);
    }

    // ==========================================
    // BLE 核心连接逻辑
    // ==========================================

    private void startBleScan() {
        if (isScanning || isConnected) return;

        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bluetoothLeScanner == null) return;

        // 仅扫描指定 Service UUID 的设备，节省功耗
        ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(SERVICE_UUID))
                .build();

        // 低延迟模式，尽快发现设备
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        isScanning = true;
        bluetoothLeScanner.startScan(Collections.singletonList(filter), settings, scanCallback);
        Log.d(TAG, "Starting BLE Scan...");
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            BluetoothDevice device = result.getDevice();
            if (device != null) {
                Log.d(TAG, "Device found: " + device.getAddress());

                // 找到设备后立即停止扫描并连接
                if (isScanning) {
                    bluetoothLeScanner.stopScan(this);
                    isScanning = false;
                }
                connectToDevice(device);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "Scan failed with error: " + errorCode);
            isScanning = false;
            // 扫描失败，2秒后重试
            mainHandler.postDelayed(() -> startBleScan(), 2000);
        }
    };

    private void connectToDevice(BluetoothDevice device) {
        Log.d(TAG, "Connecting to " + device.getAddress());
        // Android 6.0+ 使用 TRANSPORT_LE 可能会更稳定
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } else {
            bluetoothGatt = device.connectGatt(this, false, gattCallback);
        }
    }

    // ==========================================
    // BLE 回调处理
    // ==========================================

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to GATT server.");
                isConnected = true;

                // 连接成功，取消“枪支失联”报警计时
                if (alarmRunnable != null) {
                    mainHandler.removeCallbacks(alarmRunnable);
                    alarmRunnable = null;
                }

                // 开始发现服务
                gatt.discoverServices();

                // 启动 RSSI 定时轮询
                startRssiPolling();

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnected from GATT server.");
                isConnected = false;

                if (bluetoothGatt != null) {
                    bluetoothGatt.close();
                    bluetoothGatt = null;
                }

                // 停止 RSSI 轮询
                stopRssiPolling();

                // 触发断连报警计时逻辑 (10秒后上报枪支失联)
                scheduleGunLostAlarm();

                // 1秒内启动重连机制
                mainHandler.postDelayed(() -> startBleScan(), RECONNECT_DELAY_MS);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattCharacteristic characteristic = gatt.getService(SERVICE_UUID)
                        .getCharacteristic(CHAR_UUID);

                if (characteristic != null) {
                    // 开启特征值通知 (Notification)
                    gatt.setCharacteristicNotification(characteristic, true);

                    // 写 Descriptor 真正启用通知
                    BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
                    if (descriptor != null) {
                        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        gatt.writeDescriptor(descriptor);
                    }
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            // 收到数据回调
            byte[] data = characteristic.getValue();
            processData(data);
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                processRssi(rssi);
            }
        }
    };

    // ==========================================
    // 业务逻辑引擎 (Business Logic)
    // ==========================================

    /**
     * 数据协议解析
     * 协议格式: [HEAD(0xA5), EVENT, ...]
     */
    private void processData(byte[] data) {
        if (data == null || data.length < 1) return;

        // 包头校验
        if (data[0] != HEADER_BYTE) return;

        if (data.length > 1) {
            byte event = data[1];
            switch (event) {
                case EVENT_SHOT: // 0x01
                    Log.i(TAG, "Event: GUN SHOT");
                    triggerLocalVibration();
                    // 这里假设后续字节可能有G值等数据，实际解析略
                    sendMqttMessage("GUN_SHOT", "G-Force data...");
                    break;
                case EVENT_DROP: // 0x02
                    Log.i(TAG, "Event: GUN DROP");
                    sendMqttMessage("GUN_DROP", null);
                    break;
                default:
                    Log.d(TAG, "Unknown event: " + event);
                    break;
            }
        }
    }

    /**
     * 启动 RSSI 轮询
     */
    private void startRssiPolling() {
        rssiWindow.clear();
        rssiPollRunnable = new Runnable() {
            @Override
            public void run() {
                if (bluetoothGatt != null && isConnected) {
                    bluetoothGatt.readRemoteRssi(); // 异步触发 onReadRemoteRssi
                    mainHandler.postDelayed(this, 1000); // 每秒采集一次
                }
            }
        };
        mainHandler.post(rssiPollRunnable);
    }

    private void stopRssiPolling() {
        if (rssiPollRunnable != null) {
            mainHandler.removeCallbacks(rssiPollRunnable);
        }
        // 重置状态
        lowRssiStartTime = 0;
        rssiWindow.clear();
    }

    /**
     * RSSI 滤波与人枪分离预警算法
     */
    private void processRssi(int rssi) {
        // 维护滑动窗口
        if (rssiWindow.size() >= RSSI_WINDOW_SIZE) {
            rssiWindow.remove(0); // 移除最旧的
        }
        rssiWindow.add(rssi);

        // 窗口未满不计算
        if (rssiWindow.size() < RSSI_WINDOW_SIZE) return;

        // 算法：去掉最低值后取平均值
        List<Integer> sorted = new ArrayList<>(rssiWindow);
        Collections.sort(sorted);
        sorted.remove(0); // 去掉最小值 (滤波)

        double sum = 0;
        for (int val : sorted) sum += val;
        double avgRssi = sum / sorted.size();

        Log.d(TAG, "Filtered RSSI: " + avgRssi);

        // 预警逻辑: 若 平均RSSI < -85dBm 持续 5秒
        if (avgRssi < RSSI_WARNING_THRESHOLD) {
            if (lowRssiStartTime == 0) {
                lowRssiStartTime = System.currentTimeMillis();
            } else {
                long duration = System.currentTimeMillis() - lowRssiStartTime;
                if (duration >= WARNING_DURATION_MS) {
                    triggerLocalBeep();
                }
            }
        } else {
            lowRssiStartTime = 0; // RSSI恢复正常，重置计时
        }
    }

    /**
     * 安排“枪支失联”报警
     */
    private void scheduleGunLostAlarm() {
        alarmRunnable = () -> {
            // 如果10秒后仍未连接
            if (!isConnected) {
                Log.e(TAG, "ALARM: GUN LOST (Disconnected > 10s)");
                sendMqttMessage("GUN_LOST", "Connection timeout > 10s");
            }
        };
        mainHandler.postDelayed(alarmRunnable, ALARM_DURATION_MS);
    }

    // ==========================================
    // 上报与报警 (Reporting & Alerting)
    // ==========================================

    private void sendMqttMessage(String eventType, String extraInfo) {
        // 模拟 JSON 数据包构建
        try {
            JSONObject json = new JSONObject();
            json.put("device_id", "REC_8848_V5"); // 记录仪ID
            json.put("police_id", "PC_9527");     // 警员ID
            json.put("gps_location", "30.1234,120.5678"); // 模拟经纬度
            json.put("event_type", eventType);
            json.put("timestamp", System.currentTimeMillis());
            if (extraInfo != null) {
                json.put("extra_info", extraInfo);
            }

            String mqttPayload = json.toString();
            Log.i(TAG, ">>> MQTT UPLOAD: " + mqttPayload);

            // TODO: 调用实际的 MQTT Client 发送数据
            // MqttManager.getInstance().publish("police/gun/event", mqttPayload);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void triggerLocalVibration() {
        Log.w(TAG, ">>> LOCAL ALERT: VIBRATION <<<");
        // TODO: 调用 Vibrator 服务
        // Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        // v.vibrate(1000);
    }

    private void triggerLocalBeep() {
        Log.w(TAG, ">>> LOCAL WARNING: BEEP (Low RSSI) <<<");
        // TODO: 调用 ToneGenerator 或 MediaPlayer 播放警示音
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // 释放资源
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (bluetoothGatt != null) {
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
        stopRssiPolling();
    }
}
