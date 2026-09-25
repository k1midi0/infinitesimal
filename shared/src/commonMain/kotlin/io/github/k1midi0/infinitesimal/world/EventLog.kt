package io.github.k1midi0.infinitesimal.world

import kotlinx.serialization.Serializable

/**
 * **记录层**（§1.5「记录永不改写」、§6.1 事件流、§12 第 20 项）。
 *
 * 它与 [Entity] 表是**两份不同的东西**，这是 §2 那条「事实 / 记录 / 呈现」三层分离的中间那一层：
 *
 *  - **事实**（[Entity]、[Terrain]）：世界现在是什么样。判定改它，改完就没有旧值了。
 *  - **记录**（这里）：发生过什么。**只追加，永不改写、永不删除。** 谁在什么时候说了什么、做了什么、
 *    世界因此变了哪几条。
 *  - **呈现**（`Entity.sentences`）：给玩家看的文字，可以作废重来。
 *
 * 早先这三层只有第一层和第三层，而「发生过什么」只落在会话日志里——过了这一局就没了。用户对这一条
 * 的原话是「不需要记录了，导演每轮都是醒着的」：**不需要一个写句子的人**（尤其不需要一个摘要模型），
 * 但**记录本身要有**，而它存的是**原始材料**——原话原样、后果原样，谁读谁自己解释。
 *
 * ## 一条记录的文由谁写
 *
 * **代码拼，不经过任何模型。** 理由有两条，都是硬的：
 *
 *  1. **措辞唯一。** §6.1 要的是「客观记录」，而两份由模型写的历史迟早会在同一个事实上写出两种说法
 *     ——那时候没人能回答"它当时到底收到了哪一句"。
 *  2. **零成本、零漂移。** 语义并没有丢：它由**后果用了哪只工具**和**那个东西的 label** 一起承载
 *     （`Replace(oak_table → 裂成两半的木桌)` 本身就说清了一切）。
 *
 * @property seq 全序编号，从 1 开始，**只增不减**。
 * @property at 世界时间（§8.1）。**不是墙上时间**——世界里的钟才是它。
 * @property actorId 谁做的这件事。时间轴到点这类没有主体的事是 `null`。
 * @property actorLabel 做事那个人**当时的**名字。**存下来而不是现查**：人被 Destroy 之后就查不到名字
 *   了，而"谁做过这件事"是记录，不该因为世界后来变了而变。
 * @property text 原话（[EventKind.SAY]）、那句意图（[EventKind.ACT]）、或时间轴上那件事（[EventKind.CLOCK]）。
 *   **原样存，不加工。**
 * @property effects 这一次提交真的改了世界的那几条，**代码拼的客观记录**。它们**和这条事件绑在一起**
 *   ——§6.1 那条「一次提交产生的全部后果不可切开」就落在这个字段上：裁窗口时一条事件要么整个进出，
 *   要么整个不进，不会出现"看到杯子掉在地上却没看到桌子被掀"那种矛盾认知。
 */
@Serializable
data class Event(
    val seq: Long,
    val at: WorldTime,
    val kind: EventKind,
    val actorId: String? = null,
    val actorLabel: String = "",
    val text: String = "",
    val effects: List<String> = emptyList(),
) {

    /**
     * 给提示词看的一行（或者几行）。
     *
     * **id 与名字都给**，理由与 `Npc` 那段【你刚才听见】一样：只印 label 会让模型把说话的人当成自己。
     */
    fun line(): String = buildString {
        append(at.toString())
        append("　")
        when (kind) {
            EventKind.SAY -> append("${actorLabel}（${actorId}）说：「$text」")
            EventKind.ACT -> append("${actorLabel}（${actorId}）做：$text")
            EventKind.CLOCK -> append(text)
        }
        effects.forEach { append("\n　　· $it") }
    }
}

/** 事件有哪几种。**闭合枚举**：开放字符串会让"哪一种"在某处写错一个字母而不报错。 */
enum class EventKind {
    /** 说了一句话。[Event.text] 是**原话**。 */
    SAY,

    /** 做了一个动作。[Event.text] 是那句意图，[Event.effects] 是它真的改了什么。 */
    ACT,

    /** 时间轴上到点的事（§8.1）。快进走到它，于是它离开时间轴、进到这里。 */
    CLOCK,
}

