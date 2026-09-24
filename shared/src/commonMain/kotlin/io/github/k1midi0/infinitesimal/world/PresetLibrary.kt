package io.github.k1midi0.infinitesimal.world

/**
 * **预设库**：造东西的工厂（附录 A.9）。
 *
 * 两个包**分开**，这是用户定的：
 *
 *  - [builtins]：随游戏带来的。只读，由代码给出。
 *  - [generatedPresets]：建模长出来的。一局里可以一直加，也是存档要带走的那一份。
 *
 * ## 它故意做得很薄
 *
 * 它只回答三个问题：**这条叫什么**、**这句要求造过没有**、**拿它造一个物体**。没有"从物体反查预设"
 * 这样的路——那会是一条引用，而 A.9 说的是工厂：物体一旦被造出来，它与预设的关系就结束了。
 *
 * ## 缓存键是那句要求，不是名字
 *
 * [byBrief] 是建模那一步的第一道关：同一句要求进来，直接拿回已有的形状，不花一次模型调用。用户定
 * 的这条规则有个好性质——**要求写得越具体，命中越准**；而要求写得含糊时，本来也不该复用一份形状。
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
     * 按**当初那句要求**找。这是建模的缓存查询。
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
}
