package mc233.`fun`.snowygems.skill

import mc233.`fun`.snowygems.config.SkillRegistry
import mc233.`fun`.snowygems.util.ColorUtil
import mc233.`fun`.snowygems.util.DebugUtil
import mc233.`fun`.snowygems.util.Lang
import org.bukkit.Material
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.player.PlayerAnimationEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemDamageEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import taboolib.common.platform.Schedule
import taboolib.common.platform.event.SubscribeEvent
import taboolib.common5.cint
import taboolib.common5.util.createBar
import taboolib.platform.util.PlayerSessionMap
import taboolib.platform.util.sendActionBar

/**
 * 技能触发器 —— 把游戏事件翻译成触发标记(trigger), 交给 [SkillExecutor] 执行
 *
 * 服主在技能行末尾用 `~触发标记` 声明这一行响应什么事件, 当前支持:
 *
 *   onUse          右键使用
 *   onShiftUse     潜行 + 右键
 *   onSwing        左键挥臂
 *   onShiftSwing   潜行 + 左键(三叉戟等没有可靠 AnimationEvent 的物品也能用)
 *   onAttack       近战攻击命中实体
 *   onKill         击杀实体
 *   onHit:类型     发射物命中, 类型为实体类型名(ARROW / TRIDENT / SPEAR / EGG …)
 *   onHit          发射物命中(不限类型)
 *   onLaunch:类型  发射物射出
 *   onLaunch       发射物射出(不限类型)
 *   onBreak        挖掉方块
 *   onDamaged      物品即将损失耐久
 *   onTimer        每秒轮询(由 BuffEngine 驱动)
 *
 * 多版本要点: 发射物类型不写死枚举, 直接取 `entity.type.name`这样 1.21.11 的矛(SPEAR)
 * 投出去后, 服主写 `~onHit:SPEAR` 就能用, 引擎不需要认识"矛"这个概念
 */
object SkillTriggerListener {

    /** 冷却表按玩家会话存放, 玩家退出后由 TabooLib 自动清理, 不会随时间无限增长 */
    private val cooldowns = PlayerSessionMap<MutableMap<String, Long>>({ mutableMapOf() })

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

    // ── 手部动作 ────────────────────────────────────────────

