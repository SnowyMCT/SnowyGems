package mc233.`fun`.snowygems.gui

import mc233.`fun`.snowygems.Permissions
import mc233.`fun`.snowygems.config.GemRegistry
import mc233.`fun`.snowygems.manager.DismantleService
import mc233.`fun`.snowygems.manager.GemManager
import mc233.`fun`.snowygems.manager.OperationAudit
import mc233.`fun`.snowygems.util.ColorUtil
import mc233.`fun`.snowygems.util.DebugUtil
import mc233.`fun`.snowygems.util.Lang
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import taboolib.common.platform.event.SubscribeEvent
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.submit
import taboolib.library.xseries.XMaterial
import taboolib.module.configuration.Configuration
import taboolib.platform.util.SkullTexture
import taboolib.platform.util.buildItem
import taboolib.platform.util.giveItem
import java.io.File

/** Dedicated dismantle workbench. The equipped item lives in its single input slot. */
object DismantleGui {
    data class Style(val type: String, val material: String?, val display: String?, val tips: List<String>, val glow: Boolean)
    data class Layout(val title: String, val rows: List<String>, val styles: Map<Char, Style>) {
        val size get() = rows.size * 9
        fun slots(type: String): List<Int> = rows.flatMapIndexed { row, line ->
            line.mapIndexedNotNull { column, char -> if (styles[char]?.type == type) row * 9 + column else null }
        }
        fun slot(type: String): Int = slots(type).single()
    }

    class Holder(val layout: Layout) : InventoryHolder {
        lateinit var inv: Inventory
        var page = 0
        var processing = false
        var refreshPending = false
        var returned = false
        override fun getInventory(): Inventory = inv
    }

    @Volatile private var layout: Layout? = null

    fun reload() {
        val file = File(getDataFolder(), "gui/dis.yml")
        require(file.isFile) { "缺少 gui/dis.yml" }
        val cfg = Configuration.loadFromFile(file)
        val sec = cfg.getConfigurationSection("拆卸台") ?: error("gui/dis.yml 缺少 拆卸台 节点")
        val rows = sec.getStringList("Slots")
        require(rows.size in 1..6 && rows.all { it.length == 9 }) { "gui/dis.yml: Slots 需要 1 到 6 行，每行 9 个字符" }
        val items = sec.getConfigurationSection("Items") ?: error("gui/dis.yml 缺少 Items")
        val styles = items.getKeys(false).associate { key ->
            require(key.length == 1) { "gui/dis.yml: Items 键必须是一个字符" }
            val item = items.getConfigurationSection(key) ?: error("gui/dis.yml: Items.$key 必须是对象")
            val type = item.getString("Type", "TIP")!!.uppercase()
            require(type in setOf("FILLER", "TIP", "EQUIP_SLOT", "GEM_LIST", "PAGE_PREV", "PAGE_NEXT", "CLOSE")) {
                "gui/dis.yml: Items.$key.Type 不受支持: $type"
            }
            key[0] to Style(type, item.getString("Material"), item.getString("Display"), item.getStringList("Tips"),
                item.getBoolean("Glow", false))
        }
        require(rows.flatMap { it.toList() }.all { it in styles }) { "gui/dis.yml: Slots 中有未定义的 Items 字符" }
        val parsed = Layout(sec.getString("Title", "&8宝石拆卸台") ?: "&8宝石拆卸台", rows, styles)
        for (type in listOf("EQUIP_SLOT", "PAGE_PREV", "PAGE_NEXT", "CLOSE")) {
            require(parsed.slots(type).size == 1) { "gui/dis.yml: $type 必须恰好有一个槽位" }
        }
        require(parsed.slots("GEM_LIST").isNotEmpty()) { "gui/dis.yml: 至少需要一个 GEM_LIST 槽位" }
        layout = parsed
    }

    fun open(player: Player) {
        if (!player.hasPermission(Permissions.DISMANTLE)) return
        val current = layout ?: run { Lang.send(player, "dismantle.plan-error"); return }
        val holder = Holder(current)
        val inv = Bukkit.createInventory(holder, current.size, ColorUtil.colorize(current.title))
        holder.inv = inv
        draw(holder)
        DebugUtil.log("Dismantle", "${player.name} 打开拆卸台，装备槽=${current.slot("EQUIP_SLOT")} 宝石格=${current.slots("GEM_LIST").size}")
        player.openInventory(inv)
    }

