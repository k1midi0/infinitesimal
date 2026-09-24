package io.github.k1midi0.infinitesimal.judge

import io.github.k1midi0.infinitesimal.world.World
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round

/**
 * `calc(表达式)` 背后那个求值器（§12 第 12 项）。
 * **为什么要它。** 判定要算的数（够不够得着、扛不扛得动、火几息之后烧到）全都在规则书与属性词条里，
 * 而那些值是会被动作改的。让模型心算，它会把 6 和 9 相加得 14——**而且错得看不出来**：一个数字错了，
 * 下游那句话照样通顺，日志里也没有任何东西是红的。这是"错误不可检测"那一类，只能靠把算术挪出模型。
 *
 * **为什么只有四则加一个幂。** 表达式是模型吐出来的字符串。真给它一个解释器（求值一段
 * Python / JS）等于把任意代码执行权交给模型输出，而这一步根本不需要那个表达力——它需要的是"把
 * 已经写下来的数字按已经定下的关系合一下"。四则、括号、变量名，再加一个 `^`（实测里模型要算
 * 距离时自己就会写它），够用，而且能被穷尽地测。
 *
 * **不认识的名字是错误，不是 0。** 静默地把 `oak_tabel.硬度`（打错的 id）当成 0，算出来的正是一个
 * 看着合理的错答案——比报错坏得多。
 */
object Calc {

    sealed interface Result {
        /** 算出来了。[expression] 由调用方回给模型，好让它看见自己写的是什么。 */
        data class Value(val value: Double) : Result

        /** 算不出来。**这不是协议内失败**——它是模型可以自己修的一步，把 [problem] 原样回给它。 */
        data class Failed(val problem: String) : Result
    }

    /** 求值。变量名由调用方注入（见 [Variables]）。 */
    fun evaluate(expression: String, variables: Map<String, Double>): Result {
        val text = expression.trim()
        if (text.isEmpty()) return Result.Failed("式子是空的")
        val parser = Parser(text, variables)
        return try {
            val value = parser.expression()
            if (!parser.atEnd()) return Result.Failed("式子在「${parser.rest()}」这里就读不下去了")
            if (value.isNaN() || value.isInfinite()) return Result.Failed("算出来不是一个数")
            Result.Value(value)
        } catch (failure: CalcProblem) {
            Result.Failed(failure.message.orEmpty())
        }
    }

    /**
     * 把一个数写成人读的样子。**整数不带小数点**——「3 格」比「3.0 格」更像世界在说话。
     */
    fun format(value: Double): String {
        if (abs(value) < 1e15 && value == value.toLong().toDouble()) return value.toLong().toString()
        val rounded = round(value * 1000) / 1000
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
    }

    private class CalcProblem(message: String) : Exception(message)

    private class Parser(private val source: String, private val variables: Map<String, Double>) {

        private var at = 0

        fun atEnd(): Boolean {
            skip()
            return at >= source.length
        }

        /** 出错时指一下读到哪里——模型要能自己修，就得知道是哪儿断的。 */
        fun rest(): String = source.substring(at).take(20)

        // 表达式 := 项 (('+' | '-') 项)*
        fun expression(): Double {
            var value = term()
            while (true) {
                when (peek()) {
                    '+' -> { at++; value += term() }
                    '-' -> { at++; value -= term() }
                    else -> return value
                }
            }
        }

        // 项 := 幂 (('*' | '/') 幂)*
        private fun term(): Double {
            var value = power()
            while (true) {
                when (peek()) {
                    '*' -> { at++; value *= power() }
                    '/' -> {
                        at++
                        val divisor = power()
                        if (divisor == 0.0) throw CalcProblem("除以零")
                        value /= divisor
                    }

                    else -> return value
                }
            }
        }

        /**
         * 幂 := 因子 ('^' 幂)? —— **右结合**（`2^3^2` 是 2^9，不是 8^2）。
         *
         * `^` 是**实测加上去的**（§14.19）：模型要算距离，自己写出了
         * `((broom.x - player.x)^2 + (broom.y - player.y)^2)^0.5`——而当时求值器只认四则，
         * 那几次调用全部失败（它退回去写成 `(dx)*(dx) + ...` 才算出来）。
         * **平方与开方是这一层最常用的两种运算；硬禁一个模型天天想写的符号，不如把它实现对。**
         */
        private fun power(): Double {
            val base = factor()
            if (peek() == '^') {
                at++
                return base.pow(power())
            }
            return base
        }

