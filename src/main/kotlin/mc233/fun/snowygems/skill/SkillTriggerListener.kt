package mc233.`fun`.snowygems.skill

import mc233.`fun`.snowygems.config.SkillRegistry
import mc233.`fun`.snowygems.util.ColorUtil
import mc233.`fun`.snowygems.util.DebugUtil
import org.bukkit.Material
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.player.PlayerAnimationEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import taboolib.common.platform.event.SubscribeEvent
import taboolib.platform.util.PlayerSessionMap
import taboolib.platform.util.sendActionBar

object SkillTriggerListener {

    private val cooldowns = PlayerSessionMap<MutableMap<String, Long>>({ mutableMapOf() })

    @SubscribeEvent
    fun onInteract(e: PlayerInteractEvent) {
        if (e.hand != EquipmentSlot.HAND) return
        // 只处理右键; 左键/挥臂交给 PlayerAnimationEvent, 更可靠(特别是三叉戟攻击时)
        if (e.action != Action.RIGHT_CLICK_AIR && e.action != Action.RIGHT_CLICK_BLOCK) return
        val item = e.item ?: return
        val trigger = if (e.player.isSneaking) "onShiftUse" else "onUse"
        DebugUtil.log("Skill", "${e.player.name} 右键触发 $trigger, 手持=${item.type}")
        runMatchingSkills(e.player, item, trigger)
    }

    @SubscribeEvent
    fun onSwing(e: PlayerAnimationEvent) {
        val player = e.player
        val item = player.inventory.itemInMainHand
        if (item.type == Material.AIR) return
        val trigger = if (player.isSneaking) "onShiftSwing" else "onSwing"
        DebugUtil.log("Skill", "${player.name} 挥臂触发 $trigger, 手持=${item.type}")
        runMatchingSkills(player, item, trigger)
    }

    @SubscribeEvent
    fun onAttack(e: EntityDamageByEntityEvent) {
        val player = e.damager as? Player ?: return
        val item = player.inventory.itemInMainHand
        if (item.type == Material.AIR) return
        DebugUtil.log("Skill", "${player.name} 攻击 ${e.entity.type} 触发 onAttack, 手持=${item.type}")
        runMatchingSkills(player, item, "onAttack", victim = e.entity as? LivingEntity)
    }

    @SubscribeEvent
    fun onProjectileHit(e: ProjectileHitEvent) {
        val shooter = e.entity.shooter as? Player ?: return
        val item = shooter.inventory.itemInMainHand
        if (item.type == Material.AIR) return
        val typeName = e.entity.type.name
        val hitLoc = e.hitBlock?.location ?: e.hitEntity?.location ?: e.entity.location
        DebugUtil.log("Skill", "${shooter.name} 的 $typeName 命中, 触发 onHit:$typeName, 手持=${item.type}")
        runMatchingSkills(shooter, item, "onHit:$typeName", hitLocation = hitLoc)
        if (typeName == "TRIDENT") runMatchingSkills(shooter, item, "onTridentHit", hitLocation = hitLoc)
    }

    private fun runMatchingSkills(
        player: Player,
        item: ItemStack,
        trigger: String,
        victim: LivingEntity? = null,
        hitLocation: org.bukkit.Location? = null
    ) {
        val lore = item.itemMeta?.lore
        if (lore == null) {
            DebugUtil.log("Skill", "  物品 ${item.type} 没有 Lore, 无法匹配任何技能标记")
            return
        }
        var matched = 0
        for (def in SkillRegistry.all()) {
            val marker = def.lore ?: continue
            if (lore.none { ColorUtil.loreMatches(it, marker) }) continue
            matched++
            val all = def.skills.map { SkillLineParser.parse(it) }
            val relevant = all.filter { it.triggers.contains(trigger) }
            DebugUtil.log(
                "Skill",
                "  命中技能定义 ${def.id} (Lore标记=${def.lore}), 该定义共 ${all.size} 行, 其中 ${relevant.size} 行响应 $trigger"
            )
            if (relevant.isEmpty()) continue
            val remaining = checkAndSetCooldown(player, def.id, def.cooldown)
            if (remaining != null) {
                DebugUtil.log("Skill", "  技能 ${def.id} 仍在冷却中(${String.format("%.2f", remaining)}s), 跳过")
                def.cooldownTip?.let { renderCooldownTip(player, it, remaining, def.cooldown) }
                continue
            }
            for (line in relevant) {
                DebugUtil.log("Skill", "  执行技能行: name=${line.name} target=${line.target} args=${line.args} triggers=${line.triggers}")
                SkillExecutor.execute(player, item, line, victim, hitLocation)
            }
        }
        if (matched == 0) {
            DebugUtil.log("Skill", "  物品 ${item.type} 的 Lore 没有命中任何技能定义 (已加载 ${SkillRegistry.all().size} 个定义)")
        }
    }

    /** 返回 null=不在冷却中可以执行; 返回剩余秒数=冷却中 */
    private fun checkAndSetCooldown(player: Player, skillId: String, cooldownSeconds: Double): Double? {
        if (cooldownSeconds <= 0) return null
        val map = cooldowns.getOrCreate(player) ?: return null
        val now = System.currentTimeMillis()
        val last = map[skillId] ?: 0L
        val elapsed = (now - last) / 1000.0
        if (elapsed < cooldownSeconds) {
            return cooldownSeconds - elapsed
        }
        map[skillId] = now
        return null
    }

    /** 解析 CooldownTip 表达式并渲染. 支持 ActionBar{...} / Chat{...} / Title{...} */
    private fun renderCooldownTip(player: Player, raw: String, remaining: Double, total: Double) {
        val tip = SkillLineParser.parse(raw)
        val prefix = ColorUtil.colorize(tip.args["p"] ?: "")
        when (tip.name.lowercase()) {
            "actionbar" -> {
                val suffixTpl = ColorUtil.colorize(tip.args["s"] ?: "%.2fs")
                val empty = ColorUtil.colorize(tip.args["empty"] ?: "&f|")
                val full = ColorUtil.colorize(tip.args["full"] ?: "&a|")
                val length = tip.args["length"]?.toIntOrNull() ?: 10
                val ratio = (1.0 - remaining / total).coerceIn(0.0, 1.0)
                val filled = (ratio * length).toInt()
                val bar = full.repeat(filled) + empty.repeat(length - filled)
                val suffix = try {
                    String.format(suffixTpl, remaining)
                } catch (e: Exception) {
                    suffixTpl
                }
                player.sendActionBar(prefix + bar + suffix)
            }
            "chat" -> player.sendMessage(ColorUtil.colorize(tip.args["m"] ?: "&c技能冷却中"))
            "title" -> player.sendTitle(ColorUtil.colorize(tip.args["m"] ?: "&c冷却中"), "", 10, 40, 10)
            else -> player.sendMessage(ColorUtil.colorize("&c技能冷却中: &f${String.format("%.2f", remaining)}s"))
        }
    }
}