    private fun empty(item: ItemStack?) = item == null || item.type.isAir || item.amount <= 0

    private fun render(raw: String?, values: Map<String, String>): String {
        var result = raw ?: ""
        for ((key, value) in values) result = result.replace("{$key}", value)
        return ColorUtil.colorize(result)
    }

    private fun icon(style: Style, fallback: XMaterial, defaultName: String,
                     values: Map<String, String> = emptyMap(), extraLore: List<String> = emptyList(), texture: String? = null): ItemStack {
        val material = style.material?.takeUnless { it.equals("AUTO", true) }
            ?.let { XMaterial.matchXMaterial(it).orElse(fallback) } ?: fallback
        return buildItem(material) {
            if (material == XMaterial.PLAYER_HEAD && !texture.isNullOrBlank()) skullTexture = SkullTexture(texture)
            name = render(style.display ?: defaultName, values)
            lore.addAll(extraLore)
            lore.addAll(style.tips.map { render(it, values) })
            if (style.glow) shiny()
        }
    }

    private fun draw(holder: Holder) {
        val inv = holder.inv
        val layout = holder.layout
        val input = layout.slot("EQUIP_SLOT")
        for (slot in 0 until layout.size) {
            if (slot == input) continue
            val char = layout.rows[slot / 9][slot % 9]
            val style = layout.styles.getValue(char)
            if (style.type == "GEM_LIST" || style.type.startsWith("PAGE_")) continue
            inv.setItem(slot, icon(style, XMaterial.BLACK_STAINED_GLASS_PANE, " "))
        }
        refresh(holder)
    }

    private fun refresh(holder: Holder) {
        val inv = holder.inv
        val item = inv.getItem(holder.layout.slot("EQUIP_SLOT"))
        val ids = if (empty(item) || item!!.amount != 1) emptyList() else GemManager.getAppliedGems(item)
        val gemSlots = holder.layout.slots("GEM_LIST")
        val pages = ((ids.size + gemSlots.size - 1) / gemSlots.size).coerceAtLeast(1)
        holder.page = holder.page.coerceIn(0, pages - 1)
        DebugUtil.log("Dismantle", "刷新拆卸台：装备=${item?.type ?: "空"} 宝石=${ids.size} 当前页=${holder.page + 1}/$pages")
        val gemStyle = holder.layout.styles.values.first { it.type == "GEM_LIST" }
        gemSlots.forEachIndexed { offset, slot ->
            val index = holder.page * gemSlots.size + offset
            val id = ids.getOrNull(index)
            if (id == null) { inv.setItem(slot, null); return@forEachIndexed }
            val gem = GemRegistry.get(id)
            val quote = gem?.let { runCatching { DismantleService.quote(it, item!!) }.getOrNull() }
            val cost = quote?.let { DismantleService.describe(it.cost) } ?: Lang.get("dismantle.plan-error")
            val chance = quote?.let { DismantleService.format(it.success) } ?: "?"
            val gemName = gem?.let { ColorUtil.colorize(it.display.ifBlank { it.name }) } ?: id
            val material = if (gem == null) XMaterial.BARRIER else if (!gem.texture.isNullOrBlank()) XMaterial.PLAYER_HEAD
                else XMaterial.matchXMaterial(gem.material ?: "PAPER").orElse(XMaterial.PAPER)
            inv.setItem(slot, icon(gemStyle, material, gemName,
                mapOf("gem" to gemName, "id" to id, "cost" to cost, "chance" to chance,
                    "index" to (index + 1).toString(), "page" to (holder.page + 1).toString(), "pages" to pages.toString()),
                texture = gem?.texture))
        }
        for (type in listOf("PAGE_PREV", "PAGE_NEXT")) {
            val slot = holder.layout.slot(type)
            val style = holder.layout.styles.values.first { it.type == type }
            val available = if (type == "PAGE_PREV") holder.page > 0 else holder.page + 1 < pages
            val fallback = if (available) XMaterial.ARROW else XMaterial.GRAY_STAINED_GLASS_PANE
            inv.setItem(slot, icon(style, fallback, if (type == "PAGE_PREV") "&e上一页" else "&e下一页",
                mapOf("page" to (holder.page + 1).toString(), "pages" to pages.toString())))
        }
    }

