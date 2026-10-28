# 17 — 进游戏验证清单

> 这份文档的存在理由：剩下三项验证**必须**在图形客户端里做。
> 把它写成机械步骤，是为了「有十分钟就能验」，而不是每次都要重新想一遍。

---

## 0. 前置

装好四个 jar（见 `docs/16`），启动客户端，进一个**超平坦创造**世界。

打开聊天栏，输入命令需要作弊权限。

---

## 1. 宿主是否在跑（10 秒）

```
/ctd host status
```

期望：

```
CTD host status
  ticks       : 2439
  kernels     : 1
  topology    : nodes=0 edges=0
  units       : 0
  assignments : 1
  switches    : logical locks=0, railways=absent (logical only)
  ! topology empty — lay some track to exercise the mirror
```

**判定**：

| 字段 | 期望 | 不对说明什么 |
|---|---|---|
| `ticks` | 持续增长 | 0 或不动 → 宿主没接上 |
| `kernels` | ≥ 1 | 0 → 没有任何内核注册（ctdscript 没装？） |
| `topology` | 铺轨后 `nodes > 0` | 一直是 0 → 镜像没跑或没读到图 |
| `assignments` | 诊断内核会自己加 1 | — |
| 最后一行提示 | 铺轨后消失 | — |

> 以上输出已在真实服务端通过 RCON 实测（`tools/rcon.ps1`），
> 说明命令注册、执行、注册表读取都是通的。

---

## 2. 拓扑镜像非空（2 分钟）

1. 用 Create 的轨道工具铺一段 10~20 格的直线，两端各放一个**列车信号机**。
2. 再输入 `/ctd host status`。

期望变化：

```
  topology    : nodes>0 edges>0
```

**这是 L1（镜像看不见扳开的股道）之外的第一个真实验证点。**
`nodes` 与游戏里实际节点数对不上，就说明 `TopologyMirror` 的遍历有问题。

---

## 3. 列车能被扣停（5 分钟）

1. 组装一列 1~2 节的小车（不给时刻表）。
2. 装 **ctdscript**，在 `config/ctd/scripts/kernel/` 放一个最简单的内核：

```python
from ctd import kernel

class Stop:
    def on_frame(self, ctx, out):
        for t in ctx.trains():
            out.hold(t.id(), "test")

kernel.register("test", "stop", Stop())
```

3. 重启客户端（或标题界面进一次世界）。
4. `/ctd reload`，然后 `/ctd list` 确认 `test:stop` 在里面。
5. 给列车设定目的地让它动起来。

**期望**：列车立刻停住，日志出现 `held <uuid>: test`。

**这一步验的是完整链路**：脚本 → 桥接 → DispatchKernel → 宿主 → `train.targetSpeed = 0`。

---

## 4. 信号写入（3 分钟）

在上面内核里加一行：

```python
    def on_frame(self, ctx, out):
        for t in ctx.trains():
            out.hold(t.id(), "test")
        # 假设信号机 uuid 已知（可从 ctx.signals() 读）
        for s in ctx.signals():
            out.aspect(s[0], "FIRST", "ctdapi:red")
```

**期望**：信号机变成红灯。

⚠ 记住 `docs/13` 写的：**`cachedStates` 只影响显示，不影响行车判定**。
灯变红不代表列车会停——那是第 3 步验的东西。

---

## 5. 道岔物理扳动（需要汽鸣铁道）

1. 装汽鸣铁道 1.21.1 移植版，摆一个 T 型岔。
2. `/ctd host switches` 看 `railways=present`。

**当前状态**：`railways=present (physical switching NOT wired yet)`。
逻辑锁能用，物理扳动**故意留空**——位置匹配规则没有验证过，
凭猜写出来要么静默不生效、要么扳错方向，后者比不做更糟。

要接通它，需要在这里确认：哪条边是直股、哪条是左开、`getSwitchTarget()`
返回的节点与 `Rail.EdgeId` 的对应关系。确认之后填 `SwitchApplier` 里那个空方法。

---

## 6. 指派驱动时刻表（G4 后半）

`/ctd host status` 的 `assignments` 会增长（诊断内核每 600 帧发一次），
但**它还没有驱动 Create 的时刻表**。

要验这一步，需要：一列车 + 一个交路 + 一次 `Assignments.publish`，
然后看列车是否真的按交路停站。

**当前未实现**：`Assignments` 只进注册表，没有落到 `ScheduleRuntime`。

---

## 7. 验完之后该做什么

把上面每一步的**实际输出**贴回来。特别是：

- `nodes` / `edges` 的实际数字
- 列车是否真的停住
- 灯是否真的变色

有了这些，剩下三项就不再是「待验证」，可以直接改。没有的话，
继续写代码只是在猜。
