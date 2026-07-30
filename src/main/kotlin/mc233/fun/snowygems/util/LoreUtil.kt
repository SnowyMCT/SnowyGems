package mc233.`fun`.snowygems.util

/**
 * 所有函数操作的 lore 均假定已经是 §颜色代码 形式(见 ColorUtil.colorize).
 * 调用方需要保证传入的 locator/old/lore 参数已经过 colorize 处理.
 */
object LoreUtil {

    /** 查找第一个包含 needle 子串的行下标, 找不到返回 -1 */
    fun indexOfContains(lore: List<String>, needle: String): Int {
        if (needle.isEmpty()) return -1
        return lore.indexOfFirst { it.contains(needle) }
    }

    /** 统计 lore 中包含 needle 子串的行数 (用于 LoreAdd 的 limit 判断) */
    fun countContains(lore: List<String>, needle: String): Int {
        if (needle.isEmpty()) return 0
        return lore.count { it.contains(needle) }
    }

    /**
     * LoreAdd: 在 locator 附近插入一整行 [line], 或在 mode=line 时把 [line] 拼接到
     * locator 所在行末尾. 会遵守 limit(该内容已出现次数上限) 与 force(是否忽略 limit 强制插入).
     * 返回是否真正发生了修改.
     */
    fun add(lore: MutableList<String>, line: String, locator: String?, mode: String?, limit: Int, force: Boolean): Boolean {
        if (!force && limit in 0..countAppended(lore, line, locator)) return false
        return when (mode) {
            "line" -> {
                val idx = if (locator.isNullOrEmpty()) lore.size - 1 else indexOfContains(lore, locator)
                if (idx !in lore.indices) {
                    lore.add(line)
                } else {
                    lore[idx] = lore[idx] + line
                }
                true
            }
            "before" -> {
                val idx = if (locator.isNullOrEmpty()) 0 else indexOfContains(lore, locator)
                lore.add(if (idx >= 0) idx else 0, line)
                true
            }
            else -> { // "after" 或未指定
                val idx = if (locator.isNullOrEmpty()) lore.size - 1 else indexOfContains(lore, locator)
                lore.add(if (idx >= 0) idx + 1 else lore.size, line)
                true
            }
        }
    }

    private fun countAppended(lore: List<String>, line: String, locator: String?): Int {
        return if (locator.isNullOrEmpty()) countContains(lore, line)
        else lore.count { it.contains(line) }
    }

    /**
     * LoreReplace: 在 (可选) locator 之后的第一处出现 [old] 的地方, 替换为 [new].
     * 若 locator 为空, 则在整个 lore 中查找第一处出现 [old] 的整行或子串进行替换.
     * 若某行恰好等于 old, 则整行替换为 new; 否则做子串替换.
     */
    fun replace(lore: MutableList<String>, old: String, new: String, locator: String?): Boolean {
        val startIdx = if (locator.isNullOrEmpty()) 0 else {
            val i = indexOfContains(lore, locator)
            if (i < 0) return false else i
        }
        for (i in startIdx until lore.size) {
            val cur = lore[i]
            if (cur == old) {
                lore[i] = new
                return true
            }
            if (cur.contains(old)) {
                lore[i] = cur.replaceFirst(old, new)
                return true
            }
        }
        return false
    }

    /**
     * LoreVar: 查找/更新一条带数值的属性行. [prefix] 是不含数字的固定前缀文本(用于定位与展示),
     * [numeric] 是当前数值. 若已存在以 prefix 开头的行则原地更新数值, 否则按 mode 插入新行.
     */
    fun upsertVarLine(
        lore: MutableList<String>,
        prefix: String,
        rendered: String,
        locator: String?,
        mode: String?
    ) {
        val existingIdx = lore.indexOfFirst { it.startsWith(prefix) }
        if (existingIdx >= 0) {
            lore[existingIdx] = rendered
            return
        }
        when (mode) {
            "line" -> {
                val idx = if (locator.isNullOrEmpty()) -1 else indexOfContains(lore, locator)
                if (idx >= 0) lore[idx] = rendered else lore.add(rendered)
            }
            "last" -> lore.add(rendered)
            "before" -> {
                val idx = if (locator.isNullOrEmpty()) 0 else indexOfContains(lore, locator)
                lore.add(if (idx >= 0) idx else 0, rendered)
            }
            else -> { // after
                val idx = if (locator.isNullOrEmpty()) lore.size - 1 else indexOfContains(lore, locator)
                lore.add(if (idx >= 0) idx + 1 else lore.size, rendered)
            }
        }
    }

    fun removeLineStartingWith(lore: MutableList<String>, prefix: String): Boolean {
        val idx = lore.indexOfFirst { it.startsWith(prefix) }
        if (idx < 0) return false
        lore.removeAt(idx)
        return true
    }
}
