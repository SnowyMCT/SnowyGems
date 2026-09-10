package mc233.`fun`.snowygems.gui

import mc233.`fun`.snowygems.config.GemRegistry
import mc233.`fun`.snowygems.config.MenuItemDef
import mc233.`fun`.snowygems.manager.GemManager
import mc233.`fun`.snowygems.util.DebugUtil
import mc233.`fun`.snowygems.util.ItemFactory
import mc233.`fun`.snowygems.util.Lang
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.ItemStack
import taboolib.common.platform.event.SubscribeEvent
import taboolib.common.platform.function.submit
import taboolib.platform.util.giveItem

object MenuListener {

    @SubscribeEvent
    fun onClick(e: InventoryClickEvent) {
        val holder = e.inventory.holder as? MenuHolder ?: return
        val player = e.whoClicked as? Player ?: return
        if (e.isCancelled) return
        // Double-click collection searches the entire view, including decorative icons.
        if (holder.returned || holder.processing || e.action in setOf(
                InventoryAction.COLLECT_TO_CURSOR, InventoryAction.CLONE_STACK, InventoryAction.UNKNOWN)) {
            e.isCancelled = true
            return
        }
        if (e.rawSlot !in 0 until e.inventory.size) {
            if (e.action == InventoryAction.MOVE_TO_OTHER_INVENTORY) shiftMoveIn(e, player, holder)
            return
        }
        e.isCancelled = true
        val def = holder.slots[e.rawSlot] ?: return
        if (!WorkbenchMenu.isSlotDynamic(def)) {
            // Number keys, item dropping and middle-clicking must never execute a button.
            if (e.click != ClickType.LEFT && e.click != ClickType.RIGHT) return
            when (def.type.uppercase()) {
                "CONFIRM_EMBED" -> applyGemSlots(player, holder)
                "RUNE_FORGE" -> submit(delay = 1) {
                    if (!holder.returned && player.openInventory.topInventory === holder.inv) RuneForgeGui.open(player)
                }
                "USE_GEM" -> useButton(player, holder, def.gem)
                "PAGE_JUMP" -> def.gui?.let { menu ->
                    submit { if (player.openInventory.topInventory === holder.inv) WorkbenchMenu.open(player, menu) }
                }
                "CLOSE" -> submit { if (player.openInventory.topInventory === holder.inv) player.closeInventory() }
            }
            return
        }

        val target = e.currentItem
        if (def.type.equals("EQUIP_SLOT", true) && !isEmpty(target)) {
            if (e.click == ClickType.SHIFT_RIGHT && isEmpty(e.cursor) &&
                holder.slots.values.none { it.type.equals("CONFIRM_EMBED", true) }) {
                applyGemSlots(player, holder)
                return
            }
            // Preserve the intuitive gem-then-equipment gesture, but stage it for preview first.
            if ((e.click == ClickType.LEFT || e.click == ClickType.RIGHT) &&
                ItemFactory.getGemId(target) == null && !isEmpty(e.cursor) && ItemFactory.getGemId(e.cursor) != null) {
                stageCursorGem(player, holder, e.cursor!!)
                return
            }
            // 兼容旧菜单和没有明显确认按钮的布局：右键已有装备视为确认镶嵌。
            // 左键仍然只取回装备，关闭菜单也只返还输入，不会意外消耗符文。
            if (e.click == ClickType.RIGHT && !isEmpty(target) &&
                WorkbenchMenu.gemSlots(holder.inv, holder.layout).any { !isEmpty(holder.inv.getItem(it)) }) {
                applyGemSlots(player, holder)
                return
            }
        }
        when (e.action) {
            InventoryAction.PLACE_ONE, InventoryAction.PLACE_SOME, InventoryAction.PLACE_ALL,
            InventoryAction.SWAP_WITH_CURSOR -> placeCursor(e, player, holder, def)
            InventoryAction.HOTBAR_SWAP, InventoryAction.HOTBAR_MOVE_AND_READD -> {
                val incoming = when {
                    e.click == ClickType.SWAP_OFFHAND -> player.inventory.itemInOffHand
                    e.hotbarButton in 0..8 -> player.inventory.getItem(e.hotbarButton)
                    else -> return
                }
                if (!isEmpty(incoming)) {
                    val reason = MenuSlotRules.rejection(holder.layout, def, incoming!!)
                    if (reason != null) { Lang.sendRaw(player, reason); return }
                    val limit = MenuSlotRules.limit(def, incoming)
                    if (incoming.amount > limit) {
                        Lang.send(player, "menu.slot-limit", "limit" to limit)
                        return
                    }
                }
                e.isCancelled = false
                refreshLater(player, holder)
            }
            InventoryAction.MOVE_TO_OTHER_INVENTORY -> {
                if (!isEmpty(target)) {
                    holder.inv.setItem(e.rawSlot, null)
                    player.giveItem(target!!.clone())
                    refreshNow(player, holder)
                }
            }
            InventoryAction.PICKUP_ALL, InventoryAction.PICKUP_HALF, InventoryAction.PICKUP_ONE,
            InventoryAction.PICKUP_SOME, InventoryAction.DROP_ALL_SLOT, InventoryAction.DROP_ONE_SLOT -> {
                // Taking out an item is never an implicit request to consume gems.
                e.isCancelled = false
                refreshLater(player, holder)
            }
            else -> Unit
        }
    }

