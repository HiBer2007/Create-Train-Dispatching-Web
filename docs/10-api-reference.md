# 10 — Create Train Dispatching API 参考手册

> 仓库：`HiBer2007/Create-Train-Dispatching-API` ｜ 包名 `top.hibermod.ctdapi` ｜ 版本 0.1.0
> 目标：Minecraft 1.21.1 / NeoForge 21.1.219+ ｜ 许可证 LGPL-3.0-or-later
> 依赖：**只依赖 Minecraft / NeoForge 的公开类**，不依赖 Create

---

## 0. 这份 API 是什么，不是什么

| ✅ 是 | ❌ 不是 |
|---|---|
| 调度**引擎**的接口契约 | 一个能跑的调度器 |
| **宿主模组**的接口契约 | 宿主模组本身（不做任何注入） |
| 信号制度的**开放注册表** | 任何信号机方块或渲染 |
| 设备 / HUD / PIS 的**数据契约** | 任何 HUD 绘制、翻牌屏渲染、纹理模型 |
| 进程外引擎的**接入层定义** | 任何网络协议实现 |

### 0.1 三条硬边界

**① 不是引擎。** 本仓库没有一行调度逻辑——没有寻路、没有进路、没有闭塞算法。
「车该怎么走」全部由实现 `DispatchKernel` 的模组决定。

**② 不是宿主。** 本仓库没有 mixin、没有 `@Mod` 主类、没有事件订阅、没有存档。
把 Create 的 `TrackNode` 映射成 `Rail.NodeId`、把车的位置算成占用区间、把指令写回
`train.speed`——全部是宿主模组的事。

**③ 不带任何资源。** 没有纹理、模型、语言文件、声音、数据包。
`src/main/resources` 里**只有** `META-INF/neoforge.mods.toml`。
也不引用任何客户端类：`net.minecraft.client.*`、`com.mojang.blaze3d.*`、`GuiGraphics`、
`PoseStack` 一个都没有。

### 0.2 边界由构建强制

这些不是口头约定，`checkApiBoundary` 任务会让违规直接构建失败：

```bash
./gradlew checkApiBoundary
```

| 检查项 | 违规后果 |
|---|---|
| `src/main/resources/assets` 存在 | 构建失败 |
| `src/main/resources/data` 存在 | 构建失败 |
| 源码引用 `net.minecraft.client.*` | 构建失败 |
| 源码引用 `com.mojang.blaze3d.*` | 构建失败 |

### 0.3 依赖面

全部外部 import 只有 6 个，且都只到 Minecraft 层：

```
net.minecraft.nbt.CompoundTag
net.minecraft.network.chat.Component
net.minecraft.resources.ResourceKey
net.minecraft.resources.ResourceLocation
net.minecraft.world.level.Level
org.jetbrains.annotations.Nullable
```

**刻意不依赖 Create**——否则 API 会把 Create 的版本约束传染给每一个引擎与宿主模组。

---

## 1. 三方职责

```
┌───────────────────────────────────────────────────────────────┐
│  ① 调度引擎（扩展方）                                          │
│     时刻表 · 实时调度 · 进路 · 闭塞 · 延误传播                  │
│     ★ 可以跑在游戏进程之外（见 §3.3）                          │
│     实现：DispatchKernel#onFrame                              │
└──────────────────────────┬────────────────────────────────────┘
                           │ 读 FrameContext / 写 DispatchFrame
┌──────────────────────────▼────────────────────────────────────┐
│  ② 宿主模组（本 API 的实现方）                                 │
│     拓扑镜像 · 遥测采集 · 指令执行 · 安全钳位 · 存档 · 渲染      │
└──────────────────────────┬────────────────────────────────────┘
                           │ Mixin / 注册表
┌──────────────────────────▼────────────────────────────────────┐
│  ③ Create                                                  │
│     轨道图 · 列车物理 · 信号机方块 · 车站                     │
└───────────────────────────────────────────────────────────────┘
```

**关键的边界约定**：
- 扩展方**永远看不到** `TrackNode` / `Train` / `SignalBoundary` 这些 Create 类型——只有值对象。
- 宿主**永远不决定**「车怎么走」——只负责把意图变成对 Create 的写入。

---

## 2. 包结构一览