        private fun factor(): Double = when (peek()) {
            '-' -> { at++; -factor() }
            '+' -> { at++; factor() }
            else -> atom()
        }

        private fun atom(): Double {
            val c = peek() ?: throw CalcProblem("式子在这里就断了，少一个数或者变量名")
            if (c == '(') {
                at++
                val value = expression()
                if (peek() != ')') throw CalcProblem("括号没有关上")
                at++
                return value
            }
            if (c.isDigit() || c == '.') return number()
            if (isNameChar(c)) return name()
            throw CalcProblem("不认识这个符号：「$c」")
        }

        private fun number(): Double {
            val from = at
            while (at < source.length && (source[at].isDigit() || source[at] == '.')) at++
            val text = source.substring(from, at)
            return text.toDoubleOrNull() ?: throw CalcProblem("「$text」不是一个数")
        }

        private fun name(): Double {
            val from = at
            while (at < source.length && isNameChar(source[at])) at++
            val text = source.substring(from, at)
            return variables[text] ?: throw CalcProblem(
                "没有叫「$text」的变量" + if (variables.isEmpty()) {
                    "，这一步一个数都没有"
                } else {
                    "。这一步能用的有：${variables.keys.sorted().joinToString("、")}"
                },
            )
        }

        private fun peek(): Char? {
            skip()
            return if (at < source.length) source[at] else null
        }

        private fun skip() {
            while (at < source.length && source[at].isWhitespace()) at++
        }

        /** 变量名里允许中文（属性名就是中文的）、下划线和点（点用来分「哪个东西的哪个字段」）。 */
        private fun isNameChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '.'
    }
}

/**
 * 一次判定里 `calc` 被调用的记录。
 *
 * 与 [ViewBudget.widenings] 是同一条理由：**一只新加的手值不值，全看模型到底用不用它、拿它算什么**
 * ——而这两件事在结果里都看不出来（算与不算，最后那句后果读起来一样）。不记就是靠猜。
 */
class CalcLog internal constructor() {

    private val seen = mutableListOf<String>()

    /** 这次判定里调了几次。 */
    val calls: Int get() = seen.size

    /** 它算过的式子，按调用顺序。**看它拿什么在算，比看次数有用得多。** */
    val expressions: List<String> get() = seen

    internal fun record(expression: String) {
        seen += expression
    }

    override fun toString(): String = "calc ${calls} 次" + if (seen.isEmpty()) "" else "（${seen.joinToString("；")}）"
}

/**
 * `calc` 能用到的变量。**来源只有两处**：实体的属性词条，与规则书里的数值。
 *
 * 名字一律是 `<来源 id>.<字段>`（世界那份规则书用 `世界.` 前缀）。**不做"不带前缀的短名"**——那需要
 * 一条"唯一的键才能省前缀"的隐含规则，而模型看不到这条规则，只会写一个碰巧对上的名字。
 *
 * 不是数的值（「材质=橡木」）**直接不出现**：列出来只会让人以为它能算。
 */
object Variables {

    fun of(world: World, entityIds: List<String>): Map<String, Double> {
        val out = mutableMapOf<String, Double>()

        fun take(prefix: String, pairs: Map<String, String>) {
            for ((key, raw) in pairs) {
                raw.trim().toDoubleOrNull()?.let { out["$prefix.$key"] = it }
            }
        }

        for (entry in world.rulebook.entries) take("世界", entry.values)

        for (id in entityIds.distinct()) {
            val entity = world.byId(id) ?: continue
            // **坐标也是数。** 实测（§14.19）说模型第一件想算的事就是"隔多远"——它写的式子是
            // `broom.x - player.x`，而当时变量表里只有属性词条，于是那一次白调了（它还自己回退
            // 找对了答案，但那是运气）。"够不够得着"本来就是两个坐标之差，不给它就只能心算。
            out["$id.x"] = entity.x.toDouble()
            out["$id.y"] = entity.y.toDouble()
            out["$id.z"] = entity.z.toDouble()
            take(id, entity.attributes)
            for (entry in entity.rulebook.entries) take(id, entry.values)
        }

        return out
    }
}
