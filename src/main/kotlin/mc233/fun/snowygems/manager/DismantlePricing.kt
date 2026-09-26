package mc233.`fun`.snowygems.manager

import taboolib.library.configuration.ConfigurationSection
import taboolib.module.configuration.Configuration
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode

data class DismantleMetrics(
    val total: Int,
    val same: Int,
    val damage: Double,
    val enchants: Int,
    val unbreakable: Boolean
)

data class DismantleCost(val money: Double, val points: Int, val exp: Int) {
    fun isFree() = money == 0.0 && points == 0 && exp == 0
}

data class DismantleQuote(
    val cost: DismantleCost,
    val success: Double,
    val rarity: Int,
    val rules: List<String>
)

internal data class DismantleFormulas(
    val money: DismantleFormula?,
    val points: DismantleFormula?,
    val exp: DismantleFormula?,
    val success: DismantleFormula?
)

internal data class DismantleRule(
    val id: String,
    val priority: Int,
    val gemIds: Set<String>,
    val categories: Set<String>,
    val equipment: List<String>,
    val rarity: Int?,
    val formulas: DismantleFormulas
) {
    fun matches(gemId: String, category: String, equipmentMatches: (List<String>) -> Boolean): Boolean =
        (gemIds.isEmpty() || gemId in gemIds) &&
        (categories.isEmpty() || categories.any { it.equals(category, true) }) &&
        (equipment.isEmpty() || equipmentMatches(equipment))
}

/** Rules are layered by ascending priority; each currency and success formula can be overridden separately. */
class DismantlePricing internal constructor(
    private val defaultRarity: Int,
    private val defaults: DismantleFormulas,
    private val rules: List<DismantleRule>
) {
    fun quote(gemId: String, category: String, metrics: DismantleMetrics,
              equipmentMatches: (List<String>) -> Boolean): DismantleQuote {
        var rarity = defaultRarity
        var money = defaults.money ?: error("缺少默认 Money")
        var points = defaults.points ?: error("缺少默认 Points")
        var exp = defaults.exp ?: error("缺少默认 Exp")
        var success = defaults.success ?: error("缺少默认 Success")
        val applied = mutableListOf<String>()
        for (rule in rules) {
            if (!rule.matches(gemId, category, equipmentMatches)) continue
            applied += rule.id
            rule.rarity?.let { rarity = it }
            rule.formulas.money?.let { money = it }
            rule.formulas.points?.let { points = it }
            rule.formulas.exp?.let { exp = it }
            rule.formulas.success?.let { success = it }
        }
        val vars = mapOf(
            "rarity" to rarity.toDouble(), "total" to metrics.total.toDouble(),
            "same" to metrics.same.toDouble(), "damage" to metrics.damage,
            "enchants" to metrics.enchants.toDouble(), "unbreakable" to if (metrics.unbreakable) 1.0 else 0.0
        )
        val rawMoney = money.evaluate(vars)
        val rawPoints = points.evaluate(vars)
        val rawExp = exp.evaluate(vars)
        val rawSuccess = success.evaluate(vars)
        require(listOf(rawMoney, rawPoints, rawExp, rawSuccess).all { it.isFinite() }) { "拆卸计算结果不是有限数字" }
        require(rawMoney in 0.0..1.0e12 && rawPoints in 0.0..Int.MAX_VALUE.toDouble() &&
            rawExp in 0.0..Int.MAX_VALUE.toDouble()) { "拆卸价格不能为负数或超出上限" }
        fun decimal(value: Double) = BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_UP)
        val chargedMoney = decimal(rawMoney).setScale(2, RoundingMode.CEILING).toDouble()
        return DismantleQuote(
            DismantleCost(chargedMoney, decimal(rawPoints).setScale(0, RoundingMode.CEILING).toInt(),
                decimal(rawExp).setScale(0, RoundingMode.CEILING).toInt()),
            rawSuccess.coerceIn(0.0, 100.0), rarity, applied
        )
    }
}

