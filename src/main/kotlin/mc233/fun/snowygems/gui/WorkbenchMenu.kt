package mc233.`fun`.snowygems.gui

import mc233.`fun`.snowygems.config.MenuItemDef
import mc233.`fun`.snowygems.config.MenuLayout
import mc233.`fun`.snowygems.config.MenuRegistry
import mc233.`fun`.snowygems.config.GemRegistry
import mc233.`fun`.snowygems.manager.GemManager
import mc233.`fun`.snowygems.util.ColorUtil
import mc233.`fun`.snowygems.util.DebugUtil
import mc233.`fun`.snowygems.util.ItemFactory
import mc233.`fun`.snowygems.util.Lang
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import taboolib.library.xseries.XMaterial
import taboolib.platform.util.buildItem

object WorkbenchMenu {

    fun open(player: Player, menuName: String) {
        val layout = MenuRegistry.get(menuName)
        if (layout == null) {
            DebugUtil.log("Workbench", "打开菜单失败: 不存在名为 $menuName 的菜单 (已加载: ${MenuRegistry.names()})")
            Lang.send(player, "menu.not-found", "menu" to menuName)
            return
        }
        val holder = MenuHolder(layout)
        val inv = Bukkit.createInventory(holder, layout.size, ColorUtil.colorize(layout.title))
        holder.inv = inv
        DebugUtil.log("Workbench", "为 ${player.name} 打开菜单 $menuName 容量=${inv.size} 标题=${layout.title}")
        renderStatic(inv, layout)
        refresh(holder)
        DebugUtil.log(
            "Workbench",
            "  菜单 $menuName 活动槽位: EQUIP_SLOT=${findEquipSlot(inv, layout)} GEM_SLOT=${gemSlots(inv, layout)}"
        )
        player.openInventory(inv)
    }

    /** 根据行列字符网格反查 rawSlot 对应的字符, 找不到返回 null */
    fun charAt(layout: MenuLayout, rawSlot: Int): Char? {
        if (rawSlot < 0) return null
        val row = rawSlot / 9
        val col = rawSlot % 9
        if (row !in layout.rows.indices) return null
        val rowStr = layout.rows[row]
        if (col !in rowStr.indices) return null
        return rowStr[col]
    }

    /** EQUIP_SLOT / GEM_SLOT 是允许玩家自由放置物品的"活动"槽位, 其余为静态展示按钮 */
    fun isSlotDynamic(def: MenuItemDef): Boolean =
        def.type.equals("EQUIP_SLOT", true) || def.type.equals("GEM_SLOT", true)

    /** 找到该菜单中第一个 EQUIP_SLOT 的 rawSlot, 找不到返回 -1 */
    fun findEquipSlot(inv: Inventory, layout: MenuLayout): Int {
        for (row in layout.rows.indices) {
            val rowStr = layout.rows[row]
            for (col in rowStr.indices) {
                val slot = row * 9 + col
                if (slot >= inv.size) continue
                val def = layout.items[rowStr[col]] ?: continue
                if (def.type.equals("EQUIP_SLOT", true)) return slot
            }
        }
        return -1
    }

    /** 遍历该菜单所有 GEM_SLOT 的 rawSlot */
    fun gemSlots(inv: Inventory, layout: MenuLayout): List<Int> {
        val result = ArrayList<Int>()
        for (row in layout.rows.indices) {
            val rowStr = layout.rows[row]
            for (col in rowStr.indices) {
                val slot = row * 9 + col
                if (slot >= inv.size) continue
                val def = layout.items[rowStr[col]] ?: continue
                if (def.type.equals("GEM_SLOT", true)) result.add(slot)
            }
        }
        return result
    }

    private fun renderStatic(inv: Inventory, layout: MenuLayout) {
        var painted = 0
        var dynamic = 0
        var undefined = 0
        for (row in layout.rows.indices) {
            val rowStr = layout.rows[row]
            for (col in rowStr.indices) {
                val slot = row * 9 + col
                if (slot >= inv.size) continue
                val c = rowStr[col]
                val def = layout.items[c]
                if (def == null) {
                    // 只有非空白字符却没有对应 Items 定义时才算配置疏漏
                    if (!c.isWhitespace()) {
                        undefined++
                        DebugUtil.log("Workbench", "  槽位 $slot 的字符 '$c' 在 Items 中没有定义, 留空")
                    }
                    continue
                }
                if (isSlotDynamic(def)) {
                    dynamic++
                    continue
                }
                inv.setItem(slot, buildStaticIcon(def))
                painted++
            }
        }
        DebugUtil.log("Workbench", "  渲染完成: 静态图标 $painted 个, 活动槽位 $dynamic 个, 未定义字符 $undefined 个")
    }

