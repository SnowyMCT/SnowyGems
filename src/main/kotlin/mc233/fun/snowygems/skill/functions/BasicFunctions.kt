package mc233.`fun`.snowygems.skill.functions

import mc233.`fun`.snowygems.compat.Registries
import mc233.`fun`.snowygems.skill.SkillContext
import mc233.`fun`.snowygems.skill.SkillFunctions
import mc233.`fun`.snowygems.skill.skillFunction
import mc233.`fun`.snowygems.util.ColorUtil
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Registry
import org.bukkit.Sound
import taboolib.platform.util.sendActionBar

/**
 * 基础技能函数: 位移 / 伤害 / 治疗 / 音效 / 粒子 / 命令 / 消息。
 *
 * 多版本要点: Sound 和 Particle 在各版本间增删频繁(1.21 加了风弹音效, 26.x 又有新粒子),
 * 且 Sound 在 1.21.3 前后从枚举变成了接口+注册表。因此全部走"按名字查, 查不到就跳过并
 * 提示"的模式, 绝不硬编码可用清单。
 */
object BasicFunctions {

    fun registerAll() {
        registerMovement()
        registerCombat()
        registerFeedback()
        registerWorld()
    }

    // ── 位移 ────────────────────────────────────────────────

    private fun registerMovement() {
        SkillFunctions.register(skillFunction(
            name = "Blink",
            aliases = listOf("Teleport", "闪现"),
            description = "沿视线方向瞬移, 或直接传送到命中点",
            usage = "{distance=10}  配合 @Location 时传送到发射物命中处"
        ) { ctx ->
            val distance = ctx.numOr(10.0, "distance", "d")
            val dest = if (ctx.line.target.equals("Location", true) && ctx.hitLocation != null) {
                ctx.hitLocation
            } else {
                lineOfSight(ctx, distance)
            }
            if (dest.world == null) return@skillFunction false
            ctx.log("传送到 ${dest.blockX},${dest.blockY},${dest.blockZ} (distance=$distance)")
            ctx.player.teleport(dest)
            true
        })

        SkillFunctions.register(skillFunction(
            name = "Velocity",
            aliases = listOf("Push", "击退", "冲刺"),
            description = "给目标一个速度矢量(可做冲刺/击飞/上抛)",
            usage = "{forward=1.5;up=0.5}  或 {x=0;y=1;z=0}"
        ) { ctx ->
            val target = ctx.target
            val v = target.velocity
            val forward = ctx.num("forward", "f")
            val up = ctx.num("up", "u")
            if (forward == null && up == null) {
                // 绝对分量写法
                ctx.num("x")?.let { v.x = it }
                ctx.num("y")?.let { v.y = it }
                ctx.num("z")?.let { v.z = it }
            } else {
                // 相对朝向写法: forward 沿视线推, up 直接设纵向速度
                forward?.let { v.add(ctx.player.location.direction.normalize().multiply(it)) }
                up?.let { v.y = it }
            }
            ctx.log("设置速度 -> $v")
            target.velocity = v
            true
        })
    }

    /** 沿视线逐格前进, 撞到非空气就停在前一格 —— 避免把玩家塞进墙里 */
    private fun lineOfSight(ctx: SkillContext, distance: Double): Location {
        val from = ctx.player.location.clone()
        val dir = from.direction.normalize()
        var result = from
        var travelled = 0.0
        while (travelled < distance) {
            val next = from.clone().add(dir.clone().multiply(travelled))
            if (!next.block.type.isAir) break
            result = next
            travelled += 1.0
        }
        return result
    }

    // ── 战斗 ────────────────────────────────────────────────

