package mc233.`fun`.snowygems.manager

import mc233.`fun`.snowygems.util.Lang
import mc233.`fun`.snowygems.util.DebugUtil
import mc233.`fun`.snowygems.util.ItemFactory
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import taboolib.common.platform.event.SubscribeEvent

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
        Lang.send(player, "gem.protect")
    }
}
