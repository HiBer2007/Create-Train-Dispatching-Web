package top.hibermod.ctdweb;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.resources.ResourceLocation;

import top.hibermod.ctdapi.Command;
import top.hibermod.ctdapi.FrameContext;
import top.hibermod.ctdapi.Telemetry;
import top.hibermod.ctdapi.Topology;

/**
 * 帧编解码：{@link FrameContext} ⇄ JSON，JSON ⇄ 指令。
 *
 * <h2>为什么协议这么窄</h2>
 * 外部程序（网页调度台、Python 脚本、Excel 插件）需要的是**能决策的最小信息**：
 * 车在哪、跑多快、授权多少、前方什么灯、岔在哪。
 * 把整个 {@code Topology} 序列化过去会让每帧几 MB——比游戏本身还重。
 * 拓扑是低频变化的，应当单独拉一次并缓存，而不是每帧推。
 *
 * <h2>为什么不用 Java 序列化</h2>
 * 跨语言。对方可能是浏览器里的 JS。
 *
 * <h2>数值单位</h2>
 * 速度一律 **格/tick**（与 Create 一致），不换算成 km/h。
 * 换算属于展示层，放进协议会让「谁负责单位」变成一个长期的模糊点。
 */
public final class FrameCodec {

    private FrameCodec() {}

    // ── 快照（服务端 → 客户端）────────────────────────────────────────────

    public record Snapshot(long time, List<TrainView> trains, List<SignalView> signals,
                           List<SwitchView> switches) {}

    public record TrainView(String id, String name, double speed, double target,
                            double accel, double maxSpeed, double length,
                            double authority, double routeRemaining,
                            boolean manual, boolean derailed,
                            String leadingEdge, double leadingDistance) {}

    public record SignalView(String id, String side, String aspect) {}

    public record SwitchView(String node, String branch, long holder, boolean contested) {}

    /** 从一帧上下文构造快照。**只读，不产生副作用。** */
    public static Snapshot snapshot(FrameContext ctx) {
        List<TrainView> trains = new ArrayList<>();
        if (ctx.telemetry() != null) {
            for (Telemetry.TrainState s : ctx.telemetry().trains()) {
                trains.add(new TrainView(
                    s.id().toString(),
                    s.name() == null ? "" : s.name().getString(),
                    s.speed(), s.targetSpeed(), s.acceleration(), s.maxSpeed(),
                    s.length(), s.authority(),
                    s.route() == null ? 0 : s.route().remaining(),
                    s.manuallyDriven(), s.derailed(),
                    s.leadingEdge() == null ? "" : s.leadingEdge().encode(),
                    s.leadingDistance()));
            }
        }

        List<SignalView> signals = new ArrayList<>();
        for (FrameContext.SignalState s : ctx.signalStates()) {
            signals.add(new SignalView(
                s.signalId().toString(), s.side().name(),
                s.aspectId() == null ? "" : s.aspectId().toString()));
        }

        List<SwitchView> switches = new ArrayList<>();
        for (FrameContext.SwitchState s : ctx.switchStates()) {
            switches.add(new SwitchView(
                s.node().encode(),
                s.lockedBranch() == null ? "" : s.lockedBranch().encode(),
                s.holder(), s.contested()));
        }

        return new Snapshot(ctx.gameTime(), trains, signals, switches);
    }

    // ── 指令（客户端 → 服务端）────────────────────────────────────────────

    public record Response(List<TrainCmd> trains, List<AspectCmd> aspects) {
        public static Response empty() {
            return new Response(List.of(), List.of());
        }
    }

    public record TrainCmd(String id, double authority, double cap, boolean hold) {}

    public record AspectCmd(String signal, String side, String aspect) {}

    /**
     * 把外部指令转成 API 指令。
     *
     * <p>**任何非法条目单独丢弃**，不连累整批：外部程序传了半条就丢半条，
     * 不能因为一个坏 uuid 让整帧的调度消失。
     */
    public static List<Command.TrainCommand> toTrainCommands(Response response) {
        List<Command.TrainCommand> out = new ArrayList<>();
        if (response == null || response.trains() == null) return out;
        for (TrainCmd c : response.trains()) {
            UUID id = parseUuid(c.id());
            if (id == null) continue;
            if (c.hold() || c.authority() <= 0) {
                out.add(Command.TrainCommand.hold(id));
            } else {
                out.add(Command.TrainCommand.proceed(id,
                    Math.max(0, c.authority()), Math.max(0, c.cap())));
            }
        }
        return out;
    }

    public static List<Command.AspectCommand> toAspectCommands(Response response) {
        List<Command.AspectCommand> out = new ArrayList<>();
        if (response == null || response.aspects() == null) return out;
        for (AspectCmd c : response.aspects()) {
            UUID id = parseUuid(c.signal());
            if (id == null) continue;
            ResourceLocation aspect = ResourceLocation.tryParse(c.aspect());
            if (aspect == null) continue;
            Topology.SignalSide side;
            try {
                side = Topology.SignalSide.valueOf(c.side().toUpperCase(java.util.Locale.ROOT));
            } catch (RuntimeException e) {
                continue;
            }
            out.add(new Command.AspectCommand(id, side, aspect));
        }
        return out;
    }

    private static UUID parseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ── 拓扑（低频，单独拉）───────────────────────────────────────────────

    /** 拓扑摘要：只给数量与版本，完整拓扑按需再拉。 */
    public record TopologySummary(long version, int nodes, int edges, int signals,
                                  int switches, int stations, double maxTrainLength) {}

    public static TopologySummary summarize(Topology t) {
        if (t == null) return new TopologySummary(0, 0, 0, 0, 0, 0, 0);
        return new TopologySummary(t.version(), t.nodes().size(), t.edges().size(),
            t.signals().size(), t.switches().size(), t.stations().size(), t.maxTrainLength());
    }
}