    private fun registerCombat() {
        SkillFunctions.register(skillFunction(
            name = "Damage",
            aliases = listOf("伤害"),
            description = "对目标造成伤害",
            usage = "{amount=5}  配合 @Entity 作用于被攻击者"
        ) { ctx ->
            val amount = ctx.numOr(0.0, "amount", "a")
            if (amount <= 0) return@skillFunction false
            ctx.log("对 ${ctx.target.type} 造成 $amount 伤害")
            ctx.target.damage(amount)
            true
        })

        SkillFunctions.register(skillFunction(
            name = "Heal",
            aliases = listOf("治疗", "恢复"),
            description = "恢复目标生命",
            usage = "{amount=5}"
        ) { ctx ->
            val amount = ctx.numOr(0.0, "amount", "a")
            if (amount <= 0) return@skillFunction false
            val target = ctx.target
            // 生命上限的跨版本取法收在 SkillContext 里(getMaxHealth 已被属性取代)
            val max = SkillContext.maxHealthOf(target)
            if (target.health >= max) {
                ctx.log("${target.type} 生命已满, 无需治疗")
                return@skillFunction false
            }
            ctx.log("恢复 ${target.type} $amount 生命 (上限 $max)")
            target.health = (target.health + amount).coerceAtMost(max)
            true
        })

        SkillFunctions.register(skillFunction(
            name = "Fire",
            aliases = listOf("点燃", "燃烧"),
            description = "点燃目标",
            usage = "{ticks=100}  20 tick = 1 秒"
        ) { ctx ->
            val ticks = ctx.intOr(0, "ticks", "duration", "t")
            if (ticks <= 0) return@skillFunction false
            ctx.log("点燃 ${ctx.target.type} ${ticks}tick")
            ctx.target.fireTicks = ticks
            true
        })

        SkillFunctions.register(skillFunction(
            name = "Lightning",
            aliases = listOf("闪电", "雷击"),
            description = "在目标位置降下闪电",
            usage = "{damage=true}  damage=false 则只有视觉效果"
        ) { ctx ->
            val loc = ctx.location
            val world = loc.world ?: return@skillFunction false
            val withDamage = ctx.bool("damage") ?: true
            ctx.log("在 ${loc.blockX},${loc.blockY},${loc.blockZ} 降下闪电 (伤害=$withDamage)")
            if (withDamage) world.strikeLightning(loc) else world.strikeLightningEffect(loc)
            true
        })
    }

    // ── 反馈(消息/音效/粒子) ───────────────────────────────

    private fun registerFeedback() {
        SkillFunctions.register(skillFunction(
            name = "Chat",
            aliases = listOf("Message", "Msg", "消息"),
            description = "给玩家发一条聊天消息",
            usage = "{m=&a技能发动!}"
        ) { ctx ->
            val msg = ctx.str("m", "message", "msg") ?: return@skillFunction false
            ctx.player.sendMessage(ColorUtil.colorize(msg))
            true
        })

        SkillFunctions.register(skillFunction(
            name = "ActionBar",
            aliases = listOf("动作栏"),
            description = "在物品栏上方显示一行文字",
            usage = "{m=&b蓄力完成}"
        ) { ctx ->
            val msg = ctx.str("m", "message", "msg") ?: return@skillFunction false
            ctx.player.sendActionBar(ColorUtil.colorize(msg))
            true
        })

        SkillFunctions.register(skillFunction(
            name = "Title",
            aliases = listOf("标题"),
            description = "显示大标题",
            usage = "{m=主标题;s=副标题;fadeIn=10;stay=40;fadeOut=10}"
        ) { ctx ->
            val msg = ctx.str("m", "message", "msg") ?: return@skillFunction false
            ctx.player.sendTitle(
                ColorUtil.colorize(msg),
                ColorUtil.colorize(ctx.str("s", "sub", "subtitle") ?: ""),
                ctx.intOr(10, "fadeIn"),
                ctx.intOr(40, "stay"),
                ctx.intOr(10, "fadeOut")
            )
            true
        })

        SkillFunctions.register(skillFunction(
            name = "Sound",
            aliases = listOf("音效"),
            description = "播放音效. 音效名随版本增删, 写错或版本不支持会跳过并提示",
            usage = "{name=ENTITY_PLAYER_LEVELUP;volume=1;pitch=1}"
        ) { ctx ->
            val name = ctx.str("name", "n", "sound") ?: return@skillFunction false
            val sound = resolveSound(name) ?: run {
                ctx.skipUnsupported("音效 $name", "请用当前版本存在的音效名")
                return@skillFunction false
            }
            val volume = ctx.numOr(1.0, "volume", "v").toFloat()
            val pitch = ctx.numOr(1.0, "pitch", "p").toFloat()
            ctx.log("播放音效 $name volume=$volume pitch=$pitch")
            ctx.player.playSound(ctx.location, sound, volume, pitch)
            true
        })

        SkillFunctions.register(skillFunction(
            name = "Particle",
            aliases = listOf("粒子"),
            description = "生成粒子效果. 粒子名随版本增删, 不支持会跳过并提示",
            usage = "{name=FLAME;count=20;ox=0.5;oy=0.5;oz=0.5;speed=0}"
        ) { ctx ->
            val name = ctx.str("name", "n", "particle") ?: return@skillFunction false
            val particle = resolveParticle(name) ?: run {
                ctx.skipUnsupported("粒子 $name", "请用当前版本存在的粒子名")
                return@skillFunction false
            }
            val loc = ctx.location
            val world = loc.world ?: return@skillFunction false
            ctx.log("在 ${loc.blockX},${loc.blockY},${loc.blockZ} 生成粒子 $name")
            world.spawnParticle(
                particle, loc,
                ctx.intOr(20, "count", "c"),
                ctx.numOr(0.5, "ox"), ctx.numOr(0.5, "oy"), ctx.numOr(0.5, "oz"),
                ctx.numOr(0.0, "speed")
            )
            true
        })
    }

