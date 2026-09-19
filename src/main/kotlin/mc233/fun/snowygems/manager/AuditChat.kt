package mc233.`fun`.snowygems.manager

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Chat reads structured summaries; raw diagnostic fields remain in the disk journal. */
data class AuditEntry(val time: Instant, val player: String, val action: String, val subject: String, val outcome: String)

object AuditChat {
    private val colors = Regex("(?i)[&§]#[0-9a-f]{6}|[&§][0-9a-fk-orx]")
    private val timestamp = DateTimeFormatter.ofPattern("MM-dd HH:mm")

    internal fun plain(value: String): String {
        val stripped = colors.replace(value, "").map {
            if (it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()) ' ' else it
        }.joinToString("").replace(Regex("\\s+"), " ").trim()
        val points = stripped.codePointCount(0, stripped.length)
        return if (points > 24) stripped.substring(0, stripped.offsetByCodePoints(0, 23)) + "…" else stripped
    }

    fun render(entry: AuditEntry, text: (String) -> String, zone: ZoneId = ZoneId.systemDefault()): String {
        val action = when (entry.action) {
            "dismantle-refund", "dismantle-error" -> "dismantle"
            else -> entry.action
        }
        // Substitutions are applied in one pass so a configured name cannot inject placeholders.
        val args = mapOf(
            "time" to timestamp.format(entry.time.atZone(zone)),
            "action" to text("audit.action.$action"),
            "subject" to plain(entry.subject).ifBlank { text("audit.unknown-item") },
            "result" to text("audit.result.${entry.outcome}")
        )
        return Regex("\\{(time|action|subject|result)}").replace(text("audit.entry")) {
            args.getValue(it.groupValues[1])
        }
    }
}
