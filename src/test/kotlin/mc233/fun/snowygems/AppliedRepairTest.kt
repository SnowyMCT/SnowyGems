package mc233.`fun`.snowygems

import mc233.`fun`.snowygems.config.GemConfig
import mc233.`fun`.snowygems.manager.planAppliedRepair
import mc233.`fun`.snowygems.reward.FunctionCall
import mc233.`fun`.snowygems.reward.ParsedReward
import mc233.`fun`.snowygems.reward.impl.LoreVarReward
import mc233.`fun`.snowygems.reward.impl.NameReward
import mc233.`fun`.snowygems.reward.RewardContext
import mc233.`fun`.snowygems.reward.RewardPhase
import mc233.`fun`.snowygems.util.ItemData
import mc233.`fun`.snowygems.util.ItemTagData
import mc233.`fun`.snowygems.util.getItemTag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataContainer
import java.lang.reflect.Proxy
import kotlin.test.*

/**
 * 2026-10 的实测 bug: 幸运药水这类"往 lore 写数值"的 BUFF 宝石在装备上生效了, 却没进已镶嵌列表,
 * 于是 /sgem inspect 与拆卸台都看不到它。这里锁住两侧的契约:
 *   - LoreVar 必须可撤销(否则登记门槛会把它挡掉), 且 NBT 键要与装备上真实存在的键一致;
 *   - 旧装备的补登记要把"生效了但没登记"的条目按 NBT 数值算回来。
 */
class AppliedRepairTest {

    private val luckPrefix = "&6&l[&c&lBUFF&6&l] &b&l幸运"

    private fun loreVarGem(id: String, prefix: String = luckPrefix, varExpr: String = "v+1"): GemConfig {
        val call = FunctionCall("LoreVar", linkedMapOf("lore" to prefix, "var" to varExpr, "format" to "%.0f"))
        return GemConfig(id, id, parsedRewards = listOf(ParsedReward(call, emptySet())))
    }

    @Test fun `lorevar nbt key matches the key written on real equipment`() {
        // 世界存档里那双靴子上的键就是 SnowyGemsLoreVar_ece6eef30ede, 改前缀写法会让补登记失效
        assertEquals("SnowyGemsLoreVar_ece6eef30ede", LoreVarReward.keyOf(luckPrefix))
    }

    @Test fun `lorevar and name rewards are reversible so they can be tracked and dismantled`() {
        assertTrue(LoreVarReward(luckPrefix, null, "last", "v+1", null, "%.0f").reversible)
        assertTrue(LoreVarReward(luckPrefix, null, "last", "v+1", null, "%.0f", "v<=3").reversible)
        assertTrue(NameReward("新名字").reversible)
    }

    @Test fun `limit expression is evaluated against the new value`() {
        // 幸运药水1 配置写的是 limit: "v<=1", 语义是"新值最多 1"
        assertTrue(LoreVarReward.allowsLimit(null, 999.0))
        assertTrue(LoreVarReward.allowsLimit("v<=1", 1.0))
        assertFalse(LoreVarReward.allowsLimit("v<=1", 2.0))
        assertTrue(LoreVarReward.allowsLimit("v<=3", 3.0))
        assertFalse(LoreVarReward.allowsLimit("v<=3", 4.0))
    }

    @Test fun `unregistered buff is recovered from the stored value`() {
        val gems = listOf(
            loreVarGem("幸运药水1", varExpr = "v+1"),
            loreVarGem("幸运药水2", varExpr = "v<2?2:3"),
            loreVarGem("幸运药水3", varExpr = "v<3?3:4")
        )
        val plan = planAppliedRepair(
            mapOf(LoreVarReward.keyOf(luckPrefix) to 2.0),
            listOf("神生命宝石"),
            gems
        )
        // 只有"一颗 +1"的那颗能对上数值, 补 2 条
        assertEquals(mapOf("幸运药水1" to 2), plan)
    }

    @Test fun `family that is already recorded is left alone`() {
        val gems = listOf(loreVarGem("幸运药水1"), loreVarGem("幸运药水2", varExpr = "v<2?2:3"))
        val plan = planAppliedRepair(mapOf(LoreVarReward.keyOf(luckPrefix) to 2.0), listOf("幸运药水2"), gems)
        assertTrue(plan.isEmpty(), "同一 NBT 键已有登记时不能重复补: $plan")
    }

    @Test fun `zero fractional and unknown keys are ignored`() {
        val gems = listOf(loreVarGem("幸运药水1"))
        assertTrue(planAppliedRepair(mapOf(LoreVarReward.keyOf(luckPrefix) to 0.0), emptyList(), gems).isEmpty())
        assertTrue(planAppliedRepair(mapOf(LoreVarReward.keyOf(luckPrefix) to 0.5), emptyList(), gems).isEmpty())
        assertTrue(planAppliedRepair(mapOf("SnowyGemsLoreVar_deadbeef0000" to 3.0), emptyList(), gems).isEmpty())
        assertTrue(planAppliedRepair(emptyMap(), emptyList(), gems).isEmpty())
    }

