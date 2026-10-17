package mc233.`fun`.snowygems

import mc233.`fun`.snowygems.update.parseAnnouncement
import mc233.`fun`.snowygems.update.throttleElapsed
import kotlin.test.*

/** 管理员上线拉公告的两个纯函数: 节流判定 + 远端正文解析 */
class UpdateCheckerAnnouncementTest {

    @Test fun `throttle blocks re-fetching inside the window`() {
        val now = 1_000_000L
        // 从没拉过(时间戳 0)必定允许
        assertTrue(throttleElapsed(0L, now, 5))
        // 刚拉完
        assertFalse(throttleElapsed(now, now, 5))
        assertFalse(throttleElapsed(now, now + 5 * 60_000L - 1, 5))
        // 刚好到 5 分钟就是"到点了"
        assertTrue(throttleElapsed(now, now + 5 * 60_000L, 5))
        assertTrue(throttleElapsed(now, now + 30 * 60_000L, 5))
    }

    @Test fun `zero or negative minutes disables throttling`() {
        assertTrue(throttleElapsed(System.currentTimeMillis(), System.currentTimeMillis(), 0))
        assertTrue(throttleElapsed(System.currentTimeMillis(), System.currentTimeMillis(), -1))
    }

    @Test fun `announcement parsing trims line tails and drops trailing blanks`() {
        assertContentEquals(listOf("第一行", "第二行"), parseAnnouncement("第一行  \n第二行\t\n\n\n"))
        // 中间的空白行保留(公告自己的排版)
        assertContentEquals(listOf("上", "", "下"), parseAnnouncement("上\n\n下"))
        // CRLF 与行尾空白
        assertContentEquals(listOf("a", "b"), parseAnnouncement("a\r\nb\r\n   \r\n"))
        // 整份都是空白 = 空公告(对应 AnnounceResult.EMPTY, 有自己的兜底文案)
        assertTrue(parseAnnouncement("").isEmpty())
        assertTrue(parseAnnouncement("   \n\t\n").isEmpty())
    }
}
