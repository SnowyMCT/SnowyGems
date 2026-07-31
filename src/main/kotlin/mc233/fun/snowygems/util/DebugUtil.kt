package mc233.`fun`.snowygems.util

import taboolib.common.LifeCycle
import taboolib.common.platform.Awake
import taboolib.common.platform.function.console
import taboolib.module.configuration.Config
import taboolib.module.configuration.Configuration

/**
 * 调试日志系统, 通过 config.yml 中的 Debug: true/false 总开关控制.
 * 开启后插件的全部交互逻辑(菜单点击/命令/宝石使用/技能触发/奖励执行/配置加载)
 * 都会在控制台输出详细过程, 便于排查问题.
 *
 * 可选的 DebugTags 白名单: 只输出列表中的 tag, 留空表示全部输出.
 * 例: DebugTags: [Menu, GemManager]
 *
 * config.yml 由 TabooLib 的 [Config] 托管(自动释放 + 保存即热重载 + 版本迁移),
 * 不再手动 releaseResourceFile / loadFromFile.
 */
object DebugUtil {

    @Config(value = "config.yml", autoReload = true, migrate = true)
    lateinit var conf: Configuration

    var enabled: Boolean = false
        private set

    /** 为空表示不过滤, 输出全部 tag */
    private var tagFilter: Set<String> = emptySet()

    /** Internal 或 PlayerPoints */
    var pointsProvider: String = "Internal"
        private set

    /**
     * autoReload 只保证"文件内容被重新读进 Configuration", 不会自动同步到本对象的字段,
     * 所以这里注册一个 onReload 回调, 玩家手改 config.yml 保存后立即生效, 不用打命令.
     */
    @Awake(LifeCycle.ENABLE)
    fun bindAutoReload() {
        // @Config 的注入发生在更早的阶段, 但 ENABLE 阶段各方法的执行顺序不保证,
        // 所以这里仍然守一道 isInitialized, 避免抢跑时抛 UninitializedPropertyAccessException
        if (!::conf.isInitialized) return
        conf.onReload { readFields() }
        readFields()
    }

    fun reload() {
        try {
            if (::conf.isInitialized) {
                conf.reload()
                readFields()
            }
        } catch (e: Exception) {
            enabled = false
            tagFilter = emptySet()
        }
        lastByKey.clear()
    }

    private fun readFields() {
        enabled = conf.getBoolean("Debug", false)
        tagFilter = conf.getStringList("DebugTags").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        pointsProvider = conf.getString("Points.Provider", "Internal") ?: "Internal"
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