```
top.hibermod.ctdapi
├── DispatchKernel          调度内核接口（唯一的决策入口）
├── DispatchKernels         内核注册表
├── KernelEnv               生命周期服务（持久化 / 日志 / 配置）
├── FrameContext            每帧输入
├── DispatchFrame           每帧输出
├── Topology                只读拓扑
├── Telemetry               列车实时状态
├── Command                 全部写回指令
├── RailwayEvent            事件
├── Rail                    稳定标识（NodeId / EdgeId / Span）
├── ApiCatalog              ★ 统一索引（有哪些、该用哪个）
├── signal/                 信号系统
├── ato/                    ATO 引擎（绑定信号制度）
├── traction/               牵引标签 / 速度等级
├── interop/                能源联动（电力、燃料由别的模组提供）
├── status/                 临时限制与应急
├── device/                 设备（地面 / 车载）
├── timetable/              交路（时刻表对外可见的那一层）
├── hud/                    驾驶台 / 调度台面板
├── pis/                    乘客信息
└── remote/                 进程外引擎接入
```

---

## 3. 执行模型

### 3.1 一帧的流程

```
服务端 tick
  ├─ GlobalRailwayManager.tick() 之前        ← 宿主采集遥测，构造 FrameContext
  ├─ kernel.onEvent(...)  × N                ← 先派发事件
  ├─ kernel.onFrame(in, out)                 ← ★ 唯一的决策入口
  ├─ 宿主应用 out 中的指令
  │    ├─ 速度/授权 → 写 train.speed
  │    ├─ 信号显示 → 写 SignalBoundary.cachedStates
  │    ├─ 进路     → 写 Navigation.currentPath
  │    └─ 设备     → 转交设备实现
  └─ GlobalRailwayManager.tick()            ← 物理开始
```

**零指令 = 不干预。** 内核一帧什么都不写，列车就回到 Create 原生行为。这是降级、灰度、排障的基础。

### 3.2 指令只能收紧

宿主应用速度时一律取 `min(原生意图, 内核指令)`。内核逻辑出错时最坏退化为原版——**永远不会比原版跑得更远**。

### 3.3 进程外引擎

时刻表与实时调度可能很重（全路网寻路、时刻优化、换乘接续），完全可以跑在游戏外：

```java
// 管道：HTTP / WebSocket / socket / stdio 都行
KernelTransport transport = new HttpTransport("http://dispatch.local/frame");

// 编解码：JSON / Protobuf / NBT 都行
FrameCodec codec = new JsonFrameCodec();

DispatchKernels.register(
    ResourceLocation.fromNamespaceAndPath("mymod", "remote"),
    () -> new Remote.RemoteKernel(id, transport, codec, Remote.RemotePolicy.SAFE));
```

`RemoteKernel` 替扩展方处理了三件必须处理的事：

| 问题 | 处理 |
|---|---|
| 网络往返跨帧 | 请求异步发出，响应**下一帧**应用，绝不阻塞主线程 |
| 旧响应失效 | 每 tick 按 `authorityDecay` 削减授权 |
| 断线 | 超过 `maxStaleTicks` 视为失效；`holdOnStale` 时强制全停车 |

> **为什么必须衰减**：如果远端说「可以走 500 格」然后断线，列车不该傻跑 500 格。
> 授权必须随时间自然收紧——这与真实 ETCS 的通信超时降级是同一思路。

`RemotePolicy.SAFE` = 250ms 超时 / 3 tick 失效 / 每 tick 削 8 格 / 失效即停。

---

## 4. 信号系统

### 4.1 三层结构

```
SignalSystem   制度    ——「这是哪一套规矩」（干线四显示 / 调车场 / 城际三显示）
  └ SignalType 机型    ——「这台机器怎么显示」（几盏灯、什么色、语义映射到哪个灯位）
      └ Aspect 语义    ——「这意味着什么」（停车 / 注意 / 通行 / 调车 / 引导）
          └ LampColor 灯色 ——「亮的是什么颜色」
```

**为什么要分开**：

- **语义跨机型共享**——「通行」在四显示机上亮第 4 号灯座、在三显示机上亮第 3 号，
  但内核说的是同一个 `ctdapi:proceed`。
- **灯位属于机型**——所以换一种机器不用改内核。
- **制度属于交路**——所以一条铁路能同时存在多套规矩。

