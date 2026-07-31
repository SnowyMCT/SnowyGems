package mc233.`fun`.snowygems.skill

import mc233.`fun`.snowygems.config.SkillRegistry
import mc233.`fun`.snowygems.util.ColorUtil
import mc233.`fun`.snowygems.util.DebugUtil
import mc233.`fun`.snowygems.util.Lang
import org.bukkit.Material
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import java.util.UUID
import org.bukkit.event.player.PlayerAnimationEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import java.util.concurrent.ConcurrentHashMap
import taboolib.common.platform.Schedule
import taboolib.common.platform.event.SubscribeEvent
import taboolib.common5.cint
import taboolib.common5.util.createBar
import taboolib.platform.util.PlayerSessionMap
import taboolib.platform.util.sendActionBar

object SkillTriggerListener {

    /** 冷却表按玩家会话存放, 玩家退出后由 TabooLib 自动清理, 不会随时间无限增长 */
    private val cooldowns = PlayerSessionMap<MutableMap<String, Long>>({ mutableMapOf() })

    /**
     * 发射物命中时玩家手里通常已经不是原来的弓/三叉戟, 所以在发射瞬间把物品记下来.
     *
     * 注意: 箭飞进虚空 / 落在被卸载的区块里, ProjectileHitEvent 永远不会触发,
     * 那条记录就会一直留着. 这里额外记下写入时间, 由 [cleanupProjectiles] 定期清扫.
     */
    private val projectileItems = ConcurrentHashMap<UUID, Pair<ItemStack, Long>>()

    /** 发射物记录的最长保留时间: 30 秒, 比任何正常箭的飞行时间都长 */
    private const val PROJECTILE_TTL = 30_000L

    /** 每 30 秒异步清一次过期的发射物记录, 防止内存缓慢泄漏 */
    @Schedule(period = 600, async = true)
    fun cleanupProjectiles() {
        if (projectileItems.isEmpty()) return
        val deadline = System.currentTimeMillis() - PROJECTILE_TTL
        val before = projectileItems.size
        projectileItems.entries.removeIf { it.value.second < deadline }
        val removed = before - projectileItems.size
        if (removed > 0) {
            DebugUtil.log("Skill", "清理了 $removed 条未命中的发射物记录, 当前剩余 ${projectileItems.size} 条")
        }
    }

    @SubscribeEvent
    fun onInteract(e: PlayerInteractEvent) {
        if (e.hand != EquipmentSlot.HAND) return
        // 右键和潜行左键都属于物品使用类技能。三叉戟没有可靠的 AnimationEvent，
        // 因此额外监听 LEFT_CLICK，保证 Shift+左键切换类技能可触发。
        val isRight = e.action == Action.RIGHT_CLICK_AIR || e.action == Action.RIGHT_CLICK_BLOCK
        val isShiftLeft = e.player.isSneaking && (e.action == Action.LEFT_CLICK_AIR || e.action == Action.LEFT_CLICK_BLOCK)
        if (!isRight && !isShiftLeft) return
        val item = e.item ?: return
        val trigger = when {
            isShiftLeft -> "onShiftSwing"
            e.player.isSneaking -> "onShiftUse"
            else -> "onUse"
        }
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
    fun onProjectileLaunch(e: ProjectileLaunchEvent) {
        val shooter = e.entity.shooter as? Player ?: return
        val item = shooter.inventory.itemInMainHand
        if (item.type != Material.AIR) {
            projectileItems[e.entity.uniqueId] = item.clone() to System.currentTimeMillis()
        }
    }

    @SubscribeEvent
    fun onProjectileHit(e: ProjectileHitEvent) {
        val shooter = e.entity.shooter as? Player ?: return
        val item = projectileItems.remove(e.entity.uniqueId)?.first ?: shooter.inventory.itemInMainHand
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
        // CooldownTip 本身也是技能表达式，不是普通文本。
        // 部分配置经过 YAML 转义后会保留外层字符串，需要再次展开。
        var tip = SkillLineParser.parse(raw)
        if (tip.args.size == 1 && tip.args.keys.firstOrNull()?.contains("{") == true) {
            tip = SkillLineParser.parse(tip.args.keys.first())
        }
        val prefix = ColorUtil.colorize(tip.args["p"] ?: "")
        when (tip.name.lowercase()) {
            "actionbar" -> {
                val suffixTpl = ColorUtil.colorize(tip.args["s"] ?: "%.2fs")
                val empty = ColorUtil.colorize(tip.args["empty"] ?: "&f|")
                val full = ColorUtil.colorize(tip.args["full"] ?: "&a|")
                val length = (tip.args["length"]?.cint ?: 10).coerceAtLeast(1)
                val ratio = (1.0 - remaining / total).coerceIn(0.0, 1.0)
                // 进度条交给 TabooLib 的 createBar, 不再手写 repeat 拼接
                val bar = createBar(empty, full, length, ratio)
                val suffix = try {
                    String.format(suffixTpl, remaining)
                } catch (e: Exception) {
                    suffixTpl
                }
                player.sendActionBar(prefix + bar + suffix)
            }
            "chat" -> player.sendMessage(ColorUtil.colorize(tip.args["m"] ?: Lang.get("skill.cooldown-chat")))
            "title" -> player.sendTitle(ColorUtil.colorize(tip.args["m"] ?: Lang.get("skill.cooldown-title")), "", 10, 40, 10)
            else -> Lang.send(player, "skill.cooldown", "time" to String.format("%.2f", remaining))
        }
    }
}
