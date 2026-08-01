package mc233.`fun`.snowygems.gui

import mc233.`fun`.snowygems.config.GemConfig
import mc233.`fun`.snowygems.config.GemRegistry
import mc233.`fun`.snowygems.manager.DismantleService
import mc233.`fun`.snowygems.manager.GemManager
import mc233.`fun`.snowygems.util.ColorUtil
import mc233.`fun`.snowygems.util.Lang
import mc233.`fun`.snowygems.util.DebugUtil
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import taboolib.common.platform.event.SubscribeEvent
import taboolib.library.xseries.XMaterial
import taboolib.module.ui.openMenu
import taboolib.module.ui.type.Linked
import taboolib.platform.util.buildItem

/**
 * 宝石相关的三个 GUI, 各自独立:
 *
 *   [open]         /sgem view      —— 管理员按分类浏览并领取宝石(第一层分类, 第二层宝石列表, 带返回按钮)
 *   [openInspect]  /sgem inspect   —— 只读: 查看手持装备上已镶嵌的宝石信息, 不做任何修改
 *   [openDismantle]/sgem dismantle —— 拆卸: 列出手持装备的已镶嵌宝石, 左键拆卸(按 config.yml 收费 + 损坏概率)
 *
 * 拆卸和查看拆成两个指令两个界面: inspect 是纯查看不误触, dismantle 才真正扣费拆卸。
 */
object GemGui {

    private class CategoryHolder : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    // ══════════════════════════════════════════════════════════
    //  /sgem view —— 分类浏览 / 领取
    // ══════════════════════════════════════════════════════════

    fun open(player: Player) {
        val categories = GemRegistry.categories()
        DebugUtil.log("GUI", "为 ${player.name} 打开分类浏览面板, 分类数=${categories.size} $categories")
        if (categories.isEmpty()) {
            DebugUtil.log("GUI", "  没有任何分类可展示: GemRegistry 为空, 请检查 gems/ 目录")
            Lang.send(player, "view.no-gem")
            return
        }
        val holder = CategoryHolder()
        val size = (((categories.size - 1) / 9) + 1) * 9
        val inv = Bukkit.createInventory(holder, size.coerceIn(9, 54), Lang.get("view.title"))
        holder.inv = inv
        categories.forEachIndexed { index, category ->
            if (index >= inv.size) return@forEachIndexed
            val count = GemRegistry.byCategory(category).size
            inv.setItem(index, buildItem(XMaterial.CHEST) {
                name = Lang.get("view.category-name", "category" to category)
                lore.add(Lang.get("view.category-count", "count" to count))
                lore.add(Lang.get("view.category-click"))
            })
        }
        player.openInventory(inv)
    }

    @SubscribeEvent
    fun onCategoryClick(e: InventoryClickEvent) {
        val holder = e.inventory.holder as? CategoryHolder ?: return
        e.isCancelled = true
        val player = e.whoClicked as? Player ?: return
        val slot = e.rawSlot
        if (slot < 0 || slot >= e.inventory.size) return
        val categories = GemRegistry.categories()
        DebugUtil.log("GUI", "${player.name} 点击分类面板 rawSlot=$slot (共 ${categories.size} 个分类)")
        if (slot >= categories.size) return
        openCategory(player, categories[slot])
    }

