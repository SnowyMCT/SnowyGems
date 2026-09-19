package mc233.`fun`.snowygems.util

import org.bukkit.inventory.ItemStack

/** Hidden presentation must not change skill identity or dynamic values. */
object SkillLore {
    const val KEY = "SnowyGemsHiddenSkillLore"
    fun read(item: ItemStack): List<String> = (item.itemMeta?.lore ?: emptyList()) +
        (item.getItemTag()[KEY]?.asString()?.split("\n")?.filter { it.isNotEmpty() } ?: emptyList())

    fun isSkillLine(line: String): Boolean {
        val plain = ColorUtil.stripColor(line)
        return plain.contains("[技能]") || plain.contains("[BUFF]", true)
    }
}
