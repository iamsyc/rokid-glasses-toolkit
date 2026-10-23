# 助手安装、构建与恢复

## 安装已有实测 APK

前提是已经取得本设备可用 ADB。先用手机/原厂方式配置 Wi-Fi；本工程没有验证任意全新消费设备的首次 ADB 开启路径。以下命令均由使用者主动执行。

```sh
export GLASSES_ADB='GLASSES_IP:5555'
adb connect "$GLASSES_ADB"
adb -s "$GLASSES_ADB" shell getprop ro.product.model
adb -s "$GLASSES_ADB" shell getprop ro.build.fingerprint
python3 tools/rokid.py doctor --serial "$GLASSES_ADB"
python3 -c 'import hashlib,pathlib; p=pathlib.Path("releases/RokidAdbHelper-v1.7.apk"); assert hashlib.sha256(p.read_bytes()).hexdigest()=="a861b9d8509eddd3b538afbaddf159112e41f219519ea38c74d59e26fb24645f"; print("APK SHA-256 OK")'
adb -s "$GLASSES_ADB" install -r releases/RokidAdbHelper-v1.7.apk
```

`-r` 保留同签名应用的数据。如提示签名不兼容，不要自动卸载或清除数据。自行编译使用新密钥，不能覆盖本仓库原签名 APK；可改包名进行独立开发，但需同步更新源码中的包名常量和权限命令。

为这个包设置已验证的必要授权：

```sh
(
set -e
for permission in ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION ACCESS_BACKGROUND_LOCATION WRITE_SECURE_SETTINGS READ_EXTERNAL_STORAGE WRITE_EXTERNAL_STORAGE; do
  adb -s "$GLASSES_ADB" shell pm grant local.syc.rokidadb "android.permission.$permission"
done
adb -s "$GLASSES_ADB" shell cmd appops set local.syc.rokidadb RUN_IN_BACKGROUND allow
adb -s "$GLASSES_ADB" shell cmd appops set --uid local.syc.rokidadb COARSE_LOCATION allow
adb -s "$GLASSES_ADB" shell cmd appops set --uid local.syc.rokidadb FINE_LOCATION allow
adb -s "$GLASSES_ADB" shell cmd appops write-settings
adb -s "$GLASSES_ADB" shell dumpsys deviceidle whitelist +local.syc.rokidadb
adb -s "$GLASSES_ADB" shell am start -n local.syc.rokidadb/.MainActivity
)
```

逐条检查结果；任一权限拒绝就先排查固件与权限状态，不要继续假定授权完整。首次必须打开一次助手，确保脱离 Android 未启动/强行停止状态。检查首页自动联网为启用，打开联网状态页面确认能记住现有网络。不要在无保存网络时直接依赖重启恢复。

定位授权用于后台读取 SSID 与系统保存网络，不向网络服务发送定位信息。targetSdk 25 用于兼容已验证固件的后台启动行为；提高 targetSdk 后需重新验收开机接收和联网行为。

## 本地编译

环境：JDK、Android SDK platform 35、build-tools 35.0.0。源码使用 Java 8 语法，minSdk/targetSdk 为 25，无第三方运行依赖。

```sh
export ANDROID_HOME='/path/to/android-sdk'
sh app/build.sh
```

输出 `app/dist/RokidAdbHelper-v1.7.apk`。默认创建仅用于本地开发的忽略文件 `app/local-signing.p12`，默认开发口令 `localbuild`。发布签名请通过 `ROKID_KEYSTORE`、`ROKID_KEY_ALIAS`、`ROKID_KEYSTORE_PASSWORD` 指定自有密钥。密钥不随源码公开；密码通过环境读取，不展开到签名命令参数。

持续原位升级需要保留相同签名密钥。仓库中的预编译 APK 与重新构建 APK 可以具有不同证书和 SHA-256；目前未验证字节级可复现构建。

## 系统优化与恢复

只有相同工程版且本机 ADB、系统 `su` 可用时使用系统控制。先打开“系统优化与恢复”刷新实际状态，应用所需项目，确认返回成功，再次刷新读取结果。应用会在首次操作前保存 `files/optimization-baseline.json`，之后保留该首次基线。

“保存当前状态快照”写入应用外部文件目录的 `system-state-snapshot.json`，用于检查当前配置。快照与内部恢复基线用途不同；只有外部快照不能替代内部基线。

卸载前应先在界面执行“恢复优化前配置”并确认成功，保留应用数据直至恢复完成。原始基线丢失时，不能用其他设备的基线替代。

恢复包括本工程控制的诊断服务、日志轮转、Doze/新增豁免、六个颜色覆盖、亮度、屏幕超时、夜间模式和自动联网选择。禁用颜色后需要重新打开设置应用，旧进程会缓存资源。卸载助手不会自动撤销所有系统变更，也不会恢复消费版固件。
