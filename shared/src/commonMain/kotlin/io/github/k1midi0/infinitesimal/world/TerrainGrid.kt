package io.github.k1midi0.infinitesimal.world

/**
 * 地形那张分层表：**框选一块地读成一张表，改完再写回去**（§14.30）。
 *
 * ## 它和 [ShapeGrid] 差在哪
 *
 * | | 形状 | 地形 |
 * |---|---|---|
 * | 一格是多少 | 5 厘米 | 50 厘米（[TERRAIN_CM]） |
 * | 层头单位 | 厘米 | **地形格号** |
 * | 字符 | 实心 / 空 / 质点 | 材质表里的显示字符，`.` 是挖空 |
 * | 原点 | 全局原点 | **框的最小角** |
 * | 尺寸 | 随形状 | **必须与框严格同尺寸** |
 *
 * 最后一条是定的：「以这个框为最大边界输入表格修改」——**严格同尺寸**，于是没有「没写到的格」这个
 * 状态，每格都写，`.` 就是挖空。代价是模型每次都得把整框重画一遍，买到的是「我不小心把没写的地方
 * 全挖了」这类事故根本不可能发生。
 *
 * ## 为什么原点在框的最小角
 *
 * 因为表是**框的**表，不是世界的。模型先 `look_terrain(框)` 拿到这张表，改几个字符再发回来；两次的
 * 框是同一个，所以它不需要在世界坐标与表格行列之间做任何换算——那是「油灯坐标是编的」同一类错误
 * （§14.25）。
 *
 * ## 为什么尺寸上限按字符算
 *
 * 框选那三只手的代价是**段数**（[TerrainLimits.maxRuns]），表格的代价是**字符**。两者不是同一个量，
 * 所以各限各的：一张表每格一个字符，加上每层的层头，8000 字符大约是一张 20×20×20 的表。
 */
object TerrainGrid {

    const val EMPTY = '.'

    /**
     * 一次读或写，框最多这么大——**按渲染出来的字符数算**。
     *
     * 它同时护着两件事：模型手上那张表不会长到写不完，以及读回来的表不会把上下文撑爆。
     */
    const val MAX_CELLS = 8_000

    sealed interface Parsed {
        data class Ok(val cells: Map<TerrainCell, String?>) : Parsed

        /** 读不成表。理由原样回给模型——它自己修得了。 */
        data class Bad(val problems: List<String>) : Parsed
    }

    /**
     * **框选那三只**的规模判据。**空列表 = 合格。**
     *
     * 只有两条：框自己的段数与层高（[TerrainBox.problems]），以及「整个落在世界之外」——最后一条比
     * 它看起来重要：世界底下面是虚空，一个 `z = -900` 的框能过规模校验、能落库，而结果是**什么也
     * 没发生**，模型下一轮看到世界没变只会再提一次。
     *
     * **这里没有字符预算。**框选那三只走的是按列分段存的地形，一次铺满一整块 chunk（65536 格）存
     * 下来只是每列几段——那是合法的、也是生成器最常见的动作。字符预算只属于下面那两只表格的手：
     * **表格的代价是字符，框选的代价是段数**（§14.30）。
     */
    fun boxProblems(world: World, box: TerrainBox): List<String> = buildList {
        addAll(box.problems())
        outsideWorld(world, box)?.let { add(it) }
    }

    /** **表格那两只**的规模判据：在 [boxProblems] 之上再加一条字符预算。 */
    fun tableProblems(world: World, box: TerrainBox): List<String> = buildList {
        val layers = box.maxZ - box.minZ + 1
        val cells = box.columns.toLong() * layers
        if (cells > MAX_CELLS) {
            add(
                "这一张表太大了：框是 ${box.maxX - box.minX + 1}×${box.maxY - box.minY + 1} 列 × $layers 层" +
                    " = $cells 格，每格一个字符，超过 $MAX_CELLS 的预算。把框改小一点——" +
                    "大的改动走框选那三只手",
            )
        }
        addAll(box.problems())
        outsideWorld(world, box)?.let { add(it) }
    }

    /**
     * 框整个掉在世界外面。
     *
     * 措辞与提交层那条**故意写得接近**（`judge/Committer.kt` 的 `terrainBoxProblems`）：同一件事在
     * 两层各报一次，读的人不该看到两句话像是两回事。
     */
    private fun outsideWorld(world: World, box: TerrainBox): String? {
        val depth = world.terrain.depth
        if (box.maxZ >= depth.bottom && box.minZ <= depth.top) return null
        return "地形：这个框整个落在世界范围之外（世界是 z ${depth.bottom}..${depth.top}，" +
            "这个框是 z ${box.minZ}..${box.maxZ}）"
    }

