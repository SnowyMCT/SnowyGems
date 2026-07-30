package mc233.`fun`.snowygems.util

import taboolib.common.platform.function.console
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.releaseResourceFile
import taboolib.module.configuration.Configuration
import java.io.File

/**
 * 调试日志系统, 通过 config.yml 中的 Debug: true/false 总开关控制.
 * 开启后插件的全部交互逻辑(菜单点击/命令/宝石使用/技能触发/奖励执行/配置加载)
 * 都会在控制台输出详细过程, 便于排查问题.
 *
 * 可选的 DebugTags 白名单: 只输出列表中的 tag, 留空表示全部输出.
 * 例: DebugTags: [Menu, GemManager]
 */
object DebugUtil {

    var enabled: Boolean = false
        private set

    /** 为空表示不过滤, 输出全部 tag */
    private var tagFilter: Set<String> = emptySet()

    /** Internal 或 PlayerPoints */
    var pointsProvider: String = "Internal"
        private set

    private val file by lazy { File(getDataFolder(), "config.yml") }

    fun reload() {
        try {
            releaseResourceFile("config.yml", false)
            if (file.exists()) {
                val cfg = Configuration.loadFromFile(file)
                enabled = cfg.getBoolean("Debug", false)
                tagFilter = cfg.getStringList("DebugTags").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
                pointsProvider = cfg.getString("Points.Provider", "Internal") ?: "Internal"
            }
        } catch (e: Exception) {
            enabled = false
            tagFilter = emptySet()
        }
        lastByKey.clear()
        if (enabled) {
            val scope = if (tagFilter.isEmpty()) "全部" else tagFilter.joinToString(",")
            console().sendMessage(ColorUtil.colorize("&8[&bSnowyGems-Debug&8] &a调试模式已开启, 输出范围: &f$scope"))
        }
    }

    /** 运行时临时开关(不写回 config.yml, /sgem reload 后以配置文件为准) */
    fun toggle(): Boolean {
        enabled = !enabled
        return enabled
    }

    /** 运行时临时设置 tag 白名单, 传空表示输出全部 */
    fun setTags(tags: List<String>) {
        tagFilter = tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    }

    fun tags(): Set<String> = tagFilter

    private fun accept(tag: String): Boolean =
        enabled && (tagFilter.isEmpty() || tagFilter.contains(tag.lowercase()))

    fun log(message: String) {
        if (enabled) {
            console().sendMessage(ColorUtil.colorize("&8[&bSnowyGems-Debug&8] &7$message"))
        }
    }

    fun log(tag: String, message: String) {
        if (!accept(tag)) return
        console().sendMessage(ColorUtil.colorize("&8[&bSnowyGems-Debug&8]&e[$tag] &7$message"))
    }

    /** 上一次 logChanged 的内容, 用于抑制定时任务的重复刷屏 */
    private val lastByKey = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * 只在内容相对上一次发生变化时才输出, 用于 BUFF 引擎这类每秒执行的定时逻辑,
     * 避免同一条信息把控制台刷爆.
     */
    fun logChanged(tag: String, key: String, message: String) {
        if (!accept(tag)) return
        if (lastByKey.put(key, message) == message) return
        log(tag, message)
    }

    /** 清空 logChanged 的去重缓存(重载配置时调用) */
    fun resetChangeCache() = lastByKey.clear()

    /** 记录一次异常(始终打印, 不受 Debug 开关限制, 但只有开启时才带完整堆栈) */
    fun err(tag: String, message: String, e: Throwable) {
        console().sendMessage(ColorUtil.colorize("&8[&bSnowyGems&8]&c[$tag] &c$message -> ${e.javaClass.simpleName}: ${e.message}"))
        if (enabled) e.stackTrace.take(8).forEach {
            console().sendMessage(ColorUtil.colorize("&8    at &7$it"))
        }
    }

    /** 便捷方法: 记录一次带返回值的操作 */
    fun <T> trace(tag: String, what: String, block: () -> T): T {
        if (!accept(tag)) return block()
        val start = System.nanoTime()
        return try {
            val result = block()
            val ms = (System.nanoTime() - start) / 1_000_000.0
            log(tag, "$what -> $result (${"%.2f".format(ms)}ms)")
            result
        } catch (e: Throwable) {
            err(tag, "$what 抛出异常", e)
            throw e
        }
    }
}
