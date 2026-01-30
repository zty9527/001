package com.police.smartbadge;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 执法记录仪 - 枪托监控服务启动页
 * 功能：
 * 1. 申请必要的运行时权限 (蓝牙, 定位, 通知)
 * 2. 启动/停止后台监控服务
 * 3. 显示当前服务状态 (简化版)
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_CODE_PERMISSIONS = 100;

    private TextView tvStatus;
    private Button btnStartService;
    private Button btnStopService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContentView()); // 简单构建UI，不依赖XML布局文件以减少依赖

        checkAndRequestPermissions();
    }

    /**
     * 动态构建简单的UI布局，避免创建 layout xml
     */
    private View createContentView() {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setGravity(android.view.Gravity.CENTER);
        layout.setPadding(50, 50, 50, 50);

        TextView title = new TextView(this);
        title.setText("5G执法记录仪\n智能枪托监控终端");
        title.setTextSize(24);
        title.setGravity(android.view.Gravity.CENTER);
        layout.addView(title);

        tvStatus = new TextView(this);
        tvStatus.setText("状态: 未启动");
        tvStatus.setTextSize(18);
        tvStatus.setPadding(0, 50, 0, 50);
        layout.addView(tvStatus);

        btnStartService = new Button(this);
        btnStartService.setText("启动监控服务");
        btnStartService.setOnClickListener(v -> startGunService());
        layout.addView(btnStartService);

        btnStopService = new Button(this);
        btnStopService.setText("停止服务");
        btnStopService.setOnClickListener(v -> stopGunService());
        layout.addView(btnStopService);

        return layout;
    }

    private void checkAndRequestPermissions() {
        List<String> permissions = new ArrayList<>();

        // 基础定位权限 (Android 6.0+)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        // Android 12+ (API 31) 蓝牙权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }

        // Android 13+ 通知权限
        if (Build.VERSION.SDK_INT >= 33) { // Build.VERSION_CODES.TIRAMISU
            if (ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                permissions.add("android.permission.POST_NOTIFICATIONS");
            }
        }

        if (!permissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), REQUEST_CODE_PERMISSIONS);
        } else {
            // 权限已全部获取
            updateStatus("权限就绪，请启动服务");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                updateStatus("权限获取成功");
                // 自动启动服务
                startGunService();
            } else {
                updateStatus("错误: 缺少必要权限，无法工作");
                Toast.makeText(this, "必须授予蓝牙和定位权限才能连接枪托", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startGunService() {
        Intent serviceIntent = new Intent(this, GunMonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        updateStatus("服务运行中 (后台)");
        Toast.makeText(this, "枪托监控服务已启动", Toast.LENGTH_SHORT).show();
    }

    private void stopGunService() {
        Intent serviceIntent = new Intent(this, GunMonitorService.class);
        stopService(serviceIntent);
        updateStatus("服务已停止");
    }

    private void updateStatus(String msg) {
        tvStatus.setText("状态: " + msg);
        Log.d(TAG, msg);
    }
}
