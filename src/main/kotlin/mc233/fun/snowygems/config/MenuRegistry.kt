package mc233.`fun`.snowygems.config

import mc233.`fun`.snowygems.util.DebugUtil
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.releaseResourceFolder
import taboolib.common.platform.function.info
import taboolib.common.platform.function.severe
import taboolib.library.configuration.ConfigurationSection
import taboolib.module.configuration.Configuration
import java.io.File

object MenuRegistry {

    @Volatile
    private var menus: Map<String, MenuLayout> = emptyMap()

    fun reload() {
        val loaded = LinkedHashMap<String, MenuLayout>()
        releaseResourceFolder("gui/", replace = false)
        val folder = File(getDataFolder(), "gui")
        val files = folder.listFiles { f -> f.isFile && f.extension.lowercase() in setOf("yml", "yaml") }
            ?.sortedBy { it.name } ?: emptyList()
        DebugUtil.log("Registry", "开始加载菜单配置, 目录=${folder.absolutePath} 发现 ${files.size} 个文件: ${files.joinToString { it.name }}")
        for (file in files) {
            try {
                val before = loaded.size
                loadFile(file, loaded)
                DebugUtil.log("Registry", "  ${file.name} 加载了 ${loaded.size - before} 个菜单")
            } catch (e: Exception) {
                severe("加载菜单配置文件失败: ${file.name} -> ${e.message}")
                DebugUtil.err("Registry", "加载菜单配置文件失败: ${file.name}", e)
            }
        }
        menus = loaded.toMap()
        info("已加载 ${menus.size} 个菜单布局")
        DebugUtil.log("Registry", "菜单加载完毕, 名称=${menus.keys.sorted()}")
    }

    private fun loadFile(file: File, loaded: MutableMap<String, MenuLayout>) {
        val cfg = Configuration.loadFromFile(file)
        for (key in cfg.getKeys(false)) {
            val sec = cfg.getConfigurationSection(key) ?: continue
            try {
                require(key !in loaded) { "重复菜单名 '$key'，保留先加载的定义" }
                loaded[key] = parseMenu(key, sec)
            } catch (e: IllegalArgumentException) {
                severe("跳过无效菜单 ${file.name}:$key -> ${e.message}")
            }
        }
    }

    private fun parseMenu(name: String, sec: ConfigurationSection): MenuLayout {
        val rows = sec.getStringList("Slots")
        validateRows(rows)
        val itemsSec = sec.getConfigurationSection("Items")
        val items = LinkedHashMap<Char, MenuItemDef>()
        if (itemsSec != null) {
            for (key in itemsSec.getKeys(false)) {
                require(key.length == 1) { "Items 的键 '$key' 必须恰好为一个字符" }
                val c = key[0]
                val isec = itemsSec.getConfigurationSection(key) ?: continue
                val type = (isec.getString("Type", "EMPTY") ?: "EMPTY").uppercase()
                require(type in setOf("EQUIP_SLOT", "GEM_SLOT", "USE_GEM", "CONFIRM_EMBED", "CLOSE",
                    "PAGE_JUMP", "TIP", "EMPTY", "PAGE_PREV", "PAGE_NEXT", "PAGE_TIP", "RUNE_FORGE")) {
                    "Items.$key.Type 不受支持: $type"
                }
                val amount = isec.getInt("Amount", 1)
                require(amount in 1..64) { "Items.$key.Amount 必须在 1..64 之间" }
                items[c] = MenuItemDef(
                    char = c,
                    type = type,
                    material = isec.getString("Material"),
                    display = isec.getString("Display"),
                    glow = isec.getBoolean("Glow", false),
                    tips = isec.getStringList("Tips"),
                    require = readStringOrList(isec, "Require"),
                    amount = amount,
                    texture = isec.getString("Texture"),
                    gem = isec.getString("Gem"),
                    gui = isec.getString("Gui")
                )
            }
        }
        val missing = rows.flatMap { it.toList() }.filter { !it.isWhitespace() && it !in items }.distinct()
        require(missing.isEmpty()) { "Slots 使用了没有 Items 定义的字符: ${missing.joinToString()}" }
        val layout = MenuLayout(
            name = name,
            title = sec.getString("Title", name) ?: name,
            page = sec.getInt("Page", 1),
            rows = rows,
            items = items
        )
        DebugUtil.log(
            "Registry",
            "    解析菜单 name=$name 标题=${layout.title} 行数=${rows.size} 容量=${layout.size} " +
                "图标定义=${items.keys.joinToString("") { it.toString() }}"
        )
        items.forEach { (c, def) ->
            DebugUtil.log("Registry", "      字符 '$c' -> type=${def.type} material=${def.material} require=${def.require} gem=${def.gem} gui=${def.gui}")
        }
        return layout
    }

    internal fun validateRows(rows: List<String>) {
        require(rows.size in 1..6) { "Slots 必须包含 1..6 行" }
        rows.forEachIndexed { index, row ->
            require(row.length == 9) { "Slots 第 ${index + 1} 行必须恰好为 9 个字符，当前为 ${row.length}" }
        }
    }

    /** Require 字段在配置里既可能写成单个字符串, 也可能写成列表, 这里做兼容处理 */
    private fun readStringOrList(sec: ConfigurationSection, path: String): List<String> {
        val list = sec.getStringList(path)
        if (list.isNotEmpty()) return list
        val single = sec.getString(path)
        return if (single.isNullOrBlank()) emptyList() else listOf(single)
    }

    fun get(name: String): MenuLayout? = menus[name]

    fun names(): Set<String> = menus.keys.toSet()
}
