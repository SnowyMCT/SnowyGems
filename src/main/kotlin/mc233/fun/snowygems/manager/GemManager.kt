package mc233.`fun`.snowygems.manager

import mc233.`fun`.snowygems.config.GemConfig
import mc233.`fun`.snowygems.config.GemRegistry
import mc233.`fun`.snowygems.config.GemType
import mc233.`fun`.snowygems.reward.RewardContext
import mc233.`fun`.snowygems.reward.RewardPhase
import mc233.`fun`.snowygems.reward.ParsedReward
import mc233.`fun`.snowygems.reward.AppliedReward
import mc233.`fun`.snowygems.reward.RewardHistory
import mc233.`fun`.snowygems.reward.impl.RewardFactory
import mc233.`fun`.snowygems.util.ColorUtil
import mc233.`fun`.snowygems.util.ItemFactory
import mc233.`fun`.snowygems.util.Lang
import mc233.`fun`.snowygems.util.ItemRequireMatcher
import mc233.`fun`.snowygems.util.DebugUtil
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.EquipmentSlot
import mc233.`fun`.snowygems.util.ItemTagData
import mc233.`fun`.snowygems.util.ItemTagList
import mc233.`fun`.snowygems.util.getItemTag
import kotlin.random.Random
import java.util.Base64

/** 一次镶嵌/强化/使用操作的结果, 供 GUI/命令层展示消息 */
data class ApplyResult(
    val success: Boolean,
    val message: String,
    val consumedGem: Boolean,
    val resultItem: ItemStack? = null
)

object GemManager {

    private const val APPLIED_LIST_KEY = "SnowyGemsAppliedGems"

    /** GUI 中 USE_GEM 按钮点击: 无视宝石声明的 Type, 直接以 [target](可为空) 为上下文执行一次 Rewards */
    fun executeButton(player: Player, cfg: GemConfig, target: ItemStack?): ApplyResult {
        if (target != null && !target.type.isAir && target.amount != 1) {
            return ApplyResult(false, Lang.get("gem.single-target"), false)
        }
        if (cfg.require.isNotEmpty() && (target == null || target.type.isAir ||
                !ItemRequireMatcher.matches(cfg.require, target, target.itemMeta?.lore ?: emptyList()))) {
            return ApplyResult(false, Lang.get("gem.require-failed"), false)
        }
        val success = rollSuccess(cfg.success)
        DebugUtil.log(
            "GemManager",
            "executeButton: 按钮宝石=${cfg.id} 目标物品=${target?.type ?: "无"} 成功率=${cfg.success}% 本次判定=$success"
        )
        val working = target?.clone()
        val ctx = RewardContext(player, working, cfg, RewardPhase.APPLY, success)
        if (success) {
            val execution = runRewards(ctx, cfg.parsedRewards, RewardPhase.APPLY)
            if (execution.succeeded == 0) return ApplyResult(false, Lang.get("gem.no-effect"), execution.errors > 0, target)
        }
        else DebugUtil.log("GemManager", "executeButton: 判定失败, 跳过全部 Rewards")
        val msg = if (success) {
            cfg.successTip?.let(::renderTip) ?: Lang.get("gem.button-success")
        } else {
            cfg.failTip?.let(::renderTip) ?: Lang.get("gem.button-fail")
        }
        return ApplyResult(success, msg, success, ctx.item)
    }

    /** 给予玩家指定数量的宝石物品 */
    fun give(player: Player, gemId: String, amount: Int = 1): Boolean {
        if (amount <= 0) return false
        val cfg = GemRegistry.get(gemId) ?: run {
            DebugUtil.log("GemManager", "give: 宝石配置不存在 $gemId (已加载=${GemRegistry.ids().size} 个)")
            return false
        }
        val item = ItemFactory.build(cfg, amount)
        val leftover = player.inventory.addItem(item)
        leftover.values.forEach { player.world.dropItem(player.location, it) }
        DebugUtil.log(
            "GemManager",
            "give: 给 ${player.name} 发放 $gemId x$amount, 背包放不下掉落 ${leftover.values.sumOf { it.amount }} 个"
        )
        return true
    }

