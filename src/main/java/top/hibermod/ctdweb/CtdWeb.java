package top.hibermod.ctdweb;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import top.hibermod.ctdapi.DispatchKernels;

/**
 * Web 桥：把调度帧通过 WebSocket 暴露给外部程序。
 *
 * <p>它只做搬运，不做决策。真正的调度算法在浏览器、Python 或别的进程里。
 */
@Mod(CtdWeb.MOD_ID)
public final class CtdWeb {

    public static final String MOD_ID = "ctdweb";

    /** 默认端口；可用 {@code -Dctdweb.port=...} 覆盖。 */
    public static final int PORT = Integer.getInteger("ctdweb.port", 8765);

    private final WebBridge bridge = new WebBridge(PORT);
    private final RemotePolicy policy = new RemotePolicy();

    public CtdWeb(IEventBus modBus) {
        DispatchKernels.register(RemoteKernel.ID, () -> new RemoteKernel(bridge, policy));
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        // 网页/外部系统下发的人工指令落到宿主的控制通道（每 tick 被取走）。
        // 没有这一行，浏览器能看到车但动不了车。
        bridge.setControlSink(ControlSink.controlChannel(CtdWeb::currentGameTime));
        bridge.start();
    }

    private void onServerStopping(ServerStoppingEvent event) {
        bridge.shutdown();
    }

    public WebBridge bridge() {
        return bridge;
    }

    public RemotePolicy policy() {
        return policy;
    }
    /** 当前游戏刻；没有服务端时退化为 0（控制通道只把它当时间戳看）。 */
    private static long currentGameTime() {
        try {
            var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            return server == null ? 0L : server.overworld().getGameTime();
        } catch (Throwable t) {
            return 0L;
        }
    }
}
