package mc233.`fun`.snowygems.manager

import mc233.`fun`.snowygems.economy.MoneyEconomy
import mc233.`fun`.snowygems.economy.PointsEconomy
import mc233.`fun`.snowygems.util.DebugUtil
import org.bukkit.entity.Player
import taboolib.module.configuration.Config
import taboolib.module.configuration.Configuration
import kotlin.random.Random

/**
 * 拆卸宝石的费用 & 损坏规则.
 *
 * 全部由 config.yml 的 Dismantle 一节配置:
 *   - 费用类型: money(金币) / points(点券) / exp(经验等级)
 *   - 费用金额
 *   - 损坏概率: 拆卸时有概率不返还宝石(宝石损坏)
 *
 * 拆卸流程(由 [DismantleService.tryDismantle] 编排):
 *   1. 检查并扣除费用 —— 余额不足直接拒绝, 不动装备
 *   2. GemManager.removeFromItem 撤销属性/附魔, 把宝石从装备摘掉
 *   3. 掷骰子判定宝石是否损坏 —— 未损坏则返还宝石实体, 损坏则不返还
 */
object DismantleService {

    @Config(value = "config.yml", autoReload = true, migrate = true)
    lateinit var conf: Configuration

    // ── 配置字段(resolve 时读取) ─────────────────────────────
    /** 拆卸是否需要费用 */
    private var costEnabled = true
    /** money / points / exp */
    private var costType = "money"
    /** 费用数额 */
    private var costAmount = 100.0
    /** 宝石损坏概率(0~100), 0=永不损坏 */
    private var breakChance = 20

    fun resolve() {
        if (!::conf.isInitialized) return
        costEnabled = conf.getBoolean("Dismantle.Cost.Enabled", true)
        costType = (conf.getString("Dismantle.Cost.Type", "money") ?: "money").lowercase()
        costAmount = conf.getDouble("Dismantle.Cost.Amount", 100.0)
        breakChance = conf.getInt("Dismantle.BreakChance", 20).coerceIn(0, 100)
        DebugUtil.log("Dismantle", "配置就绪: 需费用=$costEnabled 类型=$costType 数额=$costAmount 损坏概率=$breakChance%")
    }

    /** 费用类型的中文名, 供提示展示 */
    fun costTypeName(): String = when (costType) {
        "points", "point" -> "点券"
        "exp", "explevel", "level" -> "经验等级"
        else -> "金币"
    }

    fun costEnabled() = costEnabled
    fun costAmount() = costAmount
    fun breakChance() = breakChance

    /** 玩家余额是否够拆卸费用 */
    fun canAfford(player: Player): Boolean {
        if (!costEnabled || costAmount <= 0) return true
        return when (costType) {
            "points", "point" -> PointsEconomy.get(player) >= costAmount
            "exp", "explevel", "level" -> player.level >= costAmount.toInt()
            else -> {
                // Money 没有可靠的"只查询"接口, 用扣0再补的方式不优雅; 直接尝试扣, 失败即余额不足.
                // 这里改为在实际扣费时判定, canAfford 对 money 返回 true, 由 charge 负责真实校验.
                true
            }
        }
    }

    /**
     * 扣除拆卸费用.
     * @return 是否扣费成功(余额不足返回 false)
     */
    fun charge(player: Player): Boolean {
        if (!costEnabled || costAmount <= 0) return true
        return when (costType) {
            "points", "point" -> {
                if (PointsEconomy.get(player) < costAmount) return false
                PointsEconomy.add(player, -costAmount)
                DebugUtil.log("Dismantle", "扣除 ${player.name} $costAmount 点券")
                true
            }
            "exp", "explevel", "level" -> {
                if (player.level < costAmount.toInt()) return false
                player.giveExpLevels(-costAmount.toInt())
                DebugUtil.log("Dismantle", "扣除 ${player.name} ${costAmount.toInt()} 级经验")
                true
            }
            else -> {
                // MoneyEconomy.add(负数) 走 withdrawBalance, 余额不足会返回 false
                val ok = MoneyEconomy.add(player, -costAmount)
                DebugUtil.log("Dismantle", "扣除 ${player.name} $costAmount 金币 -> $ok")
                ok
            }
        }
    }

    /** 掷骰子: true=宝石损坏(不返还) */
    fun rollBreak(): Boolean {
        if (breakChance <= 0) return false
        if (breakChance >= 100) return true
        return Random.nextInt(100) < breakChance
    }
}
