# 像素体系：计划

> **这一份是计划，不是设计正文。** 它是"放弃体素、改成像素 + 占用高度、世界引入维度"这三件事的施工单：
> 每一步做什么、做完之后什么必须还是绿的、按什么数验收。定稿之后它的内容并入 `docs/design.md`，
> 这一份就删掉。
>
> **分支**：`pixel-world`。`main` 停在 `d115c5d`（体素版），按用户的裁决**留给社区**，不再动它。
> **旧档不保**：存档 `format` 直接跳到 2（用户 2026-10-04 定：「不用管旧版了」）。
>
> 开工前的那份现状材料在 `docs/terrain-objects.md`（基线 `ac2c0f7`、tag `v0.1.0-terrain-objects-baseline`）。

---

## 1. 三条裁决（用户原话）

| 用户原话 | 落成什么 |
|---|---|
| 「依然保留原本渲染，物品属性等等，动的主要是体素的地形和物品模型的方式」 | **渲染那一层不动**：`Renderer` / `RegionView` / `ChunkView` / 属性 / 背景 / 句子 / 卡片 / 对话，一个字不改。改的只有"一样东西的物理体积怎么表示"与"地形怎么表示" |
| 「一个物体一个高度，相当于万物都是柱体」 | 物体的物理体积 = **一张平面像素轮廓 + 一个高度**（柱体）。不能空心、不能有腿、不能有多段 |
| 「改为像素 + 占用高度的方式」 | 地形 = **每格一个高度 + 一个材质**（高度场）；物体 = 上面那个柱体。两者共用 5 厘米/50 厘米这两把尺 |
| 「世界改成维度的体系」……「一楼是一个维度，二楼是一个维度，从楼梯上楼进入二楼维度的楼梯位置」……「维度内高度，维度之间，都是相对 z 轴的处理方式」 | **维度 = 一层空间**，层内 z 相对该层基面；**跨层（楼层、地窖、洞穴、异界、法宝内部）一律靠"另一个维度 + 一个入口"** |
| 「不用管旧版了，体素留给社区想办法吧，现在的像素体系作为分支的版本独立出来」 | 分支 `pixel-world`；`format = 2`；不写旧档迁移 |

### 一条由此推出的要害

**"地下"不再是同一张图里的负 z，而是另一个维度。** 体素版的附录 A.15 里那句
"地形是地下的体积、物体在地形表面"在新体系里变成：**每个维度只有一层地表**（高度场 + 材质），
往下一米就是那个维度的底，要"往下"就开一个维度、放一段楼梯。这一条决定了后面所有阶段——它一次
去掉了洞穴、悬空层、多层剖面、以及"一整列从 0 到第 256 格"的存储。

---

## 2. 表示（三个数据结构）

### 2.1 物体：`Prism`

```kotlin
// world/Prism.kt（新）
const val PIXEL_CM = 5                       // 与原来的 VOXEL_CM 同一个数，改个名字

@Serializable data class Pixel(val x: Int, val y: Int)   // 局部坐标，单位厘米，5 的整数倍

@Serializable data class Prism(
    /** 俯视轮廓：这个物体在平面上占哪几个像素。**同一像素最多出现一次。** */
    val pixels: List<Pixel>,
    /** 一个高度（5 厘米的整数倍）：从 base 往上占多高。 */
    val heightCm: Int,
    /**
     * 底面离它所在维度的地面多高（默认 0）。
     * **它是我拍的、不是你拍的**：没有它，"挂在墙上的灯""钉在门上的招牌""吊着的绳梯"就只能贴地放。
     * 它仍然是"一个高度"——只是这个柱体可以整体抬高。
     */
    val baseCm: Int = 0,
) {
    val footprint: Int get() = pixels.size
    fun problems(limits: PixelLimits = PixelLimits()): List<String>   // 与 VoxelShape.problems 同一条纪律
}
```

**轮廓与高度都是模型交的**，但只交**一次**：以前是"一层一层交到画完"（最多 40 个来回），
现在是"一张俯视图 + 一个高度"（**最多 2 个来回**）。这就是这一改最大的一笔收益所在。

### 2.2 地形：`Ground`（每格一个高度 + 一个材质）

```kotlin
// world/Terrain.kt（重写）
@Serializable data class Ground(val heightCm: Int, val material: String)

// 维度内的地形 = 稀疏表：格 -> Ground。**没记录的格 = 还没生成**（与现在"没剖面 = 没长出来"同义）
class Terrain(depth: Depth)        // depth：这个维度的基面与最高高度
    fun groundAt(cell: TerrainCell): Ground?
    fun heightAt(x: Int, y: Int): Int?          // 厘米
    fun materialAt(x: Int, y: Int): String?
    fun setHeight(cell, heightCm)   /  setMaterial(cell, id)  /  fill / dig / raise
```

