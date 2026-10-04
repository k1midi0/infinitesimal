package io.github.k1midi0.infinitesimal.spike

import io.github.k1midi0.infinitesimal.config.ContextSettings
import io.github.k1midi0.infinitesimal.session.GameSession
import io.github.k1midi0.infinitesimal.session.LogLine
import io.github.k1midi0.infinitesimal.npc.Npc
import io.github.k1midi0.infinitesimal.world.Active
import io.github.k1midi0.infinitesimal.world.live
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
 *   -PinfinitesimalLiveKeyFile="<密钥文件>"
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

    /**
     * **把阈值调小，让压缩真的发生**（§6.1、§14.41）。
     *
     * 默认的 400K 离得很远——§14.40 量出来要约 232 轮才碰得到，所以"压一次、再压一次"这条路在联机里
     * 根本跑不到。这一支探针要验的因此是那个设计决定本身：**阈值可配**，以及配小之后那一整套真的走通
     * ——超阈→提示词里出现"该收一收了"→模型调 `compact`→历史变短→再超阈→出第 2 级。
     */
    @Test
    fun aTightThresholdActuallyFires() {
        val key = ProbeSupport.keyOrNull() ?: run {
            println("（没有 key，跳过）")
            return
        }

        runBlocking {
            val config = ProbeSupport.configOf(key)
            // 六千：按 §14.40 那条曲线，第三轮就该越过去。
            val npc = Npc(config, settings = ContextSettings(compressAfterTokens = 6_000))
            val session = GameSession(config, npc = npc)

            println("═".repeat(100))
            println("阈值调成 6000，看压缩会不会真的发生")
            println("═".repeat(100))

            // **八轮，不是四轮。** 第一次跑只跑了四轮，结果压缩一次都没发生——不是因为阈值没越过，而是
            // 因为**建议范围还有个下限**（`MIN_RANGE_MESSAGES = 6`）：历史到第 4 轮才 11 段，刚够给出
            // 建议。两个条件（超阈 ∧ 有可压的一段）**都**要成立才会提那件事，而它们不是同时到的。
            script.forEachIndexed { i, said ->
                    val before = session.requestCount
                    val logged = session.log.size
                    session.runTurn(said, "")

                    val conv = npc.conversations.of("dwarf")
                    println()
                    println("── 第 ${i + 1} 轮 ── 调用 ${session.requestCount - before} 次｜" +
                        "prompt_tokens ${npc.lastUsage?.promptTokens}｜" +
                        "历史 ${conv.size} 段（原文 ${conv.rawCount}、摘要 ${conv.summaryCount}）")
                    // **级别要印出来**：第 2 级出现过没有，是"摘要自己再超阈就再压"那句话唯一能验的地方。
                    conv.summaries().forEach {
                        println("     摘要 ${it.blockId}：第 ${it.tier} 级，盖 h${it.pos}–h${it.coveredThrough}（${it.covered} 条原文）")
                    }
                    session.log.drop(logged).forEach { println("  ${it.kind}｜${it.text}") }
                }

            val conv = npc.conversations.of("dwarf")
            println()
            println("═".repeat(100))
            println("最后：历史 ${conv.size} 段｜原文 ${conv.rawCount}｜摘要 ${conv.summaryCount}")
            println("═".repeat(100))
        }
    }

    /**
     * 十句话，一句一轮。**它不是 [script] 的加长版，而是另一场**：这一场要看的是压缩真的发生的那几轮里，
     * 它会不会把要紧的事写进笔记（§6.3、§6.42）。
     *
     * 语序是有意排的：
     *
     *  - 第 2 轮那句「阿黛尔答应周一还我三枚银币」是**一句约定**——正是笔记该记的东西。
     *  - 第 3、5、6、7 轮是催促与它自己报出的两个数（三枚、后天中午）——**这些细节一旦被压掉就没了**。
     *  - 第 10 轮回头问第 2 轮那件事，看它靠什么接住（摘要、笔记，还是两样都不够）。
     */
    private val noteScript = listOf(
        "你好啊。",
        "阿黛尔答应周一还我三枚银币，你替我记着。",
        "那笔钱她到底还了没有？",
        "这酒怎么这么淡。",
        "我是说，阿黛尔欠我的那三枚银币。",
        "她说明天还是后天来着？",
        "后天中午，在哪儿碰头来着？",
        "算了，你先把酒给我倒上。",
        "镇上最近还有什么别的事？",
        "再问一次：阿黛尔那三枚银币的事，你还记得多少？",
    )

    /**
     * **笔记真的会发生吗，以及它会不会先记住再压掉**（§6.3、§14.42）。
     *
     * 与 `aTightThresholdActuallyFires` 同一场阈值 6000 的设置，量的是这一刀那两件事：
     *
     *  1. **它自己看得出什么值得记**——不是我们告诉它"这一句要记"，而是它在一场普通的对话里判断出
     *     「阿黛尔答应周一还三枚银币」这件事将来还有用。
     *  2. **笔记与压缩各干各的**——压了多少轮，笔记那一节都在；而它超了上限会自己撤掉旧的。
     *
     * 跑法（要真 key）：
     *
     * ```
     * .\gradlew.bat :shared:jvmTest --tests "*ConversationProbe*" --rerun `
     *   -PinfinitesimalLiveKeyFile="<密钥文件>"
     * ```
     */
    @Test
    fun notesAndCompactionShareTheSameConversation() {
        val key = ProbeSupport.keyOrNull() ?: run {
            println("（没有 key，跳过）")
            return
        }

        runBlocking {
            val config = ProbeSupport.configOf(key)
            val npc = Npc(config, settings = ContextSettings(compressAfterTokens = 6_000))
            val session = GameSession(config, npc = npc)

            println("═".repeat(100))
            println("阈值 6000｜笔记与压缩同场｜看它会不会先记下要紧的事再压")
            println("═".repeat(100))

            noteScript.forEachIndexed { i, said ->
                val before = session.requestCount
                val logged = session.log.size
                session.runTurn(said, "")

                val conv = npc.conversations.of("dwarf")
                val notes = session.world.byId("dwarf")?.notes.orEmpty()
                println()
                println("── 第 ${i + 1} 轮 ── 调用 ${session.requestCount - before} 次｜" +
                    "prompt_tokens ${npc.lastUsage?.promptTokens}｜" +
                    "历史 ${conv.size} 段（原文 ${conv.rawCount}、摘要 ${conv.summaryCount}）｜" +
                    "笔记 ${notes.live().size}/${notes.size} 条")

                // **它答了什么。** 这一支探针现在要看的另一格是"它写下的笔记这一轮就看得见"（§14.43 那个
                // 修掉的 bug）：所以答话本身也是读数——下一轮它若照着笔记说话，就是那条链活的证据。
                session.log.drop(logged).forEach {
                    if (it.kind == LogLine.Kind.SAY) println("     ${it.text}")
                }

                // **它自己写的那些**：这一支探针真正要看的读数。空着也是一个读数——说明它没觉得有什么
                // 值得记，或者提示词里那条规矩不够硬。
                notes.forEach { n ->
                    val mark = if (n.supersedes == null) "新" else "替 ${n.supersedes}"
                    println("     笔记 ${n.id}（$mark）${if (n.tag.isEmpty()) "" else "[${n.tag}]"}：${n.text}")
                }
                conv.summaries().forEach {
                    println("     摘要 ${it.blockId}：第 ${it.tier} 级，盖 h${it.pos}–h${it.coveredThrough}（${it.covered} 条原文）")
                }
            }

            val conv = npc.conversations.of("dwarf")
            val notes = session.world.byId("dwarf")?.notes.orEmpty()
            println()
            println("═".repeat(100))
            println("最后：历史 ${conv.size} 段｜原文 ${conv.rawCount}｜摘要 ${conv.summaryCount}（最高 " +
                "${conv.summaries().maxOfOrNull { it.tier } ?: 0} 级）")
            println("笔记：活 ${notes.live().size} 条、共 ${notes.size} 条（被替代 ${notes.size - notes.live().size} 条）")
            println("═".repeat(100))
        }
    }

    /**
     * **脱轨那一趟：它会不会自己回家**（§3.2.1、§14.43）。
     *
     * 这一支要看的不是"它会不会写笔记"（上一支量过了），而是**它认不认得出"这一趟结束了"**——以及
     * 它在不打算回家的时候，会不会把自己按在那一格里出不来。而 `arrive` 是**有代码把关**的：
     * 它得落在目标那一格上才放行（`Active.canArrive`），所以这一支同时也在量"它会不会点错格"。
     *
     * 与上一支的差别只有一处：**开局前把矮人标成脱轨**——目标取它此刻本来就在的那一格（18:00）。
     * 于是回归随时可成，而"它想不想回去"就完全落在它自己身上。
     *
     * 跑法同上（`-PinfinitesimalLiveKeyFile=...`）。
     */
    @Test
    fun anOffCycleNpcMayGoHomeOnItsOwn() {
        val key = ProbeSupport.keyOrNull() ?: run {
            println("（没有 key，跳过）")
            return
        }

        runBlocking {
            val config = ProbeSupport.configOf(key)
            val npc = Npc(config, settings = ContextSettings(compressAfterTokens = 6_000))
            val session = GameSession(config, npc = npc)

            // **标它脱轨。** 目标写它此刻本来就在的那一格，于是"回家"这件事随时成立——要不要回，
            // 完全是它自己的判断。这一刀里标状态的是导演或玩家（§14.43 决定之一）。
            val dwarf = session.world.byId("dwarf")
            if (dwarf != null) {
                session.world.put(
                    dwarf.copy(
                        active = Active(
                            since = session.world.clock,
                            target = dwarf.cycle.beatAt(session.world.clock),
                        ),
                    ),
                )
            }

            println("═".repeat(100))
            println("脱轨着的矮人｜它会不会自己回家（目标：${dwarf?.cycle?.beatAt(session.world.clock)?.state}）")
            println("═".repeat(100))

            listOf(
                "你好啊。",
                "这酒怎么这么淡？",
                "算了，你忙你的吧。",
                "我先走了。",
                "（我还站在这儿。）",
            ).forEachIndexed { i, said ->
                val logged = session.log.size
                session.runTurn(said, "")

                val now = session.world.byId("dwarf")
                println()
                println("── 第 ${i + 1} 轮 ── 玩家说：「$said」")
                session.log.drop(logged).forEach { println("     ${it.text}") }
                println("     ［活跃=${now?.active != null}］")
                println(
                    "     ［它此刻那一格：${now?.cycle?.beatAt(session.world.clock)?.atMinute}" +
                        "（${now?.cycle?.beatAt(session.world.clock)?.state}）｜" +
                        "目标是：${now?.active?.target?.atMinute ?: "—"}］",
                )
            }

            println()
            println("═".repeat(100))
            println("最后：活跃=${session.world.byId("dwarf")?.active != null}｜笔记 " +
                "${session.world.byId("dwarf")?.notes.orEmpty().size} 条")
            println("要读的那一格：**它自己说了要回去吗；以及它点的那一格对不对**")
            println("═".repeat(100))
        }
    }
}
