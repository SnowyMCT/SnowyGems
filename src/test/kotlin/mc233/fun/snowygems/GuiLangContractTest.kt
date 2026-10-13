package mc233.`fun`.snowygems

import kotlin.test.*

/**
 * gui 目录里每个 `lang:节点名` 引用, 必须在**每一个**语言文件里都存在.
 * 少一个键, 那个语言的界面就会退化成显示 "lang:xxx" 原文 —— 这是跨文件契约, 所以放在测试里看着.
 */
class GuiLangContractTest {

    private val languages = listOf("zh_CN", "zh_TW", "en_US", "ko_KR")
    private val guiFiles = listOf("dis.yml", "gui.yml", "rune.yml", "editor.yml")

    private fun resource(path: String): String =
        checkNotNull(javaClass.getResourceAsStream(path)) { "测试类路径里找不到 $path" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private fun langKeys(code: String): Set<String> =
        Regex("^([A-Za-z0-9_-]+):", RegexOption.MULTILINE).findAll(resource("/lang/$code.yml"))
            .map { it.groupValues[1] }.toSet()

    @Test fun `every lang reference in the gui configs exists in every language file`() {
        val refs = guiFiles
            .flatMap { Regex("lang:\\s*([A-Za-z0-9_.-]+)").findAll(resource("/gui/$it")).map { m -> m.groupValues[1] } }
            .toSet()
        assertTrue(refs.isNotEmpty(), "gui 配置里应该至少有一个 lang: 引用")
        val known = languages.associateWith { langKeys(it) }
        // 语言文件之间的键集合必须一致
        val standard = known.getValue("zh_CN")
        for ((code, keys) in known) {
            assertEquals(standard, keys, "lang/$code.yml 的键集合与 zh_CN.yml 不一致")
        }
        for (ref in refs) {
            // 允许写旧式点号键名(运行时自动转横杠), 两边都算命中
            val dashed = ref.replace('.', '-')
            val missing = languages.filter { ref !in known.getValue(it) && dashed !in known.getValue(it) }
            assertTrue(missing.isEmpty(), "gui 里引用了 $ref, 但 ${missing.joinToString()} 里没有这个节点")
        }
    }
}
