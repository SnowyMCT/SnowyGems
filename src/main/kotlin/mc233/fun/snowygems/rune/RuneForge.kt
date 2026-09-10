package mc233.`fun`.snowygems.rune

import mc233.`fun`.snowygems.Permissions
import mc233.`fun`.snowygems.config.GemRegistry
import mc233.`fun`.snowygems.util.ItemFactory
import mc233.`fun`.snowygems.util.Lang
import mc233.`fun`.snowygems.util.ColorUtil
import mc233.`fun`.snowygems.util.DebugUtil
import org.bukkit.entity.Player

object RuneForge {
    fun name(id: String): String = GemRegistry.get(id)?.let { ColorUtil.colorize(it.display.ifBlank { it.name }) } ?: id

    fun available(player: Player, id: String): Int = player.inventory.storageContents
        .filterNotNull().filter { ItemFactory.getGemId(it) == id }.sumOf { it.amount.coerceAtLeast(0) }

    /** 完整规划扣料和产物位置后一次性写回；失败不扣料、不发奖、不往地上丢物品。 */
    fun craft(player: Player, id: String, generation: Long): Boolean {
        if (!player.hasPermission(Permissions.RUNE)) { Lang.send(player, "rune.no-permission"); return false }
        val recipe = RuneRecipeRegistry.get(id)
        if (generation != RuneRecipeRegistry.generation || recipe == null) {
            Lang.send(player, "rune.stale"); return false
        }
        val cfg = GemRegistry.get(recipe.result) ?: run { Lang.send(player, "rune.stale"); return false }
        val snapshot = player.inventory.storageContents.map { it?.clone() }.toTypedArray()
        val output = ItemFactory.build(cfg, 1)
        if (output.type.isAir || ItemFactory.getGemId(output) != recipe.result) {
            Lang.send(player, "rune.stale")
            return false
        }
        val slots = snapshot.map { stack ->
            if (stack == null || stack.type.isAir) CraftSlot(null, 0)
            else CraftSlot(ItemFactory.getGemId(stack), stack.amount, stack.isSimilar(output))
        }
        val check = RuneCraftPlanner.plan(slots, recipe, output.maxStackSize.coerceAtLeast(1))
        val plan = check.plan
        if (plan == null) {
            if (check.noSpace) Lang.send(player, "rune.no-space")
            else check.missing.forEach { (gem, count) -> Lang.send(player, "rune.missing", "gem" to name(gem), "amount" to count) }
            return false
        }
        val result = snapshot.mapIndexed { i, stack ->
            when {
                plan.added[i] > 0 -> output.clone().apply { amount = plan.remaining[i] + plan.added[i] }
                plan.remaining[i] > 0 -> stack!!.clone().apply { amount = plan.remaining[i] }
                else -> null
            }
        }.toTypedArray()
        if (!snapshot.contentEquals(player.inventory.storageContents)) { Lang.send(player, "rune.changed"); return false }
        player.inventory.storageContents = result
        DebugUtil.log("Rune", "${player.name} 完成配方 $id: ${recipe.ingredients} -> ${recipe.result} x${recipe.amount}")
        Lang.send(player, "rune.success", "gem" to name(recipe.result), "amount" to recipe.amount)
        return true
    }
}
