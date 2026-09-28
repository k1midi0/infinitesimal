package io.github.k1midi0.infinitesimal.director

import io.github.k1midi0.infinitesimal.world.CHARACTER_KIND
import io.github.k1midi0.infinitesimal.world.CharacterCard
import io.github.k1midi0.infinitesimal.world.World

/**
 * **一次"改某个人的人设卡"的提议**（§14.52）。
 *
 * 语义是"按标题对上就替换那一张、对不上就在末尾追加一张"——两件事都有用：追加是"人设追加"这个词的
 * 字面意思（这一趟给它添了一张新卡），而替换是"这件事改变了它原来的某一面"（原来那张写着"你不信任
 * 陌生人"，这一趟之后它不再成立）。
 *
 * 与 [CharacterWrite] / [CycleWrite] 同形：**一次可以写好几张**，整批校验、整批提交。
 */
data class CardWrite(val id: String, val cards: List<CharacterCard>)

/** 卡那一段的解析结果。与 [CycleParse] 同形：读不懂就把原文退回去（§1.3）。 */
sealed interface CardParse {

    data class Parsed(val cards: List<CharacterCard>) : CardParse

    data class Failed(val why: String) : CardParse
}

/**
 * 读"一行一张卡"那一段（§14.52）。
 *
 * ## 与 [CharacterText.parseCards] 的差别只有一条：**标题不能省**
 *
 * `spawn_character` 那一侧，写不出标题的行整行当正文收下（标题默认成"身份"）——放一个新人时那是个
 * 合理的宽松。而这一只手是**按标题改**的：省掉标题的行会静默落到"身份"那一张上，而模型想改的很
 * 可能是别的。一次静默的错位比一次驳回贵得多，所以这里**要标题，缺了就驳回并把那行原文退回去**。
 */
object CardText {

    private val separators = charArrayOf('|', '｜')

    fun parse(text: String): CardParse {
        val cards = mutableListOf<CharacterCard>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val parts = line.split(*separators).map { it.trim() }
            if (parts.size < 2 || parts[0].isEmpty()) {
                return CardParse.Failed(
                    "这一行读不出标题：`$line`。一行一张卡，写成「标题｜内容」——**标题不能省**，" +
                        "因为这一只手是按标题改的（省了标题，这一行会静默落到「身份」那张上）。",
                )
            }
            val content = parts.drop(1).joinToString("｜").trim()
            if (content.isEmpty()) {
                return CardParse.Failed("「${parts[0]}」那张卡是空的——要删一张卡现在没有这条路，写点内容。")
            }
            cards += CharacterCard(parts[0], content)
        }
        if (cards.isEmpty()) return CardParse.Failed("一段卡都没读到（是不是只写了空行？）")
        return CardParse.Parsed(cards)
    }

    /** 写回去一行——答复里要把它复述出来（"你写进去的是……"）。 */
    fun render(card: CharacterCard): String = "${card.title}｜${card.content}"
}

/**
 * **人设追加**（§3.2.1 的第 1 件事、§12 第 46 项、§14.52）：把一个人这一趟的经历写进它的卡。
 *
 * 与 [CyclePlanner] / [CharacterPlanner] 同形——**整批校验、整批提交**，而 [apply] 只在校验通过
 * 之后被调用（`require`）。工具那一层因此在**真正落库之前**就知道成败（§1.5 第三条）。
 *
 * ## 它为什么不是一条 `Consequence`
 *
 * §12 第 46 项那句写的是"人设追加还缺一条 `Consequence`"，而做着做着发现那条路是错的：
 * `Consequence` 是**判定**那套后果（一次动作改了什么），它的发起者是世界里的某个实体，而人设追加的
 * 发起者是**导演**——它不在世界里，也不该借判定的手去改人。所以走的是一只手（与 `write_cycle` 同一个
 * 位置），而不是往 `Committer` 里加第九种后果。那一条的措辞已在文档里更正。
 *
 * ## 校验什么
 *
 *  1. 同一批里同一个 id 出现两次；
 *  2. id 是空的、世界里没有、或者**不是一个角色**（`kind` 不对）；
 *  3. **它本来一张卡都没有**——没有卡的东西在判定那边不是人（§3.5，`Rulebook` 也是按"有没有卡"分
 *     角色与物品的）。放它过去就等于"给一块石头写人设，于是它变成了一个人"，而那件事该走
 *     `spawn_character`；
 *  4. 看不见它（[visibleTo]）——看不见就写不了它的人设，因为你看不到它这一趟到底经历了什么；
 *  5. 一张卡超过 [MAX_CARD_CHARS]；
 *  6. 写完之后的**总量**超过 [CARD_BUDGET_CHARS]。
 *
 * **没有一条是"这句人设合不合常理"**——那是导演自己的活。代码判的只有"写不写得下、写的是不是人"。
 */
object CardPlanner {

