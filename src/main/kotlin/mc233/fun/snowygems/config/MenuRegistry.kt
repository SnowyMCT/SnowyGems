package mc233.`fun`.snowygems.config

import mc233.`fun`.snowygems.util.DebugUtil
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.releaseResourceFolder
import taboolib.common.platform.function.info
import taboolib.common.platform.function.severe
import taboolib.library.configuration.ConfigurationSection
import taboolib.module.configuration.Configuration
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object MenuRegistry {

    private val menus = ConcurrentHashMap<String, MenuLayout>()

    fun reload() {
        menus.clear()
        releaseResourceFolder("gui/", replace = false)
        val folder = File(getDataFolder(), "gui")
        val files = folder.listFiles { f -> f.isFile && f.extension.equals("yml", true) } ?: emptyArray()
        DebugUtil.log("Registry", "开始加载菜单配置, 目录=${folder.absolutePath} 发现 ${files.size} 个文件: ${files.joinToString { it.name }}")
        for (file in files) {
            try {
                val before = menus.size
                loadFile(file)
                DebugUtil.log("Registry", "  ${file.name} 加载了 ${menus.size - before} 个菜单")
            } catch (e: Exception) {
                severe("加载菜单配置文件失败: ${file.name} -> ${e.message}")
                DebugUtil.err("Registry", "加载菜单配置文件失败: ${file.name}", e)
            }
        }
        info("已加载 ${menus.size} 个菜单布局")
        DebugUtil.log("Registry", "菜单加载完毕, 名称=${menus.keys.sorted()}")
    }

    private fun loadFile(file: File) {
        val cfg = Configuration.loadFromFile(file)
        for (key in cfg.getKeys(false)) {
            val sec = cfg.getConfigurationSection(key) ?: continue
            menus[key] = parseMenu(key, sec)
        }
    }

    private fun parseMenu(name: String, sec: ConfigurationSection): MenuLayout {
        val rows = sec.getStringList("Slots")
        val itemsSec = sec.getConfigurationSection("Items")
        val items = LinkedHashMap<Char, MenuItemDef>()
        if (itemsSec != null) {
            for (key in itemsSec.getKeys(false)) {
                val c = key.firstOrNull() ?: continue
                val isec = itemsSec.getConfigurationSection(key) ?: continue
                items[c] = MenuItemDef(
                    char = c,
                    type = isec.getString("Type", "EMPTY") ?: "EMPTY",
                    material = isec.getString("Material"),
                    display = isec.getString("Display"),
                    glow = isec.getBoolean("Glow", false),
                    tips = isec.getStringList("Tips"),
                    require = readStringOrList(isec, "Require"),
                    amount = isec.getInt("Amount", 1),
                    texture = isec.getString("Texture"),
                    gem = isec.getString("Gem"),
                    gui = isec.getString("Gui")
                )
            }
        }
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

    /** Require 字段在配置里既可能写成单个字符串, 也可能写成列表, 这里做兼容处理 */
    private fun readStringOrList(sec: ConfigurationSection, path: String): List<String> {
        val list = sec.getStringList(path)
        if (list.isNotEmpty()) return list
        val single = sec.getString(path)
        return if (single.isNullOrBlank()) emptyList() else listOf(single)
    }

    fun get(name: String): MenuLayout? = menus[name]

    fun names(): Set<String> = menus.keys
}
