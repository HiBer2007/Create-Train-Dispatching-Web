# 19 — 交接状态报告

> 面向接手的人。看完这一份，应当知道**哪些能信、哪些不能信、下一步做什么**。

---

## 1. 一句话现状

四个模组能一起在真实服务端里跑：宿主每帧调度、脚本（Python）能注册内核并驱动真实列车的目标速度、
信号灯色能写进 `SignalBoundary`、指派能落到 Create 的 `Schedule`。

**没有验证过的只有两件事**：列车真的在铁轨上位移，和道岔的物理扳动。
两者的原因是同一个：需要玩家摆放方块产生的实体，程序化造不出来。

---

## 2. 可信的部分（都跑过真实服务端）

| 能力 | 证据 |
|---|---|
| 宿主每帧调度 | `diagnostic frames=N gameTime=N/3` |
| 拓扑镜像 | `/ctd host status` → `topology: nodes=2 edges=1` |
| 遥测 | `trains=1`，`/ctd host status` 打印每车速度 |
| 车辆编号索引 | `units=1` |
| **速度钳位** | 原生 1.0 + 指令 0.4 + 脚本 0.35 → **0.350** |
| **信号写入** | `signalApplierTest` 12 项（并抓到左右面反了的 bug） |
| **脚本加载** | `[ctdscript] 1/1 scripts loaded`，GraalPy via JarJar 19 模块 |
| **脚本驱动** | `[demo] id=... name=CTD-DIAG`，`target=0.350` |
| **指派链路** | `Schedules`/`Assignments`：脚本发的 `demo:run900` 解析到 1 列车 |
| **改派语义** | `assignmentsTest` 9 项；输的一方被撤销而非崩溃 |
| 热重载 | 改脚本 → `/ctd reload` → 行为变化（靠 `DispatchKernels.revision()` 重建 pass） |
| Web 桥 | `bridgeTest` 18 项 · `taskBrokerTest` 13 项 · Python 标准库客户端实测 |

---

## 3. 不可信的部分

| 项 | 状态 | 原因 |
|---|---|---|
| 列车位移 | ❌ | 见 §4，四次尝试的确切失败点 |
| 道岔物理扳动 | ❌ | 需要汽鸣铁道真实道岔边点；位置匹配规则未观察 |
| Mixin 在发布 jar 里是否生效 | ⚠ | 开发环境不加载；有 `LevelTickEvent` 双入口兜底 |
| 大线网性能 | ⚠ | 只有 2 节点 1 边的拓扑，没压过 |

**任何依赖「列车真的在跑」的结论都不要采信。**

---

## 4. 列车位移：四次尝试的完整链条

| # | 做法 | 结果 |
|---|---|---|
| 1 | 直接放进 `waitingTrains` | `Carriage` 的 `serialisedEntity` 是空的，`EntityType.create` 返回 null → `train.invalid = true` → 被移除 |
| 2 | 补实体 NBT | `Contraption.fromNBT` 里 `fromType("")` 返回 null → `contraption.readNBT` NPE |
| 3 | 补 `"Contraption"` + `"Type": "create:carriage"` | `saveWithoutId` 抛 `ReportedException: Saving entity NBT` |
| 4 | 先 `setContraption` 再存 | `NPE: BlockPos.getX() because "p_129225_" is null` —— 构造的 `anchor` 是 null |

**每一步都能修，修完露出下一步，而下一步依然要求真实组装。** anchor 是组装时那个方块的位置。
四条都写在 `DebugTrainCommand` 的注释里，不用再走一遍。

---

## 5. 怎么继续

### 5.1 有图形客户端（最有价值）

按 `docs/17-ingame-verification.md` 走一遍。五项，十分钟。
做完之后 §3 的前两项就有结论了，届时可以打 1.0.0。

### 5.2 没有客户端

| 方向 | 价值 | 风险 |
|---|---|---|
| 拓扑镜像改增量 | 大线网吞吐 | 需要观察 `TrackGraph` 变更时机 |
| 交路停站 ↔ 真实车站映射 | G4 才真正可用 | 需要真实车站 |
| Web 桥接补 UI 语义 | 后台模组接入 | 无 |
| 打包与发布流水线 | 交付 | 无 |

---

## 6. 已知限制清单

| # | 限制 | 影响 |
|---|---|---|
| L1 | 拓扑全量重建 | 大线网下有遍历开销 |
| L2 | `TrainState.route` / `odometer` 是占位值 | 依赖它们的脚本拿到假数据 |
| L3 | 道岔只做逻辑锁 | 岔位不动，但进路冲突检查有效 |
| L4 | 交路停站 id 未必能匹配真实车站 | 匹配不上的指派会被跳过（并计数） |
| L5 | 开发环境 Mixin 不生效 | 已用事件入口兜底，晚一帧 |

---

## 7. 代码地图

```
mods/ctdapi        契约层（无 @Mod、无 mixin、无资源）
  operation/       UnitNumber · UnitSet · Run · Assignment · Assignments · ControlChannel · OperationFeed
  signal/          SignalSystem · SignalType · Aspect · SignalRegistries
  DispatchFrame · DispatchKernels · AtoEngines · Topology · Telemetry · Rail

mods/ctdhost       宿主（唯一每帧注入点）
  HostRuntime      帧序：kernels → ControlChannel
  dispatch/        DispatchPass · DispatchFrameImpl（含 assign）
  topology/        TopologyMirror · StableIds
  execute/         SpeedApplier · SignalApplier · SwitchApplier · CommandApplier
  operation/       UnitIndex · AssignmentResolver · AssignmentApplier · StationResolver · TrainScripts
  command/         /ctd host status|switches|assignments|debugtrack|debugtrain

mods/ctdscript     脚本运行时（GraalPy 24.1.2，JarJar 19 模块）
  facade/          FrameInput · FrameOutput · TrainFacade
  bridge/          SignalApi · KernelApi · AtoApi · OperationApi · …

mods/ctdweb        Web 桥（手写 WS 服务端）
```

---

## 8. 三条不该被忘掉的教训

1. **未验证的代码里藏着真 bug**。这几轮抓到的：`Couple.set` 的 true 是第一面（信号写反）、
   `UUID.randomUUID()` 首次调用 46 ms（tick 线程上）、改派最后一辆车抛异常（整帧调度作废）。
   没有一个是读代码能发现的。

2. **日志说「成功」不等于成功**。热重载曾经打印 `reloaded 1 scripts`，而旧脚本还在跑。
   原因是 `DispatchKernels` 存的是工厂，宿主按 id 判断不出「实例被换掉了」。

3. **脚本化改 Java 源码超过两处插入就该整文件重写**。
   `HostStatusCommand`、`HostRuntime`、`DebugTrainCommand` 各自被切坏过一次。
