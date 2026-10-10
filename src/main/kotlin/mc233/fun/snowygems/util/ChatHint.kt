package mc233.`fun`.snowygems.util

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import taboolib.common.platform.function.adaptCommandSender
import taboolib.module.chat.ComponentText
import taboolib.module.chat.Components

/**
 * 可点击的用法提示.
 *
 * 起因: `/sgem history` 必须带玩家名, 但漏掉参数时命令框架只报错, 玩家看不出该补什么.
 * 于是这里把 lang/ 里的片段拼成带点击事件的组件:
 *   - 参数占位(如 <玩家名>): 点击 -> 补全命令(SUGGEST_COMMAND), 光标停在参数位置
 *   - 玩家自己执行时多给一个入口: 点击 -> 直接查自己的记录(RUN_COMMAND)
 *
 * 措辞全部来自 lang/(这里只负责拼装与点击行为), 与项目"文本只走 Lang"的约定一致.
 * 拼装与发送分开, 点击目标由 [Targets] 统一生成, 便于单元测试断言而不依赖聊天序列化.
 */
object ChatHint {

    /** 历史记录命令, 点击事件里的命令串必须与之一致 */
    const val HISTORY_COMMAND = "/sgem history"

    /**
     * 点击目标.
     * @param suggest 点参数占位时补全到聊天框的命令(结尾留空格, 光标正好停在参数处)
     * @param run     点"查看我的记录"时直接执行的命令, 控制台/离线场景为 null
     */
    data class Targets(val suggest: String, val run: String?) {
        companion object {
            fun of(command: String, playerName: String?): Targets =
                Targets("$command ", playerName?.let { "$command $it" })
        }
    }

    /** `/sgem history` 的点击目标 */
    fun targets(playerName: String?): Targets = Targets.of(HISTORY_COMMAND, playerName)

    /**
     * 用法行: `用法: /sgem history <玩家名>` —— 前半句整段由 lang 提供,
     * 后面的参数占位点击即补全命令
     * @param label    说明文字(来自 lang, 含命令本身)
     * @param argument 参数占位文字(来自 lang)
     */
    fun usageLine(label: String, argument: String, argumentHover: String, suggest: String): ComponentText =
        Components.text(label)
            .append(" ")
            .append(
                Components.text(argument)
                    .clickSuggestCommand(suggest)
                    .hoverText(argumentHover)
            )

    /** "查看自己的记录"入口, 点击直接以该玩家名执行命令 */
    fun selfEntry(run: String, label: String, hover: String): ComponentText =
        Components.text(label)
            .clickRunCommand(run)
            .hoverText(hover)

    /** `/sgem history` 不带参数时的完整提示(只拼装, 不发送) */
    fun historyUsage(sender: CommandSender?): ComponentText {
        val playerName = (sender as? Player)?.name
        val targets = targets(playerName)
        val line = usageLine(
            Lang.get("audit-usage-command"),
            Lang.get("audit-usage-player"),
            Lang.get("audit-usage-player-hover"),
            targets.suggest
        )
        val run = targets.run ?: return line
        val name = playerName.orEmpty()
        return line.append(" ").append(
            selfEntry(
                run,
                Lang.get("audit-usage-self", "player" to name),
                Lang.get("audit-usage-self-hover", "player" to name)
            )
        )
    }

    /** 把提示发给命令发送者(控制台也能收到, 只是少了"自己的记录"那一段) */
    fun sendHistoryUsage(sender: CommandSender) {
        val prefix = Lang.getOrNull("prefix")?.takeIf { it.isNotBlank() }
        val message = if (prefix == null) historyUsage(sender) else Components.text(prefix).append(historyUsage(sender))
        message.sendTo(adaptCommandSender(sender))
    }
}
