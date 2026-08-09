package mc233.`fun`.snowygems.manager

import mc233.`fun`.snowygems.gui.EmbedGui
import mc233.`fun`.snowygems.util.DebugUtil
import mc233.`fun`.snowygems.util.Lang
import org.bukkit.Sound
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEvent
import taboolib.common.platform.event.SubscribeEvent

/**
 * 镶嵌台方块交互监听器.
 *
 * 右键被标记的方块 -> 直接打开镶嵌台界面;
 * 挖掉被标记的方块 -> 清除标记, 避免后续在原位放置的方块误触发。
 */
object BlockListener {

    /** 右键被标记的方块, 打开镶嵌台 */
    @SubscribeEvent
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val clickedBlock = event.clickedBlock ?: return
        if (!MarkBlockManager.isBlockMarked(clickedBlock.location)) return

        val player = event.player
        event.isCancelled = true
        DebugUtil.log("Command", "${player.name} 右键镶嵌台方块, 打开镶嵌台")
        EmbedGui.open(player)

        player.world.playSound(clickedBlock.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 2.0f)
    }

    /** 挖掉被标记的方块时清除标记 */
    @SubscribeEvent
    fun onBlockBreak(event: BlockBreakEvent) {
        val location = event.block.location
        if (!MarkBlockManager.isBlockMarked(location)) return

        MarkBlockManager.removeMarkedBlock(location)
        val player = event.player
        DebugUtil.log("Command", "${player.name} 挖掉镶嵌台方块, 已清除标记")
        Lang.send(player, "command.has-been-dug-up")

        player.world.playSound(location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.0f)
    }
}
