package top.hibermod.ctdweb;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import top.hibermod.ctdapi.Command;
import top.hibermod.ctdapi.DispatchFrame;
import top.hibermod.ctdapi.DispatchKernel;
import top.hibermod.ctdapi.FrameContext;
import top.hibermod.ctdapi.KernelEnv;
import top.hibermod.ctdapi.operation.ControlChannel;

import net.minecraft.resources.ResourceLocation;

/**
 * 把调度帧转发给 WebSocket 客户端，并把它们的回执翻译成指令。
 *
 * <h2>为什么它「不能阻塞」</h2>
 * 这个内核跑在服务端 tick 线程上。等一个来回（≥10ms）会直接吃掉 tick 预算，
 * 所以：**发送是投递到网络线程的，读取是最新的那一份回执**。
 * 客户端慢一帧，世界只晚一帧——而不是卡住。
 *
 * <h2>为什么回执可能过期</h2>
 * 客户端基于第 N 帧做决策，指令在第 N+k 帧被应用。k 越大越危险，
 * 所以 {@link RemotePolicy} 管着「多久没新鲜回执就退回原生」。
 */
public final class RemoteKernel implements DispatchKernel {

    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath("ctdweb", "remote");

    private static final Gson GSON = new Gson();

    private final WebBridge bridge;
    private final RemotePolicy policy;

    private KernelEnv env;
    private long frames;
    private long lastAppliedFrame = -1;

    public RemoteKernel(WebBridge bridge, RemotePolicy policy) {
        this.bridge = bridge;
        this.policy = policy;
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void onAttach(KernelEnv env) {
        this.env = env;
        env.log("info", "ctdweb: remote kernel attached (clients={})", bridge.clients());
    }

    @Override
    public void onFrame(FrameContext in, DispatchFrame out) {
        frames++;
        long now = in.gameTime();

        // ① 推快照（投递，不等）
        FrameCodec.Snapshot snap = FrameCodec.snapshot(in);
        bridge.publish(GSON.toJson(snap));

        // ② 断线策略
        policy.connectionChanged(bridge.clients() > 0, now);
        if (policy.shouldHold(now)) {
            for (var t : in.telemetry().trains()) {
                out.command(Command.TrainCommand.hold(t.id()));
            }
            return;
        }
        if (policy.shouldYieldToNative(now)) {
            return;                                  // 零指令 = 不干预
        }

        // ③ 应用最新回执
        String latest = bridge.latestResponse();
        if (latest == null) return;

        FrameCodec.Response response = parse(latest);

        // 走控制通道而不是本帧的 out：
        // 外部程序是「操作员」，它的指令必须压过正在跑的调度脚本。
        // 走 out 的话会被同帧其它内核按提交顺序覆盖，语义就乱了。
        for (Command.TrainCommand c : FrameCodec.toTrainCommands(response)) {
            ControlChannel.hold(ControlChannel.Source.EXTERNAL, now, c, "web");
        }
        for (Command.AspectCommand c : FrameCodec.toAspectCommands(response)) {
            ControlChannel.aspect(ControlChannel.Source.EXTERNAL, now, c, "web");
        }
        lastAppliedFrame = frames;
    }

    /** 手写解析：Gson 反序列化到 record 依赖构造器签名，跨语言时更该放宽。 */
    static FrameCodec.Response parse(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            List<FrameCodec.TrainCmd> trains = new ArrayList<>();
            JsonArray ta = root.has("trains") ? root.getAsJsonArray("trains") : new JsonArray();
            for (JsonElement e : ta) {
                JsonObject o = e.getAsJsonObject();
                trains.add(new FrameCodec.TrainCmd(
                    str(o, "id"), num(o, "authority", 0), num(o, "cap", 0), bool(o, "hold")));
            }

            List<FrameCodec.AspectCmd> aspects = new ArrayList<>();
            JsonArray aa = root.has("aspects") ? root.getAsJsonArray("aspects") : new JsonArray();
            for (JsonElement e : aa) {
                JsonObject o = e.getAsJsonObject();
                aspects.add(new FrameCodec.AspectCmd(
                    str(o, "signal"), str(o, "side"), str(o, "aspect")));
            }

            return new FrameCodec.Response(trains, aspects);
        } catch (RuntimeException ex) {
            return FrameCodec.Response.empty();       // 坏 JSON 当作「本帧不干预」
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static double num(JsonObject o, String key, double fallback) {
        try {
            return o.has(key) ? o.get(key).getAsDouble() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject o, String key) {
        try {
            return o.has(key) && o.get(key).getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    public long frames() {
        return frames;
    }

    public long lastAppliedFrame() {
        return lastAppliedFrame;
    }

    @SuppressWarnings("unused")
    private void touch(KernelEnv e) {
        this.env = e;
    }
}
