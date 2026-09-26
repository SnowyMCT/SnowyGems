package mc233.`fun`.snowygems.manager

import mc233.`fun`.snowygems.config.GemConfig
import mc233.`fun`.snowygems.economy.MoneyEconomy
import mc233.`fun`.snowygems.economy.PointsEconomy
import mc233.`fun`.snowygems.util.ItemRequireMatcher
import mc233.`fun`.snowygems.util.Lang
import mc233.`fun`.snowygems.util.DebugUtil
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.releaseResourceFolder
import taboolib.common.platform.function.warning
import taboolib.module.configuration.Configuration
import java.io.File
import java.util.Locale
import kotlin.random.Random

/** Independent dismantle plans. Gems contain only their application effects. */
object DismantleService {
    @Volatile private var pricing: DismantlePricing? = null

    internal fun snapshot() = pricing
    internal fun restore(value: DismantlePricing?) { pricing = value }

    fun resolve() {
        val oldGlobal = File(getDataFolder(), "config.yml")
        if (oldGlobal.isFile && Configuration.loadFromFile(oldGlobal).contains("Dismantle")) {
            warning("config.yml 的旧 Dismantle 节点已忽略；请将价格与返还成功率配置在 dismantle/ 目录")
        }
        releaseResourceFolder("dismantle/", replace = false)
        val folder = File(getDataFolder(), "dismantle")
        val files = folder.listFiles { f -> f.isFile && f.extension.lowercase() in setOf("yml", "yaml") }
            ?.toList() ?: emptyList()
        pricing = DismantlePlanFiles.load(files)
    }

    fun quote(gem: GemConfig, item: ItemStack): DismantleQuote {
        val plan = pricing ?: error("拆卸方案尚未加载")
        val applied = GemManager.getAppliedGems(item)
        require(gem.id in applied) { "该装备没有此宝石" }
        val meta = item.itemMeta
        val damage = if (meta is Damageable && item.type.maxDurability > 0)
            meta.damage.toDouble() * 100.0 / item.type.maxDurability.toDouble() else 0.0
        val metrics = DismantleMetrics(applied.size, applied.count { it == gem.id },
            damage.coerceIn(0.0, 100.0), item.enchantments.size, meta?.isUnbreakable == true)
        val quote = plan.quote(gem.id, gem.category, metrics) { entries ->
            ItemRequireMatcher.matches(entries, item, meta?.lore ?: emptyList())
        }
        DebugUtil.log("Dismantle", "报价 gem=${gem.id} total=${metrics.total} same=${metrics.same} -> ${describe(quote.cost)}; 返还=${format(quote.success)}%")
        return quote
    }

    fun describe(cost: DismantleCost): String {
        val parts = ArrayList<String>(3)
        if (cost.money > 0) parts += "${format(cost.money)} ${Lang.get("dismantle.cost-money")}"
        if (cost.points > 0) parts += "${cost.points} ${Lang.get("dismantle.cost-points")}"
        if (cost.exp > 0) parts += "${cost.exp} ${Lang.get("dismantle.cost-exp")}"
        return if (parts.isEmpty()) Lang.get("dismantle.cost-free") else parts.joinToString(" + ")
    }

    fun format(value: Double): String = if (value == value.toLong().toDouble()) value.toLong().toString()
        else String.format(Locale.ROOT, "%.2f", value)

    internal fun charge(player: Player, cost: DismantleCost): DismantlePayment.Attempt =
        DismantlePayment.charge(cost, wallet(player)).also {
            DebugUtil.log("Dismantle", "${player.name} 扣费 ${describe(cost)} -> paid=${it.paid} rollbackFailed=${it.rollbackFailed}")
        }

    /** Called only after a successful charge when the item mutation fails. */
    fun refund(player: Player, cost: DismantleCost): Boolean = DismantlePayment.refund(cost, wallet(player)).also {
        DebugUtil.log("Dismantle", "${player.name} 退款 ${describe(cost)} -> $it")
    }

    private fun wallet(player: Player): DismantlePayment.Wallet = object : DismantlePayment.Wallet {
        override fun points() = PointsEconomy.get(player)
        override fun levels() = player.level
        override fun debitMoney(amount: Double) = MoneyEconomy.add(player, -amount)
        override fun debitPoints(amount: Int) = PointsEconomy.tryAdd(player, -amount.toDouble())
        override fun debitLevels(amount: Int): Boolean {
            if (player.level < amount) return false
            player.giveExpLevels(-amount)
            return true
        }
        override fun creditMoney(amount: Double) = MoneyEconomy.add(player, amount)
        override fun creditPoints(amount: Int) = PointsEconomy.tryAdd(player, amount.toDouble())
        override fun creditLevels(amount: Int): Boolean { player.giveExpLevels(amount); return true }
    }

    fun rollSuccess(quote: DismantleQuote): Boolean = Random.nextDouble(100.0) < quote.success
}
