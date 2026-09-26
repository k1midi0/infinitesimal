package io.github.k1midi0.infinitesimal.schedule

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ReasoningEffort
import io.github.k1midi0.infinitesimal.config.SystemId
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.llm.AssistantTurn
import io.github.k1midi0.infinitesimal.llm.ChatMessage
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.llm.PropertySchema
import io.github.k1midi0.infinitesimal.llm.ThinkingRequest
import io.github.k1midi0.infinitesimal.llm.ToolDefinition
import io.github.k1midi0.infinitesimal.llm.decodeCall
import io.github.k1midi0.infinitesimal.llm.text
import io.github.k1midi0.infinitesimal.llm.tool
import io.github.k1midi0.infinitesimal.spatial.RegionBuilder
import io.github.k1midi0.infinitesimal.spatial.RegionView
import io.github.k1midi0.infinitesimal.world.Beat
import io.github.k1midi0.infinitesimal.world.CycleCatchUp
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.refreshCyclesToClock
import io.github.k1midi0.infinitesimal.world.rendered
import io.github.k1midi0.infinitesimal.world.whyStuck
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable

/**
 * **到点就位**这件事的两个实现方都长这样（§14.45）。
 *
 * 会话只认这一条线，于是测试可以塞一个"所有人都进得去"的进去，而不必联网、也不必让那一轮里凭空
 * 多出几次调用：`catchUpCycles` 的调用点现在挂在每一轮开头，任何一支会话测试都绕不过它。
 */
fun interface Scheduling {
    suspend fun catchUp(world: World): CycleCatchUp
}

/**
 * **到点就位**：先跑 [World.refreshCyclesToClock]（免费的那一半），再把剩下的交给哨兵问一句。
 *
 * 与 [io.github.k1midi0.infinitesimal.director.Director] 同一层：**一个模型段**。它自己把请求落到
 * [SystemId.SENTRY] 那个缓存池（[SentryJudge] 那儿的构造器），所以调用方传进来的还是原配置。
 *
 * ## 为什么地形过了还要问一句（§14.45）
 *
 * 因为地形只说得清"那一格还在不在"。而"这一格说的这件事现在还做得了吗"是语义问题：酒馆成了火海、
 * 那间屋子塌了、店已经关了——这些都是**世界里有东西变了，地形却没变**。这一条正是用户要的那一条
 * （原话：酒馆已经因为战斗变成火海）。
 *
 * ## 一个问题一次调用，不重试
 *
 * 与判定那两套不同，这里**没有重试**：要的是一个"行 / 不行"，而调用失败时（端点、解析）**当"不行"**
 * ——那会把一个人送进"卡住"那条路，而那条路的代价是导演多改一次循环。反过来当"行"的话，一个进不去
 * 的人会每一轮都被重新问一次，症状是钱在烧而世界一动不动。
 */
class Scheduler(private val sentry: SentryJudge) : Scheduling {

    constructor(config: EndpointConfig) : this(SentryJudge(config))

    /** 问了几次。**只用来记账**：它是这一刀唯一新增的模型调用，而"一轮问几次"必须看得见。 */
    var requestCount = 0
        private set

    /**
     * 跑一轮：谁到点了、谁没去成。
     *
     * 每个需要挪过去的人**已经过了地形**（免费，拦下就没问），这里再问一句哨兵。被判"进不去"的人
     * 留在原地（位置一格不动）——"把它放到那一格的坐标上"是会话那一层的事，因为变活跃的是它，而
     * 活跃的落点就是那一格（§14.45）。
     */
    override suspend fun catchUp(world: World): CycleCatchUp {
        val settled = world.refreshCyclesToClock().map { placement ->
            if (placement.reason != null) return@map placement
            val entity = world.byId(placement.id) ?: return@map placement
            val verdict = check(world, entity, placement.beat)
            if (verdict.possible) {
                placement
            } else {
                placement.copy(
                    reason = whyStuck(placement.id, placement.beat, verdict.reason),
                    moved = false,
                )
            }
        }
        return CycleCatchUp(settled)
    }

    /** 问一句，问不成当"不行"（理由写在类头上）。 */
    private suspend fun check(world: World, entity: Entity, beat: Beat): SentryJudge.Verdict {
        requestCount++
        return try {
            sentry.check(world, entity, beat)
        } catch (t: Throwable) {
            SentryJudge.Verdict(
                possible = false,
                reason = "没问出所以然来（${t.message ?: t::class.simpleName}），先按进不去算。",
            )
        }
    }
}

/**
 * **哨兵**：一次调用，问"这一格说的那件事，现在这个世界还做得了吗"（§14.45）。
 *
 * 它**没有任何写权限**：只有一只报结果的手，而结果怎么用由 [Scheduler] 决定。这与判定那一条
 * （模型只能调工具提议，落库一律经 `Committer`）是同一种形状。
 *
 * 思考**显式关掉**（§14.12：请求里必须明说，不能靠默认）。这一条要的是一个分类结果而不是推理
 * ——判定那两步之所以开着思考，是因为它们要做物理推理；"这一格现在能不能进"读一眼就知道。
 */
