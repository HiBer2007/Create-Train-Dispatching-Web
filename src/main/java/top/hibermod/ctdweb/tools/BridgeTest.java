package top.hibermod.ctdweb.tools;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;

import top.hibermod.ctdapi.Command;
import top.hibermod.ctdweb.FrameCodec;
import top.hibermod.ctdweb.RemotePolicy;
import top.hibermod.ctdweb.WebBridge;

/**
 * D 阶段验收：WebSocket 握手、帧推送、回执解析、断线策略。
 *
 * <p>全部用裸 socket，不需要游戏也不需要浏览器——协议层的问题在协议层验完，
 * 这样进游戏时只需要关心「行为对不对」，不用怀疑「连没连上」。
 */
public final class BridgeTest {

    private BridgeTest() {}

    public static void main(String[] args) throws Exception {
        int failures = 0;

        // ① 编解码（纯逻辑）
        failures += testCodec();

        // ② 握手 + 推 + 收
        failures += testBridge();

        // ③ 断线策略
        failures += testPolicy();

        System.out.println();
        if (failures > 0) {
            System.out.println("Web 桥验收失败: " + failures + " 项");
            System.exit(1);
        }
        System.out.println("Web 桥验收通过：握手、推送、回执、断线策略全部符合预期");
    }

    // ── 编解码 ────────────────────────────────────────────────────────────

    private static int testCodec() {
        int f = 0;

        var response = new FrameCodec.Response(
            List.of(
                new FrameCodec.TrainCmd("2b8f4c5e-0000-0000-0000-000000000001", 120, 0.5, false),
                new FrameCodec.TrainCmd("not-a-uuid", 10, 0.2, false),
                new FrameCodec.TrainCmd("2b8f4c5e-0000-0000-0000-000000000002", 0, 0, true)),
            List.of(
                new FrameCodec.AspectCmd("2b8f4c5e-0000-0000-0000-000000000009", "FIRST", "ctdapi:red"),
                new FrameCodec.AspectCmd("bad", "FIRST", "ctdapi:red"),
                new FrameCodec.AspectCmd("2b8f4c5e-0000-0000-0000-000000000009", "SIDEWAYS", "ctdapi:red")));

        List<Command.TrainCommand> trains = FrameCodec.toTrainCommands(response);
        List<Command.AspectCommand> aspects = FrameCodec.toAspectCommands(response);

        f += check("坏 uuid 的列车指令被丢弃", trains.size() == 2, "got " + trains.size());
        f += check("hold 指令被翻译成停车",
            trains.stream().filter(Command.TrainCommand::hold).count() == 1,
            "holds=" + trains.stream().filter(Command.TrainCommand::hold).count());
        f += check("坏信号/坏面被丢弃", aspects.size() == 1, "got " + aspects.size());

        return f;
    }

    // ── 桥 ────────────────────────────────────────────────────────────────

    private static int testBridge() throws Exception {
        int f = 0;
        int port = freePort();
        WebBridge bridge = new WebBridge(port);
        bridge.start();

        try {
            Socket socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            String key = Base64.getEncoder().encodeToString("ctd-test-key".getBytes());
            out.write(("GET /ctd HTTP/1.1\r\n"
                + "Host: 127.0.0.1\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();

            String head = readHead(in);
            f += check("握手返回 101", head.startsWith("HTTP/1.1 101"), head.split("\r\n")[0]);

            String expected = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                        .getBytes(StandardCharsets.US_ASCII)));
            f += check("Sec-WebSocket-Accept 正确", head.contains(expected),
                "expected " + expected);

            // 等桥把连接登记进集合
            for (int i = 0; i < 50 && bridge.clients() == 0; i++) Thread.sleep(20);
            f += check("客户端已登记", bridge.clients() == 1, "clients=" + bridge.clients());

            // 推送
            bridge.publish("{\"t\":7,\"trains\":[]}");
            String frame = readTextFrame(in);
            f += check("服务端推送到达", "{\"t\":7,\"trains\":[]}".equals(frame), frame);

            // 回执
            writeMaskedText(out, "{\"trains\":[{\"id\":\"2b8f4c5e-0000-0000-0000-000000000001\","
                + "\"authority\":50,\"cap\":0.4,\"hold\":false}]}");
            for (int i = 0; i < 100 && bridge.latestResponse() == null; i++) Thread.sleep(20);
            f += check("回执已收到", bridge.latestResponse() != null,
                String.valueOf(bridge.latestResponse()));

            if (bridge.latestResponse() != null) {
                var parsed = FrameCodec.toTrainCommands(parse(bridge.latestResponse()));
                f += check("回执能翻译成指令", parsed.size() == 1, "got " + parsed.size());
            }

            socket.close();
            for (int i = 0; i < 100 && bridge.clients() > 0; i++) Thread.sleep(20);
            f += check("断线后连接被清理", bridge.clients() == 0, "clients=" + bridge.clients());
        } finally {
            bridge.shutdown();
        }
        return f;
    }

