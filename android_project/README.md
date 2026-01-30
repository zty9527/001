# 5G执法记录仪 - 智能枪托监控模块

本项目包含核心的 Android 后台服务 `GunMonitorService`，用于连接蓝牙智能枪托并进行状态监测。

## 目录结构
```
android_project/
├── app/src/main/
│   ├── java/com/police/smartbadge/
│   │   └── GunMonitorService.java  # 核心服务代码
│   └── AndroidManifest.xml         # 权限与清单配置
└── README.md                       # 说明文档
```

## 部署步骤

### 1. 代码集成
将 `GunMonitorService.java` 复制到您的 Android 项目源码目录中：
`src/main/java/com/police/smartbadge/GunMonitorService.java`
(如果您更改了包名，请记得更新 Java 文件中的 `package` 声明)

### 2. 清单文件配置 (AndroidManifest.xml)
确保您的 `AndroidManifest.xml` 包含以下权限和服务注册：

```xml
<!-- 蓝牙与定位权限 -->
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" /> <!-- BLE扫描必需 -->
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />

<!-- 后台保活权限 -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />

<application>
    <!-- 注册服务 -->
    <service
        android:name=".GunMonitorService"
        android:enabled="true"
        android:exported="false" />
</application>
```

### 3. 运行时权限申请
在 Android 6.0 (API 23) 及以上版本，BLE 扫描需要动态申请 **定位权限** (`ACCESS_FINE_LOCATION`)。
在启动服务前，请确保您的 Activity 已经获取了该权限。

```java
// 示例：在 MainActivity 中申请权限
if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        != PackageManager.PERMISSION_GRANTED) {
    ActivityCompat.requestPermissions(this,
            new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
            REQUEST_CODE_LOCATION);
}
```

### 4. 启动服务
在您的主 Activity 或 Application 初始化流程中启动服务。建议使用 `startForegroundService` (Android 8.0+) 以确保服务作为前台服务运行。

```java
Intent serviceIntent = new Intent(this, GunMonitorService.class);
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    startForegroundService(serviceIntent);
} else {
    startService(serviceIntent);
}
```

## 功能说明

*   **自动重连**: 蓝牙断开后，服务会自动尝试重连（延迟 1 秒）。
*   **状态监测**:
    *   **开枪**: 收到数据 `0x01`，触发振动并上报 MQTT。
    *   **跌落**: 收到数据 `0x02`，上报 MQTT。
    *   **人枪分离**: RSSI 低于 -85dBm 持续 5秒触发本地蜂鸣。
    *   **枪支失联**: 连接断开超过 10秒 触发高级报警。
*   **保活机制**: 使用前台通知 (Notification) 和 WakeLock 防止系统休眠或杀进程。

## 注意事项

1.  **MQTT 实现**: `GunMonitorService.java` 中的 `sendMqttMessage` 方法目前为桩代码 (Stub)，请替换为您实际的 MQTT 客户端实现 (如 Paho MQTT)。
2.  **本地报警**: `triggerLocalVibration` 和 `triggerLocalBeep` 需要结合具体的硬件 SDK 接口进行实现。
3.  **Android 12+**: 如果设备升级到 Android 12 (API 31)，需要额外适配 `BLUETOOTH_SCAN` 和 `BLUETOOTH_CONNECT` 运行时权限。