    /** 预览与实际执行共享的无副作用校验。 */
    fun validateApply(gemStack: ItemStack, targetStack: ItemStack, menuName: String? = null): String? {
        if (gemStack.type.isAir || gemStack.amount <= 0) return Lang.get("gem.not-gem")
        val gemId = ItemFactory.getGemId(gemStack) ?: return Lang.get("gem.not-gem")
        val cfg = GemRegistry.get(gemId) ?: return Lang.get("gem.config-missing")
        if (cfg.type != GemType.NORMAL) return Lang.get("embed.wrong-type")
        if (cfg.gui.isNotEmpty() && menuName !in cfg.gui) {
            return Lang.get("embed.wrong-gui", "gui" to cfg.gui.joinToString(", "))
        }
        if (targetStack.type.isAir) return Lang.get("embed.need-equip")
        if (targetStack.amount != 1) return Lang.get("gem.single-target")
        if (ItemFactory.getGemId(targetStack) != null) return Lang.get("embed.equip-is-gem")
        if (!ItemRequireMatcher.matches(cfg.require, targetStack, targetStack.itemMeta?.lore ?: emptyList())) {
            return Lang.get("gem.require-failed")
        }
        return null
    }

    fun applyToItem(player: Player, gemStack: ItemStack, targetStack: ItemStack, menuName: String? = null): ApplyResult {
        validateApply(gemStack, targetStack, menuName)?.let { return ApplyResult(false, it, false) }
        val gemId = ItemFactory.getGemId(gemStack)
        DebugUtil.log("GemManager", "applyToItem: 手持物品读取到的 GemId=$gemId (材质=${gemStack.type})")
        if (gemId == null) return ApplyResult(false, Lang.get("gem.not-gem"), false)
        val cfg = GemRegistry.get(gemId) ?: return ApplyResult(false, Lang.get("gem.config-missing"), false)
        DebugUtil.log("GemManager", "applyToItem: 宝石配置 id=${cfg.id} type=${cfg.type} require=${cfg.require} embed=${cfg.embed}")

        val target = targetStack.clone()
        val loreLines = target.itemMeta?.lore?.let { ColorUtil.colorize(it) } ?: emptyList()
        if (!ItemRequireMatcher.matches(cfg.require, target, loreLines)) {
            DebugUtil.log("GemManager", "applyToItem: Require 不匹配, 目标材质=${target.type}, 目标lore=$loreLines")
            return ApplyResult(false, Lang.get("gem.require-failed"), false)
        }

        val success = rollSuccess(cfg.success)
        DebugUtil.log("GemManager", "applyToItem: Require 通过, 成功率=${cfg.success}% 本次判定=$success")
        val ctx = RewardContext(player, target, cfg, RewardPhase.APPLY, success)
        if (success) {
            val execution = runRewards(ctx, cfg.parsedRewards, RewardPhase.APPLY)
            // 概率判定成功, 但奖励一条都没真正生效(如附魔名解析失败) —— 不能假报成功,
            // 否则玩家会看到"镶嵌成功"却毫无变化. 此时不消耗宝石, 让玩家能重试/找管理员.
            if (execution.succeeded == 0) {
                DebugUtil.log("GemManager", "applyToItem: 奖励未生效, exceptions=${execution.errors}")
                return ApplyResult(false, Lang.get("gem.no-effect"), execution.errors > 0, targetStack.clone())
            }
            markApplied(ctx.item ?: target, cfg.id, execution.applied)
            DebugUtil.log("GemManager", "applyToItem: 镶嵌完成, 该装备现有宝石=${getAppliedGems(ctx.item ?: target)}")
            val msg = cfg.successTip?.let(::renderTip)
                ?: Lang.get("gem.embed-success")
            return ApplyResult(true, msg, true, ctx.item ?: target)
        } else {
            val msg = cfg.failTip?.let(::renderTip) ?: Lang.get("gem.embed-fail")
            return ApplyResult(false, msg, true, target)
        }
    }

