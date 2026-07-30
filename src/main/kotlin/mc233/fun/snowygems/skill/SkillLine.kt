package mc233.`fun`.snowygems.skill

import mc233.`fun`.snowygems.reward.RewardTokenParser
import mc233.`fun`.snowygems.util.ColorUtil
import mc233.`fun`.snowygems.util.ExprUtil
import org.bukkit.inventory.ItemStack

/** 一条被解析后的技能行, 例如: Blink{distance=120} ~onHit:EGG @Location */
data class SkillLine(val name: String, val args: LinkedHashMap<String, String>, val triggers: Set<String>, val target: String)

object SkillLineParser {

    fun parse(raw: String): SkillLine {
        val s = raw.trim()
        val braceStart = s.indexOf('{')
        val name: String
        var argsStr = ""
        var remainder = ""
        if (braceStart < 0) {
            val sp = s.indexOfFirst { it.isWhitespace() }
            if (sp < 0) name = s else { name = s.substring(0, sp); remainder = s.substring(sp) }
        } else {
            name = s.substring(0, braceStart).trim()
            val close = RewardTokenParser.findMatchingBrace(s, braceStart)
            if (close < 0) argsStr = s.substring(braceStart + 1)
            else { argsStr = s.substring(braceStart + 1, close); remainder = s.substring(close + 1) }
        }
        // ~onHit:MATERIAL 这种触发器可能带冒号后缀, 整体作为触发标记保留 (如 "onHit:EGG")
        val triggers = Regex("""~([\w:]+)""").findAll(remainder).map { it.groupValues[1] }.toSet()
        val target = Regex("""@(\w+)""").find(remainder)?.groupValues?.get(1) ?: "Self"
        val args = LinkedHashMap<String, String>()
        for (tok in splitTopLevel(argsStr)) {
            val t = tok.trim()
            if (t.isEmpty()) continue
            val eq = indexOfTopLevelEquals(t)
            if (eq < 0) args[t] = t else args[t.substring(0, eq).trim()] = t.substring(eq + 1).trim()
        }
        return SkillLine(name.trim(), args, triggers, target)
    }

    /** 触发标记是否命中(支持 onHit:MATERIAL 精确匹配, 以及不带材质的通用 onHit) */
    fun SkillLine.hasTrigger(trigger: String): Boolean = triggers.contains(trigger)

    private fun splitTopLevel(s: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        val cur = StringBuilder()
        for (c in s) {
            when (c) {
                '{' -> { depth++; cur.append(c) }
                '}' -> { depth--; cur.append(c) }
                ';' -> if (depth == 0) { out.add(cur.toString()); cur.clear() } else cur.append(c)
                else -> cur.append(c)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    private fun indexOfTopLevelEquals(s: String): Int {
        var depth = 0
        for (i in s.indices) {
            when (s[i]) {
                '{' -> depth++
                '}' -> depth--
                '=' -> if (depth == 0) return i
            }
        }
        return -1
    }
}

/** 解析形如 $LORE:标签?默认值$ 的动态数值引用, 从物品 lore 中读取当前数值 */
object SkillValue {
    private val LORE_PATTERN = Regex("""^\${'$'}LORE:(.+?)\?(.+?)\${'$'}$""")

    fun resolve(raw: String, item: ItemStack?): String {
        val m = LORE_PATTERN.find(raw.trim()) ?: return raw
        val (label, default) = m.destructured
        // 关键: lore 行里颜色码(§6§l...)是穿插在文字中间的, 例如
        //   §6§l[§c§lBUFF§6§l] §b§l生命提升 3
        // 而配置里的标签是纯文本 "[BUFF] 生命提升". 直接 contains 永远匹配不上,
        // 会退回默认值 -> 多等级药水永远读不到真实等级. 这里统一去色后再比较/提取.
        val needle = ColorUtil.stripColor(ColorUtil.colorize(label)).trim()
        val lore = item?.itemMeta?.lore ?: emptyList()
        val strippedLine = lore.map { ColorUtil.stripColor(it) }
            .firstOrNull { it.contains(needle) } ?: return default
        // 只从标签之后的片段取数字, 避免标签本身若含数字造成误读
        val idx = strippedLine.indexOf(needle)
        val after = if (idx >= 0) strippedLine.substring(idx + needle.length) else strippedLine
        if (!after.contains(Regex("""-?\d"""))) return default
        return ExprUtil.extractNumber(after).toString()
    }

    fun resolveDouble(raw: String, item: ItemStack?, def: Double = 0.0): Double =
        resolve(raw, item).toDoubleOrNull() ?: def

    fun resolveInt(raw: String, item: ItemStack?, def: Int = 0): Int =
        resolve(raw, item).toDoubleOrNull()?.toInt() ?: def
}
