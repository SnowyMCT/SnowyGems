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

    fun colorize(text: String): String = text.colored()

    fun colorize(list: List<String>): List<String> = list.map { it.colored() }

    fun stripColor(text: String): String = text.uncolored()

    fun loreMatches(loreLine: String, marker: String): Boolean {
        val a = stripColor(loreLine).trim()
        val b = stripColor(marker).trim()
        if (b.isEmpty()) return false
        return a.contains(b)
    }
}
