# 地形物体系统：大改之前的留档

> **这一份是给"大改地形物体系统"那一刀的准备材料**，不是教程，也不是设计文档。它回答四个问题：
> 现在是什么样、为什么非改不可、改的时候哪些东西碰不得、原始证据在哪。正文的编号引用（§14.x、§12 第 N 项）
> 都指 `docs/design.md`。
>
> 写它的时间：2026-10-04，第二局（六格走完）的读数刚出来之后。

---

## 0. 基线

| | |
|---|---|
| 提交 | `662a909`（刀十六） |
| 标签 | `v0.1.0-terrain-objects-baseline`（打完这个标签之后才开始改） |
| 版本号 | `gradle.properties` 的 `infinitesimal.version = 0.1.0` |
| 测试 | `:shared:jvmTest --rerun-tasks` → **117 个测试类、771 个用例、0 失败** |
| 桌面端 | `:desktopApp:compileKotlin` 通过；`gradlew :desktopApp:run --no-daemon` 起来过多次 |
| 一局的真实体量（见 §2） | 存档 12.1 MB、455 次调用、14.08 元、107 分钟调用时间 |

**"留档"包含三样**：这个提交与标签、这一份文档、以及 §7 列的那些原始材料（已收进仓库，
`docs/readings/2026-10-04/`，除 66.7 MB 的 trace 外都在）。

---

## 1. 为什么非改不可：那一局的数

第二局（用户自己玩，1 小时 52 分钟，世界钟从第 1 天 18:00 走到 19:30，六格）：

| 量 | 读数 |
|---|---|
| 调用 | **455 次**（零失败） |
| 调用占住的时间 | **107.2 分钟** = 全部墙钟的 **72%** |
| 提示 token | 19,585,664，**命中率 97.7%** |
| 输出 token | 1,546,402 |
| 花费 | **14.08 元** |
| `inf-generator` | **357 次（78%）**、89.5 分钟、12.12 元 |
| `inf-director` | 34 次、13.5 分钟 |
| `inf-judge` / `inf-npc` / `inf-renderer` / `inf-sentry` | 27 / 26 / 10 / **1** 次 |

三个具体的病灶，按"最疼"排序：

1. **一次玩家动作可以等 73 分钟。** 那一段（`算后果…｜19:30`）从 15:36:44 跑到 16:49:51 还没有结算行，
   然后有一趟调用再也没回来（用户 17:04 关掉了窗口）。整局里**51% 的墙钟在"长地形"的 4 次串里**
   （9 / 12.9 / **48.2** / 6.2 分钟），而其余动作只要 0.4–1.9 分钟。
2. **模型一回合能点名任意多的东西，代码一件一件做。** 那一局有一次 `make_object`×16，以及 5 批 9–13 件
   的建模。§14.58 做的并行建模把这几批从 56.1 分钟压到 41.1 分钟（**省 15 分钟**），但
   **135 批建模里 130 批是单件**——收益被"名单的长度"卡住，不是被并发度卡住。
3. **单件建模本身很贵**：最慢的五趟是 194 / 181 / 143 / 137 / 136 秒（`MAX_ROUNDS = 40`，一件可以画到
   四十个来回）。而生成器那一路的回合是**串行**的（它要看一眼、铺一层、再看一眼）。

一条因果链值得单独记住：**玩家从酒馆 (1000, 200) 走到了 (7250, 1900)**（62.5 米，跨了大约八块 chunk），
于是触发了四次硬圈/缓冲圈的生成——**走了六十米，换来 357 次生成调用**。§12 第 40 项那句
"玩家每跨一块就同步生成一次，而一次生成是几十秒"，在这一局里量成了"每跨一块约 20 次调用、几分钟"。

---

## 2. 现状地图

### 2.1 数据在哪（`shared/src/commonMain/kotlin/io/github/k1midi0/infinitesimal/`）

