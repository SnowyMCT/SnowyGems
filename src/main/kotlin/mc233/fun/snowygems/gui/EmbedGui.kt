package mc233.`fun`.snowygems.gui

import mc233.`fun`.snowygems.config.GemConfig
import mc233.`fun`.snowygems.config.GemRegistry
import mc233.`fun`.snowygems.config.GemType
import mc233.`fun`.snowygems.manager.GemManager
import mc233.`fun`.snowygems.util.ColorUtil
import mc233.`fun`.snowygems.util.DebugUtil
import mc233.`fun`.snowygems.util.ItemFactory
import mc233.`fun`.snowygems.util.ItemRequireMatcher
import mc233.`fun`.snowygems.util.Lang
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import taboolib.common.platform.event.SubscribeEvent
import taboolib.common.platform.function.submit
import taboolib.library.xseries.XMaterial
import taboolib.platform.util.buildItem
import taboolib.platform.util.giveItem

/**
 * 宝石镶嵌台 (/sgem 镶嵌).
 *
 * 专用于 属性 / 附魔 / 功能 / BUFF(药水) 这几类需要作用到装备上的宝石.
 * 操作流程被简化成三步, 不再依赖"先点宝石再点装备"的隐式手势:
 *
 *   1. 把装备放进左边的装备槽 ([EQUIP_SLOT])
 *   2. 把宝石放进右边的宝石槽 ([GEM_SLOT])
 *   3. 点击中间的确认按钮 -> 完成镶嵌, 结果直接写回装备槽
 *
 * 中间按钮会实时显示当前状态(缺装备 / 缺宝石 / 不适用 / 可以镶嵌), 并把宝石的成功率、
 * 适用范围、将要生效的效果都列在按钮 Lore 上, 玩家不用猜.
 *
 * 符文类宝石(在配置里用 `Gui:` 指定了专属界面)不在此界面受理, 会提示玩家去对应的符文台.
 */
object EmbedGui {

    /** 装备槽: 放要被强化/镶嵌的那件装备 */
    const val EQUIP_SLOT = 20

    /** 宝石槽: 放要消耗掉的宝石 */
    const val GEM_SLOT = 24

    /** 确认按钮 */
    const val CONFIRM_SLOT = 22

    /** 本界面的名字, 用于和宝石配置里的 `Gui:` 字段比对 */
    const val GUI_NAME = "宝石镶嵌台"

    private val ROWS = 6
    private val SIZE = ROWS * 9

    class EmbedHolder : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    fun open(player: Player) {
        val holder = EmbedHolder()
        val inv = Bukkit.createInventory(holder, SIZE, Lang.get("embed.title"))
        holder.inv = inv
        decorate(inv)
        refreshConfirm(inv)
        DebugUtil.log("Embed", "为 ${player.name} 打开宝石镶嵌台 (装备槽=$EQUIP_SLOT 宝石槽=$GEM_SLOT 确认=$CONFIRM_SLOT)")
        player.openInventory(inv)
    }

    /** 铺背景玻璃 + 两个槽位的说明标签 */
    private fun decorate(inv: Inventory) {
        val filler = buildItem(XMaterial.GRAY_STAINED_GLASS_PANE) { name = " " }
        for (i in 0 until SIZE) inv.setItem(i, filler.clone())
        // 两个活动槽位清空, 留给玩家放东西
        inv.setItem(EQUIP_SLOT, null)
        inv.setItem(GEM_SLOT, null)
        // 槽位上方的提示标签
        inv.setItem(EQUIP_SLOT - 9, buildItem(XMaterial.LIME_STAINED_GLASS_PANE) {
            name = Lang.get("embed.label.equip-top")
        })
        inv.setItem(GEM_SLOT - 9, buildItem(XMaterial.LIGHT_BLUE_STAINED_GLASS_PANE) {
            name = Lang.get("embed.label.gem-top")
        })
        inv.setItem(EQUIP_SLOT + 9, buildItem(XMaterial.LIME_STAINED_GLASS_PANE) {
            name = Lang.get("embed.label.equip-bottom")
        })
        inv.setItem(GEM_SLOT + 9, buildItem(XMaterial.LIGHT_BLUE_STAINED_GLASS_PANE) {
            name = Lang.get("embed.label.gem-bottom")
        })
    }

