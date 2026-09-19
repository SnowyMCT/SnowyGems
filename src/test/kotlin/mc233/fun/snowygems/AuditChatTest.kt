package mc233.`fun`.snowygems

import mc233.`fun`.snowygems.manager.AuditChat
import mc233.`fun`.snowygems.manager.AuditEntry
import java.time.Instant
import java.time.ZoneId
import kotlin.test.*

class AuditChatTest {
    private val translations = mapOf(
        "audit.entry" to "§8{time} §b{action} §f{subject} §8— {result}",
        "audit.action.embed" to "镶嵌",
        "audit.action.dismantle" to "拆卸",
        "audit.result.success" to "§a成功",
        "audit.result.refunded" to "§e取消，费用已退",
        "audit.unknown-item" to "未知物品"
    )
    private fun text(key: String) = translations.getValue(key)

    @Test fun `chat uses local time and readable summaries without raw fields or obfuscation`() {
        val entry = AuditEntry(Instant.parse("2026-09-17T04:30:00Z"), "Alex", "embed", "&8&l&k||§c生命宝石\t\n", "success")
        val line = AuditChat.render(entry, ::text, ZoneId.of("Asia/Shanghai"))
        assertEquals("§809-17 12:30 §b镶嵌 §f||生命宝石 §8— §a成功", line)
        assertFalse(line.contains('\t'))
        assertFalse(line.contains('\n'))
        assertFalse(line.contains("&k"))
        assertFalse(line.contains("success="))
    }

    @Test fun `refund diagnostic action is rendered as a single dismantle outcome`() {
        val entry = AuditEntry(Instant.EPOCH, "Alex", "dismantle-refund", "宝石", "refunded")
        val line = AuditChat.render(entry, ::text, ZoneId.of("UTC"))
        assertTrue(line.contains("拆卸"))
        assertTrue(line.endsWith("§e取消，费用已退"))
        assertFalse(line.contains("dismantle-refund"))
    }

    @Test fun `long Unicode names stay bounded and cannot substitute result placeholders`() {
        val plain = AuditChat.plain("§x§f§f§0§0§0§0" + "💎".repeat(30))
        assertEquals(24, plain.codePointCount(0, plain.length))
        assertTrue(plain.endsWith("…"))
        val entry = AuditEntry(Instant.EPOCH, "Alex", "embed", "{result}", "success")
        val line = AuditChat.render(entry, ::text, ZoneId.of("UTC"))
        assertTrue(line.contains("§f{result}"))
    }
}
