package io.github.k1midi0.infinitesimal.world

/**
 * **一个人的一格真的"到点"了**：谁、哪一格、以及有没有被拦下来（§14.45）。
 *
 * [reason] 为 `null` = **这一格进得去**。[reason] 非空 = **没去成**，那段话就是交给导演的理由（谁来叫
 * 导演、怎么叫，是会话那一层的事）。判定分两半——**地形那一半在这里**（零模型调用），**"这一格现在
 * 做得了吗"那一半在 `schedule/Scheduler.kt`**。
 *
 * [moved] 单独一栏而不是"reason 为空就等于挪了"：它可能**本来就站在那一格上**，而那与"这一轮要把它
 * 挪过去"对玩家是两件事。
 *
 * ## 刷新**自己不写位置**（§14.45 的实现形状）
 *
 * [refreshCyclesToClock] 只**报**谁该去哪，动手的是会话那一层。这不是分层洁癖：一次到点可能同时有
 * 几个人，而"谁放行、谁卡住"必须**整批一起生效**——写一半再判一半的话，一个刚被判"进不去"的人已经
 * 被挪到了那一格上，而它本该留在原地（这一刀第一版就是这么错的，被 `SchedulerTest` 逮住：断言"没去成
 * 就不挪"实得要 `900`）。
 */
data class Placement(val id: String, val beat: Beat, val reason: String?, val moved: Boolean)

/**
 * 一轮"到点就位"的结果。
 *
 * [stuck] 就是"到了点却没进那一格"的那些人——**他们正是这一刀的主角**：他们要变活跃，而导演要被叫
 * 起来改循环。它是空的是常态。
 */
data class CycleCatchUp(val placements: List<Placement> = emptyList()) {
    /** 该挪过去的人（[Placement.moved] 为真才是"它不在那儿、要挪"）。 */
    val moved: List<Placement> get() = placements.filter { it.reason == null && it.moved }

    /** 进得去、而且不用挪的人（它本来就站在那一格上）。 */
    val settled: List<Placement> get() = placements.filter { it.reason == null }

    val stuck: List<Placement> get() = placements.filter { it.reason != null }
}

/** 一次"过不了"的理由，格式统一成这一句——它要同时给会话那一行日志与导演用。 */
fun whyStuck(id: String, beat: Beat, why: String): String =
    "「$id」到了「${beat.state}」（循环里相对时间 ${beat.atMinute} 那一格）该在的地方 " +
        "(${beat.x}, ${beat.y}, ${beat.z})，但$why"

/**
 * **它此刻能不能站在这一格的坐标上**（§14.45）。
 *
 * ## 一处只有这个世界的人才会写对的地方
 *
 * `z` 是**脚底**的坐标，而开局那片地一直铺到 [WorldDepth.surface]，所以"站在地上"的那些人脚下那一格
 * **本来就填着土**。于是"那一格有材质就是被埋了"是错的——它会拦住世界上的每一个人（这一刀第一次跑
 * 全量时正是这么栽的，症状是坐标恒在原地而没有任何一处报错）。
 *
 * 正确的读法是两条：
 *
 *  1. **那一格是空的** → 站得住（挖出来的坑、屋子里的空气、平台上方都走这条路）。
 *  2. **那一格填着东西，但它正好踩在世界地面上** → 也站得住：那是**地基**，是这个世界里"地面"的
 *     常态（土的坐标就是 `surfaceCm`，而角色就站在那儿）。
 *
 * 落在这两条之外（站进一堵填实的墙里、站进漫过脚面的水里、站到一片被堆起来的东西中间）就是
 * **站不住**——那是导演该来处理的那一类。
 *
 * ## 为什么"没有地形"不算过不了
 *
 * **这一列整列没被写过**（还没长出来的地方，`runsAt` 是空的）就是"我们还不知道"，与"知道那里被填了"
 * 是两件事（[refreshCyclesToClock] 对"没有循环"的那条注释是同一条理由）。不知道就**不拦**，照旧把
 * 它挪过去——生成器与导演会补上那一片，而把人都冻在原地等地图，症状比挪过去难查得多。
 *
 * 这一条同时是"空世界"的读法：测试里那些只放几个实体的世界不会因为"没有地形"而谁都不动。
 */
fun World.canStandAt(beat: Beat): Boolean {
    val here = TerrainCell(terrainCellOf(beat.x), terrainCellOf(beat.y), terrainCellOf(beat.z))
    if (terrain.runsAt(here.x, here.y).isEmpty()) return true

    val below = terrain.materialIdAt(TerrainCell(here.x, here.y, here.z - 1)) != null
    val standingOnTheBase = here.z == terrain.depth.surface && below
    return terrain.materialIdAt(here) == null || standingOnTheBase
}

