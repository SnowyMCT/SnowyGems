package mc233.`fun`.snowygems.manager

import mc233.`fun`.snowygems.config.GemConfig
import mc233.`fun`.snowygems.reward.FunctionCall
import mc233.`fun`.snowygems.reward.RewardPhase
import mc233.`fun`.snowygems.reward.impl.LoreVarReward
import mc233.`fun`.snowygems.util.ExprUtil

/**
 * 旧物品补登记的**纯计算**部分: 给定装备上现有的 LoreVar 数值、已登记的宝石 ID、全部宝石配置,
 * 算出需要补哪些宝石、各补几条。不碰 Bukkit, 可以在普通单测里断言。
 *
 * 规则(刻意保守, 算不清就不补):
 *   - 只认"每用一颗 +1"的 LoreVar(var 表达式在 0 上求值为 1), 这样数值本身就是条数;
 *   - 同一个 NBT 键(多级药水共用一个前缀)只要有任一成员已被登记, 就整体跳过, 避免重复补;
 *   - 一个键只补一个宝石 ID: 取同前缀成员里 id 最小的那个, 保证结果稳定可复现。
 */
internal fun planAppliedRepair(
    loreVarValues: Map<String, Double>,
    existing: List<String>,
    candidates: List<GemConfig>
): Map<String, Int> {
    if (loreVarValues.isEmpty() || candidates.isEmpty()) return emptyMap()
    val callsByGem: Map<GemConfig, List<FunctionCall>> = candidates.associateWith { gem ->
        gem.parsedRewards
            .filter { it.matchesPhase(RewardPhase.APPLY) }
            .map { it.call }
            .filter { it.name.equals("LoreVar", true) }
    }
    fun sharesKey(gem: GemConfig, key: String) = callsByGem[gem].orEmpty()
        .any { LoreVarReward.keyOf(it.arg("lore")) == key }

    val out = LinkedHashMap<String, Int>()
    for ((key, value) in loreVarValues) {
        if (!value.isFinite() || value < 1.0) continue
        val needed = value.toInt()
        if (needed <= 0) continue
        val family = candidates.filter { sharesKey(it, key) }
        if (family.isEmpty()) continue
        // 这一颗"键"的账面上已经有登记了: 不猜, 整体跳过
        if (family.any { gem -> existing.count { it == gem.id } > 0 }) continue
        // 只补"一颗 +1"的成员, 它的数值 == 条数, 拆卸时按配置退一颗就是对得上的
        val canonical = family
            .filter { gem ->
                callsByGem[gem].orEmpty().any {
                    LoreVarReward.keyOf(it.arg("lore")) == key &&
                        ExprUtil.eval(it.arg("var", "v"), 0.0) == 1.0
                }
            }
            .minByOrNull { it.id } ?: continue
        out[canonical.id] = needed
    }
    return out
}
