package mc233.`fun`.snowygems

import mc233.`fun`.snowygems.reward.RewardTokenParser
import mc233.`fun`.snowygems.reward.impl.pointRewardAmount
import mc233.`fun`.snowygems.util.ExprUtil
import kotlin.test.*

class PointRewardTest {
    @Test fun `logged random payout becomes a valid integer transaction`() {
        assertEquals(1696, pointRewardAmount(1696.5883017572096))
        assertEquals(500, pointRewardAmount(500.0))
        assertEquals(-1696, pointRewardAmount(-1696.5883017572096))
    }

    @Test fun `legacy random expression yields payable points`() {
        val call = RewardTokenParser.parseLine("Point{amount=100+2900*\$RANDOM()}").call
        repeat(1000) {
            val evaluated = ExprUtil.eval(call.arg("amount", "0"))
            val amount = assertNotNull(pointRewardAmount(evaluated))
            assertTrue(amount in 100..2999, "Out of random payout range: $evaluated -> $amount")
            assertTrue(amount.toDouble() <= evaluated && evaluated < amount + 1.0)
        }
    }

    @Test fun `small random values written with an exponent keep their scale`() {
        assertEquals(101.45, ExprUtil.eval("100+2900*5.0E-4"), 1e-10)
        assertEquals(100, pointRewardAmount(ExprUtil.eval("100+2900*1.0E-7")))
        assertEquals(100.0, ExprUtil.eval("100+2900*0.0"))
        assertEquals(3000.0, ExprUtil.eval("100+2900*1.0"))
        assertEquals(2010.0, ExprUtil.eval("2e+3+1E1"))
        assertEquals(-0.001, ExprUtil.eval("-1e-3"))
    }

    @Test fun `empty invalid and overflowing payouts are rejected before conversion`() {
        listOf(0.0, 0.9, -0.9, Double.NaN, Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY, Double.MAX_VALUE, -Double.MAX_VALUE,
            Int.MAX_VALUE.toDouble() + 1, Int.MIN_VALUE.toDouble()).forEach {
            assertNull(pointRewardAmount(it), "Must reject $it")
        }
        assertEquals(Int.MAX_VALUE, pointRewardAmount(Int.MAX_VALUE.toDouble()))
        assertEquals(-Int.MAX_VALUE, pointRewardAmount(-Int.MAX_VALUE.toDouble()))
    }
}
