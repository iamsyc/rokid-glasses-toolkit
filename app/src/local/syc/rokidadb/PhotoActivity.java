package local.syc.rokidadb;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PhotoActivity extends Activity {
    private static final int PAGE_SIZE = 20;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<Photo> selected = new ArrayList<>();
    private LinearLayout rows;
    private TextView status;
    private Button deleteButton;
    private Button previousButton;
    private Button nextButton;
    private Button refreshButton;
    private int offset;
    private int totalPhotos;
    private boolean busy;
    private String notice = "";

    private static final class Photo {
        final Uri uri;
        final String name;
        final long size;

        Photo(long identifier, String name, long size) {
            this.uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, identifier);
            this.name = name == null ? "未命名图片" : name;
            this.size = size;
        }
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(12, 8, 12, 8);
        content.setBackgroundColor(Color.BLACK);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
        addButton(content, "返回助手", this::finish);
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(16);
        content.addView(status);
        refreshButton = addButton(content, "刷新图片列表", this::load);
        deleteButton = addButton(content, "删除所选（0）", this::confirmDelete);
        previousButton = addButton(content, "上一页", () -> { offset = Math.max(0, offset - PAGE_SIZE); load(); });
        nextButton = addButton(content, "下一页", () -> { offset += PAGE_SIZE; load(); });
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        content.addView(rows);
        load();
        ScreenStyle.apply(this);
    }

    private Button addButton(LinearLayout content, String title, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setOnClickListener(view -> action.run());
        content.addView(button);
        return button;
    }

    private void setBusy(boolean value) {
        busy = value;
        deleteButton.setEnabled(!value && !selected.isEmpty());
        refreshButton.setEnabled(!value);
        previousButton.setEnabled(!value && offset > 0);
        nextButton.setEnabled(!value && offset + PAGE_SIZE < totalPhotos);
        rows.setVisibility(value ? android.view.View.GONE : android.view.View.VISIBLE);
    }

    private boolean alive() {
        return !isFinishing() && !isDestroyed();
    }

    private void load() {
        if (busy) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            status.setText("需要图片存储权限，仅用于查看和管理眼镜上的照片与截图。");
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 10);
            return;
        }
        selected.clear();
        deleteButton.setText("删除所选（0）");
        rows.removeAllViews();
        setBusy(true);
        status.setText("正在读取图片…");
        executor.execute(() -> {
            List<Photo> photos = new ArrayList<>();
            int total = 0;
            String failure = "";
            try (Cursor cursor = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                            MediaStore.Images.Media.SIZE}, null, null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC, " + MediaStore.Images.Media._ID + " DESC")) {
                if (cursor == null) {
                    throw new IllegalStateException("系统图片数据库未返回结果");
                }
                total = cursor.getCount();
                if (offset >= total) {
                    offset = Math.max(0, ((total - 1) / PAGE_SIZE) * PAGE_SIZE);
                }
                if (cursor.moveToPosition(offset)) {
                    do {
                        photos.add(new Photo(cursor.getLong(0), cursor.getString(1), cursor.getLong(2)));
                    } while (photos.size() < PAGE_SIZE && cursor.moveToNext());
                }
            } catch (RuntimeException exception) {
                failure = "读取失败：" + exception;
            }
            final int count = total;
            final String error = failure;
            runOnUiThread(() -> {
                if (!alive()) {
                    return;
                }
                totalPhotos = count;
                setBusy(false);
                status.setText(error.isEmpty() ? notice + "\n共 " + count + " 张图片；当前第 "
                        + (offset / PAGE_SIZE + 1) + " 页\n勾选后删除；预览中可导出。" : error);
                previousButton.setEnabled(error.isEmpty() && offset > 0);
                nextButton.setEnabled(error.isEmpty() && offset + PAGE_SIZE < count);
                for (Photo photo : photos) {
                    CheckBox check = new CheckBox(this);
                    check.setText(photo.name + "\n" + photo.size / 1024 + " KB");
                    check.setTextSize(16);
                    check.setTextColor(Color.WHITE);
                    check.setOnCheckedChangeListener((button, checked) -> {
                        if (checked) {
                            selected.add(photo);
                        } else {
                            selected.remove(photo);
                        }
                        deleteButton.setText("删除所选（" + selected.size() + "）");
                        deleteButton.setEnabled(!selected.isEmpty());
                    });
                    rows.addView(check);
                    addButton(rows, "预览 " + photo.name, () -> preview(photo));
                }
                ScreenStyle.style(rows);
            });
        });
    }

    private void preview(Photo photo) {
        if (busy) {
            return;
        }
        setBusy(true);
        status.setText("正在读取预览…");
        executor.execute(() -> {
            Bitmap bitmap = null;
            String failure = "";
            try {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                try (InputStream input = getContentResolver().openInputStream(photo.uri)) {
                    BitmapFactory.decodeStream(input, null, options);
                }
                options.inSampleSize = 1;
                while (options.outWidth / options.inSampleSize > 640 || options.outHeight / options.inSampleSize > 640) {
                    options.inSampleSize *= 2;
                }
                options.inJustDecodeBounds = false;
                try (InputStream input = getContentResolver().openInputStream(photo.uri)) {
                    bitmap = BitmapFactory.decodeStream(input, null, options);
                }
                if (bitmap == null) {
                    throw new IllegalStateException("图片无法解码");
                }
            } catch (Exception exception) {
                failure = "预览失败：" + exception;
            }
            final Bitmap image = bitmap;
            final String error = failure;
            runOnUiThread(() -> {
                if (!alive()) {
                    if (image != null) {
                        image.recycle();
                    }
                    return;
                }
                setBusy(false);
                status.setText(error.isEmpty() ? photo.name : error);
                if (image != null) {
                    ImageView view = new ImageView(this);
                    view.setImageBitmap(image);
                    view.setAdjustViewBounds(true);
                    view.setMaxHeight(280);
                    AlertDialog dialog = new AlertDialog.Builder(this).setTitle(photo.name).setView(view)
                            .setPositiveButton("关闭", null).setNeutralButton("导出", (prompt, choice) -> share(photo)).create();
                    dialog.setOnDismissListener(prompt -> { view.setImageDrawable(null); image.recycle(); });
                    dialog.show();
                    ScreenStyle.style(dialog.getWindow().getDecorView());
                }
                refreshButton.setEnabled(true);
            });
        });
    }

    private void share(Photo photo) {
        try {
            String type = getContentResolver().getType(photo.uri);
            Intent send = new Intent(Intent.ACTION_SEND).setType(type == null ? "image/*" : type)
                    .putExtra(Intent.EXTRA_STREAM, photo.uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(android.content.ClipData.newRawUri(photo.name, photo.uri));
            startActivity(Intent.createChooser(send, "导出图片"));
        } catch (RuntimeException exception) {
            status.setText("无法导出：" + exception + "\n可用电脑 ADB 导出原图。");
        }
    }

    private void confirmDelete() {
        if (busy || selected.isEmpty()) {
            return;
        }
        List<Photo> confirmed = new ArrayList<>(selected);
        new AlertDialog.Builder(this).setTitle("删除 " + confirmed.size() + " 张图片？")
                .setMessage("仅删除本页已勾选的图片，操作无法撤销。请确认已保存需要的原图。")
                .setNegativeButton("取消", null).setPositiveButton("确认删除", (dialog, choice) -> delete(confirmed)).show();
    }

    private void delete(List<Photo> photos) {
        setBusy(true);
        executor.execute(() -> {
            int deleted = 0;
            List<Uri> denied = new ArrayList<>();
            String failure = "";
            for (Photo photo : photos) {
                try {
                    deleted += getContentResolver().delete(photo.uri, null, null);
                } catch (SecurityException exception) {
                    denied.add(photo.uri);
                } catch (RuntimeException exception) {
                    failure = exception.toString();
                }
            }
            final int count = deleted;
            final String error = failure;
            runOnUiThread(() -> {
                if (!alive()) {
                    return;
                }
                busy = false;
                notice = "已删除 " + count + " 张。" + (error.isEmpty() ? "" : "部分失败：" + error);
                if (!denied.isEmpty() && Build.VERSION.SDK_INT >= 30) {
                    try {
                        startIntentSenderForResult(MediaStore.createDeleteRequest(getContentResolver(), denied)
                                .getIntentSender(), 20, null, 0, 0, 0);
                        return;
                    } catch (Exception exception) {
                        notice += "系统删除授权未完成：" + exception;
                    }
                } else if (!denied.isEmpty()) {
                    notice += " " + denied.size() + " 张缺少删除权限。";
                }
                load();
            });
        });
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 20) {
            notice += result == RESULT_OK ? "系统删除操作已确认，以刷新后的列表为准。" : "系统删除已取消。";
            load();
        }
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 10 && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            load();
        } else {
            status.setText("未授予图片权限，图片管理暂不可用。可在助手应用权限中开启后点击刷新。");
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
