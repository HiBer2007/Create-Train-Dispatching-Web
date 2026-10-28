# 15 — 调度后台模组：预留的联动面

> 状态：**接口已预留，后台模组本身未开始**
> 相关：`docs/14`（运营身份）、`docs/06`（调度内核）、`docs/13`（宿主）

---

## 0. 后台要做什么，不做什么

| 后台做 | 后台不做 |
|---|---|
| 监看：列车位置、车次担当、信号、限制、应急 | 每帧决定列车速度 |
| 排图：编排交路与车次、分配担当车组 | 直接算占用与闭塞 |
| 下发：人工扣车、改派、临时限速、封闭区间 | 替换调度内核（那是内核的活） |

**分工的判据是时间尺度**：

```
内核       每帧（50ms）    —— 快，无状态，允许随时被打断
后台       每分钟/人工     —— 慢，有状态，人看得见
```

让后台参与每帧决策会立刻卡住——它的 UI 线程、网络、数据库都不该在 tick 里。

---

## 1. 三个预留接口

### 1.1 读：已有

后台不需要新接口就能读全部状态：

| 要什么 | 从哪拿 |
|---|---|
| 列车状态 | `Telemetry#trains()` → `TrainState` |
| 线路拓扑 | `Topology#nodes/edges/signals/switches/stations` |
| 谁跑哪个车次 | `Assignments#all/ofUnit/ofRun` |
| 线路限制 | `Restrictions#active/all` |
| 应急指令 | `Emergency#active/mode` |
| 注册表内容 | `ApiCatalog#entries()` |

### 1.2 推：`OperationFeed`（新）

轮询要么太吵要么太钝。订阅制在变化发生的那一刻通知：

```java
OperationFeed.subscribe(new OperationFeed.Listener() {
    @Override public void onAssignmentPublished(Assignment a) { refreshBoard(a); }
    @Override public void onRestrictionChanged(Restrictions.Restriction r) { markClosed(r.edge()); }
    @Override public void onEmergencyIssued(Emergency.Order o) { alarm(o); }
});
```

**回调在触发者的线程上执行。** API 不提供线程池——那会把「为什么我的回调卡了 200ms」
变成一个查不到答案的问题。订阅方自己切线程。

**回调异常被吞掉并计数**（`OperationFeed.failures()`）。监控端不该有让世界停下来的能力。

### 1.3 写：`ControlChannel`（新）

后台不是内核，不该被迫实现 `DispatchKernel`。控制通道让它随时插一句话：

```java
ControlChannel.hold(Source.EXTERNAL, now, Command.TrainCommand.hold(trainId), "调度台扣车");
ControlChannel.assign(Source.EXTERNAL, now, assignment);
```

宿主每帧的顺序是 **先内核、后控制通道**，且控制通道的结果**覆盖**内核。
理由：玩家在控制台扣了车、脚本下一帧就放行，会让人觉得控制台是坏的。

来源优先级：`OPERATOR(0)` > `EXTERNAL(1)` > `AUTOMATION(2)`。

---

## 2. 进程内 vs 进程外

| | 进程内（作为模组） | 进程外（网页/独立程序） |
|---|---|---|
| 读 | 直接调 API 静态方法 | `ctd-web` 的 WebSocket 快照（D 阶段） |
| 推 | `OperationFeed.subscribe` | WebSocket 推送 |
| 写 | `ControlChannel.xxx` | WebSocket 指令 → `ControlChannel` |
| 延迟 | 0 | 一个来回（≥10ms） |
| 崩溃影响 | 拖垮服务器 | 只影响后台 |

**推荐**：监看与排图放进程外（UI 技术栈自由、崩了不影响服），
**扣车这类安全相关的操作**走进程内模组或命令——不该因为浏览器崩了而失去制动力。

---

## 3. 车次与运行图的数据形状

后台排图需要的东西：

```
交路 Service      ── 停站序列 + 速度等级（长期）
   └── 车次 Run   ── plannedDeparture + 车次号（每天）
         └── Assignment ── 由哪些车组担当
```

**运行图（图上的那条线）** = 若干 `Run` 按 `plannedDeparture` 排序后的结果。
API 不提供排图算法，只提供表达与存储——排图是后台的领域知识。

`Assignment#allUnits()` 给出实际担当的车辆编号，
`UnitIndex`（宿主侧）把编号映射回 `Train`。

---

## 4. 还没定的（写在这里，避免以后拍脑袋）

| # | 问题 | 现状 |
|---|---|---|
| Q1 | 后台修改运行图后，如何通知正在跑的内核？ | 只有 `OperationFeed` 的单向通知；内核侧没有订阅入口 ⚠ |
| Q2 | 车次与车厢编组变更（摘挂）如何保持一致？ | `UnitSet` 可以改，但没有版本号，内核可能读到半新半旧 ⚠ |
| Q3 | 多后台同时连接时谁说了算？ | 未定。`Source` 只分来源类别，不区分具体实例 ⚠ |
| Q4 | 后台需要的历史数据（准点率、延误）由谁算？ | 现在没有历史存储，PIS 只给当前值 ⚠ |

**Q1 是需要最先解决的**：内核如果每帧都读 `Assignments`，那它会看到后台的修改，
不需要通知；但如果内核自己缓存了运行图，就必须有失效信号。
建议的做法是**内核每帧重读**，把「运行图缓存」留在后台侧——
这样 Q1 直接消失，代价是内核每帧多做一次表查找。