    private fun isEmpty(item: ItemStack?) = item == null || item.type == Material.AIR

    /**
     * 检查当前两个槽位的组合能不能镶嵌.
     * 返回 null 表示可以, 否则返回给玩家看的失败原因.
     */
    private fun validate(equip: ItemStack?, gem: ItemStack?): String? {
        if (isEmpty(equip)) return Lang.get("embed.need-equip")
        if (isEmpty(gem)) return Lang.get("embed.need-gem")
        val gemId = ItemFactory.getGemId(gem) ?: return Lang.get("embed.not-gem")
        val cfg = GemRegistry.get(gemId) ?: return Lang.get("embed.gem-missing", "gem" to gemId)
        if (cfg.type != GemType.NORMAL) {
            return Lang.get("embed.wrong-type")
        }
        // 宝石声明了专属界面(如符文台)且不包含本界面 -> 引导玩家去正确的地方
        if (cfg.gui.isNotEmpty() && cfg.gui.none { it == GUI_NAME }) {
            return Lang.get("embed.wrong-gui", "gui" to cfg.gui.first())
        }
        if (ItemFactory.getGemId(equip) != null) {
            return Lang.get("embed.equip-is-gem")
        }
        val loreLines = equip!!.itemMeta?.lore?.let { ColorUtil.colorize(it) } ?: emptyList()
        if (!ItemRequireMatcher.matches(cfg.require, equip, loreLines)) {
            return Lang.get("embed.out-of-scope", "scope" to scopeOf(cfg))
        }
        return null
    }

    /** 按当前槽位状态重画确认按钮 */
    private fun refreshConfirm(inv: Inventory) {
        val equip = inv.getItem(EQUIP_SLOT)
        val gem = inv.getItem(GEM_SLOT)
        val problem = validate(equip, gem)
        val gemCfg = ItemFactory.getGemId(gem)?.let { GemRegistry.get(it) }

        val icon = if (problem == null) {
            buildItem(XMaterial.LIME_DYE) {
                name = Lang.get("embed.button.ready-name")
                lore.add(Lang.get("embed.button.equip", "equip" to equip!!.type.name))
                gemCfg?.let { appendGemInfo(this.lore, it) }
                lore.add(" ")
                lore.add(Lang.get("embed.button.cost"))
                shiny()
            }
        } else {
            buildItem(XMaterial.GRAY_DYE) {
                name = Lang.get("embed.button.locked-name")
                lore.add(problem)
                gemCfg?.let {
                    lore.add(" ")
                    appendGemInfo(this.lore, it)
                }
            }
        }
        inv.setItem(CONFIRM_SLOT, icon)
    }

    /** 把宝石的成功率/适用范围/效果说明写到按钮 Lore 上 */
    private fun appendGemInfo(lore: MutableList<String>, cfg: GemConfig) {
        lore.add(Lang.get("embed.info.gem", "gem" to cfg.display.ifBlank { cfg.name }))
        lore.add(Lang.get("embed.info.scope", "scope" to scopeOf(cfg)))
        lore.add(Lang.get("embed.info.chance", "chance" to cfg.success))
        if (cfg.success < 100) {
            lore.add(Lang.get("embed.info.may-fail"))
        }
    }

    /** Require 为空时的"任意物品"文案也走语言文件 */
    private fun scopeOf(cfg: GemConfig): String =
        if (cfg.require.isEmpty()) Lang.get("common.any-item") else cfg.require.joinToString("/")

    @SubscribeEvent
    fun onClick(e: InventoryClickEvent) {
        val holder = e.inventory.holder as? EmbedHolder ?: return
        val player = e.whoClicked as? Player ?: return
        val inv = holder.inv
        val rawSlot = e.rawSlot

        // 点自己背包: 只拦 Shift+左键快速移入, 自己决定东西该进哪个槽
        if (rawSlot < 0 || rawSlot >= inv.size) {
            if (e.action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                handleShiftIn(e, player, inv)
            }
            return
        }

        when (rawSlot) {
            CONFIRM_SLOT -> {
                e.isCancelled = true
                doEmbed(player, inv)
            }
            EQUIP_SLOT, GEM_SLOT -> {
                // 放入/取出走原版行为, 只是放完之后刷新一下确认按钮
                scheduleRefresh(player, inv)
            }
            else -> e.isCancelled = true
        }
    }

