package mc233.`fun`.snowygems.skill

import mc233.`fun`.snowygems.SnowyGems
import mc233.`fun`.snowygems.config.SkillRegistry
import mc233.`fun`.snowygems.reward.impl.EnchantReward
import mc233.`fun`.snowygems.util.ColorUtil
import mc233.`fun`.snowygems.util.DebugUtil
import mc233.`fun`.snowygems.util.Lang
import org.bukkit.*
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import taboolib.common.platform.function.submit
import taboolib.platform.util.sendActionBar

/**
 * 简化版技能函数执行器.
 * 支持: Blink / Reward{Chat|ActionBar|Title} / Debuff / Switch / RewardSwitch /
 * Potion / Damage / Heal / Fire / Sound / Particle / Lightning / Command.
 * 这不是完整的 Kether 解释器, 仅覆盖常见用法.
 */
object SkillExecutor {

    private val plugin by lazy { SnowyGems.plugin }
    private fun ns(path: String) = NamespacedKey(plugin, path)
    private const val SWITCH_KEY = "switch_mode"
    private const val ENCH_PREFIX = "ench_"

    /** 这些嵌套函数本身就会给玩家发提示, 命中时不再叠加语言文件的兜底提示 */
    private val TIP_FUNCTIONS = setOf("chat", "actionbar", "title")

    fun execute(player: Player, item: ItemStack, line: SkillLine, victim: LivingEntity? = null, hitLocation: Location? = null) {
        DebugUtil.log("SkillExec", "执行 ${line.name} 给 ${player.name} (target=${line.target} args=${line.args})")
        try {
            when (line.name.lowercase()) {
                "blink" -> blink(player, item, line, hitLocation)
                "reward" -> {
                    val nestedRaw = line.args.keys.firstOrNull()
                    if (nestedRaw == null) {
                        DebugUtil.log("SkillExec", "  Reward{...} 里没有嵌套函数, 跳过")
                        return
                    }
                    executeSimpleFunction(player, nestedRaw)
                }
                "debuff" -> debuff(resolveTarget(player, victim, line.target), item, line)
                "potion", "potionbuff" -> potion(resolveTarget(player, victim, line.target), item, line)
                "switch" -> switchMode(player, item, line)
                "damage" -> damage(resolveTarget(player, victim, line.target), line)
                "heal" -> heal(resolveTarget(player, victim, line.target), line)
                "fire" -> fire(resolveTarget(player, victim, line.target), line)
                "sound" -> sound(player, line)
                "particle" -> particle(player, line, hitLocation)
                "lightning" -> lightning(player, line, hitLocation)
                "command" -> command(player, line)
                else -> DebugUtil.log("SkillExec", "  未实现的技能函数: ${line.name}")
            }
        } catch (e: Exception) {
            DebugUtil.err("SkillExec", "执行 ${line.name} 时抛出异常", e)
        }
    }

    private fun resolveTarget(player: Player, victim: LivingEntity?, target: String): LivingEntity {
        return when (target.lowercase()) {
            "entity" -> victim ?: player
            "location" -> player
            else -> player
        }
    }

    private fun blink(player: Player, item: ItemStack, line: SkillLine, hitLocation: Location?) {
        val distance = SkillValue.resolveDouble(line.args["distance"] ?: "10", item, 10.0)
        val dest = when (line.target) {
            "Location" -> hitLocation ?: return
            else -> {
                val loc = player.location.clone()
                val dir = loc.direction.normalize()
                val world = loc.world ?: return
                var travelled = 0.0
                val step = 1.0
                var result = loc
                while (travelled < distance) {
                    val next = loc.clone().add(dir.clone().multiply(travelled))
                    if (!next.block.type.isAir) break
                    result = next
                    travelled += step
                }
                result
            }
        }
        DebugUtil.log("SkillExec", "  Blink: distance=$distance mode=${line.target ?: "视线方向"} 目标=${dest.blockX},${dest.blockY},${dest.blockZ}")
        dest.world?.let { player.teleport(dest) }
    }

