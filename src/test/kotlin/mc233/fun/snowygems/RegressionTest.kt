package mc233.`fun`.snowygems

import mc233.`fun`.snowygems.config.MenuRegistry
import mc233.`fun`.snowygems.manager.DismantleService
import mc233.`fun`.snowygems.manager.GemManager
import mc233.`fun`.snowygems.reward.AppliedReward
import mc233.`fun`.snowygems.reward.FunctionCall
import mc233.`fun`.snowygems.reward.LoreMutation
import mc233.`fun`.snowygems.reward.RewardHistory
import mc233.`fun`.snowygems.reward.RewardPhase
import mc233.`fun`.snowygems.reward.RewardTokenParser
import java.nio.ByteBuffer
import java.util.Base64
import kotlin.test.*

class RegressionTest {
    @Test fun `invalid layouts never reach Bukkit inventory creation`() {
        MenuRegistry.validateRows(listOf("TTTIGTTTT"))
        MenuRegistry.validateRows(List(6) { "TTTTTTTTT" })
        for (rows in listOf(emptyList(), List(7) { "TTTTTTTTT" }, listOf("short"), listOf("1234567890"))) {
            assertFailsWith<IllegalArgumentException> { MenuRegistry.validateRows(rows) }
        }
    }

    @Test fun `integer fees cannot truncate to free and invalid fees fail closed`() {
        assertEquals(1.0, DismantleService.normalizeCost("exp", 0.1))
        assertEquals(101.0, DismantleService.normalizeCost("points", 100.1))
        assertEquals(0.1, DismantleService.normalizeCost("money", 0.1))
        assertEquals(0.0, DismantleService.normalizeCost("money", 0.0))
        for (value in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(DismantleService.normalizeCost("money", value))
        }
        assertNull(DismantleService.normalizeCost("points", Int.MAX_VALUE.toDouble() + 1))
        assertNull(DismantleService.normalizeCost("typo", 10.0))
    }

    @Test fun `random pools ignore disabled weights and cannot overflow int totals`() {
        assertNull(GemManager.weightedPick(mapOf("disabled" to 0, "invalid" to -1)))
        repeat(30) {
            assertEquals("valid", GemManager.weightedPick(linkedMapOf("invalid" to -100, "valid" to 1)))
            assertTrue(GemManager.weightedPick(mapOf("a" to Int.MAX_VALUE, "b" to Int.MAX_VALUE)) in setOf("a", "b"))
        }
    }

    @Test fun `removal-only rewards are never recorded as application rewards`() {
        val removal = RewardTokenParser.parseLine("Enchant{name=unbreaking} ${'$'}onRemove")
        assertFalse(removal.matchesPhase(RewardPhase.APPLY))
        assertTrue(removal.matchesPhase(RewardPhase.REMOVE))
        val application = RewardTokenParser.parseLine("Enchant{name=unbreaking} ${'$'}onSuccess")
        assertTrue(application.matchesPhase(RewardPhase.APPLY))
        assertFalse(application.matchesPhase(RewardPhase.REMOVE))
    }

    @Test fun `history retains repeated rewards Unicode and actual changes`() {
        val records = listOf(
            AppliedReward(FunctionCall("Attribute", linkedMapOf("name" to "health", "var" to "v+1")), mapOf("delta" to "0.5")),
            AppliedReward(FunctionCall("LoreAdd", linkedMapOf("lore" to "§a右键即可闪现")), mapOf("loreIndex" to "2")),
            AppliedReward(FunctionCall("Attribute", linkedMapOf("name" to "health", "var" to "v+1")), mapOf("delta" to "1.0"))
        )
        val decoded = RewardHistory.decode(RewardHistory.encode(records))
        assertEquals(3, decoded.size)
        records.zip(decoded).forEach { (before, after) ->
            assertEquals(before.call.name, after.call.name)
            assertEquals(before.call.args, after.call.args)
            assertEquals(before.undoData, after.undoData)
        }
    }

    @Test fun `corrupt history refuses malformed version count truncation and trailing bytes`() {
        val bytes = Base64.getDecoder().decode(RewardHistory.encode(emptyList()))
        for (bad in listOf(bytes.copyOf(3), bytes + byteArrayOf(1),
            ByteBuffer.allocate(8).putInt(99).putInt(0).array(),
            ByteBuffer.allocate(8).putInt(1).putInt(-1).array(),
            ByteBuffer.allocate(8).putInt(1).putInt(5000).array())) {
            assertFails { RewardHistory.decode(Base64.getEncoder().encodeToString(bad)) }
        }
    }

    @Test fun `removing a rune preserves original and other runes lore`() {
        val before = listOf("原生说明", "已有技能")
        val after = before + "右键即可闪现"
        val record = LoreMutation.capture(before, after)
        val current = (listOf("其他插件插入") + after + "其他符文").toMutableList()
        assertTrue(LoreMutation.revert(current, record))
        assertEquals(listOf("其他插件插入", "原生说明", "已有技能", "其他符文"), current)
    }

    @Test fun `identical rune lines remove one occurrence and changed lines are preserved`() {
        val before = listOf("原生说明", "同名符文")
        val after = before + "同名符文"
        val current = after.toMutableList()
        assertTrue(LoreMutation.revert(current, LoreMutation.capture(before, after)))
        assertEquals(before, current)
        val replace = LoreMutation.capture(listOf("旧值"), listOf("新值"))
        val changed = mutableListOf("外部编辑后的值")
        assertFalse(LoreMutation.revert(changed, replace))
        assertEquals(listOf("外部编辑后的值"), changed)
    }
}