    @SubscribeEvent
    fun onInteract(e: PlayerInteractEvent) {
        if (e.hand != EquipmentSlot.HAND) return
        // 右键和潜行左键都属于物品使用类技能三叉戟/矛没有可靠的 AnimationEvent,
        // 因此额外监听 LEFT_CLICK, 保证 Shift+左键切换类技能可触发
        val isRight = e.action == Action.RIGHT_CLICK_AIR || e.action == Action.RIGHT_CLICK_BLOCK
        val isShiftLeft = e.player.isSneaking && (e.action == Action.LEFT_CLICK_AIR || e.action == Action.LEFT_CLICK_BLOCK)
        if (!isRight && !isShiftLeft) return
        val item = e.item ?: return
        val trigger = when {
            isShiftLeft -> "onShiftSwing"
            e.player.isSneaking -> "onShiftUse"
            else -> "onUse"
        }
        DebugUtil.log("Skill", "${e.player.name} 触发 $trigger, 手持=${item.type}")
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

    // ── 战斗 ────────────────────────────────────────────────

    @SubscribeEvent
    fun onAttack(e: EntityDamageByEntityEvent) {
        val player = e.damager as? Player ?: return
        val item = player.inventory.itemInMainHand
        if (item.type == Material.AIR) return
        DebugUtil.log("Skill", "${player.name} 攻击 ${e.entity.type} 触发 onAttack, 手持=${item.type}")
        runMatchingSkills(player, item, "onAttack", victim = e.entity as? LivingEntity)
    }

    /** 击杀触发: 让服主能做"击杀回血""击杀掉落"这类技能 */
    @SubscribeEvent
    fun onKill(e: EntityDeathEvent) {
        val killer = e.entity.killer ?: return
        val item = killer.inventory.itemInMainHand
        if (item.type == Material.AIR) return
        DebugUtil.log("Skill", "${killer.name} 击杀 ${e.entity.type} 触发 onKill, 手持=${item.type}")
        runMatchingSkills(killer, item, "onKill", victim = e.entity)
    }

    // ── 发射物 ──────────────────────────────────────────────

    @SubscribeEvent
    fun onProjectileLaunch(e: ProjectileLaunchEvent) {
        val shooter = e.entity.shooter as? Player ?: return
        val item = shooter.inventory.itemInMainHand
        if (item.type == Material.AIR) return
        projectileItems[e.entity.uniqueId] = item.clone() to System.currentTimeMillis()
        // 发射瞬间也是一个可用的触发点(蓄力完成/消耗弹药/播放音效)
        val typeName = e.entity.type.name
        runMatchingSkills(shooter, item, "onLaunch:$typeName")
        runMatchingSkills(shooter, item, "onLaunch")
    }

    @SubscribeEvent
    fun onProjectileHit(e: ProjectileHitEvent) {
        val shooter = e.entity.shooter as? Player ?: return
        val item = projectileItems.remove(e.entity.uniqueId)?.first ?: shooter.inventory.itemInMainHand
        if (item.type == Material.AIR) return
        // 类型名直接取自实体注册表, 因此新版本的新发射物(如 1.21.11 的矛)自动可用
        val typeName = e.entity.type.name
        val hitLoc = e.hitBlock?.location ?: e.hitEntity?.location ?: e.entity.location
        DebugUtil.log("Skill", "${shooter.name} 的 $typeName 命中, 手持=${item.type}")
        runMatchingSkills(shooter, item, "onHit:$typeName", victim = e.hitEntity as? LivingEntity, hitLocation = hitLoc)
        // 不带类型的通用命中标记
        runMatchingSkills(shooter, item, "onHit", victim = e.hitEntity as? LivingEntity, hitLocation = hitLoc)
        // 兼容老配置里写过的 onTridentHit
        if (typeName == "TRIDENT") {
            runMatchingSkills(shooter, item, "onTridentHit", hitLocation = hitLoc)
        }
    }

    // ── 其它 ────────────────────────────────────────────────

    /** 挖方块触发: 可做"挖矿几率额外掉落""挖掘时加速" */
    @SubscribeEvent
    fun onBreak(e: BlockBreakEvent) {
        val item = e.player.inventory.itemInMainHand
        if (item.type == Material.AIR) return
        runMatchingSkills(e.player, item, "onBreak", hitLocation = e.block.location)
    }

    /** 物品即将损失耐久: 可做"几率不掉耐久" */
    @SubscribeEvent
    fun onItemDamage(e: PlayerItemDamageEvent) {
        val item = e.item
        if (item.type == Material.AIR) return
        runMatchingSkills(e.player, item, "onDamaged")
    }

    // ── 匹配与执行 ──────────────────────────────────────────

    private fun runMatchingSkills(
        player: Player,
        item: ItemStack,
        trigger: String,
        victim: LivingEntity? = null,
        hitLocation: org.bukkit.Location? = null
    ) {
        val lore = item.itemMeta?.lore ?: return
        val strippedLore = lore.map { ColorUtil.stripColor(it).trim() }
        var matched = 0
        // 只遍历带 Lore 标记的定义(加载时已缓存); 技能行已按触发标记分组, O(1) 取用
        for (def in SkillRegistry.withLore()) {
            val marker = def.loreClean
            if (marker.isEmpty() || strippedLore.none { it.contains(marker) }) continue
            val relevant = def.byTrigger[trigger].orEmpty()
            if (relevant.isEmpty()) continue
            matched++
            DebugUtil.log(
                "Skill",
                "  命中技能定义 ${def.id} (Lore标记=${def.lore}), ${relevant.size}/${def.skills.size} 行响应 $trigger"
            )
            val remaining = checkAndSetCooldown(player, def.id, def.cooldown)
            if (remaining != null) {
                DebugUtil.log("Skill", "  技能 ${def.id} 仍在冷却中(${String.format("%.2f", remaining)}s), 跳过")
                def.cooldownTip?.let { renderCooldownTip(player, it, remaining, def.cooldown) }
                continue
            }
            for (line in relevant) {
                SkillExecutor.execute(player, item, line, victim, hitLocation, trigger)
            }
        }
        if (matched == 0) {
            DebugUtil.logChanged(
                "Skill", "nomatch:${player.uniqueId}:${item.type}:$trigger",
                "  ${item.type} 的 Lore 没有响应 $trigger 的技能定义 (已加载 ${SkillRegistry.all().size} 个定义, 相同内容不再重复输出)"
            )
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
        // CooldownTip 本身也是技能表达式, 不是普通文本
        // 部分配置经过 YAML 转义后会保留外层字符串, 需要再次展开
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