### 4.2 多套信号系统并存

这不是可选项，而是必须支持的现实：

```
同一张轨道网
├── 干线区段   → ctdapi:mainline_4（四显示自动闭塞，红黄黄绿）
├── 站场       → ctdapi:yard      （调车，白蓝）
└── 支线       → yourmod:simple   （自家注册的简易闭塞）
```

每台信号机属于且只属于一个制度（`SignalType.systemId`）。内核按**交路**决定用哪套：

```java
Service svc = topology.service(currentService);
SignalSystem sys = topology.signalSystem(svc.signalSystemId());
```

### 4.3 由时刻表决定用哪套

`timetable/Service` 是「时刻表对外可见的那一层」：

```java
Service.register(Service.builder(rl("mymod", "g_line_1"))
    .code("G1")
    .line(Component.literal("京沪线"))
    .signalSystem(SignalRegistries.DEFAULT_SYSTEM_ID)
    .priority(10)
    .build());
```

- 时刻表**怎么存、怎么算**完全属于扩展方，本 API 不定义。
- 但「这条交路用哪套信号制度」必须对外可见——否则 PIS、HUD、调试都无法解释当前灯语。
- `ApiCatalog` 会把交路与它绑定的制度一起列出来（见 §11）。

### 4.4 灯与两种显示模型

**语义 → 灯位**（注册时机决定）：

```java
SignalType.builder(rl("mymod", "china_home_4"))
    .system(mySystem)
    .lamp(LampColor.RED).lamp(LampColor.YELLOW)
    .lamp(LampColor.YELLOW).lamp(LampColor.GREEN)
    .expresses(STOP.id(), 0)                        // 红灯
    .expresses(CAUTION.id(), 1)                      // 黄灯
    .expresses(ADVANCE_CAUTION.id(), 1, 2)           // 双黄
    .expresses(PROCEED.id(), 3)                      // 绿灯
    .expresses(CALL_ON.id(), LampDisplay.flashing(0))// 红闪 = 引导
    .semantics(SignalSemantics.HOME)
    .build();
```

内核只发语义，**从不发灯位**：

```java
out.aspect(site.id(), Topology.SignalSide.FIRST, SignalRegistries.Builtin.PROCEED.id());
```

### 4.5 联锁语义（真正决定行车的那部分）

```java
SignalSemantics { target, grantsAuthority, permissiveWhenClosed, stoppingPoint, defaultSpeedFraction }
```

| 字段 | 含义 | 搞错的后果 |
|---|---|---|
| `target` | 防护的是进路/区间/站台/车站/调车区 | 联锁无法区分进站与出站 |
| `grantsAuthority` | 开放时是否授予行车授权 | 把显示当装饰，车不走 |
| `permissiveWhenClosed` | 关闭时能否低速越过 | **调车作业被卡死** |
| `stoppingPoint` | 是否是停车点 | **预告机被当边界，列车提前停死** |
| `defaultSpeedFraction` | 开放时的默认限速 | 限速不对 |

内置：`HOME` / `STARTING` / `BLOCK` / `SHUNT` / `DISTANT` / `ROUTE_INDICATOR`。

---

## 5. 地面设备

**信号机不走这里**——它有独立模型。这里管其余一切：道岔转辙机、计轴、轨道电路、应答器、平交道、站台门、发车指示器、缓冲挡。

### 5.1 类型注册

```java
Devices.register(new Devices.DeviceType(
    rl("mymod", "axle_counter"),
    Component.literal("计轴器"),
    Devices.Kind.GROUND,
    Set.of(Devices.Capabilities.AXLE_COUNTER, Devices.Capabilities.TRAIN_DETECTOR),
    false));                                  // 只读，不接受命令
```

预置标签在 `Devices.Capabilities`：`SWITCH_MACHINE` / `AXLE_COUNTER` / `TRACK_CIRCUIT` / `BALISE` / `LEVEL_CROSSING` / `PLATFORM_DOOR` / `DEPARTURE_INDICATOR` / `TRAIN_DETECTOR` / `BUFFER_STOP`。也可以自定义。

### 5.2 实例状态

