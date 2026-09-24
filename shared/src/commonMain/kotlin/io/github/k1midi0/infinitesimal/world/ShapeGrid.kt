package io.github.k1midi0.infinitesimal.world

/**
 * 分层 ASCII 网格：形状**进出模型的那唯一一种文本形式**（附录 A.11）。
 *
 * 读写共用它，于是模型只需要学一种表示法：**读世界**时一层层网格递过去，**写形状**时让它画一张
 * 网格回来。这比让它输出体素坐标列表可靠得多——一张 8×8 的图里画个矩形几乎不可能画错，用坐标
 * 描述同一张桌子几乎不可能不出错。
 *
 * ## 语法
 *
 * ```
 * z=0
 * ####
 * #..#
 * z=5
 * ####
 * ####
 * ```
 *
 * - 一层以 `z=<厘米>` 起头，**这个头是必需的**：全空的层可以整层省掉，所以层与层之间的间距不能靠
 *   顺序去猜。
 * - 层里每行是一排格：`#` 实心、`.` 空、`1`–`9` 该格里 1–9 个质点、`+` 该格里 10 个质点。
 * - **第 r 行就是 y = r × 5 厘米，第 c 个字符就是 x = c × 5 厘米。** 于是网格隐含"最小角在原点"
 *   （见 [VoxelShape] 的注释）。
 * - 同一层里所有行必须**一样长**。这条是故意留的校验：模型少画了几个字符是最常见的一种画错，
 *   而它除了"行不一样长"之外没有别的症状。
 * - 行尾的空白会被忽略；**行尾的 `.` 不会**——`.` 是实义字符。
 *
 * ## 解析出来的东西一定要过一遍 [VoxelShape.problems]
 *
 * 网格解析只管"这几行能不能读成格子"，管不了"这些格子合不合理"（同一个格既实心又质点、质点超了
 * 十个、超出单边上限……）。两件事分开报，是因为它们的修法不一样：前者是画法错了，后者是画的东西
 * 错了。
 */
object ShapeGrid {

    const val SOLID = '#'
    const val EMPTY = '.'
    const val TEN_POINTS = '+'

    sealed interface Parsed {
        data class Ok(val shape: VoxelShape) : Parsed

        /** 读不成格子。理由原样回给模型——它自己修得了。 */
        data class Bad(val problems: List<String>) : Parsed
    }

    /** 把一份形状画成网格。层从 z=0 起，**全空的层整层省掉**，但每一层都带着自己的 `z=`。 */
    fun render(shape: VoxelShape): String {
        val extent = shape.extent
        val solid = shape.solid.toHashSet()
        val points = shape.pointMass.associate { it.voxel to it.count }
        return buildString {
            var z = 0
            while (z <= extent.z) {
                val has = solid.any { it.z == z } || points.keys.any { it.z == z }
                if (has) {
                    appendLine("z=$z")
                    for (y in 0..extent.y step VOXEL_CM) {
                        for (x in 0..extent.x step VOXEL_CM) {
                            val v = Voxel(x, y, z)
                            append(
                                when {
                                    v in solid -> SOLID
                                    points[v] == MAX_POINTS_PER_VOXEL -> TEN_POINTS
                                    else -> points[v]?.let { ('0' + it) } ?: EMPTY
                                },
                            )
                        }
                        appendLine()
                    }
                }
                z += VOXEL_CM
            }
        }
    }

    fun parse(text: String): Parsed {
        val solid = mutableListOf<Voxel>()
        val pointMass = mutableListOf<PointCluster>()
        val problems = mutableListOf<String>()
        val seenLayers = mutableSetOf<Int>()

        var z: Int? = null
        /** 这一层各行按 y 排：第 r 行是 y = r × 5。 */
        var rows = mutableListOf<String>()
        var layerLine = 0

        fun flush() {
            val layerZ = z ?: return
            val width = rows.firstOrNull()?.length ?: 0
            rows.forEachIndexed { r, row ->
                val y = r * VOXEL_CM
                for (c in row.indices) {
                    val x = c * VOXEL_CM
                    val v = Voxel(x, y, layerZ)
                    when (val ch = row[c]) {
                        SOLID -> solid += v
                        EMPTY -> Unit
                        TEN_POINTS -> pointMass += PointCluster(v, MAX_POINTS_PER_VOXEL)
                        in '1'..'9' -> pointMass += PointCluster(v, ch - '0')
                        else -> problems += "第 ${layerLine + r} 行第 ${c + 1} 个字符是「$ch」；" +
                            "只认 $SOLID（实心）、$EMPTY（空）、1–9（几个质点）、$TEN_POINTS（$MAX_POINTS_PER_VOXEL 个质点）"
                    }
                }
                if (row.length != width) {
                    problems += "第 ${layerLine + r} 行有 ${row.length} 个字符，这一层的第一行有 $width 个；同一层每行要一样长"
                }
            }
            rows = mutableListOf<String>()
        }

        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trimEnd()
            val number = index + 1
            if (line.isBlank()) return@forEachIndexed

            val header = Regex("""^\s*z\s*=\s*(-?\d+)\s*$""").find(line)
            if (header != null) {
                flush()
                val value = header.groupValues[1].toInt()
                if (!seenLayers.add(value)) {
                    problems += "第 $number 行：z=$value 这一层出现了两次"
                }
                z = value
                layerLine = number
                return@forEachIndexed
            }

            if (z == null) {
                problems += "第 $number 行是格子，但它前面没有 z=<厘米> 起一层"
                return@forEachIndexed
            }
            if (rows.isEmpty()) layerLine = number
            // **行尾空白忽略，行尾的点不忽略。** `.` 是实义字符——裁掉它会让"少画了几个字符"
            // 这种最常见的画错变得看不出来，而同一层每行等长那条校验正是为它留的。
            rows += line
        }
        flush()

        if (problems.isNotEmpty()) return Parsed.Bad(problems)

        val shape = VoxelShape(solid, pointMass)
        val shapeProblems = shape.problems()
        return if (shapeProblems.isEmpty()) Parsed.Ok(shape) else Parsed.Bad(shapeProblems)
    }
}