    /** 先预留使用的那一颗，再发奖，避免礼包抽中自身时覆盖新发放的物品。调用方不得再次扣减。 */
    fun useHeld(player: Player, hand: EquipmentSlot = EquipmentSlot.HAND): ApplyResult {
        require(hand == EquipmentSlot.HAND || hand == EquipmentSlot.OFF_HAND) { "Only hand slots can use gems" }
        val inventory = player.inventory
        val slot = if (hand == EquipmentSlot.HAND) inventory.heldItemSlot else 40
        val held = inventory.getItem(slot)?.clone()
            ?: return ApplyResult(false, Lang.get("gem.not-gem"), false)
        if (held.type.isAir || held.amount <= 0) return ApplyResult(false, Lang.get("gem.not-gem"), false)
        val gemId = ItemFactory.getGemId(held) ?: return ApplyResult(false, Lang.get("gem.not-gem"), false)
        val cfg = GemRegistry.get(gemId) ?: return ApplyResult(false, Lang.get("gem.config-missing"), false)
        if (cfg.type == GemType.NORMAL) return ApplyResult(false, Lang.get("gem.need-workbench"), false)
        val reserved = held.clone().apply { amount = 1 }
        inventory.setItem(slot, held.takeIf { it.amount > 1 }?.apply { amount-- })
        val result = try {
            useDirectly(player, reserved)
        } catch (e: Exception) {
            // 未知异常可能发生在外部插件已经发奖之后，不能返还凭证制造重复奖励。
            DebugUtil.err("GemManager", "使用宝石 $gemId 发生异常", e)
            ApplyResult(false, Lang.get("gem.no-effect"), true)
        }
        if (!result.consumedGem) {
            val current = inventory.getItem(slot)
            if (current == null || current.type.isAir) inventory.setItem(slot, reserved)
            else if (current.isSimilar(reserved) && current.amount < current.maxStackSize) {
                inventory.setItem(slot, current.clone().apply { amount++ })
            } else {
                inventory.addItem(reserved).values.forEach { player.world.dropItem(player.location, it) }
            }
        }
        return result
    }

    /** 直接执行消费品逻辑；常规交互应使用 useHeld，让核心负责预留物品。 */
    fun useDirectly(player: Player, gemStack: ItemStack): ApplyResult {
        val gemId = ItemFactory.getGemId(gemStack)
        DebugUtil.log("GemManager", "useDirectly: 手持物品读取到的 GemId=$gemId (材质=${gemStack.type})")
        if (gemId == null) return ApplyResult(false, Lang.get("gem.not-gem"), false)
        val cfg = GemRegistry.get(gemId) ?: return ApplyResult(false, Lang.get("gem.config-missing"), false)

        if (cfg.type == GemType.NORMAL) return ApplyResult(false, Lang.get("gem.need-workbench"), false)
        if (!rollSuccess(cfg.success)) {
            return ApplyResult(false, cfg.failTip?.let(::renderTip) ?: Lang.get("gem.use-fail"), true)
        }

        DebugUtil.log("GemManager", "useDirectly: 宝石 ${cfg.id} 类型=${cfg.type} 成功率=${cfg.success}%")
        return when (cfg.type) {
            GemType.RANDOM_GEM -> {
                val picked = weightedPick(cfg.randomPool) ?: return ApplyResult(false, Lang.get("gem.pool-empty"), false)
                DebugUtil.log("GemManager", "useDirectly: 随机奖池 ${cfg.randomPool} 抽中 $picked")
                val subCfg = GemRegistry.get(picked) ?: return ApplyResult(false, Lang.get("gem.pool-invalid"), false)
                if (cfg.randomGiveItem) {
                    // 礼包模式(GiveItem=true): 把抽中的子宝石以物品形式放进背包;
                    // 背包放不下则在身边找安全地面(无岩浆/仙人掌/水)掉落, 并提示玩家
                    val item = ItemFactory.build(subCfg, 1)
                    val leftover = player.inventory.addItem(item)
                    val gemName = subCfg.display.ifBlank { subCfg.name }
                    if (leftover.isEmpty()) {
                        DebugUtil.log("GemManager", "useDirectly: 礼包(${cfg.id})抽中 $picked 已放入背包")
                        ApplyResult(true, Lang.get("gem.random-get", "gem" to gemName), true)
                    } else {
                        val loc = findSafeDropLocation(player)
                        if (loc != null) {
                            leftover.values.forEach { player.world.dropItem(loc, it) }
                            DebugUtil.log("GemManager", "useDirectly: 礼包(${cfg.id})抽中 $picked 背包已满, 掉落在 $loc")
                            ApplyResult(true, Lang.get("gem.inventory-full", "gem" to gemName), true)
                        } else {
                            // 周围确实没有安全地面: 不消耗礼包, 让玩家清理背包/换个位置再开
                            DebugUtil.log("GemManager", "useDirectly: 礼包(${cfg.id})抽中 $picked 背包已满且周围无安全落点, 不消耗礼包")
                            ApplyResult(false, Lang.get("gem.no-safe-spot"), false)
                        }
                    }
                } else {
                    // 旧行为: 当场执行子宝石的 Rewards (随机点券券等直接到账类随机宝石)
                    val ctx = RewardContext(player, null, subCfg, RewardPhase.APPLY, true)
                    val execution = runRewards(ctx, subCfg.parsedRewards, RewardPhase.APPLY)
                    if (execution.succeeded == 0) return ApplyResult(false, Lang.get("gem.no-effect"), execution.errors > 0)
                    val msg = subCfg.successTip?.let(::renderTip)
                        ?: Lang.get("gem.random-get", "gem" to subCfg.display.ifBlank { subCfg.name })
                    ApplyResult(true, msg, true)
                }
            }
            GemType.PLAYER_GEM -> {
                val success = true
                val ctx = RewardContext(player, null, cfg, RewardPhase.APPLY, success)
                val execution = runRewards(ctx, cfg.parsedRewards, RewardPhase.APPLY)
                if (execution.succeeded == 0) return ApplyResult(false, Lang.get("gem.no-effect"), execution.errors > 0)
                val msg = if (success) {
                    cfg.successTip?.let(::renderTip) ?: Lang.get("gem.use-success")
                } else {
                    cfg.failTip?.let(::renderTip) ?: Lang.get("gem.use-fail")
                }
                ApplyResult(success, msg, true)
            }
            GemType.NORMAL -> ApplyResult(false, Lang.get("gem.need-workbench"), false)
        }
    }

