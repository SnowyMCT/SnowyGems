package mc233.`fun`.snowygems.reward.impl

import mc233.`fun`.snowygems.compat.EnchantAliases
import mc233.`fun`.snowygems.compat.Registries
import mc233.`fun`.snowygems.compat.ServerVersion
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
import mc233.`fun`.snowygems.util.ItemTagData
import mc233.`fun`.snowygems.util.getItemTag

/** Enchant{name=DURABILITY;limit=7} 或 Enchant{name=RIPTIDE;level=0} */
class EnchantReward(private val name: String, private val level: Int?, private val limit: Int?) : Reward {
    override fun apply(ctx: RewardContext): Boolean {
        val item = ctx.item ?: run {
            DebugUtil.log("Reward", "    Enchant($name) 失败: 本次操作没有目标物品")
            return false
        }
        val enchant = resolveEnchant(name) ?: run {
            // 区分两种失败: 认识这个旧名但当前版本没有 vs 名字压根不认识
            val mapped = EnchantAliases.keyOf(name)
            DebugUtil.log(
                "Reward",
                "    Enchant 跳过: 附魔 $name " + if (mapped != null) {
                    "(现代键=$mapped) 在当前版本 ${ServerVersion.minecraftVersion} 的注册表中不存在"
                } else {
                    "不在当前版本的注册表中. 请用现代命名空间ID" +
                        "(sharpness/unbreaking/density/breach/wind_burst), 或确认它需要更高版本的服务端"
                }
            )
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
        ctx.undoData["enchantDelta"] = (item.getEnchantmentLevel(enchant) - curLevel).toString()
        ctx.item = item
        return true
    }

    /** 拆卸撤销: 未指定 level 的累加式附魔按"降 1 级"处理; 指定了 level 的直接移除该附魔 */
    override fun revert(ctx: RewardContext): Boolean {
        val item = ctx.item ?: return false
        val enchant = resolveEnchant(name) ?: error("Cannot resolve enchantment for removal: $name")
        val curLevel = item.getEnchantmentLevel(enchant)
        val delta = ctx.undoData["enchantDelta"]?.toIntOrNull()
        if (delta != null) {
            val restored = curLevel.toLong() - delta
            // 后续定级/清除附魔可能覆盖了本次贡献；不能截成 0 后返还宝石，
            // 否则以后撤销那次清除会凭空恢复额外等级。请先拆较新的覆盖型宝石。
            require(restored in 0..Int.MAX_VALUE.toLong()) { "Remove newer enchantment changes first" }
            val next = restored.toInt()
            if (next <= 0) item.removeEnchantment(enchant) else item.addUnsafeEnchantment(enchant, next)
            ctx.item = item
            return next != curLevel
        }
        if (curLevel <= 0) return false
        if (level != null && level > 0) {
            // 定级附魔: 整体移除
            item.removeEnchantment(enchant)
            DebugUtil.log("Reward", "    Enchant(${enchant.key.key}) 撤销: 移除定级附魔(原 $curLevel)")
        } else {
            val next = curLevel - 1
            if (next <= 0) item.removeEnchantment(enchant)
            else item.addUnsafeEnchantment(enchant, next)
            DebugUtil.log("Reward", "    Enchant(${enchant.key.key}) 撤销: $curLevel -> ${next.coerceAtLeast(0)}")
        }
        ctx.item = item
        return true
    }

    companion object {
        /**
         * 解析附魔名, 全权交给 [Registries.enchantment]:
         *   - 现代命名空间键(sharpness / density / breach / wind_burst / minecraft:xxx)
         *   - 1.13 之前的旧 Bukkit 常量名(DURABILITY / DAMAGE_ALL …, 见 EnchantAliases)
         *   - 服主用数据包自定义的附魔(它们同样在注册表里)
         *
         * 多版本要点: 不写死任何附魔清单1.21 的锤附魔(密度/穿透/风爆)、以及后续版本
         * 新增的附魔, 只要服务端注册表里有就能用; 老版本上写了新附魔会跳过并提示, 不会连锁失败
         */
        fun resolveEnchant(name: String): Enchantment? = Registries.enchantment(name)
    }
}

/** ItemGive{gem=xxx} / ItemSet{Gem=xxx;Amount=n} 都是"给予一个宝石物品"的语义 */
class ItemGiveReward(private val gemId: String, private val amount: Int = 1) : Reward {
    override fun apply(ctx: RewardContext): Boolean {
        val player = ctx.player ?: return false
        if (amount <= 0) return false
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
class ItemTakeReward(val gemId: String, val amount: Int) : Reward {
    override fun apply(ctx: RewardContext): Boolean {
        val player = ctx.player ?: return false
        if (amount <= 0) return false
        var remaining = amount
        val contents = player.inventory.storageContents
        val available = contents.filterNotNull().filter { ItemFactory.getGemId(it) == gemId }.sumOf { it.amount.toLong() }
        if (available < amount) {
            DebugUtil.log("Reward", "    ItemTake 材料不足: 需要 $gemId x$amount, 现有 $available，未扣除")
            return false
        }
        for (i in contents.indices) {
            val stack = contents[i]?.clone() ?: continue
            if (ItemFactory.getGemId(stack) != gemId) continue
            val take = minOf(remaining, stack.amount)
            stack.amount -= take
            remaining -= take
            contents[i] = stack.takeIf { it.amount > 0 }
            if (remaining <= 0) break
        }
        player.inventory.storageContents = contents
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
        if (!PointsEconomy.tryAdd(player, amount)) return false
        // 主动提示玩家获得了多少(取整展示, 因为点券是整数量级)
        runCatching { mc233.`fun`.snowygems.util.Lang.send(
            player, "reward.point-gain",
            "amount" to if (amount == amount.toLong().toDouble()) amount.toLong().toString() else amount.toString()
        ) }
        return true
    }
}

class MoneyReward(private val amountExpr: String) : Reward {
    override fun apply(ctx: RewardContext): Boolean {
        val player = ctx.player ?: return false
        val amount = ExprUtil.eval(amountExpr)
        val ok = MoneyEconomy.add(player, amount)
        DebugUtil.log("Reward", "    Money: 表达式 $amountExpr -> $amount 金币, 目标=${player.name} 结果=$ok")
        if (ok) {
            // 金币可能有小数, 整数时不显示小数点
            val shown = if (amount == amount.toLong().toDouble()) amount.toLong().toString() else "%.2f".format(amount)
            runCatching { mc233.`fun`.snowygems.util.Lang.send(player, "reward.money-gain", "amount" to shown) }
        }
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
        if (!newBase.isFinite() || newBase <= 0 || newBase == inst.baseValue ||
            (amount > 0 && newBase < inst.baseValue)) return false
        inst.baseValue = newBase
        return true
    }
}

class ExpLevelReward(private val amount: Int) : Reward {
    override fun apply(ctx: RewardContext): Boolean {
        val player = ctx.player ?: return false
        if (amount == 0 || player.level.toLong() + amount !in 0..Int.MAX_VALUE.toLong()) return false
        player.giveExpLevels(amount)
        return true
    }
}

class UnbreakableReward : Reward {
    override fun apply(ctx: RewardContext): Boolean {
        val item = ctx.item ?: return false
        val meta = item.itemMeta ?: return false
        if (meta.isUnbreakable) return false
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
        val updated = (meta.damage.toLong() - amount).coerceIn(0, item.type.maxDurability.toLong()).toInt()
        if (updated == meta.damage) return false
        meta.damage = updated
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
        if (meta.hasItemFlag(flag)) return false
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
            if (toHide.isEmpty()) return false
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

    private val parsed by lazy { RewardTokenParser.parseLine(nested) }
    private val nestedReward by lazy { RewardFactory.create(parsed.call) }

    override fun apply(ctx: RewardContext): Boolean {
        val item = ctx.item
        val lore = item?.itemMeta?.lore ?: emptyList()
        val pass = evaluate(condition, lore)
        DebugUtil.log("Reward", "    Conditional: 条件 $condition (roman=$roman) 判定=$pass")
        if (!pass) return false
        val reward = nestedReward ?: run {
            DebugUtil.log("Reward", "    Conditional: 嵌套奖励 ${parsed.call.name} 无法识别")
            return false
        }
        DebugUtil.log("Reward", "    Conditional 条件成立, 执行嵌套奖励 ${parsed.call.name}")
        return reward.apply(ctx)
    }

    override fun revert(ctx: RewardContext): Boolean = nestedReward?.revert(ctx) ?: false

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
