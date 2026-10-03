package io.github.k1midi0.infinitesimal.director

import io.github.k1midi0.infinitesimal.world.Script
import io.github.k1midi0.infinitesimal.world.ScriptNote
import io.github.k1midi0.infinitesimal.world.ScriptState
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldTime

/**
 * **一次"写一份剧本"的提议**（§8.4、§14.54）。
 *
 * 语义是**整张替换那一份**（id 对得上就把标题/摘要/正文/状态全换掉，**批注保留**），id 对不上就是
 * 新添一份。与 [CycleWrite] / [CharacterWrite] / [CardWrite] 同形：一次写一份，整批校验、整批提交。
 *
 * **正文也在这里**——它不进提示词，所以它的大小不是"每次唤醒的代价"，而是"打开看一次"的代价。
 */
data class ScriptWrite(
    val id: String,
    val title: String,
    val summary: String,
    val body: String,
    val state: ScriptState,
)

/**
 * **剧本池那几条规矩**（§8.4、§14.54）。
 *
 * 与 [CardPlanner] 同形：`problems` 只判不碰世界，`apply` 只在校验通过之后被调用。工具那一层因此能在
 * **真正落库之前**知道成败（§1.5 第三条）。
 *
 * ## 为什么上限卡在"份数 × 摘要"
 *
 * §8.4 定的是「池子里每份只露**标题 + 摘要 + 状态**；**正文要打开才看**」——所以常驻提示词的是
 * **份数 × 摘要**，而正文只在 `look_script` 打开时读一次。于是上限也照这一条分：
 * [MAX_SUMMARY_CHARS] 是"每次唤醒都付"的那个数，[MAX_BODY_CHARS] 只是"别让它写一本书"的保险丝。
 *
 * ## 没有一条是"这条剧情好不好"
 *
 * 那是导演自己的活。代码判的只有：写全了没有、写不写得下。
 */
object ScriptPlanner {

    /** 一局里最多几条线。 */
    const val MAX_SCRIPTS: Int = 8

    /**
     * 摘要最多多少字。
     *
     * **这是真正要卡的那个数**：每条线的摘要都进每一次唤醒的清单，所以 8 条 × 200 字 = 1600 字常驻。
     * 200 字的来处：它要装下"这条钩子是什么、玩家该撞上什么"——比一张卡的摘要长，比正文短一个数量级。
     */
    const val MAX_SUMMARY_CHARS: Int = 200

    /**
     * 正文最多多少字。**它不进提示词**，所以这条是保险丝而不是预算：拦的是"模型一次吐出一篇中篇小说"。
     */
    const val MAX_BODY_CHARS: Int = 2_000

    /**
     * 一份剧本最多留多少条批注。
     *
     * 到顶之后**没有出路**（批注是只追加的，删不了）——所以理由是"这条线该完结了，或者该开一条新的"。
     * 而提示词里**只给最近 [NOTES_IN_PROMPT] 条**，所以条数不等于每次唤醒的代价。
     */
    const val MAX_SCRIPT_NOTES: Int = 50

    /** 清单里每份剧本各带几条批注（只给最近这几条）。 */
    const val NOTES_IN_PROMPT: Int = 3

