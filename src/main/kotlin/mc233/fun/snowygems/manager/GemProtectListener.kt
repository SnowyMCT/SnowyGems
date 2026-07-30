package mc233.`fun`.snowygems.manager

import mc233.`fun`.snowygems.util.ColorUtil
import mc233.`fun`.snowygems.util.DebugUtil
import mc233.`fun`.snowygems.util.ItemFactory
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import taboolib.common.platform.event.SubscribeEvent

/**
 * 宝石物品的防误用保护.
 *
 * 宝石大多使用 PLAYER_HEAD 作为载体材质, 而头颅在原版里是可放置方块, 玩家右键地面就会把
 * 宝石当装饰头颅放下去 —— 一旦放下, 物品上的 SnowyGems NBT 全部丢失, 宝石等于被销毁.
 * 这里统一拦住所有会让宝石离开物品栏变成"世界内方块/展示物"的途径:
 *
 *  - [BlockPlaceEvent]           放置成方块(主手/副手都会触发)
 *  - [PlayerInteractEntityEvent] 塞进展示框
 *
 * 右键使用(兑换券/药水)的正常流程由 [GemUseListener] 处理, 不受影响.
 */
object GemProtectListener {

    /** 禁止把宝石当方块放置(头颅/告示牌等任何材质都一样拦) */
    @SubscribeEvent
    fun onPlace(e: BlockPlaceEvent) {
        val gemId = ItemFactory.getGemId(e.itemInHand) ?: return
        e.isCancelled = true
        DebugUtil.log("Protect", "阻止 ${e.player.name} 把宝石 $gemId (${e.itemInHand.type}) 放置为方块")
        warn(e.player)
    }

    /** 禁止把宝石塞进展示框(展示框内的物品不保留自定义 NBT, 会导致宝石失效) */
    @SubscribeEvent
    fun onPutInItemFrame(e: PlayerInteractEntityEvent) {
        val frame = e.rightClicked as? ItemFrame ?: return
        // 框里已经有东西时, 右键是旋转而不是放入, 不用拦
        if (!frame.item.type.isAir) return
        val hand = if (e.hand == EquipmentSlot.OFF_HAND) e.player.inventory.itemInOffHand
        else e.player.inventory.itemInMainHand
        val gemId = ItemFactory.getGemId(hand) ?: return
        e.isCancelled = true
        DebugUtil.log("Protect", "阻止 ${e.player.name} 把宝石 $gemId 放进展示框")
        warn(e.player)
    }

    private fun warn(player: Player) {
        player.sendMessage(ColorUtil.colorize("&c无法放置宝石..."))
    }
}
