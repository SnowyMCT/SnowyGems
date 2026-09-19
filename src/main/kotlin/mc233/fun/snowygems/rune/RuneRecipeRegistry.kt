package mc233.`fun`.snowygems.rune

import mc233.`fun`.snowygems.config.GemRegistry
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.releaseResourceFolder
import taboolib.common.platform.function.info
import java.io.File

object RuneRecipeRegistry {
    private var recipes: Map<String, RuneRecipe> = emptyMap()
    var generation = 0L
        private set

    fun invalidate() { generation++ }
    fun get(id: String): RuneRecipe? = recipes[id]
    fun all(): List<RuneRecipe> = recipes.values.toList()

    internal fun snapshot() = recipes
    internal fun restore(value: Map<String, RuneRecipe>) { recipes = value }

    fun reload() {
        invalidate()
        releaseResourceFolder("runes/", replace = false)
        releaseResourceFolder("runes/packs/", replace = false)
        val folder = File(getDataFolder(), "runes")
        val files = RuneRecipeFiles.sources(folder)
        // Publish only after every file and reference has passed validation.
        val loaded = RuneRecipeFiles.load(files) { GemRegistry.get(it) != null }
        recipes = loaded
        info("已加载 ${recipes.size} 个符文锻造配方")
    }
}
