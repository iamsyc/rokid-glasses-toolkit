package local.syc.rokidadb;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

final class SystemProfile {
    private static final String[] PHONE_PACKAGES = {"com.rokid.os.sprite.assistserver", "com.rokid.cxrservice"};
    private static final String[] OVERLAYS = {"RokidBlackBackground", "RokidBlackFloating", "RokidGreenAccent",
            "RokidBlackSurface", "RokidBlackVariant", "RokidDimSwitch"};
    private SystemProfile() {}

    static SharedPreferences prefs(Context context) { return context.getSharedPreferences("system_profile", Context.MODE_PRIVATE); }
    static boolean enabled(Context context) { return prefs(context).getBoolean("enabled", false); }
    static boolean option(Context context, String key) { return prefs(context).getBoolean(key, false); }
    private static File baselineFile(Context context) { return new File(context.getFilesDir(), "optimization-baseline.json"); }

    private static void write(File target, JSONObject data) throws Exception {
        File temporary = new File(target.getPath() + ".tmp");
        try (FileOutputStream stream = new FileOutputStream(temporary)) {
            stream.write(data.toString(2).getBytes(StandardCharsets.UTF_8)); stream.getFD().sync();
        }
        if (!temporary.renameTo(target)) throw new IllegalStateException("无法保存备份");
    }

