package mc233.`fun`.snowygems.config

import mc233.`fun`.snowygems.util.DebugUtil
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.releaseResourceFolder
import taboolib.common.platform.function.info
import taboolib.common.platform.function.severe
import taboolib.module.configuration.Configuration
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object SkillRegistry {

    private val skills = ConcurrentHashMap<String, SkillDef>()

    fun reload() {
        skills.clear()
        releaseResourceFolder("skills/", replace = false)
        val folder = File(getDataFolder(), "skills")
        val files = folder.listFiles { f -> f.isFile && f.extension.equals("yml", true) } ?: emptyArray()
        DebugUtil.log("Registry", "开始加载技能配置, 目录=${folder.absolutePath} 发现 ${files.size} 个文件: ${files.joinToString { it.name }}")
        for (file in files) {
            try {
                val before = skills.size
                loadFile(file)
                DebugUtil.log("Registry", "  ${file.name} 加载了 ${skills.size - before} 个技能定义")
            } catch (e: Exception) {
                severe("加载技能配置文件失败: ${file.name} -> ${e.message}")
                DebugUtil.err("Registry", "加载技能配置文件失败: ${file.name}", e)
            }
        }
        info("已加载 ${skills.size} 个技能/BUFF 定义")
        DebugUtil.log("Registry", "技能加载完毕, 带Lore标记可触发的共 ${withLore().size} 个: ${withLore().map { it.id }}")
    }

    private fun loadFile(file: File) {
        val cfg = Configuration.loadFromFile(file)
        // DefaultMMSkills.yml 顶层有一个 depend: MythicMobs 标记, 不是技能节点
        val dependOn = cfg.getString("depend")
        if (dependOn != null && org.bukkit.Bukkit.getPluginManager().getPlugin(dependOn) == null) {
            DebugUtil.log("Registry", "  跳过 ${file.name}: 依赖的插件 $dependOn 未安装")
            return
        }
        for (key in cfg.getKeys(false)) {
            if (key.equals("depend", true)) continue
            val sec = cfg.getConfigurationSection(key) ?: continue
            val def = SkillDef(
                id = key,
                lore = sec.getString("Lore"),
                cooldown = sec.getDouble("Cooldown", 0.0),
                cooldownTip = sec.getString("CooldownTip"),
                skills = sec.getStringList("Skills")
            )
            skills[key] = def
            DebugUtil.log("Registry", "    解析技能 id=$key lore标记=${def.lore} 冷却=${def.cooldown}s 技能行=${def.skills.size}条")
            def.skills.forEach { DebugUtil.log("Registry", "      行: $it") }
        }
    }

    fun get(id: String): SkillDef? = skills[id]

    fun all(): Collection<SkillDef> = skills.values

    /** 所有带 Lore 标记的技能/BUFF, 用于按物品 Lore 反查触发 */
    fun withLore(): List<SkillDef> = skills.values.filter { !it.lore.isNullOrBlank() }
}