```java
record DeviceSite(
    UUID id, ResourceLocation typeId,
    @Nullable UUID trainId,          // 车载时为宿主列车
    @Nullable Rail.EdgeId edge,      // 地面时为所在边
    double position,                 // 沿边位置
    boolean operational,             // 断电/损坏 → false
    CompoundTag state                // 类型自定义状态
)
```

`state` 是**开放 schema**：计轴器放 `{count: 12}`，道岔转辙机放 `{position: "reverse_left", locked: true}`，随你定义。API 不为每种设备定接口，否则这个包会无限膨胀。

### 5.3 指令

```java
out.device(new Command.DeviceCommand(deviceId, payload, holder));
```

宿主只校验三件事：设备存在、`operational`、类型 `commandable`，然后原样转交。

### 5.4 道岔的特殊说明

Create 没有道岔实体。**宿主不应自己实现道岔**——若检测到真实的道岔实现（如 Steam 'n' Rails 的 `TrackSwitch`），应把 `Command.SwitchCommand` **翻译**成对它的调用：

```java
switchCommand → TrackSwitch#setSwitchState(NORMAL / REVERSE_LEFT / REVERSE_RIGHT)
```

并行维护两套岔位必然分叉，表现是「信号显示开通但道岔没扳」。

---

## 6. 车载设备

同一个 `Devices` 注册表，`Kind.ONBOARD`：

```java
Devices.register(new Devices.DeviceType(
    rl("mymod", "atp_2000"),
    Component.literal("ATP-2000"),
    Devices.Kind.ONBOARD,
    Set.of(Devices.Capabilities.ATP, Devices.Capabilities.ATO),
    true));
```

实例用 `trainId` 定位，`edge` 为 `null`：

```java
for (Devices.DeviceSite d : topology.devicesOfTrain(trainId)) {
    if (d.type() != null && d.type().has(Devices.Capabilities.ATP)) {
        out.device(new Command.DeviceCommand(d.id(), atpCommand, holder));
    }
}
```

典型用法：
- **ATP**：读 `TrainState.speed` / `movementAuthority`，输出允许速度与制动指令；
- **ATO**：直接消费 `TrainCommand`；
- **车载显示**：见 §7；
- **电台**：把位置上报给外部引擎。

---

## 7. HUD

**引擎发布数据，宿主负责渲染。** 因为引擎可能在游戏外，HUD 契约只能是纯数据。

### 7.1 两段式

```java
// ① 声明（一次）
Hud.register(new Hud.HudPanelSpec(
    rl("mymod", "dispatch_status"),
    Component.literal("调度状态"),
    Hud.Target.DISPATCHER,
    0,      // 优先级
    8));    // 最多 8 行

// ② 填充（每帧；不发布 = 隐藏）
out.hud(new Hud.HudPanel(
    rl("mymod", "dispatch_status"),
    List.of(
        new Hud.HudRow(Component.literal("准点率"), Component.literal("97.3%"), Hud.Severity.NORMAL),
        new Hud.HudRow(Component.literal("晚点列车"), Component.literal("2"), Hud.Severity.WARN)),
    Component.literal("京沪线 / 上行")));
```

### 7.2 目标与严重度

| `Target` | 何时显示 |
|---|---|
| `DRIVER` | 玩家坐在驾驶位 |
| `DISPATCHER` | 玩家打开调度界面 |
| `DEBUG` | F3 或配置开启 |

`Severity`：`NORMAL` / `INFO` / `WARN` / `CRITICAL`——宿主据此配色。

---

## 8. PIS

同样是「引擎发布数据，宿主显示与对外推送」。

### 8.1 屏声明

```java
Pis.register(new Pis.BoardSpec(
    rl("mymod", "station_beijing_platform"),
    "北京站-站台屏",
    stationId,     // 所属车站 UUID
    6,             // 最多 6 行
    true));        // 显示经由
```

### 8.2 内容

```java
out.board(new Pis.BoardModel(
    rl("mymod", "station_beijing_platform"),
    revision++,
    Component.literal("北京站"),
    List.of(new Pis.BoardEntry(
        "G1",
        Component.literal("京沪线"),
        Component.literal("上海虹桥"),
        List.of(Component.literal("济南西")),
        scheduledTicks,      // 图定还有多少 tick（-1 = 未知）
        etaTicks,            // 实时预测（-1 = 未知）
        delayTicks,          // 延误
        Component.literal("3 站台"),
        Pis.Status.ON_TIME,
        null,
        Pis.Urgency.NORMAL))));
```

