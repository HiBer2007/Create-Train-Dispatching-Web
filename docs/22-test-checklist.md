# 22 — 等你测试：清单与判据

> 这份是给「现在没空、之后一次性验完」用的。
> 每一项都写清：**怎么操作**、**应该看到什么**、**不对的话说明哪里坏了**。
>
> 环境：服务端 `F:\TCM\mods\ctdhost` → `gradlew runServer`；客户端 → `gradlew runClient`。
> 两端共用 `run/`，端口 25565，RCON 25575。
> **改完代码必须重新 build 并把 `ctdscript` 的 jar 复制进 `run/mods/`**，
> 否则运行时用的还是旧代码（这个坑已经踩过一次，表现为 `NoClassDefFoundError`）。

---

## A. 显示板（本轮改动最大，优先验）

| # | 操作 | 应该看到 | 不对说明 |
|---|---|---|---|
| A1 | 放下显示板，准星对着它，`/ctd display 一号线\|往人民广场` | 方块**北面**出现两行白字 | 没字 → 渲染器没跑；字在但位置飘 → 偏移参数 |
| A2 | 斜着看这块板子 | 文字**完整**，不再缺半边 | 仍缺 → `SEE_THROUGH` 没生效或偏移不够 |
| A3 | `/ctd display bind time:` | 板子显示游戏内时间，**每秒变一次** | 不变 → 方块实体 tick 没跑（`getTicker` 没接上） |
| A4 | `/ctd display bind text:甲\|乙` | 显示「甲」「乙」两行 | 空 → 数据源前缀没注册 |
| A5 | `/ctd display sources` | 列出 `text` / `time` | — |
| A6 | 把板子装到车上、组装，`/ctd display 车内\|测试` | 车上那块立刻变，**随车移动** | 不变 → 构造物快照没写进去 |
| A7 | 车上板子 `/ctd display bind time:` | 车上时间每秒变 | 不变 → **已知限制**：车载板子目前没有服务端 tick，见 §F |

> A3/A4 是「拉模型」的核心证据：绑了源之后**没有人再去写它**，内容自己会变。

---

## B. HUD 与车载设备

| # | 操作 | 应该看到 | 不对说明 |
|---|---|---|---|
| B1 | 车上装机载显示设备 → 右键启用 → 坐上去 | 左上角出现速度表 | 无 → 闸门某一层没通过（日志有 `hud skipped (N): 原因`） |
| B2 | 松手滑行（不维持动力） | 速度**平滑下降** | 卡住 → 服务端车速推送断了 |
| B3 | 自动驾驶/无人操控时 | 速度仍是实时的 | 同上 |
| B4 | 松手瞬间 | 指针**不跳号** | 跳 → 驾驶台记忆没生效 |
| B5 | 换到另一端驾驶台并操作 | 指针方向翻转 | 不翻 → 驾驶台判据错 |
| B6 | 下车 | HUD 立即消失，且再上车不自动出现 | 还留着 → 下车清理没触发 |
| B7 | 两个人分别上车、各自右键 | 各看各的（一个人开不影响另一个人） | 互相影响 → 偏好不是按人存的 |

---

## C. 广播（声音）

| # | 操作 | 应该看到 | 不对说明 |
|---|---|---|---|
| C1 | `/ctd broadcast` | `voiceSources=2 ... local ready \| log ready` | — |
| C2 | `/ctd broadcast emit stopped` | 返回 `spoken by: local`，日志有 `(voice:local) mysys:announce/stopped` | 说 `log` → 对应 ogg 没找到 |
| C3 | `/ctd broadcast emit departing` | 返回 `spoken by: log`（没有该音频 → **降级**） | 说 `local` → 降级没按预期 |
| C4 | 站台上放个喇叭/等车进站 | 应收音（需真实运营事件） | ⚠ 未接站台设备，见 §F |
| C5 | `config/ctd/logs/broadcast.log` | 中文**正常显示**（UTF-8） | 乱码 → 又写回 `System.out` 了 |

---

## D. 线路限制与鸣笛

| # | 操作 | 应该看到 | 不对说明 |
|---|---|---|---|
| D1 | `/ctd host restrict speed 30 here 600` | `published ... on <edge>` | — |
| D2 | `/ctd host restrict list` | 列出该限制，并显示 `train X limited to 30 km/h` | 没有 train 行 → 边匹配失败 |
| D3 | 让车在该边跑 | 速度不超过 30 km/h | 超了 → 钳位没生效（注意 Create 速度单位是格/刻，30km/h≈0.42） |
| D4 | `/ctd host restrict horn here`，开过该边 | 听到风笛，且**每条边只响一次** | 每刻响 → 去重失效 |
| D5 | `/ctd host restrict clear` | `cleared N restriction(s)` | — |

---

## E. CBTC 与调度

| # | 操作 | 应该看到 | 不对说明 |
|---|---|---|---|
| E1 | `/ctd reload` 后看日志 | `[cbtc] selftest ok (4 checks)` | FAILED → 追尾算法坏了 |
| E2 | 等 100 帧 | `[cbtc] <车名> ma=... cap=...` | 没有 → 内核没注册 |
| E3 | `[dispatch] released=N held=M` | 授权够则放行 | — |
| E4 | **两列车同线**（关键，目前没验过） | 后车在前车后方减速，保持 ~8 格余量 | 追尾 → MA 计算或钳位有问题 |

> E4 是唯一能证明 CBTC 真的在工作的场景。现在试验线只有一列车，**这一项必然未验**。

---

## F. 已知未完成（测试时不用报这些）

| 项 | 状态 |
|---|---|
| 车载显示板的服务端 tick | ❌ 构造物上的方块实体不会走 `getTicker`；需要 `MovementBehaviour#tick` 才能让车内板子自动刷新 |
| K4 站台设备（喇叭/站台号牌） | ❌ 未开始 |
| PIS → 广播的站台侧事件 | ⚠ 车内事件已通，站台侧没有设备承载 |
| TTS / 模型语音源 | ❌ 接口在链上，`available()` 恒 false |
| CRN 显示器联动（方案 A） | ❌ **被依赖卡住**：本地没有 CRN 的 jar，无法编译/验证；需要你先装一个 |
| 岔区进路 / 站台精确停车 | ❌ 规划中，见 `docs/21` |
| 区间限速（按距离段生效） | ⚠ `Restrictions` 有 `from/to`，应用层目前按整条边处理 |

---

## G. 报问题时的最小信息

1. 哪个编号（A3 / B2 / …）
2. 期望 vs 实际
3. **客户端或服务端日志里对应的那几行**：
   - HUD：`[ctdscript] hud tick ...` / `hud skipped (N): ...`
   - 显示板：`/data get block <x> <y> <z>` 的输出
   - 广播：`config/ctd/logs/broadcast.log`
   - CBTC：`[cbtc]` / `[dispatch]`

有这几行，绝大多数问题不用猜。
