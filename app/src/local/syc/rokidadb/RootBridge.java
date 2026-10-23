package local.syc.rokidadb;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Uses the device's existing local ADB transport for the helper's fixed operations. */
final class RootBridge {
    static final class Result {
        final int code;
        final String text;
        Result(int code, String text) { this.code = code; this.text = text; }
        boolean ok() { return code == 0; }
    }

    private static int command(String name) {
        return ByteBuffer.wrap(name.getBytes(StandardCharsets.US_ASCII)).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static void send(Socket socket, String name, int first, int second, byte[] data) throws Exception {
        int checksum = 0;
        for (byte value : data) checksum += value & 255;
        int cmd = command(name);
        ByteBuffer buffer = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(cmd).putInt(first).putInt(second).putInt(data.length).putInt(checksum).putInt(cmd ^ -1).put(data);
        socket.getOutputStream().write(buffer.array());
        socket.getOutputStream().flush();
    }

    static String quote(String value) { return "'" + value.replace("'", "'\"'\"'") + "'"; }

    static synchronized Result run(String fixedCommand) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 5555), 1200);
            socket.setSoTimeout(5000);
            send(socket, "CNXN", 0x01000000, 4096, "host::\0".getBytes(StandardCharsets.UTF_8));
            DataInputStream input = new DataInputStream(socket.getInputStream());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            boolean opened = false;
            long deadline = android.os.SystemClock.elapsedRealtime() + 15000;
            for (int count = 0; count < 128; count++) {
                if (android.os.SystemClock.elapsedRealtime() > deadline) throw new IllegalStateException("系统操作超时");
                byte[] header = new byte[24]; input.readFully(header);
                ByteBuffer frame = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
                int cmd = frame.getInt(), first = frame.getInt(), second = frame.getInt(), size = frame.getInt();
                int expectedChecksum = frame.getInt(), magic = frame.getInt();
                if (magic != (cmd ^ -1) || size < 0 || size > 1048576) throw new IllegalStateException("ADB 帧无效");
                byte[] data = new byte[size]; input.readFully(data);
                int checksum = 0; for (byte value : data) checksum += value & 255;
                if (checksum != expectedChecksum) throw new IllegalStateException("ADB 校验失败");
                if (cmd == command("AUTH") || cmd == command("STLS")) return new Result(-1, "本机 ADB 当前需要认证，系统操作未执行");
                if (cmd == command("CNXN") && !opened) {
                    String shell = "shell:/system/xbin/su 0 /system/bin/sh -c " + quote(fixedCommand)
                            + "; rkh_status=$?; printf '\\nRKH_EXIT=%s\\n' \"$rkh_status\"";
                    send(socket, "OPEN", 1, 0, (shell + "\0").getBytes(StandardCharsets.UTF_8)); opened = true;
                } else if (cmd == command("WRTE") && opened && second == 1) {
                    if (output.size() + data.length > 65536) throw new IllegalStateException("系统报告过长");
                    output.write(data); send(socket, "OKAY", 1, first, new byte[0]);
                } else if (cmd == command("CLSE") && opened) {
                    send(socket, "CLSE", 1, first, new byte[0]);
                    String value = new String(output.toByteArray(), StandardCharsets.UTF_8).replace("\r", "");
                    int marker = value.lastIndexOf("RKH_EXIT=");
                    if (marker < 0) return new Result(-1, "未收到执行结果\n" + value);
                    int code = Integer.parseInt(value.substring(marker + 9).trim());
                    return new Result(code, value.substring(0, marker).trim());
                }
            }
            return new Result(-1, "ADB 操作未完成");
        } catch (Exception exception) { return new Result(-1, exception.getClass().getSimpleName() + ": " + exception.getMessage()); }
    }

    static Result status() {
        return run("echo \"系统操作 UID：$(id -u)\"; "
                + "echo \"Wi-Fi 诊断进程：$(getprop init.svc.vendor.cnss_diag)\"; "
                + "echo \"系统日志进程：$(getprop init.svc.logcatd)\"; "
                + "echo \"日志轮转份数：$(getprop logd.logpersistd.size)，每份约 2 MiB\"; "
                + "echo \"轻度待机：$(cmd deviceidle enabled light)（1 为启用）\"; "
                + "echo \"深度待机：$(cmd deviceidle enabled deep)（1 为启用）\"; "
                + "echo '系统背景色：'; cmd overlay lookup android android:color/background_material_dark; "
                + "echo '系统设置背景色：'; cmd overlay lookup android android:color/system_neutral1_900");
    }
}
