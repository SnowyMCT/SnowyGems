package mc233.`fun`.snowygems.rune

data class RuneRecipe(
    val id: String,
    val display: String,
    val result: String,
    val amount: Int,
    val ingredients: Map<String, Int>
) {
    init {
        require(id.isNotBlank() && result.isNotBlank()) { "配方 ID 与产物 ID 不能为空" }
        require(amount in 1..4096) { "产物数量须为 1..4096 的整数" }
        require(ingredients.isNotEmpty()) { "配方不能没有材料" }
        require(ingredients.all { it.key.isNotBlank() && it.value in 1..4096 }) { "材料数量须为 1..4096 的整数" }
    }
}

/** 只描述格子的材料身份和与产物的相容性，不依赖 Bukkit，也不修改玩家背包。 */
data class CraftSlot(val gem: String?, val amount: Int, val acceptsOutput: Boolean = false)
data class CraftPlan(val remaining: List<Int>, val added: List<Int>)
data class CraftCheck(val plan: CraftPlan?, val missing: Map<String, Int> = emptyMap(), val noSpace: Boolean = false)

object RuneCraftPlanner {
    fun plan(slots: List<CraftSlot>, recipe: RuneRecipe, outputLimit: Int): CraftCheck {
        require(outputLimit > 0)
        require(slots.all { it.amount >= 0 })
        val missing = recipe.ingredients.mapNotNull { (id, needed) ->
            val available = slots.filter { it.gem == id }.sumOf { it.amount.toLong() }
            (needed.toLong() - available).takeIf { it > 0 }?.let { id to it.toInt() }
        }.toMap()
        if (missing.isNotEmpty()) return CraftCheck(null, missing)
        val remaining = slots.map { it.amount }.toMutableList()
        for ((id, needed) in recipe.ingredients) {
            var take = needed
            // 优先耗尽小堆叠，使满背包时尽可能腾出产物格。
            for (i in slots.indices.filter { slots[it].gem == id }.sortedBy { remaining[it] }) {
                val count = minOf(remaining[i], take)
                remaining[i] -= count
                take -= count
                if (take == 0) break
            }
        }
        val added = MutableList(slots.size) { 0 }
        var output = recipe.amount
        // 优先合并相同产物，再利用扣材料之后空出来的格子。
        val targets = slots.indices.filter { remaining[it] > 0 && slots[it].acceptsOutput } +
            slots.indices.filter { remaining[it] == 0 }
        for (i in targets) {
            val count = minOf(output, (outputLimit - remaining[i]).coerceAtLeast(0))
            added[i] = count
            output -= count
            if (output == 0) break
        }
        return if (output > 0) CraftCheck(null, noSpace = true)
        else CraftCheck(CraftPlan(remaining, added))
    }
}
