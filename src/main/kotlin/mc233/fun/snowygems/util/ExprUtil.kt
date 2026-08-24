package mc233.`fun`.snowygems.util

import kotlin.random.Random

/**
 * 极简算术表达式求值器, 用于解析宝石配置中形如:
 *   var=v+1 | var=v+0.01 | var=v-10 | amount=100+2900*$RANDOM()
 *   var=v<2?2:3 | var=v>=3?3:v+1        (支持比较 + 三元, 用于多等级宝石)
 * 的表达式. 支持:
 *   + - * / ( )                     算术
 *   < <= > >= == !=                 比较(结果为 1.0 / 0.0)
 *   cond ? a : b                    三元(cond 非 0 取 a, 否则取 b)
 *   变量 v                          由调用方传入的当前值
 * 不支持函数调用(RANDOM 会被预先替换).
 */
object ExprUtil {

    /** $RANDOM() 通配符; 预编译避免每次求值都重新编译正则 */
    private val randomRegex = Regex("""\${'$'}RANDOM\(\)""")
    /** 独立变量 v(避免误伤单词内部, 如函数名中的 v) */
    private val varRegex = Regex("""(?<![A-Za-z0-9_.])v(?![A-Za-z0-9_.])""")
    /** 提取文本中的第一个数字 */
    private val numberRegex = Regex("""-?\d+(\.\d+)?""")

    fun eval(expr: String, v: Double = 0.0): Double {
        var s = expr.trim()
        // 先处理 $RANDOM() -> 替换为 [0,1) 的随机数, 每次出现各自独立取值
        s = randomRegex.replace(s) { Random.nextDouble().toString() }
        // 替换独立的变量 v
        s = varRegex.replace(s) { v.toString() }
        return try {
            Parser(s).parseTernary()
        } catch (e: Exception) {
            v
        }
    }

    private class Parser(private val src: String) {
        private var pos = 0

        /** 最外层: 三元 cond ? a : b */
        fun parseTernary(): Double {
            val cond = parseComparison()
            skipSpaces()
            if (peek() == '?') {
                pos++ // 吃掉 '?'
                val whenTrue = parseTernary()
                skipSpaces()
                if (peek() == ':') pos++ // 吃掉 ':'
                val whenFalse = parseTernary()
                return if (cond != 0.0) whenTrue else whenFalse
            }
            return cond
        }

        private fun parseComparison(): Double {
            val left = parseExpression()
            skipSpaces()
            val op = matchComparator() ?: return left
            val right = parseExpression()
            val r = when (op) {
                "<" -> left < right
                "<=" -> left <= right
                ">" -> left > right
                ">=" -> left >= right
                "==" -> left == right
                "!=" -> left != right
                else -> false
            }
            return if (r) 1.0 else 0.0
        }

        private fun matchComparator(): String? {
            skipSpaces()
            if (pos >= src.length) return null
            val c = src[pos]
            val n = if (pos + 1 < src.length) src[pos + 1] else ' '
            return when {
                c == '<' && n == '=' -> { pos += 2; "<=" }
                c == '>' && n == '=' -> { pos += 2; ">=" }
                c == '=' && n == '=' -> { pos += 2; "==" }
                c == '!' && n == '=' -> { pos += 2; "!=" }
                c == '<' -> { pos++; "<" }
                c == '>' -> { pos++; ">" }
                else -> null
            }
        }

        private fun parseExpression(): Double {
            var value = parseTerm()
            while (true) {
                skipSpaces()
                when (peek()) {
                    '+' -> { pos++; value += parseTerm() }
                    '-' -> { pos++; value -= parseTerm() }
                    else -> return value
                }
            }
        }

        private fun parseTerm(): Double {
            var value = parseFactor()
            while (true) {
                skipSpaces()
                when (peek()) {
                    '*' -> { pos++; value *= parseFactor() }
                    '/' -> { pos++; val d = parseFactor(); value = if (d == 0.0) 0.0 else value / d }
                    else -> return value
                }
            }
        }

        private fun parseFactor(): Double {
            skipSpaces()
            if (peek() == '-') { pos++; return -parseFactor() }
            if (peek() == '+') { pos++; return parseFactor() }
            if (peek() == '(') {
                pos++
                val v = parseTernary()
                skipSpaces()
                if (peek() == ')') pos++
                return v
            }
            val start = pos
            while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
            if (start == pos) return 0.0
            return src.substring(start, pos).toDoubleOrNull() ?: 0.0
        }

        private fun peek(): Char {
            skipSpaces()
            return if (pos < src.length) src[pos] else ' '
        }

        private fun skipSpaces() {
            while (pos < src.length && src[pos] == ' ') pos++
        }
    }

    fun extractNumber(text: String): Double {
        val m = numberRegex.find(text) ?: return 0.0
        return m.value.toDoubleOrNull() ?: 0.0
    }
}
