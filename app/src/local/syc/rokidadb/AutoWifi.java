package local.syc.rokidadb;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.SupplicantState;
import android.os.SystemClock;
import android.util.Log;
import java.util.List;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class AutoWifi {
    public static final String RESTORE = "local.syc.rokidadb.RESTORE_WIFI";
    private static final String TAG = "RokidAutoWifi";
    private static final int[] DELAYS_SECONDS = {30, 90};

    private AutoWifi() {}

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("auto_wifi", Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context) {
        return preferences(context).getBoolean("enabled", true);
    }

    public static void setEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean("enabled", enabled).apply();
        record(context, enabled ? "用户启用开机联网" : "用户停用开机联网");
        if (enabled) {
            schedule(context);
        } else {
            AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            for (int delay : DELAYS_SECONDS) {
                alarms.cancel(pending(context, delay));
            }
        }
    }

    private static PendingIntent pending(Context context, int requestCode) {
        Intent intent = new Intent(context, WifiBootReceiver.class).setAction(RESTORE);
        return PendingIntent.getBroadcast(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static void schedule(Context context) {
        if (!isEnabled(context)) {
            return;
        }
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        for (int delay : DELAYS_SECONDS) {
            alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + delay * 1000L, pending(context, delay));
        }
        Log.i(TAG, "scheduled restore checks in 30s and 90s");
    }

    @SuppressWarnings("deprecation")
    public static String enableNow(Context context, String reason) {
        if (!isEnabled(context)) {
            String result = reason + ": 开机联网已停用";
            record(context, result);
            return result;
        }
        String result;
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            int state = wifi.getWifiState();
            WifiInfo information = wifi.getConnectionInfo();
            if (state == WifiManager.WIFI_STATE_ENABLED && information != null
                    && information.getSupplicantState() == SupplicantState.COMPLETED
                    && information.getIpAddress() != 0) {
                if (information.getNetworkId() >= 0 && information.getSSID() != null
                        && !"<unknown ssid>".equals(information.getSSID())) {
                    preferences(context).edit()
                            .putInt("last_network_id", information.getNetworkId())
                            .putString("last_ssid", information.getSSID()).commit();
                    result = reason + ": 已联网，记住网络 " + information.getSSID()
                            + " id=" + information.getNetworkId();
                } else {
                    result = reason + ": 已联网，但网络名称或编号受系统权限限制";
                }
            } else if (state == WifiManager.WIFI_STATE_ENABLING) {
                result = reason + ": Wi-Fi 正在开启，等待下一次检查";
            } else if (state == WifiManager.WIFI_STATE_ENABLED) {
                result = reason + ": " + retrySavedNetwork(context, wifi, information);
            } else {
                boolean accepted = wifi.setWifiEnabled(true);
                result = reason + ": 开启 Wi-Fi 请求" + (accepted ? "已接受，等待系统连接已保存网络" : "被系统拒绝");
            }
        } catch (RuntimeException exception) {
            result = reason + ": " + exception;
        }
        record(context, result);
        Log.i(TAG, result);
        return result;
    }

    @SuppressWarnings("deprecation")
    private static String retrySavedNetwork(Context context, WifiManager wifi, WifiInfo information) {
        if (information != null) {
            SupplicantState state = information.getSupplicantState();
            if (state == SupplicantState.ASSOCIATING || state == SupplicantState.ASSOCIATED
                    || state == SupplicantState.AUTHENTICATING || state == SupplicantState.FOUR_WAY_HANDSHAKE
                    || state == SupplicantState.GROUP_HANDSHAKE || state == SupplicantState.COMPLETED) {
                return "正在连接或获取 IP，保留当前过程 " + state;
            }
        }
        int preferred = preferences(context).getInt("last_network_id", -1);
        String ssid = preferences(context).getString("last_ssid", "");
        List<WifiConfiguration> configurations = wifi.getConfiguredNetworks();
        Log.i(TAG, "saved target=" + ssid + " cachedId=" + preferred
                + " readableConfigurations=" + (configurations == null ? -1 : configurations.size()));
        WifiConfiguration selected = null;
        if (!ssid.isEmpty() && configurations != null) {
            for (WifiConfiguration configuration : configurations) {
                if (ssid.equals(configuration.SSID)) {
                    selected = configuration;
                    if (configuration.networkId == preferred) {
                        break;
                    }
                }
            }
        }
        if (selected != null) {
            preferences(context).edit().putInt("last_network_id", selected.networkId).apply();
            boolean accepted = wifi.enableNetwork(selected.networkId, true);
            boolean reconnected = wifi.reconnect();
            return "重试最后成功网络 " + ssid + " id=" + selected.networkId
                    + " enable=" + accepted + " reconnect=" + reconnected + "，尚待联网验证";
        }
        boolean accepted = wifi.reconnect();
        return "未取得最后成功网络，系统自动重连请求" + (accepted ? "已接受" : "被拒绝");
    }

    public static String lastResult(Context context) {
        return preferences(context).getString("last_result", "尚未执行");
    }

    private static synchronized void record(Context context, String result) {
        String timestamp = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date());
        String entry = timestamp + " 开机+" + SystemClock.elapsedRealtime() / 1000 + "s " + result;
        String[] previous = history(context).split("\n");
        StringBuilder entries = new StringBuilder(entry);
        for (int index = 0; index < previous.length && index < 39; index++) {
            if (!previous[index].isEmpty()) {
                entries.append('\n').append(previous[index]);
            }
        }
        preferences(context).edit().putString("last_result", entry)
                .putString("history", entries.toString()).commit();
    }

    public static String history(Context context) {
        return preferences(context).getString("history", "");
    }
}
