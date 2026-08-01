package mc233.`fun`.snowygems.skill.functions

import mc233.`fun`.snowygems.SnowyGems
import mc233.`fun`.snowygems.compat.Registries
import mc233.`fun`.snowygems.config.SkillRegistry
import mc233.`fun`.snowygems.skill.SkillContext
import mc233.`fun`.snowygems.skill.SkillExecutor
import mc233.`fun`.snowygems.skill.SkillFunctions
import mc233.`fun`.snowygems.skill.SkillLine
import mc233.`fun`.snowygems.skill.SkillLineParser
import mc233.`fun`.snowygems.skill.skillFunction
import mc233.`fun`.snowygems.util.Lang
import org.bukkit.NamespacedKey
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType

/**
 * 物品操作类技能函数: 附魔增删、形态切换、耐久
 *
 * ## Switch 的通用化
 *
 * 服主写的形态切换(精准↔时运、忠诚↔激流、精修↔无限)本质上是同一个模式:
 *   - 两个形态各自是一个技能定义, 用 RewardSwitch{Enchant{...}} 声明"进入这个形态要做什么"
 *   - 切换时关掉当前形态的附魔, 打开目标形态的附魔
 *
 * 老实现判断"当前处于哪个形态"时硬编码了两个附魔常量, 所以只有第一组能正确判断首次
 * 切换方向, 其它组第一次点会切反
 *
 * 现在改成**从配置反推**: 读取两个形态各自声明的特征附魔, 谁的附魔在物品上就说明当前
 * 处于谁的形态服主新写任何一组切换都自动正确, 引擎不需要知道具体附魔名
 */
object ItemFunctions {

    private const val SWITCH_KEY = "switch_mode"
    private const val ENCH_PREFIX = "ench_"

    private val MODE_KEYS = setOf("s", "a", "mode1")

    private val TIP_FUNCTIONS = setOf("chat", "actionbar", "title", "message", "msg", "消息", "动作栏", "标题")

    private fun key(path: String) = NamespacedKey(SnowyGems.plugin, path)

    fun registerAll() {
        SkillFunctions.register(skillFunction(
            name = "Enchant",
            aliases = listOf("附魔"),
            description = "增删附魔. level=0 移除并记住原等级, level=restore 恢复记住的等级, 不写 level 则 +1",
            usage = "{name=sharpness;level=5}  或 {name=fortune;limit=3}"
        ) { ctx -> enchant(ctx) })

        SkillFunctions.register(skillFunction(
            name = "Switch",
            aliases = listOf("形态切换", "切换"),
            description = "在两个形态之间切换. 形态名即技能定义名, 引擎自动从配置反推当前形态",
            usage = "{s=精准;时运}  两个形态必须各自是一个技能定义"
        ) { ctx -> switchMode(ctx) })

        SkillFunctions.register(skillFunction(
            name = "Repair",
            aliases = listOf("修复", "耐久"),
            description = "修复物品耐久",
            usage = "{amount=100}  amount=full 则完全修复"
        ) { ctx -> repair(ctx) })
    }

    // ── 附魔 ────────────────────────────────────────────────

    /**
     * 应用一次附魔变更. 四种语义:
     *   level=具体数字  设为该等级
     *   level=0        移除, 并把原等级记进 PDC(供 restore 用)
     *   level=restore  恢复之前记住的等级
     *   不写 level      当前等级 +1, 受 limit 限制
     */
    private fun enchant(ctx: SkillContext): Boolean {
        val rawName = ctx.str("name", "n") ?: return false
        val enchant = Registries.enchantment(rawName) ?: run {
            ctx.skipUnsupported("附魔 $rawName", "该附魔可能需要更高版本, 或名称拼写有误")
            return false
        }
        val item = ctx.item
        val meta = item.itemMeta ?: return false
        val current = meta.getEnchantLevel(enchant)
        val pdcKey = key(ENCH_PREFIX + enchant.key.key)
        val levelArg = ctx.str("level", "lv")
        val name = enchant.key.key

        val changed = when {
            levelArg == null -> {
                // 未指定等级: 在当前基础上 +1, 不超过 limit
                val limit = ctx.int("limit")
                val next = (current + 1).let { if (limit != null) it.coerceAtMost(limit) else it }
                if (next == current) {
                    ctx.log("$name 当前 $current 级已达上限 $limit")
                    false
                } else {
                    meta.addEnchant(enchant, next, true)
                    ctx.log("附魔 $name 等级 +1 -> $next")
                    true
                }
            }
            levelArg.equals("restore", true) -> {
                val saved = meta.persistentDataContainer.get(pdcKey, PersistentDataType.INTEGER)
                    ?.coerceAtLeast(1) ?: 1
                meta.addEnchant(enchant, saved, true)
                ctx.log("恢复附魔 $name 等级=$saved")
                true
            }
            else -> {
                val level = levelArg.toIntOrNull() ?: run {
                    ctx.log("level=$levelArg 既不是数值也不是 restore")
                    return false
                }
                when {
                    level > 0 && current == level -> {
                        ctx.log("$name 已经是 $level 级, 无变化")
                        false
                    }
                    level > 0 -> {
                        meta.addEnchant(enchant, level, true)
                        ctx.log("设置附魔 $name 等级=$level")
                        true
                    }
                    current <= 0 -> {
                        ctx.log("物品本就没有 $name, 无需移除")
                        false
                    }
                    else -> {
                        // 记住原等级, 之后 restore 才能恢复
                        meta.persistentDataContainer.set(pdcKey, PersistentDataType.INTEGER, current)
                        meta.removeEnchant(enchant)
                        ctx.log("移除附魔 $name (已记住等级=$current)")
                        true
                    }
                }
            }
        }
        if (changed) item.itemMeta = meta
        return changed
    }

