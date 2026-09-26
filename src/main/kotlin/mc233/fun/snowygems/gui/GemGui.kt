package mc233.`fun`.snowygems.gui

import mc233.`fun`.snowygems.Permissions
import mc233.`fun`.snowygems.config.GemConfig
import mc233.`fun`.snowygems.config.GemRegistry
import mc233.`fun`.snowygems.manager.GemManager
import mc233.`fun`.snowygems.util.ColorUtil
import mc233.`fun`.snowygems.util.Lang
import mc233.`fun`.snowygems.util.DebugUtil
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import taboolib.common.platform.function.submit
import taboolib.library.xseries.XMaterial
import taboolib.module.ui.openMenu
import taboolib.module.ui.type.Linked
import taboolib.platform.util.buildItem

/**
 * 宝石相关的三个 GUI, 各自独立:
 *
 *   [open]         /sgem view      —— 管理员按分类浏览并领取宝石(第一层分类, 第二层宝石列表, 带返回按钮)
 *   [openInspect]  /sgem inspect   —— 只读: 查看手持装备上已镶嵌的宝石信息, 不做任何修改
 *   [openDismantle]/sgem dismantle —— 拆卸: 按独立拆卸方案展示每颗宝石的价格与返还成功率
 *
 * 拆卸和查看拆成两个指令两个界面: inspect 是纯查看不误触, dismantle 才真正扣费拆卸。
 */
object GemGui {

    private var sessionGeneration = 0L

    fun invalidateSessions() { sessionGeneration++ }

    /** Bukkit 禁止在背包点击事件中直接重开界面；下一 tick 且原界面仍打开时才执行。 */
    private fun afterClick(player: Player, inventory: Inventory, generation: Long, action: () -> Unit) {
        submit(delay = 1) {
            if (player.isOnline && player.openInventory.topInventory === inventory && generation == sessionGeneration) {
                action()
            }
        }
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
        val generation = sessionGeneration
        player.openMenu<Linked<String>>(Lang.get("view.title")) {
            rows(6)
            slots((0..44).toList())
            elements { categories }
            onGenerate { _, category, _, _ ->
                buildItem(XMaterial.CHEST) {
                    name = Lang.get("view.category-name", "category" to category)
                    lore.add(Lang.get("view.category-count", "count" to GemRegistry.byCategory(category).size))
                    lore.add(Lang.get("view.category-click"))
                }
            }
            onClick { event, category ->
                event.isCancelled = true
                if (event.clickEvent().click == ClickType.LEFT) {
                    afterClick(player, event.clickEvent().view.topInventory, generation) { openCategory(player, category) }
                }
            }
            setNextPage(50) { _, hasNext -> pageIcon(hasNext, true) }
            setPreviousPage(48) { _, hasPrev -> pageIcon(hasPrev, false) }
        }
    }

    private fun openCategory(player: Player, category: String) {
        val generation = sessionGeneration
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
                if (generation != sessionGeneration || !player.hasPermission(Permissions.VIEW)) return@onClick
                if (event.clickEvent().click !in listOf(ClickType.LEFT, ClickType.SHIFT_LEFT)) return@onClick
                val amount = if (event.clickEvent().isShiftClick) 64 else 1
                DebugUtil.log("GUI", "${player.name} 从分类 $category 领取 ${gem.id} x$amount (shift=${event.clickEvent().isShiftClick})")
                GemManager.give(player, gem.id, amount)
                Lang.send(player, "view.claimed", "gem" to gem.display.ifBlank { gem.name }, "amount" to amount)
            }

            // 返回按钮: 回到分类列表. 放在底部中间(slot 49).
            set(49, buildItem(XMaterial.BARRIER) { name = Lang.get("common.back") }) {
                DebugUtil.log("GUI", "${player.name} 从分类 $category 点击返回, 回到分类列表")
                afterClick(player, clickEvent().view.topInventory, generation) { open(player) }
            }

            setNextPage(50) { _, hasNext -> pageIcon(hasNext, true) }
            setPreviousPage(48) { _, hasPrev -> pageIcon(hasPrev, false) }
        }
    }

    /** Public read-only catalog. No item granting callbacks. */
    fun openCatalog(player: Player) {
        val gems = GemRegistry.all().sortedBy { it.id }
        player.openMenu<Linked<GemConfig>>(Lang.get("catalog.title")) {
            rows(6)
            slots((0..44).toList())
            elements { gems }
            onGenerate { _, gem, _, _ ->
                val material = if (!gem.texture.isNullOrBlank()) XMaterial.PLAYER_HEAD
                    else XMaterial.matchXMaterial(gem.material ?: "PAPER").orElse(XMaterial.PAPER)
                buildItem(material) {
                    if (!gem.texture.isNullOrBlank()) skullTexture = taboolib.platform.util.SkullTexture(gem.texture)
                    name = ColorUtil.colorize(gem.display.ifBlank { gem.name })
                    lore.addAll(ColorUtil.colorize(gem.tips))
                    lore.addAll(mc233.`fun`.snowygems.util.GemDescription.lines(gem))
                    if (gem.glow) shiny()
                }
            }
            onClick { event, _ -> event.isCancelled = true }
            setNextPage(50) { _, has -> pageIcon(has, true) }
            setPreviousPage(48) { _, has -> pageIcon(has, false) }
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

    fun openDismantle(player: Player) = DismantleGui.open(player)

    /** 翻页按钮外观统一走语言文件, 有页则箭头, 无页则屏障 */
    private fun pageIcon(has: Boolean, next: Boolean) =
        if (has) buildItem(XMaterial.ARROW) {
            name = Lang.get(if (next) "common.next-page" else "common.prev-page")
        } else buildItem(XMaterial.BARRIER) {
            name = Lang.get(if (next) "common.next-page-none" else "common.prev-page-none")
        }
}