class SentryJudge(
    private val client: LlmClient,
    private val effort: ReasoningEffort = ReasoningEffort.LOW,
) {

    constructor(config: EndpointConfig) : this(LlmClient(config.copy(userId = SystemId.SENTRY)))

    /** 这一次问出来的东西。[reason] 在 [possible] 为真时是"你为什么觉得它做得了"。 */
    data class Verdict(val possible: Boolean, val reason: String)

    /**
     * 问一句。
     *
     * [Verdict.reason] 在判"不行"时**就是要交给导演的那句话**（所以它得是一句这个世界在说的话）；
     * 判"行"时只进日志与读数，不喂给任何模型。
     */
    suspend fun check(world: World, entity: Entity, beat: Beat): Verdict {
        val turn = AssistantTurn.of(
            client.streamChat(
                messages = listOf(
                    ChatMessage.system(SYSTEM),
                    ChatMessage.user(prompt(world, entity, beat)),
                ),
                tools = listOf(REPORT),
                thinking = ThinkingRequest.of(ThinkingMode.Never, effort),
                maxTokens = null,
            ).toList(),
        )

        val call = turn.calls.firstOrNull { it.name == REPORT_NAME }
            ?: return Verdict(
                possible = false,
                reason = "哨兵没给答复（${turn.diagnosis()}），先按进不去算。",
            )
        val args = decodeCall(SentryArgs.serializer(), call)
            ?: return Verdict(false, "哨兵的答复读不出来：${call.arguments.take(160)}")

        return Verdict(possible = args.possible, reason = args.reason.trim().ifEmpty { "它没给理由" })
    }

    private fun prompt(world: World, entity: Entity, beat: Beat): String = buildString {
        val persona = entity.cards.rendered()
        appendLine("【这个人是谁】")
        appendLine("${entity.label}（id 是「${entity.id}」）")
        if (persona.isNotBlank()) {
            appendLine(persona)
            appendLine()
        }
        appendLine("【它这一刻本来该去做的】")
        appendLine("「${beat.state}」")
        appendLine(
            "地点在 (${beat.x}, ${beat.y}, ${beat.z}) 厘米——" +
                "它此刻在 (${entity.x}, ${entity.y}, ${entity.z})。",
        )
        appendLine()
        appendLine("【那地方现在是什么样】")
        appendLine(RegionView.render(world, RegionBuilder.of(world, listOf(entity.id)), entity.id))
    }

    private companion object {

        const val REPORT_NAME = "report"

        @Serializable
        data class SentryArgs(val possible: Boolean = false, val reason: String = "")

        /**
         * 那只唯一的手。**两个参数都必填**：只给一个布尔值、不给理由的话，交给导演的那句话就得由
         * 代码编，而代码编不出"为什么"（§1.5 第三条：驳回理由不撒谎）。
         */
        val REPORT: ToolDefinition = tool(
            REPORT_NAME,
            "报结果。每一次都要调它——你只有这一只手，不调它就没有人知道你看到了什么。",
            "possible" to PropertySchema("boolean", "true = 这一格现在做得了；false = 做不了"),
            text(
                "reason",
                "一句话说清为什么。做不了时这一句会**原样交给导演**，" +
                    "所以要写成这个世界在说话，说清是什么挡住了它。",
            ),
        )

        /**
         * 哨兵读的那一段。
         *
         * 判据只能有**一条**，但这一条要问得足够具体，否则它会去答一个更容易的问题。第二遍读数
         * （§14.45）逮到的正是这个：酒馆大厅已经烧了小半个钟头、柜台塌了一半，它却说"只有一根烧断
         * 的屋梁在别处，挡不住招呼客人"——因为它把"做得了吗"读成了"有没有东西挡路"。
         *
         * 所以这里把它要答的问题拆成三问，每一问都要求**指着清单里的东西说话**：落点站不站得住、
         * 这件事要用的那几样还在不在、那地方此刻这状态还容不容得下这件事。
         *
         * 而**"看不出来就是做得了"**那条仍然要在：不问它的哨兵会把"这一格没什么特别的"读成"我说
         * 不出它能做"，于是每一格都变成卡住，导演每轮都被叫起来。
         */
        val SYSTEM: String = """
            你是这个世界里的一个看门人。有人按它自己的作息走到了一个地方，而你要判的只有一件事：

            **这一格说的那件事，现在这个世界还做得了吗。**

            你手上有三样东西：这个人的卡（它是谁）、这一格要做的那件事、以及**它眼前那一片清单**。
            清单是发起的这一刻的。判的时候问自己三句：

            1. **那地方站得住吗**——它的落点是空的，还是已经被填死、埋了、塌了、被水淹了？
            2. **这件事要用的东西还在吗**——要站在柜台后面招呼客人，柜台还在不在？要生火，灶还在不在？
               **要看那几样东西自己的状态**：写着"塌了一半""烧得只剩黑壳"的，就是不能用了。
            3. **那地方此刻这状态，还容得下这件事吗**——已经成了火海、浓烟压着屋顶、水漫到腰上，
               这时候说"照常招呼客人"就说不通了。

            三句都过得去，就是做得了。**有哪一句过不去，就报做不了**，理由写成一句这个世界在说的话，
            并且**指着清单里那个东西说**（例如：柜台已经塌了一半，横着的木料把那一块堵死了）。

            反过来，规矩也只有一条，而它同样要紧：

            **看不出来有什么不对，就是做得了。**

            远处有一根烧断的屋梁、地上横着点杂物，只要**不影响这一件事**，就是做得了——不要因为
            看着乱就报做不了。报了做不了，就会有一个人被从它的作息上拽下来，还会有人被叫来处理
            一件其实没发生的事。

            清单里没有的东西就是此刻不在这里。**这一片看不到那个地点时（人在半路上、或者那地方太
            远），报做得了**，不要拿没看见当那里出事了。

            每一次都调那只 report 手。做不了时理由不要写成系统提示。
        """.trimIndent()
    }
}
