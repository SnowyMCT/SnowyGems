package mc233.`fun`.snowygems.reward

import mc233.`fun`.snowygems.config.GemConfig
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

enum class RewardPhase { APPLY, REMOVE, FAIL }

/**
 * 一次奖励执行所需的上下文. [item] 是被作用的目标装备(强化/镶嵌类奖励), 可能为 null(纯玩家类奖励,
 * 如点券/金币兑换券). 修改 item 的 ItemMeta 需要调用方自行 setItemMeta 后写回背包.
 */
class RewardContext(
    val player: Player?,
    var item: ItemStack?,
    val gem: GemConfig,
    val phase: RewardPhase,
    val success: Boolean
)

interface Reward {
    fun apply(ctx: RewardContext): Boolean
}

class ParsedReward(val call: FunctionCall, val flags: Set<String>) {

    fun matchesPhase(phase: RewardPhase): Boolean {
        return when {
            flags.contains("onRemove") -> phase == RewardPhase.REMOVE
            flags.contains("onSuccess") -> phase == RewardPhase.APPLY
            else -> phase == RewardPhase.APPLY
        }
    }

    val ignorable get() = flags.contains("ignorable")
}

class FunctionCall(val name: String, val args: LinkedHashMap<String, String>) {
    fun arg(key: String, def: String = ""): String = args[key] ?: def
    fun argOrNull(key: String): String? = args[key]
}

object RewardTokenParser {

    fun parseLine(raw: String): ParsedReward {
        var s = raw.trim()
        // 去除整行包裹的引号 "xxx"
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length - 1)
        }
        val braceStart = s.indexOf('{')
        val name: String
        var argsStr = ""
        var remainder = ""
        if (braceStart < 0) {
            // 没有花括号, 可能是 "Unbreakable" 或 "Unbreakable $onSuccess"
            val spaceIdx = s.indexOfFirst { it.isWhitespace() }
            if (spaceIdx < 0) {
                name = s
            } else {
                name = s.substring(0, spaceIdx)
                remainder = s.substring(spaceIdx)
            }
        } else {
            name = s.substring(0, braceStart).trim()
            val closeIdx = findMatchingBrace(s, braceStart)
            if (closeIdx < 0) {
                argsStr = s.substring(braceStart + 1)
            } else {
                argsStr = s.substring(braceStart + 1, closeIdx)
                remainder = s.substring(closeIdx + 1)
            }
        }
        val flags = Regex("""\$([A-Za-z]+)""").findAll(remainder).map { it.groupValues[1] }.toSet()
        val args = parseArgs(argsStr)
        return ParsedReward(FunctionCall(name.trim(), args), flags)
    }

    /** 找到与 openIndex 处 '{' 匹配的 '}' 下标, 找不到返回 -1 */
    fun findMatchingBrace(s: String, openIndex: Int): Int {
        var depth = 0
        for (i in openIndex until s.length) {
            when (s[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    private fun parseArgs(argsStr: String): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        if (argsStr.isBlank()) return result
        val tokens = splitTopLevel(argsStr, ';')
        for (token in tokens) {
            val t = token.trim()
            if (t.isEmpty()) continue
            val eqIdx = indexOfTopLevelEquals(t)
            if (eqIdx < 0) {
                // 没有 '=' 的裸参数(如 ItemFlag{HIDE_ENCHANTS}), 用参数名本身当 key/value
                result[t] = t
            } else {
                val k = t.substring(0, eqIdx).trim()
                var v = t.substring(eqIdx + 1).trim()
                if (v.length >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                    v = v.substring(1, v.length - 1)
                }
                result[k] = v
            }
        }
        return result
    }

    private fun splitTopLevel(s: String, delim: Char): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var inQuote = false
        val cur = StringBuilder()
        for (c in s) {
            when {
                c == '"' -> { inQuote = !inQuote; cur.append(c) }
                c == '{' && !inQuote -> { depth++; cur.append(c) }
                c == '}' && !inQuote -> { depth--; cur.append(c) }
                c == delim && depth == 0 && !inQuote -> { out.add(cur.toString()); cur.clear() }
                else -> cur.append(c)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    private fun indexOfTopLevelEquals(s: String): Int {
        var depth = 0
        var inQuote = false
        for (i in s.indices) {
            val c = s[i]
            when {
                c == '"' -> inQuote = !inQuote
                c == '{' && !inQuote -> depth++
                c == '}' && !inQuote -> depth--
                c == '=' && depth == 0 && !inQuote -> return i
            }
        }
        return -1
    }
}