    private static JSONObject read(File target) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (FileInputStream stream = new FileInputStream(target)) {
            byte[] buffer = new byte[2048]; int count;
            while ((count = stream.read(buffer)) >= 0) {
                if (bytes.size() + count > 65536) throw new IllegalStateException("备份格式无效");
                bytes.write(buffer, 0, count);
            }
        }
        return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
    }

    private static String field(String raw, String key) {
        for (String line : raw.split("\n")) if (line.startsWith(key + "=")) return line.substring(key.length() + 1).trim();
        return "";
    }

    private static int number(String value, int fallback) {
        try { int n = Integer.parseInt(value); return n >= 0 && n <= 1000000 ? n : fallback; }
        catch (NumberFormatException exception) { return fallback; }
    }

    private static JSONObject ensureBaseline(Context context) throws Exception {
        File file = baselineFile(context);
        if (file.exists()) return read(file);
        RootBridge.Result result = RootBridge.run("id; echo RKH_LIGHT=$(cmd deviceidle enabled light); "
                + "echo RKH_DEEP=$(cmd deviceidle enabled deep); echo RKH_CNSS=$(getprop init.svc.vendor.cnss_diag); "
                + "echo RKH_LOG_COUNT=$(getprop persist.logd.logpersistd.count); echo RKH_LOG_SIZE=$(getprop persist.logd.logpersistd.size); "
                + "echo RKH_LOG_ENABLED=$(getprop logd.logpersistd.enable); cmd deviceidle whitelist");
        if (!result.ok() || !result.text.contains("uid=0(root)")) throw new IllegalStateException("未取得系统操作权限：" + result.text);
        JSONObject data = new JSONObject();
        data.put("schema", 1).put("created_ms", System.currentTimeMillis()).put("raw", result.text);
        data.put("light", "1".equals(field(result.text, "RKH_LIGHT"))).put("deep", "1".equals(field(result.text, "RKH_DEEP")));
        data.put("cnss_running", "running".equals(field(result.text, "RKH_CNSS")));
        data.put("log_count", number(field(result.text, "RKH_LOG_COUNT"), 256));
        data.put("log_size", number(field(result.text, "RKH_LOG_SIZE"), 256));
        data.put("log_enabled", "true".equals(field(result.text, "RKH_LOG_ENABLED")));
        data.put("brightness", Settings.System.getInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 136));
        data.put("timeout", Settings.System.getInt(context.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 10000));
        data.put("night", (context.getResources().getConfiguration().uiMode & 48) == 32);
        data.put("auto_wifi", AutoWifi.isEnabled(context));
        write(file, data); return data;
    }

    private static String overlayCommands(boolean enable) {
        StringBuilder command = new StringBuilder();
        if (enable) {
            command.append("cmd overlay fabricate --target android --name RokidBlackBackground android:color/background_material_dark 0x1c 0xff000000; ");
            command.append("cmd overlay fabricate --target android --name RokidBlackFloating android:color/background_floating_material_dark 0x1c 0xff000000; ");
            command.append("cmd overlay fabricate --target android --name RokidGreenAccent android:color/accent_material_dark 0x1c 0xff66cc88; ");
            command.append("cmd overlay fabricate --target android --name RokidBlackSurface android:color/system_neutral1_900 0x1c 0xff000000; ");
            command.append("cmd overlay fabricate --target android --name RokidBlackVariant android:color/system_neutral2_900 0x1c 0xff000000; ");
            command.append("cmd overlay fabricate --target android --name RokidDimSwitch android:color/system_accent1_100 0x1c 0xff5d8766; ");
        }
        for (String name : OVERLAYS) {
            if (enable) command.append("cmd overlay enable --user 0 com.android.shell:").append(name).append("; ");
            else command.append("if cmd overlay list android | grep -q 'com.android.shell:").append(name)
                    .append("'; then cmd overlay disable --user 0 com.android.shell:").append(name).append("; fi; ");
        }
        return command.toString();
    }

    private static String script(JSONObject baseline, boolean logging, boolean idle, boolean display) throws Exception {
        StringBuilder command = new StringBuilder("set -e; ");
        command.append(logging ? "stop vendor.cnss_diag; " : (baseline.getBoolean("cnss_running") ? "start vendor.cnss_diag; " : "stop vendor.cnss_diag; "));
        command.append("setprop persist.logd.logpersistd.count ").append(logging ? 8 : baseline.getInt("log_count")).append("; ");
        command.append("setprop persist.logd.logpersistd.size ").append(logging ? 8 : baseline.getInt("log_size")).append("; ");
        command.append("setprop logd.logpersistd.size ").append(logging ? 8 : baseline.getInt("log_size")).append("; ");
        command.append("setprop logd.logpersistd.enable ").append(logging || baseline.getBoolean("log_enabled") ? "true" : "false").append("; ");
        command.append("stop logcatd; ");
        if (logging || baseline.getBoolean("log_enabled")) command.append("start logcatd; ");
        command.append("cmd deviceidle ").append(idle || baseline.getBoolean("light") ? "enable" : "disable").append(" light; ");
        command.append("cmd deviceidle ").append(!idle && baseline.getBoolean("deep") ? "enable" : "disable").append(" deep; ");
        for (String name : PHONE_PACKAGES) {
            if (idle) command.append("cmd deviceidle whitelist +").append(name).append("; ");
            else if (!baseline.getString("raw").contains("," + name + ",")) command.append("cmd deviceidle whitelist -").append(name).append("; ");
        }
        command.append(overlayCommands(display));
        command.append("am force-stop com.android.settings; ");
        if (display) command.append("cmd uimode night yes; ");
        command.append("id; echo RKH_PROFILE_APPLIED");
        return command.toString();
    }

    static synchronized String apply(Context context, boolean logging, boolean idle, boolean display, String reason) {
        try {
            JSONObject baseline = ensureBaseline(context);
            RootBridge.Result result = RootBridge.run(script(baseline, logging, idle, display));
            if (!result.ok() || !result.text.contains("RKH_PROFILE_APPLIED")) throw new IllegalStateException(result.text);
            prefs(context).edit().putBoolean("enabled", true).putBoolean("logging", logging).putBoolean("idle", idle)
                    .putBoolean("display", display).putInt("applied_boot", bootCount(context))
                    .putString("last_result", reason + "：配置已应用；开机+" + SystemClock.elapsedRealtime() / 1000 + "s").commit();
            return prefs(context).getString("last_result", "") + "\n" + result.text;
        } catch (Exception exception) {
            String message = reason + "：未完成，" + exception;
            prefs(context).edit().putString("last_result", message).commit(); return message;
        }
    }

    static String restore(Context context) {
        try {
            if (!baselineFile(context).exists()) return "尚未应用优化，没有需要恢复的配置";
            JSONObject baseline = read(baselineFile(context));
            String command = script(baseline, false, false, false)
                    + "; settings put system screen_brightness " + baseline.getInt("brightness")
                    + "; settings put system screen_off_timeout " + baseline.getInt("timeout")
                    + "; cmd uimode night " + (baseline.getBoolean("night") ? "yes" : "no");
            RootBridge.Result result = RootBridge.run(command);
            if (!result.ok()) throw new IllegalStateException(result.text);
            prefs(context).edit().putBoolean("enabled", false).putBoolean("logging", false).putBoolean("idle", false)
                    .putBoolean("display", false).putString("last_result", "已恢复优化前配置").commit();
            AutoWifi.setEnabled(context, baseline.getBoolean("auto_wifi"));
            return "已恢复优化前配置\n" + result.text;
        } catch (Exception exception) { return "恢复未完成：" + exception; }
    }

    static String saveSnapshot(Context context) {
        try {
            RootBridge.Result status = RootBridge.status();
            if (!status.ok()) throw new IllegalStateException(status.text);
            JSONObject data = new JSONObject().put("created_ms", System.currentTimeMillis()).put("root_state", status.text)
                    .put("enabled", enabled(context)).put("logging", option(context, "logging"))
                    .put("idle", option(context, "idle")).put("display", option(context, "display"));
            File folder = context.getExternalFilesDir(null);
            if (folder == null) folder = context.getFilesDir();
            File target = new File(folder, "system-state-snapshot.json"); write(target, data);
            return "当前状态已保存：\n" + target;
        } catch (Exception exception) { return "备份未完成：" + exception; }
    }

    private static int bootCount(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), "boot_count", -1);
    }

    static void applyAfterBoot(Context context, String reason) {
        if (!enabled(context)) return;
        if (!android.content.Intent.ACTION_MY_PACKAGE_REPLACED.equals(reason)
                && prefs(context).getInt("applied_boot", -2) == bootCount(context)) return;
        apply(context, option(context, "logging"), option(context, "idle"), option(context, "display"), reason);
    }
}
