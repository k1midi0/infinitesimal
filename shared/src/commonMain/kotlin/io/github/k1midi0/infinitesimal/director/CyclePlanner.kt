package io.github.k1midi0.infinitesimal.director

import io.github.k1midi0.infinitesimal.world.Active
import io.github.k1midi0.infinitesimal.world.Beat
import io.github.k1midi0.infinitesimal.world.CHARACTER_KIND
import io.github.k1midi0.infinitesimal.world.Cycle
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldTime
import io.github.k1midi0.infinitesimal.world.refreshCyclesToClock

/**
 * 导演想写的一份作息：**给谁写、写成什么样**（§8.3、§14.44）。
 *
 * 它是一份**提议**，不是事实——校验与落库在 [CyclePlanner]，与判定那边的 [io.github.k1midi0.infinitesimal.judge.Consequence]
 * 同一个形状。`bornAt` **不在这里**：它由代码在提交那一刻取世界时钟（模型手里没有"现在几点"这个数，
 * 让它编一个只会错）。
 */
data class CycleWrite(
    val targetId: String,
    val beats: List<Beat>,
)

/** 写一份作息读回来的东西：**要么全是格，要么是一条读不懂的理由**（理由里带着那一行的原文）。 */
sealed interface CycleParse {

    data class Parsed(val beats: List<Beat>) : CycleParse

    data class Failed(val why: String) : CycleParse
}

/**
 * 把导演写的那段多行文本读成一份作息（§14.44）。
 *
 * 一行 = `相对分钟｜x,y,z｜这一格在做什么`，三段都必填：
 *
 * ```
 * 0｜900,320,12800｜在后厨生火、把杯子一只只擦干净。
 * 420｜1150,120,12800｜坐在柜台后面吃自己那份午饭，谁也不理。
 * ```
 *
 * ## 为什么是这种形状
 *
 * 与 `grow_terrain` 的 `placements` 同一条理由：**导演的手只能收字符串与整数**（端点那一层的
 * `JsonSchema` 不支持嵌套对象与数组），而一行一段的写法它写惯了、代码也读得回来。
 *
 * ## 读不懂就把那一行原样退回去
 *
 * 不静默丢、不跳过——§1.3：静默丢弃等于死循环。而"第几行读不懂、原文是什么"是它下一轮唯一修得动的
 * 信息。
 *
 * ## 一条反直觉的判据被留在外面
 *
 * "最后一格必须大于零""至少两格"这些是 [Cycle.problems] 的活，这里只管**读得出来读不出来**。
 * 两件事分开的理由很实在：读不懂是格式问题（它改一行就能过），立不住是结构问题（它得重想一遍），
 * 而把两种理由混在一句话里，它下一轮会去改错的那一头。
 */
object CycleText {

    /** ASCII 与全角两种竖线都认——模型两边都会写。 */
    private val separators = charArrayOf('|', '｜')

    fun parse(text: String): CycleParse {
        val beats = mutableListOf<Beat>()
        for ((i, raw) in text.lines().withIndex()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val parts = line.split(*separators).map { it.trim() }
            if (parts.size < 3) {
                return CycleParse.Failed(
                    "第 ${i + 1} 行只有 ${parts.size} 段：「$line」。一行要三段——" +
                        "相对分钟｜x,y,z｜这一格在做什么。",
                )
            }

            val atMinute = parts[0].removeSuffix("分钟").removeSuffix("分").trim().toIntOrNull()
                ?: return CycleParse.Failed(
                    "第 ${i + 1} 行的相对分钟读不出来：「${parts[0]}」。填一个整数——从循环开始那一刻" +
                        "数起的第几分钟（0 就是第一瞬间）。",
                )
            if (atMinute < 0) {
                return CycleParse.Failed("第 ${i + 1} 行的相对分钟是负数（$atMinute）。它从 0 起。")
            }

            val xyz = parts[1].removePrefix("(").removeSuffix(")").split(',', '，', ' ')
                .map { it.trim() }.filter { it.isNotEmpty() }
            if (xyz.size != 3) {
                return CycleParse.Failed(
                    "第 ${i + 1} 行没读到三个坐标：「${parts[1]}」。写成 x,y,z 三个整数（厘米）。",
                )
            }
            val coords = xyz.map { it.toIntOrNull() }
            if (coords.any { it == null }) {
                return CycleParse.Failed(
                    "第 ${i + 1} 行的坐标里有读不出来的数：「${parts[1]}」。三个都要是整数（厘米）。",
                )
            }

            val state = parts.drop(2).joinToString("｜").trim()
            if (state.isEmpty()) {
                return CycleParse.Failed("第 ${i + 1} 行没写它在做什么：「$line」。")
            }

            beats += Beat(atMinute = atMinute, state = state, x = coords[0]!!, y = coords[1]!!, z = coords[2]!!)
        }

        if (beats.isEmpty()) {
            return CycleParse.Failed("一段作息都没读到。一行一格，写成 相对分钟｜x,y,z｜这一格在做什么。")
        }
        return CycleParse.Parsed(beats)
    }