    /**
     * 从目标物品上拆除一个已应用的宝石.
     *
     * 与旧版的关键区别:
     *   - 旧版只跑 $onRemove 奖励(几乎没有宝石写了), 于是属性/附魔根本没被撤销 -> "拆了跟没拆一样".
     *   - 新版真正调用每条 Reward 的 [Reward.revert] 撤销其对装备造成的效果(移除属性修饰符/降附魔),
     *     再跑一遍配置里显式写的 $onRemove 奖励(如给回材料).
     *
     * 费用与损坏由 config.yml 的 Dismantle 一节控制, 已在调用方 [DismantleService] 处理,
     * 这里只负责"把宝石从装备上摘掉并撤销其效果".
     */
    fun removeFromItem(player: Player, targetStack: ItemStack, gemId: String): ApplyResult {
        if (targetStack.type.isAir || targetStack.amount != 1) {
            return ApplyResult(false, Lang.get("gem.single-target"), false)
        }
        if (gemId !in getAppliedGems(targetStack)) return ApplyResult(false, Lang.get("gem.not-applied"), false)
        DebugUtil.log("GemManager", "removeFromItem: 从 ${targetStack.type} 拆除 $gemId, 拆除前已镶嵌=${getAppliedGems(targetStack)}")
        val cfg = GemRegistry.get(gemId) ?: return ApplyResult(false, Lang.get("gem.config-missing"), false)
        val target = targetStack.clone()
        val ctx = RewardContext(player, target, cfg, RewardPhase.REMOVE, true)
        val history = target.getItemTag()[historyKey(gemId)]?.value as? ItemTagList
        val encoded = history?.lastOrNull()?.asString()
        val applied = if (!encoded.isNullOrEmpty()) {
            runCatching { RewardHistory.decode(encoded) }.getOrElse {
                DebugUtil.err("GemManager", "宝石 $gemId 的撤销记录损坏，拒绝拆卸", it)
                return ApplyResult(false, Lang.get("gem.no-effect"), false)
            }
        } else {
            // 老物品没有执行历史，只兼容撤销 APPLY 奖励，绝不能撤销 onRemove。
            cfg.parsedRewards.filter { it.matchesPhase(RewardPhase.APPLY) && it.reward != null }
                .map { AppliedReward(it.call, emptyMap()) }
        }
        // 倒序撤销本颗宝石实际成功的奖励，用实际增量保留其他宝石贡献。
        var reverted = 0
        for (record in applied.asReversed()) {
            val reward = RewardFactory.create(record.call) ?: continue
            ctx.undoData.clear()
            ctx.undoData.putAll(record.undoData)
            try {
                if (reward.revert(ctx)) reverted++
            } catch (e: Exception) {
                DebugUtil.err("GemManager", "撤销奖励 ${record.call.name} 失败，保留原物品", e)
                return ApplyResult(false, Lang.get("gem.no-effect"), false)
            }
        }
        // 2) 再跑配置里显式标了 $onRemove 的奖励(如返还部分材料)
        runRewards(ctx, cfg.parsedRewards, RewardPhase.REMOVE)
        // 3) 从 NBT 已镶嵌列表里摘掉
        unmarkApplied(ctx.item ?: target, gemId)
        DebugUtil.log("GemManager", "removeFromItem: 撤销了 $reverted 条奖励效果, 拆除后已镶嵌=${getAppliedGems(ctx.item ?: target)}")
        val msg = cfg.removeTip?.let(::renderTip) ?: Lang.get("gem.remove-success")
        return ApplyResult(true, msg, false, ctx.item ?: target)
    }