    private fun scheduleRefresh(player: Player, holder: Holder) {
        if (holder.refreshPending) return
        holder.refreshPending = true
        submit(delay = 1) {
            holder.refreshPending = false
            if (player.isOnline && player.openInventory.topInventory === holder.inv && !holder.returned) refresh(holder)
        }
    }

    @SubscribeEvent
    fun onClick(event: InventoryClickEvent) {
        val holder = event.inventory.holder as? Holder ?: return
        val player = event.whoClicked as? Player ?: return
        if (event.isCancelled) return
        if (holder.processing || holder.returned || !player.hasPermission(Permissions.DISMANTLE)) {
            event.isCancelled = true; return
        }
        if (event.action in setOf(InventoryAction.COLLECT_TO_CURSOR, InventoryAction.UNKNOWN, InventoryAction.CLONE_STACK)) {
            event.isCancelled = true; return
        }
        val inv = holder.inv
        val input = holder.layout.slot("EQUIP_SLOT")
        if (event.rawSlot !in 0 until inv.size) {
            if (event.action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                event.isCancelled = true
                val moving = event.currentItem ?: return
                if (empty(moving)) return
                if (!empty(inv.getItem(input))) { Lang.send(player, "embed.equip-slot-occupied"); return }
                val one = moving.clone().apply { amount = 1 }
                inv.setItem(input, one)
                val left = moving.clone().apply { amount-- }
                event.currentItem = left.takeIf { it.amount > 0 }
                refresh(holder)
            }
            return
        }
        if (event.rawSlot == input) {
            if (!acceptInput(event, player)) return
            scheduleRefresh(player, holder)
            return
        }
        event.isCancelled = true
        when (holder.layout.styles[holder.layout.rows[event.rawSlot / 9][event.rawSlot % 9]]?.type) {
            "PAGE_PREV" -> if (holder.page > 0) { holder.page--; refresh(holder) }
            "PAGE_NEXT" -> {
                val count = inv.getItem(input)?.let { GemManager.getAppliedGems(it).size } ?: 0
                if ((holder.page + 1) * holder.layout.slots("GEM_LIST").size < count) { holder.page++; refresh(holder) }
            }
            "CLOSE" -> player.closeInventory()
            "GEM_LIST" -> if (event.click == ClickType.LEFT) {
                val item = inv.getItem(input)
                val list = item?.let { GemManager.getAppliedGems(it) }.orEmpty()
                val gemSlots = holder.layout.slots("GEM_LIST")
                val index = holder.page * gemSlots.size + gemSlots.indexOf(event.rawSlot)
                list.getOrNull(index)?.let { doDismantle(player, holder, it, index) }
            }
        }
    }

    private fun acceptInput(event: InventoryClickEvent, player: Player): Boolean {
        val amount = when (event.action) {
            InventoryAction.PLACE_ALL, InventoryAction.PLACE_SOME -> (event.currentItem?.amount ?: 0) + (event.cursor?.amount ?: 0)
            InventoryAction.PLACE_ONE -> (event.currentItem?.amount ?: 0) + 1
            InventoryAction.SWAP_WITH_CURSOR -> event.cursor?.amount ?: 0
            InventoryAction.HOTBAR_SWAP, InventoryAction.HOTBAR_MOVE_AND_READD -> {
                val incoming = if (event.click == ClickType.SWAP_OFFHAND) player.inventory.getItem(EquipmentSlot.OFF_HAND)
                    else if (event.hotbarButton in 0..8) player.inventory.getItem(event.hotbarButton) else null
                incoming?.amount ?: 0
            }
            else -> 0
        }
        if (amount <= 1) return true
        event.isCancelled = true
        Lang.send(player, "embed.single-equip")
        return false
    }

    @SubscribeEvent
    fun onDrag(event: InventoryDragEvent) {
        val holder = event.inventory.holder as? Holder ?: return
        if (holder.processing || holder.returned) { event.isCancelled = true; return }
        val touched = event.rawSlots.filter { it < holder.inv.size }
        val input = holder.layout.slot("EQUIP_SLOT")
        if (touched.any { it != input } || (event.newItems[input]?.amount ?: 0) > 1) {
            event.isCancelled = true; return
        }
        (event.whoClicked as? Player)?.let { scheduleRefresh(it, holder) }
    }