    /** 一格给模型看回去的写法（答复与理由里都用它，与它写进来的形状一致）。 */
    fun render(beat: Beat): String = "${beat.atMinute}｜${beat.x},${beat.y},${beat.z}｜${beat.state}"
}

/**
 * 把那批作息**一次性校验、一次性提交**（§10.2、§14.44）。
 *
 * ## 为什么整批校验、整批退
 *
 * 与 [TimelinePlanner] 一字不差：导演交的是一组改动，而它们之间可能是有关系的（"把早班与晚班都交给
 * 这两个人"）。写一半进去，剩下那一半还得让它重写一次——而它重写时会看到一半已经变了，于是第二批会
 * 与第一批对不上。**一次提交要么完整，要么什么都没发生。**
 *
 * ## 它校验什么
 *
 * 五条，全是**读得出来的**：
 *
 *  1. 目标 id 是空的；
 *  2. 世界里没有这个 id；
 *  3. 那东西不是角色——循环只管角色的作息（[Entity.cycle] 的注释）；
 *  4. **一模一样地给同一个人写两份**：几乎一定是笔误，而它坏的方式是静默吞掉一份（后写的盖掉先写的）；
 *  5. [Cycle.problems] 那几条（至少两格、时刻不重复、最后一格大于零）。
 *
 * **没有一条是"这个作息合不合常理"**——那是导演自己的活，而代码去判它就得读懂那句话，于是就会开始
 * 静默地判错（A.6、A.7 同一条纪律）。
 *
 * ## 校验与提交分成两个函数，而模型那一头只看得到"有没有过"
 *
 * [problems] 空 = 这一批能落。[apply] 相信这一点（`require`），因为它只在校验通过之后被调用。这样
 * 工具那一层可以在**真正落库之前**就知道成败，于是答复里能说清"世界动了没有"——而"看着像成了、其实
 * 没动"正是 §1.5 第三条要防的那种失败。
 */
object CyclePlanner {

    /** 这批作息有什么问题。空列表 = 全都能写。一次报全，不报第一条就停（理由同 [TimelinePlanner]）。 */
    fun problems(world: World, writes: List<CycleWrite>, visible: Set<String>): List<String> = buildList {
        val repeated = writes.groupBy { it.targetId }.filterValues { it.size > 1 }.keys
        for (id in repeated) {
            add("「$id」在这一批里写了不止一份。给同一个人写两份等于后写的盖掉先写的——想改就只写一份。")
        }

        for (write in writes) {
            val who = write.targetId.trim()
            if (who.isEmpty()) {
                add("有一份没说要写给谁。")
                continue
            }
            val target = world.byId(who)
            if (target == null) {
                add("世界里没有 id 是「$who」的东西，所以排不了它的作息。")
                continue
            }
            if (target.kind != CHARACTER_KIND) {
                add("「${target.label}」不是角色（它的 kind 是「${target.kind}」）。作息只管角色。")
                continue
            }
            if (who !in visible) {
                add(
                    "「${target.label}」不在你这一轮看得见的范围里——" +
                        "你眼前那段清单与那张地图上都没有它，所以你不知道它此刻在哪儿，" +
                        "写出来的坐标只能靠猜。先让它进到你能看见的地方，再排。",
                )
                continue
            }

            val cycle = Cycle(beats = write.beats)
            for (problem in cycle.problems()) add("「${target.label}」的作息：$problem")
        }
    }

    /**
     * 落进世界。**每个目标整张替换**（§14.44）：写来的是完整答案，旧的一格不留。
     *
     * `bornAt` 取**提交那一刻的世界时钟**——那是"这一份作息从这一刻开始跑"的字面意思。
     *
     * ## 写完立刻按当前相位就位（§14.44）
     *
     * 否则一个刚被写好的作息要等到下一个节点才生效，而"刚写好却不动"看着像没写进去。
     *
     * 这里**只挪位置、不判"那一格现在做得了吗"**（§14.45）：判据属于"到点"那条路（[Scheduler]），
     * 而这是"刚写下一份作息"——此刻**没有谁卡住**，只是相位落在了某一格上。刷新自己只报不写
     * （见 `Placement` 的注释），所以这一下由这里落。活跃期的人照旧跳过（刷新自己跳）。
     */
    fun apply(world: World, writes: List<CycleWrite>, bornAt: WorldTime) {
        for (write in writes) {
            val target = world.byId(write.targetId)
                ?: error("CyclePlanner.apply 收到了世界上没有的目标 ${write.targetId}")
            val fresh = Cycle(beats = write.beats, bornAt = bornAt)
            world.put(
                target.copy(
                    cycle = fresh,
                    active = target.active?.let { it.copy(target = liveTargetOf(it, fresh)) },
                ),
            )
        }

        for (placement in world.refreshCyclesToClock()) {
            if (!placement.moved) continue
            val who = world.byId(placement.id) ?: continue
            world.put(who.copy(x = placement.beat.x, y = placement.beat.y, z = placement.beat.z))
        }
    }

