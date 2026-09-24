package io.github.k1midi0.infinitesimal.director

import io.github.k1midi0.infinitesimal.llm.ToolDefinition
import io.github.k1midi0.infinitesimal.llm.int
import io.github.k1midi0.infinitesimal.llm.text
import io.github.k1midi0.infinitesimal.llm.tool
import kotlinx.serialization.Serializable

/**
 * 导演交给模型的那几只"手"（§8）。
 *
 * 现在只有一只。这是有意的：§1.3 把导演的权限写得很死——**导演只能往时间轴和剧本里放意图，不能直接
 * 改数值**。所以这一层的手，每一只都必须落在"将来要发生什么"上，而不是"现在把它改成什么"。
 * 改世界是判定的活。
 *
 * 参数刻意保持扁平（只有 string / integer），与判定那边同一个理由：移植过来的 `JsonSchema` 不支持
 * 嵌套对象与数组。
 */
object DirectorTools {

    /**
     * 往时间轴里放一件事。
     *
     * ## 为什么时间是"第几天 + 几点几分"而不是"多少分钟之后"
     *
     * 因为导演想事情的单位就是那个。它要说的是"第 3 天夜里，北门有一支队伍经过"（§1.3 的原话），
     * 不是"2592 分钟之后"。让代码去猜"夜里"是几点，就等于让代码替它下一半的判断；而让它自己把
     * "两个钟头之后"换算成钟点，才是它做得了、也看得出对错的一件事——**现在的时刻就印在它要读的
     * 那一段里**。
     *
     * ## 为什么没有"谁牵涉其中"这个参数
     *
     * [io.github.k1midi0.infinitesimal.world.TimelineEntry.actors] 那个字段现在没有任何东西在读它。
     * 加一个模型要填、而没人消费的参数，结果只是让它每次多写一句话，而且那句话会随着没有反馈而漂移。
     * **等日程与 NPC 进来（§3.2）再加。**
     */
    const val SCHEDULE = "schedule"

    val ALL: List<ToolDefinition> = listOf(
        tool(
            SCHEDULE,
            "往时间轴里放一件事：**到了那个时刻，就要发生什么**。一件事调一次。" +
                "它会告诉你排到了什么时候，然后你接着排下一件。" +
                "到点了才会有人来处理它——**你排的是一条将来要发生的事，不是现在就发生**。",
            text(
                "id",
                "这件事的代号，你自己起，看得出是什么就行（例如 矮人搬酒）。" +
                    "**同一个 id 再排一次等于改主意，不是多一件事**——一批里不要用同一个 id。",
            ),
            int("day", "第几天，从 1 起。现在是第几天，看上面【现在】那一行"),
            int("hour", "几点，0 到 23"),
            int("minute", "几分，0 到 59"),
            text("what", "一句话：到点了要发生什么"),
        ),
    )
}

/** [DirectorTools.SCHEDULE] 的参数。**全部有默认值**，所以缺字段只会退化成一次看得懂的失败。 */
@Serializable
internal data class ScheduleArgs(
    val id: String = "",
    val day: Int = 0,
    val hour: Int = 0,
    val minute: Int = 0,
    val what: String = "",
)
