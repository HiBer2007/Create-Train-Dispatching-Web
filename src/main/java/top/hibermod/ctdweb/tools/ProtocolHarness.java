package top.hibermod.ctdweb.tools;

import top.hibermod.ctdweb.WebBridge;

/**
 * 协议联调台：起一个桥，周期性推快照与任务，等外部客户端回应。
 *
 * <p>存在的意义：**用一个非 Java 的客户端（{@code tools/example_client.py}）
 * 跑一遍**。通过 Java 自己测自己的协议没有说服力——真正要证明的是
 * 「浏览器/Python 能不能连上并理解这些字节」。
 */
public final class ProtocolHarness {

    private ProtocolHarness() {}

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8766;
        int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 15;

        WebBridge bridge = new WebBridge(port);
        bridge.start();

        String snapshot = "{\"time\":%d,\"trains\":[{\"id\":\"11111111-1111-1111-1111-111111111111\","
            + "\"name\":\"CRH2A-2013\",\"speed\":0.31,\"target\":0.4,\"accel\":0.0075,"
            + "\"maxSpeed\":1.4,\"length\":42.0,\"authority\":320.0,\"routeRemaining\":240.0,"
            + "\"manual\":false,\"derailed\":false,\"leadingEdge\":\"\",\"leadingDistance\":18.5}],"
            + "\"signals\":[],\"switches\":[]}";

        long end = System.currentTimeMillis() + seconds * 1000L;
        int ticks = 0;
        int tasksSent = 0;
        String lastTask = null;
        long lastTaskAt = 0;

        System.out.println("[harness] listening on ws://127.0.0.1:" + port + "/ctd");

        while (System.currentTimeMillis() < end) {
            ticks++;
            if (bridge.clients() > 0) {
                bridge.publish(String.format(snapshot, ticks));
            }

            if (ticks % 20 == 0) {
                lastTask = bridge.broker().submit("timetable", "{\"trains\":3}", ticks, 200);
                tasksSent++;
                bridge.publishTask("{\"task\":{\"id\":\"" + lastTask
                    + "\",\"kind\":\"timetable\",\"payload\":\"{}\"}}");
                lastTaskAt = ticks;
            }

            // 取回结果
            if (lastTask != null && ticks > lastTaskAt) {
                var r = bridge.broker().poll(lastTask);
                if (r.isPresent()) {
                    System.out.println("[harness] task " + lastTask + " answered: "
                        + r.get().payload());
                    lastTask = null;
                }
            }

            bridge.broker().expire(ticks);

            if (ticks % 10 == 0) {
                System.out.println("[harness] ticks=" + ticks + " clients=" + bridge.clients()
                    + " tasks=" + tasksSent + " stats=" + bridge.broker().stats());
            }

            Thread.sleep(100);
        }

        System.out.println("[harness] done. tasks=" + tasksSent
            + " responses=" + bridge.responsesReceived()
            + " stats=" + bridge.broker().stats());
        bridge.shutdown();
        System.exit(0);
    }
}
