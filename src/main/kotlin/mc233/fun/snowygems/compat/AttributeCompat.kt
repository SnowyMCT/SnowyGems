package mc233.`fun`.snowygems.compat

import mc233.`fun`.snowygems.util.DebugUtil
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.EquipmentSlotGroup
import org.bukkit.inventory.meta.ItemMeta
import taboolib.module.nms.ItemTagData
import taboolib.module.nms.getItemTag
import java.util.UUID

/**
 * AttributeModifier 的跨版本封装 —— 全项目唯一真正需要按版本分支的地方
 *
 * API 变迁:
 *   1.20.4 及以前: AttributeModifier(UUID, String name, double, Operation, EquipmentSlot)
 *   1.20.5 起:     AttributeModifier(NamespacedKey, double, Operation, EquipmentSlotGroup)
 *                  旧构造被标记 @Deprecated, 但仍保留
 *   1.21.9 前后:   部分服务端实现开始移除旧构造 / 旧的 uniqueId 访问器
 *
 * 处理方式: **优先新 API, 反射探测可用性, 一次性缓存结果**
 * 编译期只静态引用新 API(编译目标是 1.21.4, 新 API 已存在), 旧 API 全部走反射,
 * 这样既能在 1.20.5+ 全系列正常工作, 也不会因为旧构造被移除而在高版本上 NoSuchMethodError
 *
 * 修饰符的身份标识:
 *   新 API 用 NamespacedKey("snowygems", "attr_<属性名>")
 *   旧 API 用由同一字符串派生的确定性 UUID
 * 两者都能保证"同一个宝石属性重复镶嵌时替换而不是叠加无数条"
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
     * 服务器升级后如果只按新 key 清理, 旧的 UUID 修饰符会永久残留并持续叠加
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
     * 创建一个修饰符. 新版本走 EquipmentSlotGroup, 老版本走 EquipmentSlot
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
     * 让"任意手持生效"和"任意盔甲位生效"这种配置成为可能
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

    /**
     * 把物品的"默认属性"固化进 meta —— 修复"镶嵌属性宝石后, 装备自带护甲/韧性消失"的核心方法.
     *
     * 原理: Minecraft 的物品有两层属性:
     *   1) 隐式默认属性: 下界合金胸甲自带 +8 护甲 +3 韧性, 钻石剑自带攻击力等. 这些不在 NBT 里,
     *      是原版按材质动态附加的.
     *   2) 显式 AttributeModifier: 写进物品 NBT 的.
     * 原版规则: **一旦物品带有任何显式 AttributeModifier, 隐式默认属性全部不再生效**. 所以我们给
     * 盔甲加一条 max_health 修饰符后, 它自带的护甲/韧性就凭空没了.
     *
     * 解决: 在写入我们的修饰符之前, 若 meta 尚无任何属性修饰符, 就把该材质在对应槽位的全部默认属性
     * 显式拷进 meta. 之后再叠加我们自己的, 两者共存.
     *
     * 只在"首次给这件物品加修饰符"时做一次(用 NBT 标记去重), 避免重复镶嵌时反复累加默认属性.
     *
     * @return 固化的默认属性条数(0 表示无需固化或该材质无默认属性)
     */
    fun preserveDefaultsIfNeeded(item: org.bukkit.inventory.ItemStack, meta: ItemMeta, slotName: String): Int {
        // 已经固化过就不再重复(用一个专属 NBT 标记)
        val tag = runCatching { item.getItemTag() }.getOrNull()
        if (tag != null && tag[DEFAULTS_KEPT_KEY]?.asString() == "1") return 0
        // meta 已带显式修饰符: 说明要么之前已固化, 要么本就是自定义装备, 不动它, 只打标记.
        // hasAttributeModifiers() 返回 Boolean, 避开直接引用 Guava Multimap 类型(编译期不在 classpath).
        val hasModifiers = runCatching { meta.hasAttributeModifiers() }.getOrDefault(false)
        if (hasModifiers) {
            tag?.let { it[DEFAULTS_KEPT_KEY] = ItemTagData("1"); it.saveTo(item) }
            return 0
        }
        val defaults = defaultModifiersOf(item, slotName)
        if (defaults.isEmpty()) {
            // 该材质本就没有默认属性(如普通靴子除盔甲外无其它), 仍打标记避免每次都查
            tag?.let { it[DEFAULTS_KEPT_KEY] = ItemTagData("1"); it.saveTo(item) }
            return 0
        }
        var kept = 0
        for ((attr, mod) in defaults) {
            runCatching {
                meta.addAttributeModifier(attr, mod)
                kept++
            }.onFailure { DebugUtil.log("Compat", "固化默认属性 ${attr.key.key} 失败: ${it.message}") }
        }
        tag?.let { it[DEFAULTS_KEPT_KEY] = ItemTagData("1"); it.saveTo(item) }
        DebugUtil.log("Compat", "为 ${item.type} 固化了 $kept 条默认属性(槽位=$slotName), 防止原生护甲/韧性丢失")
        return kept
    }

    private const val DEFAULTS_KEPT_KEY = "SnowyGemsDefaultsKept"

    /**
     * 取某材质在指定槽位的默认属性修饰符.
     * 走 Paper 的 `Material.getDefaultAttributeModifiers(EquipmentSlot)`, 用反射调用以兼容不同版本:
     *   - 1.21.x Paper 有此方法, 返回 Multimap<Attribute, AttributeModifier>
     *   - 若方法不存在(极旧版本), 返回空表, 上层按"无默认属性"处理
     */
    @Suppress("UNCHECKED_CAST")
    private fun defaultModifiersOf(item: org.bukkit.inventory.ItemStack, slotName: String): List<Pair<Attribute, AttributeModifier>> {
        return runCatching {
            val slot = slotOf(slotName)
            val method = org.bukkit.Material::class.java.getMethod("getDefaultAttributeModifiers", EquipmentSlot::class.java)
            val multimap = method.invoke(item.type, slot)
            // Multimap.entries() -> Collection<Map.Entry<Attribute, AttributeModifier>>
            val entries = multimap.javaClass.getMethod("entries").invoke(multimap) as Collection<*>
            entries.mapNotNull { e ->
                val entry = e as? Map.Entry<*, *> ?: return@mapNotNull null
                val attr = entry.key as? Attribute ?: return@mapNotNull null
                val mod = entry.value as? AttributeModifier ?: return@mapNotNull null
                attr to mod
            }
        }.getOrElse {
            DebugUtil.log("Compat", "获取 ${item.type} 默认属性失败(可能是旧版本无此 API): ${it.message}")
            emptyList()
        }
    }
}
