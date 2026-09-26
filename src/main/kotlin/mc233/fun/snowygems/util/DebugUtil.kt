package mc233.`fun`.snowygems.util

import taboolib.common.LifeCycle
import taboolib.common.platform.Awake
import taboolib.common.platform.function.console
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.severe
import taboolib.module.configuration.Config
import taboolib.module.configuration.Configuration
import java.io.File

/**
 * 调试日志系统, 通过 config.yml 中的 Debug: true/false 总开关控制.
 * 开启后插件的全部交互逻辑(菜单点击/命令/宝石使用/技能触发/奖励执行/配置加载)
 * 都会在控制台输出详细过程, 便于排查问题.
 *
 * config.yml 由 TabooLib 的 [Config] 托管(自动释放 + 保存即热重载 + 版本迁移),
 * 不再手动 releaseResourceFile / loadFromFile.
 */
object DebugUtil {

    @Config(value = "config.yml", autoReload = true, migrate = true)
    lateinit var conf: Configuration

    @Volatile var enabled: Boolean = false
        private set

    /** 为空表示不过滤, 输出全部 tag */
    @Volatile private var tagFilter: Set<String> = emptySet()
    @Volatile private var runtimeEnabled: Boolean? = null
    @Volatile private var runtimeTags: Set<String>? = null
    private var boundToConfig = false

    /** Internal 或 PlayerPoints */
    var pointsProvider: String = "Internal"
        private set

    /**
     * autoReload 只保证"文件内容被重新读进 Configuration", 不会自动同步到本对象的字段,
     * 所以这里注册一个 onReload 回调, 玩家手改 config.yml 保存后立即生效, 不用打命令.
     */
    @Awake(LifeCycle.ENABLE)
    fun bindAutoReload() {
        bindIfReady()
    }

    @Awake(LifeCycle.ACTIVE)
    fun bindAfterStartup() = bindIfReady()

    private fun bindIfReady() {
        if (!::conf.isInitialized || boundToConfig) return
        conf.onReload { readFields(conf) }
        boundToConfig = true
        readFields(conf)
    }

    fun reload() {
        try {
            if (::conf.isInitialized) {
                bindIfReady()
                conf.reload()
                readFields(conf)
            } else {
                val file = File(getDataFolder(), "config.yml")
                if (file.isFile) readFields(Configuration.loadFromFile(file))
                else severe("调试配置尚未注入且 config.yml 不存在，保留当前 Debug 状态")
            }
        } catch (e: Exception) {
            severe("读取 Debug/DebugTags 失败，保留当前状态: ${e.javaClass.simpleName}: ${e.message}")
        }
        lastByKey.clear()
    }

    private fun readFields(source: Configuration) {
        val wasEnabled = enabled
        val oldTags = tagFilter
        enabled = runtimeEnabled ?: source.getBoolean("Debug", false)
        tagFilter = runtimeTags ?: source.getStringList("DebugTags")
            .map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        pointsProvider = source.getString("Points.Provider", "Internal") ?: "Internal"
        if (enabled != wasEnabled || tagFilter != oldTags) lastByKey.clear()
    }

    /** Commands override config.yml for this server session, including plugin config reloads. */
    fun toggle(): Boolean {
        enabled = !enabled
        runtimeEnabled = enabled
        if (enabled) lastByKey.clear()
        return enabled
    }

    /** A scoped debug command also turns logging on. Empty tags mean all categories. */
    fun enable(tags: List<String>) {
        setTags(tags)
        runtimeEnabled = true
        enabled = true
        lastByKey.clear()
    }

    /** 运行时设置 tag 白名单, 传空表示输出全部 */
    fun setTags(tags: List<String>) {
        tagFilter = tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        runtimeTags = tagFilter
        lastByKey.clear()
    }

    fun tags(): Set<String> = tagFilter

    internal fun accepts(tag: String): Boolean =
        enabled && (tagFilter.isEmpty() || tagFilter.contains(tag.lowercase()))

    fun log(message: String) {
        if (enabled) {
            console().sendMessage(ColorUtil.colorize("&8[&bSnowyGems-Debug&8] &7$message"))
        }
    }

    fun log(tag: String, message: String) {
        if (!accepts(tag)) return
        console().sendMessage(ColorUtil.colorize("&8[&bSnowyGems-Debug&8]&e[$tag] &7$message"))
    }

    /** 上一次 logChanged 的内容, 用于抑制定时任务的重复刷屏 */
    private val lastByKey = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * 只在内容相对上一次发生变化时才输出, 用于 BUFF 引擎这类每秒执行的定时逻辑,
     * 避免同一条信息把控制台刷爆.
     */
    fun logChanged(tag: String, key: String, message: String) {
        if (!accepts(tag)) return
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
        if (!accepts(tag)) return block()
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