/**
 * 一次会话里发生过的全部事情，加上**每个角色收到了哪几条**（§6.1 的「一份事件流 + 每角色的 id 列表」）。
 *
 * 不是「全局一份」或「每角色一份」二选一，而是第三种：只有一份事件流，每个角色有一份**指向它的编号
 * 列表**。这样写入成本是 1 条记录 + M 个编号，措辞天然一致，而信息隔离仍然成立。
 *
 * 它**可审计**：出问题时你能回答"这个角色当时到底收到了什么"，因为那份编号列表就是证据。
 */
class EventLog private constructor(
    private val events: MutableList<Event>,
    private val delivered: MutableMap<String, MutableList<Long>>,
    private var next: Long,
) {

    val size: Int get() = events.size

    fun all(): List<Event> = events.toList()

    fun byId(seq: Long): Event? = events.firstOrNull { it.seq == seq }

    /** 某个角色收到过的编号，**按收到顺序**。 */
    fun deliveredTo(id: String): List<Long> = delivered[id]?.toList() ?: emptyList()

    /**
     * 追加一条，并**投递**给 [to] 里的每一个角色。
     *
     * 投递与追加是**同一步**：一条记录产生之后立刻决定谁收到了它，中间没有"还没投递"的状态。否则会
     * 出现一个窗口，在那里"事情已经发生"而"谁都不知道"，而那种中间态没有任何东西会去清理。
     *
     * 同一个角色被投递两次不会重复——编号列表里一个编号只出现一次。
     */
    fun append(
        at: WorldTime,
        kind: EventKind,
        actorId: String?,
        actorLabel: String,
        text: String,
        effects: List<String> = emptyList(),
        to: Collection<String> = emptyList(),
    ): Event {
        val event = Event(next++, at, kind, actorId, actorLabel, text, effects)
        events += event
        deliver(to, event.seq)
        return event
    }

    /** 单独投递一条已经存在的记录（给"想起来了"这类路径用）。重复投递是幂等的。 */
    fun deliver(to: Collection<String>, seq: Long) {
        to.distinct().forEach { id ->
            val list = delivered.getOrPut(id) { mutableListOf() }
            if (seq !in list) list += seq
        }
    }

    /**
     * 某个角色**此刻装得下的**最近那些事。
     *
     * **按条裁，不按轮裁**——§6.1 那条「一次提交产生的全部后果不可切开」是在**一条事件内部**保证的
     * （[Event.effects] 是一个整体），而不是跨事件。一次提交说了一句话又做了一件事，那是两条记录，
     * 裁掉其中一条不会让另一条自相矛盾。
     *
     * 最新的那些排在后面，**顺序与发生顺序一致**。
     */
    fun windowOf(id: String, limit: Int): List<Event> {
        if (limit <= 0) return emptyList()
        val ids = delivered[id] ?: return emptyList()
        return ids.takeLast(limit).mapNotNull { seq -> byId(seq) }
    }

    fun snapshot(): EventLogSnapshot = EventLogSnapshot(
        events = events.toList(),
        delivered = delivered.mapValues { (_, v) -> v.toList() },
        next = next,
    )

    companion object {

        fun empty(): EventLog = EventLog(mutableListOf(), mutableMapOf(), 1L)

        fun from(snapshot: EventLogSnapshot): EventLog = EventLog(
            snapshot.events.toMutableList(),
            snapshot.delivered.mapValuesTo(mutableMapOf()) { (_, v) -> v.toMutableList() },
            // **从快照里读回来，而不是从条数推。** 推出来的话，一个被裁过事件、却留下更大编号的世界
            // 会在下一次追加时撞号——而撞号的症状是两个不同的事件共用一个 seq。
            snapshot.next,
        )
    }
}

/**
 * 记录层的一份快照。**它进 [WorldSnapshot]**，于是存档天然带上"发生过什么"。
 *
 * [delivered] 是「角色 id → 编号列表」，用 `Map` 而不是一堆实体字段：那份列表**属于记录层**，不属于
 * 角色本身——角色死了、被删了，它收到过什么仍然是记录（§3.2.2「信息全部保留，循环停止」）。
 */
@Serializable
data class EventLogSnapshot(
    val events: List<Event> = emptyList(),
    val delivered: Map<String, List<Long>> = emptyMap(),
    val next: Long = 1L,
)