    fun problems(
        world: World,
        writes: List<ScriptWrite>,
        maxScripts: Int = MAX_SCRIPTS,
        maxSummaryChars: Int = MAX_SUMMARY_CHARS,
        maxBodyChars: Int = MAX_BODY_CHARS,
    ): List<String> = buildList {
        val seen = mutableSetOf<String>()
        for (write in writes) {
            val id = write.id.trim()
            if (id.isEmpty()) {
                add("有一条剧本没写 id。给它起一个代号——以后改它、给它写批注都要用它。")
                continue
            }
            if (!seen.add(id)) {
                add("同一批里「$id」写了两次——一份剧本一次只写一遍。")
                continue
            }
            if (write.title.isBlank()) {
                add("「$id」没有标题。清单里只露标题、摘要与状态，没有标题就认不出它是哪一条。")
                continue
            }
            if (write.summary.isBlank()) {
                add("「$id」没有摘要。**摘要才是常驻提示词的那一段**——没有它，这条线在你看得见的那份清单里就是一个空壳。")
                continue
            }
            if (write.body.isBlank()) {
                add("「$id」没有正文。正文是这条剧情本身（用自然语言写），要打开才看的那一份。")
                continue
            }
            if (write.summary.length > maxSummaryChars) {
                add(
                    "「${write.title}」的摘要太长了（${write.summary.length} 字，最多 $maxSummaryChars）" +
                        "——**每一条的摘要都进你每一次开工读的那份清单**，所以它是「这一条线是什么」的一句话，" +
                        "而不是正文的缩写。正文写进 body 里。",
                )
            }
            if (write.body.length > maxBodyChars) {
                add("「${write.title}」的正文太长了（${write.body.length} 字，最多 $maxBodyChars）。")
            }
            // **新增才受份数限制**：改写已有的那一条永远可以（那正是"线太多"的出路之一）。
            val existing = world.scripts.any { it.id == id }
            if (!existing && world.scripts.size >= maxScripts) {
                add(
                    "池子里已经有 $maxScripts 条线了，添不下新的。出路有两条：把已经**完结**的那几条" +
                        "留在池子里（它们不占新的位置——这一条限制只管新增），或者**改写现有的某一条**" +
                        "（id 写它现在那个，就是把那一条换掉）。现在这些是：" +
                        world.scripts.joinToString("、") { "${it.id}〔${it.state.label}〕" },
                )
            }
        }
    }

    /**
     * 落库。**只在校验通过之后被调用**。
     *
     * 两条语义：**id 对得上的整张替换**（标题/摘要/正文/状态全换，而**批注保留**——§8.4 那条"批注与
     * 正文分开存"在这里落成事实），**对不上的追加到末尾**（批注从空开始）。
     */
    fun apply(world: World, writes: List<ScriptWrite>) {
        val out = world.scripts.toMutableList()
        for (write in writes) {
            val id = write.id.trim()
            val at = out.indexOfFirst { it.id == id }
            val fresh = Script(
                id = id,
                title = write.title.trim(),
                summary = write.summary.trim(),
                body = write.body.trim(),
                state = write.state,
                notes = if (at >= 0) out[at].notes else emptyList(),
            )
            if (at >= 0) out[at] = fresh else out += fresh
        }
        world.scripts = out
    }

    // ── 批注（§8.4：只追加、永不改写） ────────────────────────────────────────────────

    /**
     * 往一份剧本上写一条批注。**这是快速档唯一的写权限**（§8.5、§1.4：快速档只能追加，只有思考档能
     * 改写）。
     *
     * 门：id 空 / 世界里没有这一份 / 正文空 / 太长 / **批注满了**（满了没有删除这条路，所以理由是
     * "这条线该完结了，或者该开一条新的"）。
     */
    fun noteProblems(world: World, id: String, text: String, maxChars: Int = NoteText.MAX): List<String> = buildList {
        val script = world.scripts.firstOrNull { it.id == id.trim() }
        when {
            id.isBlank() -> add("没说是哪一份剧本。id 填你那份清单里的代号。")
            script == null -> add(
                "池子里没有 id 是「$id」的剧本。现在这些是：" +
                    (if (world.scripts.isEmpty()) "（一条都没有）" else world.scripts.joinToString("、") { it.id }),
            )
            text.isBlank() -> add("这条批注是空的。要记的是「这一轮对它发生了什么」，一句话。")
            text.length > maxChars -> add("这条批注太长了（${text.length} 字，最多 $maxChars）——批注是流水上的一行，不是又一份正文。")
            script.notes.size >= MAX_SCRIPT_NOTES -> add(
                "「${script.title}」的批注已经记满 $MAX_SCRIPT_NOTES 条了。批注是只追加的、删不了，" +
                    "所以一条线记到这里就该**完结**它、或者**开一条新的**。",
            )
        }
    }

    /** 落库：**只追加**（§8.4）。时刻由代码取，不由模型写。 */
    fun annotate(world: World, id: String, text: String, at: WorldTime) {
        val out = world.scripts.toMutableList()
        val idx = out.indexOfFirst { it.id == id.trim() }
        if (idx < 0) return
        out[idx] = out[idx].copy(notes = out[idx].notes + ScriptNote(at = at, text = text.trim()))
        world.scripts = out
    }
}

/** 批注那一侧的字数上限（与一条笔记同一个数：一句话，不是一段日记）。 */
private object NoteText {
    const val MAX: Int = 200
}