    @SubscribeEvent
    fun onDrag(e: InventoryDragEvent) {
        val holder = e.inventory.holder as? MenuHolder ?: return
        val player = e.whoClicked as? Player ?: return
        if (e.isCancelled) return
        if (holder.returned || holder.processing) { e.isCancelled = true; return }
        var changed = false
        for ((slot, item) in e.newItems) {
            if (slot !in 0 until holder.inv.size) continue
            val def = holder.dynamicSlots[slot]
            if (def == null) { e.isCancelled = true; return }
            val reason = MenuSlotRules.rejection(holder.layout, def, item)
            if (reason != null) { e.isCancelled = true; Lang.sendRaw(player, reason); return }
            val limit = MenuSlotRules.limit(def, item)
            if (item.amount > limit) {
                e.isCancelled = true
                Lang.send(player, "menu.slot-limit", "limit" to limit)
                return
            }
            changed = true
        }
        if (changed) refreshLater(player, holder)
    }

    @SubscribeEvent
    fun onClose(e: InventoryCloseEvent) {
        val holder = e.inventory.holder as? MenuHolder ?: return
        val player = e.player as? Player ?: return
        // A reward can open another inventory during execution. Commit its result before
        // returning inputs, otherwise the unmodified original target would be duplicated.
        if (holder.processing) holder.closeRequested = true else returnItems(player, holder)
    }

    private fun returnItems(player: Player, holder: MenuHolder) {
        if (holder.returned) return
        holder.returned = true
        val items = holder.dynamicSlots.keys.mapNotNull { slot ->
            holder.inv.getItem(slot)?.takeUnless(::isEmpty)?.clone().also { holder.inv.setItem(slot, null) }
        }
        items.forEach { player.giveItem(it) }
        if (items.isNotEmpty()) {
            Lang.send(player, "menu.returned", "count" to items.size)
            DebugUtil.log("Menu", "关闭 ${holder.menuName}, 已归还 ${items.size} 组物品给 ${player.name}")
        }
    }

    private fun isEmpty(item: ItemStack?): Boolean = item == null || item.type.isAir || item.amount <= 0

    private fun shiftMoveIn(e: InventoryClickEvent, player: Player, holder: MenuHolder) {
        e.isCancelled = true
        val moving = e.currentItem?.takeUnless(::isEmpty) ?: return
        val slot = findReceivingSlot(holder, moving)
        if (slot == null) { Lang.send(player, "menu.no-slot"); return }
        addOne(holder, slot, moving)
        e.currentItem = moving.clone().also { it.amount-- }.takeUnless(::isEmpty)
        refreshNow(player, holder)
    }

    private fun findReceivingSlot(holder: MenuHolder, item: ItemStack, gemsOnly: Boolean = false): Int? =
        holder.dynamicSlots.entries.firstOrNull { (slot, def) ->
            if (gemsOnly && !def.type.equals("GEM_SLOT", true)) return@firstOrNull false
            if (MenuSlotRules.rejection(holder.layout, def, item) != null) return@firstOrNull false
            val current = holder.inv.getItem(slot)
            isEmpty(current) || (current!!.isSimilar(item) && current.amount < MenuSlotRules.limit(def, item))
        }?.key

    private fun addOne(holder: MenuHolder, slot: Int, item: ItemStack) {
        val current = holder.inv.getItem(slot)
        holder.inv.setItem(slot, item.clone().also { it.amount = if (isEmpty(current)) 1 else current!!.amount + 1 })
    }

    private fun stageCursorGem(player: Player, holder: MenuHolder, gem: ItemStack) {
        val slot = findReceivingSlot(holder, gem, gemsOnly = true)
        if (slot == null) { Lang.send(player, "menu.no-slot"); return }
        addOne(holder, slot, gem)
        player.setItemOnCursor(gem.clone().also { it.amount-- }.takeUnless(::isEmpty))
        refreshNow(player, holder)
    }

