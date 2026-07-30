package mc233.`fun`.snowygems.gui

import mc233.`fun`.snowygems.config.GemConfig
import mc233.`fun`.snowygems.config.GemRegistry
import mc233.`fun`.snowygems.manager.GemManager
import mc233.`fun`.snowygems.util.ColorUtil
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
 * /sgem view 管理员宝石浏览/领取面板:
 * 第一层按分类(来自配置文件名, 如 AttributeGem / ButtonGem / EmbedGem ...)展示,
 * 点击分类进入该分类下的宝石分页列表, 左键领取 1 个, Shift+左键领取一组(64个, 不超过最大堆叠).
 */
object GemGui {

    private class CategoryHolder : InventoryHolder {
        lateinit var inv: Inventory
        override fun getInventory(): Inventory = inv
    }

    /** 打开分类选择菜单 */
    fun open(player: Player) {
        val categories = GemRegistry.categories()
        DebugUtil.log("GUI", "为 ${player.name} 打开分类浏览面板, 分类数=${categories.size} $categories")
        if (categories.isEmpty()) {
            DebugUtil.log("GUI", "  没有任何分类可展示: GemRegistry 为空, 请检查 gems/ 目录")
            player.sendMessage(ColorUtil.colorize("&c当前没有加载任何宝石配置"))
            return
        }
        val holder = CategoryHolder()
        val size = (((categories.size - 1) / 9) + 1) * 9
        val inv = Bukkit.createInventory(holder, size.coerceIn(9, 54), ColorUtil.colorize("&b宝石分类浏览"))
        holder.inv = inv
        categories.forEachIndexed { index, category ->
            if (index >= inv.size) return@forEachIndexed
            val count = GemRegistry.byCategory(category).size
            inv.setItem(index, buildItem(XMaterial.CHEST) {
                name = ColorUtil.colorize("&e&l$category")
                lore.add(ColorUtil.colorize("&7共 &f$count &7个宝石/物品"))
                lore.add(ColorUtil.colorize("&a点击查看"))
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
        player.openMenu<Linked<GemConfig>>(ColorUtil.colorize("&b分类: &f$category")) {
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
                    lore.add(ColorUtil.colorize("&7"))
                    lore.add(ColorUtil.colorize("&7ID: &f${gem.id}"))
                    lore.add(ColorUtil.colorize("&a左键领取 1 个 / Shift+左键领取一组"))
                    if (gem.glow) shiny()
                }
            }

            onClick { event, gem ->
                event.isCancelled = true
                val amount = if (event.clickEvent().isShiftClick) 64 else 1
                DebugUtil.log("GUI", "${player.name} 从分类 $category 领取 ${gem.id} x$amount (shift=${event.clickEvent().isShiftClick})")
                GemManager.give(player, gem.id, amount)
                player.sendMessage(ColorUtil.colorize("&a已领取 &f${gem.display.ifBlank { gem.name }} &ax$amount"))
            }

            setNextPage(50) { _, hasNext ->
                if (hasNext) buildItem(XMaterial.ARROW) { name = ColorUtil.colorize("&a下一页") }
                else buildItem(XMaterial.BARRIER) { name = ColorUtil.colorize("&7已是最后一页") }
            }
            setPreviousPage(48) { _, hasPrev ->
                if (hasPrev) buildItem(XMaterial.ARROW) { name = ColorUtil.colorize("&a上一页") }
                else buildItem(XMaterial.BARRIER) { name = ColorUtil.colorize("&7已是第一页") }
            }
        }
    }

    /** 查看玩家当前手持装备上已镶嵌的宝石(原 /sgem view 的功能, 现挪到 /sgem inspect) */
    fun openInspect(player: Player) {
        val held = player.inventory.itemInMainHand
        if (held.type.isAir) {
            DebugUtil.log("GUI", "${player.name} 打开 inspect 失败: 手上没有物品")
            player.sendMessage(ColorUtil.colorize("&c请先手持你想查看的装备"))
            return
        }
        val appliedIds = GemManager.getAppliedGems(held)
        val gems = GemManager.getAppliedGemConfigs(held)
        DebugUtil.log(
            "GUI",
            "${player.name} 查看 ${held.type} 上的已镶嵌宝石: NBT记录=$appliedIds 能匹配到配置的=${gems.map { it.id }}"
        )
        if (appliedIds.size != gems.size) {
            DebugUtil.log("GUI", "  警告: 有 ${appliedIds.size - gems.size} 个已镶嵌ID在当前配置中找不到定义 (配置被删或改名?)")
        }
        if (gems.isEmpty()) {
            player.sendMessage(ColorUtil.colorize("&7该装备上还没有镶嵌任何宝石"))
            return
        }

        player.openMenu<Linked<GemConfig>>(ColorUtil.colorize("&b已镶嵌宝石 - ${held.itemMeta?.displayName ?: held.type.name}")) {
            rows(6)
            slots(listOf(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34))
            elements { gems }

            onGenerate { _, gem, _, _ ->
                val mat = XMaterial.matchXMaterial(gem.material ?: "PAPER").orElse(XMaterial.PAPER)
                buildItem(mat) {
                    name = ColorUtil.colorize(gem.display.ifBlank { gem.name })
                    lore.addAll(ColorUtil.colorize(gem.tips))
                    lore.add(ColorUtil.colorize("&7"))
                    lore.add(ColorUtil.colorize("&e右键拆除该宝石"))
                    if (gem.glow) shiny()
                }
            }

            onClick { event, gem ->
                event.isCancelled = true
                if (event.clickEvent().isRightClick) {
                    val hand = player.inventory.itemInMainHand
                    DebugUtil.log("GUI", "${player.name} 请求从 ${hand.type} 上拆除宝石 ${gem.id}")
                    val result = GemManager.removeFromItem(player, hand, gem.id)
                    DebugUtil.log("GUI", "  拆除结果 success=${result.success} msg=${result.message} 有新物品=${result.resultItem != null}")
                    player.sendMessage(ColorUtil.colorize(result.message))
                    result.resultItem?.let { player.inventory.setItemInMainHand(it) }
                    player.closeInventory()
                    openInspect(player)
                }
            }

            setNextPage(40) { _, hasNext ->
                if (hasNext) buildItem(XMaterial.ARROW) { name = ColorUtil.colorize("&a下一页") }
                else buildItem(XMaterial.BARRIER) { name = ColorUtil.colorize("&7已是最后一页") }
            }
            setPreviousPage(38) { _, hasPrev ->
                if (hasPrev) buildItem(XMaterial.ARROW) { name = ColorUtil.colorize("&a上一页") }
                else buildItem(XMaterial.BARRIER) { name = ColorUtil.colorize("&7已是第一页") }
            }
        }
    }
}
