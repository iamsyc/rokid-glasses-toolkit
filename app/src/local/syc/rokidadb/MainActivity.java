package local.syc.rokidadb;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.graphics.Color;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<Button> controls = new ArrayList<>();
    private TextView output;
    private TextView status;
    private Button autoWifiButton;
    private boolean busy;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(16, 12, 16, 12);
        content.setBackgroundColor(Color.BLACK);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
        TextView title = text("眼镜 ADB 助手", 24);
        content.addView(title);
        status = text("正在检测…", 20);
        content.addView(status);
        button(content, "联网状态与日志", () -> startActivity(new Intent(this, ToolsActivity.class).putExtra("page", "network")));
        button(content, "照片与截图管理", () -> startActivity(new Intent(this, PhotoActivity.class)));
        button(content, "设置与权限", () -> startActivity(new Intent(this, ToolsActivity.class).putExtra("page", "settings")));
        button(content, "系统优化与恢复", () -> startActivity(new Intent(this, MaintenanceActivity.class)));
        autoWifiButton = new Button(this);
        autoWifiButton.setTextSize(18);
        autoWifiButton.setAllCaps(false);
        autoWifiButton.setOnClickListener(view -> {
            AutoWifi.setEnabled(this, !AutoWifi.isEnabled(this));
            updateWifiButton();
        });
        content.addView(autoWifiButton);
        updateWifiButton();
        button(content, "立即开启 Wi-Fi", () -> executor.execute(() -> {
            String result = AutoWifi.enableNow(this, "manual");
            runOnUiThread(() -> output.append("\n" + result));
        }));
        content.addView(text("显示本机 IP 和真实 ADB 响应。开启操作需要系统已授予权限；权限不足会显示原始错误。", 16));
        button(content, "1  重新检测", () -> runTask(false));
        button(content, "2  尝试开启 TCP ADB", () -> runTask(true));
        button(content, "3  打开系统开发者设置", () -> openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        button(content, "打开官方扫码投屏", this::openOfficialScanner);
        button(content, "打开系统设置", () -> openSettings(Settings.ACTION_SETTINGS));
        output = text("", 16);
        output.setTextIsSelectable(true);
        content.addView(output);
        AutoWifi.schedule(this);
        executor.execute(() -> AutoWifi.enableNow(this, "launch"));
        runTask(false);
        ScreenStyle.apply(this);
    }

    private void updateWifiButton() {
        autoWifiButton.setText(AutoWifi.isEnabled(this) ? "开机自动联网：已启用（点击停用）" : "开机自动联网：已停用（点击启用）");
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Color.WHITE);
        view.setPadding(0, 6, 0, 6);
        return view;
    }

    private void button(LinearLayout parent, String label, Runnable action) {
        Button control = new Button(this);
        control.setText(label);
        control.setTextSize(18);
        control.setAllCaps(false);
        control.setOnClickListener(view -> action.run());
        parent.addView(control, new LinearLayout.LayoutParams(-1, -2));
        controls.add(control);
    }

    private void openSettings(String action) {
        try {
            startActivity(new Intent(action));
        } catch (RuntimeException exception) {
            output.append("\n系统未提供此设置页：" + exception.getClass().getSimpleName());
        }
    }

    private void openOfficialScanner() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("com.rokid.glass.mirrorscan");
        if (launch == null) {
            output.append("\n未找到可启动的官方扫码投屏应用，请先安装官方签名 APK。");
            return;
        }
        try {
            startActivity(launch);
        } catch (RuntimeException exception) {
            output.append("\n官方扫码应用启动失败：" + exception);
        }
    }

    private void runTask(boolean enable) {
        if (busy) {
            return;
        }
        busy = true;
        for (Button control : controls) {
            control.setEnabled(false);
        }
        status.setText(enable ? "正在尝试开启并验证…" : "正在检测…");
        executor.execute(() -> {
            StringBuilder report = new StringBuilder();
            String summary;
            try {
                report.append("设备：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL);
                report.append("\nAndroid ").append(Build.VERSION.RELEASE).append(" / API ").append(Build.VERSION.SDK_INT);
                report.append("\n系统：").append(Build.DISPLAY).append('\n');
                report.append("\n开机联网：").append(AutoWifi.isEnabled(this) ? "已启用" : "已停用");
                report.append("\n上次联网操作：").append(AutoWifi.lastResult(this)).append('\n');
                List<String> addresses = addresses(report);
                boolean secure = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
                report.append("\nWRITE_SECURE_SETTINGS：").append(secure ? "已授权" : "未授权");
                report.append("\nadb_enabled：").append(setting("adb_enabled"));
                report.append("\nadb_wifi_enabled：").append(setting("adb_wifi_enabled"));
                report.append("\nservice.adb.tcp.port：").append(command("/system/bin/getprop", "service.adb.tcp.port"));
                report.append("\npersist.adb.tcp.port：").append(command("/system/bin/getprop", "persist.adb.tcp.port"));
                report.append("\n无线调试 TLS 端口：").append(command("/system/bin/getprop", "service.adb.tls.port"));
                report.append("\ninit.svc.adbd：").append(command("/system/bin/getprop", "init.svc.adbd"));
                report.append("\n系统优化：").append(SystemProfile.enabled(this) ? "已启用，按保存配置开机应用" : "未启用");
                if (enable) {
                    enable(secure, report);
                    Thread.sleep(1500);
                }
                boolean responded = false;
                List<String> targets = new ArrayList<>();
                targets.add("127.0.0.1");
                targets.addAll(addresses);
                report.append("\n\nTCP 5555 协议检测：");
                for (String address : targets) {
                    String result = probe(address);
                    report.append('\n').append(address).append(":5555 ").append(result);
                    responded |= result.startsWith("ADB ");
                }
                summary = responded ? "已检测到 ADB 协议响应" : "未检测到 TCP 5555 ADB 响应";
                if (responded) {
                    report.append("\n\n请把上面的 Wi-Fi IP 告诉电脑端。AUTH 表示还需电脑密钥授权，STLS 表示需 TLS 配对。局域网是否可达仍需电脑验证。");
                } else {
                    report.append("\n\n普通签名 APK 无法自行取得系统权限。若开启被拒绝，请使用官方平台签名的扫码投屏 APK；若其也报授权错误，需要对应生活版固件的厂商授权。");
                }
            } catch (Exception exception) {
                summary = "检测未完成";
                report.append("\n异常：").append(exception);
            }
            String finalSummary = summary;
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    status.setText(finalSummary);
                    output.setText(report.toString());
                    for (Button control : controls) {
                        control.setEnabled(true);
                    }
                    busy = false;
                }
            });
        });
    }

    private List<String> addresses(StringBuilder report) throws Exception {
        List<String> result = new ArrayList<>();
        for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!network.isUp() || network.isLoopback()) {
                continue;
            }
            for (InetAddress address : Collections.list(network.getInetAddresses())) {
                if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                    result.add(address.getHostAddress());
                    report.append("\nIP [").append(network.getName()).append("]：").append(address.getHostAddress());
                }
            }
        }
        if (result.isEmpty()) {
            report.append("\nIPv4：未发现，请先让眼镜连接 Wi-Fi");
        }
        return result;
    }

    private String setting(String name) {
        try {
            String value = Settings.Global.getString(getContentResolver(), name);
            return value == null ? "未设置" : value;
        } catch (RuntimeException exception) {
            return exception.toString();
        }
    }

    private void enable(boolean secure, StringBuilder report) {
        report.append("\n\n开启操作结果：");
        if (secure) {
            try {
                boolean updated = Settings.Global.putInt(getContentResolver(), "adb_enabled", 1);
                report.append("\nadb_enabled=1：").append(updated ? "写入成功" : "写入失败");
            } catch (RuntimeException exception) {
                report.append("\n写入 adb_enabled：").append(exception);
            }
        } else {
            report.append("\n无法写入 adb_enabled：缺少 WRITE_SECURE_SETTINGS");
        }
        report.append("\n设置临时 TCP 端口：").append(command("/system/bin/setprop", "service.adb.tcp.port", "5555"));
        String port = command("/system/bin/getprop", "service.adb.tcp.port");
        report.append("\n端口属性回读：").append(port);
        if ("5555".equals(port)) {
            report.append("\n请求重启 adbd：").append(command("/system/bin/setprop", "ctl.restart", "adbd"));
        } else {
            report.append("\n端口未生效，跳过重启 adbd");
        }
        report.append("\n操作后 adb_enabled：").append(setting("adb_enabled"));
    }

    private String command(String... arguments) {
        Process process = null;
        try {
            process = new ProcessBuilder(arguments).redirectErrorStream(true).start();
            long deadline = android.os.SystemClock.elapsedRealtime() + 2000;
            while (true) {
                try {
                    process.exitValue();
                    break;
                } catch (IllegalThreadStateException running) {
                    if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                        process.destroy();
                        return "命令超时";
                    }
                    Thread.sleep(25);
                }
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = input.read(buffer)) != -1 && bytes.size() < 8192) {
                    bytes.write(buffer, 0, count);
                }
            }
            String value = new String(bytes.toByteArray(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                return "exit=" + process.exitValue() + " " + value;
            }
            return value.isEmpty() ? "(空)" : value;
        } catch (Exception exception) {
            return exception.toString();
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private String probe(String address) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, 5555), 600);
            socket.setSoTimeout(900);
            byte[] payload = "host::\0".getBytes(StandardCharsets.UTF_8);
            int checksum = 0;
            for (byte value : payload) {
                checksum += value & 255;
            }
            int command = 0x4e584e43;
            ByteBuffer packet = ByteBuffer.allocate(24 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
            packet.putInt(command).putInt(0x01000001).putInt(4096).putInt(payload.length).putInt(checksum).putInt(command ^ -1).put(payload);
            socket.getOutputStream().write(packet.array());
            byte[] header = new byte[24];
            new DataInputStream(socket.getInputStream()).readFully(header);
            ByteBuffer response = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            int responseCommand = response.getInt();
            response.getInt();
            response.getInt();
            int responseLength = response.getInt();
            response.getInt();
            int magic = response.getInt();
            if (magic != (responseCommand ^ -1) || responseLength < 0 || responseLength > 1048576) {
                return "端口开放，响应不符合 ADB 帧头";
            }
            if (responseCommand == 0x48545541) {
                return "ADB AUTH，等待电脑密钥授权";
            }
            if (responseCommand == 0x4e584e43) {
                return "ADB CNXN，服务接受连接";
            }
            if (responseCommand == 0x534c5453) {
                return "ADB STLS，需要 TLS 配对";
            }
            return "未知协议命令 0x" + Integer.toHexString(responseCommand);
        } catch (Exception exception) {
            return exception.getClass().getSimpleName() + ": " + exception.getMessage();
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
