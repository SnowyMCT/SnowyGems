package mc233.`fun`.snowygems.config

import mc233.`fun`.snowygems.skill.SkillLine
import mc233.`fun`.snowygems.skill.SkillLineParser
import mc233.`fun`.snowygems.util.ColorUtil
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

    /** 带 Lore 标记的技能定义, 加载时算好缓存, 触发/BUFF 循环直接取用(避免每次事件重新 filter 分配) */
    private var withLoreCache: List<SkillDef> = emptyList()

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
        withLoreCache = skills.values.filter { !it.lore.isNullOrBlank() }
        info("已加载 ${skills.size} 个技能/BUFF 定义")
        DebugUtil.log("Registry", "技能加载完毕, 带Lore标记可触发的共 ${withLoreCache.size} 个: ${withLoreCache.map { it.id }}")
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
            val rawSkills = sec.getStringList("Skills")
            // 一次性预解析: 触发事件里直接取用, 不再每次重复解析配置行
            val parsed = rawSkills.mapNotNull { raw ->
                if (raw.isBlank()) null else runCatching { SkillLineParser.parse(raw) }.getOrNull()
            }
            // 按触发标记分组(一行可挂多个 ~trigger, 会出现在多组); 无触发标记的行不参与
            val byTrigger = HashMap<String, MutableList<SkillLine>>()
            for (line in parsed) {
                for (t in line.triggers) byTrigger.getOrPut(t) { ArrayList() }.add(line)
            }
            val def = SkillDef(
                id = key,
                lore = sec.getString("Lore"),
                cooldown = sec.getDouble("Cooldown", 0.0),
                cooldownTip = sec.getString("CooldownTip"),
                skills = rawSkills,
                parsedSkills = parsed,
                byTrigger = byTrigger,
                timerLines = byTrigger["onTimer"] ?: emptyList(),
                loreClean = sec.getString("Lore")?.let { ColorUtil.stripColor(it).trim() } ?: ""
            )
            skills[key] = def
            DebugUtil.log("Registry", "    解析技能 id=$key lore标记=${def.lore} 冷却=${def.cooldown}s 技能行=${def.skills.size}条")
            def.skills.forEach { DebugUtil.log("Registry", "      行: $it") }
        }
    }

    fun get(id: String): SkillDef? = skills[id]

    fun all(): Collection<SkillDef> = skills.values

    /** 所有带 Lore 标记的技能/BUFF, 用于按物品 Lore 反查触发(加载时缓存, 零分配) */
    fun withLore(): List<SkillDef> = withLoreCache
}
