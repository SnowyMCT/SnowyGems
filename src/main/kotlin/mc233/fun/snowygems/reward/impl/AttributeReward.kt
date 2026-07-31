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
        val newValue = ExprUtil.eval(varExpr, current).let {
            // limit 对正负增益都是"绝对值上限", 所以按符号夹取
            when {
                limit == null -> it
                it >= 0 -> it.coerceAtMost(limit)
                else -> it.coerceAtLeast(limit)
            }
        }
        // 已达上限(或表达式算出的值没变): 视为未生效, 避免白吃宝石
        if (newValue == current) {
            return fail("已叠加值=$current 已达上限或无变化(limit=$limit)")
        }
        val op = AttributeModifier.Operation.entries.getOrNull(operation)
            ?: return fail("operation=$operation 越界, 只能是 0/1/2")

        val slotName = if (slot.equals("auto", true)) ItemRequireMatcher.autoSlot(item) else slot
        // 先清掉本插件之前写的同属性修饰符(新旧两种身份都清), 再写入新值
        val removed = AttributeCompat.removeOwn(meta, attribute, attrName)
        val modifier = AttributeCompat.create(attrName, newValue, op, slotName) ?: return false
        DebugUtil.log(
            "Reward",
            "    Attribute(${attribute.key.key}): $current -> $newValue (表达式=$varExpr limit=$limit " +
                "槽位=$slotName operation=$op 清理旧修饰符=$removed 条)"
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

    private fun failResolve(): Boolean = fail(
        AttributeAliases.candidatesOf(attrName)?.let { keys ->
            "属性(键=${keys.joinToString("/")}) 在当前版本 ${ServerVersion.minecraftVersion} 的注册表中不存在, " +
                "可能需要更高版本的服务端"
        } ?: "无法识别的属性名. 可用简写: ${AttributeAliases.knownAliases().sorted()}"
    )
}
