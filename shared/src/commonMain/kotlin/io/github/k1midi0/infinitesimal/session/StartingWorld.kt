package io.github.k1midi0.infinitesimal.session

import io.github.k1midi0.infinitesimal.world.Beat
import io.github.k1midi0.infinitesimal.world.CHUNK_CELLS
import io.github.k1midi0.infinitesimal.world.CharacterCard
import io.github.k1midi0.infinitesimal.world.Cycle
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.HARD_RADIUS_CHUNKS
import io.github.k1midi0.infinitesimal.world.Landform
import io.github.k1midi0.infinitesimal.world.RuleEntry
import io.github.k1midi0.infinitesimal.world.Rulebook
import io.github.k1midi0.infinitesimal.world.Shapes
import io.github.k1midi0.infinitesimal.world.TerrainCell
import io.github.k1midi0.infinitesimal.world.TerrainColumn
import io.github.k1midi0.infinitesimal.world.TerrainRun
import io.github.k1midi0.infinitesimal.world.TerrainSnapshot
import io.github.k1midi0.infinitesimal.world.Todo
import io.github.k1midi0.infinitesimal.world.WorldDepth
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import io.github.k1midi0.infinitesimal.world.WorldTime
import io.github.k1midi0.infinitesimal.world.ZoneBlock
import io.github.k1midi0.infinitesimal.world.ZoneCell
import io.github.k1midi0.infinitesimal.world.ZoneTile
import io.github.k1midi0.infinitesimal.world.ZoningSnapshot
import io.github.k1midi0.infinitesimal.world.chunkOf
import io.github.k1midi0.infinitesimal.world.terrainCellOf
import io.github.k1midi0.infinitesimal.world.zoneCellOf

/**
 * 开局的那个世界。
 *
 * **它是内容，不是配置。** 放在代码里只是因为现在还只有这一个；等有了第二个场景，它就该搬家
 * （一个 JSON 文件，或者一张表）。那时要顺手加一条：**开局世界的合法性也得过一遍 [io.github.k1midi0.infinitesimal.judge.Committer]**
 * ——手写七个实体、坐标写错一位是迟早的事，而那种错得等到运行时才炸。
 *
 * 这里的东西**故意留着没渲染**（`sentences` 为空）：一进游戏就该看到"没见过它"的状态，
 * 玩家点一下才烧一次渲染（§7.1）。
 */
object StartingWorld {

    /**
     * 世界自己的规则书（§12 第 12 项）。**它是所有规则里最泛的一份**，默认优先级 10。
     *
     * 两条都是"这个世界怎么运转"级别的话，不是某一样东西的性质——东西的性质写在它自己的 [Entity.rulebook]
     * 里。这两句故意都很短：规则书要能一眼读完，长规则书的效果是模型开始跳读。
     */
    fun rules(): Rulebook = Rulebook(
        listOf(
            RuleEntry("火会烧起来：木头碰到火先冒烟，再烧着。"),
            RuleEntry("水往低处流，不会自己往上走。"),
        ),
    )

    /**
     * **地面在世界坐标里的厘米高度。**
     *
     * 世界的地面默认在第 256 个地形格（附录 A.15），也就是 12800 厘米。开局这张酒馆站在地上，所以
     * 下面每一个 z 都写成 [GROUND] 加一个偏移——**写死 0 会让整座酒馆沉到地下一百二十八米**，而
     * 那个错没有任何东西会报。
     */
    private val GROUND: Int = WorldDepth().surfaceCm

