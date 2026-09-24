package io.github.k1midi0.infinitesimal.judge

import io.github.k1midi0.infinitesimal.world.World

/**
 * 判定系统的**代码段**：校验 + 原子提交（§4.2）。
 *
 * 两条纪律：
 *
 *  1. **全成或全不成。** 一回合里可能并行回来多个工具调用，而不原子提交的话，一次"同时扣血和加血"
 *     就能让存档变成不可复现的东西。
 *  2. **协议内失败绝不示给玩家**（§4.3）。校验不过就是"我们的代码或模型的提议坏了"，退回重试；
 *     玩家看到"你的操作不合理"而实际上是我们的 bug，会让你在错误的层次上排查很久。
 *
 * 校验只看**结构**：id 在不在、新 id 有没有撞上、一批里后面的后果有没有引用已经被收走的东西。
 * 物理与语义（够不够得着、搬不搬得动）**一律不在这里**——那是模型段的事（附录 A.4）。
 *
 * **它不管"谁挂在谁下面"了。** 那条关系（`parent`）连同成环校验一起删掉了：关系不存成事实，
 * 位置就是坐标（附录 A.5，2026-09-24）。
 */
object Committer {

    sealed interface Result

    /** 全部通过并且已经落库。 */
    data class Applied(
        val consequences: List<Consequence>,
        /** 这次动作影响到的角色 id。§5.2 的投递对象，由模型段判定，这里只负责带过去。 */
        val affected: Set<String>,
    ) : Result

    /** 协议内失败。**退回重试，不示给玩家。** */
    data class Rejected(val problems: List<String>) : Result

    fun apply(
        world: World,
        consequences: List<Consequence>,
        affected: Collection<String> = emptyList(),
    ): Result {
        val problems = validate(world, consequences)
        if (problems.isNotEmpty()) return Rejected(problems)

        for (consequence in consequences) {
            when (consequence) {
                is Consequence.Move ->
                    world.byId(consequence.id)?.let {
                        world.put(it.copy(x = consequence.x, y = consequence.y, z = consequence.z))
                    }

                is Consequence.SetAttribute ->
                    world.byId(consequence.id)?.let {
                        world.put(it.copy(attributes = it.attributes + (consequence.key to consequence.value)))
                        // 算外表变化：属性变了，既有描述就不再可信（见 Consequence.SetAttribute 的注释）。
                        world.invalidateAppearance(consequence.id)
                    }

                is Consequence.ChangeAppearance -> world.invalidateAppearance(consequence.id)

                // 建模造出来的形状。**它排在 Spawn/Replace 后面**（ShapeLog.consequences 的注释），
                // 所以到这里 `byId` 找得到那个刚进世界的新东西。
                is Consequence.SetShape -> world.byId(consequence.id)?.let {
                    world.put(it.copy(shape = consequence.shape))
                    world.invalidateAppearance(consequence.id)
                }

                is Consequence.Destroy -> world.remove(consequence.id)

                is Consequence.Spawn -> world.put(consequence.entity)

                is Consequence.Replace -> world.replace(consequence.oldId, consequence.entity)
            }
        }

        return Applied(consequences, affected.toSet())
    }

    /**
     * 校验整批后果。**不碰世界。**
     *
     * 它维护一份影子状态（哪些 id 存在、谁是谁的父对象、哪些已经被这一批收走），所以"一批里第 3 条
     * 非法"能在**落库之前**被发现——而不是前两条已经写进去了才发现。
     */
    fun validate(world: World, consequences: List<Consequence>): List<String> {
        val problems = mutableListOf<String>()
        val existing = world.all().associateByTo(mutableMapOf()) { it.id }
        val retired = mutableSetOf<String>()

        fun requireLive(id: String, what: String) {
            when {
                !existing.containsKey(id) -> problems += "$what：世界里没有 $id"
                id in retired -> problems += "$what：$id 已经在这一批里被收走了，后面的后果不能再引用它"
            }
        }

        for (consequence in consequences) {
            when (consequence) {
                is Consequence.Move -> requireLive(consequence.id, "移动")

                is Consequence.SetAttribute -> {
                    requireLive(consequence.id, "改属性")
                    if (consequence.key.isBlank()) problems += "改属性：属性名不能为空"
                }

                is Consequence.ChangeAppearance -> requireLive(consequence.id, "外表变化")

                is Consequence.SetShape -> requireLive(consequence.id, "造形状")

                is Consequence.Destroy -> {
                    requireLive(consequence.id, "摧毁")
                    retired += consequence.id
                }

                is Consequence.Spawn -> {
                    val entity = consequence.entity
                    when {
                        entity.id.isBlank() -> problems += "新增：id 不能为空"
                        existing.containsKey(entity.id) -> problems += "新增：${entity.id} 已经存在了"
                        // 同批里先前 Spawn 出来的东西可以被后面的后果引用。
                        else -> existing[entity.id] = entity
                    }
                }

                is Consequence.Replace -> {
                    requireLive(consequence.oldId, "彻底改变")
                    val entity = consequence.entity
                    when {
                        entity.id == consequence.oldId ->
                            problems += "彻底改变：新 id 必须与旧 id 不同（同 id 的改动是外表变化，该用 ChangeAppearance）"
                        existing.containsKey(entity.id) ->
                            problems += "彻底改变：新 id ${entity.id} 已经存在"
                        else -> {
                            existing[entity.id] = entity
                            retired += consequence.oldId
                        }
                    }
                }
            }
        }

        return problems
    }
}
