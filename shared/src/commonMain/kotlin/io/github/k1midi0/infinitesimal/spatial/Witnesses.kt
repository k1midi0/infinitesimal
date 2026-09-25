package io.github.k1midi0.infinitesimal.spatial

import io.github.k1midi0.infinitesimal.world.Event
import io.github.k1midi0.infinitesimal.world.World

/**
 * **谁在这一片里**——「投递」那一侧的粗筛（§14.37）。
 *
 * 用户对这一条的裁定是把两件事分开：
 *
 *  - **指向**（这话冲谁说的、这动作冲着谁去的）归**判定**：那是语义判断，代码做不了。
 *  - **投递**（这一轮发生了什么，谁收得到）归**代码**：它就是"在不在这片里"，而空间框已经算好了。
 *
 * 于是「跟我同伴闲聊一句」与「冲满屋子喊一嗓子」在系统里终于分得开：前者只有同伴被唤醒，而隔壁桌
 * **照样收得到这件事**（进它的编号列表，不唤醒它）——这就是 §3.2 那句「**投递总是发生，唤醒才是
 * 例外**」。氛围 NPC 之所以能有成百个而成本零增量，全部来源就是这条分法：投递是一个编号，唤醒才
 * 是一次调用。
 *
 * ## 为什么用**和判定同一把尺子**
 *
 * 框就是判定看的那一片（同一个 [RegionBuilder.of]，默认档位），不是另划一个半径。多算一个半径，
 * 就等于世界里有了**两套"附近"**，而它们迟早会漂开——症状是"判定看得见它，但它没收到这件事"。
 *
 * 框**不跟着判定扩大**：扩大是模型为了看清某样东西而做的，而投递的粗筛不需要那么准。多投递几条不
 * 危险（收到不等于相信），少投递才危险——而少投递那一头由「指向」兜住：被指向的人一定收得到。
 */
internal fun World.witnesses(actorId: String): List<String> {
    if (byId(actorId) == null) return emptyList()
    val region = RegionBuilder.of(this, listOf(actorId))
    return all()
        .filter { it.id != actorId && it.cards.isNotEmpty() && region.covers(it) }
        .map { it.id }
}

/**
 * 一条记录该投递给谁：**这一片里的角色 + 被指向的 + 被波及的 + 自己**。
 *
 * 后三项是**兜底**，各自堵一个具体的漏：
 *
 *  - **被指向的**：判定报出来的人可能站得比默认框远一点（它把框看大了才发现他们），而"被点名的人
 *    没收到这句话"是最坏的一种漏。
 *  - **被波及的**：桌子被劈了，站在框外的物主也该知道——它收到的是"世界变了"，不是"我听见了"。
 *  - **自己**：人知道自己做了什么。少了它，一个角色的窗口里会出现"东西自己动了"。
 *
 * 顺序是**由近及远**的：先同处一片的，再被点名的，再被波及的，最后是自己。投递本身不关心顺序
 * （[EventLog.deliver] 逐个追加编号），但排一下让调用点读起来是那个意思。
 */
internal fun World.audience(
    actorId: String,
    addressed: Collection<String> = emptyList(),
    affected: Collection<String> = emptyList(),
): List<String> = (witnesses(actorId) + addressed + affected + actorId).distinct()

/**
 * **事件总线**：这个角色此刻该看见的那些事（§6.1、§14.39）。
 *
 * ## 它是记录层的一个投影，不是另一份数据
 *
 * 记录层（`EventLog`）是 append-only 的、**不注入任何提示词**的；模型看见的是它在**上回合、范围内**
 * 的一个切片。所以"压掉就真没了"（模型那一侧的遗忘）与"底下还在"（`decompress` 与归因）能同时成立。
 *
 * ## 三条判据
 *
 *  - **[since] 起**：回合的起点，由会话在一次提交之前读 [io.github.k1midi0.infinitesimal.world.EventLog.latestSeq]
 *    取到。所以这**不是"最近 N 条"，而是"这一回合的那些"**——换回合就整体换掉，不带历史。
 *  - **在范围内**：用的是**与判定同一把尺子**（同一个 `RegionBuilder.of`、默认档位）。多算一个半径
 *    就等于世界里有了两套"附近"，而它们迟早会漂开。
 *  - **带位置的按位置筛，不带位置的算"哪儿都算"**（见 `Event.spot`）。
 *
 * ## 全文还是标题
 *
 * [BusLine.full]：**被指向的给全文，其余只给 `ref + 标题`**。这不是省 token 的小手段，而是"投递与唤醒
 * 分开"那条设计的延伸——**被指向**和**恰好在场**是两回事，而模型该能看出这个差别，并自己决定要不要
 * 花一次 `look` 去查。（§14.37 把「指向」交给判定，这里就是那个判断的消费者。）
 *
 * 自己被点名的、以及自己做的事，一律给全文：一个人不必"去查"自己刚说过什么。
 */
internal fun World.bus(actorId: String, since: Long): List<BusLine> {
    if (byId(actorId) == null) return emptyList()
    val region = RegionBuilder.of(this, listOf(actorId))
    return log.since(since)
        .filter { event ->
            val spot = event.spot ?: return@filter true
            region.contains(metreCell(spot.x), metreCell(spot.y))
        }
        .map { event ->
            val mine = event.actorId == actorId
            BusLine(event, full = mine || actorId in event.addressedTo)
        }
}

/**
 * 总线上的一条。
 *
 * @property full `true` = 给全文（被指向的、以及自己的）；`false` = 只给 `ref + 标题`，想看就用 `look`。
 */
internal data class BusLine(val event: Event, val full: Boolean) {
    /**
     * 提示词里的一行。两种形态的差别是刻意的——见 [World.bus] 的 KDoc。
     *
     * **「标题」是一行 gist，所以短句的 gist 约等于原话。** 这是它的固有性质，不是漏洞：一行标题本来
     * 就该一眼看得出"这是件什么事"。真正的分野在**后果那几条**（`Event.effects`）——那才是"细节"，
     * 而它只在 `look` 里给。所以这一对不是"看见 / 看不见"，而是"**知道有这么件事** / **知道它到底怎么
     * 了**"。
     */
    fun render(): String =
        if (full) "- ${event.line()}" else "- ${event.ref}｜${event.title()}（想看细节就 look 它）"
}