- **一个格只有一个材质**（表层），底下统一是"那个维度的底"（不可破坏、不可进入）。
  体素版的三层剖面（土/石/岩层）因此消失。**这是我拍的**——若你要"挖下去能看到石层"，
  那件事的正确做法是**再开一个维度**（一个"地下"维度），而不是在同一个格子里存多段。
- 存档因此从"每列 2–3 段"变成"每格一个数 + 一个材质 id"：§14.59 那局的 **26,010 列 / 64,750 段**
  在新体系里是 **26,010 格**（一格一个高度 + 一个材质）。

### 2.3 维度：`Dimension`

```kotlin
// world/Dimension.kt（新）
@Serializable data class Dimension(
    val id: String,                       // "ground"（主层）、"upstairs"、"cellar"、"treasure"…
    val name: String,                     // 给人看的
    val depth: Depth,                     // 这个维度自己的基面与上限（相对 z 的家）
    val terrain: Terrain,
    val zoning: Zoning,
    val entities: Map<String, Entity>,    // 实体属于**一个**维度
    val generated: Set<ChunkKey>,         // 这个维度已经生成过哪些区块
)
```

- **共享**：世界钟、记录层（`EventLog`）、规则书、材质表、剧本池、发言名单、参考剧本库、对话。
- **独立**：地形、区划、实体、已生成区块、深度。
- **`Entity` 多一格 `dimension: String = "ground"`**；`z` 一律是**相对该维度基面**的厘米。
- **入口**（楼梯/门/传送）：一个实体（`kind = "入口"`，属性里写 `去=upstairs;在=1000,200`）+
  一条新后果 `Consequence.SendTo(id, dimensionId, x, y, z)`。楼梯就是两个入口（各自那一侧各一个）。
  **这是这条路上唯一新增的一种后果**；其余八种原样保留。

---

## 3. 阶段

每个阶段结束时：**`:shared:jvmTest --rerun-tasks` 全绿 + `:desktopApp:compileKotlin` 通过 + 一个提交**。
不绿不进下一阶段。

### 阶段 1：物体换成柱体（收益最大、最先做）

- 新增 `world/Prism.kt`、`world/PixelGrid.kt`（新的文本表：一张俯视图 + 一行高度）。
- `Entity.shape: VoxelShape?` → `Entity.prism: Prism?`；`Preset` 那一格跟着换。
- `judge/ShapeModeler.kt` → `judge/PrismModeler.kt`：协议从"`voxel_floor` 逐层交 + `voxel_done`"
  换成"`pixel_outline` 交一张轮廓 + `pixel_height` 交一个高度 + `pixel_done`"；
  **一个来回交得完，最多重试一次**。
- `Consequence.SetShape` → `SetPrism`；`Committer` 那一支跟着换。
- 出厂预设包 `files/builtin-presets.json` 重扫（`spike/PresetSweep`）。
- 渲染那一侧只动"读形状"的几处（现在读 `extent` / `cellCount` / `solid` 的地方），
  **`RegionView` / `ChunkView` / `Renderer` 的句子与属性一个字不改**。

**做完这一阶段的即时读数**（要真跑一次量出来）：建模从 185 次调用 / 41 分钟 / 单次最慢 194 秒，
降到"一件一次调用、最慢十几秒"。

### 阶段 2：地形换成高度场

- `Terrain` / `TerrainRun` / `TerrainColumn` / `TerrainSnapshot` 重写成 `Ground` 那套。
- 生成器那六只手里的地形四只改语义：`look_terrain` 印一张高度表 + 一张材质表；
  框选那三只从"填/挖/换材质"改成"**设高度 / 设材质 / 抬降**"；表格那只同步改。
- `world/TerrainGrid.kt` 重做（现在是"每格一个材质的三视图"，改成"高度表 + 材质表"）。
- 判据重写：`canStandAt`（站得住 = 该格有 ground 且高差在容差内）、
  `materialIdAt`、`isFilled`、射线遮挡（沿射线比高度，不再"整格实心"）。
- 到点就位（§14.44）与 `Active.canGo` 跟着换（位置仍写在实体上，只是高度来自高度场）。

### 阶段 3：维度

- `World`：`entities` / `terrain` / `zoning` / `generated` 四样搬进 `Dimension`；
  `World.snapshot()` 变成 `dimensions: List<DimensionSnapshot>` + 共享的那几样。
