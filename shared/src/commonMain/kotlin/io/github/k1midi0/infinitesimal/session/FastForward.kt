package io.github.k1midi0.infinitesimal.session

import io.github.k1midi0.infinitesimal.world.TimelineEntry
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldTime

/**
 * 快进的结果。
 */
data class Stop(
    /** 停在哪一刻。 */
    val at: WorldTime,
    /** 这一路上到期的那些，按时刻排序。**它们已经从世界的时间轴上取走了。** */
    val happened: List<TimelineEntry>,
    /** 是不是**因为有事才停的**。`false` = 一路无事，走到了目标时刻。 */
    val interrupted: Boolean,
)

/**
 * 快进：把世界时间往前推，**在最早到期的那一刻停下**（§8.1）。
 *
 * ## 为什么必须有它
 *
 * §8.1 那句话是这一整套东西的地基：
 *
 * > **没有时间轴就必然漏事**——你睡觉时邻居家着火，是没有任何定时器能预告的。
 *
 * 玩家说"我睡到天亮"，如果世界只是一张当下的快照，那一夜之间什么都不可能发生——**不是"没有安排"，
 * 而是根本无从判断该不该有**。能算出来的唯一前提，是每一件将来的事都有一个到期时刻。
 *
 * ## 为什么它属于会话层
 *
 * §11.2 把"回合推进、时间快进"放在 `session/`，而把时钟与时间轴放在 `world/`。这条分界是"状态"与
 * "推进"的分界：**时间是世界的事实**（和"桌上有一把斧子"同类，必须存下来、只有一个答案），而**往前
 * 走是会话的动作**。放在 `world/` 会让那一层开始有"接下来做什么"，而它现在只回答"现在是什么"。
 *
 * ## 它不叫模型
 *
 * §8.2 把触发分成两类，性质完全不同：**时间轴到期由代码检测，确定性、可复现**；剧情跑偏才要快速
 * 模型，而且它只能**发现**、不能改。所以这里一次模型调用都没有——"到点了该发生什么"是查表。
 * 要不要因为这件事改剧本，是导演的事，在这个函数之外。
 */
object FastForward {

    /**
     * 从现在起，最早会因为到期而必须停下的时刻。没有就是 `null`。
     *
     * 界面用它来回答"还有没有可等的"。**没有的时候不要凭空往后推**——见 [runToNext]。
     */
    fun nextStop(world: World): WorldTime? = world.timeline.nextDueFrom(world.clock)

    /**
     * 快进到 [target]。
     *
     * **只停一次。** 假设这一路上第一件事在 t1 到期、第二件在 t2 到期，那么快进到 t2 只停在 **t1**——
     * 停下来意味着"该有人看一眼了"，而看过之后时间不会自己继续走。一次调用里连停两次，等于替调用方
     * 决定"没人看也接着走"。
     *
     * [target] 早于或等于现在时：**时间不往前**，但此刻正好到期的事仍然会被取走（时间只往前，而
     * "现在已经到点了"这件事不会因为没往前推就不发生）。
     */
    fun run(world: World, target: WorldTime): Stop {
        val from = world.clock
        if (target <= from) {
            return Stop(at = from, happened = world.timeline.takeDueBy(from), interrupted = false)
        }

        val next = world.timeline.nextDueFrom(from)
        val at = if (next != null && next < target) next else target
        world.clock = at
        return Stop(
            at = at,
            happened = world.timeline.takeDueBy(at),
            interrupted = at < target,
        )
    }

    /**
     * 快进到**下一件到期的事**。
     *
     * 时间轴上没有可等的事时返回 `null`，**且一点时间都不推进**。不推进是有意的：没有下一刻可等的
     * 时候，"等一下"这个动作没有意义——凭空往前推一小时，会静默地制造出一段谁都没看见的时间。
     */
    fun runToNext(world: World): Stop? {
        val next = nextStop(world) ?: return null
        return run(world, next)
    }
}
