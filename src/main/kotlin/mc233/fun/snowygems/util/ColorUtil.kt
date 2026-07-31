package mc233.`fun`.snowygems.util

import taboolib.module.chat.colored
import taboolib.module.chat.uncolored

/**
 * 颜色处理.
 *
 * 底层直接用 TabooLib 的 [colored] / [uncolored], 不再自己写 replace('&','§'):
 *   - 支持 &#RRGGBB 十六进制颜色 (1.16+), 老实现只认 &a 这种传统码
 *   - 只转换真正的颜色码, 不会把配置文本里当普通字符用的 & 误伤
 *   - 去色由 TabooLib 处理, 比自己写正则可靠
 */
object ColorUtil {

    /** 将配置中的 &颜色代码 / &#RRGGBB 转换为客户端可识别的 §序列 */
    fun colorize(text: String): String = text.colored()

    fun colorize(list: List<String>): List<String> = list.map { it.colored() }

    /** 去除颜色代码, 用于纯文本比较 */
    fun stripColor(text: String): String = text.uncolored()

    /**
     * 判断一行 lore 是否"命中"某个技能/BUFF 的标记文本.
     * 两边都先去色再 trim, 从而容忍:
     *   - 颜色码穿插差异 (§6§l vs &6&l)
     *   - 配置标签尾部空格被参数解析 trim 掉 (如 "生命提升 " 写成 "生命提升4")
     * 只要去色后的 lore 行包含去色后的标记(忽略两端空白), 即算命中.
     */
    fun loreMatches(loreLine: String, marker: String): Boolean {
        val a = stripColor(loreLine).trim()
        val b = stripColor(marker).trim()
        if (b.isEmpty()) return false
        return a.contains(b)
    }
}
