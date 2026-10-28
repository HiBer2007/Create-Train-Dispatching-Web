package top.hibermod.ctdweb;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 异步任务代理：把「几十毫秒级的重计算」挪出 tick 线程。
 *
 * <h2>为什么需要它</h2>
 * D 阶段的桥是**每帧往返**：发快照、收回执，延迟一个来回。那适合「扣车」「改灯」这种
 * 即时决策，但不适合排图、全网重新规划——那些要几百毫秒，压进 tick 就是卡服。
 *
 * <p>所以这里把事情反过来：**提交任务 → 立刻返回 → 过几帧再来取结果**。
 * 取不到就继续用旧结果，绝不等待。
 *
 * <h2>为什么没有线程池</h2>
 * 线程池会把「谁在什么时候执行」变成一个没人读得懂的问题，
 * 而且游戏线程与计算线程之间的状态共享最容易出错。
 * 这里所有方法都只在 tick 线程或网络线程的短临界区内跑，**没有任何阻塞等待**。
 *
 * <h2>超时是必须的</h2>
 * 外部进程可能崩了、可能卡住。任务必须有截止时间，过期就丢弃并计数——
 * 否则一个卡住的排图任务会让内核永远等一个不会来的结果。
 */
public final class TaskBroker {

    /** 一个待外部处理的任务。 */
    public record Task(String id, String kind, String payload, long issuedAt, long deadline) {
        public boolean expired(long now) {
            return now >= deadline;
        }
    }

    /** 外部返回的结果。 */
    public record Result(String id, boolean ok, String payload, long completedAt) {}

    /** 统计，用于 `/ctd stats` 或日志。 */
    public record Stats(long submitted, long completed, long expired, long dropped) {
        @Override
        public String toString() {
            return "submitted=" + submitted + " completed=" + completed
                + " expired=" + expired + " dropped=" + dropped;
        }
    }

    private final Map<String, Task> pending = new ConcurrentHashMap<>();
    private final Map<String, Result> done = new ConcurrentHashMap<>();

    /**
     * 任务 id 用自增计数器，不用 {@code UUID.randomUUID()}。
     *
     * <p>实测：首次生成 UUID 要 46 ms —— 它走 {@code SecureRandom}，可能阻塞在熵上。
     * 这个方法跑在 tick 线程里，任何一帧偶发的 46 ms 都是一次可见的卡顿。
     * 任务 id 只需要在**本次会话内**唯一，计数器完全够用。
     */
    private long seq;

    private long submitted;
    private long completed;
    private long expired;
    private long dropped;

    /**
     * 提交任务。
     *
     * @param timeoutTicks 多少刻之后过期（按游戏刻，不是墙钟）
     * @return 任务 id；调用方拿它之后 {@link #poll}
     */
    public String submit(String kind, String payload, long now, long timeoutTicks) {
        String id = "t" + (++seq);
        long deadline = now + Math.max(1, timeoutTicks);
        pending.put(id, new Task(id, kind, payload, now, deadline));
        submitted++;
        return id;
    }

    /**
     * 外部进程送回结果。
     *
     * <p>未知 id（已过期）或重复 id 一律丢弃并计数——静默接受会让
     * 「任务超时了但结果晚到」变成一次意料之外的状态跳变。
     */
    public boolean complete(String id, boolean ok, String payload, long now) {
        if (id == null) return false;
        Task task = pending.remove(id);
        if (task == null) {
            dropped++;
            return false;
        }
        done.put(id, new Result(id, ok, payload == null ? "" : payload, now));
        completed++;
        return true;
    }

    /**
     * 取结果。取走即删除——结果只该被消费一次。
     */
    public Optional<Result> poll(String id) {
        return Optional.ofNullable(done.remove(id));
    }

    /**
     * 清理过期任务。**每帧调一次**，否则 pending 会无限增长。
     *
     * @return 本帧过期掉的数量
     */
    public int expire(long now) {
        int n = 0;
        for (Task t : pending.values()) {
            if (t.expired(now) && pending.remove(t.id(), t)) {
                n++;
                expired++;
            }
        }
        return n;
    }

    public int pendingCount() {
        return pending.size();
    }

    public int doneCount() {
        return done.size();
    }

    public synchronized Stats stats() {
        return new Stats(submitted, completed, expired, dropped);
    }

    /** 供测试与统计使用。 */
    public Map<String, Task> pendingTasks() {
        return Map.copyOf(pending);
    }

    public synchronized void reset() {
        pending.clear();
        done.clear();
        submitted = 0;
        completed = 0;
        expired = 0;
        dropped = 0;
    }

    /** 便捷：按 kind 找未完成的任务（一般不需要）。 */
    public Map<String, Task> pendingOfKind(String kind) {
        Map<String, Task> out = new HashMap<>();
        for (Task t : pending.values()) {
            if (t.kind().equals(kind)) out.put(t.id(), t);
        }
        return out;
    }
}
