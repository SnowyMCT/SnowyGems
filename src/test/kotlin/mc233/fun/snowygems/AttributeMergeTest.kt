package mc233.`fun`.snowygems

import mc233.`fun`.snowygems.compat.AttributeCompat
import mc233.`fun`.snowygems.reward.impl.AttributeReward
import org.bukkit.NamespacedKey
import org.bukkit.attribute.AttributeModifier
import org.bukkit.inventory.EquipmentSlotGroup
import kotlin.test.*

/**
 * 属性宝石"在装备自带数值上添加"的核心逻辑:
 *   - 装备自带属性(护甲/韧性/击退抗性…)的身份识别与 NBT 备份还原
 *   - 自带基数去重: 反复镶嵌不能把同一份自带属性越算越多
 */
class AttributeMergeTest {

    private val op = AttributeModifier.Operation.ADD_NUMBER

    private fun modifier(namespace: String, key: String, amount: Double) =
        AttributeModifier(NamespacedKey(namespace, key), amount, op, EquipmentSlotGroup.CHEST)

    @Test fun `装备自带的属性修饰符能认出并能原样重建`() {
        val vanilla = modifier("minecraft", "armor_toughness.chestplate", 3.0)
        assertFalse(AttributeCompat.isOwnModifier(vanilla), "原版自带属性不该被当成插件自己写的")
        val text = AttributeCompat.describe(vanilla)
        assertNotNull(text, "1.20.5+ 的修饰符都应能留下可还原的备份")
        val rebuilt = AttributeCompat.rebuild(text, op, "chest")
        assertEquals(vanilla, rebuilt, "还原出来的修饰符必须与原来完全一致")
        assertEquals(3.0, rebuilt!!.amount)
        assertEquals(EquipmentSlotGroup.CHEST, rebuilt.slotGroup)
    }

    @Test fun `本插件写的修饰符不会被当成自带属性`() {
        // 新 API: snowygems 命名空间
        assertTrue(AttributeCompat.isOwnModifier(modifier("snowygems", "attr_armor_toughness_0_chest", 4.0)))
        // 旧 API: UUID 身份. 1.21.x 上它的 getName() 是 UUID 文本、getKey() 是 minecraft:<UUID>,
        // 只能按 UUID 判定 —— 否则升级上来的老物品会把自己的旧修饰符当成装备自带属性再算一遍
        val legacyOwn = AttributeModifier(
            java.util.UUID.nameUUIDFromBytes("snowygems:attr_armor_toughness".toByteArray()),
            "snowygems:armor_toughness", 2.0, op, EquipmentSlotGroup.CHEST
        )
        assertTrue(AttributeCompat.isOwnIdentifier(legacyOwn, "armor_toughness"))
        assertFalse(AttributeCompat.isOwnIdentifier(legacyOwn, "armor_toughness_0_chest"))
        assertFalse(AttributeCompat.isOwnModifier(modifier("otherplugin", "bonus_toughness", 1.0)))
    }

    @Test fun `自带基数同一身份只算一次`() {
        val vanilla = modifier("minecraft", "armor_toughness.chestplate", 3.0)
        // 第二次镶嵌时从 NBT 备份读回来的同一份自带属性
        val restored = AttributeCompat.rebuild(AttributeCompat.describe(vanilla)!!, op, "chest")!!
        val merged = AttributeReward.mergeBaseline(listOf(vanilla), listOf(restored))
        assertEquals(1, merged.size, "同一份自带属性重复出现时只能算一次")
        assertEquals(3.0, merged.single().amount)
    }

    @Test fun `中途新增的同类属性也会被并进自带基数`() {
        val vanilla = modifier("minecraft", "armor_toughness.chestplate", 3.0)
        val extra = modifier("otherplugin", "bonus_toughness", 2.0)
        val merged = AttributeReward.mergeBaseline(listOf(vanilla), listOf(extra))
        assertEquals(2, merged.size)
        assertEquals(5.0, merged.sumOf { it.amount }, "自带 3 + 其它来源 2 都应计入基数")
    }
}
