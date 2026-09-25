package io.github.k1midi0.infinitesimal.spike

import io.github.k1midi0.infinitesimal.npc.NPC_MEMORY_WINDOW
import io.github.k1midi0.infinitesimal.session.GameSession
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * **一场真正的多轮对话**（§12 第 3 项、第 43 项、第 44 项）。
 *
 * `NpcProbe` 里的五场每一场都是**一句话、一个新世界**，所以它验不到"记得住"这件事——而
 * §14.37 刚给角色装上记忆。这一支探针把同一场对话连着跑八轮，一次量三件未完的账：
 *
 *  1. **窗口 N**（第 3 项）：它现在被拍成 [NPC_MEMORY_WINDOW] 条。跑过那个数之后会怎样？§6.1 对这条
 *     的原话是「观察 NPC 在多少轮之后开始重复或自相矛盾」——所以第 5 到第 8 轮是真正要看的地方，
 *     那时最早那几轮已经被挤出窗口了。
 *  2. **空动作**（第 43 项）：`Npc.SYSTEM` 第 7 条加了「不要为了做而做，不要为了说而说」。省了几次
 *     调用，一轮里看不出来，八轮里看得出来。
 *  3. **记忆到底有没有用**：第三轮问它"你还记得我刚才说了什么"，而**第六轮问一个它不可能知道的事**
 *     （玩家没说过自己姓什么）——那一问是照妖镜：有记忆不等于该编，**答"不知道"才是对的**。
 *
 * 跑法（要真 key）：
 *
 * ```
 * .\gradlew.bat :shared:jvmTest --tests "*ConversationProbe*" --rerun `
 *   -PinfinitesimalLiveKeyFile="C:\Users\20758\Desktop\测试key.txt"
 * ```
 *
 * stdout 从 `shared/build/test-results/jvmTest/TEST-*ConversationProbe*.xml` 的 `system-out` 读。
 * **它不是断言型的测试**，是打印读数用的——所以没有 key 时它直接跳过，不报失败。
 */
class ConversationProbe {

    /**
     * 八句，一句一轮。**语序是有意排的**：
     *
     *  - 1–2 轮是开场与一个具体的话头（"这酒淡"）——第 3 轮要问的就是它。
     *  - 4 轮问一件人设卡里的事（它叫杜林），那是**不该靠记忆**的一问，用作对照。
     *  - 5–6 轮时窗口已经装过 [NPC_MEMORY_WINDOW] 条，前两轮开始被挤出去。
     *  - 7 轮问一个**玩家从没说过**的事——答"不知道"才对。
     *  - 8 轮再回到第 2 轮那个话头，看它接不接得上。
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
            val session = GameSession(ProbeSupport.configOf(key))

            println("═".repeat(100))
            println("一场八轮的对话｜记忆窗口 $NPC_MEMORY_WINDOW 条")
            println("═".repeat(100))

            val perTurn = mutableListOf<Pair<Int, Int>>()
            script.forEachIndexed { i, said ->
                val before = session.requestCount
                val logged = session.log.size

                println()
                println("── 第 ${i + 1} 轮 ──────────────────────────────────────────────────────────────────")
                println("  玩家说：「$said」")

                session.runTurn(said, "")

                val calls = session.requestCount - before
                perTurn += (i + 1) to calls

                session.log.drop(logged).forEach { println("  ${it.kind}｜${it.text}") }

                val window = session.world.log.windowOf("dwarf", NPC_MEMORY_WINDOW)
                println("  ── 它看得见 ${window.size} 条：")
                window.forEach { println("     · ${it.line().replace("\n", " / ")}") }
                println("  ── 这一轮花了 $calls 次调用")
            }

            println()
            println("═".repeat(100))
            println("合计：${perTurn.sumOf { it.second }} 次调用｜${perTurn.joinToString("、") { "第${it.first}轮=${it.second}" }}")
            println("世界里还有 ${session.world.all().size} 样东西｜记录层 ${session.world.log.size} 条")
            println("═".repeat(100))
        }
    }
}
