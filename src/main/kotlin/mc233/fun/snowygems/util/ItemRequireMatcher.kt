package mc233.`fun`.snowygems.util

import mc233.`fun`.snowygems.compat.ItemCategories
import mc233.`fun`.snowygems.compat.Registries
import org.bukkit.inventory.ItemStack

/**
 * Require 匹配 —— 判断一件物品是否落在某个宝石的适用范围内。
 *
 * 多版本要点: 分类关键字(WEAPON / TOOL / ARMOR / SWORD …)的判定不再靠本文件里硬编码的
 * 正则和物品清单, 而是转交给 [ItemCategories] —— 它在启动时读服务端注册表建索引。
 * 这样 1.21.11 的矛(SPEAR)、铜盔甲(COPPER_*)、以及未来新增的材质/形态, 都会自动落入
 * 正确的分类, 服主不需要改配置, 我也不需要改代码。
 *
 * 支持的写法(OR 语义, 命中任意一条即通过):
 *   NOTHING            —— 无条件通过
 *   WEAPON / TOOL / ARMOR / SWORD / PICKAXE / MACE / SPEAR / RANGED / MELEE …
 *                      —— 分类关键字, 完整清单见 ItemCategories.knownCategories
 *   _SWORD             —— 下划线开头表示"物品名以此结尾", 可匹配任意材质
 *   COPPER_*           —— 星号通配, 如 COPPER_* 匹配全套铜制物品
 *   LORE:文本          —— 物品 lore 里含指定文本
 *   DIAMOND_SWORD      —— 精确物品名
 */
object ItemRequireMatcher {

    /**
     * 判断物品是否满足 Require 列表中的 **任意一条** (OR 语义).
     * [loreLines] 传入已 colorize 的物品 lore, 用于 LORE: 前缀的匹配.
     */
    fun matches(require: List<String>, item: ItemStack?, loreLines: List<String>): Boolean {
        if (require.isEmpty()) return true
        val name = item?.type?.name ?: "AIR"
        for (raw in require) {
            if (matchesSingle(raw.trim(), name, loreLines)) return true
        }
        return false
    }

    /** 单条 Require 的判定 */
    private fun matchesSingle(entry: String, materialName: String, loreLines: List<String>): Boolean {
        if (entry.isEmpty()) return false
        return when {
            entry.equals("NOTHING", true) -> true
            entry.equals("ALL", true) || entry.equals("ANY", true) -> true
            entry.startsWith("LORE:", true) -> {
                val needle = ColorUtil.colorize(entry.substring(5))
                loreLines.any { it.contains(needle) }
            }
            // _SWORD -> 以 _SWORD 结尾的任意材质
            entry.startsWith("_") -> materialName.endsWith(entry.uppercase())
            // COPPER_* -> 以 COPPER_ 开头(1.21.9+ 铜装备整套匹配)
            entry.endsWith("*") -> materialName.startsWith(entry.dropLast(1).uppercase())
            // 分类关键字: 由注册表建立的索引决定
            ItemCategories.matches(materialName, entry) -> true
            // 精确物品名
            else -> materialName.equals(entry, ignoreCase = true)
        }
    }

    /**
     * 校验一条 Require 写法在当前版本是否有意义, 供配置加载时给出可读警告。
     * @return null 表示没问题, 否则返回警告文本
     */
    fun validate(entry: String): String? {
        val e = entry.trim()
        if (e.isEmpty()) return "空的 Require 条目"
        if (e.equals("NOTHING", true) || e.equals("ALL", true) || e.equals("ANY", true)) return null
        if (e.startsWith("LORE:", true) || e.startsWith("_") || e.endsWith("*")) return null
        if (e.uppercase() in ItemCategories.knownCategories) return null
        if (Registries.hasMaterial(e)) return null
        return "Require 条目 '$e' 既不是当前版本的物品名, 也不是可用的分类关键字" +
            "(可用分类: ${ItemCategories.knownCategories.joinToString("/")})"
    }

    /** 根据物品类型推断 auto 槽位: 盔甲对应部位, 其余默认主手 */
    fun autoSlot(item: ItemStack?): String {
        val name = item?.type?.name ?: return "hand"
        val cats = ItemCategories.categoriesOf(name)
        return when {
            "HEAD" in cats -> "head"
            "CHEST" in cats && "ELYTRA" !in cats -> "chest"
            "LEGS" in cats -> "legs"
            "FEET" in cats -> "feet"
            "OFF_HAND" in cats -> "off_hand"
            else -> "hand"
        }
    }

    // ── 兼容旧调用点 ────────────────────────────────────────

    fun isWeapon(name: String) = ItemCategories.matches(name, "WEAPON")

    fun isTool(name: String) = ItemCategories.matches(name, "TOOL")

    fun isArmor(name: String) = ItemCategories.matches(name, "ARMOR")
}
