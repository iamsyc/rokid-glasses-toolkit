package local.syc.rokidadb;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ToolsActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView report;
    private boolean networkPage;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        networkPage = "network".equals(getIntent().getStringExtra("page"));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(12, 8, 12, 8);
        content.setBackgroundColor(Color.BLACK);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
        addButton(content, "返回助手", this::finish);
        if (networkPage) {
            addButton(content, "刷新联网状态", this::refresh);
            addButton(content, "尝试恢复 Wi-Fi", () -> executor.execute(() -> {
                AutoWifi.enableNow(this, "manual-tools");
                runOnUiThread(this::refresh);
            }));
            addButton(content, "复制诊断报告", () -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("眼镜联网诊断", report.getText()));
                android.widget.Toast.makeText(this, "已复制到眼镜剪贴板", android.widget.Toast.LENGTH_SHORT).show();
            });
        } else {
            LinearLayout brightness = new LinearLayout(this);
            brightness.setOrientation(LinearLayout.HORIZONTAL);
            content.addView(brightness);
            addBrightnessButton(brightness, "亮度 20%", 50);
            addBrightnessButton(brightness, "亮度 35%", 89);
            addButton(content, "恢复原亮度", () -> restoreBrightness());
            addButton(content, "Wi-Fi 设置", () -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
            addButton(content, "无线调试与开发者设置", () -> open(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)));
            addButton(content, "显示与休眠设置", () -> open(new Intent(Settings.ACTION_DISPLAY_SETTINGS)));
            addButton(content, "助手权限与电池设置", () -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()))));
            addButton(content, "系统完整设置", () -> open(new Intent(Settings.ACTION_SETTINGS)));
            addButton(content, "刷新授权状态", this::refresh);
        }
        report = new TextView(this);
        report.setTextColor(Color.WHITE);
        report.setTextSize(16);
        content.addView(report);
        ScreenStyle.apply(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void addButton(LinearLayout content, String title, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setOnClickListener(view -> action.run());
        content.addView(button);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (RuntimeException exception) {
            report.setText("当前固件无法打开此页：" + exception);
        }
    }

    private String permission(String name) {
        return checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED ? "已授权" : "未授权";
    }

    private void restoreBrightness() {
        int value = getSharedPreferences("display_profiles", MODE_PRIVATE).getInt("original_brightness", 136);
        try {
            Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, value);
            refresh();
        } catch (RuntimeException exception) { report.setText("亮度恢复失败：" + exception); }
    }

    private void addBrightnessButton(LinearLayout content, String title, int value) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setOnClickListener(view -> {
            try {
                android.content.SharedPreferences saved = getSharedPreferences("display_profiles", MODE_PRIVATE);
                if (!saved.contains("original_brightness")) saved.edit().putInt("original_brightness",
                        Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 136)).commit();
                boolean written = Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, value);
                if (!written || Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, -1) != value) {
                    throw new IllegalStateException("系统未接受或已覆盖亮度设置");
                }
                refresh();
            } catch (RuntimeException exception) {
                report.setText("亮度调整未完成：" + exception + "\n可通过电脑 ADB 或系统显示设置调整。");
            }
        });
        content.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
    }

    @SuppressWarnings("deprecation")
    private void refresh() {
        StringBuilder text = new StringBuilder();
        try {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            text.append("助手 1.7\n开机已运行：").append(SystemClock.elapsedRealtime() / 1000).append(" 秒");
            text.append("\n开机联网：").append(AutoWifi.isEnabled(this) ? "启用" : "停用");
            text.append("\n省电豁免：").append(power.isIgnoringBatteryOptimizations(getPackageName()) ? "已加入" : "未加入");
            if (networkPage) {
                WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo information = wifi.getConnectionInfo();
                text.append("\nWi-Fi 状态：").append(wifiState(wifi.getWifiState()));
                if (information != null) {
                    int address = information.getIpAddress();
                    text.append("\n网络：").append(information.getSSID());
                    text.append("\n连接阶段：").append(information.getSupplicantState());
                    text.append("\nIPv4：").append(address == 0 ? "尚未取得" :
                            (address & 255) + "." + ((address >> 8) & 255) + "."
                                    + ((address >> 16) & 255) + "." + ((address >> 24) & 255));
                    text.append("\n信号：").append(information.getRssi()).append(" dBm");
                }
                ConnectivityManager connections = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                Network network = connections.getActiveNetwork();
                NetworkCapabilities capabilities = connections.getNetworkCapabilities(network);
                text.append("\n系统互联网验证：").append(capabilities != null && capabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? "通过" : "未通过或未检测，局域网仍可能可用");
                text.append("\n\n最近 40 条联网操作（新到旧）\n").append(AutoWifi.history(this));
                text.append("\n\n只记录本助手的请求和检查结果，不代表完整系统连接日志。未记录 Wi-Fi 密码。");
            } else {
                text.append("\n安卓亮度：").append(Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, -1)).append(" / 255");
                int night = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
                text.append("\n系统深色模式：").append(night == android.content.res.Configuration.UI_MODE_NIGHT_YES ? "启用" : "未启用");
                text.append("\n安全设置写入：").append(permission(Manifest.permission.WRITE_SECURE_SETTINGS));
                text.append("\n精确位置：").append(permission(Manifest.permission.ACCESS_FINE_LOCATION));
                text.append("\n后台位置：").append(permission(Manifest.permission.ACCESS_BACKGROUND_LOCATION));
                text.append("\n读取图片：").append(permission(Manifest.permission.READ_EXTERNAL_STORAGE));
                text.append("\n删除图片兼容权限：").append(permission(Manifest.permission.WRITE_EXTERNAL_STORAGE));
                text.append("\n开发者选项：").append(Settings.Global.getInt(getContentResolver(), "development_settings_enabled", 0));
                text.append("\n无线调试：").append(Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", 0));
                text.append("\n\n位置授权用于读取已保存 Wi-Fi 信息。未申请相机、麦克风、无障碍权限。");
                text.append("\n现有 TCP 5555 保留，系统操作按当前家庭网络配置执行。");
            }
        } catch (RuntimeException exception) {
            text.append("\n读取失败：").append(exception);
        }
        report.setText(text.toString());
    }

    private String wifiState(int state) {
        switch (state) {
            case WifiManager.WIFI_STATE_ENABLED: return "已开启";
            case WifiManager.WIFI_STATE_DISABLED: return "已关闭";
            case WifiManager.WIFI_STATE_ENABLING: return "正在开启";
            case WifiManager.WIFI_STATE_DISABLING: return "正在关闭";
            default: return "未知";
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
