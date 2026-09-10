package mc233.`fun`.snowygems.gui

import mc233.`fun`.snowygems.config.MenuItemDef
import mc233.`fun`.snowygems.config.MenuLayout
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder

/** Keep the layout that owns these items even if the configuration is reloaded or removed. */
class MenuHolder(val layout: MenuLayout) : InventoryHolder {
    val menuName: String get() = layout.name
    val slots: Map<Int, MenuItemDef> = buildMap {
        layout.rows.forEachIndexed { row, text ->
            text.forEachIndexed { column, character ->
                layout.items[character]?.let { put(row * 9 + column, it) }
            }
        }
    }
    val dynamicSlots = slots.filterValues(WorkbenchMenu::isSlotDynamic)
    var returned = false
    var processing = false
    var closeRequested = false
    lateinit var inv: Inventory
    override fun getInventory(): Inventory = inv
}