- **谁都必须带维度**：`RegionBuilder`、`audience`、`visibleTo`、`Witnesses`、哨兵、`ChunkView`、
  俯视图、到点就位、`longAbsent`。**漏一处的症状是"隔壁维度的事传过来了"**（静默，所以每处都要一支测试）。
- `Consequence.SendTo` + `Entity` 的入口属性 + 判定那一侧"踩在入口上"的识别。
- 界面：当前维度显示在顶栏；俯视图只画当前维度；入口在俯视图上标出来。
- 开局世界给一个**示范**：主层（酒馆那一层）+ 一个"楼上"维度 + 两段楼梯，让人一眼看懂这个体系。

### 阶段 4：删干净 + 定版

- 删 `world/Voxel.kt`、`world/ShapeGrid.kt`、`world/LayerGrid.kt`、`world/Shapes.kt` 的体素构造器。
- `ShapeLimits` → `PixelLimits`（轮廓像素数上限、高度上限）。
- `format = 2`；`SaveFile.peek` 那一句"档比程序新"的判据不变。
- `spike/` 里读旧形状的探针（`ShapeModelerProbe`、`PresetSweep`）改成新的；
  `docs/readings/2026-10-04/grown-world.infsave.json.gz` 只当"旧格式样本"，不再要求能读进来。

### 阶段 5：验收（拿同一把尺量）

**验收方式**：用与 §14.59 相同的那一局（同一套开局、同一串玩家动作、六格），跑一次联机，把账并排放。

| 量 | §14.59 的读数（体素版） | 目标（像素版） |
|---|---|---|
| 一件东西的建模来回 | ≤ 40 | **≤ 2** |
| `inf-generator` 调用 | 357 次 | **< 120** |
| 六格墙钟 | 112 分钟 | **< 30 分钟** |
| 同一局的调用时间 | 107.2 分钟 | **< 25 分钟** |
| 同六格存档 | 12.1 MB | **< 3 MB** |
| 测试 | 117 类 / 771 用例 | 重写后全绿；探针仍可重跑 |
| 花费 | 14.08 元 | **< 5 元** |

目标数没达到就不要说"改完了"——**它们是这次大改唯一的判据**（延迟与体积，不是"代码更漂亮"）。

---

## 4. 爆炸半径与"碰不得"

| 面 | 规模 | 处置 |
|---|---|---|
| 形状/体素相关引用 | **186 处**（约 30 个文件） | 阶段 1 与 4 逐处换掉或删掉 |
| `WorldSnapshot` / `World.from` 相关 | **112 处**（几乎每个测试夹具都手造世界） | 阶段 2、3 各改一轮；**夹具集中到少数几个共用的工厂**（现在是每支测试自己拼，改一次要动十几处） |
| 账与读数（§14.56–§14.59） | `LlmTrace` + `PlayLog` | **一个字都不改**——阶段 5 全靠它 |
| 判定与导演的提示词 | 形状那一段全换 | 换完之后**旧读数不可比**（提示词变了），所以阶段 5 必须真跑 |
| `GameSession.writes` 那一把锁 | — | 不动（这次不是并行那一刀） |
| 存档 | `format` → 2 | `SaveGame` 只改 `world` 那一格的形状 |

---

## 5. 我按推荐拍了、你可以推翻的五处

1. **`Prism.baseCm`**：允许柱体整体离地（吊灯、门牌、绳梯）。不加的话这些只能贴地。
2. **每格只有一个材质**：要"挖下去见石层"就开一个"地下"维度，而不是同格多段。
3. **入口 = 实体 + 一条新后果 `SendTo`**：不做"踩到某格自动传送"（那会把判定绕过去，与 §1.2"改世界必须显式动手"冲突）。
4. **主维度叫 `ground`**，其余维度由导演取名（`upstairs` / `cellar` / 法宝名字）。
5. **阶段顺序**：先物体（收益最大、风险最小），再地形，再维度。反过来做的话，地形换完但物体还在逐层画，收益要等到最后才出现。

---

## 6. 一开始就要做的两件工程事

1. **测试夹具搬家**：112 处 `WorldSnapshot(...)` 手造世界里，绝大多数只是"一个酒馆 + 几样东西"。
   先把它们收进一个 `TestWorlds`（`commonTest`）工厂，后面三个阶段每次改动才不用动几十处。
   **这一件事在阶段 1 之前做**。
2. **每个阶段都留一次真跑**：本地单测全绿只说明结构没坏；"它会不会照着新的表示把地形与东西长对"
   只有联机跑一遍才知道（§14.46 那次放人探针逮到的就是这类）。