    /**
     * 开局的**地层剖面**（附录 A.15、A.16）。
     *
     * 从下往上三层：**一格岩层、石、土**。最底下那一格是 `breakable = false` 的岩层——它是"挖穿
     * 世界底"这件事在数据上的唯一保险，所以它必须在，哪怕开局谁也挖不到那里。
     *
     * ## 范围就是 A.16 那个硬性圈
     *
     * 以玩家为心、半径 [HARD_RADIUS_CELLS]（64 格 = 32 米），**画成圆**；圈里每一列从第 0 格铺到
     * 地面。**这正是硬性圈那一整片**，所以它底下那些 chunk 要一并标成「已经有地形了」。
     *
     * ## 它是内容，不是配置
     *
     * A.16 写着**没有代码默认剖面**——「这里该长什么样」是内容问题，该由生成器读着区划产出。这一份
     * 手写的剖面是给**开局那一圈**用的，它扮演的是「冒险准备阶段用户和导演讨论时定下的那片地」
     * （§14.30）。等准备阶段真做出来，它就该被换掉。
     *
     * ## 「已经有地形了」是标在哪儿的
     *
     * [TerrainSnapshot.generated] 里。**它不区分来源**（§14.30）：冒险准备定下的、外部导入的、
     * 生成器长出来的，三者等价——都是「这块地有地形了」。少一个字段，是因为多出来的那个字段没有
     * 任何一处代码会去分支。
     */
    val ground: TerrainSnapshot by lazy { layGround(WorldDepth()) }

    private fun layGround(depth: WorldDepth): TerrainSnapshot {
        // 圆心跟着玩家走，不另写一份坐标——两份写法迟早会漂。
        val seat = tavern().first { it.id == PLAYER }
        val home = chunkOf(TerrainCell(terrainCellOf(seat.x), terrainCellOf(seat.y), depth.bottom))
        // 土与石的分界放在七成深处：上面那七十七层（3.85 米）是土，下面是石。
        val stoneTop = depth.bottom + (depth.surface - depth.bottom) * 7 / 10
        val profile = listOf(
            TerrainRun("bedrock", depth.bottom, depth.bottom),
            TerrainRun("stone", depth.bottom + 1, stoneTop),
            TerrainRun("soil", stoneTop + 1, depth.surface),
        )

        // **圈是拿整块挑出来的，块里的格一格不少。**早先那版按格画圆，于是硬性圈边上留下半块的
        // chunk——那些块会被标成「已经有地形」，而界外那几列空着、谁也不会再去补。那正是
        // 「地形以 chunk 为最小生成单位、不会有厘米级缺口」的反例（§14.30）。
        val columns = buildList {
            for (dcx in -HARD_RADIUS_CHUNKS..HARD_RADIUS_CHUNKS) {
                for (dcy in -HARD_RADIUS_CHUNKS..HARD_RADIUS_CHUNKS) {
                    if (dcx * dcx + dcy * dcy > HARD_RADIUS_CHUNKS * HARD_RADIUS_CHUNKS) continue
                    val x0 = (home.x + dcx) * CHUNK_CELLS
                    val y0 = (home.y + dcy) * CHUNK_CELLS
                    for (dx in 0 until CHUNK_CELLS) {
                        for (dy in 0 until CHUNK_CELLS) {
                            add(TerrainColumn(x = x0 + dx, y = y0 + dy, runs = profile))
                        }
                    }
                }
            }
        }

        // 圈里那些 chunk 就是「已经有地形了」的那些。**从列上算出来，不另写一遍半径**：两份算法
        // 迟早会漂，而漂了之后的表现是"生成器把脚下重铺一遍"。
        val covered = columns.map { chunkOf(TerrainCell(it.x, it.y, depth.bottom)) }.distinct()
        return TerrainSnapshot(columns = columns, generated = covered, depth = depth)
    }

    /**
     * 开局那张**区划**（附录 A.16）。
     *
     * 区划是**导演涂在地图上的意图**，不是事实——「区划是意图，地形是事实」，两者允许不一致。它的
     * 用处只有一个：生成器读它，决定一片地方该长成什么样。
     *
     * 这一份同样是内容，是「游客先涂鸦」那一版的替身：等导演真能自己涂了，这一份就该被它涂出来的
     * 那张盖掉。
     */
    fun zoning(): ZoningSnapshot {
        val seat = tavern().first { it.id == PLAYER }
        val home = ZoneCell(zoneCellOf(seat.x), zoneCellOf(seat.y))

        fun at(dx: Int, dy: Int, landform: Landform, note: String) = ZoneBlock(
            ZoneCell(home.x + dx, home.y + dy),
            ZoneTile(landform, note),
        )

        return ZoningSnapshot(
            listOf(
                at(0, 0, Landform.TOWN, "灰渡镇中心，酒馆这一片"),
                at(1, 0, Landform.ROAD, "往东出镇的石板路"),
                at(2, 0, Landform.ROAD, "出镇之后那条官道"),
                at(3, 0, Landform.FOREST, "官道北边那片林子"),
                at(1, 1, Landform.FOREST, "镇东那片老橡树林"),
                at(0, 1, Landform.TOWN, "镇南的住家"),
                at(-1, 0, Landform.WILD, "镇西的荒地"),
                at(-1, 1, Landform.WILD, "镇西南的坡地"),
                at(0, -1, Landform.WILD, "镇北的野地"),
            ),
        )
    }

