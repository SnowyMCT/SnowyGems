package mc233.`fun`.snowygems.skill.functions

import mc233.`fun`.snowygems.skill.SkillContext
import mc233.`fun`.snowygems.skill.SkillExecutor
import mc233.`fun`.snowygems.skill.SkillFunctions
import mc233.`fun`.snowygems.skill.skillFunction
import taboolib.common.platform.function.submit
import taboolib.common.util.random

/**
 * 控制流类技能函数 —— 让服主能写出有条件、有组合的技能, 而不只是一串平铺的效果
 *
 * 这一层是"技能引擎足够聪明"的关键: 有了条件判断和组合执行, 服主可以自己拼出
 * "血量低于 30% 时才触发""每 3 次攻击触发一次""同时放三个效果"这类逻辑,
 * 不需要我为每种玩法单独写一个函数
 *
 * 嵌套执行统一走 [SkillExecutor.runNested], 因此嵌套里也能再套控制流
 */
object ControlFunctions {

    fun registerAll() {
        SkillFunctions.register(skillFunction(
            name = "Reward",
            aliases = listOf("Do", "执行"),
            description = "执行一个嵌套函数, 用于把效果包一层(兼容老配置写法)",
            usage = "{Chat{m=&a触发!}}"
        ) { ctx -> withNested(ctx) { SkillExecutor.runNested(ctx, it) } })

        SkillFunctions.register(skillFunction(
            name = "All",
            aliases = listOf("Group", "组合"),
            description = "依次执行多个函数, 全部执行完才结束",
            usage = "{Damage{amount=5};Fire{ticks=60};Sound{name=ENTITY_BLAZE_SHOOT}}"
        ) { ctx ->
            // 注意用 count 而不是 any{}: any 会短路, 后面的效果就不放了
            ctx.line.args.keys.count { ctx.line.args[it] == it && SkillExecutor.runNested(ctx, it) } > 0
        })

        SkillFunctions.register(skillFunction(
            name = "Chance",
            aliases = listOf("Random", "概率"),
            description = "按概率执行嵌套函数",
            usage = "{p=0.3;Damage{amount=10}}  p 为 0~1 的概率"
        ) { ctx ->
            val chance = ctx.numOr(1.0, "p", "chance", "probability")
            withNested(ctx) { nested ->
                // random() 是 TabooLib 的线程安全随机数
                if (!random(chance)) {
                    ctx.log("概率 $chance 未命中, 跳过")
                    false
                } else {
                    ctx.log("概率 $chance 命中")
                    SkillExecutor.runNested(ctx, nested)
                }
            }
        })

        SkillFunctions.register(skillFunction(
            name = "If",
            aliases = listOf("Condition", "条件"),
            description = "条件成立时才执行. 可比较自己/目标的生命、饱食度、经验、高度",
            usage = "{c=health<0.3;Heal{amount=10}}  变量见下方说明"
        ) { ctx ->
            val condition = ctx.str("c", "condition") ?: return@skillFunction ctx.miss("c 条件参数")
            withNested(ctx) { nested ->
                if (!evaluate(condition, ctx)) false else SkillExecutor.runNested(ctx, nested)
            }
        })

        SkillFunctions.register(skillFunction(
            name = "Delay",
            aliases = listOf("延迟"),
            description = "延迟若干 tick 后执行嵌套函数, 可做连招/持续效果",
            usage = "{t=20;Damage{amount=5}}  20 tick = 1 秒"
        ) { ctx ->
            val ticks = ctx.intOr(20, "t", "ticks", "delay").toLong()
            withNested(ctx) { nested ->
                ctx.log("延迟 $ticks tick 后执行")
                submit(delay = ticks) { runIfOnline(ctx, nested) }
                true
            }
        })

        SkillFunctions.register(skillFunction(
            name = "Repeat",
            aliases = listOf("循环"),
            description = "重复执行嵌套函数, 可指定间隔",
            usage = "{times=3;interval=10;Damage{amount=2}}"
        ) { ctx ->
            // 上限 100: 防止服主手滑写 times=99999 把主线程卡死
            val times = ctx.intOr(1, "times", "n").coerceIn(1, 100)
            val interval = ctx.intOr(0, "interval", "i").toLong()
            withNested(ctx) { nested ->
                ctx.log("重复 $times 次, 间隔 $interval tick")
                if (interval <= 0) {
                    repeat(times) { SkillExecutor.runNested(ctx, nested) }
                } else {
                    repeat(times) { i -> submit(delay = interval * i) { runIfOnline(ctx, nested) } }
                }
                true
            }
        })
    }

    private inline fun withNested(ctx: SkillContext, body: (String) -> Boolean): Boolean {
        val nested = ctx.nested() ?: return ctx.miss("嵌套函数")
        return body(nested)
    }

    private fun runIfOnline(ctx: SkillContext, nested: String) {
        if (ctx.player.isOnline) SkillExecutor.runNested(ctx, nested)
    }

    private fun SkillContext.miss(what: String): Boolean {
        log("缺少 $what")
        return false
    }

    /** If 支持的条件变量: 名字 -> 取值方式 */
    private val VARIABLES: Map<String, (SkillContext) -> Double?> = mapOf(
        "health" to { c -> SkillContext.healthRatioOf(c.player) },
        "raw_health" to { c -> c.player.health },
        "max_health" to { c -> SkillContext.maxHealthOf(c.player) },
        "food" to { c -> c.player.foodLevel.toDouble() },
        "level" to { c -> c.player.level.toDouble() },
        "y" to { c -> c.player.location.y },
        "target_health" to { c -> c.victim?.let { SkillContext.healthRatioOf(it) } },
        "target_raw_health" to { c -> c.victim?.health }
    )

    private val CONDITION = Regex("""^\s*(\w+)\s*(>=|<=|==|!=|>|<)\s*(-?[\d.]+)\s*$""")

    /**
     * 条件求值. 语法: `<变量><运算符><数值>`
     *
     * 变量见 [VARIABLES]: health(生命比例 0~1) / raw_health / max_health / food /
     * level / y / target_health / target_raw_health
     * 运算符: `>` `>=` `<` `<=` `==` `!=`
     */
    private fun evaluate(condition: String, ctx: SkillContext): Boolean {
        val m = CONDITION.find(condition) ?: run {
            ctx.log("条件语法无法解析: $condition (正确写法如 health<0.3)")
            return false
        }
        val (name, op, valueStr) = m.destructured
        val expected = valueStr.toDoubleOrNull() ?: return false
        val getter = VARIABLES[name.lowercase()] ?: run {
            ctx.log("未知的条件变量: $name (可用: ${VARIABLES.keys.joinToString("/")})")
            return false
        }
        // target_* 在没有受害者时取不到值 —— 这不是错误, 只是条件不成立
        val actual = getter(ctx) ?: run {
            ctx.log("条件变量 $name 当前不可用(通常是这次触发没有目标实体)")
            return false
        }
        val result = when (op) {
            ">" -> actual > expected
            ">=" -> actual >= expected
            "<" -> actual < expected
            "<=" -> actual <= expected
            "==" -> actual == expected
            else -> actual != expected
        }
        ctx.log("条件 $name($actual) $op $expected -> $result")
        return result
    }
}
