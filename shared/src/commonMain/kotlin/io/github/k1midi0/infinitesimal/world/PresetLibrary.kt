package io.github.k1midi0.infinitesimal.world

import io.github.k1midi0.infinitesimal.memory.queryTerms

/**
 * **预设库**：造东西的工厂（附录 A.9、A.14）。
 *
 * 两个包**分开**，这是用户定的：
 *
 *  - [builtins]：随游戏带来的。只读，由代码给出。
 *  - [generatedPresets]：建模长出来的。一局里可以一直加，也是存档要带走的那一份。
 *
 * ## 它故意做得很薄
 *
 * 它只回答四个问题：**这条叫什么**、**这句要求造过没有**、**哪几条像我要的那样东西**、**拿它造一个
 * 物体**。没有"从物体反查预设"这样的路——那会是一条引用，而 A.9 说的是工厂：物体一旦被造出来，它
 * 与预设的关系就结束了。
 *
 * ## 两道关：先是那句要求，再是关键词
 *
 * [byBrief] 是**第一道关**：同一句要求进来，直接拿回已有的形状，不花一次模型调用。用户定的这条规则
 * 有个好性质——**要求写得越具体，命中越准**；而要求写得含糊时，本来也不该复用一份形状。
 *
 * 但实测证明它不够（§14.31）：一次生成里同一个东西被造了 11 遍，因为模型每次写的要求都略微不同，
 * 11 次全部落空。于是有了**第二道关** [search]——按 [Preset.category] 与 [Preset.tags] 做关键词
 * 检索。两道关是两个不同的问题：前者问"是不是同一句话"，后者问"是不是同一类东西"。
 */
class PresetLibrary(builtin: List<Preset> = emptyList()) {

    private val builtins: Map<String, Preset> = builtin.associateBy { it.name }

    /** 生成出来的那些。**用 LinkedHashMap 保序**：存档读回来时保持当初造出来的先后。 */
    private val generated = LinkedHashMap<String, Preset>()

    /** 库里所有预设，先出厂后生成。 */
    fun all(): List<Preset> = builtins.values.toList() + generated.values.toList()

    fun builtinPresets(): List<Preset> = builtins.values.toList()

    fun generatedPresets(): List<Preset> = generated.values.toList()

    fun find(name: String): Preset? = builtins[name] ?: generated[name]

    /**
     * 按**当初那句要求**找。这是建模缓存的第一道关。
     *
     * 线性扫，因为库很小（出厂那批几十条、一局里长出来的通常也是几十条）。真到了几千条再换成索引，
     * 而那时候更该先问的是"为什么一局里会建模几千次"。
     */
    fun byBrief(brief: String): Preset? {
        val wanted = brief.trim()
        if (wanted.isEmpty()) return null
        return all().firstOrNull { it.brief.trim() == wanted }
    }

    /**
     * 按**关键词**找：这是第二道关，也是"我要一棵树"这一类的问法唯一能走的路。
     *
     * ## 为什么用 bigram
     *
     * 中文不写空格，整句切出来就是一个词，什么样的包含关系都匹配不上。这是从 Pulsus 搬来的那条经验
     * （[queryTerms] 的注释）：切错方向的代价不对称——**多一条候选只是清单里多一行，而少一条是
     * 一样东西静默地搜不到**。
     *
     * ## 打分
     *
     * 一个词落在 [Preset.category] 或 [Preset.tags] 上算 2 分，落在名字或那句要求上算 1 分。分成两档
     * 是因为这两处的词性不同：属性是**专门为搜索写的**，而那句要求是为了画图写的，里面全是修饰词
     * （"被雷劈过的"、"断面粗糙的"）——同一个词出现在前者，比出现在后者说明的问题多得多。
     *
     * 同一个词只算一次，取它的最高那一档。入参为空、或者一个词都没命中，返回空表（**不是全部**——
     * 空查询返回全部，会让"搜不到"与"懒得搜"长得一样）。
     */
    fun search(query: String, limit: Int = MAX_RESULTS): List<Preset> {
        val terms = queryTerms(query.trim())
        if (terms.isEmpty()) return emptyList()

        return all()
            .map { preset -> preset to preset.scoreOf(terms) }
            .filter { (_, score) -> score > 0 }
            .sortedByDescending { (_, score) -> score }
            .take(limit)
            .map { (preset, _) -> preset }
    }

    /** 一条预设对一组词有多少分。见 [search] 里那张两档的表。 */
    private fun Preset.scoreOf(terms: List<String>): Int {
        val attribute = (listOf(category) + tags).joinToString(" ").lowercase()
        val descriptive = "$name $brief".lowercase()
        return terms.sumOf { term ->
            val word = term.lowercase()
            when {
                attribute.contains(word) -> 2
                descriptive.contains(word) -> 1
                else -> 0
            }
        }
    }

    /**
     * 记下一条生成出来的预设。
     *
     * **同名的直接替换**，因为名字是"一样什么东西"的标签：同一个名字的第二份形状，本身就是对第一份
     * 的修正（建模重来一次），并存两条只会让 `find` 的答案变得取决于插入顺序。
     *
     * 进来的一条一律标成 [Preset.Origin.GENERATED]：它进了生成那个包，就不该还挂着出厂的标签——
     * 一个字段与它所在的位置各说各话，是日后最难查的一类不一致。
     */
    fun learn(preset: Preset): Preset {
        val stored = preset.copy(origin = Preset.Origin.GENERATED)
        generated[stored.name] = stored
        return stored
    }

    /** 存档读回来时用。 */
    fun restore(presets: List<Preset>) {
        generated.clear()
        presets.forEach { generated[it.name] = it }
    }

    /**
     * 拿一条预设造一个物体。**这就是"工厂"那一步。**
     *
     * 形状是**直接给过去**的，不是引用：`VoxelShape` 不可变，所以"拷贝"与"共享"在这里没有区别——
     * 而 A.9 要的正是不可能出现"改一个杯子让所有杯子一起碎"。
     */
    fun spawn(
        preset: Preset,
        id: String,
        kind: String,
        label: String,
        x: Int,
        y: Int,
        z: Int,
        background: String? = null,
    ): Entity = Entity(
        id = id,
        kind = kind,
        label = label,
        x = x,
        y = y,
        z = z,
        shape = preset.shape,
        background = background,
    )

    companion object {
        /**
         * 一次检索最多回几条。**这个数是给模型看的**，不是性能上限：候选列多了它挑不过来，而列 5 条
         * 与列 20 条对"有没有它要的那样东西"这个问题的回答几乎一样。
         */
        const val MAX_RESULTS = 8
    }
}
