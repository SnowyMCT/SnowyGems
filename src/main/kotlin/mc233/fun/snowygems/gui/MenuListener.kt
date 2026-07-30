package mc233.`fun`.snowygems.gui

import mc233.`fun`.snowygems.config.GemRegistry
import mc233.`fun`.snowygems.config.MenuItemDef
import mc233.`fun`.snowygems.config.MenuLayout
import mc233.`fun`.snowygems.config.MenuRegistry
import mc233.`fun`.snowygems.manager.GemManager
import mc233.`fun`.snowygems.util.ColorUtil
import mc233.`fun`.snowygems.util.DebugUtil
import mc233.`fun`.snowygems.util.ItemFactory
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import taboolib.common.platform.event.SubscribeEvent
import taboolib.platform.util.giveItem

object MenuListener {

    @SubscribeEvent
    fun onClick(e: InventoryClickEvent) {
        val holder = e.inventory.holder as? MenuHolder ?: return
        val layout = MenuRegistry.get(holder.menuName) ?: run {
            DebugUtil.log("Menu", "点击了菜单 ${holder.menuName}, 但找不到对应的布局配置")
            return
        }
        val player = e.whoClicked as? Player ?: return
        val rawSlot = e.rawSlot
        // 点击玩家自己的背包区域: 只需要拦住 Shift+左键快速移入, 避免物品被塞进 TIP 等静态按钮槽
        if (rawSlot < 0 || rawSlot >= e.inventory.size) {
            if (e.action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                handleShiftMoveIn(e, player, layout)
            }
            return
        }

        val c = WorkbenchMenu.charAt(layout, rawSlot)
        val def = c?.let { layout.items[it] }
        DebugUtil.log("Menu", "菜单=${holder.menuName} rawSlot=$rawSlot char=$c type=${def?.type} action=${e.action}")
        if (def == null) {
            e.isCancelled = true
            return
        }

        // ── 核心镶嵌流程 ─────────────────────────────────────────────
        // 玩家先在背包里点起宝石(宝石在光标上), 再点击工作台内已放好的装备 -> 立刻镶嵌.
        // 装备放在 EQUIP_SLOT 还是 GEM_SLOT 都算, 因为镶嵌台里活动槽位很多,
        // 玩家不会去数哪一格才是"装备格".
        if (WorkbenchMenu.isSlotDynamic(def) && tryEmbedWithCursor(e, player)) return

        when (def.type.uppercase()) {
            "EQUIP_SLOT" -> handleEquipClick(e, player, layout)
            "GEM_SLOT" -> handleGemSlotClick(e, def)
            "USE_GEM" -> {
                e.isCancelled = true
                handleUseGem(player, e.inventory, layout, def.gem)
            }
            "PAGE_JUMP" -> {
                e.isCancelled = true
                def.gui?.let { WorkbenchMenu.open(player, it) }
            }
            else -> e.isCancelled = true // TIP / PAGE_PREV / PAGE_NEXT / PAGE_TIP / EMPTY
        }
    }

    /**
     * 光标上拿着宝石, 点击槽内的装备 -> 执行镶嵌.
     * 返回 true 表示本次点击已被当作"镶嵌操作"处理完毕, 调用方不要再走后面的放入/取出逻辑.
     *
     * 只有"光标是宝石 + 槽内有物品 + 槽内物品不是宝石"这一种组合才算镶嵌意图;
     * 其余组合(光标空/光标是装备/槽内也是宝石)一律返回 false, 交回原来的槽位逻辑.
     */
    private fun tryEmbedWithCursor(e: InventoryClickEvent, player: Player): Boolean {
        val cursor = e.cursor
        val target = e.currentItem
        if (isEmpty(cursor) || isEmpty(target)) return false
        val gemId = ItemFactory.getGemId(cursor) ?: return false
        // 槽里放的也是宝石: 那是玩家在整理宝石槽, 不是要往宝石上镶宝石
        if (ItemFactory.getGemId(target) != null) return false

        e.isCancelled = true
        DebugUtil.log(
            "Menu",
            "镶嵌意图: 光标宝石=$gemId (${cursor!!.type} x${cursor.amount}) -> 槽位 ${e.rawSlot} 的 ${target!!.type}"
        )
        applyCursorGem(player, e.inventory, cursor, target, e.rawSlot)
        return true
    }

