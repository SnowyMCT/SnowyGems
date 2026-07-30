package mc233.`fun`.snowygems.gui

import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder

class MenuHolder(val menuName: String) : InventoryHolder {
    lateinit var inv: Inventory
    override fun getInventory(): Inventory = inv
}
