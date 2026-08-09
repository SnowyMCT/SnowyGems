package mc233.`fun`.snowygems.manager

import mc233.`fun`.snowygems.SnowyGems
import mc233.`fun`.snowygems.util.DebugUtil
import mc233.`fun`.snowygems.util.Lang
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import taboolib.module.configuration.Config
import taboolib.module.configuration.Configuration

/**
 * 镶嵌台方块标记管理器.
 *
 * 用「区块的 PersistentDataContainer」持久化被标记的方块:
 *   - 每个被标记的方块在其所属区块 PDC 上写一个以自身坐标为 path 的 NamespacedKey,
 *     因此同一区块可标记任意多个方块(不会互相覆盖), 且随区块存档一起持久化。
 *   - tagged-blocks 白名单由 @Config 托管, reloadAll 时通过 [resolve] 刷新缓存。
 */
object MarkBlockManager {

    @Config(value = "config.yml", autoReload = true, migrate = true)
    lateinit var conf: Configuration

    /** 可被设置为镶嵌台的方块材质白名单(全大写) */
    private var taggedBlocks: Set<String> = emptySet()

    /** reloadAll 时刷新白名单缓存 */
    fun resolve() {
        if (!::conf.isInitialized) return
        taggedBlocks = conf.getStringList("tagged-blocks").map { it.uppercase() }.toSet()
        DebugUtil.log("Command", "镶嵌台方块白名单已加载: ${taggedBlocks.size} 种")
    }

    /** 标记玩家准星指向的方块为镶嵌台 */
    fun markBlock(player: Player) {
        val targetBlock = player.getTargetBlock(null, 6)
        if (targetBlock.type.isAir) {
            Lang.send(player, "command.no-block")
            return
        }

        val material = targetBlock.type
        if (material.name !in taggedBlocks) {
            Lang.send(player, "command.block-not-allowed")
            return
        }

        val location = targetBlock.location
        val override = isBlockMarked(location)
        saveMarkedBlock(location)

        if (override) Lang.send(player, "command.mark-override")
        Lang.send(
            player, "command.mark-success",
            "block" to material.name,
            "x" to location.blockX,
            "y" to location.blockY,
            "z" to location.blockZ
        )
        Lang.send(player, "command.mark-hint")
    }

    /** 检查方块是否被标记为镶嵌台 */
    fun isBlockMarked(location: Location): Boolean =
        location.chunk.persistentDataContainer.has(keyOf(location), PersistentDataType.BYTE)

    /** 写入标记 */
    fun saveMarkedBlock(location: Location) {
        location.chunk.persistentDataContainer.set(keyOf(location), PersistentDataType.BYTE, 1)
    }

    /** 移除标记 */
    fun removeMarkedBlock(location: Location) {
        location.chunk.persistentDataContainer.remove(keyOf(location))
    }

    /** 每个方块用其世界坐标生成唯一 key(同区块内不冲突) */
    private fun keyOf(location: Location): NamespacedKey =
        NamespacedKey(SnowyGems.plugin, "mark_${location.blockX}_${location.blockY}_${location.blockZ}")
}
