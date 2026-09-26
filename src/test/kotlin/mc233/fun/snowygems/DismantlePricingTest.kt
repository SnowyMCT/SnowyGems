package mc233.`fun`.snowygems

import mc233.`fun`.snowygems.manager.DismantleFormula
import mc233.`fun`.snowygems.manager.DismantleFormulas
import mc233.`fun`.snowygems.manager.DismantleMetrics
import mc233.`fun`.snowygems.manager.DismantlePricing
import mc233.`fun`.snowygems.manager.DismantleRule
import mc233.`fun`.snowygems.manager.DismantleCost
import mc233.`fun`.snowygems.manager.DismantlePayment
import kotlin.test.*

class DismantlePricingTest {
    private fun f(value: String) = DismantleFormula.compile(value)
    private val metrics = DismantleMetrics(total = 3, same = 2, damage = 40.0, enchants = 2, unbreakable = false)

    @Test fun `each currency and success rate is calculated independently`() {
        val plan = DismantlePricing(1, DismantleFormulas(
            f("100 + rarity*10 + total*5"), f("same*100 + 0.1"), f("damage/100 + 0.1"),
            f("100 - rarity*5 - enchants*10")
        ), emptyList())
        val quote = plan.quote("gem", "Rare", metrics) { false }
        assertEquals(125.0, quote.cost.money)
        assertEquals(201, quote.cost.points)
        assertEquals(1, quote.cost.exp)
        assertEquals(75.0, quote.success)
    }

    @Test fun `rules layer per field and select by gem category and equipment`() {
        val defaults = DismantleFormulas(f("100"), f("0"), f("0"), f("80"))
        val rules = listOf(
            DismantleRule("rare", 10, setOf("gem"), emptySet(), emptyList(), 4,
                DismantleFormulas(null, f("rarity*100"), null, null)),
            DismantleRule("armor", 20, emptySet(), setOf("Rare"), listOf("ARMOR"), null,
                DismantleFormulas(f("total*25"), null, f("enchants"), f("60")))
        )
        val plan = DismantlePricing(1, defaults, rules)
        val matched = plan.quote("gem", "Rare", metrics) { it == listOf("ARMOR") }
        assertEquals(75.0, matched.cost.money)
        assertEquals(400, matched.cost.points)
        assertEquals(2, matched.cost.exp)
        assertEquals(60.0, matched.success)
        assertEquals(listOf("rare", "armor"), matched.rules)
        val other = plan.quote("other", "Rare", metrics) { false }
        assertEquals(100.0, other.cost.money)
        assertEquals(0, other.cost.points)
    }

    @Test fun `invalid and negative prices fail closed while success is clamped`() {
        assertFailsWith<IllegalArgumentException> { f("unknown + 1") }
        assertFailsWith<IllegalArgumentException> { f("1 +") }
        val negative = DismantlePricing(1, DismantleFormulas(f("-1"), f("0"), f("0"), f("100")), emptyList())
        assertFailsWith<IllegalArgumentException> { negative.quote("gem", "Rare", metrics) { false } }
        val divide = DismantlePricing(1, DismantleFormulas(f("1/0"), f("0"), f("0"), f("100")), emptyList())
        assertFailsWith<IllegalArgumentException> { divide.quote("gem", "Rare", metrics) { false } }
        val chance = DismantlePricing(1, DismantleFormulas(f("0.001"), f("0.1"), f("0.1"), f("150")), emptyList())
            .quote("gem", "Rare", metrics) { false }
        assertEquals(0.01, chance.cost.money)
        assertEquals(1, chance.cost.points)
        assertEquals(1, chance.cost.exp)
        assertEquals(100.0, chance.success)
        assertEquals(12.0, f("clamp(max(2, rarity*3), 0, min(12, 20))")
            .evaluate(mapOf("rarity" to 5.0)))
    }

    private class Wallet : DismantlePayment.Wallet {
        var money = 1000.0
        var pointsBalance = 1000.0
        var level = 20
        var failPoints = false
        var failMoneyRefund = false
        override fun points() = pointsBalance
        override fun levels() = level
        override fun debitMoney(amount: Double): Boolean {
            if (money < amount) return false
            money -= amount; return true
        }
        override fun debitPoints(amount: Int): Boolean {
            if (failPoints || pointsBalance < amount) return false
            pointsBalance -= amount; return true
        }
        override fun debitLevels(amount: Int): Boolean {
            if (level < amount) return false
            level -= amount; return true
        }
        override fun creditMoney(amount: Double): Boolean {
            if (failMoneyRefund) return false
            money += amount; return true
        }
        override fun creditPoints(amount: Int): Boolean { pointsBalance += amount; return true }
        override fun creditLevels(amount: Int): Boolean { level += amount; return true }
    }

    @Test fun `mixed payment charges and refunds every currency`() {
        val wallet = Wallet()
        val cost = DismantleCost(100.0, 200, 3)
        assertTrue(DismantlePayment.charge(cost, wallet).paid)
        assertEquals(900.0, wallet.money)
        assertEquals(800.0, wallet.pointsBalance)
        assertEquals(17, wallet.level)
        assertTrue(DismantlePayment.refund(cost, wallet))
        assertEquals(1000.0, wallet.money)
        assertEquals(1000.0, wallet.pointsBalance)
        assertEquals(20, wallet.level)
    }

    @Test fun `later currency failure rolls back earlier debit and reports failed compensation`() {
        val wallet = Wallet().apply { failPoints = true }
        val cost = DismantleCost(100.0, 200, 3)
        assertEquals(DismantlePayment.Attempt(false), DismantlePayment.charge(cost, wallet))
        assertEquals(1000.0, wallet.money)
        wallet.failMoneyRefund = true
        assertEquals(DismantlePayment.Attempt(false, rollbackFailed = true), DismantlePayment.charge(cost, wallet))
        assertEquals(900.0, wallet.money)
    }
}
