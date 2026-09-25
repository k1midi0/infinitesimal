package io.github.k1midi0.infinitesimal.spatial

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