    /**
     * **旧的那个目标还作不作数**（§14.45）。
     *
     * [Active.target] 存的是"它脱轨时正要去的那一格"。整张换掉之后，那一格可能已经不在新的作息里
     * 了（导演正是为了这个才改的：那一格进不去）。这时**留着它是错的**——它会一直指向一个不存在的
     * 时刻，而"回到循环上"那条路永远等不到那个时刻。所以按 [Beat.atMinute] 对一下：新表里还有这一格
     * 就留着，没有就清零，让它在新的那一格上重新宣告一次回归。
     *
     * 比的是 [Beat.atMinute]，与 [io.github.k1midi0.infinitesimal.world.canArrive] 同一条理由
     * ——时刻是唯一不会撞的东西（两句不同的状态指向同一件事是常事）。
     */
    private fun liveTargetOf(active: Active, fresh: Cycle) =
        active.target?.takeIf { want -> fresh.beats.any { it.atMinute == want.atMinute } }

    // ── 归宿的另一半：升成剧情角色（§14.53、§3.2.1 的第 2 件事） ────────────────────────

    /**
     * **把它升成剧情角色**：丢掉它的作息（§14.53）。
     *
     * ## 为什么"升剧情"就是"清掉作息"
     *
     * 因为这个项目里**两档人是结构决定的**：有作息 = 氛围角色（代码按时钟挪它，平时零模型调用），
     * 没有作息 = 剧情角色（待在原地，被叫到才有人演它）。§14.46 放一个人出来时就是这么分的
     * （`beats` 空着 = 剧情角色），而这一只做的是**同一个判断的另一半：一个已经活着的人怎么变档**。
     * 用户 2026-09-28 就是这么定的（另外两个选项——加一个显式标记、或者"保留作息但暂停"——都被否了：
     * 那会多出一个字段、一种状态）。
     *
     * ## 为什么连 `active` 一起清掉
     *
     * 用户定的。而且不清的话会留一个**永远做不了的动作**：`Active.canGo` 要靠 `cycle.beatAt`，而空循环
     * 恒为 null——于是它每次醒来都会看到「做完了就用 `arrive` 回去」，而那只手对它**恒失败**。清掉
     * `active` 之后它就不再是"脱轨的人"，它就是"这种角色"。
     *
     * （`Schedule.longAbsent` 那一侧不用管：它的判据里有 `cycle.beatAt ?: beats.firstOrNull() ?: continue`，
     * 空循环的人本来就不会被报"脱轨太久"。）
     */
    fun dropProblems(world: World, id: String, visible: Set<String>): List<String> = buildList {
        val target = world.byId(id)
        when {
            id.isEmpty() -> add("没说要让谁变成剧情角色。id 填你眼前那段清单里的那个角色。")
            target == null -> add("世界里没有 id 是「$id」的东西。")
            target.kind != CHARACTER_KIND -> add("「${target.label}」不是角色（它是个${target.kind}）。")
            target.cycle.isEmpty -> add("「${target.label}」**本来就是剧情角色**——它没有作息，到点也不会被挪。")
            id !in visible -> add("「${target.label}」不在你这一轮看得见的范围里。")
        }
    }

    /**
     * 落库：**作息清空、活跃标记清掉**，位置不动（它此刻站在哪儿就留在哪儿——升剧情不是传送）。
     *
     * 只在校验通过之后被调用。
     */
    fun dropActiveCycle(world: World, id: String) {
        val target = world.byId(id) ?: return
        world.put(target.copy(cycle = Cycle(), active = null))
    }
}

/**
 * **这一轮它看得见的那些人**：焦点自己，加上身边那一片（[io.github.k1midi0.infinitesimal.spatial.RegionView]
 * 那一节用的同一个空间框）里的东西。
 *
 * 它决定导演能给谁写作息（§14.44 的一道门）：看不见的人，它不知道那人在哪、在做什么，写出来的坐标
 * 只能靠猜——而"编出来的坐标"这个坑这个项目已经踩过一次（§14.18 那盏油灯）。
 *
 * 判据**与那一节渲染用的是同一条**（`Region.covers`），所以"提示词里出现过"与"这里放行"永远一致。
 * 名单**不是**从渲染好的那段文本里反解出来的：那种做法会在排版一改就悄悄失效（§14.40 那个回合起点的
 * 差一是同一类）。
 */
fun visibleTo(world: World, focusId: String): Set<String> {
    val region = io.github.k1midi0.infinitesimal.spatial.RegionBuilder.of(world, listOf(focusId))
    return world.all().filter { region.covers(it) }.map { it.id }.toSet() + focusId
}
