package mc233.`fun`.snowygems.manager

import org.bukkit.entity.Player
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.severe
import java.io.File
import java.time.Instant
import java.util.logging.FileHandler
import java.util.logging.Formatter
import java.util.logging.LogRecord
import java.util.logging.Level

/** Bounded diagnostic journal, independent of Debug. Not a crash-recovery transaction log. */
object OperationAudit {
    private val recent = ArrayDeque<AuditEntry>()
    private var handler: FileHandler? = null
    private var warned = false

    internal fun clean(value: String): String = value.replace(Regex("[\\r\\n\\t]"), " ").take(1000)

    @Synchronized
    fun record(player: Player, action: String, detail: String, subject: String, outcome: String) {
        val now = Instant.now()
        val line = "$now\t${player.uniqueId}\t${clean(player.name)}\t${clean(action)}\t${clean(detail)}"
        val gem = mc233.`fun`.snowygems.config.GemRegistry.get(subject)
        val name = gem?.display?.ifBlank { gem.name } ?: subject
        if (action != "dismantle-charged") recent.addLast(AuditEntry(now, player.name, action, name, outcome))
        while (recent.size > 500) recent.removeFirst()
        try {
            val writer = handler ?: run {
                val dir = File(getDataFolder(), "logs").apply { mkdirs() }
                FileHandler(File(dir, "operations-%g.log").path, 1_048_576, 5, true).apply {
                    encoding = "UTF-8"
                    formatter = object : Formatter() {
                        override fun format(record: LogRecord): String = record.message + "\n"
                    }
                }.also { handler = it }
            }
            writer.publish(LogRecord(Level.INFO, line))
            writer.flush()
            warned = false
        } catch (e: Exception) {
            if (!warned) severe("SnowyGems 操作日志写入失败: ${e.message}")
            warned = true
        }
    }

    @Synchronized
    fun recent(player: String): List<AuditEntry> = recent.filter { it.player.equals(player, true) }.takeLast(5).reversed()

    @Synchronized
    fun close() { handler?.close(); handler = null }
}
