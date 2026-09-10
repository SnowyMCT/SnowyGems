package mc233.`fun`.snowygems.gui

import mc233.`fun`.snowygems.Permissions
import mc233.`fun`.snowygems.config.GemRegistry
import mc233.`fun`.snowygems.rune.RuneForge
import mc233.`fun`.snowygems.rune.RuneRecipe
import mc233.`fun`.snowygems.rune.RuneRecipeRegistry
import mc233.`fun`.snowygems.util.ColorUtil
import mc233.`fun`.snowygems.util.ItemFactory
import mc233.`fun`.snowygems.util.Lang
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import taboolib.common.platform.function.submit
import taboolib.library.xseries.XMaterial
import taboolib.module.ui.openMenu
import taboolib.module.ui.type.Linked
import taboolib.platform.util.buildItem

object RuneForgeGui {
    fun open(player: Player) {
        if (!player.hasPermission(Permissions.RUNE)) { Lang.send(player, "rune.no-permission"); return }
        val recipes = RuneRecipeRegistry.all()
        if (recipes.isEmpty()) { Lang.send(player, "rune.empty"); return }
        val generation = RuneRecipeRegistry.generation
        player.openMenu<Linked<RuneRecipe>>(Lang.get("rune.title")) {
            rows(6)
            slots((0..44).toList())
            elements { recipes }
            onGenerate { _, recipe, _, _ -> icon(player, recipe, false) }
            onClick { event, recipe ->
                event.isCancelled = true
                if (event.clickEvent().click == ClickType.LEFT) {
                    later(player, event.clickEvent().view.topInventory, generation) { confirm(player, recipe, generation) }
                }
            }
            setPreviousPage(48) { _, has -> pageIcon(has, false) }
            setNextPage(50) { _, has -> pageIcon(has, true) }
            set(49, buildItem(XMaterial.BOOK) { name = Lang.get("rune.refresh") }) {
                later(player, clickEvent().view.topInventory, generation) { open(player) }
            }
            set(45, buildItem(XMaterial.ENCHANTING_TABLE) { name = Lang.get("rune.embed") }) {
                later(player, clickEvent().view.topInventory, generation) {
                    if (player.hasPermission(Permissions.OPEN)) WorkbenchMenu.open(player, "符文镶嵌台")
                    else Lang.send(player, "rune.no-permission")
                }
            }
        }
    }

    private fun confirm(player: Player, recipe: RuneRecipe, generation: Long) {
        player.openMenu<Linked<RuneRecipe>>(Lang.get("rune.confirm-title")) {
            rows(3)
            slots(listOf(13))
            elements { listOf(recipe) }
            onGenerate { _, value, _, _ -> icon(player, value, true) }
            onClick { event, value ->
                event.isCancelled = true
                if (event.clickEvent().click == ClickType.LEFT) {
                    later(player, event.clickEvent().view.topInventory, generation) {
                        RuneForge.craft(player, value.id, generation)
                        // 重开后旧菜单排队的重复点击不会继续执行。
                        confirm(player, value, generation)
                    }
                }
            }
            set(22, buildItem(XMaterial.ARROW) { name = Lang.get("common.back") }) {
                later(player, clickEvent().view.topInventory, generation) { open(player) }
            }
        }
    }

    private fun icon(player: Player, recipe: RuneRecipe, confirm: Boolean): ItemStack {
        val cfg = GemRegistry.get(recipe.result)
        val item = cfg?.let { ItemFactory.build(it, 1) } ?: buildItem(XMaterial.BARRIER)
        val meta = item.itemMeta ?: return item
        meta.setDisplayName(ColorUtil.colorize(recipe.display))
        meta.lore = buildList {
            add(Lang.get("rune.output", "gem" to RuneForge.name(recipe.result), "amount" to recipe.amount))
            cfg?.let { addAll(ColorUtil.colorize(it.tips)) }
            add(" ")
            add(Lang.get("rune.ingredients"))
            recipe.ingredients.forEach { (id, required) ->
                val have = RuneForge.available(player, id)
                add(Lang.get(if (have >= required) "rune.material-ok" else "rune.material-missing",
                    "gem" to RuneForge.name(id), "have" to have, "need" to required))
            }
            add(" ")
            add(Lang.get(if (confirm) "rune.confirm" else "rune.details"))
            add(Lang.get("rune.storage-only"))
            add(Lang.get("rune.guaranteed"))
        }
        item.itemMeta = meta
        return item
    }

    private fun later(player: Player, inventory: Inventory, generation: Long, action: () -> Unit) {
        submit(delay = 1) {
            if (!player.isOnline || player.openInventory.topInventory !== inventory) return@submit
            if (generation != RuneRecipeRegistry.generation) {
                Lang.send(player, "rune.stale")
                player.closeInventory()
            } else if (!player.hasPermission(Permissions.RUNE)) {
                Lang.send(player, "rune.no-permission")
                player.closeInventory()
            } else action()
        }
    }

    private fun pageIcon(has: Boolean, next: Boolean): ItemStack = buildItem(if (has) XMaterial.ARROW else XMaterial.BARRIER) {
        name = Lang.get(if (next) { if (has) "common.next-page" else "common.next-page-none" }
            else { if (has) "common.prev-page" else "common.prev-page-none" })
    }
}
