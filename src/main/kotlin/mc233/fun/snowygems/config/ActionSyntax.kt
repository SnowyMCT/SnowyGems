package mc233.`fun`.snowygems.config

import mc233.`fun`.snowygems.reward.FunctionCall
import mc233.`fun`.snowygems.reward.ParsedReward
import mc233.`fun`.snowygems.reward.RewardTokenParser
import mc233.`fun`.snowygems.skill.SkillLine
import mc233.`fun`.snowygems.skill.SkillLineParser
import taboolib.library.configuration.ConfigurationSection

/** Human readable YAML actions. String entries remain valid for existing installations. */
object ActionSyntax {
    fun entries(section: ConfigurationSection, key: String): List<Any> =
        (section.get(key) as? List<*>)?.filterNotNull() ?: emptyList()

    fun reward(value: Any): ParsedReward = when (value) {
        is String -> RewardTokenParser.parseLine(value)
        else -> {
            val map = fields(value)
            val action = take(map, "action") ?: error("奖励缺少 action")
            val child = takeValue(map, "then")
            val flags = linkedSetOf<String>()
            take(map, "phase")?.let { phase ->
                flags += when (phase.lowercase()) {
                    "apply", "success" -> "onSuccess"
                    "remove" -> "onRemove"
                    else -> error("无效奖励 phase: $phase")
                }
            }
            if (take(map, "ignorable")?.toBooleanStrictOrNull() == true) flags += "ignorable"
            if (child != null) {
                require(action.equals("Conditional", true)) { "只有 Conditional 奖励支持 then" }
                require(map.keys.none { it.equals("reward", true) }) { "不能同时设置 reward 与 then" }
                map["reward"] = renderReward(reward(child))
            }
            ParsedReward(FunctionCall(action, stringFields(map)), flags)
        }
    }

