package io.github.k1midi0.infinitesimal.director

import io.github.k1midi0.infinitesimal.world.TimelineEntry
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldTime

/** 排时间轴的结果。 */
sealed interface Planned {

    /** 排上了，已经落到时间轴上。 */
    data class Done(val entries: List<TimelineEntry>) : Planned

    /** 一条都没落。**整批退回**，不是"能落的落、不能落的丢掉"。 */
    data class Rejected(val problems: List<String>) : Planned
}

/**
 * 把导演排出的那批事**一次性校验、一次性提交**（§10.2）。
 *
 * ## 为什么整批退，不做部分提交
 *
 * 因为导演排的是一张表，而表里各条是有关系的（"先起火、后被人发现"）。落一半进去，剩下那一半还得
 * 让它重排一次——而它重排时会看到一张已经变了一半的表，于是它排出来的第二批会和第一批对不上。
 * **一次提交要么是完整的计划，要么什么都没发生**，这一条与 [io.github.k1midi0.infinitesimal.judge.Committer]
 * 是同一条理由。
 *
 * ## 代码只管结构，不管情节
 *
 * 这里校验的四件事全都是**读得出来的**：id 有没有、话有没有说、时刻是不是合法的钟点、时刻是不是
 * 已经过去了。**没有一件是"这件事合不合理"**——那是导演自己的活，而代码去判它就得读懂那句话，
 * 于是就会开始静默地判错（A.6、A.7 同一条纪律）。
 */
object TimelinePlanner {

    /**
     * 这批事排不下的理由。空列表 = 全都能排。
     *
     * 一次报全，不是报第一条就停——模型拿着完整的一张问题清单修一轮就够了，报一条要来回好几趟。
     */
    fun problems(entries: List<TimelineEntry>, now: WorldTime): List<String> = buildList {
        // 一批里同一个 id 出现两次：几乎一定是笔误，而它坏的方式是**静默吞掉一件事**
        // （`put` 是同 id 覆盖）。§8.1 那条"必然漏事"最典型的一种就是这里漏的。
        val repeated = entries.groupBy { it.id }.filterValues { it.size > 1 }.keys
        for (id in repeated) {
            add("「$id」在这一批里排了不止一次。同一个 id 是「改主意」，会盖掉前面那条——想排两件事就起两个 id。")
        }

        for (entry in entries) {
            if (entry.id.isBlank()) {
                add("有一条事没给 id。")
                continue
            }
            if (entry.what.isBlank()) {
                add("「${entry.id}」没说要发生什么。")
            }
            if (entry.dueAt < now) {
                // 这条最要紧：**已经过去的时刻排不出事**。它不是"排早了一点"，而是那件事永远不会
                // 发生——快进只往前走，而它已经在身后了。
                add("「${entry.id}」排在 ${entry.dueAt}，那个时刻已经过去了（现在是 $now）。")
            }
        }
    }

    /** 校验通过就全部落进时间轴；有一条不通过就一条都不落。 */
    fun apply(world: World, entries: List<TimelineEntry>): Planned {
        val problems = problems(entries, world.clock)
        if (problems.isNotEmpty()) return Planned.Rejected(problems)
        for (entry in entries) world.timeline.put(entry)
        return Planned.Done(entries)
    }
}