    /**
     * 一张卡最多多少字。
     *
     * 比一条笔记（`Npc.MAX_NOTE_CHARS = 200`）宽一倍：笔记是"一件事的一句话"，而一张卡是"这个人的
     * 一面"——身份、脾气、知道什么、怕什么，写成两三百字是常态。
     */
    const val MAX_CARD_CHARS: Int = 400

    /**
     * **一个人的全部卡加起来最多多少字**。
     *
     * 这一条比单张那条重要：卡是**每次唤醒都全文进提示词**的（`Npc.kt:927` 的【你的人设】、判定那边
     * 的共用前缀与被指向者那一段），所以一个活在局里几十轮的角色如果每回归一次就添一张卡，最后会把
     * 别人挤出视野。
     *
     * 2000 字的来处：给"开局那几张"留足位置（开局矮人四张卡加起来不到两百字），再给十来次回归留余地。
     * 到顶之后的出路**不是丢弃**，而是改写——[problems] 会驳回并把现有的标题列回去（§6.5 那条
     * "可见的截断"同一个道理：让"它忘掉了什么"有人看得见）。
     */
    const val CARD_BUDGET_CHARS: Int = 2_000

    fun problems(
        world: World,
        writes: List<CardWrite>,
        visible: Set<String>,
        maxCardChars: Int = MAX_CARD_CHARS,
        budgetChars: Int = CARD_BUDGET_CHARS,
    ): List<String> = buildList {
        val seen = mutableSetOf<String>()
        for (write in writes) {
            val id = write.id.trim()
            if (id.isEmpty()) {
                add("有一条没说要写给谁。id 填你眼前那段清单里的那个角色。")
                continue
            }
            if (!seen.add(id)) {
                add("同一批里「$id」写了两次——一个人一次只写一遍，把几张卡并进同一条里。")
                continue
            }

            val target = world.byId(id)
            if (target == null) {
                add("世界里没有 id 是「$id」的东西。你眼前那段清单与那张地图上的 id 才是能写的那些。")
                continue
            }
            if (target.kind != CHARACTER_KIND) {
                add("「${target.label}」不是角色（它是个${target.kind}），没有它可以写的人设。")
                continue
            }
            if (target.cards.isEmpty()) {
                add(
                    "「${target.label}」现在**一张卡都没有**——那种东西在判定那边不算一个人（§3.5）。" +
                        "要给一块石头或者一件死物写来历，那是 `grow_terrain` 与它的 `placements` 的活；" +
                        "要放一个有名字的人出来，用 `spawn_character`。",
                )
                continue
            }
            if (id !in visible) {
                add(
                    "「${target.label}」不在你这一轮看得见的范围里——你看不到它这一趟经历了什么，" +
                        "写出来的人设只能是编的。先让它进到你能看见的地方，再写。",
                )
                continue
            }

            for (card in write.cards) {
                if (card.content.length > maxCardChars) {
                    add(
                        "「${card.title}」那张卡太长了（${card.content.length} 字，最多 $maxCardChars）" +
                            "——一张卡是这个人的**一面**，不是他的一生。把最要紧的那句留下，其余的并进" +
                            "别的卡里或者丢掉。",
                    )
                }
            }

            val merged = merge(target.cards, write.cards)
            val total = merged.sumOf { it.content.trim().length }
            if (total > budgetChars) {
                add(
                    "写完就超了：这几张卡加起来会有 $total 字，上限是 $budgetChars。" +
                        "它现在有的是：${target.cards.joinToString("、") { "${it.title}（${it.content.trim().length} 字）" }}。" +
                        "**把现有的那几张改写或者合并**（写出它们的标题就等于替换它们），别只往上添。",
                )
            }
        }
    }

    /**
     * 落库。**只在校验通过之后被调用**（`require` 在 [problems] 那一侧）。
     *
     * 合并规则是这一只手的全部语义：**标题对上的替换那一张（位置不动），没对上的追加到末尾**。
     * 位置不动是有用的：一个角色的卡是有编辑次序的（身份在头一张最自然），而"这件事改变了它的身份"
     * 不该顺手把身份挪到末尾去。
     */
    fun apply(world: World, writes: List<CardWrite>) {
        for (write in writes) {
            val target = world.byId(write.id) ?: continue
            world.put(target.copy(cards = merge(target.cards, write.cards)))
        }
    }

    private fun merge(existing: List<CharacterCard>, written: List<CharacterCard>): List<CharacterCard> {
        val out = existing.toMutableList()
        for (card in written) {
            val at = out.indexOfFirst { it.title.trim() == card.title.trim() }
            if (at >= 0) out[at] = card else out += card
        }
        return out
    }

    /** 写完一次之后，那个人的卡总量——答复里要报给人看（与 `write_cycle` 报格数同一条理由）。 */
    fun totalChars(cards: List<CharacterCard>): Int = cards.sumOf { it.content.trim().length }
}

/** 把一个人的卡列成一行（标题 + 字数）——提示词与答复都用它，于是两处不会各说各话。 */
fun List<CharacterCard>.titlesWithSize(): String =
    if (isEmpty()) "（一张卡都没有）"
    else joinToString("、") { "${it.title}（${it.content.trim().length} 字）" }