    private fun debuff(target: LivingEntity, item: ItemStack, line: SkillLine) {
        val type = line.args["type"] ?: run {
            DebugUtil.log("SkillExec", "  Debuff 缺少 type 参数, 跳过")
            return
        }
        val durationSeconds = SkillValue.resolveInt(line.args["duration"] ?: "5", item, 5)
        DebugUtil.log("SkillExec", "  Debuff: type=$type duration=${durationSeconds}s 目标=${target.type}")
        when (type.lowercase()) {
            "move" -> target.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, durationSeconds * 20, 250, true, false))
            "fly" -> {
                if (target !is Player) return
                val original = target.allowFlight
                target.allowFlight = false
                target.isFlying = false
                submit(delay = (durationSeconds * 20).toLong()) {
                    target.allowFlight = original
                }
            }
            else -> {
                // 通用药水: 会话 Debuff 也能写任意效果, 如 BLINDNESS/失明
                resolvePotionType(type)?.let {
                    val level = SkillValue.resolveInt(line.args["level"] ?: "1", item, 1).coerceAtLeast(1)
                    target.addPotionEffect(PotionEffect(it, (durationSeconds * 20).coerceAtLeast(20), level - 1, true, false))
                } ?: DebugUtil.log("SkillExec", "    无法识别的 Debuff 类型: $type")
            }
        }
    }

    private fun potion(target: LivingEntity, item: ItemStack, line: SkillLine) {
        val typeName = line.args["type"] ?: run {
            DebugUtil.log("SkillExec", "  Potion 缺少 type 参数, 跳过")
            return
        }
        val type = resolvePotionType(typeName) ?: run {
            DebugUtil.log("SkillExec", "  无法识别的药水效果: $typeName")
            return
        }
        val durationSeconds = SkillValue.resolveInt(line.args["duration"] ?: "5", item, 5)
        val level = SkillValue.resolveInt(line.args["level"] ?: "1", item, 1).coerceAtLeast(1)
        DebugUtil.log("SkillExec", "  Potion: 给 ${target.type} 施加 ${type.name} 等级=$level 时长=${durationSeconds}s")
        target.addPotionEffect(PotionEffect(type, (durationSeconds * 20).coerceAtLeast(20), level - 1, true, false))
    }

    private fun damage(target: LivingEntity, line: SkillLine) {
        val amount = line.args["amount"]?.toDoubleOrNull() ?: line.args["a"]?.toDoubleOrNull() ?: 0.0
        if (amount <= 0) return
        DebugUtil.log("SkillExec", "  Damage: 对 ${target.type} 造成 $amount 伤害")
        target.damage(amount)
    }

    private fun heal(target: LivingEntity, line: SkillLine) {
        val amount = line.args["amount"]?.toDoubleOrNull() ?: line.args["a"]?.toDoubleOrNull() ?: 0.0
        if (amount <= 0) return
        DebugUtil.log("SkillExec", "  Heal: 恢复 ${target.type} $amount 生命")
        target.health = (target.health + amount).coerceAtMost(target.maxHealth)
    }

    private fun fire(target: LivingEntity, line: SkillLine) {
        val ticks = line.args["ticks"]?.toIntOrNull() ?: line.args["duration"]?.toIntOrNull() ?: 0
        if (ticks <= 0) return
        DebugUtil.log("SkillExec", "  Fire: 烧伤 ${target.type} ${ticks}tick")
        target.fireTicks = ticks
    }

    private fun sound(player: Player, line: SkillLine) {
        val name = line.args["name"] ?: run {
            DebugUtil.log("SkillExec", "  Sound 缺少 name 参数")
            return
        }
        val sound = try {
            Sound.valueOf(name.uppercase())
        } catch (e: Exception) {
            DebugUtil.log("SkillExec", "  无法识别的音效: $name")
            return
        }
        val volume = line.args["volume"]?.toFloatOrNull() ?: 1f
        val pitch = line.args["pitch"]?.toFloatOrNull() ?: 1f
        player.playSound(player.location, sound, volume, pitch)
        DebugUtil.log("SkillExec", "  Sound: $name volume=$volume pitch=$pitch")
    }

    private fun particle(player: Player, line: SkillLine, hitLocation: Location?) {
        val name = line.args["name"] ?: run {
            DebugUtil.log("SkillExec", "  Particle 缺少 name 参数")
            return
        }
        val particle = try {
            Particle.valueOf(name.uppercase())
        } catch (e: Exception) {
            DebugUtil.log("SkillExec", "  无法识别的粒子: $name")
            return
        }
        val loc = hitLocation ?: player.location
        val count = line.args["count"]?.toIntOrNull() ?: 20
        val speed = line.args["speed"]?.toDoubleOrNull() ?: 0.0
        val offsetX = line.args["ox"]?.toDoubleOrNull() ?: 0.5
        val offsetY = line.args["oy"]?.toDoubleOrNull() ?: 0.5
        val offsetZ = line.args["oz"]?.toDoubleOrNull() ?: 0.5
        loc.world?.spawnParticle(particle, loc, count, offsetX, offsetY, offsetZ, speed)
        DebugUtil.log("SkillExec", "  Particle: $name 位置=${loc.blockX},${loc.blockY},${loc.blockZ} 数量=$count")
    }

    private fun lightning(player: Player, line: SkillLine, hitLocation: Location?) {
        val loc = hitLocation ?: player.location
        val damage = line.args["damage"]?.toBooleanStrictOrNull() ?: true
        DebugUtil.log("SkillExec", "  Lightning: 位置=${loc.blockX},${loc.blockY},${loc.blockZ} 造成伤害=$damage")
        loc.world?.strikeLightning(loc)
    }

    private fun command(player: Player, line: SkillLine) {
        val cmd = line.args["c"] ?: line.args["command"] ?: run {
            DebugUtil.log("SkillExec", "  Command 缺少 c/command 参数")
            return
        }
        val asWho = line.args["as"] ?: "player"
        val parsed = cmd.replace("%player%", player.name).replace("%uuid%", player.uniqueId.toString())
        DebugUtil.log("SkillExec", "  Command: as=$asWho 命令=$parsed")
        when (asWho.lowercase()) {
            "op" -> {
                val wasOp = player.isOp
                player.isOp = true
                try {
                    Bukkit.dispatchCommand(player, parsed)
                } finally {
                    player.isOp = wasOp
                }
            }
            "console" -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed)
            else -> player.performCommand(parsed)
        }
    }

    private fun switchMode(player: Player, item: ItemStack, line: SkillLine) {
        val modeA = line.args["s"] ?: return
        // 兼容 Switch{s=精准;时运} 以及 Switch{s=精准;mode=时运}
        val modeB = line.args.keys.firstOrNull { it != "s" }
            ?: line.args.values.firstOrNull { it != modeA }
            ?: return
        // 优先使用触发时的物品，避免副手/事件物品与主手不一致导致三叉戟等无法切换。
        val target = item
        var meta = target.itemMeta ?: return
        val stored = meta.persistentDataContainer.get(ns(SWITCH_KEY), PersistentDataType.STRING)
        // 第一次切换不能只依赖 PDC。旧物品/手动添加 Lore 没有状态，需要根据实际附魔判断当前模式。
        val current = stored ?: when {
            target.itemMeta?.hasEnchant(org.bukkit.enchantments.Enchantment.LOOTING) == true -> modeB
            target.itemMeta?.hasEnchant(org.bukkit.enchantments.Enchantment.SILK_TOUCH) == true -> modeA
            else -> null
        }
        val next = if (current == modeA) modeB else modeA
        meta.persistentDataContainer.set(ns(SWITCH_KEY), PersistentDataType.STRING, next)
        target.itemMeta = meta
        DebugUtil.log("SkillExec", "  Switch: 形态 ${current ?: "(未设置)"} -> $next")
        val nextDef = SkillRegistry.get(next) ?: run {
            DebugUtil.log("SkillExec", "  Switch 目标形态 $next 在技能配置中不存在")
            // 配置里没定义目标形态: 至少给玩家一个反馈, 否则按下去毫无反应像是坏了
            Lang.send(player, "skill.switch", "mode" to next)
            player.inventory.setItemInMainHand(target)
            return
        }
        // 目标形态自己配了 Chat/ActionBar/Title 提示时以配置为准, 没配才用语言文件兜底
        var notified = false
        for (raw in nextDef.skills) {
            val nl = SkillLineParser.parse(raw)
            if (!nl.name.equals("RewardSwitch", true)) continue
            val nestedRaw = nl.args.keys.firstOrNull() ?: continue
            if (SkillLineParser.parse(nestedRaw).name.lowercase() in TIP_FUNCTIONS) notified = true
            executeSimpleFunction(player, nestedRaw, target)
        }
        if (!notified) Lang.send(player, "skill.switch", "mode" to next)
        player.inventory.setItemInMainHand(target)
    }

    /** 执行 Chat{m=...} / ActionBar{m=...} / Title{m=...} / Enchant{...} 这类简单函数 */
    private fun executeSimpleFunction(player: Player, raw: String, handItem: ItemStack? = null) {
        val nested = SkillLineParser.parse(raw)
        DebugUtil.log("SkillExec", "  嵌套函数 ${nested.name} args=${nested.args}")
        when (nested.name.lowercase()) {
            "chat" -> player.sendMessage(ColorUtil.colorize(nested.args["m"] ?: ""))
            "actionbar" -> {
                val msg = ColorUtil.colorize(nested.args["m"] ?: "")
                player.sendActionBar(msg)
            }
            "title" -> {
                val msg = ColorUtil.colorize(nested.args["m"] ?: "")
                player.sendTitle(msg, "", 10, 40, 10)
            }
            "enchant" -> {
                val item = handItem ?: player.inventory.itemInMainHand
                val rawName = nested.args["name"] ?: return
                val enchant = EnchantReward.resolveEnchant(rawName) ?: run {
                    DebugUtil.log("SkillExec", "    附魔名无法解析: $rawName")
                    return
                }
                var meta = item.itemMeta ?: return
                val levelArg = nested.args["level"]
                if (levelArg != null && levelArg.equals("restore", true)) {
                    val saved = meta.persistentDataContainer.get(ns(ENCH_PREFIX + enchant.key.key), PersistentDataType.INTEGER)?.coerceAtLeast(1) ?: 1
                    meta.addEnchant(enchant, saved, true)
                    DebugUtil.log("SkillExec", "    恢复附魔 ${enchant.key.key} 等级=$saved")
                } else {
                    val level = levelArg?.toIntOrNull()
                    if (level != null) {
                        if (level <= 0) {
                            val cur = meta.getEnchantLevel(enchant)
                            if (cur > 0) {
                                meta.persistentDataContainer.set(ns(ENCH_PREFIX + enchant.key.key), PersistentDataType.INTEGER, cur)
                            }
                            meta.removeEnchant(enchant)
                            DebugUtil.log("SkillExec", "    移除附魔 ${enchant.key.key} (已记住等级=$cur)")
                        } else {
                            meta.addEnchant(enchant, level, true)
                            DebugUtil.log("SkillExec", "    设置附魔 ${enchant.key.key} 等级=$level")
                        }
                    } else {
                        val limit = nested.args["limit"]?.toIntOrNull()
                        var nextLv = meta.getEnchantLevel(enchant) + 1
                        if (limit != null) nextLv = nextLv.coerceAtMost(limit)
                        meta.addEnchant(enchant, nextLv, true)
                        DebugUtil.log("SkillExec", "    附魔 ${enchant.key.key} 等级 +1 -> $nextLv (limit=$limit)")
                    }
                }
                item.itemMeta = meta
            }
        }
    }

    private fun resolvePotionType(name: String): PotionEffectType? {
        val raw = name.trim()
        val mapped = LEGACY_TYPES[raw.uppercase()] ?: raw
        for (c in listOf(mapped.uppercase(), mapped.lowercase(), raw.uppercase(), raw.lowercase())) {
            @Suppress("DEPRECATION")
            PotionEffectType.getByName(c)?.let { return it }
        }
        for (key in listOf(mapped.lowercase(), raw.lowercase())) {
            try {
                @Suppress("DEPRECATION")
                org.bukkit.Registry.EFFECT.get(org.bukkit.NamespacedKey.minecraft(key))?.let { return it }
            } catch (ignored: Exception) {
            }
        }
        return try {
            @Suppress("DEPRECATION")
            PotionEffectType::class.java.getField(mapped.uppercase()).get(null) as? PotionEffectType
        } catch (e: Exception) {
            null
        }
    }

    private val LEGACY_TYPES = mapOf(
        "DAMAGE_RESISTANCE" to "RESISTANCE",
        "FAST_DIGGING" to "HASTE",
        "SLOW_DIGGING" to "MINING_FATIGUE",
        "INCREASE_DAMAGE" to "STRENGTH",
        "JUMP" to "JUMP_BOOST",
        "MOVEMENT_SPEED" to "SPEED",
        "SLOW" to "SLOWNESS",
        "HEAL" to "INSTANT_HEALTH",
        "HARM" to "INSTANT_DAMAGE",
        "CONFUSION" to "NAUSEA",
        "BLIND" to "BLINDNESS",
        "NIGHTVISION" to "NIGHT_VISION"
    )
}
