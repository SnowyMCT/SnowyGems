package mc233.`fun`.snowygems.compat

import mc233.`fun`.snowygems.util.DebugUtil
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.EquipmentSlotGroup
import org.bukkit.inventory.meta.ItemMeta
import java.util.UUID

/**
 * AttributeModifier 的跨版本封装 —— 全项目唯一真正需要按版本分支的地方。
 *
 * API 变迁:
 *   1.20.4 及以前: AttributeModifier(UUID, String name, double, Operation, EquipmentSlot)
 *   1.20.5 起:     AttributeModifier(NamespacedKey, double, Operation, EquipmentSlotGroup)
 *                  旧构造被标记 @Deprecated, 但仍保留
 *   1.21.9 前后:   部分服务端实现开始移除旧构造 / 旧的 uniqueId 访问器
 *
 * 处理方式: **优先新 API, 反射探测可用性, 一次性缓存结果**。
 * 编译期只静态引用新 API(编译目标是 1.21.4, 新 API 已存在), 旧 API 全部走反射,
 * 这样既能在 1.20.5+ 全系列正常工作, 也不会因为旧构造被移除而在高版本上 NoSuchMethodError。
 *
 * 修饰符的身份标识:
 *   新 API 用 NamespacedKey("snowygems", "attr_<属性名>")
 *   旧 API 用由同一字符串派生的确定性 UUID
 * 两者都能保证"同一个宝石属性重复镶嵌时替换而不是叠加无数条"。
 */
object AttributeCompat {

    /** 新构造是否可用(1.20.5+). 探测一次即缓存 */
    private val modernAvailable: Boolean by lazy {
        runCatching {
            AttributeModifier::class.java.getConstructor(
                NamespacedKey::class.java,
                java.lang.Double.TYPE,
                AttributeModifier.Operation::class.java,
                EquipmentSlotGroup::class.java
            )
            true
        }.getOrElse {
            DebugUtil.log("Compat", "AttributeModifier 新构造(NamespacedKey+SlotGroup)不可用, 回退旧构造")
            false
        }
    }

    /** 旧构造(UUID+EquipmentSlot), 仅在新构造不可用时通过反射使用 */
    private val legacyConstructor by lazy {
        runCatching {
            AttributeModifier::class.java.getConstructor(
                UUID::class.java,
                String::class.java,
                java.lang.Double.TYPE,
                AttributeModifier.Operation::class.java,
                EquipmentSlot::class.java
            )
        }.getOrNull()
    }

    /** 本插件写入的修饰符统一用这个命名空间, 便于识别和清理 */
    private const val NAMESPACE = "snowygems"

    /** 某个属性对应的修饰符标识(字符串形式), 新旧 API 共用同一份来源 */
    private fun identifierOf(attrName: String) = "attr_${attrName.lowercase().replace(Regex("[^a-z0-9_]"), "_")}"

    private fun keyOf(attrName: String) = NamespacedKey(NAMESPACE, identifierOf(attrName))

    private fun uuidOf(attrName: String): UUID =
        UUID.nameUUIDFromBytes("$NAMESPACE:${identifierOf(attrName)}".toByteArray())

    /**
     * 移除本插件之前为该属性写入的修饰符.
     *
     * 新旧两种身份都要清: 玩家的装备可能是在插件用旧 API 的版本上镶嵌的,
     * 服务器升级后如果只按新 key 清理, 旧的 UUID 修饰符会永久残留并持续叠加。
     */
    fun removeOwn(meta: ItemMeta, attribute: Attribute, attrName: String): Int {
        val existing = meta.getAttributeModifiers(attribute) ?: return 0
        val key = keyOf(attrName)
        val uuid = uuidOf(attrName)
        var removed = 0
        for (modifier in existing) {
            val isOurs = runCatching { modifier.key == key }.getOrDefault(false) ||
                runCatching {
                    @Suppress("DEPRECATION")
                    modifier.uniqueId == uuid
                }.getOrDefault(false)
            if (isOurs) {
                meta.removeAttributeModifier(attribute, modifier)
                removed++
            }
        }
        return removed
    }

    /**
     * 创建一个修饰符. 新版本走 EquipmentSlotGroup, 老版本走 EquipmentSlot。
     * @param slotName 槽位名(head/chest/legs/feet/hand/off_hand/any/armor)
     * @return 失败返回 null, 由调用方按"未生效"处理
     */
    fun create(attrName: String, value: Double, operation: AttributeModifier.Operation, slotName: String): AttributeModifier? {
        if (modernAvailable) {
            return runCatching {
                AttributeModifier(keyOf(attrName), value, operation, slotGroupOf(slotName))
            }.onFailure {
                DebugUtil.err("Compat", "用新 API 创建 AttributeModifier 失败", it)
            }.getOrNull()
        }
        val ctor = legacyConstructor ?: run {
            DebugUtil.log("Compat", "AttributeModifier 新旧构造都不可用, 无法应用属性 $attrName")
            return null
        }
        return runCatching {
            ctor.newInstance(uuidOf(attrName), "$NAMESPACE:$attrName", value, operation, slotOf(slotName)) as AttributeModifier
        }.onFailure {
            DebugUtil.err("Compat", "用旧 API 创建 AttributeModifier 失败", it)
        }.getOrNull()
    }

    /**
     * 槽位名 -> EquipmentSlotGroup (1.20.5+).
     * 相比旧的 EquipmentSlot, Group 多了 ANY / ARMOR / HAND 这类"一组槽位"的语义,
     * 让"任意手持生效"和"任意盔甲位生效"这种配置成为可能。
     */
    fun slotGroupOf(name: String): EquipmentSlotGroup = when (name.trim().lowercase()) {
        "head", "helmet" -> EquipmentSlotGroup.HEAD
        "chest", "chestplate" -> EquipmentSlotGroup.CHEST
        "legs", "leggings" -> EquipmentSlotGroup.LEGS
        "feet", "boots" -> EquipmentSlotGroup.FEET
        "off_hand", "offhand" -> EquipmentSlotGroup.OFFHAND
        "main_hand", "mainhand", "hand" -> EquipmentSlotGroup.MAINHAND
        "any_hand", "hands" -> EquipmentSlotGroup.HAND
        "armor" -> EquipmentSlotGroup.ARMOR
        "any", "all" -> EquipmentSlotGroup.ANY
        // 未知写法按"任意槽位"处理, 比直接失败更符合服主预期
        else -> EquipmentSlotGroup.ANY
    }

    /** 槽位名 -> EquipmentSlot (旧 API 用). Group 独有的 any/armor 只能退化成主手 */
    fun slotOf(name: String): EquipmentSlot = when (name.trim().lowercase()) {
        "head", "helmet" -> EquipmentSlot.HEAD
        "chest", "chestplate" -> EquipmentSlot.CHEST
        "legs", "leggings" -> EquipmentSlot.LEGS
        "feet", "boots" -> EquipmentSlot.FEET
        "off_hand", "offhand" -> EquipmentSlot.OFF_HAND
        else -> EquipmentSlot.HAND
    }

    /** 供启动日志: 当前用的是哪套 API */
    fun describe(): String = if (modernAvailable) "AttributeModifier 使用现代 API(SlotGroup)" else "AttributeModifier 使用旧 API(EquipmentSlot)"
}
