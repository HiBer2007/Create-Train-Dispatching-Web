package top.hibermod.ctdweb;

import java.util.UUID;

import top.hibermod.ctdapi.Command;
import top.hibermod.ctdapi.operation.ControlChannel;

/**
 * 网页/外部系统下发的控制指令往哪去。
 *
 * <h2>为什么做成接口</h2>
 * 生产环境里它写 {@link ControlChannel}（宿主每 tick 取走）；
 * 离线测试里要能直接断言「收到的是哪条指令」，而不是去翻世界状态。
 * 两者只差一个落点，解析逻辑没必要复制两遍。
 *
 * <h2>优先级</h2>
 * 网页控制台属于 {@link ControlChannel.Source#EXTERNAL}：低于玩家命令，
 * 高于自动化规则。操作员在游戏里扣住的车，不该被浏览器一帧就放行。
 */
public interface ControlSink {

    /** 扣车：授权 0。返回是否接受。 */
    boolean hold(UUID trainId, String note);

    /** 放行：给授权距离与限速上限。 */
    boolean proceed(UUID trainId, double authority, double speedCap, String note);

    /** 全部丢弃（无接收方时的默认实现）。 */
    ControlSink NONE = new ControlSink() {
        @Override public boolean hold(UUID trainId, String note) { return false; }
        @Override public boolean proceed(UUID trainId, double a, double s, String note) { return false; }
    };

    /** 写进宿主的控制通道。{@code now} 每次发送时取值（游戏刻）。 */
    static ControlSink controlChannel(java.util.function.LongSupplier now) {
        return new ControlSink() {
            @Override
            public boolean hold(UUID trainId, String note) {
                if (trainId == null) return false;
                ControlChannel.hold(ControlChannel.Source.EXTERNAL, now.getAsLong(),
                    new Command.TrainCommand(trainId, Command.Direction.KEEP, 0.0, 0.0, true, null),
                    note);
                return true;
            }

            @Override
            public boolean proceed(UUID trainId, double authority, double speedCap, String note) {
                if (trainId == null) return false;
                ControlChannel.hold(ControlChannel.Source.EXTERNAL, now.getAsLong(),
                    new Command.TrainCommand(trainId, Command.Direction.KEEP, authority, speedCap, false, null),
                    note);
                return true;
            }
        };
    }
}
