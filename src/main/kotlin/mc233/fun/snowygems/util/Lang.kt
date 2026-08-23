package mc233.`fun`.snowygems.util

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import taboolib.common.platform.event.SubscribeEvent
import taboolib.common.platform.function.console
import taboolib.common.platform.function.warning
import taboolib.module.configuration.Config
import taboolib.module.configuration.Configuration
import taboolib.module.lang.Language
import taboolib.module.lang.LanguageFile
import taboolib.module.lang.Type
import taboolib.module.lang.TypeList
import taboolib.module.lang.TypeText
import taboolib.module.lang.event.PlayerSelectLocaleEvent
import taboolib.module.lang.event.SystemSelectLocaleEvent
import taboolib.platform.util.sendActionBar

/**
 * 玩家可见文本的唯一出口.
 *
 * 设计约定(改代码时请遵守):
 *   - 任何会被玩家看到的字符串, 都必须来自 lang/ 目录的语言文件, 代码里不写死.
 *   - 代码只负责传"数据"(占位符值), 不负责决定措辞和颜色.
 *
 *   - 语言文件位于 lang/ 目录, 每个语言一个文件
 *   - 首次运行自动释放到插件目录 lang/, 之后以插件目录内的文件为准(直接改文件即热重载)
 *   - migrate=true, 插件更新后新增的语言节点会自动补进已释放的文件, 不覆盖已改过的行
 *   - 当前语言由 config.yml 的 Lang 节点决定, /sgem reload 后立即切换, 无需重启
 *
 * 支持在文本最前面写发送方式前缀, 由 [send] 解析:
 *   actionbar:xxx   -> 动作栏
 *   title:主|副     -> 大标题
 *   none:           -> 静默(等于关掉这条提示)
 *   (无前缀)         -> 聊天栏
 */
object Lang {

    var language: String = "zh_CN"
        private set

    //已经加载的语言文件
    val languages: Set<String> get() = Language.languageFile.keys

    @Config(value = "config.yml", autoReload = true, migrate = true)
    lateinit var config: Configuration

    init {
        // 最后的退路
        Language.default = "zh_CN"
    }

    /** /sgem reload 时调用: 读取 config.yml 的里面的 Lang 设置并立即切换语言 */
    fun reload() {
        if (::config.isInitialized) config.reload()
        val wanted = config.getString("Lang", "zh_CN")?.trim()?.takeIf { it.isNotBlank() } ?: "zh_CN"
        Language.default = wanted
        // 重新扫描 lang/ 目录(jar 内资源 + 插件目录内已释放的文件)
        Language.reload()
        language = when {
            wanted in Language.languageFile -> wanted
            "zh_CN" in Language.languageFile -> "zh_CN"
            else -> Language.languageFile.keys.firstOrNull() ?: "zh_CN"
        }
        if (language != wanted) {
            warning("SnowyGems: 语言文件 lang/$wanted.yml 不存在, 已回退到 $language (可用: ${Language.languageFile.keys.joinToString(", ")})")
        }
    }

    //所有玩家都用 config.yml 里面设置的语言
    @SubscribeEvent
    fun onPlayerSelectLocale(event: PlayerSelectLocaleEvent) {
        event.locale = language
    }

    /** 控制台/系统消息同样使用服务器语言 */
    @SubscribeEvent
    fun onSystemSelectLocale(event: SystemSelectLocaleEvent) {
        event.locale = language
    }

    fun get(key: String, vararg args: Pair<String, Any?>): String =
        render(text(key) ?: key, args)

    fun getOrNull(key: String, vararg args: Pair<String, Any?>): String? =
        text(key)?.let { render(it, args) }

    //读取文本的一些东西
    fun getList(key: String, vararg args: Pair<String, Any?>): List<String> =
        texts(key).map { render(it, args) }

    fun send(sender: CommandSender, key: String, vararg args: Pair<String, Any?>) {
        sendText(sender, text(key) ?: key, args)
    }

    fun sendCommand(sender: CommandSender, key: String, vararg args: Pair<String, Any?>) {
        val text = text(key) ?: key
        // 前缀只加聊天栏, actionbar/title 不加
        if (text.startsWith("actionbar:", true) || text.startsWith("title:", true)) {
            sendText(sender, text, args)
            return
        }
        sendText(sender, (text("prefix") ?: "") + text, args)
    }

    fun sendRaw(sender: CommandSender, text: String) {
        sendText(sender, text, emptyArray())
    }

    // ────────────────────────────────────────────────────────────

    /** 当前语言的语言文件(依次回退: 配置语言 -> 兜底语言 -> 任意一个) */
    private fun localeFile(): LanguageFile? =
        Language.languageFile[language]
            ?: Language.languageFile[Language.default]
            ?: Language.languageFile.values.firstOrNull()

    /** 旧点号键名 -> 横杠键名的转换缓存(调用点固定, 最多百来条, 避免每条消息都重新 replace 分配字符串) */
    private val dashCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 按节点名取 Type; 兼容旧的点号键名(自动转为横杠) */
    private fun type(key: String): Type? {
        val file = localeFile() ?: return null
        file.nodes[key]?.let { return it }
        if ('.' !in key) return null
        return file.nodes[dashCache.getOrPut(key) { key.replace('.', '-') }]
    }

    /** 取单行原始文本(仅标准文本节点; 列表节点返回 null, 与旧版 getString 行为一致) */
    private fun text(key: String): String? = when (val t = type(key)) {
        is TypeText -> t.text
        else -> null
    }

    /** 取多行原始文本列表(标准文本节点视为单行列表) */
    private fun texts(key: String): List<String> = when (val t = type(key)) {
        is TypeText -> listOfNotNull(t.text)
        is TypeList -> t.list.filterIsInstance<TypeText>().mapNotNull { it.text }
        else -> emptyList()
    }

    private fun render(text: String, args: Array<out Pair<String, Any?>>): String {
        var result = text
        for ((k, v) in args) {
            result = result.replace("{$k}", v?.toString() ?: "")
        }
        return ColorUtil.colorize(result)
    }

    private fun sendText(sender: CommandSender, rawText: String, args: Array<out Pair<String, Any?>>) {
        // 前缀都是 ASCII, 用 startsWith(ignoreCase) 判断, 避免整条消息 lowercases 分配字符串
        when {
            rawText.startsWith("none:", true) -> return
            rawText.startsWith("actionbar:", true) -> {
                val body = render(rawText.substring("actionbar:".length), args)
                if (sender is Player) sender.sendActionBar(body) else console().sendMessage(body)
            }
            rawText.startsWith("title:", true) -> {
                val body = render(rawText.substring("title:".length), args)
                if (sender is Player) {
                    val parts = body.split("|", limit = 2)
                    sender.sendTitle(parts[0], parts.getOrElse(1) { "" }, 10, 40, 10)
                } else {
                    console().sendMessage(body)
                }
            }
            else -> sender.sendMessage(render(rawText, args))
        }
    }
}