    private static FrameCodec.Response parse(String json) throws Exception {
        // 复用生产解析器，避免测试自己造一套「看起来对」的解析
        var m = Class.forName("top.hibermod.ctdweb.RemoteKernel")
            .getDeclaredMethod("parse", String.class);
        m.setAccessible(true);
        return (FrameCodec.Response) m.invoke(null, json);
    }

    // ── 策略 ──────────────────────────────────────────────────────────────

    private static int testPolicy() {
        int f = 0;

        RemotePolicy p = new RemotePolicy();
        p.connectionChanged(true, 0);
        f += check("连接中不扣车", !p.shouldHold(1000), "hold");
        f += check("连接中不退回原生", !p.shouldYieldToNative(1000), "yield");

        p.setMode(RemotePolicy.Mode.NATIVE);
        p.connectionChanged(false, 1000);
        f += check("NATIVE：断线不扣车", !p.shouldHold(1001), "hold");
        f += check("NATIVE：断线立即退回原生", p.shouldYieldToNative(1001), "no yield");

        p.setMode(RemotePolicy.Mode.HOLD);
        p.connectionChanged(false, 2000);
        f += check("HOLD：断线即扣车", p.shouldHold(2001), "no hold");

        p.setMode(RemotePolicy.Mode.GRACEFUL);
        p.setGraceTicks(100);
        p.connectionChanged(false, 3000);
        f += check("GRACEFUL：宽限期内不退回原生", !p.shouldYieldToNative(3080), "yielded early");
        f += check("GRACEFUL：宽限期剩余可读", p.remainingGrace(3080) == 20,
            String.valueOf(p.remainingGrace(3080)));
        f += check("GRACEFUL：过期后退回原生", p.shouldYieldToNative(3101), "no yield after grace");

        return f;
    }

    // ── 工具 ──────────────────────────────────────────────────────────────

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static String readHead(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int b = in.read();
            if (b < 0) break;
            buf.write(b);
            matched = switch (matched) {
                case 0 -> b == '\r' ? 1 : 0;
                case 1 -> b == '\n' ? 2 : 0;
                case 2 -> b == '\r' ? 3 : 0;
                case 3 -> b == '\n' ? 4 : 0;
                default -> matched;
            };
        }
        return buf.toString(StandardCharsets.US_ASCII);
    }

    private static String readTextFrame(InputStream in) throws Exception {
        int b0 = in.read();
        int b1 = in.read();
        long len = b1 & 0x7F;
        if (len == 126) {
            len = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) len = (len << 8) | (in.read() & 0xFF);
        }
        byte[] payload = new byte[(int) len];
        int read = 0;
        while (read < payload.length) {
            int n = in.read(payload, read, payload.length - read);
            if (n < 0) break;
            read += n;
        }
        return new String(payload, StandardCharsets.UTF_8);
    }

    private static void writeMaskedText(OutputStream out, String text) throws Exception {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        byte[] mask = { 1, 2, 3, 4 };
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.write(0x81);
        if (payload.length < 126) {
            buf.write(0x80 | payload.length);
        } else {
            buf.write(0x80 | 126);
            buf.write((payload.length >> 8) & 0xFF);
            buf.write(payload.length & 0xFF);
        }
        buf.write(mask);
        for (int i = 0; i < payload.length; i++) {
            buf.write(payload[i] ^ mask[i & 3]);
        }
        out.write(buf.toByteArray());
        out.flush();
    }

    private static int check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + name + (ok ? "" : "  <- " + detail));
        return ok ? 0 : 1;
    }
}
