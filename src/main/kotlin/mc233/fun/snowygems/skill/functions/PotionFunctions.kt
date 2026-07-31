package mc233.`fun`.snowygems.skill.functions

import mc233.`fun`.snowygems.compat.Registries
import mc233.`fun`.snowygems.skill.SkillContext
import mc233.`fun`.snowygems.skill.SkillFunctions
import mc233.`fun`.snowygems.skill.skillFunction
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import taboolib.common.platform.function.submit

/**
 * 药水效果类技能函数
 *
 * 多版本要点: 效果名一律经 [Registries.effect] 解析, 因此
 *   - 1.20.5+ 的注册表化改名(DAMAGE_RESISTANCE -> resistance)自动兼容
 *   - 1.21 新增的 oozing / infested / weaving / wind_charged 直接可用
 *   - 26.x 若再加新效果, 无需改代码
 *   - 老版本上写了新效果只跳过这一行, 并在日志里说明"需要更高版本"
 */
object PotionFunctions {

    private const val MIN_TICKS = 40

    fun registerAll() {
        SkillFunctions.register(skillFunction(
            name = "Potion",
            aliases = listOf("PotionBuff", "Buff", "药水"),
            description = "给目标施加药水效果. 效果名支持新旧两种写法",
            usage = "{type=SPEED;level=2;duration=10}  duration 单位为秒"
        ) { ctx -> applyPotion(ctx) })

        SkillFunctions.register(skillFunction(
            name = "Debuff",
            aliases = listOf("负面"),
            description = "施加负面效果. type=move 为强力缓慢, type=fly 为禁飞, 其余按效果名解析",
            usage = "{type=move;duration=5}  或 {type=BLINDNESS;level=1;duration=5}"
        ) { ctx -> applyDebuff(ctx) })

        SkillFunctions.register(skillFunction(
            name = "ClearPotion",
            aliases = listOf("RemovePotion", "清除效果"),
            description = "移除目标身上的指定效果, 不写 type 则清除全部",
            usage = "{type=POISON}  或不带参数清空所有效果"
        ) { ctx -> clearPotion(ctx) })
    }

    /**
     * 施加一个药水效果.
     *
     * duration 单位是秒(配置里习惯这么写), 内部换算成 tick
     * level 是"第几级"(1 起), 而 PotionEffect 的 amplifier 从 0 起, 所以要 -1
     */
    private fun applyPotion(ctx: SkillContext): Boolean {
        val type = ctx.potionEffect("type", "t", "name") ?: return false
        val seconds = ctx.intOr(5, "duration", "d")
        val level = ctx.intOr(1, "level", "lv", "amplifier").coerceAtLeast(1)
        val target = ctx.target
        ctx.log("给 ${target.type} 施加 ${type.key.key} 等级=$level 时长=${seconds}s")
        target.addPotionEffect(effect(type, seconds, level))
        return true
    }

    /**
     * 负面效果. move/fly 是两个语义化的特殊值, 其余一律按效果名解析,
     * 因此服主可以写 Debuff{type=weaving;duration=5} 这种新版本效果
     */
    private fun applyDebuff(ctx: SkillContext): Boolean {
        val typeName = ctx.str("type", "t") ?: return false
        val seconds = ctx.intOr(5, "duration", "d")
        return when (typeName.lowercase()) {
            "move", "禁行" -> {
                val slowness = Registries.effect("slowness") ?: run {
                    ctx.skipUnsupported("缓慢效果")
                    return false
                }
                ctx.log("禁止 ${ctx.target.type} 移动 ${seconds}s")
                // amplifier 250 是惯用的"完全定身"量级, 远超正常缓慢等级
                ctx.target.addPotionEffect(effect(slowness, seconds, 251))
                true
            }
            "fly", "禁飞" -> {
                val player = ctx.target as? Player ?: run {
                    ctx.log("禁飞只能作用于玩家, 当前目标是 ${ctx.target.type}")
                    return false
                }
                val original = player.allowFlight
                player.allowFlight = false
                player.isFlying = false
                ctx.log("禁止 ${player.name} 飞行 ${seconds}s")
                submit(delay = seconds * 20L) { player.allowFlight = original }
                true
            }
            else -> applyPotion(ctx)
        }
    }

    private fun clearPotion(ctx: SkillContext): Boolean {
        val target = ctx.target
        if (ctx.str("type", "t") == null) {
            val active = target.activePotionEffects
            if (active.isEmpty()) return false
            ctx.log("清除 ${target.type} 的全部 ${active.size} 个效果")
            active.forEach { target.removePotionEffect(it.type) }
            return true
        }
        val type = ctx.potionEffect("type", "t") ?: return false
        if (!target.hasPotionEffect(type)) {
            ctx.log("${target.type} 身上没有 ${type.key.key}, 无需移除")
            return false
        }
        target.removePotionEffect(type)
        ctx.log("移除 ${target.type} 的 ${type.key.key}")
        return true
    }

    private fun effect(type: org.bukkit.potion.PotionEffectType, seconds: Int, level: Int) =
        PotionEffect(type, (seconds * 20).coerceAtLeast(MIN_TICKS), level - 1, true, false)
}