| 文件 | 是什么 |
|---|---|
| `world/Terrain.kt` | **地形本体**：`Terrain`（稀疏、按列分段）、`TerrainRun(material, from, to)`、`TerrainColumn(x, y, runs)`、`TerrainSnapshot(columns, generated, depth)`；`TERRAIN_CM = 50`、`terrainCellOf`、`runsAt`、`materialIdAt`、`isFilled`；三个写入口 `fill` / `dig` / `replace` |
| `world/World.kt` | 世界本体：`entities` / `rulebook` / `clock` / `timeline` / `materials` / `terrain` / `zoning` / `log` / `speakQueue` / `scripts`；`snapshot()` 与 `from()` |
| `world/Entity.kt` | 一样东西：`id / kind / label / x / y / z / attributes / shape / background / sentences / cards / rulebook / cycle / todos / notes / active`；`WorldDepth(bottom, surface, top)`、`WorldSnapshot` 九格 + `scripts` |
| `world/Material.kt` | **材质表**：`Material(id, name, …, display(字符+颜色), breakable)`；开局七种 `soil / stone / bedrock / wood / grass / water / road`（优先级 15） |
| `world/Zoning.kt` | **区划层**：10 米一格、只有"地貌 + 备注"，是**导演的涂鸦**，生成时读它当输入，**允许与地形不一致** |
| `world/Voxel.kt` | **体素形状**：`VOXEL_CM = 5`、`VoxelShape`、`ShapeLimits`（`maxCells` 十万，**不是规则是保险丝**） |
| `world/ShapeGrid.kt` / `world/LayerGrid.kt` | 形状的**文本表示**与解析（模型交上来的是 `z=0` 加一格格字符） |
| `world/TerrainGrid.kt` | 地形给模型看的**文本表**（`render` / `tableProblems` / `boxProblems`）——地形与模型之间只有这一种界面 |
| `world/Preset.kt` / `PresetLibrary.kt` / `PresetPack.kt` / `BuiltinPresets.kt` | **预设库**：`Preset(name, brief, shape, category, tags)`；`byBrief` 是**缓存**（键就是那句要求），`learn` 把新造的收进生成包，`spawn(...)` 用它造一样东西；出厂包是资源文件 `files/builtin-presets.json` |
| `spatial/` | **视图**：`Region`/`RegionBuilder`/`RegionView`（身边那一片，印厘米坐标）、`ChunkView`（区块地图）、`ChunkDetail`、`Cells`（`metreCell` 是唯一的"米格"换算处）、`Ground`（脚下那层）、`Witnesses`、`Distance` |
| `generate/TerrainGenerator.kt` | **生成器**：一次调用 = 若干回合，六只手（框选三 + `look_terrain`/`edit_terrain` + `make_object` + `find_preset`）；`grow`（带 `maxAttempts`）/ `growOnce`（`MAX_ROUNDS`）/ `makeObject` / `warmModels`（§14.58 的并行建模） |
| `generate/GeneratorTools.kt` / `Placement.kt` / `Edges.kt` | 手与参数；放置的解析；相邻地块的**边缘剖面**（附给下一次生成） |
| `judge/Consequence.kt` | **八种后果**：`Move` / `SetAttribute` / `ChangeAppearance` / `Destroy` / `Spawn` / `Replace` / `SetShape` + 三种地形后果 `FillTerrain` / `DigTerrain` / `ReplaceTerrain` |
| `judge/Committer.kt` | **提交层**：整批校验、整批提交（半批落进去是这里唯一不许发生的事） |
| `judge/ShapeModeler.kt` | **建模**：一句要求 → 一份体素形状；`draw` 的回合循环（`voxel_floor` / `voxel_done` / `give_up`）；`modelAll`（并行画、串行落库） |
| `judge/Judge.kt` | 两步判定；`make_object` 那只手在这里，落到 `ShapeModeler` |
| `session/StartingWorld.kt` | 开局那片地（硬性圈半径 64 格画成圆、三层剖面）、开局的几样东西、`snapshot()` |
| `session/GameSession.kt` | 会话：`writes` 一把锁、`run(...)` 的 `finally` 自动存档、`abortTurn()`、`working()`；后台那一看、硬圈缺口那一条 |
| `save/` | 存档与**账**（`PlayLog`）；`llm/Exchange.kt` 的 `LlmTrace` 抄每一次调用 |

### 2.2 一次"长地形"的完整链条

```
玩家动了（走 / 过一格）              GameSession.runTurn / nextTurnNow
  → closeGap()：代码发现硬圈里还没生成的 chunk        （§14.35：同步叫思考档，玩家等）
  → Director.wake(Wake.HardGap)                      导演思考档
      · hands: schedule / grow_terrain / look_map / write_cycle / spawn_character / write_card /
               promote_character / look_script / write_script / look_reference
  → TerrainGenerator.grow(requirement, chunks, placements)
      · 一回合：look_terrain / fill_terrain / dig_terrain / replace_terrain / edit_terrain /
                find_preset / make_object
      · make_object → 预设命中（免费）或 ShapeModeler.modelAll（并行画、串行落库）
  → 后果整批交回：Consequence.FillTerrain / DigTerrain / ReplaceTerrain / Spawn
  → Committer：整批校验、整批提交
  → 世界变了；下一次"看它一眼"才渲染新东西（§7.1）
```

