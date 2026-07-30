package mc233.`fun`.snowygems.reward.impl

import mc233.`fun`.snowygems.reward.Reward
import mc233.`fun`.snowygems.reward.RewardContext
import mc233.`fun`.snowygems.reward.RewardPhase
import mc233.`fun`.snowygems.util.ColorUtil
import mc233.`fun`.snowygems.util.DebugUtil
import mc233.`fun`.snowygems.util.ExprUtil
import mc233.`fun`.snowygems.util.LoreUtil
import taboolib.module.nms.ItemTagData
import taboolib.module.nms.getItemTag
import java.security.MessageDigest

private fun withMeta(ctx: RewardContext, block: (org.bukkit.inventory.meta.ItemMeta, MutableList<String>) -> Unit): Boolean {
    val item = ctx.item ?: run {
        DebugUtil.log("Reward", "    Lore 类奖励失败: 本次操作没有目标物品")
        return false
    }
    val meta = item.itemMeta ?: return false
    val lore = (meta.lore ?: mutableListOf()).toMutableList()
    val before = lore.size
    block(meta, lore)
    DebugUtil.log("Reward", "    Lore 行数 $before -> ${lore.size}")
    meta.lore = lore
    item.itemMeta = meta
    ctx.item = item
    return true
}

/** LoreAdd{lore=...;mode=after|before|line;locator=...;limit=;force=} */
class LoreAddReward(
    private val lore: String,
    private val locator: String?,
    private val mode: String?,
    private val limit: Int,
    private val force: Boolean
) : Reward {
    override fun apply(ctx: RewardContext): Boolean = withMeta(ctx) { _, list ->
        val line = ColorUtil.colorize(lore)
        val loc = locator?.let { ColorUtil.colorize(it) }
        DebugUtil.log("Reward", "    LoreAdd: 内容=\"$lore\" mode=$mode locator=$locator limit=$limit force=$force")
        LoreUtil.add(list, line, loc, mode, limit, force)
    }
}

/** LoreReplace{old=...;new=... 或 lore=...;mode=line;locator=...} */
class LoreReplaceReward(
    private val old: String,
    private val new: String,
    private val locator: String?
) : Reward {
    override fun apply(ctx: RewardContext): Boolean = withMeta(ctx) { _, list ->
        DebugUtil.log("Reward", "    LoreReplace: \"$old\" -> \"$new\" locator=$locator")
        LoreUtil.replace(list, ColorUtil.colorize(old), ColorUtil.colorize(new), locator?.let { ColorUtil.colorize(it) })
    }
}

/**
 * LoreVar{lore=前缀文本;locator=;mode=;var=v+1;inv=v-1;format=%.2f%%}
 * 数值以 NBT 形式持久化, 支持 APPLY 时按 var 计算, REMOVE 时按 inv 计算.
 */
class LoreVarReward(
    private val prefix: String,
    private val locator: String?,
    private val mode: String?,
    private val varExpr: String,
    private val invExpr: String?,
    private val format: String
) : Reward {

    override fun apply(ctx: RewardContext): Boolean {
        val item = ctx.item ?: return false
        val meta = item.itemMeta ?: return false
        val tag = item.getItemTag()
        val key = "SnowyGemsLoreVar_" + md5(prefix)
        val current = tag[key]?.asDouble() ?: 0.0
        val expr = if (ctx.phase == RewardPhase.REMOVE) (invExpr ?: varExpr) else varExpr
        val newValue = ExprUtil.eval(expr, current)
        DebugUtil.log(
            "Reward",
            "    LoreVar(前缀=\"$prefix\"): 阶段=${ctx.phase} 旧值=$current 表达式=$expr -> 新值=$newValue format=$format"
        )
        val list = (meta.lore ?: mutableListOf()).toMutableList()
        val coloredPrefix = ColorUtil.colorize(prefix)
        if (newValue == 0.0 && ctx.phase == RewardPhase.REMOVE) {
            LoreUtil.removeLineStartingWith(list, coloredPrefix)
        } else {
            val rendered = coloredPrefix + formatNumber(newValue) + ColorUtil.colorize(trailingLiteral())
            LoreUtil.upsertVarLine(list, coloredPrefix, rendered, locator?.let { ColorUtil.colorize(it) }, mode)
        }
        meta.lore = list
        item.itemMeta = meta
        tag[key] = ItemTagData(newValue)
        tag.saveTo(item)
        ctx.item = item
        return true
    }

    private fun percentSpec(): String {
        val m = Regex("""%[\d.]*[a-zA-Z]""").find(format)
        return m?.value ?: "%.2f"
    }

    private fun trailingLiteral(): String {
        val spec = percentSpec()
        val idx = format.indexOf(spec)
        return if (idx < 0) "" else format.substring(idx + spec.length)
    }

    private fun formatNumber(v: Double): String {
        return try {
            String.format(percentSpec(), v)
        } catch (e: Exception) {
            v.toString()
        }
    }

    private fun md5(s: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(12)
    }
}

/** Name{name=...} 直接设置物品显示名称 */
class NameReward(private val name: String) : Reward {
    override fun apply(ctx: RewardContext): Boolean {
        val item = ctx.item ?: return false
        val meta = item.itemMeta ?: return false
        meta.setDisplayName(ColorUtil.colorize(name))
        item.itemMeta = meta
        ctx.item = item
        return true
    }
}