    @SubscribeEvent
    fun onDrag(e: InventoryDragEvent) {
        val holder = e.inventory.holder as? MenuHolder ?: return
        val layout = MenuRegistry.get(holder.menuName) ?: return
        for (slot in e.rawSlots) {
            if (slot >= e.inventory.size) continue
            val c = WorkbenchMenu.charAt(layout, slot)
            val def = c?.let { layout.items[it] }
            if (def == null || !WorkbenchMenu.isSlotDynamic(def)) {
                e.isCancelled = true
                DebugUtil.log("Menu", "拖拽被取消: 菜单=${holder.menuName} 槽位 $slot 字符=$c type=${def?.type} 不是活动槽位")
                return
            }
        }
        DebugUtil.log("Menu", "拖拽放行: 菜单=${holder.menuName} 涉及槽位=${e.rawSlots.filter { it < e.inventory.size }}")
    }

    /**
     * 关闭菜单时, 把玩家放进 EQUIP_SLOT / GEM_SLOT 里还没被消耗掉的物品还给玩家,
     * 避免物品凭空消失。背包放不下时直接掉落在玩家脚下。
     */
    @SubscribeEvent
    fun onClose(e: InventoryCloseEvent) {
        val holder = e.inventory.holder as? MenuHolder ?: return
        val layout = MenuRegistry.get(holder.menuName) ?: return
        val player = e.player as? Player ?: return
        var returned = 0
        for (row in layout.rows.indices) {
            val rowStr = layout.rows[row]
            for (col in rowStr.indices) {
                val slot = row * 9 + col
                if (slot >= e.inventory.size) continue
                val def = layout.items[rowStr[col]] ?: continue
                if (!WorkbenchMenu.isSlotDynamic(def)) continue
                val item = e.inventory.getItem(slot) ?: continue
                if (item.type == Material.AIR) continue
                player.giveItem(item)
                e.inventory.setItem(slot, null)
                returned++
            }
        }
        if (returned > 0) {
            DebugUtil.log("Menu", "关闭菜单 ${holder.menuName}, 归还了 $returned 组未消耗的物品给 ${player.name}")
        }
    }

    private fun isEmpty(item: ItemStack?) = item == null || item.type == Material.AIR

    /**
     * 玩家在背包里 Shift+左键: 原版会把物品塞进菜单的第一个空槽(可能是装备槽也可能是宝石槽,
     * 甚至可能顶掉静态按钮). 这里改成自己接管 —— 只投放到第一个类型匹配且为空的活动槽位,
     * 找不到合适的槽位就整体取消, 物品留在背包里.
     */
    private fun handleShiftMoveIn(e: InventoryClickEvent, player: Player, layout: MenuLayout) {
        e.isCancelled = true
        val moving = e.currentItem
        if (isEmpty(moving)) return
        val inv = e.inventory
        val isGem = ItemFactory.getGemId(moving) != null

        DebugUtil.log("Menu", "Shift 移入: ${player.name} 把 ${moving!!.type} x${moving.amount} 推向菜单 (isGem=$isGem)")
        val target = dynamicSlots(inv, layout).firstOrNull { (slot, def) ->
            if (!isEmpty(inv.getItem(slot))) return@firstOrNull false
            if (def.type.equals("GEM_SLOT", true)) isGem && gemSlotAccepts(def, moving)
            else !isGem // EQUIP_SLOT 只接收非宝石物品(即待镶嵌的装备)
        }
        if (target == null) {
            DebugUtil.log("Menu", "Shift 移入被拒绝: ${moving!!.type} isGem=$isGem 没有匹配的空槽位")
            player.sendMessage(ColorUtil.colorize("&c没有可以放入该物品的空槽位"))
            return
        }
        val one = moving!!.clone()
        one.amount = 1
        inv.setItem(target.first, one)
        val left = moving.clone()
        left.amount -= 1
        e.currentItem = if (left.amount <= 0) null else left
        DebugUtil.log("Menu", "Shift 移入: ${one.type} -> 槽位 ${target.first} (${target.second.type})")
    }

