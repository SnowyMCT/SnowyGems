package mc233.`fun`.snowygems.manager

/** Keeps mixed-currency debits and compensating refunds in one ordered operation. */
internal object DismantlePayment {
    interface Wallet {
        fun points(): Double
        fun levels(): Int
        fun debitMoney(amount: Double): Boolean
        fun debitPoints(amount: Int): Boolean
        fun debitLevels(amount: Int): Boolean
        fun creditMoney(amount: Double): Boolean
        fun creditPoints(amount: Int): Boolean
        fun creditLevels(amount: Int): Boolean
    }

    data class Attempt(val paid: Boolean, val rollbackFailed: Boolean = false)

    fun canAfford(cost: DismantleCost, wallet: Wallet): Boolean =
        wallet.levels() >= cost.exp && (cost.points == 0 || wallet.points() >= cost.points)

    fun charge(cost: DismantleCost, wallet: Wallet): Attempt {
        if (!canAfford(cost, wallet)) return Attempt(false)
        if (cost.money > 0 && !safe { wallet.debitMoney(cost.money) }) return Attempt(false)
        if (cost.points > 0 && !safe { wallet.debitPoints(cost.points) }) {
            val restored = cost.money == 0.0 || safe { wallet.creditMoney(cost.money) }
            return Attempt(false, !restored)
        }
        if (cost.exp > 0 && !safe { wallet.debitLevels(cost.exp) }) {
            val pointsRestored = cost.points == 0 || safe { wallet.creditPoints(cost.points) }
            val moneyRestored = cost.money == 0.0 || safe { wallet.creditMoney(cost.money) }
            return Attempt(false, !pointsRestored || !moneyRestored)
        }
        return Attempt(true)
    }

    fun refund(cost: DismantleCost, wallet: Wallet): Boolean {
        val levels = cost.exp == 0 || safe { wallet.creditLevels(cost.exp) }
        val points = cost.points == 0 || safe { wallet.creditPoints(cost.points) }
        val money = cost.money == 0.0 || safe { wallet.creditMoney(cost.money) }
        return levels && points && money
    }

    private inline fun safe(action: () -> Boolean): Boolean = runCatching(action).getOrDefault(false)
}