    /** 读取一件装备上已记录的所有宝石ID */
    fun getAppliedGems(item: ItemStack): List<String> {
        val tag = item.getItemTag()
        val list = tag[APPLIED_LIST_KEY]?.value as? ItemTagList ?: return emptyList()
        return list.mapNotNull { it.asString() }
    }

    /** 返回一件装备上已应用宝石的展示信息(供 GUI 使用) */
    fun getAppliedGemConfigs(item: ItemStack): List<GemConfig> =
        getAppliedGems(item).mapNotNull { GemRegistry.get(it) }

    private fun historyKey(gemId: String): String = "SnowyGemsHistory_" +
        Base64.getUrlEncoder().withoutPadding().encodeToString(gemId.toByteArray(Charsets.UTF_8))

    private fun markApplied(item: ItemStack, gemId: String, applied: List<AppliedReward>) {
        val tag = item.getItemTag()
        val list = (tag[APPLIED_LIST_KEY]?.value as? ItemTagList) ?: ItemTagList()
        val history = (tag[historyKey(gemId)]?.value as? ItemTagList) ?: ItemTagList()
        // 为旧版已有的同 ID 宝石补一个兼容占位，保持每颗记录与撤销历史对齐。
        repeat((list.count { it.asString() == gemId } - history.size).coerceAtLeast(0)) {
            history.add(ItemTagData(""))
        }
        history.add(ItemTagData(RewardHistory.encode(applied)))
        list.add(ItemTagData(gemId))
        tag[historyKey(gemId)] = history
        tag[APPLIED_LIST_KEY] = list
        tag.saveTo(item)
    }

    private fun unmarkApplied(item: ItemStack, gemId: String) {
        val tag = item.getItemTag()
        val list = (tag[APPLIED_LIST_KEY]?.value as? ItemTagList) ?: return
        // 仅删除最后一次镶嵌，与下方历史记录的 dropLast(1) 保持一致。
        val removeIndex = list.indexOfLast { it.asString() == gemId }
        if (removeIndex < 0) return
        val kept = ItemTagList()
        for ((index, data) in list.withIndex()) {
            if (index != removeIndex) kept.add(data)
        }
        tag[APPLIED_LIST_KEY] = kept
        val key = historyKey(gemId)
        val history = tag[key]?.value as? ItemTagList
        if (history != null) {
            if (history.size <= 1) tag.remove(key)
            else tag[key] = ItemTagList().apply { addAll(history.dropLast(1)) }
        }
        tag.saveTo(item)
    }

    /**
     * 执行匹配当前阶段的 Rewards(奖励行已在注册表加载时预解析).
     * @return Pair(attempted, succeeded): attempted=尝试执行的奖励条数(已识别且匹配阶段),
     *         succeeded=其中 apply() 返回 true 的条数. 供调用方判断"是否真的产生了效果".
     */
    private data class RewardExecution(val succeeded: Int, val errors: Int, val applied: List<AppliedReward>)

