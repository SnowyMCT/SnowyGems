package mc233.`fun`.snowygems.config

import mc233.`fun`.snowygems.SnowyGems
import taboolib.common.platform.function.getDataFolder
import taboolib.module.configuration.Configuration
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Converts legacy inline actions to YAML objects; original bytes are retained in a backup folder. */
object ConfigMigration {
    data class Result(val files: Int, val actions: Int, val backup: File?)

    fun migrate(): Result {
        val root = getDataFolder()
        val files = listOf("gems", "skills").flatMap { dir ->
            File(root, dir).listFiles { f -> f.isFile && f.extension.lowercase() in setOf("yml", "yaml") }
                ?.sortedBy { it.name } ?: emptyList()
        }
        val edits = linkedMapOf<File, Configuration>()
        var count = 0
        for (file in files) {
            val cfg = Configuration.loadFromFile(file)
            val key = if (file.parentFile.name == "gems") "Rewards" else "Skills"
            var changed = false
            for (id in cfg.getKeys(false)) {
                val sec = cfg.getConfigurationSection(id) ?: continue
                val entries = ActionSyntax.entries(sec, key)
                if (entries.none { it is String && (if (key == "Rewards") convertibleReward(it) else convertibleSkill(it)) }) continue
                val converted = entries.map { entry ->
                    if (entry !is String || !(if (key == "Rewards") convertibleReward(entry) else convertibleSkill(entry))) entry
                    else {
                        count++
                        if (key == "Rewards") ActionSyntax.rewardMap(ActionSyntax.reward(entry))
                        else ActionSyntax.skillMap(ActionSyntax.skill(entry))
                    }
                }
                cfg.set("$id.$key", converted)
                changed = true
            }
            if (changed) edits[file] = cfg
        }
        if (edits.isEmpty()) return Result(0, 0, null)
        val backup = File(root, "migration-backups/${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))}")
        backup.mkdirs()
        val originals = edits.keys.associateWith { it.readBytes() }
        try {
            for ((file, cfg) in edits) {
                val copy = File(backup, "${file.parentFile.name}/${file.name}")
                copy.parentFile.mkdirs()
                Files.copy(file.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING)
                cfg.saveToFile()
            }
            require(SnowyGems.reloadAll()) { "转换后配置重载失败" }
        } catch (e: Exception) {
            originals.forEach { (file, bytes) -> file.writeBytes(bytes) }
            SnowyGems.reloadAll()
            throw IllegalStateException("转换失败，原文件已恢复：${e.message}", e)
        }
        return Result(edits.size, count, backup)
    }

    /** Skills are converted only when the object form parses back to the same runtime line. */
    private fun convertibleSkill(raw: String): Boolean = raw.isNotBlank() && runCatching {
        val original = ActionSyntax.skill(raw)
        ActionSyntax.skill(ActionSyntax.skillMap(original)) == original
    }.getOrDefault(false)

    private fun convertibleReward(raw: String): Boolean = raw.isNotBlank() && runCatching {
        val original = ActionSyntax.reward(raw)
        val converted = ActionSyntax.reward(ActionSyntax.rewardMap(original))
        original.call.name == converted.call.name && original.call.args == converted.call.args && original.flags == converted.flags
    }.getOrDefault(false)
}