### 8.3 三个时间轴必须分开

| 字段 | 含义 |
|---|---|
| `scheduledTicks` | **权威计划**（图定时刻折算） |
| `etaTicks` | **实时预测** |
| `delayTicks` | 二者的差 |

Create 原生只给了一个 `int ticks`；CRN 的 `scheduled` 其实是**学习值**。不把「计划」与「预测」分开，准点率就没有意义。

### 8.4 车内视图

```java
Pis.ServiceInfo("G1", 下一站, 终点, etaTicks, delayTicks, Component.literal("车门开启"))
```

---

## 9. 时刻表与调度引擎

### 9.1 引擎注册

```java
DispatchKernels.register(
    ResourceLocation.fromNamespaceAndPath("mymod", "interlocking"),
    InterlockingKernel::new);
```

- 注册时机：mod 构造期 / `FMLCommonSetupEvent`
- 同时只激活一个（玩家在配置里选）；切换时旧的 `onDetach()`，其 `state()` 保留不删
- 重复 id → 立即抛异常（不静默覆盖）

### 9.2 内核生命周期

```java
public interface DispatchKernel {
    ResourceLocation id();
    default int apiVersion() { return 1; }
    void onAttach(KernelEnv env);            // state() 已恢复
    void onFrame(FrameContext in, DispatchFrame out);   // ★ 唯一必须实现
    default void onTopologyChanged(Topology.TopologyDelta d, KernelEnv env) {}
    default void onEvent(RailwayEvent e, KernelEnv env) {}
    default void onDetach() {}
}
```

调用顺序：`[全部 onEvent] → [onTopologyChanged?] → onFrame`。

### 9.3 持久化

```java
CompoundTag state = env.state();   // 永不为 null
state.putLong("revision", ++revision);
env.markStateDirty();
```

宿主负责存盘与恢复。切换引擎时旧数据保留不删，便于回滚。

### 9.4 错误处理

`onFrame` 抛异常 → 宿主捕获 → **丢弃该帧全部指令** → 若 `failSafeOnError` 则退回 Create 原生行为，并发出 `RailwayEvent.KernelFault`。

**内核必须假定自己随时可能被降级。**

---

## 10. 交路（时刻表对外的一层）

见 §4.3。补充要点：

| 能力 | API |
|---|---|
| 交路绑定信号制度 | `Service.signalSystemId()` |
| 按制度反查交路 | `Service.usingSystem(systemId)` |
| 冻结时校验制度存在 | `Service.freeze()` |
| 统一列出 | `ApiCatalog.of(Category.SERVICE)` |

---

## 11. ★ 注册总表：有哪些、用哪个

### 11.1 注册入口一览

| 类别 | 注册方法 | 时机 | id 命名空间 | 校验 | 查询 |
|---|---|---|---|---|---|
| **信号制度** | `SignalRegistries.registerSystem` | init | 自己的 | freeze 时校验机型/语义存在、有 STOP | `Topology.signalSystems()` |
| **信号机型** | `SignalRegistries.registerType` | init | 自己的 | 构造时校验灯位越界 | `Topology.signalTypes()` |
| **信号语义** | `SignalRegistries.registerAspect` | init | 自己的 | 重复即抛 | `Topology.aspects()` |
| **灯色** | `SignalRegistries.registerLamp` | init | 自己的 | 重复即抛 | `Topology.` 无（用 `SignalRegistries.lamps()`） |
| **地面设备** | `Devices.register(Kind.GROUND)` | init | 自己的 | 重复即抛 | `Topology.devices()` |
| **车载设备** | `Devices.register(Kind.ONBOARD)` | init | 自己的 | 重复即抛 | `Topology.devicesOfTrain()` |
| **HUD 面板** | `Hud.register` | init | 自己的 | 重复即抛 | `Hud.specs()` |
| **PIS 屏** | `Pis.register` | init | 自己的 | 重复即抛 | `Pis.boards()` |
| **交路** | `Service.register` | init | 自己的 | freeze 时校验制度存在 | `Topology.services()` |
| **调度引擎** | `DispatchKernels.register` | init | 自己的 | 重复即抛 | `DispatchKernels.registeredIds()` |

