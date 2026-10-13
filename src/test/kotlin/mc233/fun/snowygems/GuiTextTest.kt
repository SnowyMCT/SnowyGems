package mc233.`fun`.snowygems

import mc233.`fun`.snowygems.util.ColorUtil
import mc233.`fun`.snowygems.util.GuiText
import kotlin.test.*

class GuiTextTest {

    @Test fun `null and blank values never crash a gui`() {
        assertEquals("", GuiText.line(null))
        assertTrue(GuiText.lines(null).isEmpty())
        assertEquals(" ", GuiText.line(" "), "填充用的空格不能被裁掉")
    }

    @Test fun `literal text keeps colours and only replaces the given placeholders`() {
        assertEquals(ColorUtil.colorize("&7维修 &c{gem}"), GuiText.line("&7维修 &c{gem}"))
        assertEquals(ColorUtil.colorize("&7维修 500 金币"), GuiText.line("&7维修 {cost} 金币", mapOf("cost" to "500")))
        // 调用方没给值的占位符原样保留, 一眼能看出是漏传了
        assertEquals(ColorUtil.colorize("&7红宝石 -> {cost}"), GuiText.line("&7{gem} -> {cost}", mapOf("gem" to "红宝石")))
    }

    @Test fun `a literal may carry several lines`() {
        assertContentEquals(
            listOf(ColorUtil.colorize("&a第一行"), ColorUtil.colorize("&b第二行")),
            GuiText.lines("&a第一行\n&b第二行")
        )
    }

    @Test fun `lang prefix is recognised regardless of case and padding`() {
        for (value in listOf("lang:no-such-node", "  LANG:no-such-node  ", "Lang:no-such-node")) {
            val rendered = GuiText.lines(value)
            // 节点不存在时不能静默变空: 把原文显示出来, 界面上一眼能看出配置写错了
            assertEquals(1, rendered.size, "$value 应该退化成一行原文")
            assertTrue(rendered.single().contains("no-such-node"), "$value -> $rendered")
        }
        // 只有前缀没有节点名时按普通文本处理
        assertEquals(ColorUtil.colorize("lang:"), GuiText.line("lang:"))
        // 只是长得像前缀的普通文本不受影响
        assertEquals(ColorUtil.colorize("langx:hello"), GuiText.line("langx:hello"))
    }

    @Test fun `line() is the first line that lines() produced`() {
        val raw = "&a第一行\n&b第二行"
        assertEquals(GuiText.lines(raw).first(), GuiText.line(raw))
    }
}
