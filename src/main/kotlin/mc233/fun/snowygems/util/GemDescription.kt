package mc233.`fun`.snowygems.util

import mc233.`fun`.snowygems.config.GemConfig
import mc233.`fun`.snowygems.reward.RewardPhase

object GemDescription {
    fun lines(gem: GemConfig): List<String> = buildList {
        add(Lang.get("catalog.scope", "scope" to gem.require.joinToString(" / ").ifBlank { Lang.get("common.any-item") }))
        if (gem.embed > 0) add(Lang.get("catalog.limit", "limit" to gem.embed))
        if (gem.exclusiveGroup.isNotBlank()) add(Lang.get("catalog.group", "group" to gem.exclusiveGroup))
        val rewards = gem.parsedRewards.filter { it.matchesPhase(RewardPhase.APPLY) }
        val removable = gem.trackApplied && rewards.isNotEmpty() && rewards.all { it.reward?.reversible == true }
        add(Lang.get(if (removable) "catalog.removable" else "catalog.consumable"))
        add(Lang.get("catalog.chance", "chance" to gem.success))
    }
}
