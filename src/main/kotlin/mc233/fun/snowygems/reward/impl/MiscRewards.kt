package mc233.`fun`.snowygems.reward.impl

import mc233.`fun`.snowygems.config.GemRegistry
import mc233.`fun`.snowygems.economy.MoneyEconomy
import mc233.`fun`.snowygems.economy.PointsEconomy
import mc233.`fun`.snowygems.reward.Reward
import mc233.`fun`.snowygems.reward.RewardContext
import mc233.`fun`.snowygems.reward.RewardTokenParser
import mc233.`fun`.snowygems.util.ColorUtil
import mc233.`fun`.snowygems.util.DebugUtil
import mc233.`fun`.snowygems.util.ExprUtil
import mc233.`fun`.snowygems.util.ItemFactory
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.meta.Damageable
import taboolib.module.nms.ItemTagData
import taboolib.module.nms.getItemTag

/** Enchant{name=DURABILITY;limit=7} 或 Enchant{name=RIPTIDE;level=0} */
class EnchantReward(private val name: String, private val level: Int?, private val limit: Int?) : Reward {
    override fun apply(ctx: RewardContext): Boolean {
        val item = ctx.item ?: run {
            DebugUtil.log("Reward", "    Enchant($name) 失败: 本次操作没有目标物品")
            return false
        }
        val enchant = resolveEnchant(name) ?: run {
            DebugUtil.log("Reward", "    Enchant 失败: 无法识别附魔名 $name (1.21 请用 unbreaking/sharpness 这类命名空间ID)")
            return false
        }
        val curLevel = item.getEnchantmentLevel(enchant)
        DebugUtil.log("Reward", "    Enchant(${enchant.key.key}): 当前等级=$curLevel 指定level=$level limit=$limit")
        if (level != null) {
            if (level <= 0) {
                if (curLevel <= 0) {
                    DebugUtil.log("Reward", "    Enchant: 物品本就没有该附魔, 无需移除, 视为未生效")
                    return false
                }
                item.removeEnchantment(enchant)
            } else {
                if (curLevel == level) {
                    DebugUtil.log("Reward", "    Enchant: 已经是目标等级 $level, 无变化, 视为未生效")
                    return false
                }
                item.addUnsafeEnchantment(enchant, level)
            }
        } else {
            // 未指定 level: 在当前基础上 +1, 但不超过 limit; 已达上限则不再消耗
            if (limit != null && curLevel >= limit) {
                DebugUtil.log("Reward", "    Enchant: 当前等级 $curLevel 已达上限 $limit, 无法继续提升, 视为未生效")
                return false
            }
            var next = curLevel + 1
            if (limit != null) next = next.coerceAtMost(limit)
            if (next == curLevel) {
                DebugUtil.log("Reward", "    Enchant: 计算后等级无变化($curLevel), 视为未生效")
                return false
            }
            item.addUnsafeEnchantment(enchant, next)
        }
        ctx.item = item
        return true
    }

    companion object {
        /**
         * 旧附魔名(1.13 之前的 Bukkit 常量 / 老配置写法) -> 现代命名空间键.
         * 1.20.5+ 起 org.bukkit.enchantments.Enchantment 换成了注册表, 旧的静态字段名
         * (DURABILITY / DAMAGE_ALL / ARROW_DAMAGE ...) 全部被移除, 反射 getField 会失败,
         * 而 minecraft(旧名.lowercase()) 又不是合法键, 于是解析全军覆没 -> 附魔无效果.
         * 这里补一张兼容表, 把老配置里出现过的旧名映射到新键.
         */
        private val LEGACY = mapOf(
            "DURABILITY" to "unbreaking",
            "DAMAGE_ALL" to "sharpness",
            "DAMAGE_UNDEAD" to "smite",
            "DAMAGE_ARTHROPODS" to "bane_of_arthropods",
            "ARROW_DAMAGE" to "power",
            "ARROW_KNOCKBACK" to "punch",
            "ARROW_FIRE" to "flame",
            "ARROW_INFINITE" to "infinity",
            "LOOT_BONUS_BLOCKS" to "fortune",
            "LOOT_BONUS_MOBS" to "looting",
            "DIG_SPEED" to "efficiency",
            "OXYGEN" to "respiration",
            "WATER_WORKER" to "aqua_affinity",
            "PROTECTION_ENVIRONMENTAL" to "protection",
            "PROTECTION_FIRE" to "fire_protection",
            "PROTECTION_FALL" to "feather_falling",
            "PROTECTION_EXPLOSIONS" to "blast_protection",
            "PROTECTION_PROJECTILE" to "projectile_protection",
            "LUCK" to "luck_of_the_sea",
            "LURE" to "lure",
            "SWEEPING_EDGE" to "sweeping_edge",
            "SWEEPING" to "sweeping_edge",
            "DURABILITY_MENDING" to "mending"
        )

        fun resolveEnchant(name: String): Enchantment? {
            val raw = name.trim()
            // 1) 直接当现代命名空间键解析 (sharpness / unbreaking / minecraft:sharpness)
            byKey(raw)?.let { return it }
            // 2) 旧名兼容表
            LEGACY[raw.uppercase()]?.let { mapped -> byKey(mapped)?.let { return it } }
            // 3) 兜底: 老 Bukkit 静态字段名反射(在仍保留旧字段的版本上还能命中)
            for (c in listOf(raw.uppercase(), raw.lowercase())) {
                try {
                    val f = Enchantment::class.java.getField(c)
                    (f.get(null) as? Enchantment)?.let { return it }
                } catch (ignored: Exception) {
                }
            }
            return null
        }

        private fun byKey(key: String): Enchantment? = try {
            val k = key.lowercase()
            val nsKey = if (k.contains(':')) {
                val (ns, path) = k.split(':', limit = 2)
                org.bukkit.NamespacedKey(ns, path)
            } else {
                org.bukkit.NamespacedKey.minecraft(k)
            }
            @Suppress("DEPRECATION")
            Enchantment.getByKey(nsKey)
        } catch (e: Exception) {
            null
        }
    }
}

