package mc233.`fun`.snowygems.manager

import mc233.`fun`.snowygems.util.Lang
import org.bukkit.Location
import org.bukkit.entity.Player
import taboolib.common.platform.function.getDataFolder
import taboolib.module.configuration.Configuration
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

/**
 * 方块标记管理器
 *
 * 方块可以标记成两种功能方块, 类型记录在 MarkedBlocks.yml 每条记录的 type 字段里:
 *   - [MarkType.EMBEDDER]   镶嵌台: 右键执行 /sgem embed
 *   - [MarkType.RUNE_FORGE] 锻造台: 右键执行 /sgem open 符文镶嵌台
 *
 * 两种类型共用 config.yml 的 tagged-blocks 白名单, 方块被挖掉时标记自动取消。
 */
object MarkBlockManager {

    /** 两种类型共用的方块材质白名单节点 */
    private const val BLOCK_LIST_NODE = "tagged-blocks"

    /**
     * 标记类型
     *
     * @param id          指令参数, 同时也是存档里记录的字符串
     * @param openCommand 右键被标记方块时执行的指令。与玩家手打完全等价,
     *                    所以该指令自己的权限判断照常生效
     * @param successKey  标记成功的提示
     * @param hintKey     标记后告诉玩家右键能干什么
     * @param deniedKey   方块不在白名单时的提示
     */
    enum class MarkType(
        val id: String,
        val openCommand: String,
        val successKey: String,
        val hintKey: String,
        val deniedKey: String
    ) {
        EMBEDDER(
            "embedder", "sgem embed",
            "command-block-success", "command-block-hint", "command-block-not-allowed"
        ),
        RUNE_FORGE(
            // 符文镶嵌台 = gui/gui.yml 里的顶层菜单名
            "rune-forge", "sgem open 符文镶嵌台",
            "command-rune-forge-success", "command-rune-forge-hint", "command-rune-forge-not-allowed"
        );

        companion object {
            /** 所有类型名(指令补全用) */
            fun names(): List<String> = entries.map { it.id }

            /** 按类型名取枚举(指令参数与存档字段共用), 不认识返回 null */
            fun fromId(raw: String?): MarkType? {
                val key = raw?.trim() ?: return null
                return entries.firstOrNull { it.id.equals(key, true) }
            }
        }
    }

    private val file by lazy { File(getDataFolder(), "MarkedBlocks.yml") }
    private val storage by lazy {
        file.parentFile?.mkdirs()
        if (!file.exists()) file.createNewFile()
        Configuration.loadFromFile(file)
    }

    // 单条标记信息: 格式化后的时间 + 执行标记的玩家 + 标记类型
    private data class MarkedInfo(val time: String, val player: String, val type: MarkType)

    // 标记内存: world:x:y:z -> 标记信息
    private val markedBlocks = HashMap<String, MarkedInfo>()

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd")

    private fun formatTime(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(dateFormatter)

    /** 所有类型名(指令补全用) */
    fun typeNames(): List<String> = MarkType.names()

    // 加载
    fun load() {
        storage.reload()
        markedBlocks.clear()
        for (entry in storage.getMapList("marked")) {
            val key = entry["key"]?.toString() ?: continue
            val type = MarkType.fromId(entry["type"]?.toString()) ?: continue
            val rawTime = entry["time"] ?: continue
            val time = when (rawTime) {
                is Date -> formatTime(rawTime.time)
                else -> rawTime.toString()
            }
            val player = entry["player"]?.toString()?.ifBlank { "?" } ?: "?"
            markedBlocks[key] = MarkedInfo(time, player, type)
        }
    }

    // 存
    private fun save() {
        storage.set("marked", markedBlocks.map { (k, v) ->
            mapOf("key" to k, "time" to v.time, "player" to v.player, "type" to v.type.id)
        })
        storage.saveToFile()
    }

    // 把玩家准星所指的方块标记成指定类型的功能方块
    fun markBlock(player: Player, type: MarkType) {
        val targetBlock = player.getTargetBlock(null, 6) ?: run {
            Lang.send(player, "command.no-block")
            return
        }

        if (targetBlock.type.isAir) {
            Lang.send(player, "command.no-air")
            return
        }

        // 检查方块是否在 config.yml 的 tagged-blocks 白名单里(两种类型共用这一份)
        val allowedBlocks = Configuration.loadFromFile(File(getDataFolder(), "config.yml"))
            .getStringList(BLOCK_LIST_NODE) ?: emptyList()
        val material = targetBlock.type
        if (allowedBlocks.isEmpty() || material.name !in allowedBlocks.map { it.uppercase() }) {
            Lang.send(player, type.deniedKey)
            return
        }

        val location = targetBlock.location

        // 检查是否已被标记
        if (isBlockMarked(location)) {
            Lang.send(player, "command-block-marked")
        }

        // 保存标记
        saveMarkedBlock(location, player.name, type)

        Lang.send(player, type.successKey, "material" to material.name,
            "x" to location.blockX, "y" to location.blockY, "z" to location.blockZ)
        Lang.send(player, type.hintKey)
    }

    //检查方块是否被标记(任意类型)
    fun isBlockMarked(location: Location): Boolean =
        markedBlocks.containsKey(locationToKey(location))

    //取方块的标记类型, 未标记返回 null
    fun getMarkType(location: Location): MarkType? =
        markedBlocks[locationToKey(location)]?.type

    //保存标记到持久化数据
    fun saveMarkedBlock(location: Location, playerName: String, type: MarkType) {
        markedBlocks[locationToKey(location)] = MarkedInfo(formatTime(System.currentTimeMillis()), playerName, type)
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