    @Test fun `gems without a one step lorevar cannot be reconstructed`() {
        val step2 = listOf(loreVarGem("幸运药水2", varExpr = "v<2?2:3"))
        assertTrue(planAppliedRepair(mapOf(LoreVarReward.keyOf(luckPrefix) to 2.0), emptyList(), step2).isEmpty())
    }

    // ── 元数据层往返（不需要服务器, 用代理 ItemMeta 当夹具, 与 SafetyTest 同一套路） ──

    private fun applyCtx(item: ItemStack, gemId: String) =
        RewardContext(null, item, GemConfig(gemId, gemId), RewardPhase.APPLY, true)

    private fun revertCtx(item: ItemStack, gemId: String, undo: Map<String, String>) =
        RewardContext(null, item, GemConfig(gemId, gemId), RewardPhase.REMOVE, true).apply { undoData.putAll(undo) }

    @Test fun `lorevar round trip writes the buff and removes it on dismantle`() {
        val item = TestItem()
        item.itemMeta!!.lore = listOf("原生说明")
        val reward = LoreVarReward(luckPrefix, null, "last", "v+1", null, "%.0f", "v<=1")
        val ctx = applyCtx(item, "幸运药水1")

        assertTrue(reward.apply(ctx), "第一颗应生效")
        val key = LoreVarReward.keyOf(luckPrefix)
        assertEquals(1.0, item.getItemTag()[key]!!.asDouble())
        assertEquals(2, item.itemMeta!!.lore!!.size, "应新增一行 BUFF")
        assertTrue(item.itemMeta!!.lore!!.last().contains("幸运"))

        // limit: "v<=1" 意味着第二颗不能把数值推到 2
        assertFalse(reward.apply(applyCtx(item, "幸运药水1")), "超过上限必须拒绝")
        assertEquals(1.0, item.getItemTag()[key]!!.asDouble(), "被上限拒绝时不能改动物品")

        val undo = ctx.undoData.toMap()
        assertTrue(reward.revert(revertCtx(item, "幸运药水1", undo)), "拆卸应成功")
        assertEquals(listOf("原生说明"), item.itemMeta!!.lore)
        assertNull(item.getItemTag()[key], "数值归零后 NBT 键也要清掉")
    }

    @Test fun `legacy revert without history steps exactly one level back`() {
        val item = TestItem()
        val colored = "§6§l[§c§lBUFF§6§l] §b§l幸运"
        item.itemMeta!!.lore = listOf(colored + "2")
        val tag = item.getItemTag()
        tag[LoreVarReward.keyOf(luckPrefix)] = ItemTagData(2.0)
        tag.saveTo(item)

        val reward = LoreVarReward(luckPrefix, null, "last", "v+1", null, "%.0f", null)
        assertTrue(reward.revert(revertCtx(item, "幸运药水1", emptyMap())))
        assertEquals(1.0, item.getItemTag()[LoreVarReward.keyOf(luckPrefix)]!!.asDouble())
        assertEquals(listOf(colored + "1"), item.itemMeta!!.lore)
    }

    @Test fun `name reward restores the previous display name`() {
        val item = TestItem()
        item.itemMeta!!.setDisplayName("§f旧名字")
        val reward = NameReward("&f新名字")
        val ctx = applyCtx(item, "改名宝石")
        assertTrue(reward.apply(ctx))
        assertEquals("§f新名字", item.itemMeta!!.displayName)
        val undo = ctx.undoData.toMap()
        assertTrue(reward.revert(revertCtx(item, "改名宝石", undo)))
        assertEquals("§f旧名字", item.itemMeta!!.displayName)
    }

    /** 代理 ItemMeta 夹具: 只实现这些奖励真正会用到的方法 */
    private class TestItem : ItemStack() {
        private val data = mutableMapOf<Any, Any>()
        private var lore: List<String>? = null
        private var display: String? = null
        private val pdc = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PersistentDataContainer::class.java)) { _, method, args ->
            when (method.name) {
                "set" -> { data[args[0]] = args[2]; null }
                "get" -> data[args[0]]
                "has" -> data.containsKey(args[0])
                "remove" -> { data.remove(args[0]); null }
                "isEmpty" -> data.isEmpty()
                "getKeys" -> data.keys
                else -> error("Unexpected PDC call: ${method.name}")
            }
        } as PersistentDataContainer
        private val meta = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ItemMeta::class.java)) { _, method, args ->
            when (method.name) {
                "getPersistentDataContainer" -> pdc
                "getLore" -> lore?.toMutableList()
                "setLore" -> { @Suppress("UNCHECKED_CAST") val v = args[0] as? List<String>; lore = v?.toList(); null }
                "hasDisplayName" -> display != null
                "getDisplayName" -> display ?: ""
                "setDisplayName" -> { display = args[0] as? String; null }
                "isUnbreakable" -> false
                "setUnbreakable" -> null
                "addItemFlags" -> null
                "removeItemFlags" -> null
                "hasItemFlag" -> false
                else -> error("Unexpected meta call: ${method.name}")
            }
        } as ItemMeta
        init { ItemData(linkedMapOf()).saveTo(meta) }
        override fun getItemMeta(): ItemMeta = meta
        override fun setItemMeta(itemMeta: ItemMeta?): Boolean = true
        override fun hasItemMeta(): Boolean = true
    }
}
