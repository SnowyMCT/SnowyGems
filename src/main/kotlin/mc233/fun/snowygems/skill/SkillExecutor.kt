package mc233.`fun`.snowygems.skill

import mc233.`fun`.snowygems.compat.ServerVersion
import mc233.`fun`.snowygems.skill.functions.BasicFunctions
import mc233.`fun`.snowygems.skill.functions.ControlFunctions
import mc233.`fun`.snowygems.skill.functions.ItemFunctions
import mc233.`fun`.snowygems.skill.functions.PotionFunctions
import mc233.`fun`.snowygems.util.DebugUtil
import org.bukkit.Location
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 技能执行器 —— 只负责查表和分派, 不含任何具体技能的实现。
 *
 * 老实现是一个 200 行的 `when (line.name.lowercase())`, 加函数就得改它, 服主也只能用
 * 我写死的那十几个。现在函数全部注册在 [SkillFunctions] 里, 这里只做三件事:
 *   1. 注册内置函数(分四组: 基础/药水/物品/控制流)
 *   2. 按名字查函数, 查不到给出拼写建议
 *   3. 版本不支持时给出明确提示而不是静默失败
 */
object SkillExecutor {

    /**
     * 注册内置技能函数.
     *
     * 刻意**不用** @Awake: 同一生命周期内多个 @Awake 方法的执行顺序不保证, 而配置自检
     * (ConfigValidator)必须在函数注册完之后才能跑, 否则会把所有函数误报成"未注册"。
     * 因此由 [mc233.fun.snowygems.Bootstrap.reloadAll] 显式按顺序调用。
     */
    fun registerBuiltins() {
        SkillFunctions.clear()
        BasicFunctions.registerAll()
        PotionFunctions.registerAll()
        ItemFunctions.registerAll()
        ControlFunctions.registerAll()
        val unavailable = SkillFunctions.unavailable()
        DebugUtil.log("SkillExec", "技能函数注册完成: 共 ${SkillFunctions.all().size} 个, ${unavailable.size} 个在当前版本不可用")
        if (unavailable.isNotEmpty()) {
            DebugUtil.log("SkillExec", "  不可用: ${unavailable.map { "${it.name}(${it.requires})" }}")
        }
    }

    /** 执行一行技能. 签名与老版一致, [SkillTriggerListener] / [BuffEngine] 无需改动 */
    fun execute(
        player: Player,
        item: ItemStack,
        line: SkillLine,
        victim: LivingEntity? = null,
        hitLocation: Location? = null,
        trigger: String = ""
    ): Boolean = dispatch(SkillContext(player, item, line, trigger, victim, hitLocation))

    /**
     * 执行一个嵌套函数字符串(如 `Chat{m=...}`), 复用调用方的上下文.
     *
     * 控制流(If/Chance/All/Delay/Repeat/Reward)和形态切换(RewardSwitch)都要这个能力,
     * 统一放这里, 免得每个函数域各写一份。
     */
    fun runNested(ctx: SkillContext, nestedRaw: String): Boolean =
        dispatch(ctx.copy(line = SkillLineParser.parse(nestedRaw)))

    /** 查表 → 检查版本 → 执行. 全部失败路径都留下可读的日志, 绝不静默 */
    private fun dispatch(ctx: SkillContext): Boolean {
        val line = ctx.line
        val function = SkillFunctions.find(line.name) ?: run {
            val suggestion = SkillFunctions.suggest(line.name)
            DebugUtil.log(
                "SkillExec",
                "未注册的技能函数: ${line.name}" +
                    if (suggestion.isEmpty()) " (用 /sgem skills 查看全部可用函数)"
                    else " —— 你是不是想写: ${suggestion.joinToString(" / ")}?"
            )
            return false
        }
        if (!function.isAvailable()) {
            DebugUtil.log(
                "SkillExec",
                "技能函数 ${function.name} 在当前版本 ${ServerVersion.minecraftVersion} 不可用" +
                    if (function.requires.isBlank()) "" else ": ${function.requires}"
            )
            return false
        }
        DebugUtil.log("SkillExec", "执行 ${function.name} 给 ${ctx.player.name} (target=${line.target} args=${line.args})")
        return runCatching { function.execute(ctx) }
            .onFailure { DebugUtil.err("SkillExec", "执行 ${line.name} 时抛出异常", it) }
            .getOrDefault(false)
    }
}
