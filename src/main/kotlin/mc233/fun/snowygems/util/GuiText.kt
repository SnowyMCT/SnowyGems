package mc233.`fun`.snowygems.util

/**
 * gui 目录下的 yml 界面文本渲染.
 *
 * 约定(与 dis.yml 一致):
 *   - 值写成 `lang:键名` 时改用 lang/ 语言文件里的文本, 界面随 config.yml 的 Lang 切换语言
 *   - 直接写文本(例如 Display: '&b装备槽')时按配置原文渲染, 并替换 {占位符}
 *   - 两种写法并存, 老配置不需要改就能继续用
 *
 * 由 DismantleGui / WorkbenchMenu(菜单系统) / EditorTheme(游戏内编辑器) 共用.
 */
object GuiText {

    /** 指向语言文件的写法前缀: `lang:dismantle-gui-title` */
    const val LANG_PREFIX = "lang:"

    /** `lang:键名` -> 键名; 不是引用(或键名为空)时返回 null */
    fun langKeyOf(raw: String?): String? {
        val text = raw?.trim() ?: return null
        if (!text.startsWith(LANG_PREFIX, ignoreCase = true)) return null
        return text.substring(LANG_PREFIX.length).trim().ifBlank { null }
    }

    /** 单行文本: 引用取语言文件里的文本, 否则用配置原文并替换占位符 */
    fun line(raw: String?, values: Map<String, String>): String =
        lines(raw, values).firstOrNull() ?: ""

    /** 多行文本: 引用指向列表节点时展开成多行(给 Tips 用), 否则按单行处理 */
    fun lines(raw: String?, values: Map<String, String>): List<String> {
        val key = langKeyOf(raw)
        if (key != null) {
            val args = values.map { it.key to it.value as Any? }.toTypedArray()
            return Lang.getList(key, *args)
        }
        val text = raw ?: return emptyList()
        var result = text
        for ((k, v) in values) result = result.replace("{$k}", v)
        return listOf(ColorUtil.colorize(result))
    }
}
