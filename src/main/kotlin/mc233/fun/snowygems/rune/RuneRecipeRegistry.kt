package mc233.`fun`.snowygems.rune

import mc233.`fun`.snowygems.config.GemRegistry
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.releaseResourceFolder
import taboolib.common.platform.function.severe
import taboolib.common.platform.function.info
import taboolib.module.configuration.Configuration
import java.io.File

object RuneRecipeRegistry {
    private var recipes: Map<String, RuneRecipe> = emptyMap()
    var generation = 0L
        private set

    fun invalidate() { generation++ }
    fun get(id: String): RuneRecipe? = recipes[id]
    fun all(): List<RuneRecipe> = recipes.values.toList()

    fun reload() {
        invalidate()
        val loaded = linkedMapOf<String, RuneRecipe>()
        try {
            releaseResourceFolder("runes/", replace = false)
            val config = Configuration.loadFromFile(File(getDataFolder(), "runes/forge.yml"))
            val section = config.getConfigurationSection("Recipes") ?: error("缺少 Recipes 节点")
            for (id in section.getKeys(false)) {
                try {
                    val entry = section.getConfigurationSection(id) ?: error("配方必须是配置节")
                    if (!entry.getBoolean("Enabled", true)) continue
                    val ingredients = entry.getConfigurationSection("Ingredients") ?: error("缺少 Ingredients")
                    val recipe = RuneRecipe(id, entry.getString("Display", id) ?: id,
                        entry.getString("Result") ?: error("缺少 Result"),
                        entry.get("Amount")?.toString()?.toIntOrNull() ?: if (!entry.contains("Amount")) 1 else error("Amount 必须为整数"),
                        ingredients.getKeys(false).associateWith { key ->
                            ingredients.get(key)?.toString()?.toIntOrNull() ?: error("Ingredients.$key 必须为整数")
                        })
                    require(GemRegistry.get(recipe.result) != null) { "产物宝石不存在: ${recipe.result}" }
                    recipe.ingredients.keys.forEach { require(GemRegistry.get(it) != null) { "材料宝石不存在: $it" } }
                    loaded[id] = recipe
                } catch (e: Exception) {
                    severe("跳过符文配方 $id: ${e.message}")
                }
            }
        } catch (e: Exception) {
            severe("加载 runes/forge.yml 失败: ${e.message}")
        }
        recipes = loaded.toMap()
        info("已加载 ${recipes.size} 个符文锻造配方")
    }
}