/** 那一格底下是什么材质——用来把"站不住"写成一句导演看得懂的话。查不到时返回 null。 */
fun World.materialUnder(beat: Beat): String? {
    val z = terrainCellOf(beat.z)
    return terrain.runsAt(terrainCellOf(beat.x), terrainCellOf(beat.y))
        .lastOrNull { it.covers(z - 1) }
        ?.material
}

/**
 * **到点了：把还在走循环的人挪到它此刻该在的那一格上**（§14.44、§14.45）。
 *
 * ## 它只做地形那一半
 *
 * 这里问得出来的只有"站得住吗"——地面被挖空、那一格被填实、被水漫过去，这三件事都落在地形上。而
 * "**酒馆已经成了火海**"那一种**问不出来**：火是一个实体，不是地形。所以这一层只把地形拦下的那一半
 * 判掉（免费），剩下的交给 `schedule/Scheduler.kt`（一次调用）。这个分界是刻意的：**免费的判据先跑**，
 * 贵的那一条只在还有意义的时候跑。
 *
 * ## 三条不算
 *
 *  - **`active != null` 的人跳过。** 他此刻没在走循环（§3.2.1），正被别的什么牵着；把他拉回作息上
 *    等于让一个被揍了的人下一刻又站在柜台后面。
 *  - **没有循环的人跳过。** `beatAt == null` 是"没有答案"，不是"该在 (0,0,0)"。
 *  - **不是角色的东西跳过。** [Entity.cycle] 的注释写着只有角色会写它，而桌上的杯子恰好也可以挂着
 *    一张循环表（字段对所有实体开放）。
 *
 * ## 还有一条跳过：**已经站在那一格上的人**
 *
 * 这一条是这一刀的成本闸。没有它，一个在循环里的人**每一轮**都会被问一次"这一格还做得了吗"，而它
 * 其实一整格都没动过地方——那是几百轮乘以几十个人的调用。有了它，"到点这一下"只在**它需要挪过去**
 * 的时候发生：正常走动的人一轮最多一次，而且只在跨过一格的那一轮。
 *
 * ## 调用点是"每轮开始"与"时钟往前推之后"
 *
 * **不是"到点那一瞬间"**：快进会跳过时刻，没有任何代码在那一瞬看着，所以正确的做法是每次推进之后
 * 对一遍。
 *
 * ## 它**不写位置**，也不写记录（§14.44、§14.45）
 *
 * 这一遍只**报**：谁该去哪、谁进不去。真正把坐标写下去的是会话那一层——理由写在 [Placement] 的
 * 注释里（整批一起生效）。
 *
 * 而"挪过去"这件事**不留记录**：它只是一次位置写入，与判定改位置时留下的东西不是一回事。要让玩家
 * 看见它走过来，那是把移动事件化，另一件活。
 */
fun World.refreshCyclesToClock(): List<Placement> = buildList {
    for (entity in all()) {
        if (entity.active != null) continue
        if (entity.kind != CHARACTER_KIND) continue
        val beat = entity.cycle.beatAt(clock) ?: continue
        // **站得正好的人跳过**，而不是"站得差不多的人跳过"：差一两米也算没就位，因为"就位"是**站到那一格
        // 的坐标上**这件事本身。用容差（`nearEnough`，两米）在这里会静默吃掉一次就位：导演刚写完一份
        // 作息，而那个人恰好站在离新坐标一米半的地方——于是它不会过去，而所有读数看起来都对。
        // 容差只属于另一件事：**它自己宣告回归**时够不够近（`Active.canGo`）。
        if (entity.x == beat.x && entity.y == beat.y && entity.z == beat.z) continue

        if (!canStandAt(beat)) {
            val material = materialUnder(beat)?.let { materials.name(it) }
            add(
                Placement(
                    id = entity.id,
                    beat = beat,
                    reason = "那一格现在站不住——" +
                        (material?.let { "那里填着「$it」" } ?: "那一格的地面没了") +
                        "（z 从 ${terrainCellOf(beat.z) - 1} 到 ${terrainCellOf(beat.z)} 那两层）。",
                    moved = false,
                ),
            )
            continue
        }

        val already = entity.x == beat.x && entity.y == beat.y && entity.z == beat.z
        add(Placement(id = entity.id, beat = beat, reason = null, moved = !already))
    }
}.sortedBy { it.id }
