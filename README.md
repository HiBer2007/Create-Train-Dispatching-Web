# ctd-web — WebSocket 桥

把调度帧通过 WebSocket 暴露给**游戏外的程序**：网页调度台、Python 脚本、Excel 插件、
另一个进程里的排图引擎。

> 它只搬运，不决策。真正的调度算法在外面。

---

## 连接

```
ws://<服务器地址>:8765/ctd
```

端口可用 `-Dctdweb.port=9000` 覆盖。握手是标准 WebSocket（`Sec-WebSocket-Version: 13`），
浏览器直接 `new WebSocket("ws://.../ctd")` 即可。

---

## 服务端 → 客户端（每刻一条）

```json
{
  "time": 12345,
  "trains": [{
    "id": "2b8f4c5e-...",  "name": "CRH2A-2013",
    "speed": 0.31,          "target": 0.40,
    "accel": 0.0075,        "maxSpeed": 1.4,
    "length": 42.0,
    "authority": 320.0,     "routeRemaining": 240.0,
    "manual": false,        "derailed": false,
    "leadingEdge": "minecraft:overworld@12,-64,34,0|...",
    "leadingDistance": 18.5
  }],
  "signals": [{ "id": "...", "side": "FIRST", "aspect": "ctdapi:green" }],
  "switches": [{ "node": "...", "branch": "...", "holder": 3, "contested": false }]
}
```

**单位是格/tick**，与 Create 内部一致。换算成 km/h 属于展示层，放进协议会让
「谁负责单位」长期模糊。

---

## 客户端 → 服务端（按需，可以不发）

```json
{
  "trains": [
    { "id": "2b8f4c5e-...", "authority": 120.0, "cap": 0.4, "hold": false }
  ],
  "aspects": [
    { "signal": "...", "side": "FIRST", "aspect": "ctdapi:red" }
  ]
}
```

- `hold: true` 或 `authority <= 0` → 停车
- 坏 uuid / 坏 aspect / 坏 side **逐条丢弃**，不影响同一帧里的其它条目
- **只保留最新一条回执**：晚到的旧决策比没有决策更危险

---

## 优先级：外部程序 = 操作员

桥把回执写进 `ControlChannel`（`Source.EXTERNAL`），宿主每帧
**先跑内核、再跑控制通道**。所以网页扣车不会被脚本下一帧放行。

反过来，如果你希望网页**就是**调度算法（不是覆盖），那它应该注册成内核而不是走桥。

---

## 断线策略

| 模式 | 行为 | 适用 |
|---|---|---|
| `NATIVE`（默认） | 不干预，退回 Create 原生 | 最不意外 |
| `HOLD` | 全部扣车 | 货运、无人线路 |
| `GRACEFUL` | 再等 N 刻（默认 100 = 5 秒），然后退回原生 | 高速干线，避免急停 |

**默认不是 `HOLD`。** 一条正常运行的线网因为浏览器崩了就全线停车，
后果比「暂时没人调度」严重得多。安全不等于一律停车。

---

## 验收

```
./gradlew bridgeTest
```

18 项，全部用裸 socket，不需要游戏也不需要浏览器：

```
ok  坏 uuid 的列车指令被丢弃      ok  服务端推送到达
ok  hold 指令被翻译成停车         ok  回执已收到
ok  坏信号/坏面被丢弃             ok  回执能翻译成指令
ok  握手返回 101                  ok  断线后连接被清理
ok  Sec-WebSocket-Accept 正确     ok  NATIVE / HOLD / GRACEFUL 三种策略
```

---

## 为什么不用 Netty

NeoForge 的开发编译路径里没有 `netty-codec-http`。为一个桥接去拉一条版本容易漂移的
依赖不划算——WebSocket 的服务端子集很小（一次 SHA-1 握手 + 两种帧格式），
自己写反而没有依赖也没有版本风险。