/** ItemGive{gem=xxx} / ItemSet{Gem=xxx;Amount=n} 都是"给予一个宝石物品"的语义 */
class ItemGiveReward(private val gemId: String, private val amount: Int = 1) : Reward {
    override fun apply(ctx: RewardContext): Boolean {
        val player = ctx.player ?: return false
        val cfg = GemRegistry.get(gemId) ?: run {
            DebugUtil.log("Reward", "    ItemGive 引用的宝石配置不存在: $gemId (请检查配置文件里是否漏写了这个宝石的定义)")
            return false
        }
        val item = ItemFactory.build(cfg, amount)
        val leftover = player.inventory.addItem(item)
        leftover.values.forEach { player.world.dropItem(player.location, it) }
        DebugUtil.log("Reward", "    ItemGive: 给 ${player.name} 发放 $gemId x$amount, 掉落 ${leftover.values.sumOf { it.amount }} 个")
        return true
    }
}

/** ItemTake{GEM:xxx=amount} 从背包扣除指定数量的某宝石物品 */
class ItemTakeReward(private val gemId: String, private val amount: Int) : Reward {
    override fun apply(ctx: RewardContext): Boolean {
        val player = ctx.player ?: return false
        var remaining = amount
        val contents = player.inventory.contents
        for (i in contents.indices) {
            val stack = contents[i] ?: continue
            if (ItemFactory.getGemId(stack) != gemId) continue
            val take = minOf(remaining, stack.amount)
            stack.amount -= take
            remaining -= take
            if (stack.amount <= 0) contents[i] = null
            if (remaining <= 0) break
        }
        player.inventory.contents = contents
        if (remaining > 0) {
            DebugUtil.log("Reward", "    ItemTake 材料不足: 需要 $gemId x$amount, 还差 $remaining 个")
        } else {
            DebugUtil.log("Reward", "    ItemTake: 已从 ${player.name} 背包扣除 $gemId x$amount")
        }
        return remaining <= 0
    }
}

class PointReward(private val amountExpr: String) : Reward {
    override fun apply(ctx: RewardContext): Boolean {
        val player = ctx.player ?: return false
        val amount = ExprUtil.eval(amountExpr)
        DebugUtil.log("Reward", "    Point: 表达式 $amountExpr -> $amount 点券, 目标=${player.name}")
        PointsEconomy.add(player, amount)
        return true
    }
}

class MoneyReward(private val amountExpr: String) : Reward {
    override fun apply(ctx: RewardContext): Boolean {
        val player = ctx.player ?: return false
        val amount = ExprUtil.eval(amountExpr)
        val ok = MoneyEconomy.add(player, amount)
        DebugUtil.log("Reward", "    Money: 表达式 $amountExpr -> $amount 金币, 目标=${player.name} 结果=$ok")
        return ok
    }
}

class MaxHealthReward(private val amount: Double, private val limit: Double?) : Reward {
    override fun apply(ctx: RewardContext): Boolean {
        val player = ctx.player ?: return false
        val attribute = AttributeReward.resolve("health") ?: return false
        val inst = player.getAttribute(attribute) ?: return false
        var newBase = inst.baseValue + amount
        if (limit != null) newBase = newBase.coerceAtMost(limit)
        inst.baseValue = newBase
        return true
    }
}

class ExpLevelReward(private val amount: Int) : Reward {
    override fun apply(ctx: RewardContext): Boolean {
        val player = ctx.player ?: return false
        player.giveExpLevels(amount)
        return true
    }
}

class UnbreakableReward : Reward {
    override fun apply(ctx: RewardContext): Boolean {
        val item = ctx.item ?: return false
        val meta = item.itemMeta ?: return false
        meta.isUnbreakable = true
        item.itemMeta = meta
        ctx.item = item
        return true
    }
}

