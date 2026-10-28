package top.hibermod.ctdweb.tools;

import java.util.Optional;

import top.hibermod.ctdweb.TaskBroker;

/**
 * E 阶段验收：异步任务代理。
 *
 * <p>验的是「不阻塞」这件事的**可观察后果**：
 * 没结果时立刻返回空，超时后结果被丢弃而不是无限等待。
 */
public final class TaskBrokerTest {

    private TaskBrokerTest() {}

    public static void main(String[] args) {
        int failures = 0;
        TaskBroker b = new TaskBroker();

        // 预热：首调用含类初始化，不能算进「常数时间」的测量里
        for (int i = 0; i < 100; i++) b.submit("warmup", "{}", 0, 1);
        b.reset();

        // ① 提交后立刻可取 id，且不阻塞
        long t0 = System.nanoTime();
        String id = b.submit("timetable", "{\"trains\":3}", 100, 20);
        long submitNanos = System.nanoTime() - t0;
        failures += check("提交是常数时间（无阻塞）", submitNanos < 5_000_000,
            submitNanos / 1000 + "us");
        failures += check("待处理计数为 1", b.pendingCount() == 1, "" + b.pendingCount());

        // ② 没有结果时 poll 立刻返回空
        t0 = System.nanoTime();
        Optional<TaskBroker.Result> none = b.poll(id);
        long pollNanos = System.nanoTime() - t0;
        failures += check("无结果时 poll 返回空且立即返回",
            none.isEmpty() && pollNanos < 5_000_000, pollNanos / 1000 + "us");

        // ③ 外部返回结果
        boolean accepted = b.complete(id, true, "{\"ok\":true}", 105);
        failures += check("结果被接受", accepted, "rejected");
        var result = b.poll(id);
        failures += check("能取到结果", result.isPresent() && result.get().ok(),
            String.valueOf(result));
        failures += check("结果只能取一次", b.poll(id).isEmpty(), "取到了第二次");

        // ④ 重复/未知 id 被丢弃
        String id2 = b.submit("plan", "{}", 110, 20);
        failures += check("未知 id 被拒绝", !b.complete("no-such-id", true, "", 111), "accepted");
        failures += check("重复提交结果被拒绝",
            b.complete(id2, true, "", 112) && !b.complete(id2, true, "", 113), "accepted twice");

        // ⑤ 超时
        String id3 = b.submit("timetable", "{}", 200, 10);
        failures += check("未到期不清理", b.expire(205) == 0, "expired early");
        failures += check("到期后被清理", b.expire(215) == 1, "not expired");
        failures += check("超时任务的结果被拒绝", !b.complete(id3, true, "", 216), "accepted");
        failures += check("待处理已清空", b.pendingCount() == 0, "" + b.pendingCount());

        // ⑥ 统计
        var s = b.stats();
        failures += check("统计记录了提交/完成/过期/丢弃",
            s.submitted() == 3 && s.completed() == 2 && s.expired() == 1 && s.dropped() == 3,
            s.toString());

        System.out.println();
        if (failures > 0) {
            System.out.println("异步任务验收失败: " + failures + " 项");
            System.exit(1);
        }
        System.out.println("异步任务验收通过：提交/取回/超时/去重都不阻塞 tick");
    }

    private static int check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + name + (ok ? "" : "  <- " + detail));
        return ok ? 0 : 1;
    }
}
