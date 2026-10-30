package top.hibermod.ctdweb.tools;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import top.hibermod.ctdweb.ControlSink;
import top.hibermod.ctdweb.WebBridge;

/**
 * D5 验收：**外部程序下发的控制指令能进到游戏侧**。
 *
 * <h2>这条验收补的是什么</h2>
 * 早先的桥只能「往外推快照、往里收回执」，回执只是数据——没有任何东西因此改变。
 * 也就是说浏览器能看到车，但**动不了车**。这一条把它接上：
 *
 * <pre>
 *   WebSocket  {"control":{"train":..,"kind":"hold"}}
 *     → WebBridge.handleControl
 *     → ControlSink
 *     → ControlChannel（宿主每 tick 取走并施加）
 * </pre>
 *
 * <p>这里用假的 sink 断言「收到的是哪条指令」，不碰世界——协议层的问题在协议层验。
 */
public final class ControlTest {

    private ControlTest() {}

    /** 记录下来的指令。 */
    record Received(String kind, UUID trainId, double authority, double speedCap, String note) {}

    public static void main(String[] args) throws Exception {
        int failures = 0;

        List<Received> received = new ArrayList<>();

        ControlSink sink = new ControlSink() {
            @Override
            public boolean hold(UUID trainId, String note) {
                received.add(new Received("hold", trainId, 0, 0, note));
                return true;
            }

            @Override
            public boolean proceed(UUID trainId, double authority, double speedCap, String note) {
                received.add(new Received("proceed", trainId, authority, speedCap, note));
                return true;
            }
        };

        int port = freePort();
        WebBridge bridge = new WebBridge(port);
        bridge.setControlSink(sink);
        bridge.start();

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            String key = Base64.getEncoder().encodeToString("ctd-control".getBytes());
            out.write(("GET /ctd HTTP/1.1\r\n"
                + "Host: 127.0.0.1\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            readHead(in);

            for (int i = 0; i < 50 && bridge.clients() == 0; i++) Thread.sleep(20);
            failures += check("客户端已连接", bridge.clients() == 1, "clients=" + bridge.clients());

            UUID train = UUID.fromString("2b8f4c5e-0000-0000-0000-000000000001");

            // ① 扣车
            writeMaskedText(out, "{\"control\":{\"train\":\"" + train
                + "\",\"kind\":\"hold\",\"note\":\"web hold\"}}");
            for (int i = 0; i < 100 && received.isEmpty(); i++) Thread.sleep(20);

            failures += check("扣车指令到达落点", received.size() == 1, "got " + received.size());
            if (received.size() == 1) {
                Received r = received.get(0);
                failures += check("扣车参数正确", r.kind().equals("hold") && train.equals(r.trainId())
                    && "web hold".equals(r.note()), r.toString());
            }

            // ② 放行（带授权与限速）
            writeMaskedText(out, "{\"control\":{\"train\":\"" + train
                + "\",\"kind\":\"proceed\",\"authority\":320.0,\"speedCap\":0.4,"
                + "\"note\":\"web proceed\"}}");
            for (int i = 0; i < 100 && received.size() < 2; i++) Thread.sleep(20);

            failures += check("放行指令到达落点", received.size() == 2, "got " + received.size());
            if (received.size() == 2) {
                Received r = received.get(1);
                failures += check("放行参数解析正确",
                    r.kind().equals("proceed") && Math.abs(r.authority() - 320.0) < 1e-9
                        && Math.abs(r.speedCap() - 0.4) < 1e-9,
                    r.toString());
            }

            // ③ 坏 uuid 必须被拒绝，而不是抛异常穿透到读线程
            writeMaskedText(out, "{\"control\":{\"train\":\"not-a-uuid\",\"kind\":\"hold\"}}");
            for (int i = 0; i < 100 && bridge.controlsRejected() == 0; i++) Thread.sleep(20);

            failures += check("坏 uuid 被拒绝", bridge.controlsRejected() == 1,
                "rejected=" + bridge.controlsRejected());
            failures += check("拒绝不影响已接受的指令", received.size() == 2,
                "got " + received.size());
            failures += check("接受计数正确", bridge.controlsAccepted() == 2,
                "accepted=" + bridge.controlsAccepted());
        } finally {
            bridge.shutdown();
        }

        System.out.println();
        if (failures > 0) {
            System.out.println("控制通道验收失败: " + failures + " 项");
            System.exit(1);
        }
        System.out.println("控制通道验收通过：外部程序能真的扣车与放行");
    }

    // ── 最小 WebSocket 客户端 ─────────────────────────────────────────────

    private static int freePort() throws Exception {
        try (var s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static String readHead(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int state = 0;
        while (state < 4) {
            int b = in.read();
            if (b < 0) break;
            sb.append((char) b);
            if (b == '\r') state = state == 1 ? 2 : state;
            else if (b == '\n') state = (state == 2) ? 4 : (state == 0 ? 1 : 0);
            else state = 0;
        }
        return sb.toString();
    }

    /** 发一个带掩码的文本帧（客户端必须掩码）。 */
    private static void writeMaskedText(OutputStream out, String text) throws Exception {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream head = new java.io.ByteArrayOutputStream();
        head.write(0x81);                       // FIN + text

        if (payload.length < 126) {
            head.write(0x80 | payload.length);
        } else if (payload.length <= 0xFFFF) {
            head.write(0x80 | 126);
            head.write((payload.length >> 8) & 0xFF);
            head.write(payload.length & 0xFF);
        } else {
            head.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) head.write((int) (((long) payload.length >> (8 * i)) & 0xFF));
        }

        byte[] mask = { 0x11, 0x22, 0x33, 0x44 };
        head.write(mask);

        byte[] masked = new byte[payload.length];
        for (int i = 0; i < payload.length; i++) {
            masked[i] = (byte) (payload[i] ^ mask[i & 3]);
        }

        out.write(head.toByteArray());
        out.write(masked);
        out.flush();
    }

    private static int check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + name + (ok ? "" : "  <- " + detail));
        return ok ? 0 : 1;
    }
}
