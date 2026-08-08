package mc233.`fun`.snowygems.manager

import mc233.`fun`.snowygems.util.Lang
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import taboolib.common.platform.function.getDataFolder
import taboolib.module.chat.colored
import taboolib.module.configuration.Configuration
import java.io.File

/**
 * 方块标记管理器
 * 使用 PersistentDataContainer 持久化存储被标记的方块
 */
object MarkBlockManager {

    private val markedBlockKey = NamespacedKey("snowplugin", "marked_block")
    private val markedBlockTimeKey = NamespacedKey("snowplugin", "marked_time")

    /**
     * 标记玩家准星指向的方块
     */
    fun markBlock(player: Player) {
        val targetBlock = player.getTargetBlock(null, 6) ?: run {
            Lang.send(player,"command.no-block")
            return
        }

        if (targetBlock.type.isAir) {
            Lang.send(player,"command.no-air")
            return
        }

        // 检查方块是否是 config.yml 文件中 tagged-blocks 中定义的方块类型
        val allowedBlocks = Configuration.loadFromFile(File(getDataFolder(), "config.yml"))
            .getStringList("tagged-blocks") ?: emptyList()
        val material = targetBlock.type
        if (material.name !in allowedBlocks.map { it.uppercase() }) {
            Lang.send(player, "command.block-not-allowed")
            return
        }

        val location = targetBlock.location

        // 检查是否已被标记
        if (isBlockMarked(location)) {
            player.sendMessage("§e该方块已被标记，正在覆盖...".colored())
        }

        // 保存标记
        saveMarkedBlock(location)

        player.sendMessage("§a✓ 成功将该方块设置成镶嵌台 §6${targetBlock.type.name} §a坐标 §b${location.blockX}, ${location.blockY}, ${location.blockZ}".colored())
        player.sendMessage("§7现在右键点击该方块将打开镶嵌台界面".colored())
    }

    /**
     * 检查方块是否被标记
     */
    fun isBlockMarked(location: Location): Boolean {
        val chunk = location.chunk
        val pdc = chunk.persistentDataContainer
        val key = locationToKey(location)
        val stored = pdc.get(markedBlockKey, PersistentDataType.STRING)
        return stored != null && stored == key
    }

    /**
     * 保存标记到持久化数据
     */
    fun saveMarkedBlock(location: Location) {
        val chunk = location.chunk
        val pdc = chunk.persistentDataContainer
        val key = locationToKey(location)
        pdc.set(markedBlockKey, PersistentDataType.STRING, key)
        pdc.set(markedBlockTimeKey, PersistentDataType.LONG, System.currentTimeMillis())
    }

    /**
     * 移除标记
     */
    fun removeMarkedBlock(location: Location) {
        val chunk = location.chunk
        val pdc = chunk.persistentDataContainer
        pdc.remove(markedBlockKey)
        pdc.remove(markedBlockTimeKey)
    }

    /**
     * 获取标记时间（用于扩展功能）
     */
    fun getMarkedTime(location: Location): Long? {
        val chunk = location.chunk
        val pdc = chunk.persistentDataContainer
        return pdc.get(markedBlockTimeKey, PersistentDataType.LONG)
    }

    /**
     * 将 Location 转换为字符串键
     */
    private fun locationToKey(location: Location): String {
        return "${location.world?.name}:${location.blockX}:${location.blockY}:${location.blockZ}"
    }
}