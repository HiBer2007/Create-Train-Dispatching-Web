# 20 — 脚本定义内容：方块、纹理、HUD

> 这一份回答一个问题：**脚本能不能造出世界里和屏幕上的东西。**
> 答案是可以，但必须遵守「启动期注册」这一条铁律。

---

## 1. 为什么必须启动期

Minecraft 有两张表是**一次性**的：

| 表 | 什么时候关 | 后果 |
|---|---|---|
| 方块注册表 | `RegisterEvent` 之后 | 再注册会直接抛异常 |
| 资源包 | 资源首次加载之后 | 再挂包不会生效 |

所以脚本里「声明方块」和「提供纹理」两件事，只能在**启动期**做。
这不是设计选择，是运行时约束。

与既有划分对照：

| 目录 | 阶段 | 内容 |
|---|---|---|
| `signal/` `device/` `timetable/` | 启动 | 定义类 |
| **`block/` `hud/`** | **启动** | **内容类：方块与 HUD 定义** |
| `kernel/` `ato/` | 可热重载 | 算法类 |

热重载期间再调 `content.block(...)` 会得到明确异常：

```
block registration is closed: content definitions are startup-only
```

而不是「看起来注册成功、实际没有」。

---

## 2. 目录约定

```
config/ctd/
├── scripts/
│   ├── block/demo.py          ← content.block(...)
│   ├── hud/demo.py            ← content.hud(...)
│   ├── kernel/                ← 热重载
│   └── ato/
└── assets/                    ← 脚本自己的美术资源（**引擎会复制成资源包**）
    └── <namespace>/
        ├── textures/block/*.png
        ├── textures/item/*.png
        ├── textures/gui/*.png   ← HUD 贴图
        └── lang/en_us.json      ← 可选
```

**脚本只提供 PNG，不写任何 JSON。** blockstate、block model、item model
三份 JSON 由引擎按声明合成。

---

## 3. 注册一个方块

```python
from ctd import content

content.block("mysys", "bridge_panel",
              "mysys:block/bridge_panel",   # 纹理 id，省略则默认 ns:block/<name>
              2.0,                          # 硬度
              0,                            # 自发光 0–15
              True)                         # 是否有碰撞
```

对应的图片放在：

```
config/ctd/assets/mysys/textures/block/bridge_panel.png
```

引擎会合成：

| 产物 | 内容 |
|---|---|
| `blockstates/bridge_panel.json` | `{"variants":{"":{"model":"mysys:block/bridge_panel"}}}` |
| `models/block/bridge_panel.json` | `cube_all`，`"all": "mysys:block/bridge_panel"` |
| `models/item/bridge_panel.json` | 继承 block model |

---

## 4. 纹理怎么进游戏

引擎在构造期做三件事：

1. 把 `config/ctd/assets/` 复制到 `config/ctd/generated/resourcepack/assets/`；
2. 为每个声明的方块写三份 JSON；
3. 写 `pack.mcmeta`（`pack_format = 34`，对应 1.21.1），
   把整个目录复制到 `resourcepacks/ctd_scripts/`，
   并把 `file/ctd_scripts` 写进 `options.txt` 的 `resourcePacks`。

> **为什么不用 `AddPackFindersEvent`**：那个事件只接受**模组 jar 内部**的包——
> 它按命名空间去 `ModList.get().getModContainerById(ns)` 找模组文件，
> 外部目录进不去。NeoForge 没有公开「加一个外部资源包并强制启用」的 API，
> 所以走文件系统这条路。

**失败不影响方块可用**：资源包只决定外观，方块注册不依赖它。
装不上会打一条 `script resource pack: NOT installed`，不会静默。

---

## 5. HUD

**引擎画，脚本只下单。**

```python
from ctd import content

def draw(h):
    h.rect(6, 6, 120, 18, 0xAA000000)          # 背景条
    h.text(10, 11, "T0 次 12:30", 0xFFFFFFFF)   # 文字
    h.image("mysys:gui/badge", 132, 6, 16, 16)  # 贴图

content.hud("mysys", "badge", draw,
            2,    # 刷新间隔（tick），2 = 每秒 10 次
            0)    # 绘制顺序，小的先画
```

为什么不让脚本直接调用渲染：

> 那等于把渲染线程交给一个随时可能死循环的脚本。

下单便宜、可校验、可裁剪；画由引擎在渲染线程上做。
每个面板按自己的间隔重新求值，中间帧复用上次的图元。

连续失败 5 次后该面板停止求值（不会每帧刷屏）。

---

## 6. 时序（重要）

```
模组构造期
 ├─ 加载 scripts/（含 block/ hud/）      ← 收集定义
 ├─ 复制 assets/ + 合成模型 JSON         ← 生成资源包
 ├─ 封存 ContentRegistry                 ← 之后拒绝新定义
 │
 RegisterEvent
 ├─ BLOCK: 每个定义 → new Block(...)
 └─ ITEM : 每个定义 → new BlockItem(...)
 │
资源加载
 └─ resourcepacks/ctd_scripts 生效        ← 纹理与模型可见
```

**脚本加载必须早于 `RegisterEvent`**，所以它在构造函数里跑，
而不是 `FMLCommonSetupEvent`——后者在注册事件之后，那时再收集就晚了，
而且**不会报错**，只是方块永远不出现。

---

## 7. 自检

启动日志里会有：

```
[ctdscript] content sealed: blocks=1 huds=1
[ctdscript] script resource pack: installed -> .../config/ctd/generated/resourcepack
```

配方（`content.block`）写错时会在启动期直接抛异常并指明是哪一条，
不会把半个定义塞进注册表。

---

## 8. 实测记录

### 服务端（已验证）

```
[block] declared bridge_panel
[hud] declared badge
[ctdscript] content sealed: blocks=1 huds=1
```

`/ctd content`：

```
CTD script content: blocks=1 huds=1 (sealed)
  block mysys:bridge_panel texture=mysys:block/bridge_panel [registered]
  hud   mysys:badge every 2t
```

`/setblock 0 -60 0 mysys:bridge_panel` → `Changed the block at 0, -60, 0`

**脚本声明的方块确实进了注册表，而且能放置。** 这一步与资源包无关，
所以即使纹理没装上，方块也是可用的。

### 生成的资源包（已验证）

```
pack.mcmeta
assets/mysys/blockstates/bridge_panel.json
assets/mysys/models/block/bridge_panel.json
assets/mysys/models/item/bridge_panel.json
assets/mysys/textures/block/bridge_panel.png   ← 脚本提供的
assets/mysys/textures/gui/badge.png            ← 脚本提供的
```

三份 JSON 全是引擎合成，脚本只给了两张 PNG。

### 客户端（待验证）

纹理与 HUD 的外观要在客户端看：

- 方块应显示蓝色（测试图是 16×16 纯蓝），而不是紫黑格
- 左上角应出现 `CTD script HUD` 文字与一条半透明底条

⚠ 如果方块是紫黑格：资源包没被启用。检查 `options.txt` 的
`resourcePacks` 是否含 `file/ctd_scripts`，或在「选项 → 资源包」里手动勾选。