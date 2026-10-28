package mc233.`fun`.snowygems.reward.impl

import mc233.`fun`.snowygems.reward.Reward
import mc233.`fun`.snowygems.reward.RewardContext
import mc233.`fun`.snowygems.reward.RewardPhase
import mc233.`fun`.snowygems.reward.LoreMutation
import mc233.`fun`.snowygems.util.ColorUtil
import mc233.`fun`.snowygems.util.DebugUtil
import mc233.`fun`.snowygems.util.ExprUtil
import mc233.`fun`.snowygems.util.LoreUtil
import mc233.`fun`.snowygems.util.ItemTagData
import mc233.`fun`.snowygems.util.getItemTag
import java.security.MessageDigest

private fun withMeta(ctx: RewardContext, block: (org.bukkit.inventory.meta.ItemMeta, MutableList<String>) -> Unit): Boolean {
    val item = ctx.item ?: run {
        DebugUtil.log("Reward", "    Lore 类奖励失败: 本次操作没有目标物品")
        return false
    }
    val meta = item.itemMeta ?: return false
    val lore = (meta.lore ?: mutableListOf()).toMutableList()
    val before = lore.toList()
    block(meta, lore)
    if (before == lore) return false
    ctx.undoData.putAll(LoreMutation.capture(before, lore))
    DebugUtil.log("Reward", "    Lore 行数 ${before.size} -> ${lore.size}")
    meta.lore = lore
    item.itemMeta = meta
    ctx.item = item
    return true
}

private fun revertLore(ctx: RewardContext): Boolean {
    val item = ctx.item ?: return false
    val meta = item.itemMeta ?: return false
    val lines = meta.lore?.toMutableList() ?: return false
    if (!LoreMutation.revert(lines, ctx.undoData)) return false
    meta.lore = lines
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
    override val reversible = true
    override fun revert(ctx: RewardContext): Boolean = revertLore(ctx)
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
    override val reversible = true
    override fun revert(ctx: RewardContext): Boolean = revertLore(ctx)
    override fun apply(ctx: RewardContext): Boolean = withMeta(ctx) { _, list ->
        DebugUtil.log("Reward", "    LoreReplace: \"$old\" -> \"$new\" locator=$locator")
        LoreUtil.replace(list, ColorUtil.colorize(old), ColorUtil.colorize(new), locator?.let { ColorUtil.colorize(it) })
    }
}

/**
 * LoreVar{lore=前缀文本;locator=;mode=;var=v+1;inv=v-1;format=%.2f%%;limit=v<=3}
 * 数值以 NBT 形式持久化, 支持 APPLY 时按 var 计算, 拆卸时按记录的实际增量回退.
 *
 * limit 是"新值"的准入表达式: 求值 <= 0 表示已到上限, 本次不生效。
 * 因此 `limit: "v<=1"` = 该前缀的数值最多涨到 1。旧版本没读这个参数, 药水类 BUFF 会无限叠。
 *
 * 这一类奖励会真实改动物品(lore + NBT), 所以必须 `reversible = true`:
 * 否则 [GemManager.markApplied] 的"至少一条可撤销奖励"门槛会把它挡在已镶嵌列表之外 ——
 * 表现就是 /sgem inspect 看不到、拆卸台里也不出现(2026-10 实测的 bug)。
 */
