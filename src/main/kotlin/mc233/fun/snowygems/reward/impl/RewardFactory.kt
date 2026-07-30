package mc233.`fun`.snowygems.reward.impl

import mc233.`fun`.snowygems.reward.FunctionCall
import mc233.`fun`.snowygems.reward.Reward

object RewardFactory {

    fun create(call: FunctionCall): Reward? {
        return when (call.name.trim().lowercase().replace("_", "")) {
            "attribute" -> AttributeReward(
                attrName = call.arg("name"),
                operation = call.arg("operation", "0").toIntOrNull() ?: 0,
                slot = call.arg("slot", "auto"),
                varExpr = call.arg("var", "v"),
                limit = call.argOrNull("limit")?.toDoubleOrNull()
            )
            "loreadd" -> LoreAddReward(
                lore = call.arg("lore"),
                locator = call.argOrNull("locator"),
                mode = call.argOrNull("mode"),
                limit = call.argOrNull("limit")?.toIntOrNull() ?: Int.MAX_VALUE,
                force = call.arg("force", "false").toBoolean()
            )
            "lorereplace" -> LoreReplaceReward(
                old = call.arg("old"),
                new = call.argOrNull("new") ?: call.arg("lore"),
                locator = call.argOrNull("locator")
            )
            "lorevar" -> LoreVarReward(
                prefix = call.arg("lore"),
                locator = call.argOrNull("locator"),
                mode = call.argOrNull("mode"),
                varExpr = call.arg("var", "v"),
                invExpr = call.argOrNull("inv"),
                format = call.arg("format", "%.2f")
            )
            "name" -> NameReward(call.arg("name"))
            "enchant" -> EnchantReward(
                name = call.arg("name"),
                level = call.argOrNull("level")?.toIntOrNull(),
                limit = call.argOrNull("limit")?.toIntOrNull()
            )
            "itemgive" -> ItemGiveReward(
                gemId = call.argOrNull("gem") ?: call.argOrNull("Gem") ?: return null,
                amount = call.argOrNull("amount")?.toIntOrNull() ?: call.argOrNull("Amount")?.toIntOrNull() ?: 1
            )
            "itemset" -> ItemGiveReward(
                gemId = call.argOrNull("Gem") ?: call.argOrNull("gem") ?: return null,
                amount = call.argOrNull("Amount")?.toIntOrNull() ?: call.argOrNull("amount")?.toIntOrNull() ?: 1
            )
            "itemtake" -> {
                val entry = call.args.entries.firstOrNull { it.key.startsWith("GEM:") } ?: return null
                ItemTakeReward(gemId = entry.key.removePrefix("GEM:"), amount = entry.value.toIntOrNull() ?: 1)
            }
            "point" -> PointReward(call.arg("amount", "0"))
            "money" -> MoneyReward(call.arg("amount", "0"))
            "maxhealth" -> MaxHealthReward(
                amount = call.arg("amount", "0").toDoubleOrNull() ?: 0.0,
                limit = call.argOrNull("limit")?.toDoubleOrNull()
            )
            "explevel" -> ExpLevelReward(call.arg("amount", "0").toIntOrNull() ?: 0)
            "unbreakable" -> UnbreakableReward()
            "durability" -> DurabilityReward(call.arg("amount", "0").toIntOrNull() ?: 0)
            "itemflag" -> {
                val flag = call.args.keys.firstOrNull() ?: return null
                ItemFlagReward(flag)
            }
            "skilltonbt" -> SkillToNbtReward()
            "conditional" -> ConditionalReward(
                condition = call.arg("condition"),
                roman = call.arg("roman", "false").toBoolean(),
                nested = call.argOrNull("reward") ?: return null
            )
            "empty" -> EmptyReward()
            else -> null
        }
    }
}
