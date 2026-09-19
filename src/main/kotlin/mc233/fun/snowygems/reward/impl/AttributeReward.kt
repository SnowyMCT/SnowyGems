package mc233.`fun`.snowygems.reward.impl

import mc233.`fun`.snowygems.compat.AttributeAliases
import mc233.`fun`.snowygems.compat.AttributeCompat
import mc233.`fun`.snowygems.compat.Registries
import mc233.`fun`.snowygems.compat.ServerVersion
import mc233.`fun`.snowygems.reward.Reward
import mc233.`fun`.snowygems.reward.RewardContext
import mc233.`fun`.snowygems.util.DebugUtil
import mc233.`fun`.snowygems.util.ExprUtil
import mc233.`fun`.snowygems.util.ItemRequireMatcher
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.inventory.meta.ItemMeta
import mc233.`fun`.snowygems.util.ItemData
import mc233.`fun`.snowygems.util.ItemTagData
import mc233.`fun`.snowygems.util.ItemTagList
import mc233.`fun`.snowygems.util.getItemTag

/**
 * Attribute{name=health;operation=0;slot=auto;var=v+1;limit=5}
 *
 * 语义: 从 NBT 读出本插件在这件物品上为该属性已累计加的值 v, 用 [varExpr] 算出新值(不超过 limit),
 * 再用一个固定标识的 AttributeModifier 替换旧的 —— 于是数值可重复叠加也可回退
 *
 * ★ 与装备自带的同名属性合并(本次修复)
 *   下界合金胸甲自带 护甲+8 / 盔甲韧性+3 / 击退抗性+0.1, 这些是原版按材质给物品的属性修饰符.
 *   一旦物品上出现显式修饰符, 原版就不再自动套用这层自带属性, 所以写入前会先用
 *   [AttributeCompat.preserveDefaultsIfNeeded] 把它固化进物品 —— 于是物品上已经有了"盔甲韧性 +3"这一条.
 *   修复前宝石只再挂一条自己的修饰符, 属性就变成两条(自带 +3 与宝石 +4), 看着像"没在原有数值上加".
 *   现在: 镶嵌前把装备自带的同类修饰符(同属性/同计算方式/同槽位)合并进本插件那一条, 物品上始终只有一条,
 *   数值 = 自带 + 宝石累计; 被合并掉的自带修饰符以身份备份写进 NBT, 拆卸时原样还回去.
 *   v 与 limit 仍只针对"宝石累计加的值"(不含自带), 已调好的 limit 数值不受影响.
 *
 * 多版本: 属性名解析走 [Registries.attribute], 直接问服务端注册表, 因此 1.20.5+ 的
 * scale / block_interaction_range、1.21.2+ 的 submerged_mining_speed 以及后续版本
 * 新增的属性都无需改代码; 老版本上写了新属性只跳过这一条并提示
 * 修饰符的创建、清理与自带属性合并走 [AttributeCompat], 兼容新旧两套构造函数
 */