class LoreVarReward(
    private val prefix: String,
    private val locator: String?,
    private val mode: String?,
    private val varExpr: String,
    private val invExpr: String?,
    private val format: String,
    private val limitExpr: String? = null
) : Reward {

    override val reversible = true

    companion object {
        /** NBT 键前缀; 同一 prefix 的多级宝石共用同一个键, 等级就是键上的数值 */
        const val KEY_PREFIX = "SnowyGemsLoreVar_"

        private const val EPSILON = 1.0e-9
        private const val UNDO_DELTA = "loreVarDelta"
        private const val UNDO_KEY = "loreVarKey"

        /** 某个 lore 前缀对应的 NBT 键(拆卸回退与旧物品补登记都要用) */
        fun keyOf(prefix: String): String = KEY_PREFIX + md5(prefix)

        /** limit 表达式按**新值**求值; 空表示不限制 */
        internal fun allowsLimit(limitExpr: String?, newValue: Double): Boolean =
            limitExpr.isNullOrBlank() || ExprUtil.eval(limitExpr, newValue) > 0.0

        private fun md5(s: String): String {
            val bytes = MessageDigest.getInstance("MD5").digest(s.toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }.take(12)
        }
    }

    override fun apply(ctx: RewardContext): Boolean {
        val item = ctx.item ?: return false
        val meta = item.itemMeta ?: return false
        val tag = item.getItemTag()
        val key = keyOf(prefix)
        val current = tag[key]?.asDouble() ?: 0.0
        val expr = if (ctx.phase == RewardPhase.REMOVE) (invExpr ?: varExpr) else varExpr
        val newValue = ExprUtil.eval(expr, current)
        DebugUtil.log(
            "Reward",
            "    LoreVar(前缀=\"$prefix\"): 阶段=${ctx.phase} 旧值=$current 表达式=$expr -> 新值=$newValue " +
                "format=$format limit=$limitExpr"
        )
        if (!current.isFinite() || !newValue.isFinite()) return false
        if (ctx.phase != RewardPhase.REMOVE && !allowsLimit(limitExpr, newValue)) {
            DebugUtil.log("Reward", "    LoreVar(前缀=\"$prefix\"): 新值 $newValue 不满足上限表达式 $limitExpr, 本次不生效")
            return false
        }
        // 数值没变(比如已经 2 级又用了一颗"最多 2 级"的药水): 视为未生效, 不消耗宝石, 也不留一条 delta=0 的记录
        if (ctx.phase != RewardPhase.REMOVE && newValue == current) {
            DebugUtil.log("Reward", "    LoreVar(前缀=\"$prefix\"): 数值无变化($current), 视为未生效")
            return false
        }
        val list = (meta.lore ?: mutableListOf()).toMutableList()
        val coloredPrefix = ColorUtil.colorize(prefix)
        if (newValue == 0.0 && ctx.phase == RewardPhase.REMOVE) {
            LoreUtil.removeLineStartingWith(list, coloredPrefix)
            tag.remove(key)
        } else {
            val rendered = coloredPrefix + formatNumber(newValue) + ColorUtil.colorize(trailingLiteral())
            LoreUtil.upsertVarLine(list, coloredPrefix, rendered, locator?.let { ColorUtil.colorize(it) }, mode)
            tag[key] = ItemTagData(newValue)
        }
        meta.lore = list
        item.itemMeta = meta
        tag.saveTo(item)
        ctx.undoData[UNDO_DELTA] = (newValue - current).toString()
        ctx.undoData[UNDO_KEY] = key
        ctx.item = item
        return true
    }

    /**
     * 拆卸: 按镶嵌时记录的实际增量回退(多级宝石各自只减自己加的那部分)。
     * 老物品没有撤销记录时按 inv 表达式(默认 v-1)退回一颗, 归零则整行删掉。
     */
    override fun revert(ctx: RewardContext): Boolean {
        val item = ctx.item ?: return false
        val meta = item.itemMeta ?: return false
        val tag = item.getItemTag()
        val key = ctx.undoData[UNDO_KEY] ?: keyOf(prefix)
        val current = tag[key]?.asDouble() ?: return false
        if (!current.isFinite()) return false
        val recorded = ctx.undoData[UNDO_DELTA]?.toDoubleOrNull()?.takeIf { it.isFinite() }
        val next = if (recorded != null) {
            current - recorded
        } else {
            // 没有历史: 用 inv 表达式算这一步该退多少(等于"按配置再撤一颗")
            val afterInv = ExprUtil.eval(invExpr ?: "v-1", current)
            if (!afterInv.isFinite()) return false
            afterInv
        }
        if (!next.isFinite()) return false
        val list = (meta.lore ?: mutableListOf()).toMutableList()
        val coloredPrefix = ColorUtil.colorize(prefix)
        val cleared = kotlin.math.abs(next) <= EPSILON
        if (cleared) {
            LoreUtil.removeLineStartingWith(list, coloredPrefix)
            tag.remove(key)
        } else {
            val rendered = coloredPrefix + formatNumber(next) + ColorUtil.colorize(trailingLiteral())
            LoreUtil.upsertVarLine(list, coloredPrefix, rendered, locator?.let { ColorUtil.colorize(it) }, mode)
            tag[key] = ItemTagData(next)
        }
        meta.lore = list
        item.itemMeta = meta
        tag.saveTo(item)
        ctx.item = item
        DebugUtil.log("Reward", "    LoreVar(前缀=\"$prefix\") 撤销: $current -> $next${if (cleared) " (整行移除)" else ""}")
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
}

/** Name{name=...} 直接设置物品显示名称 */
class NameReward(private val name: String) : Reward {

    override val reversible = true

    companion object {
        private const val UNDO_NAME = "nameBefore"
    }

    @Suppress("DEPRECATION")
    override fun apply(ctx: RewardContext): Boolean {
        val item = ctx.item ?: return false
        val meta = item.itemMeta ?: return false
        val rendered = ColorUtil.colorize(name)
        val before = if (meta.hasDisplayName()) meta.displayName else ""
        if (before == rendered) return false
        ctx.undoData[UNDO_NAME] = before
        meta.setDisplayName(rendered)
        item.itemMeta = meta
        ctx.item = item
        return true
    }

    @Suppress("DEPRECATION")
    override fun revert(ctx: RewardContext): Boolean {
        val item = ctx.item ?: return false
        val meta = item.itemMeta ?: return false
        val before = ctx.undoData[UNDO_NAME] ?: return false
        if (before.isEmpty()) meta.setDisplayName(null) else meta.setDisplayName(before)
        item.itemMeta = meta
        ctx.item = item
        return true
    }
}