    /** 列出该菜单中所有 EQUIP_SLOT / GEM_SLOT 的 (rawSlot, 定义), 按槽位顺序 */
    private fun dynamicSlots(inv: Inventory, layout: MenuLayout): List<Pair<Int, MenuItemDef>> {
        val result = ArrayList<Pair<Int, MenuItemDef>>()
        for (row in layout.rows.indices) {
            val rowStr = layout.rows[row]
            for (col in rowStr.indices) {
                val slot = row * 9 + col
                if (slot >= inv.size) continue
                val def = layout.items[rowStr[col]] ?: continue
                if (WorkbenchMenu.isSlotDynamic(def)) result.add(slot to def)
            }
        }
        return result
    }

    /**
     * 判断一个物品是否允许放进带 [MenuItemDef.require] 限制的宝石槽.
     * Require 里写的是分类关键字(如 "红色符文" / "蓝宝石"), 依次拿 宝石ID / Name / Display / Tips
     * 去做包含匹配, 任意一条命中即通过 (OR 语义). 完全不是本插件宝石的物品一律拒绝.
     */
    private fun gemSlotAccepts(def: MenuItemDef, item: ItemStack?): Boolean {
        if (isEmpty(item)) return true
        val gemId = ItemFactory.getGemId(item)
        if (gemId == null) {
            DebugUtil.log("Menu", "gemSlotAccepts: ${item!!.type} 没有 SnowyGems 的 NBT 标记, 拒绝")
            return false
        }
        if (def.require.isEmpty()) {
            DebugUtil.log("Menu", "gemSlotAccepts: 槽位 '${def.char}' 未设置 Require, 放行 $gemId")
            return true
        }
        val cfg = GemRegistry.get(gemId)
        val haystack = buildList {
            add(gemId)
            cfg?.let {
                add(it.name)
                add(it.display)
                addAll(it.tips)
            }
        }
        val ok = def.require.any { raw ->
            val keyword = raw.trim()
            if (keyword.isEmpty() || keyword.equals("ALL", true) || keyword.equals("GEM:ALL", true)) return@any true
            haystack.any { it.contains(keyword) }
        }
        DebugUtil.log(
            "Menu",
            "gemSlotAccepts: 槽位 '${def.char}' require=${def.require} 待放入=$gemId 可匹配文本=$haystack -> $ok"
        )
        return ok
    }

    /**
     * 宝石槽的放入校验. 需要覆盖三种放入方式:
     *  - 光标放置 / 与槽内物品对调 (PLACE_*, SWAP_WITH_CURSOR)
     *  - 从背包 Shift+左键快速移入 (MOVE_TO_OTHER_INVENTORY, 此时点击的是背包侧, 见 onClick 入口)
     *  - 数字键换位 (HOTBAR_SWAP)
     * 取出方向(PICKUP_*)始终放行.
     */
    private fun handleGemSlotClick(e: InventoryClickEvent, def: MenuItemDef) {
        val incoming = when (e.action) {
            InventoryAction.HOTBAR_SWAP, InventoryAction.HOTBAR_MOVE_AND_READD ->
                e.whoClicked.inventory.getItem(e.hotbarButton)
            InventoryAction.PLACE_ONE, InventoryAction.PLACE_SOME, InventoryAction.PLACE_ALL,
            InventoryAction.SWAP_WITH_CURSOR -> e.cursor
            else -> null
        }
        if (isEmpty(incoming)) return
        if (!gemSlotAccepts(def, incoming)) {
            e.isCancelled = true
            val hint = def.require.firstOrNull()
            val player = e.whoClicked as? Player
            DebugUtil.log("Menu", "GEM_SLOT 拒绝放入: gemId=${ItemFactory.getGemId(incoming)} require=${def.require}")
            player?.sendMessage(
                ColorUtil.colorize(
                    if (hint.isNullOrBlank()) "&c这个槽位只能放入本插件的宝石"
                    else "&c该槽位只能放入 &f$hint &c类宝石"
                )
            )
            player?.updateInventory()
        }
    }