    /**
     * **开局世界打包成一份快照。**
     *
     * 存在的理由只有一个：`WorldSnapshot(StartingWorld.tavern())` 那句话**看起来是对的，而它漏掉了
     * 三样东西**——世界规则、地形、区划。三样都带默认值，所以它不会报错，只会给出一个"规则书是空的、
     * 脚下一格地都没有"的世界。
     *
     * 这个坑踩过两次：第一次是规则书（§14.35 的测试逮到的），第二次是地形（`NpcProbe` 的离线那一段
     * 逮到的——矮人眼里的整片地都写着"缺"）。**两次都不是逻辑错，是调用点少写了一行。** 所以这里把
     * 四样捆在一起，新写的调用点用这一份就不会漏。
     *
     * 第五样是**发言的顺序**（2026-09-26 加的）：开局只有矮人一个会说话的角色，它排在队首。空名单
     * 也能跑（没排过队的排在最后），但那样"谁先开口"就不可读了——开局这一份该把人排好。
     *
     * [GameSession] 不用它，因为它那四个参数是分开的（测试要能把其中一样换掉）。
     */
    fun snapshot(): WorldSnapshot = WorldSnapshot(
        entities = tavern(),
        rulebook = rules(),
        terrain = ground,
        zoning = zoning(),
        speakQueue = listOf(DWARF_ID),
    )

    /** 开局那个矮人的 id。它现在出现在几处（发言名单、探针、测试），所以给它一个名字。 */
    const val DWARF_ID: String = "dwarf"

