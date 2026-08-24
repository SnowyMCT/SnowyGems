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
import taboolib.module.nms.ItemTagData
import taboolib.module.nms.getItemTag

/**
 * Attribute{name=health;operation=0;slot=auto;var=v+1;limit=5}
 *
 * 语义: 从 NBT 读出该属性已叠加的数值 v, 用 [varExpr] 算出新值(不超过 limit), 再用一个
 * 固定标识的 AttributeModifier 替换旧的 —— 于是数值可重复叠加也可回退
 *
 * 多版本: 属性名解析走 [Registries.attribute], 直接问服务端注册表, 因此 1.20.5+ 的
 * scale / block_interaction_range、1.21.2+ 的 submerged_mining_speed 以及后续版本
 * 新增的属性都无需改代码; 老版本上写了新属性只跳过这一条并提示
 * 修饰符的创建与清理走 [AttributeCompat], 兼容新旧两套构造函数
 */
class AttributeReward(
    private val attrName: String,
    private val operation: Int,
    private val slot: String,
    private val varExpr: String,
    private val limit: Double?
) : Reward {

    companion object {
        fun resolve(key: String): Attribute? = Registries.attribute(key)
    }

    override fun apply(ctx: RewardContext): Boolean {
        val item = ctx.item ?: return fail("本次操作没有目标物品(纯玩家类宝石不能用 Attribute)")
        val attribute = resolve(attrName) ?: return failResolve()
        val meta = item.itemMeta ?: return false

        val tag = item.getItemTag()
        val nbtKey = "SnowyGemsAttr_$attrName"
        val current = tag[nbtKey]?.asDouble() ?: 0.0
        val raw = ExprUtil.eval(varExpr, current)
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
        val op = AttributeModifier.Operation.entries.getOrNull(operation)
            ?: return fail("operation=$operation 越界, 只能是 0/1/2")

        val slotName = if (slot.equals("auto", true)) ItemRequireMatcher.autoSlot(item) else slot
        // ★ 关键: 一旦给物品写入任何显式 AttributeModifier, 原版会停止应用该物品的"默认属性"
        //   (下界合金胸甲自带的护甲/韧性/击退抗性). 所以在写入我们的修饰符之前, 先把物品原本的
        //   默认属性显式固化进 meta, 否则镶嵌生命宝石后护甲/韧性会凭空消失.
        val preserved = AttributeCompat.preserveDefaultsIfNeeded(item, meta, slotName)
        // 先清掉本插件之前写的同属性修饰符(新旧两种身份都清), 再写入新值
        val removed = AttributeCompat.removeOwn(meta, attribute, attrName)
        val modifier = AttributeCompat.create(attrName, newValue, op, slotName) ?: return false
        DebugUtil.log(
            "Reward",
            "    Attribute(${attribute.key.key}): $current -> $newValue (表达式=$varExpr limit=$limit " +
                "槽位=$slotName operation=$op 清理旧修饰符=$removed 条 固化默认属性=$preserved 条)"
        )
        meta.addAttributeModifier(attribute, modifier)
        item.itemMeta = meta
        tag[nbtKey] = ItemTagData(newValue)
        tag.saveTo(item)
        ctx.item = item
        return true
    }

    private fun fail(reason: String): Boolean {
        DebugUtil.log("Reward", "    Attribute($attrName) 未生效: $reason")
        return false
    }

    /**
     * 拆卸时撤销: 清掉本插件为该属性写的修饰符, 并抹掉 NBT 累计值.
     * 这样同名属性(如 move)下次镶嵌会从 0 重新累计, 不会残留.
     */
    override fun revert(ctx: RewardContext): Boolean {
        val item = ctx.item ?: return false
        val attribute = resolve(attrName) ?: return false
        val meta = item.itemMeta ?: return false
        val removed = AttributeCompat.removeOwn(meta, attribute, attrName)
        item.itemMeta = meta
        val tag = item.getItemTag()
        val nbtKey = "SnowyGemsAttr_$attrName"
        val had = tag[nbtKey] != null
        if (had) {
            tag.remove(nbtKey)
            tag.saveTo(item)
        }
        ctx.item = item
        DebugUtil.log("Reward", "    Attribute($attrName) 撤销: 移除修饰符=$removed 条, 清NBT=$had")
        return removed > 0 || had
    }

    private fun failResolve(): Boolean = fail(
        AttributeAliases.candidatesOf(attrName)?.let { keys ->
            "属性(键=${keys.joinToString("/")}) 在当前版本 ${ServerVersion.minecraftVersion} 的注册表中不存在, " +
                "可能需要更高版本的服务端"
        } ?: "无法识别的属性名. 可用简写: ${AttributeAliases.knownAliases().sorted()}"
    )
}