    private fun handleEquipClick(e: InventoryClickEvent, player: Player, layout: MenuLayout) {
        val cursor = e.cursor
        val current = e.currentItem
        val cursorEmpty = isEmpty(cursor)
        val currentEmpty = isEmpty(current)
        DebugUtil.log("Menu", "EQUIP_SLOT 点击: cursorEmpty=$cursorEmpty currentEmpty=$currentEmpty action=${e.action}")

        // 手持宝石点击装备槽: 配置里描述的"先点宝石, 再点装备"操作流程 —— 直接用光标上的宝石镶嵌
        if (!cursorEmpty && !currentEmpty && ItemFactory.getGemId(cursor) != null) {
            e.isCancelled = true
            applyCursorGem(player, e.inventory, cursor!!, current!!, e.rawSlot)
            return
        }
        // 光标为空且槽内有装备: 用本菜单内所有 GEM_SLOT 中的物品依次尝试镶嵌
        if (cursorEmpty && !currentEmpty) {
            e.isCancelled = true
            applyAllGemSlots(player, e.inventory, layout, current!!, e.rawSlot)
            return
        }
        // 光标上是普通装备/槽内为空: 放入或取出装备, 允许 vanilla 行为
    }

    /** 用光标上的宝石对装备槽内的装备执行一次镶嵌, 成功后就地更新装备并扣掉一个宝石 */
    private fun applyCursorGem(player: Player, inv: Inventory, gemStack: ItemStack, equip: ItemStack, equipRawSlot: Int) {
        DebugUtil.log(
            "Menu",
            "applyCursorGem: 用光标宝石(${gemStack.type}, GemId=${ItemFactory.getGemId(gemStack)}) 镶嵌 ${equip.type}"
        )
        val result = GemManager.applyToItem(player, gemStack, equip)
        DebugUtil.log(
            "Menu",
            "applyCursorGem 结果: success=${result.success} consumed=${result.consumedGem} " +
                "有新物品=${result.resultItem != null} msg=${result.message}"
        )
        player.sendMessage(ColorUtil.colorize(result.message))
        if (result.consumedGem) {
            val left = gemStack.clone()
            left.amount -= 1
            player.setItemOnCursor(if (left.amount <= 0) null else left)
            DebugUtil.log("Menu", "  光标宝石数量 ${gemStack.amount} -> ${left.amount.coerceAtLeast(0)}")
        }
        inv.setItem(equipRawSlot, result.resultItem ?: equip)
        DebugUtil.log("Menu", "  已把装备写回槽位 $equipRawSlot")
        // 事件被 cancel 后客户端会用服务端的旧快照重画界面, 这里强制同步一次,
        // 否则玩家看到的光标/槽位内容会和服务端不一致(需要按 F3+T 或重开菜单才刷新)
        player.updateInventory()
    }

