package top.hibermod.ctdweb;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 极简 WebSocket 服务端：把调度帧推给外部程序，并收回执。
 *
 * <h2>为什么不用 Netty</h2>
 * NeoForge 的开发编译路径里没有 {@code netty-codec-http}，为了一个桥接去拉一条
 * 版本容易漂移的依赖不划算。WebSocket 的服务端子集非常小：
 * 一次握手（SHA-1 + base64）加两种帧格式，自己写反而**没有依赖也没有版本风险**。
 *
 * <h2>线程模型</h2>
 * <ul>
 *   <li>游戏线程调用 {@link #publish} —— 只遍历连接写字节，**不等对端**；</li>
 *   <li>每个客户端一个读线程，收到的回执写进 {@link #latestResponse}。</li>
 * </ul>
 * 客户端卡住只会让回执变旧，不会让服务端掉刻。
 *
 * <h2>只保留最新回执</h2>
 * 队列会积压过期决策。调度有时效，旧授权被当成新授权执行会开出危险的距离。
 */
public final class WebBridge {

    private static final Logger LOG = LoggerFactory.getLogger("ctdweb");

    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    public static final String PATH = "/ctd";

    private final int port;
    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private final AtomicReference<String> latestResponse = new AtomicReference<>();
    private final AtomicInteger received = new AtomicInteger();

    private volatile ServerSocket server;
    private volatile Thread acceptThread;
    private volatile boolean running;

    public WebBridge(int port) {
        this.port = port;
    }

    public int port() {
        return port;
    }

    public int clients() {
        return clients.size();
    }

    public String latestResponse() {
        return latestResponse.get();
    }

    public int responsesReceived() {
        return received.get();
    }

    // ── 生命周期 ──────────────────────────────────────────────────────────

    public synchronized void start() {
        if (running) return;
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(port));
        } catch (IOException e) {
            LOG.error("ctdweb: cannot bind port {}: {}", port, e.getMessage());
            return;
        }
        running = true;
        acceptThread = new Thread(this::acceptLoop, "ctdweb-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        LOG.info("ctdweb: listening on ws://0.0.0.0:{}{}", port, PATH);
    }

    public synchronized void shutdown() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
            // 关服时的异常没有处理价值
        }
        for (Client c : clients) c.close();
        clients.clear();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = server.accept();
                socket.setTcpNoDelay(true);
                Client client = new Client(socket);
                if (client.handshake()) {
                    clients.add(client);
                    Thread t = new Thread(client::readLoop, "ctdweb-client");
                    t.setDaemon(true);
                    t.start();
                    LOG.info("ctdweb: client connected ({} total)", clients.size());
                } else {
                    client.close();
                }
            } catch (IOException e) {
                if (running) LOG.warn("ctdweb: accept failed: {}", e.getMessage());
                return;
            }
        }
    }

    // ── 推送 ──────────────────────────────────────────────────────────────

    /** 游戏线程调用。只写字节，不等对端。 */
    public void publish(String json) {
        if (json == null || clients.isEmpty()) return;
        byte[] text = json.getBytes(StandardCharsets.UTF_8);
        for (Client c : clients) {
            c.sendText(text);
        }
    }

    /** 给测试用：直接喂一条回执。 */
    void acceptResponse(String json) {
        latestResponse.set(json);
        received.incrementAndGet();
    }

    // ── 单个客户端 ────────────────────────────────────────────────────────

    private final class Client {

        private final Socket socket;
        private InputStream in;
        private OutputStream out;

        Client(Socket socket) {
            this.socket = socket;
        }

        boolean handshake() {
            try {
                in = socket.getInputStream();
                out = socket.getOutputStream();

                String request = readHttpHead();
                if (request == null) return false;

                String key = null;
                for (String line : request.split("\r\n")) {
                    int colon = line.indexOf(':');
                    if (colon > 0 && line.substring(0, colon).trim()
                            .equalsIgnoreCase("Sec-WebSocket-Key")) {
                        key = line.substring(colon + 1).trim();
                    }
                }
                if (key == null) {
                    out.write(("HTTP/1.1 400 Bad Request\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    return false;
                }

                String accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1")
                        .digest((key + WS_GUID).getBytes(StandardCharsets.US_ASCII)));

                String response = "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
                out.write(response.getBytes(StandardCharsets.US_ASCII));
                out.flush();
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        private String readHttpHead() throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int matched = 0;
            while (matched < 4) {
                int b = in.read();
                if (b < 0) return null;
                buffer.write(b);
                matched = switch (matched) {
                    case 0 -> b == '\r' ? 1 : 0;
                    case 1 -> b == '\n' ? 2 : 0;
                    case 2 -> b == '\r' ? 3 : 0;
                    case 3 -> b == '\n' ? 4 : 0;
                    default -> matched;
                };
                if (buffer.size() > 16 * 1024) return null;
            }
            return buffer.toString(StandardCharsets.US_ASCII);
        }

        void readLoop() {
            try {
                while (!socket.isClosed()) {
                    Frame f = readFrame();
                    if (f == null) break;
                    switch (f.opcode()) {
                        case 0x1 -> acceptResponse(f.text());
                        case 0x8 -> { close(); return; }        // close
                        case 0x9 -> writeFrame(0xA, f.payload()); // ping → pong
                        default -> { }
                    }
                }
            } catch (IOException ignored) {
                // 客户端断开
            } finally {
                clients.remove(this);
                close();
                LOG.info("ctdweb: client disconnected ({} left)", clients.size());
            }
        }

        private Frame readFrame() throws IOException {
            int b0 = in.read();
            int b1 = in.read();
            if (b0 < 0 || b1 < 0) return null;

            int opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;

            if (len == 126) {
                len = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | (in.read() & 0xFF);
            }
            if (len > 8L * 1024 * 1024) return null;         // 防滥用

            byte[] mask = new byte[4];
            if (masked) readFully(mask);

            byte[] payload = new byte[(int) len];
            readFully(payload);
            if (masked) {
                for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
            }
            return new Frame(opcode, payload);
        }

        private void readFully(byte[] target) throws IOException {
            int read = 0;
            while (read < target.length) {
                int n = in.read(target, read, target.length - read);
                if (n < 0) throw new IOException("eof");
                read += n;
            }
        }

        synchronized void sendText(byte[] text) {
            try {
                writeFrame(0x1, text);
            } catch (IOException e) {
                close();
            }
        }

        private synchronized void writeFrame(int opcode, byte[] payload) throws IOException {
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            head.write(0x80 | opcode);
            if (payload.length < 126) {
                head.write(payload.length);
            } else if (payload.length <= 0xFFFF) {
                head.write(126);
                head.write((payload.length >> 8) & 0xFF);
                head.write(payload.length & 0xFF);
            } else {
                head.write(127);
                for (int i = 7; i >= 0; i--) head.write((int) (((long) payload.length >> (8 * i)) & 0xFF));
            }
            out.write(head.toByteArray());
            out.write(payload);
            out.flush();
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
    }

    private record Frame(int opcode, byte[] payload) {
        String text() {
            return new String(payload, StandardCharsets.UTF_8);
        }
    }
}