    /**
     * 开局那张酒馆。
     *
     * **坐标是厘米**（附录 A.2，2026-09-24 改的）。高度不再是"第几层"，而是真实的高度：桌面在地面
     * 之上 80 厘米，所以酒杯就在 `GROUND + 80`。于是「在桌上」从一个编号变成了一个**几何事实**
     * ——这正是当初要厘米的理由（附录 A.2）。
     */
    fun tavern(): List<Entity> = listOf(
        Entity(
            id = "tavern_hall",
            kind = "地点",
            label = "酒馆大厅",
            x = 1000, y = 0, z = GROUND,
            attributes = mapOf("地面" to "夯土", "光照" to "几盏油灯"),
            background = """
                这里是「醉猫」酒馆，边境小镇灰渡唯一还在营业的酒馆，木石结构，建成约二十年。
                眼下是深秋傍晚，屋里点了四盏油灯，客人比往常少。
            """.trimIndent(),
        ),
        Entity(
            id = "oak_table",
            kind = "家具",
            label = "木桌",
            // 桌面 80 厘米高、两米长一米宽——这几句在有了形状之后会变成体素表，现在先只有位置。
            x = 1200, y = 400, z = GROUND,
            attributes = mapOf("材质" to "橡木", "状态" to "完好"),
            shape = Shapes.table(200, 100, 80, topCm = 10, legCm = 10),
            // 一条**具体到这张桌子上**的规则。它默认优先级 30，比世界规则（10）高——这就是"越具体
            // 越优先"落到实处的地方：世界规则说木头会烧，这张桌子的规则可以推翻它。
            rulebook = Rulebook(
                listOf(RuleEntry("这张桌子是橡木打的，结实，但怕火。", values = mapOf("硬度" to "6"))),
            ),
        ),
        Entity(
            id = "wine_cup",
            kind = "器具",
            label = "酒杯",
            // 站在桌面上：80 厘米高。
            x = 1200, y = 400, z = GROUND + 80,
            attributes = mapOf("材质" to "深色木制", "内容物" to "半杯麦酒"),
            shape = Shapes.box(10, 10, 15),
        ),
        Entity(
            id = "oil_lamp",
            kind = "器具",
            label = "油灯",
            x = 1200, y = 500, z = GROUND + 80,
            attributes = mapOf("材质" to "铁与玻璃", "状态" to "点着"),
            shape = Shapes.box(15, 15, 25),
        ),
        Entity(
            id = "hearth",
            kind = "家具",
            label = "壁炉",
            x = 1000, y = 0, z = GROUND,
            attributes = mapOf("状态" to "烧着"),
            shape = Shapes.box(150, 60, 120),
        ),
        Entity(
            id = "cellar_door",
            kind = "门窗",
            label = "地窖门",
            x = 1300, y = 100, z = GROUND,
            attributes = mapOf("状态" to "关着"),
            shape = Shapes.box(80, 5, 100),
        ),
        // **开局世界里唯一的另一个活人**（§14.36）。它是**氛围 NPC** 的样板：§3.2 说这类角色无人
        // 值守时"根本不是 AI"——代码查状态循环就知道它在干什么，零模型调用；只有被搭话时才唤醒。
        //
        // **它的状态循环与待办（§14.37）** 是这个样板真正的骨架：`cycle` 是作息（代码查表，不花钱），
        // `todos` 是打算（进提示词，到点提醒它）。两样都不影响"被指向才唤醒、唤醒之后走同一套判定"
        // 那条路——循环管的是"没人理它时它在干什么"，而被唤醒时那件事一定已经被接管了。
        Entity(
            id = "dwarf",
            kind = "角色",
            label = "酒馆里的矮人",
            // 离玩家两米出头：**正好在空间框里，而且伸手够不着**——于是"跟他说话"成立、
            // "把杯子递给他"不成立，两条路都能验到。
            x = 1000, y = 200, z = GROUND,
            attributes = mapOf("两手" to "捧着自己那只锡杯", "神色" to "半醉"),
            shape = Shapes.box(45, 30, 120),
            // 作息写成四条，**跨夜那一格是刻意的**：23:00 打烊之后到次日 07:00 开门之前，人还在
            // "打烊"那一格里。循环的读法是**相对时间**（从它开始那一刻起算，§14.44）：这几格从 07:00
            // 写到 23:00，而 `periodMinutes` **显式给成一天（1440）**——于是 23:00 那一格管到次日 07:00
            // 开门之前，而这一圈也正好是一天。
            //
            // **每一格还带着坐标**（§14.44）：那是它平时这个点该在哪儿，代码到点了把它挪过去
            // （`World.refreshCyclesToClock`）。18:00 那一格的坐标**刻意就是它现在站的这个位置**
            // ——开局那一瞬正好落在这一格里，于是新加的刷新不会在开局把谁挪一下。
            cycle = Cycle(
                beats = listOf(
                    Beat(7 * 60, "在后厨生火、把杯子一只只擦干净，等第一个客人进门。", 900, 320, GROUND),
                    Beat(12 * 60, "坐在柜台后面吃自己那份午饭，谁也不理。", 1150, 120, GROUND),
                    Beat(18 * 60, "站在柜台后面招呼客人，手里那块布一直没停过。", 1000, 200, GROUND),
                    Beat(23 * 60, "灭灯、闩门、把椅子倒扣在桌上。", 1100, 260, GROUND),
                ),
                // 一天一圈。**不写这一项时默认的一圈是"最后一格的时刻 + 1"（23 小时零 1 分）**，
                // 那会把"23:00 到次日 07:00"那一段算成"打烊"，而把起床提前到 15:00——所以这一格是
                // 内容，不是可省的默认值。
                periodOverride = 24 * 60,
            ),
            // **开局就有一条到点的待办**，这是有意的：到点提醒那条路不装一条真待办就永远验不到。
            // 它是一件小事、而且不需要什么目标，所以不会把一场对话搅成一次行动。
            todos = listOf(
                Todo(
                    at = WorldTime.of(day = 1, hour = 17, minute = 30),
                    what = "后厨那扇窗子关不严，夜里风大，得找块木头把缝塞上。",
                ),
            ),
            cards = listOf(
                CharacterCard(
                    "身份",
                    "你叫杜林，在灰渡镇待了很多年的矮人，替「醉猫」的老板看场子换酒喝。" +
                        "镇上的人你都认得，外面来的人你一眼就看得出来。",
                ),
                CharacterCard(
                    "性格",
                    "话不多，问三句答一句。不熟的人搭话，你先看他一眼，再决定理不理。" +
                        "不主动惹事，但也吃不得亏。",
                ),
                CharacterCard("交互范围", "你伸得着大约一格以内的东西——一臂之遥。够不到就得先站起来。"),
                // 这一张的标题**刻意选了一个只在它自己身上出现的词**：「口癖」不出现在任何一段提示词
                // 的正文里（`NpcTest` 拿它验"标题不进提示词"这条规则）。用「身份」验不了——那个词
                // 恰好在角色那份 system 的正文里，断言会假报。
                CharacterCard("口癖", "说到钱的时候你会哼一声，说完还要摇摇头。"),
            ),
        ),
        // **矮人手里那只杯子也是一样东西。**
        //
        // 这一条是联机验收逼出来的（§14.36）：它的「两手」属性原来写着"捧着自己那只杯子"，而世界里
        // **没有那只杯子**。于是它想呷一口时，判定对着一份没有它的清单，驳回了「这一片里没有哪件
        // 东西是「我两手捧着的那只杯子」」——判定是对的，错的是世界里少了一样东西。
        //
        // 与玩家那把斧子、那块抹布是同一条：**身上带着的东西也是实体**，属性里提到的东西必须在
        // 世界里找得到。§1 那句"地图就是事实库"落到这一层就是这个意思——**说得出名字的，得在图上**。
        Entity(
            id = "dwarf_cup",
            kind = "器具",
            label = "矮人的锡杯",
            // 与玩家那把斧子同一个高度：手里。
            x = 1000, y = 200, z = GROUND + 90,
            attributes = mapOf("材质" to "锡", "内容物" to "小半杯麦酒"),
            shape = Shapes.box(10, 10, 15),
        ),
        Entity(
            id = PLAYER,
            kind = "角色",
            label = "你",
            x = 1200, y = 300, z = GROUND,
            attributes = mapOf("两手" to "空着", "腰间" to "挂着一把短柄斧"),
            shape = Shapes.box(40, 25, 170),
            // 人设卡（§3.5）。**「交互范围」这一张就是「够不够得着」那条线的来源**——距离在这里
            // 已经是格了（写卡那一步换算，基准 1 格 = 1 米，附录 A.12），判定只读，不做算术。
            // 没有这一张卡的故事就不给这一行，由模型按情境判（A.6）。
            cards = listOf(
                CharacterCard("身份", "你是走南闯北的过路人，身上没什么钱，惯用一把短柄斧。"),
                CharacterCard("交互范围", "你伸得着大约一格以内的东西——一臂之遥。够不到就得先走过去。"),
            ),
        ),
        // **身上带着的东西也是实体。** 腰间那把斧子与那块抹布跟玩家是同一个 x、y，只是高 90 厘米
        // ——所以"把手里的东西放下"就是一次 `Move`，不需要为"随身物品"另开一套数据结构（§3.1）。
        // 以前这里靠 `parent` 表达归属，那个字段已经删了：**位置就是坐标**（附录 A.5）。
        Entity(
            id = "axe",
            kind = "器具",
            label = "短柄斧",
            // 腰间：90 厘米。
            x = 1200, y = 300, z = GROUND + 90,
            attributes = mapOf("状态" to "刃口有些卷"),
            shape = Shapes.box(30, 5, 10),
        ),
        Entity(
            id = "rag",
            kind = "器具",
            label = "抹布",
            x = 1200, y = 300, z = GROUND + 90,
            attributes = mapOf("状态" to "半干"),
            shape = Shapes.box(25, 25, 5),
        ),
    )
}
