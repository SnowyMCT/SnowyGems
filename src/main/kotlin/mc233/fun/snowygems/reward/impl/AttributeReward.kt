package mc233.`fun`.snowygems.reward.impl

import mc233.`fun`.snowygems.reward.Reward
import mc233.`fun`.snowygems.reward.RewardContext
import mc233.`fun`.snowygems.util.DebugUtil
import mc233.`fun`.snowygems.util.ExprUtil
import mc233.`fun`.snowygems.util.ItemRequireMatcher
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.inventory.EquipmentSlot
import taboolib.module.nms.ItemTagData
import taboolib.module.nms.getItemTag
import java.util.*

/**
 * Attribute{name=health;operation=0;slot=auto;var=v+1;limit=5}
 * 语义: 从 NBT 中读取该属性已叠加的数值 v, 用 [var] 表达式算出新值(不超过 limit), 用一个固定
 * UUID(由 gem 属性名派生)的 AttributeModifier 替换旧的, 使数值可重复叠加且可回退.
 */
class AttributeReward(
    private val attrName: String,
    private val operation: Int,
    private val slot: String,
    private val varExpr: String,
    private val limit: Double?
) : Reward {

    companion object {
        private val NAME_MAP = mapOf(
            "health" to listOf("GENERIC_MAX_HEALTH", "MAX_HEALTH"),
            "move" to listOf("GENERIC_MOVEMENT_SPEED", "MOVEMENT_SPEED"),
            "damage" to listOf("GENERIC_ATTACK_DAMAGE", "ATTACK_DAMAGE"),
            "attack_speed" to listOf("GENERIC_ATTACK_SPEED", "ATTACK_SPEED"),
            "attack_knockback" to listOf("GENERIC_ATTACK_KNOCKBACK", "ATTACK_KNOCKBACK"),
            "luck" to listOf("GENERIC_LUCK", "LUCK"),
            "armor" to listOf("GENERIC_ARMOR", "ARMOR"),
            "armor_toughness" to listOf("GENERIC_ARMOR_TOUGHNESS", "ARMOR_TOUGHNESS"),
            "knockback_resistance" to listOf("GENERIC_KNOCKBACK_RESISTANCE", "KNOCKBACK_RESISTANCE")
        )

        fun resolve(key: String): Attribute? {
            val candidates = NAME_MAP[key] ?: listOf(key.uppercase())
            for (c in candidates) {
                try {
                    @Suppress("UNCHECKED_CAST")
                    return (Attribute::class.java.getField(c).get(null) as Attribute)
                } catch (ignored: Exception) {
                }
            }
            return null
        }
    }

    override fun apply(ctx: RewardContext): Boolean {
        val item = ctx.item ?: run {
            DebugUtil.log("Reward", "    Attribute($attrName) 失败: 本次操作没有目标物品(纯玩家类宝石不能用 Attribute)")
            return false
        }
        val attribute = resolve(attrName) ?: run {
            DebugUtil.log("Reward", "    Attribute 失败: 无法识别属性名 $attrName (可用简写: ${NAME_MAP.keys})")
            return false
        }
        val meta = item.itemMeta ?: return false
        val tag = item.getItemTag()
        val nbtKey = "SnowyGemsAttr_$attrName"
        val current = tag[nbtKey]?.asDouble() ?: 0.0
        var newValue = ExprUtil.eval(varExpr, current)
        if (limit != null) {
            newValue = if (newValue >= 0) newValue.coerceAtMost(limit) else newValue.coerceAtLeast(limit)
        }
        // 已达上限(或表达式算出的新值和当前值一致): 不再变化, 视为未生效, 避免白吃宝石
        if (newValue == current) {
            DebugUtil.log(
                "Reward",
                "    Attribute($attrName): 已叠加值=$current 已达上限或无变化(limit=$limit), 视为未生效"
            )
            return false
        }
        val equipSlot = resolveSlot(if (slot == "auto") ItemRequireMatcher.autoSlot(item) else slot)
        DebugUtil.log(
            "Reward",
            "    Attribute($attrName): 已叠加值=$current 表达式=$varExpr -> 新值=$newValue " +
                "limit=$limit 生效槽位=$equipSlot operation=${AttributeModifier.Operation.values()[operation]}"
        )
        val uuid = UUID.nameUUIDFromBytes("snowygems:attr:$attrName".toByteArray())
        // ⚠️ 版本敏感点: AttributeModifier(UUID, String, double, Operation, EquipmentSlot) 是较旧的构造函数,
        // 1.21.3+ 的 Paper/Spigot API 新增了基于 NamespacedKey + EquipmentSlotGroup 的构造函数并标记旧构造为
        // 过时(但截至 1.21.4 仍保留). 若编译时报错找不到该构造函数, 请改用新版构造函数并自行调整此处代码.
        // 移除旧的同名修饰符
        meta.getAttributeModifiers(attribute)?.forEach { entry ->
            if (entry.uniqueId == uuid) meta.removeAttributeModifier(attribute, entry)
        }
        val modifier = AttributeModifier(uuid, "snowygems:$attrName", newValue, AttributeModifier.Operation.values()[operation], equipSlot)
        meta.addAttributeModifier(attribute, modifier)
        item.itemMeta = meta
        tag[nbtKey] = ItemTagData(newValue)
        tag.saveTo(item)
        ctx.item = item
        return true
    }

    private fun resolveSlot(name: String): EquipmentSlot {
        return when (name.lowercase()) {
            "head" -> EquipmentSlot.HEAD
            "chest" -> EquipmentSlot.CHEST
            "legs" -> EquipmentSlot.LEGS
            "feet" -> EquipmentSlot.FEET
            "off_hand", "offhand" -> EquipmentSlot.OFF_HAND
            else -> EquipmentSlot.HAND
        }
    }
}