    /** Shift+左键: 宝石进宝石槽, 其余物品进装备槽 */
    private fun handleShiftIn(e: InventoryClickEvent, player: Player, inv: Inventory) {
        e.isCancelled = true
        val moving = e.currentItem
        if (isEmpty(moving)) return
        val isGem = ItemFactory.getGemId(moving) != null
        val target = if (isGem) GEM_SLOT else EQUIP_SLOT
        if (!isEmpty(inv.getItem(target))) {
            Lang.send(player, if (isGem) "embed.gem-slot-occupied" else "embed.equip-slot-occupied")
            return
        }
        val one = moving!!.clone()
        one.amount = 1
        inv.setItem(target, one)
        val left = moving.clone()
        left.amount -= 1
        e.currentItem = if (left.amount <= 0) null else left
        DebugUtil.log("Embed", "Shift 移入: ${one.type} -> ${if (isGem) "宝石槽" else "装备槽"}")
        scheduleRefresh(player, inv)
    }

    @SubscribeEvent
    fun onDrag(e: InventoryDragEvent) {
        val holder = e.inventory.holder as? EmbedHolder ?: return
        val touched = e.rawSlots.filter { it < holder.inv.size }
        if (touched.any { it != EQUIP_SLOT && it != GEM_SLOT }) {
            e.isCancelled = true
            return
        }
        (e.whoClicked as? Player)?.let { scheduleRefresh(it, holder.inv) }
    }

    /** 关闭界面时把两个槽位里剩下的东西还给玩家, 避免物品消失 */
    @SubscribeEvent
    fun onClose(e: InventoryCloseEvent) {
        val holder = e.inventory.holder as? EmbedHolder ?: return
        val player = e.player as? Player ?: return
        var returned = 0
        for (slot in intArrayOf(EQUIP_SLOT, GEM_SLOT)) {
            val item = holder.inv.getItem(slot) ?: continue
            if (item.type == Material.AIR) continue
            player.giveItem(item)
            holder.inv.setItem(slot, null)
            returned++
        }
        if (returned > 0) {
            DebugUtil.log("Embed", "关闭镶嵌台, 归还 $returned 组物品给 ${player.name}")
        }
    }

    /** 原版的放入行为在事件结束后才落地, 所以延迟一 tick 再重画按钮 */
    private fun scheduleRefresh(player: Player, inv: Inventory) {
        submit(delay = 1) {
            refreshConfirm(inv)
            player.updateInventory()
        }
    }

    /** 执行一次镶嵌 */
    private fun doEmbed(player: Player, inv: Inventory) {
        val equip = inv.getItem(EQUIP_SLOT)
        val gem = inv.getItem(GEM_SLOT)
        val problem = validate(equip, gem)
        if (problem != null) {
            DebugUtil.log("Embed", "${player.name} 点击确认但条件不满足: $problem")
            player.sendMessage(problem)
            return
        }

        DebugUtil.log(
            "Embed",
            "${player.name} 确认镶嵌: 宝石=${ItemFactory.getGemId(gem)} 装备=${equip!!.type}"
        )
        val result = GemManager.applyToItem(player, gem!!, equip)
        DebugUtil.log(
            "Embed",
            "  结果 success=${result.success} consumed=${result.consumedGem} 有新物品=${result.resultItem != null}"
        )
        Lang.sendRaw(player, result.message)

        // 结果装备写回装备槽, 玩家可以接着镶下一颗
        inv.setItem(EQUIP_SLOT, result.resultItem ?: equip)
        // 消耗一个宝石
        if (result.consumedGem) {
            val left = gem.clone()
            left.amount -= 1
            inv.setItem(GEM_SLOT, if (left.amount <= 0) null else left)
            DebugUtil.log("Embed", "  宝石数量 ${gem.amount} -> ${left.amount.coerceAtLeast(0)}")
        }
        refreshConfirm(inv)
        player.updateInventory()
    }
}
