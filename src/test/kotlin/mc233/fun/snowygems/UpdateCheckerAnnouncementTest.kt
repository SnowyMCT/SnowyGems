package mc233.`fun`.snowygems

import mc233.`fun`.snowygems.update.AnnounceResult
import mc233.`fun`.snowygems.update.parseAnnouncement
import mc233.`fun`.snowygems.update.throttleElapsed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 管理员上线拉公告: 只测纯逻辑(解析 + 节流判定), 联网/投递那部分依赖真实服务端.
 */
class UpdateCheckerAnnouncementTest {

    @Test fun `announcement body is trimmed per line and trailing blanks are dropped`() {
        val lines = parseAnnouncement("&a第一行  \r\n&b第二行\r\n\r\n\r\n")
        assertEquals(listOf("&a第一行", "&b第二行"), lines)
    }

    @Test fun `blank only body yields no announcement`() {
        assertEquals(emptyList(), parseAnnouncement(""))
        assertEquals(emptyList(), parseAnnouncement("\r\n\r\n"))
        assertEquals(emptyList(), parseAnnouncement("   \r\n\t\r\n"))
    }

    @Test fun `inner blank lines survive so the remote layout is kept`() {
        assertEquals(listOf("a", "", "b"), parseAnnouncement("a\n\nb\n"))
    }

    @Test fun `throttle blocks a refetch inside the window and allows it once elapsed`() {
        val now = 1_000_000_000L
        val five = 5L * 60_000L
        assertFalse(throttleElapsed(now, now, 5), "刚拉过, 不该重新联网")
        assertFalse(throttleElapsed(now - five + 1, now, 5), "差 1ms 未到节流时间")
        assertTrue(throttleElapsed(now - five, now, 5), "刚好到节流时间, 允许重新拉")
        assertTrue(throttleElapsed(0L, now, 5), "本次运行还没拉过")
    }

    @Test fun `zero minutes disables throttling`() {
        assertTrue(throttleElapsed(1_000_000L, 1_000_001L, 0L))
    }

    @Test fun `empty result is its own outcome, not a failed fetch`() {
        // 三种结果必须能区分: 空公告要发 update-announce-empty, 拉取失败发 update-console-failed
        assertEquals(3, AnnounceResult.entries.size)
    }
}