    private fun placeCursor(e: InventoryClickEvent, player: Player, holder: MenuHolder, def: MenuItemDef) {
        val incoming = e.cursor?.takeUnless(::isEmpty) ?: return
        val reason = MenuSlotRules.rejection(holder.layout, def, incoming)
        if (reason != null) { Lang.sendRaw(player, reason); return }
        val limit = MenuSlotRules.limit(def, incoming)
        val current = e.currentItem?.takeUnless(::isEmpty)
        if (current != null && !current.isSimilar(incoming)) {
            if (incoming.amount > limit) { Lang.send(player, "menu.slot-limit", "limit" to limit); return }
            holder.inv.setItem(e.rawSlot, incoming.clone())
            player.setItemOnCursor(current.clone())
        } else {
            val space = limit - (current?.amount ?: 0)
            val count = minOf(space, if (e.action == InventoryAction.PLACE_ONE) 1 else incoming.amount)
            if (count <= 0) { Lang.send(player, "menu.slot-limit", "limit" to limit); return }
            holder.inv.setItem(e.rawSlot, incoming.clone().also { it.amount = (current?.amount ?: 0) + count })
            player.setItemOnCursor(incoming.clone().also { it.amount -= count }.takeUnless(::isEmpty))
        }
        refreshNow(player, holder)
    }

    private fun applyGemSlots(player: Player, holder: MenuHolder) {
        val inv = holder.inv
        val equipSlot = WorkbenchMenu.findEquipSlot(inv, holder.layout)
        var target = if (equipSlot >= 0) inv.getItem(equipSlot)?.takeUnless(::isEmpty) else null
        if (target == null) { Lang.send(player, "embed.need-equip"); return }
        val gemSlots = WorkbenchMenu.gemSlots(inv, holder.layout).filter { !isEmpty(inv.getItem(it)) }
        if (gemSlots.isEmpty()) { Lang.send(player, "embed.need-gem"); return }
        // Refuse invalid input before any reward, random roll or cost.
        for (slot in gemSlots) {
            val gem = inv.getItem(slot) ?: continue
            val reason = MenuSlotRules.rejection(holder.layout, holder.slots.getValue(slot), gem)
                ?: GemManager.validateApply(gem, target, holder.menuName)
            if (reason != null) {
                Lang.send(player, "menu.preview-invalid", "slot" to (slot + 1), "reason" to reason)
                refreshNow(player, holder)
                return
            }
        }
        holder.processing = true
        try {
            var succeeded = 0
            var failed = 0
            for (slot in gemSlots) {
                val gem = inv.getItem(slot)?.takeUnless(::isEmpty) ?: continue
                val result = GemManager.applyToItem(player, gem, target!!, holder.menuName)
                if (result.consumedGem) inv.setItem(slot, gem.clone().also { it.amount-- }.takeUnless(::isEmpty))
                target = result.resultItem ?: target
                // Commit each result, including failure-side changes, before the next attempt.
                inv.setItem(equipSlot, target)
                if (result.success) succeeded++ else failed++
                Lang.sendRaw(player, result.message)
                if (holder.closeRequested) break
            }
            Lang.send(player, "menu.batch-result", "success" to succeeded, "failed" to failed)
        } finally {
            finishOperation(player, holder)
        }
    }

    private fun useButton(player: Player, holder: MenuHolder, gemId: String?) {
        val cfg = gemId?.let(GemRegistry::get)
        if (cfg == null) { Lang.send(player, "menu.button-gem-missing", "gem" to (gemId ?: "?")); return }
        val slot = WorkbenchMenu.findEquipSlot(holder.inv, holder.layout)
        val target = if (slot >= 0) holder.inv.getItem(slot)?.takeUnless(::isEmpty) else null
        if (slot >= 0 && target == null) { Lang.send(player, "menu.need-target"); return }
        if (target != null) {
            val def = holder.slots.getValue(slot)
            val reason = MenuSlotRules.rejection(holder.layout, def, target)
            if (reason != null) { Lang.sendRaw(player, reason); return }
            if (target.amount != 1) { Lang.send(player, "menu.slot-limit", "limit" to 1); return }
        }
        holder.processing = true
        try {
            val result = GemManager.executeButton(player, cfg, target)
            if (target != null) holder.inv.setItem(slot, if (result.consumedGem) null else result.resultItem ?: target)
            Lang.sendRaw(player, result.message)
        } finally {
            finishOperation(player, holder)
        }
    }

    private fun finishOperation(player: Player, holder: MenuHolder) {
        holder.processing = false
        if (holder.closeRequested) returnItems(player, holder) else refreshNow(player, holder)
    }

    private fun refreshNow(player: Player, holder: MenuHolder) {
        WorkbenchMenu.refresh(holder)
        player.updateInventory()
    }

    private fun refreshLater(player: Player, holder: MenuHolder) {
        submit(delay = 1) {
            if (!holder.returned && player.openInventory.topInventory === holder.inv) refreshNow(player, holder)
        }
    }
}