    @SubscribeEvent
    fun onClose(event: InventoryCloseEvent) {
        val holder = event.inventory.holder as? Holder ?: return
        val player = event.player as? Player ?: return
        if (holder.returned) return
        holder.returned = true
        val slot = holder.layout.slot("EQUIP_SLOT")
        val item = holder.inv.getItem(slot)
        holder.inv.setItem(slot, null)
        if (!empty(item)) player.giveItem(item!!)
        DebugUtil.log("Dismantle", "${player.name} 关闭拆卸台，返还装备=${item?.type ?: "无"}")
    }

    private fun doDismantle(player: Player, holder: Holder, gemId: String, occurrenceIndex: Int) {
        val inv = holder.inv
        val slot = holder.layout.slot("EQUIP_SLOT")
        val item = inv.getItem(slot)
        if (empty(item) || item!!.amount != 1 || GemManager.getAppliedGems(item).getOrNull(occurrenceIndex) != gemId) {
            Lang.send(player, "dismantle.item-changed"); refresh(holder); return
        }
        val gem = GemRegistry.get(gemId) ?: run { Lang.send(player, "gem.config-missing"); return }
        val quote = runCatching { DismantleService.quote(gem, item) }.getOrElse {
            DebugUtil.err("Dismantle", "计算 $gemId 拆卸方案失败", it)
            Lang.send(player, "dismantle.plan-error"); return
        }
        DebugUtil.log("Dismantle", "${player.name} 选择第 ${occurrenceIndex + 1} 颗 $gemId，费用=${DismantleService.describe(quote.cost)}，返还=${DismantleService.format(quote.success)}%")
        holder.processing = true
        try {
            val charge = DismantleService.charge(player, quote.cost)
            if (!charge.paid) {
                Lang.send(player, if (charge.rollbackFailed) "dismantle.refund-failed" else "dismantle.not-afford-multi",
                    "cost" to DismantleService.describe(quote.cost))
                return
            }
            OperationAudit.record(player, "dismantle-charged", "gem=$gemId money=${quote.cost.money} points=${quote.cost.points} exp=${quote.cost.exp} chance=${quote.success}", subject = gemId, outcome = "pending")
            val result = try { GemManager.removeFromItem(player, item, gemId, occurrenceIndex) } catch (error: Exception) {
                val refunded = DismantleService.refund(player, quote.cost)
                OperationAudit.record(player, "dismantle-error", "gem=$gemId refunded=$refunded error=${error.message}", subject = gemId,
                    outcome = if (refunded) "refunded" else "refund-failed")
                Lang.send(player, if (refunded) "gem.undo-conflict" else "dismantle.refund-failed")
                return
            }
            if (!result.success || result.resultItem == null) {
                val refunded = DismantleService.refund(player, quote.cost)
                OperationAudit.record(player, "dismantle-refund", "gem=$gemId refunded=$refunded reason=${result.message}", subject = gemId,
                    outcome = if (refunded) "refunded" else "refund-failed")
                if (!refunded) Lang.send(player, "dismantle.refund-failed")
                Lang.sendRaw(player, result.message)
                return
            }
            inv.setItem(slot, result.resultItem)
            val broke = !DismantleService.rollSuccess(quote)
            if (broke) Lang.send(player, "dismantle.broke", "gem" to ColorUtil.colorize(gem.display.ifBlank { gem.name }))
            else {
                GemManager.give(player, gemId, 1)
                Lang.send(player, "dismantle.success", "gem" to ColorUtil.colorize(gem.display.ifBlank { gem.name }))
            }
            OperationAudit.record(player, "dismantle", "gem=$gemId broken=$broke returned=${!broke}", subject = gemId,
                outcome = if (broke) "broken" else "returned")
            DebugUtil.log("Dismantle", "${player.name} 拆卸完成 gem=$gemId 损坏=$broke 剩余=${GemManager.getAppliedGems(result.resultItem).size}")
            refresh(holder)
            player.updateInventory()
        } finally {
            holder.processing = false
        }
    }
}
