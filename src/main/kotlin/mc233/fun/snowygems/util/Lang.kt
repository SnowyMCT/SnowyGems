package mc233.`fun`.snowygems.util

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import taboolib.common.platform.function.console
import taboolib.module.configuration.Config
import taboolib.module.configuration.Configuration
import taboolib.platform.util.sendActionBar

/**
 * 玩家可见文本的唯一出口.
 *
 * 设计约定(改代码时请遵守):
 *   - 任何会被玩家看到的字符串, 都必须来自 lang.yml, 代码里不写死中文.
 *   - 代码只负责传"数据"(占位符值), 不负责决定措辞和颜色.
 *
 * lang.yml 由 TabooLib 的 [Config] 托管:
 *   - 首次运行自动释放到插件目录
 *   - autoReload=true, 文件保存后自动重新读取, 不需要 /sgem reload
 *   - migrate=true, 插件更新后新增的语言节点会自动补进玩家已有的文件里, 不覆盖已改过的行
 *
 * 支持在文本最前面写发送方式前缀, 由 [send] 解析:
 *   actionbar:xxx   -> 动作栏
 *   title:主|副     -> 大标题
 *   none:           -> 静默(等于关掉这条提示)
 *   (无前缀)         -> 聊天栏
 */
object Lang {

    @Config(value = "lang.yml", autoReload = true, migrate = true)
    lateinit var conf: Configuration

    /** /sgem reload 时手动触发一次(热重载已由 autoReload 负责, 这里保证立即生效) */
    fun reload() {
        if (::conf.isInitialized) conf.reload()
    }

    /**
     * 取一条文本. 找不到节点时返回节点名本身, 方便一眼看出是哪个 key 漏配了.
     * @param args 具名占位符, 如 "gem" to cfg.id  会替换文本里的 {gem}
     */
    fun get(key: String, vararg args: Pair<String, Any?>): String =
        render(raw(key) ?: key, args)

    /** 取一条文本, 节点不存在时返回 null(用于"配置了才发"的可选提示) */
    fun getOrNull(key: String, vararg args: Pair<String, Any?>): String? =
        raw(key)?.let { render(it, args) }

    /** 取多行文本(lang.yml 里写成 YAML 列表) */
    fun getList(key: String, vararg args: Pair<String, Any?>): List<String> {
        if (!::conf.isInitialized) return emptyList()
        return conf.getStringList(key).map { render(it, args) }
    }

    /** 发送给玩家/控制台, 自动解析 actionbar: / title: / none: 前缀 */
    fun send(sender: CommandSender, key: String, vararg args: Pair<String, Any?>) {
        sendText(sender, raw(key) ?: key, args)
    }

    /** 命令回执: 在消息前自动加上 prefix 节点 */
    fun sendCommand(sender: CommandSender, key: String, vararg args: Pair<String, Any?>) {
        val text = raw(key) ?: key
        // 前缀只对聊天栏消息有意义, actionbar/title 不加
        if (text.startsWith("actionbar:", true) || text.startsWith("title:", true)) {
            sendText(sender, text, args)
            return
        }
        sendText(sender, (raw("prefix") ?: "") + text, args)
    }

    /** 直接发一段已经组装好的文本(用于宝石配置里自定义的 SuccessTip 等) */
    fun sendRaw(sender: CommandSender, text: String) {
        sendText(sender, text, emptyArray())
    }

    // ────────────────────────────────────────────────────────────

    private fun raw(key: String): String? {
        if (!::conf.isInitialized) return null
        return conf.getString(key)
    }

    private fun render(text: String, args: Array<out Pair<String, Any?>>): String {
        var result = text
        for ((k, v) in args) {
            result = result.replace("{$k}", v?.toString() ?: "")
        }
        return ColorUtil.colorize(result)
    }

    private fun sendText(sender: CommandSender, rawText: String, args: Array<out Pair<String, Any?>>) {
        val lower = rawText.lowercase()
        when {
            lower.startsWith("none:") -> return
            lower.startsWith("actionbar:") -> {
                val body = render(rawText.substring("actionbar:".length), args)
                if (sender is Player) sender.sendActionBar(body) else console().sendMessage(body)
            }
            lower.startsWith("title:") -> {
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
