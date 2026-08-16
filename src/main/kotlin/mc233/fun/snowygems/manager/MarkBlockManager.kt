package mc233.`fun`.snowygems.manager

import mc233.`fun`.snowygems.util.Lang
import org.bukkit.Location
import org.bukkit.entity.Player
import taboolib.common.platform.function.getDataFolder
import taboolib.module.chat.colored
import taboolib.module.configuration.Configuration
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

/**
 * 方块标记管理器
 */
object MarkBlockManager {

    private val file by lazy { File(getDataFolder(), "MarkedBlocks.yml") }
    private val storage by lazy {
        file.parentFile?.mkdirs()
        if (!file.exists()) file.createNewFile()
        Configuration.loadFromFile(file)
    }

    // 单条标记信息: 格式化后的时间 + 执行标记的玩家
    private data class MarkedInfo(val time: String, val player: String)

    // 标记内存: world:x:y:z -> 标记信息
    private val markedBlocks = HashMap<String, MarkedInfo>()

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd")

    private fun formatTime(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(dateFormatter)

    // 加载
    fun load() {
        markedBlocks.clear()
        for (entry in storage.getMapList("marked")) {
            val key = entry["key"]?.toString() ?: continue
            val rawTime = entry["time"] ?: continue
            val time = when (rawTime) {
                is Number -> formatTime(rawTime.toLong())
                is Date -> formatTime(rawTime.time)
                else -> rawTime.toString()
            }
            val player = entry["player"]?.toString()?.ifBlank { "?" } ?: "?"
            markedBlocks[key] = MarkedInfo(time, player)
        }
    }

    // 存
    private fun save() {
        storage.set("marked", markedBlocks.map { (k, v) ->
            mapOf("key" to k, "time" to v.time, "player" to v.player)
        })
        storage.saveToFile()
    }

    // 标记玩家指的方块
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
            Lang.send(player, "command.block-marked")
        }

        // 保存标记
        saveMarkedBlock(location, player.name)

        player.sendMessage("§a✓ 成功将该方块设置成镶嵌台 §6${targetBlock.type.name} §a坐标 §b${location.blockX}, ${location.blockY}, ${location.blockZ}".colored())
        player.sendMessage("§7现在右键点击该方块将打开镶嵌台界面".colored())
    }

    //检查方块是否被标记
    fun isBlockMarked(location: Location): Boolean =
        markedBlocks.containsKey(locationToKey(location))


    //保存标记到持久化数据
    fun saveMarkedBlock(location: Location, playerName: String) {
        markedBlocks[locationToKey(location)] = MarkedInfo(formatTime(System.currentTimeMillis()), playerName)
        save()
    }

    // 移除标记
    fun removeMarkedBlock(location: Location) {
        markedBlocks.remove(locationToKey(location))
        save()
    }

    //获取标记时间
    fun getMarkedTime(location: Location): String? =
        markedBlocks[locationToKey(location)]?.time

    //获取标记该方块的玩家名
    fun getMarkedPlayer(location: Location): String? =
        markedBlocks[locationToKey(location)]?.player


    //将 Location 转换为字符串键
    private fun locationToKey(location: Location): String {
        return "${location.world?.name}:${location.blockX}:${location.blockY}:${location.blockZ}"
    }
}
