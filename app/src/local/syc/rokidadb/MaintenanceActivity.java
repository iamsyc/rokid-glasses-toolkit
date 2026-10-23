package local.syc.rokidadb;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.graphics.Color;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;

public final class MaintenanceActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<Button> buttons = new ArrayList<>();
    private TextView report;
    private TextView summary;
    private Button logging, idle, display;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(14, 10, 14, 10); content.setBackgroundColor(Color.BLACK);
        ScrollView scroll = new ScrollView(this); scroll.addView(content); setContentView(scroll);
        add(content, "返回助手", this::finish);
        summary = new TextView(this); summary.setTextSize(18); content.addView(summary);
        add(content, "应用全部优化", () -> run(() -> SystemProfile.apply(this, true, true, true, "手动应用")));
        logging = add(content, "", () -> change("logging"));
        idle = add(content, "", () -> change("idle"));
        display = add(content, "", () -> change("display"));
        add(content, "刷新实际状态", () -> run(() -> RootBridge.status().text));
        add(content, "保存当前状态快照", () -> run(() -> SystemProfile.saveSnapshot(this)));
        add(content, "恢复优化前配置", () -> new AlertDialog.Builder(this).setTitle("恢复优化前配置")
                .setMessage("恢复原日志、待机、系统配色、亮度和休眠时间，并停止开机应用优化。原助手和无线 ADB 保留。")
                .setNegativeButton("取消", null).setPositiveButton("恢复", (dialog, choice) -> run(() -> SystemProfile.restore(this))).show());
        report = new TextView(this); report.setTextSize(15); report.setTextIsSelectable(true); report.setPadding(0, 10, 0, 8);
        content.addView(report); update(); ScreenStyle.apply(this);
        run(() -> RootBridge.status().text);
    }

    private Button add(LinearLayout parent, String label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setTextSize(17);
        button.setOnClickListener(view -> action.run()); parent.addView(button); buttons.add(button); return button;
    }

    private void change(String key) {
        boolean logs = SystemProfile.option(this, "logging"), doze = SystemProfile.option(this, "idle"), colors = SystemProfile.option(this, "display");
        if ("logging".equals(key)) logs = !logs;
        if ("idle".equals(key)) doze = !doze;
        if ("display".equals(key)) colors = !colors;
        final boolean a = logs, b = doze, c = colors;
        run(() -> SystemProfile.apply(this, a, b, c, "手动调整"));
    }

    private void update() {
        summary.setText("系统优化 · 助手 1.7\n开机应用：" + (SystemProfile.enabled(this) ? "启用" : "停用"));
        logging.setText("精简日志：" + (SystemProfile.option(this, "logging") ? "已启用" : "未启用"));
        idle.setText("轻度待机：" + (SystemProfile.option(this, "idle") ? "已启用" : "未启用"));
        display.setText("系统黑底配色：" + (SystemProfile.option(this, "display") ? "已启用" : "未启用"));
    }

    private void run(Callable<String> task) {
        for (Button button : buttons) button.setEnabled(false);
        report.setText("正在读取或应用，请稍候…");
        executor.execute(() -> {
            String result;
            try { result = task.call(); } catch (Exception exception) { result = exception.toString(); }
            final String message = result;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                report.setText(message); update();
                for (Button button : buttons) button.setEnabled(true);
            });
        });
    }

    @Override protected void onDestroy() { executor.shutdownNow(); super.onDestroy(); }
}
