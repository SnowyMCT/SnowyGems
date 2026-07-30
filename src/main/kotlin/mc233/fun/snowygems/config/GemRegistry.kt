package mc233.`fun`.snowygems.config

import mc233.`fun`.snowygems.util.DebugUtil
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.releaseResourceFolder
import taboolib.common.platform.function.info
import taboolib.common.platform.function.severe
import taboolib.module.configuration.Configuration
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object GemRegistry {

    private val gems = ConcurrentHashMap<String, GemConfig>()

    fun reload() {
        gems.clear()
        // 首次运行时释放内置默认配置, 不覆盖玩家已有的自定义文件
        releaseResourceFolder("gems/", replace = false)
        val folder = File(getDataFolder(), "gems")
        val files = folder.listFiles { f -> f.isFile && (f.extension.equals("yml", true) || f.extension.equals("yaml", true)) }
            ?: emptyArray()
        DebugUtil.log("Registry", "开始加载宝石配置, 目录=${folder.absolutePath} 发现 ${files.size} 个文件: ${files.joinToString { it.name }}")
        for (file in files) {
            try {
                val before = gems.size
                loadFile(file)
                DebugUtil.log("Registry", "  ${file.name} 加载了 ${gems.size - before} 个条目")
            } catch (e: Exception) {
                severe("加载宝石配置文件失败: ${file.name} -> ${e.message}")
                DebugUtil.err("Registry", "加载宝石配置文件失败: ${file.name}", e)
            }
        }
        info("已加载 ${gems.size} 个宝石/物品配置")
        DebugUtil.log("Registry", "宝石加载完毕, 分类=${categories()} 全部ID=${gems.keys.sorted()}")
    }

    private fun loadFile(file: File) {
        val cfg = Configuration.loadFromFile(file)
        val category = file.nameWithoutExtension
        for (key in cfg.getKeys(false)) {
            if (key.equals("Version", true)) continue
            val sec = cfg.getConfigurationSection(key) ?: continue
            val gem = parse(key, sec, category)
            gems[key] = gem
            DebugUtil.log(
                "Registry",
                "    解析宝石 id=$key 分类=$category type=${gem.type} material=${gem.material} " +
                    "texture=${if (gem.texture.isNullOrBlank()) "无" else "有"} require=${gem.require} gui=${gem.gui} " +
                    "success=${gem.success} embed=${gem.embed} rewards=${gem.rewards.size}条 randomPool=${gem.randomPool.keys}"
            )
        }
    }

    private fun parse(id: String, sec: taboolib.library.configuration.ConfigurationSection, category: String): GemConfig {
        val randomPool = LinkedHashMap<String, Int>()
        sec.getConfigurationSection("Gems")?.let { gs ->
            for (k in gs.getKeys(false)) {
                randomPool[k] = gs.getInt(k, 1)
            }
        }
        return GemConfig(
            id = id,
            name = sec.getString("Name", id) ?: id,
            type = GemType.parse(sec.getString("Type")),
            require = sec.getStringList("Require"),
            display = sec.getString("Display", "") ?: "",
            tips = sec.getStringList("Tips"),
            texture = sec.getString("Texture"),
            material = sec.getString("Material"),
            glow = sec.getBoolean("Glow", false),
            success = sec.getInt("Success", 100),
            embed = sec.getInt("Embed", 0),
            color = sec.getString("Color"),
            eat = sec.getBoolean("Eat", false),
            successTip = sec.getString("SuccessTip"),
            removeTip = sec.getString("RemoveTip"),
            failTip = sec.getString("FailTip"),
            rewards = sec.getStringList("Rewards"),
            randomPool = randomPool,
            gui = sec.getStringList("Gui"),
            category = category
        )
    }

    fun get(id: String): GemConfig? = gems[id]

    fun all(): Collection<GemConfig> = gems.values

    fun ids(): Set<String> = gems.keys

    fun categories(): List<String> = gems.values.map { it.category }.distinct().sorted()

    fun byCategory(category: String): List<GemConfig> = gems.values.filter { it.category == category }.sortedBy { it.id }
}