    /** Short input used by the GUI editor; the full legacy call is accepted for nested expressions. */
    fun editorReward(raw: String): ParsedReward {
        if ('{' in raw) return reward(raw)
        val name = raw.substringBefore(' ').trim()
        require(name.matches(Regex("[A-Za-z][A-Za-z0-9_]*"))) { "函数名无效" }
        val args = raw.substringAfter(' ', "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val map = linkedMapOf<String, Any>("action" to name)
        for (arg in args) {
            val pair = arg.split('=', limit = 2)
            require(pair.size == 2 && pair[0].matches(Regex("[A-Za-z][A-Za-z0-9_]*"))) {
                "格式：函数名 参数=值, 参数=值"
            }
            map[pair[0].trim()] = pair[1].trim()
        }
        return reward(map)
    }

    fun skill(value: Any): SkillLine = skill(value, 0)

    private fun skill(value: Any, depth: Int): SkillLine {
        require(depth <= 16) { "技能嵌套不能超过 16 层" }
        return when (value) {
            is String -> SkillLineParser.parse(value)
            else -> {
            val map = fields(value)
            val action = take(map, "action") ?: error("技能缺少 action")
            val onValue = takeValue(map, "trigger") ?: takeValue(map, "on")
            val on = when (onValue) {
                is List<*> -> onValue.filterNotNull().joinToString(",")
                null -> ""
                else -> onValue.toString()
            }
            val target = take(map, "target") ?: "Self"
            val children = takeValue(map, "then")
            val switchFrom = if (action.equals("Switch", true)) take(map, "from") else null
            val switchTo = if (action.equals("Switch", true)) take(map, "to") else null
            val triggers = on.split(',').map { it.trim().removePrefix("~") }.filter { it.isNotEmpty() }.toSet()
            val args = stringFields(map)
            if (switchFrom != null || switchTo != null) {
                require(!switchFrom.isNullOrBlank() && !switchTo.isNullOrBlank()) { "Switch 需要 from 和 to" }
                args["s"] = switchFrom
                args[switchTo] = switchTo
            }
            if (children != null) {
                val nested = when (children) {
                    is List<*> -> children.filterNotNull().map { skill(it, depth + 1) }
                    else -> listOf(skill(children, depth + 1))
                }
                require(nested.isNotEmpty()) { "then 不能为空" }
                if (action.equals("All", true) || action.equals("Group", true)) {
                    nested.forEach { child -> val raw = renderSkill(child); args[raw] = raw }
                } else {
                    val raw = if (nested.size == 1) renderSkill(nested.first())
                        else "All{${nested.joinToString(";") { renderSkill(it) }}}"
                    args[raw] = raw
                }
            }
            SkillLine(action, args, triggers, target.removePrefix("@"))
            }
        }
    }

    fun rewardMap(line: ParsedReward): Map<String, Any> = linkedMapOf<String, Any>("action" to line.call.name).apply {
        for ((key, value) in line.call.args) {
            if (line.call.name.equals("Conditional", true) && key.equals("reward", true) && looksLikeNestedSkill(value))
                put("then", rewardMap(reward(value)))
            else put(key, value)
        }
        if ("onRemove" in line.flags) put("phase", "remove")
        if ("ignorable" in line.flags) put("ignorable", true)
    }

    fun skillMap(line: SkillLine): Map<String, Any> = skillMap(line, 0)

    private fun skillMap(line: SkillLine, depth: Int): Map<String, Any> = linkedMapOf<String, Any>("action" to line.name).apply {
        require(depth <= 16) { "技能嵌套不能超过 16 层" }
        val switchMode = if (line.name.equals("Switch", true) && line.args["s"] != null)
            line.args.entries.firstOrNull { it.key != "s" && it.key == it.value && !looksLikeNestedSkill(it.key) }?.key else null
        if (switchMode != null) {
            put("from", line.args.getValue("s"))
            put("to", switchMode)
        }
        val children = mutableListOf<Map<String, Any>>()
        val control = line.name.lowercase() in setOf("all", "group", "chance", "random", "if", "condition", "delay", "repeat", "reward", "do", "rewardswitch")
        for ((key, value) in line.args) {
            if (switchMode != null && (key == "s" || key == switchMode)) continue
            if (key == value && (looksLikeNestedSkill(key) || control && key.matches(Regex("[A-Za-z][A-Za-z0-9_]*")))) {
                children += skillMap(SkillLineParser.parse(key), depth + 1)
            } else put(key, value)
        }
        if (children.isNotEmpty()) put("then", if (children.size == 1) children.first() else children)
        if (line.triggers.isNotEmpty()) put("trigger", line.triggers.joinToString(", "))
        if (!line.target.equals("Self", true)) put("target", line.target)
    }

    private fun looksLikeNestedSkill(raw: String): Boolean = raw.indexOf('{') > 0 && raw.endsWith('}')

    private fun renderSkill(line: SkillLine): String {
        val args = line.args.entries.joinToString(";") { (key, value) -> if (key == value) key else "$key=$value" }
        return line.name + (if (args.isEmpty()) "" else "{$args}") +
            (if (line.triggers.isEmpty()) "" else " " + line.triggers.joinToString(" ") { "~$it" }) +
            (if (line.target.equals("Self", true)) "" else " @${line.target}")
    }

    private fun renderReward(line: ParsedReward): String {
        val args = line.call.args.entries.joinToString(";") { (key, value) -> "$key=$value" }
        return line.call.name + (if (args.isEmpty()) "" else "{$args}")
    }

    private fun fields(value: Any): LinkedHashMap<String, Any> {
        val raw: Map<*, *> = when (value) {
            is Map<*, *> -> value
            is ConfigurationSection -> value.getKeys(false).associateWith { value.get(it) }
            else -> error("动作必须是文本或 YAML 对象")
        }
        return LinkedHashMap<String, Any>().apply {
            raw.forEach { (key, entry) -> if (key != null && entry != null) put(key.toString(), entry) }
        }
    }

    private fun take(map: MutableMap<String, Any>, key: String): String? {
        return takeValue(map, key)?.toString()
    }

    private fun takeValue(map: MutableMap<String, Any>, key: String): Any? {
        val actual = map.keys.firstOrNull { it.equals(key, true) } ?: return null
        return map.remove(actual)
    }

    private fun stringFields(map: Map<String, Any>): LinkedHashMap<String, String> =
        LinkedHashMap<String, String>().apply { map.forEach { (key, value) -> put(key, value.toString()) } }
}