    // ── 耐久 ────────────────────────────────────────────────

    private fun repair(ctx: SkillContext): Boolean {
        val item = ctx.item
        val meta = item.itemMeta as? Damageable ?: return false
        if (meta.damage <= 0) {
            ctx.log("物品耐久已满, 无需修复")
            return false
        }
        val full = ctx.str("amount", "a")?.equals("full", true) == true
        val newDamage = if (full) 0 else (meta.damage - ctx.intOr(0, "amount", "a")).coerceAtLeast(0)
        ctx.log("耐久损伤 ${meta.damage} -> $newDamage")
        meta.damage = newDamage
        item.itemMeta = meta as ItemMeta
        return true
    }

    // ── 形态切换 ────────────────────────────────────────────

    /**
     * 两形态切换. 形态名的两种写法都支持:
     *   Switch{s=精准;时运}       第二个是裸参数
     *   Switch{s=精准;mode=时运}  第二个带键名
     */
    private fun switchMode(ctx: SkillContext): Boolean {
        val modeA = ctx.str(*MODE_KEYS.toTypedArray()) ?: return false
        val modeB = secondMode(ctx.line, modeA) ?: run {
            ctx.log("只找到一个形态 $modeA, 需要两个形态才能切换")
            return false
        }

        val item = ctx.item
        val meta = item.itemMeta ?: return false
        val pdcKey = key(SWITCH_KEY)
        // 首次切换时 PDC 里没有状态(旧物品/服主手动加的 Lore), 从配置反推当前形态
        val current = meta.persistentDataContainer.get(pdcKey, PersistentDataType.STRING)
            ?: inferCurrentMode(ctx, item, modeA, modeB)
        val next = if (current == modeA) modeB else modeA
        ctx.log("形态 ${current ?: "(未知)"} -> $next")

        meta.persistentDataContainer.set(pdcKey, PersistentDataType.STRING, next)
        item.itemMeta = meta

        // 执行目标形态声明的 RewardSwitch; 目标形态自己配了提示就不再叠加兜底提示
        val switches = rewardSwitchesOf(next)
        var notified = false
        var applied = 0
        for (nested in switches) {
            if (SkillLineParser.parse(nested).name.lowercase() in TIP_FUNCTIONS) notified = true
            if (SkillExecutor.runNested(ctx, nested)) applied++
        }
        if (switches.isEmpty()) {
            // 配置里漏写了目标形态: 至少给个反馈, 否则按下去毫无反应像是插件坏了
            ctx.log("目标形态 $next 没有任何 RewardSwitch, 只更新了状态标记")
        }
        if (!notified) Lang.send(ctx.player, "skill.switch", "mode" to next)
        ctx.log("目标形态 $next 的 RewardSwitch 执行了 $applied/${switches.size} 条")
        ctx.player.inventory.setItemInMainHand(item)
        return true
    }

    /** 取第二个形态名: 优先裸参数, 其次任意非首键的值 */
    private fun secondMode(line: SkillLine, modeA: String): String? =
        line.args.keys.firstOrNull { it !in MODE_KEYS }
            ?.let { k -> if (line.args[k] == k) k else line.args[k] }
            ?: line.args.values.firstOrNull { it != modeA }

    /**
     * 从配置反推物品当前处于哪个形态 —— 通用化的关键
     *
     * 做法: 分别取出两个形态声明「要设为有效等级」的附魔, 看物品上实际带着谁的
     *
     */
    private fun inferCurrentMode(ctx: SkillContext, item: ItemStack, modeA: String, modeB: String): String? {
        val meta = item.itemMeta
        val enchantsA = signatureEnchants(modeA)
        val enchantsB = signatureEnchants(modeB)
        val hasA = enchantsA.any { meta?.hasEnchant(it) == true }
        val hasB = enchantsB.any { meta?.hasEnchant(it) == true }
        ctx.log(
            "反推形态: $modeA 特征=${enchantsA.map { it.key.key }}(命中=$hasA) " +
                "$modeB 特征=${enchantsB.map { it.key.key }}(命中=$hasB)"
        )
        // 两个都有或都没有时无法判断, 返回 null 让调用方切到 modeA
        return when {
            hasA && !hasB -> modeA
            hasB && !hasA -> modeB
            else -> null
        }
    }

    /**
     * 取某个形态的特征附魔: RewardSwitch 里 level=restore 或 level>0 的那些
     * level=0 表示"关掉", 不算特征
     */
    private fun signatureEnchants(mode: String): List<Enchantment> =
        rewardSwitchesOf(mode).mapNotNull { nested ->
            val line = SkillLineParser.parse(nested)
            if (!line.name.equals("Enchant", true)) return@mapNotNull null
            if (line.args["level"]?.toIntOrNull() == 0) return@mapNotNull null
            line.args["name"]?.let { Registries.enchantment(it) }
        }

    /** 取某个形态定义里全部 RewardSwitch 的嵌套函数字符串 */
    private fun rewardSwitchesOf(mode: String): List<String> =
        SkillRegistry.get(mode)?.skills.orEmpty().mapNotNull { raw ->
            val line = SkillLineParser.parse(raw)
            if (line.name.equals("RewardSwitch", true)) line.args.keys.firstOrNull() else null
        }
}
