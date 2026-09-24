package io.github.k1midi0.infinitesimal.judge

import io.github.k1midi0.infinitesimal.world.Material
import io.github.k1midi0.infinitesimal.world.TerrainBox
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

                // ── 地形（第八类，附录 A.15）────────────────────────────────────────
                //
                // **它与上面七种同批落库**，所以"把桌子推开、把桌底下的地挖了"要么一起生效、要么
                // 一起不生效。三只都走 [Terrain] 那三个入口，而它们自己会跳过不可破坏的格；
                // "碰到了挖不动的东西"在 [validate] 里就已经被拦下了，不会走到这儿。
                is Consequence.FillTerrain ->
                    world.terrain.fill(consequence.box, consequence.material, world.materials)

                is Consequence.DigTerrain ->
                    world.terrain.dig(consequence.box, world.materials)

                is Consequence.ReplaceTerrain ->
                    world.terrain.replace(consequence.box, consequence.from, consequence.to, world.materials)
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

                // ── 地形（第八类，附录 A.15）────────────────────────────────────────
                is Consequence.FillTerrain -> {
                    problems += terrainBoxProblems(world, consequence.box)
                    problems += materialProblems(world, "铺地形", consequence.material)
                }

                is Consequence.DigTerrain -> {
                    problems += terrainBoxProblems(world, consequence.box)
                    blocked(world, consequence.box, from = null)?.let {
                        problems += "挖地形：这个框碰到了挖不动的${it.named()}——把框抬上来，" +
                            "或者只圈你要挖的那一层"
                    }
                }

                is Consequence.ReplaceTerrain -> {
                    problems += terrainBoxProblems(world, consequence.box)
                    problems += materialProblems(world, "换地形（源材质）", consequence.from)
                    problems += materialProblems(world, "换地形（目标材质）", consequence.to)
                    blocked(world, consequence.box, from = consequence.from)?.let {
                        problems += "换地形：这个框碰到了挖不动的${it.named()}——框里那些就是你要换的，" +
                            "把框抬上来"
                    }
                }
            }
        }

        return problems
    }

    /**
     * 地形那个框的结构问题：**规模的、以及"整个落在世界之外"的**。
     *
     * 最后一条比它看起来重要：世界底下面是虚空（A.15），一个 `z = -900` 的框能过规模校验、能过
     * 材质校验、也能落库，而结果是**什么也没发生**——[io.github.k1midi0.infinitesimal.world.Terrain]
     * 只动落在 `WorldDepth` 里的格。模型下一轮看到世界没变，只会再提一次。
     */
    private fun terrainBoxProblems(world: World, box: TerrainBox): List<String> {
        val depth = world.terrain.depth
        val problems = box.problems().toMutableList()
        if (problems.isEmpty() && (box.maxZ < depth.bottom || box.minZ > depth.top)) {
            problems += "地形：这个框整个落在世界范围之外（世界是 z ${depth.bottom}..${depth.top}，" +
                "这个框是 z ${box.minZ}..${box.maxZ}）"
        }
        return problems
    }

    /** 材质 id 在不在那张独立的表里（A.15）。**拼错的材质名不会报错，只会什么都不发生**。 */
    private fun materialProblems(world: World, what: String, material: String): List<String> =
        if (world.materials.byId(material) == null) {
            val known = world.materials.all.joinToString("、") { it.id }
            listOf("$what：材质表里没有「$material」。表里现在是：$known")
        } else {
            emptyList()
        }

    /**
     * 框里有没有**挖不动**的材质，有就返回它。
     *
     * [io.github.k1midi0.infinitesimal.world.Terrain.dig] 与 `replace` 会**静默跳过**不可破坏的格
     * ——那是"世界底那层岩层不会被挖穿"的实现。静默跳过在这里是坏的：模型提议"把这一片挖空"、
     * 代码一格没动、而它下一轮看到世界没变，只会原样再提一次。所以这条**在提交之前**报出来。
     *
     * [from] 不为 null 时只看正在进行替换的那种材质——"框擦到一点岩层"与"框里全是岩层"不是一回事。
     */
    private fun blocked(world: World, box: TerrainBox, from: String?): Material? {
        // 框已经超限就别扫了：那条问题本身就要它把框改小，扫一遍是白花的。
        if (box.problems().isNotEmpty()) return null
        val depth = world.terrain.depth
        for (x in box.minX..box.maxX) {
            for (y in box.minY..box.maxY) {
                for (run in world.terrain.runsAt(x, y)) {
                    val a = maxOf(run.from, box.minZ, depth.bottom)
                    val b = minOf(run.to, box.maxZ, depth.top)
                    if (a > b) continue
                    if (from != null && run.material != from) continue
                    val material = world.materials.byId(run.material) ?: continue
                    if (!material.breakable) return material
                }
            }
        }
        return null
    }

    /**
     * 材质在**给模型看的那句话**里该怎么写：`「岩层」（bedrock）`。
     *
     * 两个都要给，因为两边各知道一半：**id 是它必须用来填参数的那个词**（也是【世界材质表】里那一列），
     * 而**名字是它在网格里看到的那个字符旁边的字**。只给 id，模型要回头去表里查；只给名字，它不知道
     * 该避开哪个 id——而这条消息的全部用处就是让它改得动那个框。
     */
    private fun Material.named(): String = "「$name」（$id）"
}