**关键性质**：地形与物体都由模型生成（代码没有任何默认剖面）；`chunk`（16×16 地形格 = 8×8 米）
是**单次生成的最小单位**、也是"不会撞出厘米级缺口"的保证；硬性圈半径 4 块、缓冲圈 32 块。

### 2.3 一局六格之后，世界实际长成了什么样（那一局的存档）

| 量 | 读数 |
|---|---|
| 实体 | **114 个**：tree 16、furniture 14、bush 10、plant 8、**角色 6**、rock 5、grass 5、tool 5、fence 5、器具 5、building 4、barrel 4、stump 3、farm 3、fuel 3、其余零星 |
| 地形 | **26,010 列 / 64,750 段 / 约 3,447,583 格**（一格 50 厘米） |
| 已生成区块 | **104 块** |
| 材质 | 7 种（开局那七种，一种都没新增） |
| 区划 | **9 格**（导演几乎没在图区划） |
| 时间轴 | 11 件｜记录 15 条（`next = 16`） |
| 生成包 | 12 条（都带形状）｜参考剧本 6 条｜剧本池 6 条 |
| 对话 | 3 份｜笔记 0 条 |
| **存档** | **12.1 MB**（上一局同样是开局那一份时是 2.07 MB——六格长了六倍，其中地形约占 85%） |

两个可以直接看出来的东西：**生成器主要在生产"树/灌木/家具"这类常景**（114 个实体里约 90 个是它们），
而**玩家跑到了 (7250, 1900)**、生成器在 (7150, 1900) 放了一个叫「矮人」的角色——与开局那个
`dwarf(1000, 200)` 是两个实体、同一张脸。

---

## 3. 铁的约定（改的时候必须保住）

这些是散在 §12、附录 A 与各 §14.x 里的硬约束，**每一条都有测试或读数在背后**：

1. **一格 50 厘米、坐标一律厘米整数**（§12 第 10 项）。"1 格 = 1 米"只是空间框的视图，换算只在
   `spatial/Cells.kt` 的 `metreCell` 一处。
2. **地形按列分段存**（`TerrainRun`），不是按格存——开局那块地按格存就是 330 万格。
3. **"空气"不是材质**：挖掉 = 清掉那一格（附录 A.15）。
4. **地形挡射线**，与实体同级（一格米格要**整格**被填满才算实心，附录 A.15）。
5. **`shape` 不可变，"拷贝"是白拿的**（附录 A.13）——同一份形状可以被许多实体共用。
6. **建模的产物是一条预设**（不是加速表）：它进生成预设包、**随存档带走**（A.14）。
7. **预设的缓存键是那句要求**（`byBrief`）——要求写得越具体，命中越准。
8. **八种后果 + 整批原子提交**：半批落进去，模型重试时会看到一张变了一半的世界。
9. **关系不存成事实**（附录 A.5）：`Entity.parent` 已删，"东西在哪儿"由坐标与地形回答。
10. **区划与地形允许不一致**：区划是意图，地形是事实（附录 A.16）。
11. **chunk 是最小生成单位**：一次生成要么不写、要么整块写（不会有厘米级缺口）。
12. **氛围 NPC 到点就位是"代码挪坐标"**，零模型调用（§14.44）——地形改了之后这条不能变成"每次要问模型"。
13. **同一格里的行动顺序是玩家 + 活跃 + 剧情角色按发言队列串行**（§14.51）；`writes` 那一把锁现在是
    "所有会写世界的工作串起来"（§12 第 42 项）——真并行要先想清这一条。
14. **地形/物体不在提示词里被"渲染"出来**:模型看到的是 `TerrainGrid` 的文本表与 `RegionView` 的清单。

---

## 4. 病灶：为什么现在这套要改

| # | 症状 | 机制上的原因 | 可能的刀口 |
|---|---|---|---|
| 1 | 一次动作等 73 分钟 | 硬圈那条路**同步**叫思考档、玩家等；而"一次生成"没有预算上限 | 给一次动作能长多少地设上限，其余排后台（§8.3.1 那句"决策同步，生成异步"本来就写着） |
| 2 | 357 次生成调用 / 4 次串 | 每跨一块触发一次；每次生成又是多回合 | 生成的范围与回合数要有预算；预先在玩家周围生成（而不是等他走到才长） |
| 3 | 135 批建模里 130 批是单件 | 模型一次只点名一件东西；而单件要几个到四十个来回 | ① 让它一次把要放的东西说全；② 让单件更快（少来回 / 降 effort——用户此前定的是"不限制思考"）；③ 并行只能在"名单长"时帮上忙 |
| 4 | 最慢单次 263 秒 | 思考档 high + 不设 `max_tokens` + 长输出（网格是一大段文字） | effort、`max_tokens`、以及"一次只交一层"的协议都值得重新想 |
| 5 | 存档六格长到 12.1 MB | 地形按列分段存，但一局会长出两万六千列 | 地形那段的分块/压缩/增量（现在每一回合都全量写一次） |