class AttributeReward(
    private val attrName: String,
    private val operation: Int,
    private val slot: String,
    private val varExpr: String,
    private val limit: Double?
) : Reward {
    override val reversible = true

    companion object {
        fun resolve(key: String): Attribute? = Registries.attribute(key)

        /** 装备自带属性的备份键前缀, 后面接修饰符 id(与 SnowyGemsAttr_ 一一对应) */
        private const val BASE_KEY_PREFIX = "SnowyGemsAttrBase_"

        /** 判定"数值没变"的容差, 与 [revert] 保持一致 */
        private const val EPSILON = 1.0e-10

        /**
         * 自带基数 = 上次镶嵌存下的备份 + 这次在物品上发现的同类修饰符, 同一个身份只保留一次.
         * 少了这层去重, 每镶一颗宝石就会把同一份自带属性再算一遍, 数值越滚越大
         */
        internal fun mergeBaseline(
            stored: List<AttributeModifier>,
            discovered: List<AttributeModifier>
        ): List<AttributeModifier> {
            val seen = HashSet<String>()
            val out = ArrayList<AttributeModifier>()
            for (modifier in stored + discovered) {
                val text = AttributeCompat.describe(modifier) ?: continue
                if (seen.add(text)) out.add(modifier)
            }
            return out
        }
    }

    override fun apply(ctx: RewardContext): Boolean {
        val item = ctx.item ?: return fail("本次操作没有目标物品(纯玩家类宝石不能用 Attribute)")
        val attribute = resolve(attrName) ?: return failResolve()
        val meta = item.itemMeta ?: return false
        val op = AttributeModifier.Operation.entries.getOrNull(operation)
            ?: return fail("operation=$operation 越界, 只能是 0/1/2")

        val tag = item.getItemTag()
        val slotName = canonicalSlot(if (slot.equals("auto", true)) ItemRequireMatcher.autoSlot(item) else slot)
        // 新物品按属性、计算方式和装备槽分别累计，避免不同 operation/slot 的宝石覆盖彼此。
        // 旧物品保留原键，避免升级时突然失去已有强化。
        val legacyKey = "SnowyGemsAttr_$attrName"
        val modifierId = if (tag[legacyKey] != null) attrName else
            "${attribute.key}_${operation}_$slotName".replace(Regex("[^A-Za-z0-9_]"), "_")
        val nbtKey = if (tag[legacyKey] != null) legacyKey else "SnowyGemsAttr_$modifierId"
        val baseKey = BASE_KEY_PREFIX + modifierId
        // current 是"宝石累计加的值", 不含装备自带; 镶嵌结果 = 自带基数 + 这个值
        val current = tag[nbtKey]?.asDouble() ?: 0.0
        val raw = ExprUtil.eval(varExpr, current)
        if (!current.isFinite() || !raw.isFinite() || limit?.isFinite() == false) return fail("属性数值不是有限数")
        // limit 是同方向的绝对值上限: 仅当表达式结果与 limit 同号(同方向增益)才夹取.
        // 异号说明是方向相反的宝石在叠加(如先缩小再放大), limit 不适用, 不夹取.
        val newValue = when {
            limit == null -> raw
            raw > 0 && limit > 0 -> raw.coerceAtMost(limit)
            raw < 0 && limit < 0 -> raw.coerceAtLeast(limit)
            else -> raw
        }
        // 防降级: 同一属性(如 move)被多种宝石共享一份累计值. 若本宝石的 limit 比当前累计值更小,
        // 夹取会把它拉回 —— 表现为"先用神速度到30%, 再用真速度反而掉到15%".
        // 判定: 只有夹取真正发生(newValue != raw)才可能降级, 夹取后相对当前值变弱才拒绝.
        // 从 0 开始镶负增益宝石(如 scale 缩小)时 raw 未被夹取, 属正常生效, 不是降级.
        if (limit != null && newValue != raw) {
            val weakened = if (raw >= 0) newValue < current else newValue > current
            if (weakened) {
                return fail("本宝石上限 $limit 低于当前累计值 $current, 夹取将回退到 $newValue, 视为未生效")
            }
        }
        // 已达上限(或表达式算出的值没变): 视为未生效, 避免白吃宝石
        if (newValue == current) {
            return fail("已叠加值=$current 已达上限或无变化(limit=$limit)")
        }

        // ★ 关键: 原版物品的自带属性(护甲/韧性/击退抗性)只要物品上还没有显式修饰符就由材质提供,
        //   一旦写入我们的修饰符就会失效. 所以先把它固化进 meta(用 NBT 标记去重, 只做一次),
        //   而固化下来的那一条紧接着会被下面的合并逻辑并进宝石这一条, 不会留下两条同名属性
        val preserved = AttributeCompat.preserveDefaultsIfNeeded(item, meta, slotName)
        // ★ 装备自带的同类属性: 合并进本插件这一条, 物品上只留一条同名属性
        //   (首次镶嵌时从物品上发现, 之后从 NBT 备份里读, 中途别人往物品上加的同类属性也会被并进来)
        val baseline = attributeBaseline(tag, baseKey, meta, attribute, op, slotName, modifierId)
        val baseValue = baseline.sumOf { it.amount }
        val merged = baseValue + newValue
        if (!merged.isFinite()) return fail("装备自带值 $baseValue 与宝石新值 $newValue 合并后不是有限数")

        // 先清掉本插件之前写的同属性修饰符(新旧两种身份都清)与要合并的自带属性, 再写入合并后的新值
        val removed = AttributeCompat.removeOwn(meta, attribute, modifierId)
        var absorbed = 0
        for (known in baseline) {
            if (runCatching { meta.removeAttributeModifier(attribute, known) }.getOrDefault(false)) absorbed++
        }
        val modifier = AttributeCompat.create(modifierId, merged, op, slotName)
            ?: return fail("无法创建属性修饰符")
        DebugUtil.log(
            "Reward",
            "    Attribute(${attribute.key.key}): 宝石累计 $current -> $newValue, 物品上写入 $merged" +
                "(装备自带 $baseValue) 表达式=$varExpr limit=$limit 槽位=$slotName operation=$op " +
                "合并自带属性=$absorbed 条 清理旧修饰符=$removed 条 固化默认属性=$preserved 条"
        )
        if (!meta.addAttributeModifier(attribute, modifier)) return fail("服务端拒绝添加属性修饰符")
        item.itemMeta = meta
        // 自带属性的备份: 拆卸时按原身份还回去, 所以这里连键名一起记
        val backup = baseline.mapNotNull { AttributeCompat.describe(it) }
        item.getItemTag().apply {
            this[nbtKey] = ItemTagData(newValue)
            if (backup.isEmpty()) remove(baseKey)
            else this[baseKey] = ItemTagList().apply { backup.forEach { add(ItemTagData(it)) } }
            saveTo(item)
        }
        ctx.undoData["delta"] = (newValue - current).toString()
        ctx.undoData["nbtKey"] = nbtKey
        ctx.undoData["modifierId"] = modifierId
        ctx.undoData["slot"] = slotName
        ctx.item = item
        return true
    }

    private fun fail(reason: String): Boolean {
        DebugUtil.log("Reward", "    Attribute($attrName) 未生效: $reason")
        return false
    }

    /**
     * 拆卸时撤销: 只剩这一颗宝石的贡献被扣掉后, 把装备自带属性原样还原;
     * 若同属性还有别的宝石贡献, 就继续保留合并后的那一条.
     * NBT 累计值归零时一并抹掉, 这样下次镶嵌会从 0 重新累计, 不会残留.
     */
    override fun revert(ctx: RewardContext): Boolean {
        val item = ctx.item ?: return false
        val attribute = resolve(attrName) ?: error("Cannot resolve attribute for removal: $attrName")
        val meta = item.itemMeta ?: return false
        val op = AttributeModifier.Operation.entries.getOrNull(operation) ?: error("Invalid operation")
        val slotName = canonicalSlot(ctx.undoData["slot"] ?: slot)
        val delta = ctx.undoData["delta"]?.toDoubleOrNull()
        val nbtKey = ctx.undoData["nbtKey"] ?: "SnowyGemsAttr_$attrName"
        val modifierId = ctx.undoData["modifierId"] ?: attrName
        val baseKey = BASE_KEY_PREFIX + modifierId
        val tag = item.getItemTag()
        val current = tag[nbtKey]?.asDouble() ?: 0.0
        val next = if (delta == null) 0.0 else current - delta
        require(next.isFinite()) { "Invalid stored attribute contribution" }
        // 镶嵌时被合并掉的装备自带属性
        val baseline = storedBaseline(tag, baseKey, op, slotName)
        val baseValue = baseline.sumOf { it.amount }
        val removed = AttributeCompat.removeOwn(meta, attribute, modifierId)
        val cleared = kotlin.math.abs(next) <= EPSILON
        if (cleared) {
            // 该属性上已无宝石贡献: 让装备自带属性回到原样
            for (known in baseline) {
                runCatching { meta.addAttributeModifier(attribute, known) }
                    .onFailure { DebugUtil.log("Reward", "    还原装备自带属性失败: ${it.message}") }
            }
        } else {
            val total = baseValue + next
            require(total.isFinite()) { "Invalid merged attribute value" }
            val modifier = AttributeCompat.create(modifierId, total, op, slotName)
                ?: error("Cannot restore attribute modifier")
            check(meta.addAttributeModifier(attribute, modifier)) { "Cannot restore attribute modifier" }
        }
        item.itemMeta = meta
        val had = tag[nbtKey] != null
        item.getItemTag().apply {
            if (cleared) {
                remove(nbtKey)
                remove(baseKey)
            } else {
                this[nbtKey] = ItemTagData(next)
            }
            saveTo(item)
        }
        ctx.item = item
        DebugUtil.log(
            "Reward",
            "    Attribute($attrName) 撤销: 移除修饰符=$removed 条, 宝石累计 $current -> $next, " +
                "装备自带=$baseValue(${baseline.size} 条${if (cleared) ", 已还原" else ", 仍合并"}) 清NBT=$had"
        )
        return removed > 0 || had
    }

    /**
     * 该属性在物品上的"自带基数":
     *   NBT 备份(镶嵌时合并下来的) + 物品上现存的非本插件同类修饰符(第一次镶嵌, 或中途有新来源写入)
     * 同一份修饰符只算一次, 免得反复镶嵌把基数越滚越大
     */
    private fun attributeBaseline(
        tag: ItemData,
        baseKey: String,
        meta: ItemMeta,
        attribute: Attribute,
        op: AttributeModifier.Operation,
        slotName: String,
        modifierId: String
    ): List<AttributeModifier> =
        mergeBaseline(
            storedBaseline(tag, baseKey, op, slotName),
            // 本插件自己写过的标识(新版 modifierId 与旧版属性名)都排除掉, 别把自己的旧修饰符当成自带属性
            AttributeCompat.baselineModifiers(meta, attribute, op, slotName, modifierId, attrName)
        )

    /** 读 NBT 里的自带属性备份(拆不开的行直接丢弃, 不影响本次镶嵌) */
    private fun storedBaseline(
        tag: ItemData,
        baseKey: String,
        op: AttributeModifier.Operation,
        slotName: String
    ): List<AttributeModifier> {
        val list = tag[baseKey]?.value as? ItemTagList ?: return emptyList()
        return list.mapNotNull { AttributeCompat.rebuild(it.asString(), op, slotName) }
    }

    private fun canonicalSlot(name: String): String = when (name.trim().lowercase()) {
        "head", "helmet" -> "head"
        "chest", "chestplate" -> "chest"
        "legs", "leggings" -> "legs"
        "feet", "boots" -> "feet"
        "off_hand", "offhand" -> "off_hand"
        "main_hand", "mainhand", "hand" -> "hand"
        "any_hand", "hands" -> "hands"
        "armor" -> "armor"
        else -> "any"
    }

    private fun failResolve(): Boolean = fail(
        AttributeAliases.candidatesOf(attrName)?.let { keys ->
            "属性(键=${keys.joinToString("/")}) 在当前版本 ${ServerVersion.minecraftVersion} 的注册表中不存在, " +
                "可能需要更高版本的服务端"
        } ?: "无法识别的属性名. 可用简写: ${AttributeAliases.knownAliases().sorted()}"
    )
}
