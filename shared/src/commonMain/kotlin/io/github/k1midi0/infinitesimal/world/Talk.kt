package io.github.k1midi0.infinitesimal.world

/**
 * **发言的顺序**（用户定，2026-09-26）：一个角色每回合最多被唤醒一次，而同时被点到名的那些人
 * **按一个队列依次发言**。
 *
 * ## 为什么是一个队列，而不是每人身上一个整数
 *
 * 用户对这一条的形状说得很具体：
 *
 * > 优先值表值应该是紧挨着的，创建和修改的时候直接选择插入的位置，剩下的顺延往后，就像排队插队
 * > 一样，不会出现重合的
 *
 * 那就是**有序名单**，而不是"每人一个数"。两者的差别不是风格：
 *
 *  - 每人一个整数，就要有"两个人都写了 3"时怎么办的规则，而那条规则永远是猜的（谁先？按什么？）。
 *  - 一个队列，**重号在设计上就不可能存在**——名单里一个 id 只出现一次，插进去的时候后面那些
 *    自动往后挪一位。这跟 [Rulebook.priority] 那套不同：那是"哪条规则赢"，数字大的先算，同一个
 *    数字可以有好几条，因为规则本来就可能并列。
 *
 * 于是"不能存在相同优先值的 NPC"这条要求**由数据模型保证，不靠校验**——与 §7.2 那条
 * 「写操作的限制要落在数据模型上，不落在提示词上」是同一个做法。
 *
 * ## 它为什么落在 [World] 上，而不是实体上
 *
 * 顺序是**同一批人之间**的性质，不是某个人自己的属性：一个人的"第几位"没有意义，除非知道队列里
 * 还有谁。放在世界里也让**道具**能改它（用户点名的那件事）：「先手」那类东西动的是这个名单——
 * 把它拎到队首，或者插进某个位置。它进快照、跟着存档走，因为它是世界的事实（"谁先开口"不该随
 * 重启变）。
 *
 * ## 没写进名单的人
 *
 * 排在**最后**，按 id 排（只为让同一份世界每次跑出同一个顺序）。他是"没特别安排"，不是"第 0 位"
 * ——所以不能默认他最先。
 */

/** 名单里排第几。`null` = 它不在名单上（那就排在所有在名单上的人后面）。 */
fun World.speakPositionOf(id: String): Int? = speakQueue.indexOf(id).takeIf { it >= 0 }

/**
 * 这一批人按发言顺序排好。
 *
 * **不在名单上的排在最后**，彼此按 id 排——不为别的，只为**同一份世界每次跑出同一个顺序**。少这
 * 一条，两个都没排过队的角色谁先说话就取决于它们在实体表里的顺序，而那件事没人看得见。
 *
 * 名单里那些**已经不在世界上的 id** 在这里被跳过（角色死了、走了）。它们留在名单里不删是有理由的：
 * 也许只是暂时不在场（§3.2 的位置是存下来的值），回来时还该排在原来的位置。清理它们的是
 * [World.leaveTheQueue]。
 */
fun World.speakersAmong(ids: Collection<String>): List<String> = ids
    .filter { byId(it) != null }
    .sortedWith(compareBy({ speakPositionOf(it) ?: Int.MAX_VALUE }, { it }))

/**
 * 一个角色进入名单，**排在队尾**。
 *
 * 已经在名单上就什么都不做——**不把它挪到队尾**。这条看着小，而它是"创建时选位置"的另一半：
 * 一个早就在排队的角色不该因为再被提起一次就失去它的位置。
 */
fun World.joinTheQueue(id: String) {
    if (id in speakQueue) return
    if (byId(id) == null) return
    speakQueue = speakQueue + id
}

/**
 * **插队**：把 [id] 插到第 [position] 位，后面那些人依次往后挪一位（用户点名的那条规则）。
 *
 * 它同时管三件事，而它们必须一起做：
 *
 *  1. 已经在名单上 → **先摘掉**（不然名单里会有两个它）。
 *  2. 位置越界 → 夹到 `0..size`。**不做成错误**：调用方（导演、道具、界面）给一个"往前挪很多位"
 *     的意图时，夹一下比驳回更接近它要的意思。
 *  3. 插进去之后**后面那些自然顺延**。
 *
 * 它不校验"这个人是不是角色"：名单是**发言**的顺序，而"谁能发言"由 `GameSession` 那边按
 * 有没有卡判定（那件事只有一个地方管，见 `isCharacter`）。
 */
fun World.insertIntoQueue(id: String, position: Int) {
    val rest = speakQueue.filter { it != id }
    val at = position.coerceIn(0, rest.size)
    speakQueue = rest.toMutableList().apply { add(at, id) }
}

/**
 * 一个角色离开名单，后面那些人**依次往前挪**。
 *
 * 它不在每次唤醒时自动跑：**不在场不等于退出队列**（位置是存下来的值，它可能只是走到别处去了，
 * 回来时还该排在原来的位置）。什么时候一个人真的不再排队，是内容的事——死了、或者这一局不再出场。
 */
fun World.leaveTheQueue(id: String) {
    if (id !in speakQueue) return
    speakQueue = speakQueue.filter { it != id }
}

/**
 * 名单自己立不立得住。**只给诊断与测试看**，唤醒路径上不查它。
 *
 * 重号与"没这个人"在这套形状下**本来就不可能**（见文件头），所以这两条是保险丝，不是规则：它们
 * 一旦报出来，说明有人绕过了 [World.insertIntoQueue] / [World.joinTheQueue] 直接改了名单。
 */
fun World.speakQueueProblems(): List<String> = buildList {
    speakQueue.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted().forEach {
        add("发言名单里「$it」出现了不止一次——插队那条路会先摘掉它，所以这说明有人直接改了名单")
    }
    speakQueue.filter { byId(it) == null }.forEach {
        add("发言名单里的「$it」不在世界上（它可能是走了，也可能是被删了）")
    }
}
