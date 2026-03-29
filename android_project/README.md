# 5G执法记录仪 - 智能枪托监控模块

本项目包含核心的 Android 后台服务 `GunMonitorService`，用于连接蓝牙智能枪托并进行状态监测。

## 目录结构
```
android_project/
├── app/
│   ├── build.gradle                # 模块级构建脚本
│   └── src/main/
│       ├── java/com/police/smartbadge/
│       │   ├── GunMonitorService.java  # 核心服务代码
│       │   └── MainActivity.java       # 启动界面与权限管理
│       └── AndroidManifest.xml         # 权限与清单配置
├── build.gradle                    # 项目级构建脚本
├── settings.gradle                 # 项目配置
└── README.md                       # 说明文档
```

## 功能说明

*   **自动重连**: 蓝牙断开后，服务会自动尝试重连（延迟 1 秒）。
*   **状态监测**:
    *   **开枪**: 收到数据 `0x01`，触发振动并上报 MQTT。
    *   **跌落**: 收到数据 `0x02`，上报 MQTT。
    *   **人枪分离**: RSSI 低于 -85dBm 持续 5秒触发本地蜂鸣。
    *   **枪支失联**: 连接断开超过 10秒 触发高级报警。
*   **保活机制**: 使用前台通知 (Notification) 和 WakeLock 防止系统休眠或杀进程。

---

## 部署与构建指南

本项目已配置为标准的 Gradle Android 工程，您可以通过以下方式打包为 APK 并部署。

### 方式一：使用 Android Studio (推荐)

1.  **下载源码**: 将 `android_project` 文件夹下载到本地。
2.  **导入项目**: 打开 Android Studio -> `File` -> `Open` -> 选择 `android_project` 文件夹。
3.  **同步**: 等待 Gradle Sync 完成。如果提示更新 Gradle Wrapper，选择默认升级即可。
4.  **构建 APK**:
    *   点击菜单栏 `Build` -> `Build Bundle(s) / APK(s)` -> `Build APK(s)`。
    *   构建完成后，右下角会提示位置，通常在 `app/build/outputs/apk/debug/app-debug.apk`。

### 方式二：使用命令行 (Gradle Wrapper)

如果您有 JDK 环境 (JDK 17 推荐)，可以在项目根目录下生成 wrapper 并构建：

1.  **生成 Wrapper** (如果尚未生成):
    ```bash
    gradle wrapper
    ```
2.  **构建 Debug APK**:
    ```bash
    ./gradlew assembleDebug
    ```
    (Windows 用户使用 `gradlew.bat assembleDebug`)
3.  **获取 APK**:
    输出文件位于 `app/build/outputs/apk/debug/`。

### 方式三：安装到设备

**通过 ADB 安装:**
1.  开启执法记录仪的 **USB 调试** 模式。
2.  连接电脑，运行命令：
    ```bash
    adb install -r app/build/outputs/apk/debug/app-debug.apk
    ```

**手动安装:**
1.  将 APK 文件复制到执法记录仪存储卡中。
2.  在文件管理器中点击 APK 进行安装。

## 运行时权限说明

首次运行 APP 时，界面会请求以下权限，**请务必全部点击“允许”**，否则无法连接枪托：
1.  **定位权限** (Location): 扫描蓝牙设备必须。
2.  **附近设备** (Nearby Devices): Android 12+ 连接蓝牙必须。
3.  **通知权限** (Notification): 仅 Android 13+，用于前台服务保活显示。

## 注意事项

1.  **MQTT 实现**: `GunMonitorService.java` 中的 `sendMqttMessage` 方法目前为桩代码 (Stub)，请替换为您实际的 MQTT 客户端实现 (如 Paho MQTT)。
2.  **本地报警**: `triggerLocalVibration` 和 `triggerLocalBeep` 需要结合具体的硬件 SDK 接口进行实现。
