package mc233.`fun`.snowygems.manager

import mc233.`fun`.snowygems.config.GemRegistry
import mc233.`fun`.snowygems.config.GemType
import mc233.`fun`.snowygems.util.Lang
import mc233.`fun`.snowygems.util.ItemFactory
import mc233.`fun`.snowygems.util.DebugUtil
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.inventory.EquipmentSlot
import taboolib.common.platform.event.EventPriority
import taboolib.common.platform.event.SubscribeEvent

/**
 * PlayerGem / RandomGem 类型的宝石(兑换券/药水/消耗品)可以被玩家直接右键或吃/喝使用,
 * 不需要进入工作台. NORMAL 类型的宝石(需要镶嵌到装备上)不受此监听器影响.
 */
object GemUseListener {

    @SubscribeEvent
    fun onInteract(e: PlayerInteractEvent) {
        if (e.hand != EquipmentSlot.HAND) return
        if (e.action != Action.RIGHT_CLICK_AIR && e.action != Action.RIGHT_CLICK_BLOCK) return
        if (e.clickedBlock?.location?.let { MarkBlockManager.isBlockMarked(it) } == true) return
        val item = e.item ?: return
        val gemId = ItemFactory.getGemId(item)
        DebugUtil.log("GemUse", "玩家 ${e.player.name} 右键物品(${item.type}), 读取 GemId=$gemId")
        if (gemId == null) return
        val cfg = GemRegistry.get(gemId) ?: run {
            DebugUtil.log("GemUse", "  物品带有 GemId=$gemId 但配置里找不到该定义, 忽略")
            return
        }
        if (cfg.type != GemType.PLAYER_GEM && cfg.type != GemType.RANDOM_GEM) {
            DebugUtil.log("GemUse", "  宝石 $gemId 的类型是 ${cfg.type}, 不支持右键直接使用(需要在工作台镶嵌)")
            return
        }
        if (cfg.eat) {
            DebugUtil.log("GemUse", "  宝石 $gemId 配置了 Eat=true, 交给食用事件处理")
            return // 走 PlayerItemConsumeEvent
        }
        e.isCancelled = true
        DebugUtil.log("GemUse", "  宝石 $gemId 类型=${cfg.type}, 开始执行使用逻辑")
        consume(e.player, gemId)
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    fun onConsume(e: PlayerItemConsumeEvent) {
        if (e.isCancelled) return
        val gemId = ItemFactory.getGemId(e.item) ?: return
        DebugUtil.log("GemUse", "${e.player.name} 食用/饮用物品(${e.item.type}), GemId=$gemId")
        val cfg = GemRegistry.get(gemId) ?: return
        if (!cfg.eat || cfg.type == GemType.NORMAL) {
            DebugUtil.log("GemUse", "  宝石 $gemId 未配置 Eat=true, 忽略食用事件")
            return
        }
        // 由 useHeld 统一扣除；取消原版吃喝，防止额外扣物品或把刚发放的奖励换成空瓶。
        e.isCancelled = true
        consume(e.player, gemId, e.hand)
    }

    private fun consume(player: Player, gemId: String, hand: EquipmentSlot = EquipmentSlot.HAND) {
        val held = player.inventory.getItem(hand)
        val heldId = ItemFactory.getGemId(held)
        if (heldId != gemId) {
            DebugUtil.log("GemUse", "  主手物品的 GemId=$heldId 与待使用的 $gemId 不一致(可能是副手触发), 取消")
            return
        }
        val result = GemManager.useHeld(player, hand)
        DebugUtil.log("GemUse", "  useDirectly 返回 success=${result.success} consumed=${result.consumedGem} msg=${result.message}")
        Lang.sendRaw(player, result.message)
    }
}
