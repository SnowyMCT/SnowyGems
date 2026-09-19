package mc233.`fun`.snowygems

import mc233.`fun`.snowygems.rune.RuneRecipe
import mc233.`fun`.snowygems.rune.RuneRecipeFiles
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class RuneRecipeFilesTest {
    private fun withFolder(block: (File) -> Unit) {
        val folder = Files.createTempDirectory("snowygems-recipes").toFile()
        try { block(folder) } finally { folder.deleteRecursively() }
    }
    private fun file(folder: File, name: String): File = File(folder, name).apply {
        parentFile.mkdirs()
        writeText("server-owned contents")
    }
    private fun recipe(id: String, result: String = "rune", ingredient: String = "sand") =
        RuneRecipe(id, id, result, 1, mapOf(ingredient to 24))

    @Test fun `expansion discovery preserves legacy and ignores unrelated root yaml`() = withFolder { folder ->
        val legacy = file(folder, "forge.yml")
        val before = legacy.readBytes()
        file(folder, "draft.yml")
        file(folder, "packs/z.yaml")
        file(folder, "packs/a.yml")
        file(folder, "packs/notes.txt")
        val files = RuneRecipeFiles.sources(folder)
        assertEquals(listOf("forge.yml", "a.yml", "z.yaml"), files.map { it.name })
        val loaded = RuneRecipeFiles.load(files, { listOf(recipe(it.nameWithoutExtension)) }) { true }
        assertEquals(listOf("forge", "a", "z"), loaded.keys.toList())
        assertContentEquals(before, legacy.readBytes())
        assertEquals(24, loaded.getValue("forge").ingredients["sand"])
    }

    @Test fun `duplicate expansion cannot replace legacy recipe`() = withFolder { folder ->
        val files = listOf(file(folder, "forge.yml"), file(folder, "packs/new.yml"))
        val error = assertFailsWith<IllegalArgumentException> {
            RuneRecipeFiles.load(files, { listOf(recipe("legacy")) }) { true }
        }
        assertTrue(error.message.orEmpty().contains("重复配方 ID"))
    }

    @Test fun `empty or disabled expansion keeps legacy entries`() = withFolder { folder ->
        val files = listOf(file(folder, "forge.yml"), file(folder, "packs/new.yml"))
        val loaded = RuneRecipeFiles.load(files, {
            if (it.name == "forge.yml") listOf(recipe("legacy")) else emptyList()
        }) { true }
        assertEquals(setOf("legacy"), loaded.keys)
    }

    @Test fun `missing files and invalid references cannot yield a partial result`() = withFolder { folder ->
        assertFailsWith<IllegalArgumentException> {
            RuneRecipeFiles.load(RuneRecipeFiles.sources(folder), { emptyList() }) { true }
        }
        val files = listOf(file(folder, "forge.yml"), file(folder, "packs/new.yml"))
        for (invalid in listOf(recipe("new", result = "missing"), recipe("new", ingredient = "missing"))) {
            assertFailsWith<IllegalArgumentException> {
                RuneRecipeFiles.load(files, {
                    listOf(if (it.name == "forge.yml") recipe("legacy") else invalid)
                }) { it != "missing" }
            }
        }
    }
}
