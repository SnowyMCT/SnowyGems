package mc233.`fun`.snowygems

import mc233.`fun`.snowygems.config.*
import mc233.`fun`.snowygems.economy.*
import mc233.`fun`.snowygems.reward.*
import mc233.`fun`.snowygems.reward.impl.*
import mc233.`fun`.snowygems.skill.SkillBudget
import mc233.`fun`.snowygems.util.*
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataContainer
import java.lang.reflect.Proxy
import kotlin.test.*

class SafetyTest {
    @Test fun `gem limits count individual copies and exclusion ignores unrelated groups`() {
        val ruby = GemConfig("ruby", "ruby", embed = 2, exclusiveGroup = "color")
        val blue = GemConfig("blue", "blue", exclusiveGroup = "color")
        val other = GemConfig("other", "other", exclusiveGroup = "skill")
        val gems = listOf(ruby, blue, other).associateBy { it.id }
        assertNull(EmbedRules.rejection(ruby, listOf("ruby", "other"), gems::get))
        assertEquals("gem.embed-limit", EmbedRules.rejection(ruby, listOf("ruby", "ruby"), gems::get))
        assertEquals("gem.exclusive-group", EmbedRules.rejection(ruby, listOf("blue"), gems::get))
        assertNull(EmbedRules.rejection(ruby.copy(embed = 0), List(100) { "ruby" }, gems::get))
    }

    @Test fun `external economy failures never select the internal ledger`() {
        assertEquals(PointsProvider.UNAVAILABLE, selectPointsProvider("PlayerPoints", false))
        assertEquals(PointsProvider.PLAYER_POINTS, selectPointsProvider("PlayerPoints", true))
        assertEquals(PointsProvider.UNAVAILABLE, selectPointsProvider("typo", true))
        assertEquals(PointsProvider.INTERNAL, selectPointsProvider(" Internal ", false))
    }

    @Test fun `nested and scheduled executions share bounded budgets`() {
        val budget = SkillBudget(4, 2)
        assertFalse(budget.consume(17))
        repeat(4) { assertTrue(budget.consume(it)) }
        assertFalse(budget.consume(0))
        assertTrue(budget.reserveTask())
        assertTrue(budget.reserveTask())
        assertFalse(budget.reserveTask())
    }

    @Test fun `unbreakable gem removes its effect and refuses unknown legacy state`() {
        val item = TestItem()
        val ctx = context(item)
        val reward = UnbreakableReward()
        assertTrue(reward.apply(ctx))
        assertTrue(item.itemMeta!!.isUnbreakable)
        assertFalse(reward.apply(ctx))
        assertTrue(reward.revert(ctx))
        assertFalse(item.itemMeta!!.isUnbreakable)
        ctx.undoData.clear()
        assertFailsWith<IllegalArgumentException> { reward.revert(ctx) }
    }

    @Test fun `flag undo preserves flags that this gem did not add`() {
        val item = TestItem()
        item.itemMeta!!.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        val ctx = context(item)
        val reward = ItemFlagReward("HIDE_ENCHANTS")
        assertTrue(reward.apply(ctx))
        assertTrue(reward.revert(ctx))
        assertFalse(item.itemMeta!!.hasItemFlag(ItemFlag.HIDE_ENCHANTS))
        assertTrue(item.itemMeta!!.hasItemFlag(ItemFlag.HIDE_ATTRIBUTES))
    }

    @Test fun `hidden colored skill remains readable and dismantling restores visible lore`() {
        val item = TestItem()
        val skill = "§6§l[§c§lBUFF§6§l] §b生命提升 3"
        item.itemMeta!!.lore = listOf("other plugin line", skill)
        val ctx = context(item)
        val reward = SkillToNbtReward()
        assertTrue(reward.apply(ctx))
        assertEquals(listOf("other plugin line"), item.itemMeta!!.lore)
        assertTrue(skill in SkillLore.read(item))
        assertEquals(3.0, mc233.`fun`.snowygems.skill.SkillValue.resolveDouble("\$LORE:[BUFF] 生命提升?1\$", item))
        assertFalse(reward.apply(ctx))
        assertTrue(reward.revert(ctx))
        assertEquals(listOf("other plugin line", skill), item.itemMeta!!.lore)
        assertNull(item.getItemTag()[SkillLore.KEY])
    }

    @Test fun `changed hidden data cannot be overwritten during dismantling`() {
        val item = TestItem()
        item.itemMeta!!.lore = listOf("[技能] original")
        val ctx = context(item)
        val reward = SkillToNbtReward()
        assertTrue(reward.apply(ctx))
        val tag = item.getItemTag()
        tag[SkillLore.KEY] = ItemTagData("[技能] external change")
        tag.saveTo(item)
        assertFailsWith<IllegalArgumentException> { reward.revert(ctx) }
        assertEquals("[技能] external change", item.getItemTag()[SkillLore.KEY]?.asString())
    }

    @Test fun `repair and payouts never advertise reversible rewards`() {
        assertFalse(DurabilityReward(1000).reversible)
        assertFalse(MoneyReward("100").reversible)
        assertFalse(ItemGiveReward("gem").reversible)
        assertFalse(ConditionalReward("x", false, "Durability{amount=1}").reversible)
        assertTrue(ConditionalReward("x", false, "Unbreakable").reversible)
    }

    private fun context(item: ItemStack) = RewardContext(null, item, GemConfig("test", "test"), RewardPhase.APPLY, true)

    /** Metadata contract fixture only; real inventory/events still require a server. */
    private class TestItem : ItemStack() {
        private val data = mutableMapOf<Any, Any>()
        private var lore: List<String>? = null
        private var unbreakable = false
        private val flags = mutableSetOf<ItemFlag>()
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
                "setLore" -> { @Suppress("UNCHECKED_CAST") val value = args[0] as? List<String>; lore = value?.toList(); null }
                "isUnbreakable" -> unbreakable
                "setUnbreakable" -> { unbreakable = args[0] as Boolean; null }
                "addItemFlags" -> { flags.addAll((args[0] as Array<*>).filterIsInstance<ItemFlag>()); null }
                "removeItemFlags" -> { flags.removeAll((args[0] as Array<*>).filterIsInstance<ItemFlag>().toSet()); null }
                "hasItemFlag" -> args[0] in flags
                else -> error("Unexpected meta call: ${method.name}")
            }
        } as ItemMeta
        init { ItemData(linkedMapOf()).saveTo(meta) }
        override fun getItemMeta(): ItemMeta = meta
        override fun setItemMeta(itemMeta: ItemMeta?): Boolean = true
        override fun hasItemMeta(): Boolean = true
    }
}
