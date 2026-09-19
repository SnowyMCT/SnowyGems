package mc233.`fun`.snowygems.compat

import mc233.`fun`.snowygems.util.DebugUtil
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.EquipmentSlotGroup
import org.bukkit.inventory.meta.ItemMeta
import mc233.`fun`.snowygems.util.ItemTagData
import mc233.`fun`.snowygems.util.getItemTag
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
 *
 * 装备自带属性的合并:
 *   1.20.5+ 起物品自带的护甲/韧性/击退抗性等是物品自己的属性修饰符(attribute_modifiers 组件),
 *   往物品上再加一条本插件的修饰符就会显示成两条同名属性. [baselineModifiers] 负责把这类
 *   "自带"修饰符识别出来交给 [AttributeReward] 合并, [describe]/[rebuild] 负责在 NBT 里留备份
 *   并在拆卸时原样还原
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
     * 该修饰符是否出自本插件: 新 API 认 snowygems 命名空间, 旧 API 认同名前缀的 name
     *
     * 注意: 1.21.x 上旧 API(UUID 身份)创建出来的修饰符, getName() 返回的是 UUID 文本、
     * getKey() 是 minecraft:<UUID>, 只能靠 [isOwnIdentifier] 按 UUID 判定
     */
    fun isOwnModifier(modifier: AttributeModifier): Boolean {
        val key = runCatching { modifier.key }.getOrNull()
        if (key != null && key.namespace == NAMESPACE) return true
        return runCatching { modifier.name.startsWith("$NAMESPACE:") }.getOrDefault(false)
    }

    /**
     * 该修饰符是不是本插件为 [ids] 里某个标识写下的(新旧两套身份都查).
     * 用来把"库里老版本留下的自己的修饰符"和"装备自带属性"区分开
     */
    fun isOwnIdentifier(modifier: AttributeModifier, vararg ids: String): Boolean {
        for (id in ids) {
            if (runCatching { modifier.key == keyOf(id) }.getOrDefault(false)) return true
            if (runCatching {
                    @Suppress("DEPRECATION")
                    modifier.uniqueId == uuidOf(id)
                }.getOrDefault(false)
            ) return true
        }
        return false
    }

    /**
     * 取物品上"装备自带"(或其它插件写入)的同类修饰符 —— 同属性、同计算方式、同槽位, 且不是本插件写的.
     *
     * 这些就是宝石要"在原有数值上添加"的基数: 镶嵌时合并进本插件那一条(物品上只留一条同名属性),
     * 拆卸时按 [describe] 留下的备份原样还回去
     *
     * 拿不准身份的一律不碰:
     *   - [ownIds] 是本插件可能用过的标识, 命中即视为自己的, 不并进基数
     *   - 槽位语义不同(护甲位 vs 任意位)合并会改变生效范围, 只保留原样
     *
     * @param ownIds 本插件在这件物品上可能用过的修饰符标识(如 modifierId 与旧版的属性名)
     */
    fun baselineModifiers(
        meta: ItemMeta,
        attribute: Attribute,
        operation: AttributeModifier.Operation,
        slotName: String,
        vararg ownIds: String
    ): List<AttributeModifier> {
        val existing = runCatching { meta.getAttributeModifiers(attribute) }.getOrNull() ?: return emptyList()
        return existing.filter { modifier ->
            modifier != null &&
                !isOwnModifier(modifier) &&
                !isOwnIdentifier(modifier, *ownIds) &&
                describe(modifier) != null &&
                runCatching { modifier.operation == operation }.getOrDefault(false) &&
                sameSlot(modifier, slotName)
        }
    }

    /**
     * 把修饰符压成一行备份文本 `命名空间|键|数值`, 供拆卸还原; 身份没法记下时返回 null(这类修饰符就不合并)
     *
     * 旧 API(UUID 身份)的修饰符在 1.21.x 上 getKey() 会给 minecraft:<UUID>, 备份/还原后会变成
     * 同一命名空间+键的修饰符 —— 数值、计算方式、槽位都不变, 游戏侧效果完全一致
     */
    fun describe(modifier: AttributeModifier): String? {
        val amount = runCatching { modifier.amount }.getOrNull() ?: return null
        if (!amount.isFinite()) return null
        val key = runCatching { modifier.key }.getOrNull() ?: return null
        if (runCatching { NamespacedKey(key.namespace, key.key) }.isFailure) return null
        return "${key.namespace}|${key.key}|$amount"
    }

    /**
     * 还原 [describe] 记下的修饰符
     * @return 失败返回 null(只记日志, 不抛异常 —— 还原失败不该让整次拆卸中断)
     */
    fun rebuild(text: String, operation: AttributeModifier.Operation, slotName: String): AttributeModifier? {
        // 按首尾分隔符切: 键本身允许含 '|'(虽然极少见), 数值固定在最后一段
        val first = text.indexOf('|')
        val last = text.lastIndexOf('|')
        if (first <= 0 || last <= first) return null
        val amount = text.substring(last + 1).toDoubleOrNull() ?: return null
        val key = runCatching { NamespacedKey(text.substring(0, first), text.substring(first + 1, last)) }.getOrNull()
            ?: return null
        return runCatching { AttributeModifier(key, amount, operation, slotGroupOf(slotName)) }
            .onFailure { DebugUtil.log("Compat", "还原装备自带属性 ${key} 失败: ${it.message}") }
            .getOrNull()
    }

    /** 槽位语义是否一致: 新 API 比 EquipmentSlotGroup, 旧 API 退化成比 EquipmentSlot */
    private fun sameSlot(modifier: AttributeModifier, slotName: String): Boolean {
        val group = runCatching { modifier.slotGroup }.getOrNull()
        if (group != null) return runCatching { group == slotGroupOf(slotName) }.getOrDefault(false)
        val slot = runCatching { modifier.slot }.getOrNull() ?: return false
        return runCatching { slot == slotOf(slotName) }.getOrDefault(false)
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
     *   1) 材质自带的属性修饰符: 下界合金胸甲自带 +8 护甲 +3 韧性 +0.1 击退抗性, 钻石剑自带攻击力等.
     *      1.20.5+ 它们写在物品的 attribute_modifiers 组件里, 但只要物品上还没有显式修饰符,
     *      这一层就由材质提供, 并随时可被"写入显式修饰符"顶掉(实测 1.21.4 上 addAttributeModifier
     *      会把组件替换成只剩我们这一条, 自带护甲/韧性直接消失).
     *   2) 显式 AttributeModifier: 已经写进物品 NBT 的.
     *
     * 解决: 在写入我们的修饰符之前, 若 meta 尚无任何属性修饰符, 就把该材质在对应槽位的全部默认属性
     * 显式拷进 meta. 之后再叠加我们自己的, 两者共存.
     *
     * 固化下来的"同类属性"随后会被 [AttributeReward] 合并进宝石那一行(见 baselineModifiers), 所以
     * 玩家看到的是"自带 + 宝石"合成一条, 而不是两条同名属性.
     *
     * 只在"首次给这件物品加修饰符"时做一次(用 NBT 标记去重), 避免重复镶嵌时反复累加默认属性.
     *
     * @return 固化的默认属性条数(0 表示无需固化或该材质无默认属性)
     */
    fun preserveDefaultsIfNeeded(item: org.bukkit.inventory.ItemStack, meta: ItemMeta, slotName: String): Int {
        // 已经固化过就不再重复(用一个专属 NBT 标记)
        val tag = item.getItemTag()
        if (tag[DEFAULTS_KEPT_KEY]?.asString() == "1") return 0
        // meta 已带显式修饰符: 说明要么之前已固化, 要么本就是自定义装备, 不动它, 只打标记.
        // hasAttributeModifiers() 返回 Boolean, 避开直接引用 Guava Multimap 类型(编译期不在 classpath).
        val hasModifiers = runCatching { meta.hasAttributeModifiers() }.getOrDefault(false)
        if (hasModifiers) {
            tag?.let { it[DEFAULTS_KEPT_KEY] = ItemTagData("1"); it.saveTo(meta) }
            return 0
        }
        val defaults = defaultModifiersOf(item, slotName)
        if (defaults.isEmpty()) {
            // 该材质本就没有默认属性(如普通靴子除盔甲外无其它), 仍打标记避免每次都查
            tag?.let { it[DEFAULTS_KEPT_KEY] = ItemTagData("1"); it.saveTo(meta) }
            return 0
        }
        var kept = 0
        for ((attr, mod) in defaults) {
            runCatching {
                meta.addAttributeModifier(attr, mod)
                kept++
            }.onFailure { DebugUtil.log("Compat", "固化默认属性 ${attr.key.key} 失败: ${it.message}") }
        }
        tag?.let { it[DEFAULTS_KEPT_KEY] = ItemTagData("1"); it.saveTo(meta) }
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
