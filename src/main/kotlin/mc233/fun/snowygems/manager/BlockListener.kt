package mc233.`fun`.snowygems.manager

import mc233.`fun`.snowygems.util.DebugUtil
import mc233.`fun`.snowygems.util.Lang
import org.bukkit.Bukkit
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEvent
import taboolib.common.platform.event.SubscribeEvent
import taboolib.common.platform.function.getDataFolder
import taboolib.module.configuration.Configuration
import java.io.File

/**
 * 方块交互事件监听器
 */
object BlockListener {

    /**
     * 处理玩家右键点击方块
     */
    @SubscribeEvent
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player
        val clickedBlock = event.clickedBlock ?: return

        // 检查是否是右键点击
        val action = event.action
        if (action != Action.RIGHT_CLICK_BLOCK) return

        // 检查方块是否被标记
        val location = clickedBlock.location
        if (!MarkBlockManager.isBlockMarked(location)) return

        // 取消默认交互
        event.isCancelled = true

        // 执行命令
        Bukkit.dispatchCommand(player, "sgem embed")

        // 播放点击特效
        player.world.playSound(
            clickedBlock.location,
            org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING,
            1.0f,
            2.0f
        )
    }

    /**
     * 处理方块被挖掘
     * 清除标记，确保后续填充的方块不会触发
     */
    @SubscribeEvent
    fun onBlockBreak(event: BlockBreakEvent) {
        val block = event.block
        val location = block.location

        // 检查是否被标记
        if (!MarkBlockManager.isBlockMarked(location)) {
            return
        }

        // 清除标记
        MarkBlockManager.removeMarkedBlock(location)

        val player = event.player
        Lang.send(player, "command.has-been-dug-up")

        // 播放破坏特效
        player.world.playSound(
            location,
            org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP,
            0.5f,
            1.0f
        )
    }
}