    private fun runRewards(ctx: RewardContext, parsedRewards: List<ParsedReward>, phase: RewardPhase): RewardExecution {
        DebugUtil.log("Reward", "开始执行 ${ctx.gem.id} 的 Rewards, 阶段=$phase 共 ${parsedRewards.size} 行")
        var attempted = 0
        var succeeded = 0
        var skipped = 0
        var errors = 0
        val applied = mutableListOf<AppliedReward>()
        for (parsed in parsedRewards) {
            if (!parsed.matchesPhase(phase)) {
                skipped++
                DebugUtil.log("Reward", "  跳过 ${parsed.call.name}: 标记=${parsed.flags} 不匹配当前阶段 $phase")
                continue
            }
            val reward = parsed.reward ?: continue
            val before = ctx.item?.clone()
            ctx.undoData.clear()
            try {
                val ok = reward.apply(ctx)
                attempted++
                if (ok) {
                    succeeded++
                    applied += AppliedReward(parsed.call, ctx.undoData.toMap())
                } else {
                    ctx.item = before
                }
                DebugUtil.log("Reward", "  ${parsed.call.name} 参数=${parsed.call.args} -> $ok")
            } catch (e: Exception) {
                attempted++
                errors++
                ctx.item = before
                DebugUtil.err("Reward", "  执行 ${parsed.call.name} 失败", e)
            }
        }
        DebugUtil.log("Reward", "Rewards 执行完毕: 尝试 $attempted 条, 生效 $succeeded 条, 阶段不匹配跳过 $skipped 条")
        return RewardExecution(succeeded, errors, applied)
    }

    internal fun renderTip(value: String): String =
        if (value.equals("none", true)) "none:" else ColorUtil.colorize(value)

    private fun rollSuccess(chance: Int): Boolean {
        if (chance >= 100) return true
        if (chance <= 0) return false
        return Random.nextInt(100) < chance
    }

    internal fun weightedPick(pool: Map<String, Int>): String? {
        val total = pool.values.sumOf { it.coerceAtLeast(0).toLong() }
        if (total <= 0) return null
        var roll = Random.nextLong(total)
        for ((k, w) in pool) {
            if (w <= 0) continue
            if (roll < w) return k
            roll -= w
        }
        return null
    }

    /** 掉落物会被销毁/烧毁/冲走的方块类型: 岩浆(烧毁物品)、仙人掌(销毁物品)、水(冲走物品) */
    private fun isHazardous(mat: Material): Boolean =
        mat == Material.LAVA || mat == Material.WATER || mat == Material.CACTUS

    /** 落点/头顶格可通行: 非实体方块, 且不是岩浆/水 */
    private fun isClear(block: Block): Boolean =
        !block.type.isSolid && !isHazardous(block.type)

    /**
     * 以玩家为中心、半径 4 格的圆形范围, 由近到远找一个安全掉落点:
     *  - 落点正下方必须有实体方块支撑, 且该方块不是岩浆/仙人掌(水不是实体方块, 天然不满足支撑);
     *  - 落点格与头顶格必须可通行且不含岩浆/水, 保证掉落物实体不会落进危险方块;
     *  - 玩家脚下一层找不到支撑面时向下最多探 6 格(玩家站在低空/浮空时也能落回最近地面).
     * 找不到返回 null, 由调用方提示玩家清理背包或换个位置再开.
     */
    private fun findSafeDropLocation(player: Player): Location? {
        val world = player.world
        val blockX = player.location.blockX
        val blockZ = player.location.blockZ
        val feetY = player.location.blockY
        val radius = 4
        val spots = ArrayList<IntArray>()
        for (dx in -radius..radius) {
            for (dz in -radius..radius) {
                if (dx * dx + dz * dz > radius * radius) continue // 只搜圆形范围, 不搜四个远角
                spots.add(intArrayOf(dx, dz))
            }
        }
        spots.sortBy { it[0] * it[0] + it[1] * it[1] } // 近处优先
        for (spot in spots) {
            val x = blockX + spot[0]
            val z = blockZ + spot[1]
            for (down in 0..6) {
                val groundY = feetY - down
                if (groundY < world.minHeight) break
                val ground = world.getBlockAt(x, groundY, z)
                if (!ground.type.isSolid || isHazardous(ground.type)) continue
                val stand = world.getBlockAt(x, groundY + 1, z)
                val head = world.getBlockAt(x, groundY + 2, z)
                if (isClear(stand) && isClear(head)) {
                    return Location(world, x + 0.5, groundY + 1.0, z + 0.5)
                }
            }
        }
        return null
    }

}
