package mc233.`fun`.snowygems.gui

import mc233.`fun`.snowygems.util.ColorUtil
import org.bukkit.inventory.ItemStack
import taboolib.common.platform.function.getDataFolder
import taboolib.library.xseries.XMaterial
import taboolib.module.configuration.Configuration
import taboolib.platform.util.buildItem
import java.io.File

/** Visual settings for the in-game editor, kept separate from workbench menus. */
object EditorTheme {
    @Volatile private var config: Configuration? = null

    fun reload() {
        val file = File(getDataFolder(), "gui/editor.yml")
        require(file.isFile) { "缺少 gui/editor.yml" }
        val loaded = Configuration.loadFromFile(file)
        val content = loaded.getIntegerList("ContentSlots").ifEmpty { defaultContent }
        require(content.isNotEmpty() && content.distinct().size == content.size && content.all { it in 0..53 }) {
            "gui/editor.yml: ContentSlots 必须是 0..53 内不重复的位置"
        }
        val controls = listOf("Back", "Add", "Previous", "Next").map { key ->
            val value = loaded.getInt("Slots.$key", defaultSlot(key))
            require(value in 0..53 && value !in content) { "gui/editor.yml: Slots.$key 与内容区域冲突或超出范围" }
            value
        }
        require(controls.distinct().size == controls.size) { "gui/editor.yml: 返回、添加和翻页按钮的位置不能重复" }
        fun checkPage(label: String, keys: List<Pair<String, Int>>, lastSlot: Int, occupied: Set<Int> = emptySet()) {
            val positions = keys.map { (key, fallback) -> loaded.getInt("Slots.$key", fallback) }
            require(positions.all { it in 0..lastSlot } && positions.distinct().size == positions.size && positions.none { it in occupied }) {
                "gui/editor.yml: $label 的按钮位置超出界面或相互重叠"
            }
        }
        checkPage("首页", listOf("HomeGem" to 11, "HomeRune" to 15, "HomeHelp" to 22), 26)
        checkPage("条目详情", listOf("DetailSummary" to 4, "DetailBack" to 27,
            "DetailMove" to 29, "DetailFields" to 31, "DetailDelete" to 35), 35, setOf(10, 12, 14, 16, 22))
        checkPage("确认页", listOf("ConfirmYes" to 11, "ConfirmInfo" to 13, "ConfirmNo" to 15), 26)
        config = loaded
    }

    private val defaultContent = (1 until 5).flatMap { row -> (1..7).map { column -> row * 9 + column } }

    fun contentSlots(): List<Int> = config?.getIntegerList("ContentSlots")?.ifEmpty { defaultContent } ?: defaultContent

    private fun defaultSlot(key: String) = when (key) {
        "Back" -> 45
        "Add" -> 49
        "Previous" -> 48
        "Next" -> 50
        else -> -1
    }

    fun slot(key: String, fallback: Int): Int = config?.getInt("Slots.$key", fallback) ?: fallback

    fun title(key: String, fallback: String, vars: Map<String, String> = emptyMap()): String =
        ColorUtil.colorize(render(config?.getString("Titles.$key") ?: fallback, vars))

    fun icon(role: String, material: XMaterial, name: String, lines: List<String> = emptyList(),
             vars: Map<String, String> = emptyMap()): ItemStack {
        val section = config?.getConfigurationSection("Icons.$role")
        val selected = section?.getString("Material")?.let { XMaterial.matchXMaterial(it).orElse(material) } ?: material
        val values = vars + ("name" to name)
        return buildItem(selected) {
            this.name = ColorUtil.colorize(render(section?.getString("Display") ?: name, values))
            val tips = section?.getStringList("Tips")?.takeIf { it.isNotEmpty() } ?: lines
            lore.addAll(tips.map { ColorUtil.colorize(render(it, values)) })
            if (section?.getBoolean("Glow", false) == true) shiny()
        }
    }

    private fun render(raw: String, vars: Map<String, String>): String {
        var result = raw
        vars.forEach { (key, value) -> result = result.replace("{$key}", value) }
        return result
    }
}
