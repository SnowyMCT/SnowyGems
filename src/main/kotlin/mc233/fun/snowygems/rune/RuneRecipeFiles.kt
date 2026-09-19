package mc233.`fun`.snowygems.rune

import taboolib.module.configuration.Configuration
import java.io.File

/** Keep forge.yml authoritative for legacy recipes; additional files live only in packs/. */
internal object RuneRecipeFiles {
    fun sources(folder: File): List<File> = listOf(File(folder, "forge.yml")) +
        (File(folder, "packs").listFiles { f ->
            f.isFile && f.extension.lowercase() in setOf("yml", "yaml")
        } ?: emptyArray()).sortedBy { it.name }

    fun load(
        files: List<File>,
        readRecipes: (File) -> List<RuneRecipe> = ::read,
        gemExists: (String) -> Boolean
    ): Map<String, RuneRecipe> {
        val loaded = linkedMapOf<String, RuneRecipe>()
        for (file in files) {
            try {
                require(file.isFile) { "配方文件不存在" }
                for (recipe in readRecipes(file)) {
                    require(recipe.id !in loaded) { "重复配方 ID: ${recipe.id}，不允许覆盖旧配方" }
                    require(gemExists(recipe.result)) { "${recipe.id}: 产物宝石不存在: ${recipe.result}" }
                    recipe.ingredients.keys.forEach {
                        require(gemExists(it)) { "${recipe.id}: 材料宝石不存在: $it" }
                    }
                    loaded[recipe.id] = recipe
                }
            } catch (e: Exception) {
                throw IllegalArgumentException("${file.path}: ${e.message}", e)
            }
        }
        return loaded.toMap()
    }

    private fun read(file: File): List<RuneRecipe> {
        val config = Configuration.loadFromFile(file)
        if (!config.getBoolean("Enabled", true)) return emptyList()
        val section = config.getConfigurationSection("Recipes") ?: error("缺少 Recipes 节点")
        return section.getKeys(false).mapNotNull { id ->
            try {
                val entry = section.getConfigurationSection(id) ?: error("配方必须是配置节")
                if (!entry.getBoolean("Enabled", true)) return@mapNotNull null
                val ingredients = entry.getConfigurationSection("Ingredients") ?: error("缺少 Ingredients")
                RuneRecipe(id, entry.getString("Display", id) ?: id,
                    entry.getString("Result") ?: error("缺少 Result"),
                    entry.get("Amount")?.toString()?.toIntOrNull()
                        ?: if (!entry.contains("Amount")) 1 else error("Amount 必须为整数"),
                    ingredients.getKeys(false).associateWith { key ->
                        ingredients.get(key)?.toString()?.toIntOrNull()
                            ?: error("Ingredients.$key 必须为整数")
                    })
            } catch (e: Exception) {
                throw IllegalArgumentException("配方 $id: ${e.message}", e)
            }
        }
    }
}
