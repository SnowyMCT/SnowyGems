package mc233.`fun`.snowygems.util

/**
 * 这个文件负责渲染定义在 yml 文件里的文本，可避免在 GUI 页面将其写死
 */
object GuiText {

    private const val PREFIX = "lang:"

    // 单行
    fun line(raw: String?, values: Map<String, String> = emptyMap()): String =
        lines(raw, values).firstOrNull() ?: ""

    // 多行，多用于列表
    fun lines(raw: String?, values: Map<String, String> = emptyMap()): List<String> {
        if (raw == null) return emptyList()
        val trimmed = raw.trim()
        if (trimmed.length > PREFIX.length &&
            trimmed.regionMatches(0, PREFIX, 0, PREFIX.length, ignoreCase = true)
        ) {
            val key = trimmed.substring(PREFIX.length).trim()
            if (key.isNotEmpty()) {
                // 交给 Lang: 它会做旧点号键名兼容、{占位符} 替换和颜色渲染
                val text = if (values.isEmpty()) Lang.getList(key) else Lang.getList(key, *args(values))
                if (text.isNotEmpty()) return text
                DebugUtil.log("Gui", "语言文件里没有节点 $key, 界面按原文显示 $PREFIX$key")
            }
        }
        return raw.split('\n').map { ColorUtil.colorize(fill(it, values)) }
    }

    private fun args(values: Map<String, String>): Array<out Pair<String, Any?>> =
        values.entries.map { it.key to (it.value as Any?) }.toTypedArray()

    // 占位符
    private fun fill(text: String, values: Map<String, String>): String {
        if (values.isEmpty() || '{' !in text) return text
        var result = text
        for ((key, value) in values) result = result.replace("{$key}", value)
        return result
    }
}