    private fun openCategory(player: Player, category: String) {
        val gems = GemRegistry.byCategory(category)
        DebugUtil.log("GUI", "为 ${player.name} 打开分类 $category, 共 ${gems.size} 个条目: ${gems.map { it.id }}")
        player.openMenu<Linked<GemConfig>>(Lang.get("view.category-title", "category" to category)) {
            rows(6)
            slots((0..44).toList())
            elements { gems }

            onGenerate { _, gem, _, _ ->
                val mat = if (!gem.texture.isNullOrBlank()) XMaterial.PLAYER_HEAD
                else XMaterial.matchXMaterial(gem.material ?: "PAPER").orElse(XMaterial.PAPER)
                buildItem(mat) {
                    if (!gem.texture.isNullOrBlank()) {
                        skullTexture = taboolib.platform.util.SkullTexture(gem.texture)
                    }
                    name = ColorUtil.colorize(gem.display.ifBlank { gem.name })
                    lore.addAll(ColorUtil.colorize(gem.tips))
                    lore.add(" ")
                    lore.add(Lang.get("view.gem-id", "id" to gem.id))
                    lore.add(Lang.get("view.gem-click"))
                    if (gem.glow) shiny()
                }
            }

            onClick { event, gem ->
                event.isCancelled = true
                val amount = if (event.clickEvent().isShiftClick) 64 else 1
                DebugUtil.log("GUI", "${player.name} 从分类 $category 领取 ${gem.id} x$amount (shift=${event.clickEvent().isShiftClick})")
                GemManager.give(player, gem.id, amount)
                Lang.send(player, "view.claimed", "gem" to gem.display.ifBlank { gem.name }, "amount" to amount)
            }

            // 返回按钮: 回到分类列表. 放在底部中间(slot 49).
            set(49, buildItem(XMaterial.BARRIER) { name = Lang.get("common.back") }) {
                DebugUtil.log("GUI", "${player.name} 从分类 $category 点击返回, 回到分类列表")
                open(player)
            }

            setNextPage(50) { _, hasNext -> pageIcon(hasNext, true) }
            setPreviousPage(48) { _, hasPrev -> pageIcon(hasPrev, false) }
        }
    }

    // ══════════════════════════════════════════════════════════
    //  /sgem inspect —— 只读查看已镶嵌宝石(不做修改)
    // ══════════════════════════════════════════════════════════

    fun openInspect(player: Player) {
        val held = player.inventory.itemInMainHand
        if (held.type.isAir) {
            DebugUtil.log("GUI", "${player.name} 打开 inspect 失败: 手上没有物品")
            Lang.send(player, "inspect.need-held")
            return
        }
        val gems = GemManager.getAppliedGemConfigs(held)
        DebugUtil.log("GUI", "${player.name} inspect ${held.type}: 已镶嵌=${gems.map { it.id }}")
        if (gems.isEmpty()) {
            Lang.send(player, "inspect.no-embed")
            return
        }
        player.openMenu<Linked<GemConfig>>(Lang.get("inspect.title", "item" to (held.itemMeta?.displayName ?: held.type.name))) {
            rows(6)
            slots(listOf(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34))
            elements { gems }
            onGenerate { _, gem, _, _ ->
                val mat = if (!gem.texture.isNullOrBlank()) XMaterial.PLAYER_HEAD
                else XMaterial.matchXMaterial(gem.material ?: "PAPER").orElse(XMaterial.PAPER)
                buildItem(mat) {
                    if (!gem.texture.isNullOrBlank()) {
                        skullTexture = taboolib.platform.util.SkullTexture(gem.texture)
                    }
                    name = ColorUtil.colorize(gem.display.ifBlank { gem.name })
                    lore.addAll(ColorUtil.colorize(gem.tips))
                    lore.add(" ")
                    lore.add(Lang.get("inspect.readonly-hint"))
                    if (gem.glow) shiny()
                }
            }
            onClick { event, _ -> event.isCancelled = true } // 纯查看, 点击不做任何事
            setNextPage(40) { _, hasNext -> pageIcon(hasNext, true) }
            setPreviousPage(38) { _, hasPrev -> pageIcon(hasPrev, false) }
        }
    }

    // ══════════════════════════════════════════════════════════
    //  /sgem dismantle —— 拆卸(扣费 + 损坏概率)
    // ══════════════════════════════════════════════════════════

