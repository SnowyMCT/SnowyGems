package mc233.`fun`.snowygems.manager

/** Strict, compiled arithmetic for dismantle prices and return chance. */
class DismantleFormula private constructor(private val root: Node) {
    private fun interface Node { fun value(vars: Map<String, Double>): Double }

    fun evaluate(vars: Map<String, Double>): Double = root.value(vars)

    companion object {
        val variables = setOf("rarity", "total", "same", "damage", "enchants", "unbreakable")

        fun compile(raw: String): DismantleFormula {
            require(raw.length in 1..256) { "表达式长度须为 1..256" }
            return DismantleFormula(Parser(raw).parse())
        }
    }

    private class Parser(private val source: String) {
        private var pos = 0
        fun parse(): Node {
            val node = expression()
            spaces()
            require(pos == source.length) { "表达式第 ${pos + 1} 位有无效内容" }
            return node
        }

        private fun expression(): Node {
            var left = term()
            while (true) {
                when (peek()) {
                    '+' -> { pos++; val a = left; val b = term(); left = Node { v -> a.value(v) + b.value(v) } }
                    '-' -> { pos++; val a = left; val b = term(); left = Node { v -> a.value(v) - b.value(v) } }
                    else -> return left
                }
            }
        }

        private fun term(): Node {
            var left = factor()
            while (true) {
                when (peek()) {
                    '*' -> { pos++; val a = left; val b = factor(); left = Node { v -> a.value(v) * b.value(v) } }
                    '/' -> { pos++; val a = left; val b = factor(); left = Node { v -> a.value(v) / b.value(v) } }
                    else -> return left
                }
            }
        }

        private fun factor(): Node {
            return when (peek()) {
                '+' -> { pos++; factor() }
                '-' -> { pos++; val node = factor(); Node { v -> -node.value(v) } }
                '(' -> {
                    pos++
                    val node = expression()
                    require(peek() == ')') { "缺少右括号" }
                    pos++
                    node
                }
                else -> atom()
            }
        }

        private fun atom(): Node {
            spaces()
            val start = pos
            while (pos < source.length && (source[pos].isDigit() || source[pos] == '.')) pos++
            if (pos > start) {
                val number = source.substring(start, pos).toDoubleOrNull()
                require(number != null && number.isFinite()) { "无效数字" }
                return Node { number }
            }
            while (pos < source.length && (source[pos].isLetter() || source[pos] == '_')) pos++
            require(pos > start) { "表达式第 ${pos + 1} 位缺少数字或变量" }
            val name = source.substring(start, pos).lowercase()
            if (peek() == '(') {
                pos++
                val args = mutableListOf<Node>()
                if (peek() != ')') {
                    do {
                        args += expression()
                        if (peek() != ',') break
                        pos++
                    } while (true)
                }
                require(peek() == ')') { "$name 缺少右括号" }
                pos++
                return when (name) {
                    "min" -> { require(args.size == 2) { "min 需要两个参数" }; Node { v -> minOf(args[0].value(v), args[1].value(v)) } }
                    "max" -> { require(args.size == 2) { "max 需要两个参数" }; Node { v -> maxOf(args[0].value(v), args[1].value(v)) } }
                    "clamp" -> { require(args.size == 3) { "clamp 需要三个参数" }; Node { v -> args[0].value(v).coerceIn(args[1].value(v), args[2].value(v)) } }
                    else -> error("未知函数: $name")
                }
            }
            require(name in variables) { "未知变量: $name" }
            return Node { vars -> vars[name] ?: error("缺少变量 $name") }
        }

        private fun peek(): Char { spaces(); return source.getOrNull(pos) ?: '\u0000' }
        private fun spaces() { while (pos < source.length && source[pos].isWhitespace()) pos++ }
    }
}