object DismantlePlanFiles {
    fun load(files: List<File>): DismantlePricing {
        var defaultRarity: Int? = null
        var defaults: DismantleFormulas? = null
        val rules = mutableListOf<DismantleRule>()
        val ids = mutableSetOf<String>()
        for (file in files.sortedBy { it.path }) {
            require(file.isFile) { "拆卸方案文件不存在: ${file.path}" }
            try {
                val cfg = Configuration.loadFromFile(file)
                if (!cfg.getBoolean("Enabled", true)) continue
                require(cfg.getKeys(false).all { it in setOf("Enabled", "Default", "Rules") }) { "拆卸文件含未知顶层字段" }
                cfg.getConfigurationSection("Default")?.let { sec ->
                    require(defaults == null) { "只能定义一个 Default" }
                    require(sec.getKeys(false).all { it in setOf("Rarity", "Cost", "Success") }) { "Default 含未知字段" }
                    defaultRarity = rarity(sec, required = true)
                    defaults = formulas(sec, required = true)
                }
                if (cfg.contains("Rules")) require(cfg.getConfigurationSection("Rules") != null) { "Rules 必须是配置节" }
                cfg.getConfigurationSection("Rules")?.let { section ->
                    for (id in section.getKeys(false)) {
                        val sec = section.getConfigurationSection(id) ?: error("$id 必须是配置节")
                        if (!sec.getBoolean("Enabled", true)) continue
                        require(ids.add(id)) { "重复规则 ID: $id" }
                        require(sec.getKeys(false).all { it in setOf("Enabled", "Priority", "Match", "Rarity", "Cost", "Success") }) { "$id 含未知字段" }
                        val match = sec.getConfigurationSection("Match")
                        require(match == null || match.getKeys(false).all { it in setOf("GemIds", "Categories", "Equipment") }) { "$id.Match 含未知字段" }
                        val priority = sec.get("Priority")?.toString()?.toIntOrNull()
                            ?: if (sec.contains("Priority")) error("$id.Priority 必须是整数") else 0
                        rules += DismantleRule(
                            id, priority,
                            readList(match, "GemIds").toSet(),
                            readList(match, "Categories").toSet(),
                            readList(match, "Equipment"),
                            rarity(sec, required = false), formulas(sec, required = false)
                        )
                    }
                }
            } catch (e: Exception) {
                throw IllegalArgumentException("${file.name}: ${e.message}", e)
            }
        }
        require(defaults != null && defaultRarity != null) { "dismantle/ 中必须定义一个 Default 方案" }
        return DismantlePricing(defaultRarity!!, defaults!!, rules.sortedWith(compareBy<DismantleRule> { it.priority }.thenBy { it.id }))
    }

    private fun rarity(sec: ConfigurationSection, required: Boolean): Int? {
        if (!sec.contains("Rarity")) {
            require(!required) { "Default 缺少 Rarity" }
            return null
        }
        val value = sec.get("Rarity")?.toString()?.toIntOrNull() ?: error("Rarity 必须是整数")
        require(value in 0..100) { "Rarity 须为 0..100" }
        return value
    }

    private fun formulas(sec: ConfigurationSection, required: Boolean): DismantleFormulas {
        val cost = sec.getConfigurationSection("Cost")
        if (required) require(cost != null) { "Default 缺少 Cost" }
        require(cost == null || cost.getKeys(false).all { it in setOf("Money", "Points", "Exp") }) { "Cost 含未知币种字段" }
        fun read(section: ConfigurationSection?, key: String): DismantleFormula? {
            val value = section?.get(key)?.toString()
            if (required) require(!value.isNullOrBlank()) { "Default 缺少 Cost.$key" }
            return value?.let { DismantleFormula.compile(it) }
        }
        val successValue = sec.get("Success")?.toString()
        if (required) require(!successValue.isNullOrBlank()) { "Default 缺少 Success" }
        return DismantleFormulas(read(cost, "Money"), read(cost, "Points"), read(cost, "Exp"),
            successValue?.let { DismantleFormula.compile(it) })
    }

    private fun readList(sec: ConfigurationSection?, key: String): List<String> {
        if (sec == null || !sec.contains(key)) return emptyList()
        val raw = sec.get(key) as? List<*> ?: error("Match.$key 必须是列表")
        val values = raw.map { it?.toString()?.trim() ?: "" }
        require(values.isNotEmpty() && values.all { it.isNotEmpty() }) { "Match.$key 不能为空" }
        return values
    }
}
