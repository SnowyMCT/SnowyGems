package mc233.`fun`.snowygems.util

import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

object ItemRequireMatcher {

    private val SWORD = Regex("_SWORD$")
    private val AXE = Regex("_AXE$")
    private val PICKAXE = Regex("_PICKAXE$")
    private val SHOVEL = Regex("_SHOVEL$")
    private val HOE = Regex("_HOE$")
    private val HELMET = Regex("_HELMET$")
    private val CHESTPLATE = Regex("_CHESTPLATE$")
    private val LEGGINGS = Regex("_LEGGINGS$")
    private val BOOTS = Regex("_BOOTS$")

    fun isWeapon(name: String) = SWORD.containsMatchIn(name) || AXE.containsMatchIn(name) ||
        name in setOf("BOW", "CROSSBOW", "TRIDENT", "MACE")

    fun isTool(name: String) = PICKAXE.containsMatchIn(name) || SHOVEL.containsMatchIn(name) ||
        HOE.containsMatchIn(name) || AXE.containsMatchIn(name) || name == "SHEARS"

    fun isArmor(name: String) = HELMET.containsMatchIn(name) || CHESTPLATE.containsMatchIn(name) ||
        LEGGINGS.containsMatchIn(name) || BOOTS.containsMatchIn(name) ||
        name == "ELYTRA" || name == "TURTLE_HELMET" || name == "SHIELD"

    /**
     * 判断物品是否满足 Require 列表中的 **任意一条** (OR 语义).
     * [loreLines] 传入已 colorize 的物品 lore, 用于 LORE: 前缀的匹配.
     */
    fun matches(require: List<String>, item: ItemStack?, loreLines: List<String>): Boolean {
        if (require.isEmpty()) return true
        val name = item?.type?.name ?: "AIR"
        for (raw in require) {
            val entry = raw.trim()
            val ok = when {
                entry.equals("NOTHING", true) -> true
                entry.startsWith("LORE:") -> {
                    val needle = ColorUtil.colorize(entry.removePrefix("LORE:"))
                    loreLines.any { it.contains(needle) }
                }
                entry.startsWith("_") -> name.endsWith(entry.removePrefix("_"))
                entry.equals("WEAPON", true) -> isWeapon(name)
                entry.equals("TOOL", true) -> isTool(name)
                entry.equals("ARMOR", true) -> isArmor(name)
                entry.equals("SWORD", true) -> SWORD.containsMatchIn(name)
                entry.equals("PICKAXE", true) -> PICKAXE.containsMatchIn(name)
                entry.equals("AXE", true) -> AXE.containsMatchIn(name)
                entry.equals("SHOVEL", true) -> SHOVEL.containsMatchIn(name)
                entry.equals("HOE", true) -> HOE.containsMatchIn(name)
                entry.equals("BOOTS", true) -> BOOTS.containsMatchIn(name)
                entry.equals("HELMET", true) -> HELMET.containsMatchIn(name)
                entry.equals("CHESTPLATE", true) -> CHESTPLATE.containsMatchIn(name)
                entry.equals("LEGGINGS", true) -> LEGGINGS.containsMatchIn(name)
                entry.equals("SHIELD", true) -> name == "SHIELD"
                entry.equals("FISHING_ROD", true) -> name == "FISHING_ROD"
                entry.equals("FLINT_AND_STEEL", true) -> name == "FLINT_AND_STEEL"
                entry.equals("TRIDENT", true) -> name == "TRIDENT"
                entry.equals("BOW", true) -> name == "BOW"
                entry.equals("CROSSBOW", true) -> name == "CROSSBOW"
                else -> name.equals(entry, ignoreCase = true)
            }
            if (ok) return true
        }
        return false
    }

    /** 根据物品类型推断 auto 槽位: 盔甲对应部位, 其余默认主手 */
    fun autoSlot(item: ItemStack?): String {
        val name = item?.type?.name ?: return "hand"
        return when {
            HELMET.containsMatchIn(name) -> "head"
            CHESTPLATE.containsMatchIn(name) -> "chest"
            LEGGINGS.containsMatchIn(name) -> "legs"
            BOOTS.containsMatchIn(name) -> "feet"
            else -> "hand"
        }
    }
}
