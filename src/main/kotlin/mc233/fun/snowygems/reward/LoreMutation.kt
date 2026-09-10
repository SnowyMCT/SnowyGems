package mc233.`fun`.snowygems.reward

/** 一条 Lore 奖励的局部变化；撤销时不覆盖其他插件或后续宝石编辑的整份 Lore。 */
object LoreMutation {
    fun capture(before: List<String>, after: List<String>): Map<String, String> {
        if (before == after) return emptyMap()
        val index = before.indices.firstOrNull { it >= after.size || before[it] != after[it] } ?: before.size
        if (after.size == before.size + 1 && before.take(index) + after[index] + before.drop(index) == after) {
            return mapOf("loreKind" to "insert", "loreIndex" to index.toString(), "loreAfter" to after[index])
        }
        if (after.size == before.size && before.indices.count { before[it] != after[it] } == 1) {
            return mapOf("loreKind" to "replace", "loreIndex" to index.toString(),
                "loreBefore" to before[index], "loreAfter" to after[index])
        }
        return emptyMap()
    }

    fun revert(lines: MutableList<String>, data: Map<String, String>): Boolean {
        val expected = data["loreAfter"] ?: return false
        val originalIndex = data["loreIndex"]?.toIntOrNull() ?: return false
        val index = if (originalIndex in lines.indices && lines[originalIndex] == expected) originalIndex
            else lines.indexOfLast { it == expected }
        if (index < 0) return false
        return when (data["loreKind"]) {
            "insert" -> { lines.removeAt(index); true }
            "replace" -> { lines[index] = data["loreBefore"] ?: return false; true }
            else -> false
        }
    }
}
