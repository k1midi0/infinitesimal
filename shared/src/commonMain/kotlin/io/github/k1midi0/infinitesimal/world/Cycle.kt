package io.github.k1midi0.infinitesimal.world

import kotlinx.serialization.Serializable

/**
 * 状态循环上的一格：**从这一天里的第几分钟起，我在做什么**（§3.2、§3.2.5）。
 *
 * @property atMinute 一天里的第几分钟，`0..1439`。用「一天里的第几分钟」而不是「第几天几点」，是因为
 *   循环是**周期**的——"23:00 打烊、次日 07:00 又开门"是同一格与下一格的关系，不是两条要各写一遍的
 *   记录。
 */
@Serializable
data class Beat(val atMinute: Int, val state: String)

/**
 * **状态循环**：一张周期表（时刻 → 状态）。
 *
 * ## 它回答什么
 *
 * 「**没人理它的时候，它此刻在干什么**」。这一句话是它的全部职责。
 *
 * ## 它给谁读——这一条决定了整个世界能装多少个氛围 NPC
 *
 * **给代码读，零模型调用。** 这是「氛围 NPC 不是便宜的 AI，而是平时根本不是 AI」那条设计（§3.2）
 * 的全部来源：一个在循环里的角色不装提示词、不花钱、不占上下文，而它的"此刻在干什么"仍然有一个
 * 确定的答案——代码查表就有。成百个这样的角色，成本零增量。
 *
 * 代价是：**代码查出来的只是基线**。被唤醒之后模型接管，接管产生的是**覆盖层**——存下来的事实。
 * 世界状态 = 基线 + 覆盖（§3.2）。
 *
 * ## 两条它**不包括**的东西
 *
 *  - **位置不在循环里**（2026-09-26 定）。它**待在被放下、或被挪到的地方**——那是一个存下来的值。
 *    原设计那句"位置由时间轴上的日程算出"因此作废，理由是硬碰硬的一条：它要求循环知道地点，而
 *    "在哪儿"是判定随时会改的事实，两者迟早会打架（§3.2.5）。
 *  - **一件打算做的事也不在循环里**，那是 [Todo]。分界线是**消费者不同**：循环由代码查，待办要进
 *    提示词。所以循环可以有成千条，待办只能属于少数几个角色。
 *
 * ## 谁写它
 *
 * 生成时由**导演**写（§8.3）。**角色自己改不了它**——「明天去修铠甲」是它自己的一件打算（那是一条
 * [Todo]），而把循环交到它手里，等于让它改自己的作息。
 */
@Serializable
data class Cycle(val beats: List<Beat> = emptyList()) {

    val isEmpty: Boolean get() = beats.isEmpty()

    /**
     * 此刻按循环本该在做什么。**没有循环就是没有答案**（`null`），不是"空闲"——那是两件事。
     *
     * 取「不晚于此刻的最后一格」。**此刻早于当天第一格时取当天最后一格**：那是跨夜那一格的正确读法
     * （23:00 打烊之后到次日 07:00 开门之前，人还在"打烊"那一格里），而不是"今天还没开始"。
     */
    fun stateAt(now: WorldTime): String? {
        if (beats.isEmpty()) return null
        val minute = now.minuteOfDay
        return (beats.lastOrNull { it.atMinute <= minute } ?: beats.last()).state
    }

    /** 按时刻排好。**存进去时不必有序**——模型写出来大概率是无序的，排序是代码的事。 */
    fun sorted(): Cycle = Cycle(beats.sortedBy { it.atMinute })

    /**
     * 这份循环自己立不立得住。
     *
     * 与 `Terrain.problems`、`ShapeLimits.problems` 同类：**这是代码该守的那条线**（结构合法），而
     * "这个角色该不该这个点吃饭"是内容，代码不管（A.6）。一条坏循环不该被静默接受——它会让
     * [stateAt] 给出一个看起来正常、其实是错的答案。
     */
    fun problems(): List<String> = buildList {
        beats.forEachIndexed { i, beat ->
            if (beat.atMinute !in 0 until WorldTime.MINUTES_PER_DAY) {
                add("第 ${i + 1} 格的时刻 ${beat.atMinute} 不在一天之内（0..${WorldTime.MINUTES_PER_DAY - 1}）")
            }
            if (beat.state.isBlank()) add("第 ${i + 1} 格的状态是空的")
        }
        beats.groupBy { it.atMinute }.filterValues { it.size > 1 }.keys.sorted().forEach {
            add("有不止一格落在同一分钟（$it）——那一刻它到底在做什么没有答案")
        }
    }
}
