# 18 — 0.1.0 发布说明

> 四个模组一起发。它们互相依赖，单独发任何一个都没有意义。

---

## 组成

| 工件 | 仓库 | 作用 |
|---|---|---|
| `create-train-dispatching-api-0.1.0.jar` | `Create-Train-Dispatching-API` | 契约层：没有 `@Mod`，没有 mixin，只定义类型 |
| `create-train-dispatching-host-0.1.0.jar` | `Create-Train-Dispatching-Host` | 宿主：镜像轨道图、采样列车、执行指令 |
| `create-train-dispatching-script-runtime-0.1.0.jar` | `Create-Train-Dispatching-Script-Runtime` | 用 Python 写调度 |
| `create-train-dispatching-web-0.1.0.jar` | `Create-Train-Dispatching-Web` | WebSocket 桥，给游戏外程序用 |

**装哪一个取决于你想怎么调度**：

- 只想跑 Java 内核 → api + host
- 想用 Python → 再加 script-runtime
- 想用浏览器/外部程序 → 再加 web

---

## 这一版能做什么

### 信号与线网
- 自定义信号制度（灯位、语义、机型），全部走注册表，无枚举硬编码
- 轨道图镜像成稳定 id 的只读拓扑，含节点、边、道岔、车站、区段
- 读取汽鸣铁道的道岔开通状态（`ISwitchDisabledEdge#isEnabled`，反射）

### 行车
- 每帧调度：采集 → 内核决策 → 应用
- **速度只收紧不放宽**：`min(原生, 内核)` 且不超过授权刹车曲线
- 信号灯色写入（语义 → 灯色的映射 + `cachedStates`）
- 指派链路：车辆编号 → 车组 → 车次 → 交路 → 真实 Create `Schedule`

### 脚本
- Python 注册信号制度 / 内核 / ATO / 设备 / 交路
- **定义类只在启动期注册**（信号、设备、交路），**算法类可热重载**（内核、ATO）——
  这条是强制的：重载期调用定义类注册会抛异常并给出修正建议
- 全量重载失败**整批回滚**（靠记录对象本身，不靠重跑旧文件）
- 沙箱：`import java` / 文件 / 进程 / 线程四道禁令

### 外部集成
- WebSocket：每帧快照 + 回执，断线三策略（`NATIVE` / `HOLD` / `GRACEFUL`）
- 异步任务代理：排图这类重计算不占 tick
- `OperationFeed` 事件订阅 + `ControlChannel` 外部指令（优先级高于内核）

---

## 已验证到什么程度

| 项 | 验证方式 | 结果 |
|---|---|---|
| 宿主每帧调度 | 真实服务端 | `frames=420 gameTime=140`，帧刻 3:1 |
| 拓扑镜像 | 程序化建图 + RCON | `nodes=2 edges=1` |
| 遥测采样 | 反射造车 | `trains=1` |
| 车辆编号索引 | 同上 | `units=1` |
| **速度钳位** | 原生 1.0 + 指令 0.4 | **`target=0.400`** |
| **信号写入** | 真实 `SignalBoundary` | 12 项单测，**并抓到一个左右面反了的 bug** |
| 指派 → 时刻表 | RCON | `appliedTrains=1 entries=2` |
| 脚本注册与重载 | 9 个验收测试 | 全绿 |
| Web 桥 | 18 项，裸 socket | 全绿 |
| 异步任务 | 13 项 + 跨语言 | 全绿 |

---

## 未验证（环境阻塞，不是实现缺口）

| 项 | 为什么 | 怎么验 |
|---|---|---|
| **列车真的按时刻表跑** | 程序化造的列车没有车厢实体，被 `Train.tick` 判 invalid 后移除 | 真实组装一列车，见 `docs/17` §3 |
| **道岔物理扳动** | 需要汽鸣铁道真实道岔的边点，位置匹配规则未观察 | 摆一个岔，见 `docs/17` §5 |
| Mixin 是否在发布 jar 里生效 | 开发环境不加载 | 装 jar 后看有没有 `hook never fired` 告警 |

第三条特别说明：宿主有 **双入口**（Mixin HEAD + `LevelTickEvent.Post`，按刻去重）。
即使 Mixin 在某个环境不生效，调度仍然工作，只是晚一帧。

---

## 装之前要知道

- **客户端也要装 ctdhost**。原因：`EdgePointType.read(FriendlyByteBuf)` 对未注册类型直接 NPE，
  宿主注册了边点类型，客户端缺了会在图同步时崩。
- **API 必须装**。它没有 `@Mod`，装不装看起来都一样，但另外三个都依赖它。
- 详细安装与排障见 `docs/16`。

---

## 已知限制

| # | 限制 | 影响 |
|---|---|---|
| L1 | 拓扑镜像是全量重建（非增量） | 大线网拓扑变化时有一次遍历开销 |
| L2 | `TrainState.route` 与 `odometer` 恒为空 / 0 | 依赖它们的脚本拿到占位值 |
| L3 | 道岔只做逻辑锁 | 岔位不动，但进路冲突检查有效 |
| L4 | 指派应用的时刻表站名来自交路的停站 id | 与真实车站对应关系由宿主决定，当前未接 |
| L5 | 开发环境 Mixin 不生效 | 已用事件入口兜底 |

---

## 下一步方向

1. 进游戏跑一遍 `docs/17`，清掉最后两项阻塞；
2. 拓扑镜像改增量（需要观察 `TrackGraph` 的变更时机）；
3. 道岔物理扳动（需要先观察位置匹配规则）；
4. 交路停站 id ↔ 真实车站的映射规则。
