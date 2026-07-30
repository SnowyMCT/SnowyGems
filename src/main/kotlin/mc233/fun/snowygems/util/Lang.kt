package mc233.`fun`.snowygems.util

import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.releaseResourceFile
import taboolib.module.configuration.Configuration
import org.bukkit.entity.Player
import java.io.File

/**
 * 玩家可见语言统一入口
 * 所有玩家提示应通过 Lang 获取
 */
object Lang {

    private var config: Configuration? = null
    private val file get() = File(getDataFolder(), "lang.yml")

    fun reload() {
        releaseResourceFile("lang.yml", false)
        config = Configuration.loadFromFile(file)
    }

    fun get(key: String, vararg args: Pair<String, Any>): String {
        var text = config?.getString(key) ?: key
        args.forEach { text = text.replace("{${it.first}}", it.second.toString()) }
        return ColorUtil.colorize(text)
    }

    fun send(player: Player, key: String, vararg args: Pair<String, Any>) {
        player.sendMessage(get(key, *args))
    }
}