**统一冻结**：

```java
ApiCatalog.freezeAll();   // 内部顺序：信号 → ATO → 牵引 → 交路 → 设备 → HUD → PIS
ApiCatalog.allFrozen();   // 自检
ApiCatalog.entries();     // 打印全部已注册项
```

### 11.2 执行时「该用哪个」

| 我要… | 查什么 | 拿什么 |
|---|---|---|
| 看这台信号机能显示什么 | `Topology.signalType(site.typeId())` | `SignalType.displaysFor(aspectId)` |
| 看这台信号属于哪套制度 | `Topology.signalSystem(type.systemId())` | `SignalSystem` |
| 决定这条路用哪套规矩 | `Topology.service(svcId).signalSystemId()` | 制度 |
| 让信号显示某语义 | `out.aspect(id, side, aspectId)` | — |
| 找一个能扳的道岔 | `Devices.withCapability(SWITCH_MACHINE)` | `DeviceType` |
| 读某台设备状态 | `Topology.devicesOf(typeId)` → `DeviceSite.state` | `CompoundTag` |
| 给设备下命令 | `out.device(new DeviceCommand(...))` | — |
| 加一个驾驶台面板 | `Hud.register` + `out.hud(...)` | — |
| 更新站台屏 | `Pis.register` + `out.board(...)` | — |
| 让车走 / 停 | `out.proceed(...)` / `out.hold(...)` | — |
| 排进路 | `out.command(TrainCommand.withRoute(...))` | — |
| 拿到全部能力清单 | `ApiCatalog.entries()` | `List<Entry>` |

### 11.3 自检输出示例

```
SIGNAL_SYSTEM  ctdapi:mainline_4  — 5 机型 / 5 语义
SIGNAL_SYSTEM  ctdapi:yard        — 2 机型 / 3 语义
SIGNAL_TYPE    ctdapi:home_4      — 4 灯 / ROUTE / 系统 mainline_4
ASPECT         ctdapi:proceed     — PROCEED / 限速 100%
GROUND_DEVICE  mymod:axle_counter — axle-counter,train-detector / 只读
HUD_PANEL      mymod:dispatch_status — DISPATCHER / 优先级 0
PIS_BOARD      mymod:bj_platform  — 车站 <uuid> / 最多 6 行
SERVICE        mymod:g_line_1     — 京沪线 / 信号制度 mainline_4 / 优先级 10
DISPATCH_KERNEL mymod:interlocking — 调度内核
```

---

## 12. 完整类清单（25 个）

| 类 | 一句话 |
|---|---|
| `DispatchKernel` | 调度内核：每帧读状态、写指令 |
| `DispatchKernels` | 内核注册表 |
| `KernelEnv` | 生命周期服务：持久化 / 日志 / 配置 |
| `FrameContext` | 每帧输入：拓扑 + 遥测 + 设施状态 + 待处理事件 |
| `DispatchFrame` | 每帧输出：列车/信号/道岔/设备/HUD/PIS/事件 |
| `Topology` | 只读拓扑：节点、边、信号、岔口、车站、区段、设备、交路 |
| `Telemetry` | 列车实时状态：占用区间、速度、授权、里程计 |
| `Command` | 全部写回指令：`TrainCommand` / `AspectCommand` / `SwitchCommand` / `DeviceCommand` |
| `RailwayEvent` | 事件：到站、发车、扣车、进路、信号变化、冲突、内核故障 |
| `Rail` | 稳定标识：`NodeId` / `EdgeId` / `Span` |
| `ApiCatalog` | 统一索引：有哪些、冻结、自检 |
| `signal.SignalSystem` | 一套信号制度 |
| `signal.SignalType` | 一种信号机机型（灯位 + 语义映射 + 联锁语义） |
| `signal.Aspect` | 一个显示语义（类别 + 限速） |
| `signal.SignalSemantics` | 联锁语义（是否停车点、是否授予授权……） |
| `signal.LampColor` | 灯色 |
| `signal.LampSpec` | 灯座规格（色 + 模式） |
| `signal.LampMode` | 常亮 / 闪烁 / 脉冲 |
| `signal.LampDisplay` | 「第几号灯座、怎么亮」 |
| `signal.SignalRegistries` | 信号注册表 + 内置内容 |
| `device.Devices` | 设备类型与实例（地面 / 车载）+ 能力标签 |
| `timetable.Service` | 交路：绑定信号制度 |
| `hud.Hud` | HUD 面板声明与内容 |
| `pis.Pis` | PIS 屏声明与内容 |
| `remote.Remote` | 进程外引擎：`KernelTransport` / `FrameCodec` / `RemoteKernel` |