    private fun applyAllGemSlots(player: Player, inv: Inventory, layout: MenuLayout, equip: ItemStack, equipRawSlot: Int) {
        var current = equip
        val gemSlots = WorkbenchMenu.gemSlots(inv, layout)
        DebugUtil.log("Menu", "applyAllGemSlots: 本菜单共有 ${gemSlots.size} 个 GEM_SLOT: $gemSlots")
        var attempted = false
        for (slot in gemSlots) {
            val gemStack = inv.getItem(slot) ?: continue
            if (gemStack.type == Material.AIR) continue
            attempted = true
            DebugUtil.log("Menu", "applyAllGemSlots: 尝试用槽位 $slot 的物品(${gemStack.type}, GemId=${ItemFactory.getGemId(gemStack)}) 镶嵌")
            val result = GemManager.applyToItem(player, gemStack, current)
            DebugUtil.log(
                "Menu",
                "applyAllGemSlots: 槽位 $slot 结果 success=${result.success} consumed=${result.consumedGem} msg=${result.message}"
            )
            player.sendMessage(ColorUtil.colorize(result.message))
            if (result.consumedGem) {
                val left = gemStack.clone()
                left.amount -= 1
                inv.setItem(slot, if (left.amount <= 0) null else left)
            }
            if (result.success && result.resultItem != null) {
                current = result.resultItem
            }
        }
        if (!attempted) {
            DebugUtil.log("Menu", "applyAllGemSlots: 所有 GEM_SLOT 都是空的, 没有可镶嵌的宝石")
            player.sendMessage(ColorUtil.colorize("&c请先点起一颗宝石, 再点击这件装备来镶嵌"))
        }
        inv.setItem(equipRawSlot, current)
        player.updateInventory()
    }

    private fun handleUseGem(player: Player, inv: Inventory, layout: MenuLayout, gemId: String?) {
        if (gemId == null) return
        val cfg = GemRegistry.get(gemId) ?: run {
            player.sendMessage(ColorUtil.colorize("&c该按钮引用的宝石配置不存在: $gemId"))
            DebugUtil.log("Menu", "USE_GEM 按钮引用的宝石配置不存在: $gemId")
            return
        }
        val equipRawSlot = WorkbenchMenu.findEquipSlot(inv, layout)
        val equipItem = if (equipRawSlot >= 0) inv.getItem(equipRawSlot) else null
        val hasEquip = !isEmpty(equipItem)
        DebugUtil.log("Menu", "USE_GEM: gemId=$gemId equipRawSlot=$equipRawSlot hasEquip=$hasEquip")

        // 该菜单存在装备槽却是空的: 说明这个按钮需要一个目标物品, 先提示玩家放入而不是空跑一次
        if (equipRawSlot >= 0 && !hasEquip) {
            val equipDef = WorkbenchMenu.charAt(layout, equipRawSlot)?.let { layout.items[it] }
            val hint = equipDef?.require?.firstOrNull { !it.equals("GEM:ALL", true) && !it.equals("ALL", true) }
            player.sendMessage(
                ColorUtil.colorize(
                    if (hint.isNullOrBlank()) "&c请先在左侧槽位放入要操作的物品"
                    else "&c请先在左侧槽位放入 &f$hint"
                )
            )
            DebugUtil.log("Menu", "USE_GEM 被拒绝: 菜单有 EQUIP_SLOT($equipRawSlot) 但槽内为空")
            return
        }

        val result = GemManager.executeButton(player, cfg, if (hasEquip) equipItem else null)
        DebugUtil.log("Menu", "USE_GEM 执行结果: success=${result.success} msg=${result.message}")
        player.sendMessage(ColorUtil.colorize(result.message))
        if (hasEquip) {
            // 只有成功才消耗目标物品; 失败时把物品原样留在槽里, 避免玩家白丢东西
            if (result.success) {
                inv.setItem(equipRawSlot, null)
                DebugUtil.log("Menu", "USE_GEM 成功, 已消耗装备槽内的目标物品")
            } else {
                // reward 可能改写过物品(如扣耐久), 有返回值就写回, 否则保持原物品
                inv.setItem(equipRawSlot, result.resultItem ?: equipItem)
                DebugUtil.log("Menu", "USE_GEM 失败, 目标物品保留在装备槽内")
            }
        }
    }
}
