package top.hibermod.ctdweb;

/**
 * 断线策略：外部程序失联时怎么办。
 *
 * <h2>为什么这是一个必须显式设计的问题</h2>
 * 网页调度台可能只是关了个 Tab。如果「没收到指令」被当成「保持现状」，
 * 列车就会按最后一次授权继续跑——而那条授权可能是「放行到站外」。
 *
 * <p>三种策略，默认第二种：
 *
 * <table border="1">
 *   <tr><th>策略</th><th>行为</th><th>适用</th></tr>
 *   <tr><td>{@link #HOLD}</td><td>全部扣车</td><td>货运、无人线路；宁可停</td></tr>
 *   <tr><td>{@link #NATIVE}</td><td>不干预，退回 Create 原生</td><td>默认；最不意外</td></tr>
 *   <tr><td>{@link #GRACEFUL}</td><td>先按最后指令跑 N 刻，然后退回原生</td><td>高速干线；避免急停</td></tr>
 * </table>
 *
 * <p><b>不选 {@code HOLD} 作为默认</b>：一条正常运行的线网因为浏览器崩了就全线停车，
 * 这个后果比「暂时没人调度」严重得多。安全不等于一律停车。
 */
public final class RemotePolicy {

    public enum Mode {
        /** 断线即全部扣车。 */
        HOLD,
        /** 断线即不干预。 */
        NATIVE,
        /** 断线后再等 {@code graceTicks} 刻，然后不干预。 */
        GRACEFUL
    }

    private Mode mode = Mode.NATIVE;
    private int graceTicks = 100;          // 5 秒
    private long disconnectedAt = -1;

    public Mode mode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.NATIVE : mode;
    }

    public int graceTicks() {
        return graceTicks;
    }

    public void setGraceTicks(int ticks) {
        this.graceTicks = Math.max(0, ticks);
    }

    /** 客户端连上/断开的时刻。{@code connected=false} 时开始计时。 */
    public void connectionChanged(boolean connected, long now) {
        disconnectedAt = connected ? -1 : now;
    }

    /** 当前是否处于「断线」状态。 */
    public boolean disconnected() {
        return disconnectedAt >= 0;
    }

    /**
     * 是否应当全部扣车。
     *
     * @param now 当前游戏刻
     */
    public boolean shouldHold(long now) {
        if (!disconnected()) return false;
        return switch (mode) {
            case HOLD -> true;
            case NATIVE -> false;
            case GRACEFUL -> false;        // 宽限期内保持最后指令，由调用方决定
        };
    }

    /**
     * 是否应当退回原生（不干预）。
     *
     * <p>{@code GRACEFUL} 在宽限期过后返回 true；其余两条立即生效。
     */
    public boolean shouldYieldToNative(long now) {
        if (!disconnected()) return false;
        return switch (mode) {
            case HOLD -> false;            // 由 shouldHold 接管
            case NATIVE -> true;
            case GRACEFUL -> now - disconnectedAt >= graceTicks;
        };
    }

    /** 宽限期内剩余的刻数，供 HUD/日志显示。 */
    public int remainingGrace(long now) {
        if (!disconnected() || mode != Mode.GRACEFUL) return 0;
        return (int) Math.max(0, graceTicks - (now - disconnectedAt));
    }

    public String describe(long now) {
        if (!disconnected()) return "connected";
        return mode + " (grace left " + remainingGrace(now) + ")";
    }
}