---

## 13. 写一个最小内核

```java
public final class FixedBlockKernel implements DispatchKernel {

    private static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath("mymod", "fixed_block");

    @Override public ResourceLocation id() { return ID; }
    @Override public void onAttach(KernelEnv env) {}

    @Override
    public void onFrame(FrameContext in, DispatchFrame out) {
        Topology topo = in.topology();

        for (Telemetry.TrainState t : in.telemetry().trains()) {
            if (t.derailed() || t.manuallyDriven()) continue;

            if (t.route() == null) {
                out.hold(t.id(), "无进路");
                continue;
            }

            // 到前车尾部的距离（宿主已按图坐标算好，见 Telemetry.footprint）
            double clear = nearestObstacle(t, in.telemetry());

            // 授权 = min(进路剩余, 到障碍距离) - 安全余量
            double ma = Math.max(0, Math.min(t.route().remaining(), clear) - 0.5);

            // 限速 = 能在 ma 内停下的速度
            double cap = Math.sqrt(2 * t.acceleration() * ma);

            out.proceed(t.id(), ma, Math.min(cap, t.maxSpeed()));
        }

        // 灯：本车进路覆盖 → 通行，其余 → 停车
        for (Topology.SignalSite s : topo.signals()) {
            ResourceLocation aspect = coveredByAnyRoute(s)
                ? SignalRegistries.Builtin.PROCEED.id()
                : SignalRegistries.Builtin.STOP.id();
            if (s.hasFirstFace())  out.aspect(s.id(), Topology.SignalSide.FIRST, aspect);
            if (s.hasSecondFace()) out.aspect(s.id(), Topology.SignalSide.SECOND, aspect);
        }
    }
}
```

注册：

```java
DispatchKernels.register(FixedBlockKernel.ID, FixedBlockKernel::new);
```

---

## 14. 宿主职责（谁来实现这个 API）

| # | 职责 | 要点 |
|---|---|---|
| H1 | 拓扑镜像 | 用 `Rail.NodeId` 等值语义键；**不能用 `TrackNode` 引用或 `netId`**（每次编辑/读档都重建） |
| H2 | 边缘可达性 | 边必须暴露 `enabled`；道岔会禁用未开通股道 |
| H3 | 遥测采样 | 占用区间从 `TravellingPoint` 算，**不能跨帧缓存**分区占用 |
| H4 | 指令执行 | 速度取 min；路径必须覆盖到下一岔口 |
| H5 | 安全钳位 | 在 `distance = speed` 之前钳速度（这是唯一能兜住所有直写的位置） |
| H6 | 持久化 | 每内核一个 `CompoundTag`；自己的 SavedData 存区段标注/交路 |
| H7 | 渲染 | HUD/PIS 数据 → 翻牌屏 / 面板 / 网络 |
| H8 | 配置 | 选哪个内核、总开关、`failSafeOnError` |

---

## 15. 版本与兼容

| 项 | 约定 |
|---|---|
| `apiVersion()` | 当前 1。破坏性变更时递增 |
| 接口演进 | 只加 `default` 方法，不删不改已有签名 |
| 指令记录 | 一旦发布不改字段顺序 |
| 内核故障 | 丢帧 + 降级 + `KernelFault` 事件 |
| 多内核 | 同时只激活一个；切换保留旧状态 |
| 与 Create | **不依赖**。宿主负责全部适配 |
| 与 CRN / S&R | 本 API 不引用它们；互相影响只发生在宿主实现层（见 `docs/04`、`docs/09`） |

---

## 16. 一句话

> **调度引擎说「怎么走」，宿主负责「让它走」，API 只说「用哪套规矩」。**
>
> 信号制度、设备、HUD、PIS、交路全部可注册；
> 引擎可以在游戏里，也可以在一台专门的服务器上；
> 一条铁路可以同时存在多套信号系统，由每条交路自己决定用哪套。
