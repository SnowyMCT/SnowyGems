package mc233.`fun`.snowygems.manager

import mc233.`fun`.snowygems.config.GemConfig
import mc233.`fun`.snowygems.config.GemRegistry
import mc233.`fun`.snowygems.config.GemType
import mc233.`fun`.snowygems.reward.RewardContext
import mc233.`fun`.snowygems.reward.RewardPhase
import mc233.`fun`.snowygems.reward.RewardTokenParser
import mc233.`fun`.snowygems.reward.impl.RewardFactory
import mc233.`fun`.snowygems.util.ColorUtil
import mc233.`fun`.snowygems.util.ItemFactory
import mc233.`fun`.snowygems.util.Lang
import mc233.`fun`.snowygems.util.ItemRequireMatcher
import mc233.`fun`.snowygems.util.DebugUtil
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import taboolib.module.nms.ItemTagData
import taboolib.module.nms.ItemTagList
import taboolib.module.nms.getItemTag
import kotlin.random.Random

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
        val success = rollSuccess(cfg.success)
        DebugUtil.log(
            "GemManager",
            "executeButton: 按钮宝石=${cfg.id} 目标物品=${target?.type ?: "无"} 成功率=${cfg.success}% 本次判定=$success"
        )
        val working = target?.clone()
        val ctx = RewardContext(player, working, cfg, RewardPhase.APPLY, success)
        if (success) runRewards(ctx, cfg.rewards, RewardPhase.APPLY)
        else DebugUtil.log("GemManager", "executeButton: 判定失败, 跳过全部 Rewards")
        val msg = if (success) {
            cfg.successTip?.takeIf { it != "none" }?.let { ColorUtil.colorize(it) } ?: Lang.get("gem.button-success")
        } else {
            cfg.failTip?.let { ColorUtil.colorize(it) } ?: Lang.get("gem.button-fail")
        }
        return ApplyResult(success, msg, true, ctx.item)
    }

    /** 给予玩家指定数量的宝石物品 */
    fun give(player: Player, gemId: String, amount: Int = 1): Boolean {
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

    fun applyToItem(player: Player, gemStack: ItemStack, targetStack: ItemStack): ApplyResult {
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
            val (attempted, succeeded) = runRewards(ctx, cfg.rewards, RewardPhase.APPLY)
            // 概率判定成功, 但奖励一条都没真正生效(如附魔名解析失败) —— 不能假报成功,
            // 否则玩家会看到"镶嵌成功"却毫无变化. 此时不消耗宝石, 让玩家能重试/找管理员.
            if (attempted > 0 && succeeded == 0) {
                DebugUtil.log("GemManager", "applyToItem: 判定成功但 $attempted 条奖励全部未生效, 视为失败并保留宝石")
                return ApplyResult(false, Lang.get("gem.no-effect"), false, target)
            }
            markApplied(ctx.item ?: target, cfg.id)
            DebugUtil.log("GemManager", "applyToItem: 镶嵌完成, 该装备现有宝石=${getAppliedGems(ctx.item ?: target)}")
            val msg = cfg.successTip?.takeIf { it != "none" }?.let { ColorUtil.colorize(it) }
                ?: Lang.get("gem.embed-success")
            return ApplyResult(true, msg, true, ctx.item ?: target)
        } else {
            val msg = cfg.failTip?.let { ColorUtil.colorize(it) } ?: Lang.get("gem.embed-fail")
            return ApplyResult(false, msg, true, target)
        }
    }

    /** 玩家直接右键使用 PlayerGem / RandomGem (兑换券/药水/消耗品) */
    fun useDirectly(player: Player, gemStack: ItemStack): ApplyResult {
        val gemId = ItemFactory.getGemId(gemStack)
        DebugUtil.log("GemManager", "useDirectly: 手持物品读取到的 GemId=$gemId (材质=${gemStack.type})")
        if (gemId == null) return ApplyResult(false, Lang.get("gem.not-gem"), false)
        val cfg = GemRegistry.get(gemId) ?: return ApplyResult(false, Lang.get("gem.config-missing"), false)

        DebugUtil.log("GemManager", "useDirectly: 宝石 ${cfg.id} 类型=${cfg.type} 成功率=${cfg.success}%")
        return when (cfg.type) {
            GemType.RANDOM_GEM -> {
                val picked = weightedPick(cfg.randomPool) ?: return ApplyResult(false, Lang.get("gem.pool-empty"), false)
                DebugUtil.log("GemManager", "useDirectly: 随机奖池 ${cfg.randomPool} 抽中 $picked")
                val subCfg = GemRegistry.get(picked) ?: return ApplyResult(false, Lang.get("gem.pool-invalid"), false)
                val ctx = RewardContext(player, null, subCfg, RewardPhase.APPLY, true)
                runRewards(ctx, subCfg.rewards, RewardPhase.APPLY)
                val msg = subCfg.successTip?.takeIf { it != "none" }?.let { ColorUtil.colorize(it) }
                    ?: Lang.get("gem.random-get", "gem" to subCfg.display.ifBlank { subCfg.name })
                ApplyResult(true, msg, true)
            }
            GemType.PLAYER_GEM -> {
                val success = rollSuccess(cfg.success)
                val ctx = RewardContext(player, null, cfg, RewardPhase.APPLY, success)
                if (success) runRewards(ctx, cfg.rewards, RewardPhase.APPLY)
                val msg = if (success) {
                    cfg.successTip?.takeIf { it != "none" }?.let { ColorUtil.colorize(it) } ?: Lang.get("gem.use-success")
                } else {
                    cfg.failTip?.let { ColorUtil.colorize(it) } ?: Lang.get("gem.use-fail")
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
        DebugUtil.log("GemManager", "removeFromItem: 从 ${targetStack.type} 拆除 $gemId, 拆除前已镶嵌=${getAppliedGems(targetStack)}")
        val cfg = GemRegistry.get(gemId) ?: return ApplyResult(false, Lang.get("gem.config-missing"), false)
        val target = targetStack.clone()
        val ctx = RewardContext(player, target, cfg, RewardPhase.REMOVE, true)
        // 1) 撤销该宝石所有奖励对装备造成的效果(属性/附魔)
        var reverted = 0
        for (raw in cfg.rewards) {
            if (raw.isBlank()) continue
            val parsed = runCatching { RewardTokenParser.parseLine(raw) }.getOrNull() ?: continue
            val reward = RewardFactory.create(parsed.call) ?: continue
            runCatching { if (reward.revert(ctx)) reverted++ }
                .onFailure { DebugUtil.err("GemManager", "撤销奖励 ${parsed.call.name} 失败", it) }
        }
        // 2) 再跑配置里显式标了 $onRemove 的奖励(如返还部分材料)
        runRewards(ctx, cfg.rewards, RewardPhase.REMOVE)
        // 3) 从 NBT 已镶嵌列表里摘掉
        unmarkApplied(ctx.item ?: target, gemId)
        DebugUtil.log("GemManager", "removeFromItem: 撤销了 $reverted 条奖励效果, 拆除后已镶嵌=${getAppliedGems(ctx.item ?: target)}")
        val msg = cfg.removeTip?.takeIf { it != "none" }?.let { ColorUtil.colorize(it) } ?: Lang.get("gem.remove-success")
        return ApplyResult(true, msg, false, ctx.item ?: target)
    }

    /** 读取一件装备上已记录的所有宝石ID */
    fun getAppliedGems(item: ItemStack): List<String> {
        val tag = item.getItemTag()
        val list = tag[APPLIED_LIST_KEY] as? ItemTagList ?: return emptyList()
        return list.mapNotNull { it.asString() }
    }

    /** 返回一件装备上已应用宝石的展示信息(供 GUI 使用) */
    fun getAppliedGemConfigs(item: ItemStack): List<GemConfig> =
        getAppliedGems(item).mapNotNull { GemRegistry.get(it) }

    private fun markApplied(item: ItemStack, gemId: String) {
        val tag = item.getItemTag()
        val list = (tag[APPLIED_LIST_KEY] as? ItemTagList) ?: ItemTagList()
        if (list.none { it.asString() == gemId }) {
            list.add(ItemTagData(gemId))
        }
        tag[APPLIED_LIST_KEY] = list
        tag.saveTo(item)
    }

    private fun unmarkApplied(item: ItemStack, gemId: String) {
        val tag = item.getItemTag()
        val list = (tag[APPLIED_LIST_KEY] as? ItemTagList) ?: return
        // ⚠️ ItemTagList 底层是 CopyOnWriteArrayList, 其迭代器不支持 remove(),
        //   直接 list.removeAll { } / removeIf { } 会抛 UnsupportedOperationException。
        //   所以重建一个新列表, 只保留不等于 gemId 的项, 整体替换。
        val kept = ItemTagList()
        for (data in list) {
            if (data.asString() != gemId) kept.add(data)
        }
        tag[APPLIED_LIST_KEY] = kept
        tag.saveTo(item)
    }

    /**
     * 执行匹配当前阶段的 Rewards.
     * @return Pair(attempted, succeeded): attempted=尝试执行的奖励条数(已识别且匹配阶段),
     *         succeeded=其中 apply() 返回 true 的条数. 供调用方判断"是否真的产生了效果".
     */
    private fun runRewards(ctx: RewardContext, rewards: List<String>, phase: RewardPhase): Pair<Int, Int> {
        DebugUtil.log("Reward", "开始执行 ${ctx.gem.id} 的 Rewards, 阶段=$phase 共 ${rewards.size} 行")
        var attempted = 0
        var succeeded = 0
        var skipped = 0
        for (raw in rewards) {
            if (raw.isBlank()) continue
            val parsed = try {
                RewardTokenParser.parseLine(raw)
            } catch (e: Exception) {
                DebugUtil.err("Reward", "解析奖励行失败: $raw", e)
                continue
            }
            if (!parsed.matchesPhase(phase)) {
                skipped++
                DebugUtil.log("Reward", "  跳过 ${parsed.call.name}: 标记=${parsed.flags} 不匹配当前阶段 $phase")
                continue
            }
            val reward = RewardFactory.create(parsed.call)
            if (reward == null) {
                DebugUtil.log("Reward", "  未识别的奖励函数: ${parsed.call.name} 参数=${parsed.call.args} (原始配置行: $raw)")
                continue
            }
            try {
                val ok = reward.apply(ctx)
                attempted++
                if (ok) succeeded++
                DebugUtil.log("Reward", "  ${parsed.call.name} 参数=${parsed.call.args} -> $ok")
            } catch (e: Exception) {
                attempted++
                DebugUtil.err("Reward", "  执行 ${parsed.call.name} 失败", e)
            }
        }
        DebugUtil.log("Reward", "Rewards 执行完毕: 尝试 $attempted 条, 生效 $succeeded 条, 阶段不匹配跳过 $skipped 条")
        return attempted to succeeded
    }

    private fun rollSuccess(chance: Int): Boolean {
        if (chance >= 100) return true
        if (chance <= 0) return false
        return Random.nextInt(100) < chance
    }

    private fun weightedPick(pool: Map<String, Int>): String? {
        val total = pool.values.sum()
        if (total <= 0) return null
        var roll = Random.nextInt(total)
        for ((k, w) in pool) {
            if (roll < w) return k
            roll -= w
        }
        return pool.keys.lastOrNull()
    }

}
