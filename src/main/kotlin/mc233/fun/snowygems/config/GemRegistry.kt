package mc233.`fun`.snowygems.config

import mc233.`fun`.snowygems.reward.RewardTokenParser
import mc233.`fun`.snowygems.reward.impl.RewardFactory
import mc233.`fun`.snowygems.util.DebugUtil
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.releaseResourceFolder
import taboolib.common.platform.function.info
import taboolib.common.platform.function.severe
import taboolib.common.platform.function.warning
import taboolib.module.configuration.Configuration
import java.io.File

object GemRegistry {

    private var gems: Map<String, GemConfig> = emptyMap()
    internal fun snapshot() = gems
    internal fun restore(value: Map<String, GemConfig>) { gems = value }

    fun reload() {
        val loaded = linkedMapOf<String, GemConfig>()
        // 首次运行时释放内置默认配置, 不覆盖玩家已有的自定义文件
        releaseResourceFolder("gems/", replace = false)
        val folder = File(getDataFolder(), "gems")
        val files = folder.listFiles { f -> f.isFile && (f.extension.equals("yml", true) || f.extension.equals("yaml", true)) }
            ?: emptyArray()
        DebugUtil.log("Registry", "开始加载宝石配置, 目录=${folder.absolutePath} 发现 ${files.size} 个文件: ${files.joinToString { it.name }}")
        for (file in files.sortedBy { it.name }) {
            try {
                val before = loaded.size
                loadFile(file, loaded)
                DebugUtil.log("Registry", "  ${file.name} 加载了 ${loaded.size - before} 个条目")
            } catch (e: Exception) {
                severe("加载宝石配置文件失败: ${file.name} -> ${e.message}")
                throw IllegalArgumentException("${file.name}: ${e.message}", e)
            }
        }
        gems = loaded.toMap()
        info("已加载 ${gems.size} 个宝石/物品配置")
        DebugUtil.log("Registry", "宝石加载完毕, 分类=${categories()} 全部ID=${gems.keys.sorted()}")
    }

    private fun loadFile(file: File, loaded: MutableMap<String, GemConfig>) {
        val cfg = Configuration.loadFromFile(file)
        val category = file.nameWithoutExtension
        var legacyDismantle = 0
        for (key in cfg.getKeys(false)) {
            if (key.equals("Version", true)) continue
            val sec = cfg.getConfigurationSection(key) ?: continue
            val gem = parse(key, sec, category)
            if (sec.contains("RemoveTip") || gem.parsedRewards.any { "onRemove" in it.flags }) legacyDismantle++
            require(key !in loaded) { "重复宝石 ID: $key" }
            require(gem.embed >= 0 && gem.success in 0..100) { "$key: Embed / Success 无效" }
            loaded[key] = gem
            DebugUtil.log(
                "Registry",
                "    解析宝石 id=$key 分类=$category type=${gem.type} material=${gem.material} " +
                    "texture=${if (gem.texture.isNullOrBlank()) "无" else "有"} require=${gem.require} gui=${gem.gui} " +
                    "success=${gem.success} embed=${gem.embed} rewards=${gem.rewards.size}条 randomPool=${gem.randomPool.keys}"
            )
        }
        if (legacyDismantle > 0) warning("${file.name} 中 $legacyDismantle 个宝石含旧拆卸字段/奖励，现已忽略；请将价格和成功率移入 dismantle/ 目录")
    }

    private fun parse(id: String, sec: taboolib.library.configuration.ConfigurationSection, category: String): GemConfig {
        val randomPool = LinkedHashMap<String, Int>()
        sec.getConfigurationSection("Gems")?.let { gs ->
            for (k in gs.getKeys(false)) {
                randomPool[k] = gs.getInt(k, 1)
            }
        }
        val rewardEntries = ActionSyntax.entries(sec, "Rewards")
        val rawRewards = rewardEntries.map { it.toString() }
        val parsedRewards = rewardEntries.mapIndexed { index, entry ->
            try {
                ActionSyntax.reward(entry).also { it.reward = RewardFactory.create(it.call) }
            } catch (e: Exception) {
                throw IllegalArgumentException("$id Rewards 第 ${index + 1} 条: ${e.message}", e)
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
            exclusiveGroup = sec.getString("ExclusiveGroup", "")?.trim() ?: "",
            trackApplied = sec.getBoolean("TrackApplied", true),
            color = sec.getString("Color"),
            eat = sec.getBoolean("Eat", false),
            successTip = sec.getString("SuccessTip"),
            failTip = sec.getString("FailTip"),
            rewards = rawRewards,
            // 一次性预解析: 每次镶嵌/使用直接取用, 不再重复解析配置行;
            // 顺带创建并缓存 Reward 实例(全部实现为不可变配置持有者, 可安全共享), 运行时零分配
            parsedRewards = parsedRewards,
            randomPool = randomPool,
            randomGiveItem = sec.getBoolean("GiveItem", false),
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