    fun openDismantle(player: Player) {
        val held = player.inventory.itemInMainHand
        if (held.type.isAir) {
            DebugUtil.log("GUI", "${player.name} 打开 dismantle 失败: 手上没有物品")
            Lang.send(player, "dismantle.need-held")
            return
        }
        val gems = GemManager.getAppliedGemConfigs(held)
        DebugUtil.log("GUI", "${player.name} dismantle ${held.type}: 已镶嵌=${gems.map { it.id }}")
        if (gems.isEmpty()) {
            Lang.send(player, "dismantle.no-embed")
            return
        }
        val costName = DismantleService.costTypeName()
        val costAmount = DismantleService.costAmount()
        val breakChance = DismantleService.breakChance()
        player.openMenu<Linked<GemConfig>>(Lang.get("dismantle.title", "item" to (held.itemMeta?.displayName ?: held.type.name))) {
            rows(6)
            slots(listOf(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34))
            elements { gems }
            onGenerate { _, gem, _, _ ->
                val mat = if (!gem.texture.isNullOrBlank()) XMaterial.PLAYER_HEAD
                else XMaterial.matchXMaterial(gem.material ?: "PAPER").orElse(XMaterial.PAPER)
                buildItem(mat) {
                    if (!gem.texture.isNullOrBlank()) {
                        skullTexture = taboolib.platform.util.SkullTexture(gem.texture)
                    }
                    name = ColorUtil.colorize(gem.display.ifBlank { gem.name })
                    lore.addAll(ColorUtil.colorize(gem.tips))
                    lore.add(" ")
                    if (DismantleService.costEnabled() && costAmount > 0) {
                        lore.add(Lang.get("dismantle.cost-line", "amount" to formatAmount(costAmount), "type" to costName))
                    }
                    if (breakChance > 0) {
                        lore.add(Lang.get("dismantle.break-line", "chance" to breakChance))
                    }
                    lore.add(Lang.get("dismantle.click-hint"))
                    if (gem.glow) shiny()
                }
            }
            onClick { event, gem ->
                event.isCancelled = true
                doDismantle(player, gem.id)
            }
            setNextPage(40) { _, hasNext -> pageIcon(hasNext, true) }
            setPreviousPage(38) { _, hasPrev -> pageIcon(hasPrev, false) }
        }
    }

    /**
     * 执行一次拆卸:
     *   1. 校验并扣除费用(余额不足直接拒绝)
     *   2. 撤销宝石效果 + 从装备摘掉
     *   3. 掷骰子判定宝石是否损坏 —— 未损坏则返还宝石实体
     * 全程针对"当前手持"物品, 拆完重开拆卸界面刷新列表。
     */
    private fun doDismantle(player: Player, gemId: String) {
        val hand = player.inventory.itemInMainHand
        if (hand.type.isAir || !GemManager.getAppliedGems(hand).contains(gemId)) {
            DebugUtil.log("GUI", "${player.name} 拆卸失败: 手持物品已变化或不含 $gemId")
            Lang.send(player, "dismantle.item-changed")
            player.closeInventory()
            return
        }
        // 1) 扣费
        if (!DismantleService.canAfford(player)) {
            Lang.send(player, "dismantle.not-afford",
                "amount" to formatAmount(DismantleService.costAmount()), "type" to DismantleService.costTypeName())
            return
        }
        if (!DismantleService.charge(player)) {
            Lang.send(player, "dismantle.not-afford",
                "amount" to formatAmount(DismantleService.costAmount()), "type" to DismantleService.costTypeName())
            return
        }
        // 2) 撤销效果并摘掉
        val result = GemManager.removeFromItem(player, hand, gemId)
        DebugUtil.log("GUI", "${player.name} 拆卸 $gemId: success=${result.success} 有新物品=${result.resultItem != null}")
        result.resultItem?.let { player.inventory.setItemInMainHand(it) }
        // 3) 损坏判定
        val broke = DismantleService.rollBreak()
        if (broke) {
            Lang.send(player, "dismantle.broke", "gem" to gemName(gemId))
            DebugUtil.log("GUI", "  宝石 $gemId 拆卸时损坏, 不返还")
        } else {
            GemManager.give(player, gemId, 1)
            Lang.send(player, "dismantle.success", "gem" to gemName(gemId))
            DebugUtil.log("GUI", "  宝石 $gemId 拆卸成功并返还")
        }
        // 刷新界面
        player.closeInventory()
        openDismantle(player)
    }

    private fun gemName(gemId: String): String {
        val cfg = GemRegistry.get(gemId) ?: return gemId
        return ColorUtil.colorize(cfg.display.ifBlank { cfg.name })
    }

    /** 费用是整数时不显示小数, 否则保留两位 */
    private fun formatAmount(amount: Double): String =
        if (amount == amount.toLong().toDouble()) amount.toLong().toString() else "%.2f".format(amount)

    /** 翻页按钮外观统一走语言文件, 有页则箭头, 无页则屏障 */
    private fun pageIcon(has: Boolean, next: Boolean) =
        if (has) buildItem(XMaterial.ARROW) {
            name = Lang.get(if (next) "common.next-page" else "common.prev-page")
        } else buildItem(XMaterial.BARRIER) {
            name = Lang.get(if (next) "common.next-page-none" else "common.prev-page-none")
        }
}