    /** 把框里的地形画成一张表。层头是**地形格号**，不是厘米。 */
    fun render(world: World, box: TerrainBox): String = buildString {
        for (z in box.minZ..box.maxZ) {
            appendLine("z=$z")
            for (y in box.minY..box.maxY) {
                for (x in box.minX..box.maxX) {
                    val id = world.terrain.materialIdAt(TerrainCell(x, y, z))
                    append(if (id == null) EMPTY else world.materials.display(id))
                }
                appendLine()
            }
        }
    }

    /**
     * 读回一张表，**锚在 [box] 的最小角上**。
     *
     * 严格同尺寸分三层报，因为三处修法不同：层不对（少写一层）、行数不对（少画一行）、行长不对
     * （少画几个字符）。第一条整张表退回，后两条按层/按行报。
     */
    fun parse(world: World, box: TerrainBox, text: String): Parsed {
        val layers = when (val parsed = LayerGrid.parse(text, headName = "z", unit = "地形格")) {
            is LayerGrid.Parsed.Bad -> return Parsed.Bad(parsed.problems)
            is LayerGrid.Parsed.Ok -> parsed.layers
        }

        val columns = box.maxX - box.minX + 1
        val rows = box.maxY - box.minY + 1
        val wanted = box.minZ..box.maxZ

        if (layers.size != wanted.count() || layers.map { it.head }.toSet() != wanted.toSet()) {
            val got = layers.map { it.head }
            return Parsed.Bad(
                listOf(
                    "表格必须与框严格同尺寸。这个框是 z ${box.minZ}..${box.maxZ}（${wanted.count()} 层）、" +
                        "y ${box.minY}..${box.maxY}（$rows 行）、x ${box.minX}..${box.maxX}（$columns 个字符）；" +
                        "表里现在是 ${got.size} 层：${if (got.isEmpty()) "一层都没有" else got.joinToString("、")}。" +
                        "每层都要有 z=<地形格号> 的头，一层都不能省——整层是空的也要写出来。",
                ),
            )
        }

        val problems = mutableListOf<String>()
        val unknown = linkedSetOf<Char>()
        val cells = LinkedHashMap<TerrainCell, String?>()

        layers.sortedBy { it.head }.forEach { layer ->
            if (layer.rows.size != rows) {
                problems += "「z=${layer.head}」这一层有 ${layer.rows.size} 行，应该是 $rows 行" +
                    "（y ${box.minY}..${box.maxY}）"
                return@forEach
            }
            layer.rows.forEachIndexed { r, row ->
                if (row.text.length != columns) {
                    problems += "第 ${row.line} 行有 ${row.text.length} 个字符，应该是 $columns 个" +
                        "（x ${box.minX}..${box.maxX}）"
                    return@forEachIndexed
                }
                val y = box.minY + r
                row.text.forEachIndexed { c, ch ->
                    when (val meaning = meaningOf(world, ch)) {
                        is Meaning.Fill ->
                            cells[TerrainCell(box.minX + c, y, layer.head)] = meaning.material

                        Meaning.Dig -> cells[TerrainCell(box.minX + c, y, layer.head)] = null

                        Meaning.Unknown -> unknown += ch
                    }
                }
            }
        }

        if (unknown.isNotEmpty()) {
            problems += "表格里有不认识的字符：${unknown.joinToString("、") { "「$it」" }}；" +
                "只认材质表里那些显示字符，以及 $EMPTY（挖空）。表里现在是：${vocabulary(world)}"
        }

        return if (problems.isEmpty()) Parsed.Ok(cells) else Parsed.Bad(problems)
    }

    /** 表里那些字符分别在说什么。 */
    private sealed interface Meaning {
        data object Dig : Meaning
        data class Fill(val material: String) : Meaning
        data object Unknown : Meaning
    }

    private fun meaningOf(world: World, ch: Char): Meaning = when (ch) {
        EMPTY -> Meaning.Dig
        else -> world.materials.all.firstOrNull { it.display == ch }
            ?.let { Meaning.Fill(it.id) }
            ?: Meaning.Unknown
    }

    /** 报「这个字符不认识」时把整份词表附上：模型下一轮要照着它改。 */
    private fun vocabulary(world: World): String =
        world.materials.all.joinToString("、") { "${it.display}=${it.name}" } + "、$EMPTY=挖空"
}
