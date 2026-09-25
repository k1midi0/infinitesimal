package io.github.k1midi0.infinitesimal.spike

import io.github.k1midi0.infinitesimal.session.GameSession
import io.github.k1midi0.infinitesimal.npc.Npc
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * **一场真正的多轮对话**（§6.1、§14.39）。
 *
 * `NpcProbe` 那五场每一场都是**一句话、一个新世界**，所以它验不到"记得住"这件事。这一支把同一场对话
 * 连着跑八轮，量的是刀一那三样：
 *
 *  1. **历史真的在累积**：每一轮它带在身上的消息条数只增不减。
 *  2. **真数在涨**：每一轮那一格的 `usage.prompt_tokens` 是**提供方说的**，不是我们估的——它就是压缩
 *     判据要用的那个锚（§6.1）。这一栏一列出来，"400K 离得有多远"就不是猜的了。
 *  3. **总线在换**：每一轮它看见的「这一片里刚刚发生的事」应当是**这一回合**的，不是累积的。
 *
 * 跑法（要真 key）：
 *
 * ```
 * .\gradlew.bat :shared:jvmTest --tests "*ConversationProbe*" --rerun `
 *   -PinfinitesimalLiveKeyFile="C:\Users\20758\Desktop\测试key.txt"
 * ```
 *
 * stdout 从 `shared/build/test-results/jvmTest/TEST-*ConversationProbe*.xml` 的 `system-out` 读。
 * **它不是断言型的测试**，是打印读数用的——没有 key 时直接跳过，不报失败。
 */
class ConversationProbe {

    /**
     * 八句，一句一轮。语序是有意排的：
     *
     *  - 第 3 轮问它"我刚才说了什么"——**要它用上累积的历史**，而上一刀它靠的是记录层铺成的窗口。
     *  - 第 4 轮问它的名字（人设里有，不该靠历史），作对照。
     *  - 第 6、7 轮问**玩家从没说过**的事——答"不知道"才对，有历史不等于该编。
     *  - 第 8 轮回到第 2 轮那个话头，看它接不接得上。
     */
    private val script = listOf(
        "你好啊。",
        "这酒怎么这么淡？",
        "我刚才说这酒怎么了，你还记得吧？",
        "你叫什么名字？",
        "算了，给我讲讲镇上最近的事。",
        "我说过我叫什么吗？",
        "那我姓什么，你记得吗？",
        "接着刚才那句——酒淡这事，你打算怎么办？",
    )

    @Test
    fun aLongConversationPrintsWhatItRemembers() {
        val key = ProbeSupport.keyOrNull() ?: run {
            println("（没有 key，跳过——这一支探针要真端点才跑得起来）")
            return
        }

        runBlocking {
            val config = ProbeSupport.configOf(key)
            // **注入一份自己拿着的 `Npc`**，这样探针读得到它的历史。默认那一份在会话内部，外面看不见。
            val npc = Npc(config)
            val session = GameSession(config, npc = npc)

            println("═".repeat(100))
            println("一场八轮的对话｜历史累积中")
            println("═".repeat(100))

            val perTurn = mutableListOf<Triple<Int, Int, Int>>() // 轮次、调用数、prompt token
            script.forEachIndexed { i, said ->
                val before = session.requestCount
                val logged = session.log.size

                println()
                println("── 第 ${i + 1} 轮 ──────────────────────────────────────────────────────────────────")
                println("  玩家说：「$said」")

                session.runTurn(said, "")

                val calls = session.requestCount - before
                val prompt = npc.lastUsage?.promptTokens ?: 0
                perTurn += Triple(i + 1, calls, prompt)

                session.log.drop(logged).forEach { println("  ${it.kind}｜${it.text}") }

                val conv = npc.conversations.of("dwarf")
                println("  ── 这一轮花了 $calls 次调用｜它带在身上的消息 ${conv.size} 条")
                println("  ── 这一次请求的 prompt_tokens（提供方说的）：$prompt")
                println("  ── 这一回合的总线（${session.world.log.since(session.turnStart).size} 条，起点 e${session.turnStart}）")
                session.world.log.since(session.turnStart).forEach {
                    println("     ${it.ref}｜${it.title()}")
                }
            }

            println()
            println("═".repeat(100))
            println("合计：${perTurn.sumOf { it.second }} 次调用")
            println("逐轮：调用数 ${perTurn.joinToString("、") { "${it.first}=${it.second}" }}")
            println("逐轮：prompt_tokens ${perTurn.joinToString("、") { "${it.first}=${it.third}" }}")
            println("世界里还有 ${session.world.all().size} 样东西｜记录层 ${session.world.log.size} 条")
            println("═".repeat(100))
        }
    }
}
