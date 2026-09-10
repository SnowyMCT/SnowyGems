package mc233.`fun`.snowygems.gui

import mc233.`fun`.snowygems.config.GemConfig
import mc233.`fun`.snowygems.config.GemRegistry
import mc233.`fun`.snowygems.config.GemType
import mc233.`fun`.snowygems.config.MenuItemDef
import mc233.`fun`.snowygems.config.MenuLayout
import mc233.`fun`.snowygems.util.ColorUtil
import mc233.`fun`.snowygems.util.ItemFactory
import mc233.`fun`.snowygems.util.ItemRequireMatcher
import mc233.`fun`.snowygems.util.Lang
import org.bukkit.inventory.ItemStack

/** One policy shared by cursor placement, shift transfer, hotbar swaps and dragging. */
object MenuSlotRules {

    fun limit(def: MenuItemDef, item: ItemStack): Int =
        if (def.type.equals("EQUIP_SLOT", true)) 1 else def.amount.coerceIn(1, item.maxStackSize.coerceAtLeast(1))

    fun rejection(layout: MenuLayout, def: MenuItemDef, item: ItemStack): String? {
        val gemId = ItemFactory.getGemId(item)
        val gem = gemId?.let(GemRegistry::get)
        if (def.type.equals("GEM_SLOT", true)) {
            if (gemId == null) return Lang.get("menu.slot-only-gem")
            if (gem == null) return Lang.get("embed.gem-missing", "gem" to gemId)
            if (gem.type != GemType.NORMAL) return Lang.get("embed.wrong-type")
            if (gem.gui.isNotEmpty() && layout.name !in gem.gui) {
                return Lang.get("embed.wrong-gui", "gui" to gem.gui.joinToString(", "))
            }
            if (!matchesGem(def.require, gem)) {
                return Lang.get("menu.slot-require", "require" to def.require.joinToString(" / "))
            }
            return null
        }
        if (!def.type.equals("EQUIP_SLOT", true)) return Lang.get("menu.no-slot")
        // Gems are accepted as a target only when a menu explicitly asks for GEM:... .
        val accepts = if (gemId != null) {
            gem != null && def.require.any {
                it.startsWith("GEM:", true) && matchesGem(listOf(it), gem)
            }
        } else {
            val requirements = def.require.filterNot { it.startsWith("GEM:", true) }
            (def.require.isEmpty() || requirements.isNotEmpty()) &&
                ItemRequireMatcher.matches(requirements, item, item.itemMeta?.lore ?: emptyList())
        }
        return if (accepts) null else Lang.get("menu.target-require", "require" to
            def.require.joinToString(" / ").ifBlank { Lang.get("embed.label-equip-bottom") })
    }

    /** Explicit identifiers avoid accidental category matches; legacy text keywords remain supported. */
    fun matchesGem(require: List<String>, gem: GemConfig): Boolean {
        if (require.isEmpty()) return true
        val text = listOf(gem.id, gem.name, gem.display) + gem.tips
        return require.any { raw ->
            val entry = raw.trim()
            when {
                entry.equals("ALL", true) || entry.equals("GEM:ALL", true) -> true
                entry.startsWith("GEM:", true) -> gem.id.equals(entry.substring(4), true)
                entry.startsWith("CATEGORY:", true) -> gem.category.equals(entry.substring(9), true)
                entry.isBlank() -> false
                else -> text.any { ColorUtil.stripColor(it).contains(ColorUtil.stripColor(entry), true) }
            }
        }
    }
}
