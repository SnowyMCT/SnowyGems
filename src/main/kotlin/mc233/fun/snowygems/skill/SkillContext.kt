package mc233.`fun`.snowygems.skill

import mc233.`fun`.snowygems.compat.Registries
import mc233.`fun`.snowygems.compat.ServerVersion
import mc233.`fun`.snowygems.util.DebugUtil
import org.bukkit.Location
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 一次技能执行的上下文 —— 技能函数能拿到的全部信息
 *
 * 之所以把参数收进一个对象而不是继续用长参数列表: 新增一个能力(比如"命中的实体""伤害值")
 * 时不需要改动每一个技能函数的签名, 服主自定义的函数也不会因为引擎升级而失效
 */
data class SkillContext(
    val player: Player,
    val item: ItemStack,
    val line: SkillLine,
    val trigger: String,
    val victim: LivingEntity? = null,
    val hitLocation: Location? = null
) {

    val target: LivingEntity
        get() = when (line.target.lowercase()) {
            "entity", "victim", "target" -> victim ?: player
            else -> player
        }

    val location: Location
        get() = hitLocation ?: player.location

    // ── 参数读取(统一入口, 支持 $LORE:标签?默认值$ 动态取值与算术表达式) ──

    fun str(vararg keys: String): String? = keys.firstNotNullOfOrNull { line.args[it] }

    fun num(vararg keys: String): Double? =
        str(*keys)?.let { SkillValue.resolveDouble(it, item, Double.NaN).takeUnless(Double::isNaN) }

    fun int(vararg keys: String): Int? = num(*keys)?.toInt()

    fun numOr(default: Double, vararg keys: String): Double = num(*keys) ?: default

    fun intOr(default: Int, vararg keys: String): Int = int(*keys) ?: default

    fun bool(vararg keys: String): Boolean? = when (str(*keys)?.lowercase()) {
        "true", "yes", "1", "on" -> true
        "false", "no", "0", "off" -> false
        else -> null
    }

    /** 第一个"裸参数"(没有 = 号的那个), 用于 Reward{Chat{...}} 这类嵌套写法 */
    fun nested(): String? = line.args.keys.firstOrNull { line.args[it] == it }

    // ── 日志 ────────────────────────────────────────────────

    fun log(message: String) = DebugUtil.log("SkillExec", "  ${line.name}: $message")

    /** 记一条"因为版本不支持而跳过"的日志 —— 多版本兼容的统一提示口径 */
    fun skipUnsupported(what: String, detail: String = "") = log(
        "跳过 —— $what 在当前版本 ${ServerVersion.minecraftVersion} 不可用" +
            if (detail.isBlank()) "" else " ($detail)"
    )

    // ── 版本安全的取值 ──────────────────────────────────────

    fun potionEffect(vararg keys: String) = str(*keys)?.let { name ->
        Registries.effect(name) ?: run {
            skipUnsupported("药水效果 $name", "该效果可能需要更高版本, 或名称拼写有误")
            null
        }
    }

    companion object {

        /**
         * 取实体的生命上限.
         *
         * getMaxHealth() 在新版本被 getAttribute(MAX_HEALTH) 取代, 所以先查注册表,
         * 取不到再退回已过时的 maxHealth —— 治疗和 If{c=health<x} 都要用, 收在这里免得各写一份
         */
        fun maxHealthOf(entity: LivingEntity): Double =
            Registries.attribute("max_health")?.let { entity.getAttribute(it)?.value }
                ?: @Suppress("DEPRECATION") entity.maxHealth

        /** 生命比例(0~1), 上限为 0 时返回 0 而不是 NaN */
        fun healthRatioOf(entity: LivingEntity): Double {
            val max = maxHealthOf(entity)
            return if (max > 0) entity.health / max else 0.0
        }
    }
}
