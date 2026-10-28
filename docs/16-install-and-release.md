# 16 — 安装与发布

> 四个模组 + 可选的外部程序。**装错顺序不会崩，但会静默不工作**，所以这份文档按「信任链」写。

---

## 1. 需要什么

| 组件 | 版本 | 必需 |
|---|---|---|
| Minecraft | 1.21.1 | ✅ |
| NeoForge | 21.1.219+ | ✅ |
| Create | 6.0.11+ | ✅ |
| **ctdapi** | 0.1.0+ | ✅ 契约层 |
| **ctdhost** | 0.1.0+ | ✅ 跑起来的前提 |
| **ctdscript** | 0.1.0+ | 想用 Python 写调度才需要 |
| **ctdweb** | 0.1.0+ | 想用浏览器/外部程序调度才需要 |
| 汽鸣铁道（Railways） | 0.3.0-beta.2（1.21.1 移植） | 可选，装了才能扳真实道岔 |

**ctdapi 必须被其它三个依赖**。它没有 `@Mod` 主类，装不装看起来都一样，
但缺了它另外三个都加载失败——所以它排在第一位。

---

## 2. 装什么

```
mods/
├── create-1.21.1-6.0.11.jar
├── create-train-dispatching-api-0.1.0.jar      ← 先装
├── create-train-dispatching-host-0.1.0.jar
├── create-train-dispatching-script-runtime-0.1.0.jar   （可选）
└── create-train-dispatching-web-0.1.0.jar              （可选）
```

**客户端也要装 ctdhost**。原因在 `AGENTS.md` §6 第 3 条：
`EdgePointType.read(FriendlyByteBuf)` 对未注册类型直接 NPE。
宿主注册了边点类型，客户端没有就会在图同步时崩。

---

## 3. 第一次启动看到什么

正常启动的日志（服务端）：

```
[ctdhost] attached; N registry entries
ctdhost: dispatch ticking (gameTime=1, dim=minecraft:overworld)
```

如果看到这条，说明**没接上**：

```
[ctdhost] WARNING: GlobalRailwayManager#tick hook never fired.
```

它的意思是「没有任何调度入口被触发」。开发环境里 Mixin 不可用是已知问题
（`docs/13` §11），但宿主有 `LevelTickEvent` 兜底，所以这条**不应该出现**。
出现了就说明宿主整体没跑起来，先查模组是否真的加载。

---

## 4. 配置文件

```
config/ctd/scripts/
├── signal/       ← 启动加载：信号制度、机型、灯位语义
├── device/       ← 启动加载：地面设备、车载设备
├── timetable/    ← 启动加载：交路、停站表
├── kernel/       ← 可热重载：调度算法
├── ato/          ← 可热重载：自动驾驶算法
└── disabled/     ← 放在这里的一律跳过
```

**定义类（前三者）与算法类（后两者）的区别是强制的，不是约定**：
重载时调用定义类注册会抛异常并告诉你该怎么改。

Web 桥端口：`-Dctdweb.port=9000`，默认 8765。

---

## 5. 命令

所有命令需要权限等级 2。

| 命令 | 作用 |
|---|---|
| `/ctd list` | 列出注册表内容 |
| `/ctd scripts` | 脚本加载报告 |
| `/ctd reload` | 全量重载；失败整批回滚 |
| `/ctd stats` | 各脚本内核的 p50/p99/max 耗时 |

---

## 6. 出问题先看哪里

| 现象 | 先查 |
|---|---|
| 列车完全不受控 | 有没有内核被激活？`/ctd list` 里有没有 kernel |
| 脚本改了没反应 | 是否在 `signal/device/timetable` 下（那三类要重启，或者直接 `/ctd reload`，它会全量） |
| 浏览器连不上 | 端口是否被占；服务端日志有没有 `ctdweb: listening on` |
| 列车急停 | 不是 bug——`frontSignalListener` 的硬停层。看是否授权突然变 0 |
| 能量总显示未知 | 没装 Electro Energetics；`UNKNOWN` 不阻止牵引（设计如此） |

---

## 7. 发布检查清单

发版前逐条过：

- [ ] 四个仓库 `git status` 干净，远端一致
- [ ] 四个 jar 的 `Implementation-Version` = 版本号（不是 `0.0NONE`）
- [ ] `./gradlew build` 四个仓库全部成功
- [ ] ctdscript 的 9 个验收测试全绿
- [ ] ctdweb 的 `bridgeTest` 全绿
- [ ] `docs/` 与各仓库 `docs/` 一致（`tools/sync-docs.ps1 -Check`）
- [ ] 参考树零写入（`AGENTS.md` R1）
- [ ] 进游戏实测一次：至少确认 `/ctd list` 有内容、列车能被扣停

**最后一条没法省。** 到目前为止所有验证都是离线或服务端启动，
「列车真的按脚本动起来」还没有被眼睛看见过。

---

## 8. 已知限制（写在这里，免得被当成 bug 报）

| # | 限制 | 影响 | 状态 |
|---|---|---|---|
| L1 | 拓扑镜像看不到汽鸣铁道扳开的股道 | 内核可能排进已扳开的股道 | `EdgeData` 无 `enabled` 字段，门控在 `canTravelTo` 里 |
| L2 | 道岔只做逻辑锁，不物理扳动 | 岔位不会动，但进路检查有效 | 需进游戏验证位置匹配规则 |
| L3 | `TrainState.route` 与 `odometer` 恒为空/0 | 依赖它们的脚本拿到的是占位值 | 待 C5 续 |
| L4 | 开发环境的 Mixin 不生效 | 宿主晚一帧调度 | 已用 `LevelTickEvent` 兜底 |
| L5 | 外部进程优化（E 阶段）未做 | 排图等重计算会占用桥的带宽 | 可选项 |
