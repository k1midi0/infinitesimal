package io.github.k1midi0.infinitesimal.world

/**
 * 分层字符网格：**「一层一个头 + 每行等长」**这一种表的骨架（附录 A.11）。
 *
 * ## 为什么抽它，而不让两端共用同一张表
 *
 * 形状（体素，5 厘米）与地形（50 厘米一格）用的是**同一种形状的表**，但层头的单位与字符的含义不同：
 *
 * | | 层头 | 字符 |
 * |---|---|---|
 * | [ShapeGrid] | `z=<厘米>` | `#` 实心 / `.` 空 / `1`–`9` / `+` 质点 |
 * | [TerrainGrid] | `z=<地形格>` | 材质表里的显示字符，`.` 是挖空 |
 *
 * A.11 那条「读写同一种表示法」说的是**同一个东西的读与写**，不是「所有的表都长一样」。所以这里抽的
 * 只是骨架：怎么起一层、怎么判一行、行不齐怎么报。**字符的含义留给两端**——它才是两件事真正的区别。
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
 * - 一层以 `z=<整数>` 起头。**这个头是必需的**：全空的层可以整层省掉，所以层与层之间的间距不能靠
 *   顺序去猜。
 * - 同一层里所有行必须**一样长**。这条是故意留的校验：模型少画了几个字符是最常见的一种画错，
 *   而它除了「行不一样长」之外没有别的症状。
 * - 行尾的空白会被忽略；**行尾的 `.` 不会**——`.` 是实义字符。
 * - 空行整行忽略：无论是提示词里的表还是模型的回话，都容易被它带上。
 */
object LayerGrid {

    sealed interface Parsed {
        data class Ok(val layers: List<Layer>) : Parsed

        /** 读不成格子。理由原样回给模型——它自己修得了。 */
        data class Bad(val problems: List<String>) : Parsed
    }

    /**
     * 一层里的一行。
     *
     * [line] 是它在**原文里的行号**（1 起）。报错要指回原文：模型改错时看的是自己刚写的那张表，
     * 而不是我们内部的行列号。
     */
    data class Row(val line: Int, val text: String)

    /**
     * 一层。
     *
     * [head] 是层头那个整数（形状那边是厘米、地形那边是地形格号）；[headLine] 是层头那一行的行号；
     * [rows] 是这一层的各行，**第 r 行就是第 r 行、第 c 个字符就是第 c 列**——原点由调用方定
     * （形状是全局原点，地形是框的最小角）。
     */
    data class Layer(val head: Int, val headLine: Int, val rows: List<Row>) {

        /** 这一层第一行的长度。空层是 0。 */
        val width: Int get() = rows.firstOrNull()?.text?.length ?: 0
    }

    /**
     * 读。
     *
     * [headName] 与 [unit] **只出现在给模型看的报错里**（`z`、`厘米`）：换了一层意思就要换一个词，
     * 否则模型会照着一个错单位的提示去改对的表。
     */
    fun parse(text: String, headName: String = "z", unit: String = "厘米"): Parsed {
        val layers = mutableListOf<Layer>()
        val problems = mutableListOf<String>()
        val seen = mutableSetOf<Int>()

        var head: Int? = null
        var headLine = 0
        var rows = mutableListOf<Row>()

        fun flush() {
            val value = head ?: return
            layers += Layer(value, headLine, rows)
            rows = mutableListOf()
        }

        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trimEnd()
            val number = index + 1
            if (line.isBlank()) return@forEachIndexed

            val header = Regex("""^\s*$headName\s*=\s*(-?\d+)\s*$""").find(line)
            if (header != null) {
                flush()
                val value = header.groupValues[1].toInt()
                if (!seen.add(value)) {
                    problems += "第 $number 行：$headName=$value 这一层出现了两次"
                }
                head = value
                headLine = number
                return@forEachIndexed
            }

            if (head == null) {
                problems += "第 $number 行是格子，但它前面没有 $headName=<$unit> 起一层"
                return@forEachIndexed
            }
            rows += Row(number, line)
        }
        flush()

        layers.forEach { layer ->
            val width = layer.width
            layer.rows.forEach { row ->
                if (row.text.length != width) {
                    problems += "第 ${row.line} 行有 ${row.text.length} 个字符，" +
                        "这一层的第一行有 $width 个；同一层每行要一样长"
                }
            }
        }

        return if (problems.isEmpty()) Parsed.Ok(layers) else Parsed.Bad(problems)
    }
}