---

## 5. 改动会碰到的六个硬点

1. **存档兼容**：`WorldSnapshot` 九格 + `scripts` 是 `format = 1`；改地形/物体的表示就是**换 format**，
   而"旧档迁移"这条路**一寸都没走过**（§12 第 33 项）。这是第一件要先定的事。
2. **探针与测试的可重跑性**：`spike/` 下二十多个探针、`commonTest` 里 117 个测试类。改动只要碰
   `Terrain` / `PresetLibrary` / `VoxelShape` 的公开形状，就会连带一大片（这一条不是反对改，是排工期）。
3. **账与读数**（§14.56–§14.59）：`LlmTrace` + `PlayLog` 是现在唯一能回答"它当时看见了什么"的东西，
   改动期间**不要让它失效**——下一局的读数全靠它。
4. **`writes` 那一把锁**：`GameSession.writes` 把判定第二步、导演每一次唤醒、AI 角色每一次反应串起来。
   要真并行（§12 第 42 项），先要的其实是"先在一个快照上算后果、之后再提交"，而这会碰到提交层的原子性。
5. **可见范围比想象的紧**（§14.53 量到）：挪到离玩家 3.4 米就已经不在 `visibleTo` 里。生成范围、导演能改
   哪些地块、以及"到点就位"都建在它上面。
6. **平台口**：读写文件只有 `image/AttachedImages.kt` 那一对 `expect/actual`（`readBytesOrNull` /
   `readTextOrNull` / `writeTextFile` / `appendTextToFile`），**没有 okio、没有 atomicfu、没有 sql**
   （§7.4）。要落盘新格式，只能走这四个函数。

---

## 6. 已经踩过的坑（不要再踩一次）

- **`z` 是脚底，而地面在第 256 格**：§14.45 那个坑——"那一格有材质就是被埋了"会把世界上每个人都判成
  站不住（开局那片地铺到第 256 格）。
- **循环的相位边界**（§14.44）：`periodMinutes` 默认是"最后一格的时刻 + 1"；差一格是**静默**的
  （某一格永远轮不到）。
- **回合起点的差一**（§14.40）：`turnStartSeq = latestSeq + 1`。
- **断言要落在"这一次请求新拼的那一段"里**：请求体里装着整串历史，而历史里存着之前几次请求
  （§14.43；用 `substringAfterLast("\"role\":\"user\"")`）。
- **记录一次调用要记那一刻的快照**：`messages` 常常是**活的** `mutableListOf`，稍后在另一个线程序列化
  会得到 `ConcurrentModificationException`（§14.57）。
- **不要并行跑两个 gradle**（同一个 build 目录）：测试 runner 会报假失败；App 跑着的时候不要编译
  同一个模块。
- **取消一个还没开始跑的协程，`finally` 不会执行**（§14.58）：`launch(start = UNDISPATCHED)` 才对。

---

## 7. 原始材料在哪

| 材料 | 位置 | 体量 |
|---|---|---|
| 这一局的账（给人读） | `docs/readings/2026-10-04/session.log` | 79 KB |
| 这一局的逐次调用表（从 trace 摘的） | `docs/readings/2026-10-04/calls.txt` | 45 KB，455 行 |
| **完整 trace**（每一次调用的完整提示词与回答） | `D:\存档\world.infsave.trace.jsonl`（**不进仓库**） | 66.7 MB |
| 那一局的存档（长成的世界样本，改格式时拿它当回归） | `docs/readings/2026-10-04/grown-world.infsave.json.gz` | 1.02 MB（原始 12.1 MB） |
| 那一局的存档原件（还活着，可以直接读） | `D:\存档\world.infsave.json` | 12.1 MB |
| 读数写进文档的那几节 | `docs/design.md` §14.57（第一局）、§14.59（第二局） | — |

## 8. 要用户拍的（大改之前）

- **旧档要不要保**：`format = 1` 的那份 12.1 MB 存档是"迁移"这条路的唯一素材；不保的话 `format` 直接跳。
- **地形的表示要不要换**：现在"按列分段"是为开局那 330 万格选的（附录 A.15）。大改是想换**存储**
  （分块、压缩、增量）还是换**生成方式**（预算、并行、预生成）？这两件事的刀口完全不同。
- **§12 里与地形物体直接相关的未定项**：第 31 项（谁能新增材质）、第 32 项（地形要不要上俯视图的色、
  按什么粒度）、第 40 项（硬圈同步的账单——现在量出来了）。