    // ── 世界交互 ────────────────────────────────────────────

    private fun registerWorld() {
        SkillFunctions.register(skillFunction(
            name = "Command",
            aliases = listOf("Cmd", "命令"),
            description = "执行命令. as=player/op/console 决定执行者",
            usage = "{c=give %player% diamond 1;as=console}"
        ) { ctx ->
            val cmd = ctx.str("c", "command", "cmd") ?: return@skillFunction false
            val asWho = ctx.str("as") ?: "player"
            val parsed = cmd
                .replace("%player%", ctx.player.name)
                .replace("%uuid%", ctx.player.uniqueId.toString())
            ctx.log("以 $asWho 身份执行: $parsed")
            when (asWho.lowercase()) {
                "op" -> {
                    // 临时提权后必须还原, 因此放在 finally 里
                    val wasOp = ctx.player.isOp
                    ctx.player.isOp = true
                    try {
                        Bukkit.dispatchCommand(ctx.player, parsed)
                    } finally {
                        ctx.player.isOp = wasOp
                    }
                }
                "console" -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed)
                else -> ctx.player.performCommand(parsed)
            }
            true
        })
    }

    // ── 版本安全的枚举解析 ──────────────────────────────────

    /**
     * 音效解析. Sound 在 1.21.3 前后从枚举变成了接口 + 注册表, 两种形态都要能查:
     * 先试注册表(新版本), 再试枚举 valueOf(老版本), 全失败才算不支持。
     *
     * 注册表用反射取: 编译目标上 Registry.SOUNDS 的类型随版本变化, 静态引用会在
     * 另一个版本上编译不过。
     */
    private fun resolveSound(name: String): Sound? {
        val raw = name.trim()
        runCatching {
            val key = Registries.keyOf(raw) ?: return@runCatching null
            @Suppress("UNCHECKED_CAST")
            (Registry::class.java.getField("SOUNDS").get(null) as Registry<Sound>).get(key)
        }.getOrNull()?.let { return it }
        return runCatching { Sound.valueOf(raw.uppercase().replace('.', '_')) }.getOrNull()
    }

    /** 粒子解析. Particle 始终是枚举, 但成员随版本变化, 所以 valueOf 包 runCatching */
    private fun resolveParticle(name: String): Particle? =
        runCatching { Particle.valueOf(name.trim().uppercase()) }.getOrNull()
}
