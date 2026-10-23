package local.syc.rokidadb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class WifiBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !AutoWifi.RESTORE.equals(action)) {
            return;
        }
        Log.i("RokidAutoWifi", "receiver " + action);
        if (!AutoWifi.isEnabled(context) && !SystemProfile.enabled(context)) {
            return;
        }
        if (!AutoWifi.RESTORE.equals(action)) {
            AutoWifi.schedule(context);
        }
        PendingResult result = goAsync();
        Context application = context.getApplicationContext();
        new Thread(() -> {
            try {
                if (AutoWifi.isEnabled(application)) AutoWifi.enableNow(application, action);
                SystemProfile.applyAfterBoot(application, action);
            } finally {
                result.finish();
            }
        }, "RokidAutoWifi").start();
    }
}