    /** Preview only reads items and configuration; it never rolls success or executes rewards. */
    fun refresh(holder: MenuHolder) {
        if (holder.returned) return
        val inv = holder.inv
        val equipSlot = findEquipSlot(inv, holder.layout)
        val target = if (equipSlot >= 0) inv.getItem(equipSlot)?.takeUnless { it.type.isAir } else null
        val gems = gemSlots(inv, holder.layout).mapNotNull { slot ->
            inv.getItem(slot)?.takeUnless { it.type.isAir }?.let { slot to it }
        }
        val issues = gems.mapNotNull { (slot, gem) ->
            val reason = holder.slots[slot]?.let { MenuSlotRules.rejection(holder.layout, it, gem) }
                ?: target?.let { GemManager.validateApply(gem, it, holder.menuName) }
            reason?.let { Lang.get("menu.preview-invalid", "slot" to (slot + 1), "reason" to it) }
        }
        for ((slot, def) in holder.slots) {
            if (!def.type.equals("CONFIRM_EMBED", true)) continue
            val ready = target != null && gems.isNotEmpty() && issues.isEmpty()
            val icon = buildStaticIcon(def)
            val meta = icon.itemMeta ?: continue
            meta.setDisplayName(Lang.get(if (ready) "embed.button-ready-name" else "embed.button-locked-name"))
            meta.lore = buildList {
                addAll(ColorUtil.colorize(def.tips))
                if (target == null) add(Lang.get("embed.need-equip"))
                else add(Lang.get("embed.button-equip", "equip" to displayName(target)))
                if (gems.isEmpty()) add(Lang.get("embed.need-gem"))
                for ((gemSlot, item) in gems) {
                    val cfg = ItemFactory.getGemId(item)?.let(GemRegistry::get)
                    val slotDef = holder.slots[gemSlot]
                    val slotLabel = slotDef?.let { runeSlotLabel(it.require) } ?: "槽位"
                    add(Lang.get("menu.preview-slot", "slot" to slotLabel, "gem" to displayName(item),
                        "chance" to (cfg?.success ?: 0)))
                }
                addAll(issues)
                if (gems.isNotEmpty()) add(Lang.get("menu.batch-cost", "count" to gems.size))
                if (gems.any { (_, item) -> (ItemFactory.getGemId(item)?.let(GemRegistry::get)?.success ?: 100) < 100 }) {
                    add(Lang.get("embed.info-may-fail"))
                }
            }
            icon.itemMeta = meta
            inv.setItem(slot, icon)
        }
    }

    private fun displayName(item: ItemStack): String =
        item.itemMeta?.takeIf { it.hasDisplayName() }?.displayName ?: item.type.name

    private fun runeSlotLabel(require: List<String>): String {
        val text = require.joinToString(" ")
        return when {
            text.contains("蓝色") -> "蓝色符文槽"
            text.contains("绿色") -> "绿色符文槽"
            text.contains("红色") -> "红色符文槽"
            text.contains("技能") -> "技能符文槽"
            else -> "宝石槽"
        }
    }

    private fun buildStaticIcon(def: MenuItemDef): ItemStack {
        val hasTexture = !def.texture.isNullOrBlank()
        val mat = if (hasTexture) XMaterial.PLAYER_HEAD
        else XMaterial.matchXMaterial(def.material ?: "STONE").orElse(XMaterial.STONE)
        return buildItem(mat) {
            if (hasTexture) skullTexture = taboolib.platform.util.SkullTexture(def.texture!!)
            name = ColorUtil.colorize(def.display ?: "")
            lore.addAll(ColorUtil.colorize(def.tips))
            amount = def.amount.coerceIn(1, 64)
            if (def.glow) shiny()
        }
    }
}