class DurabilityReward(private val amount: Int) : Reward {
    override fun apply(ctx: RewardContext): Boolean {
        val item = ctx.item ?: return false
        val meta = item.itemMeta as? Damageable ?: return false
        meta.damage = (meta.damage - amount).coerceAtLeast(0)
        item.itemMeta = meta as org.bukkit.inventory.meta.ItemMeta
        ctx.item = item
        return true
    }
}

/** ItemFlag{HIDE_ENCHANTS} 一次可加一个 flag(重复调用叠加多个) */
class ItemFlagReward(private val flagName: String) : Reward {
    override fun apply(ctx: RewardContext): Boolean {
        val item = ctx.item ?: return false
        val meta = item.itemMeta ?: return false
        val flag = try {
            ItemFlag.valueOf(flagName.uppercase())
        } catch (e: Exception) {
            DebugUtil.log("Reward", "    ItemFlag 失败: 无法识别的 flag 名 $flagName")
            return false
        }
        meta.addItemFlags(flag)
        item.itemMeta = meta
        ctx.item = item
        return true
    }
}

/**
 * SkillToNBT: 简化实现 —— 把 lore 中含有 "[技能]" / "[BUFF]" 标记的行整体隐藏/显示,
 * 真实内容以 NBT 列表形式暂存, 达到"技能隐藏粉尘"的效果.
 */
class SkillToNbtReward : Reward {
    override fun apply(ctx: RewardContext): Boolean {
        val item = ctx.item ?: return false
        val meta = item.itemMeta ?: return false
        val tag = item.getItemTag()
        val hiddenKey = "SnowyGemsHiddenSkillLore"
        val lore = (meta.lore ?: mutableListOf()).toMutableList()
        val hidden = tag[hiddenKey]
        if (hidden == null) {
            val toHide = lore.filter { it.contains("[技能]") || it.contains("[BUFF]") }
            if (toHide.isEmpty()) return true
            lore.removeAll(toHide)
            tag[hiddenKey] = ItemTagData(toHide.joinToString("\n"))
        } else {
            val restored = hidden.asString().split("\n").filter { it.isNotEmpty() }
            lore.addAll(restored)
            tag.remove(hiddenKey)
        }
        meta.lore = lore
        item.itemMeta = meta
        tag.saveTo(item)
        ctx.item = item
        return true
    }
}

/**
 * Conditional{condition="$LORE:标签:$==1";roman=true;reward=ItemSet{Gem=x;Amount=10}}
 * condition 语法: $LORE:<定位文本>:$<运算符><数值>, 从物品 lore 中定位到含该文本的行,
 * 提取其后紧跟的数字(或罗马数字, roman=true 时)进行比较.
 */
class ConditionalReward(private val condition: String, private val roman: Boolean, private val nested: String) : Reward {

    override fun apply(ctx: RewardContext): Boolean {
        val item = ctx.item
        val lore = item?.itemMeta?.lore ?: emptyList()
        val pass = evaluate(condition, lore)
        DebugUtil.log("Reward", "    Conditional: 条件 $condition (roman=$roman) 判定=$pass")
        if (!pass) return false
        val parsed = RewardTokenParser.parseLine(nested)
        val reward = RewardFactory.create(parsed.call) ?: run {
            DebugUtil.log("Reward", "    Conditional: 嵌套奖励 ${parsed.call.name} 无法识别")
            return false
        }
        DebugUtil.log("Reward", "    Conditional 条件成立, 执行嵌套奖励 ${parsed.call.name}")
        return reward.apply(ctx)
    }

    private fun evaluate(condition: String, lore: List<String>): Boolean {
        val m = Regex("""\${'$'}LORE:(.+?):\${'$'}([=!<>]+)(-?\d+(?:\.\d+)?)""").find(condition) ?: return false
        val (label, op, valueStr) = m.destructured
        val target = valueStr.toDoubleOrNull() ?: return false
        val coloredLabel = ColorUtil.colorize(label)
        val line = lore.firstOrNull { it.contains(coloredLabel) } ?: return false
        val current = if (roman) {
            val romanMatch = Regex("""[IVXLCDM]+\s*$""").find(line)
            romanMatch?.value?.let { romanToInt(it.trim()) }?.toDouble() ?: ExprUtil.extractNumber(line)
        } else {
            ExprUtil.extractNumber(line)
        }
        return when (op) {
            "==" -> current == target
            "!=" -> current != target
            ">=" -> current >= target
            "<=" -> current <= target
            ">" -> current > target
            "<" -> current < target
            else -> false
        }
    }

    private fun romanToInt(s: String): Int {
        val values = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
        var result = 0
        var prev = 0
        for (c in s.reversed()) {
            val v = values[c] ?: continue
            result += if (v < prev) -v else v
            prev = v
        }
        return result
    }
}

class EmptyReward : Reward {
    override fun apply(ctx: RewardContext): Boolean = true
}
