package mc233.`fun`.snowygems

import mc233.`fun`.snowygems.rune.*
import kotlin.random.Random
import kotlin.test.*

class RuneCraftTest {
    private fun recipe(amount: Int = 1, ingredients: Map<String, Int> = mapOf("shard" to 24), result: String = "rune") =
        RuneRecipe("craft", "Craft", result, amount, ingredients)

    @Test fun `missing materials never produce a partial plan`() {
        val slots = listOf(CraftSlot("shard", 20), CraftSlot(null, 0))
        val result = RuneCraftPlanner.plan(slots, recipe(), 64)
        assertNull(result.plan)
        assertEquals(mapOf("shard" to 4), result.missing)
        assertEquals(20, slots[0].amount)
    }

    @Test fun `full inventory uses slot freed by consuming exact materials`() {
        val result = RuneCraftPlanner.plan(listOf(CraftSlot("shard", 24), CraftSlot(null, 64)), recipe(), 64)
        assertEquals(listOf(0, 64), result.plan!!.remaining)
        assertEquals(listOf(1, 0), result.plan.added)
    }

    @Test fun `full inventory with partially consumed stack fails without deduction`() {
        val result = RuneCraftPlanner.plan(listOf(CraftSlot("shard", 25), CraftSlot(null, 64)), recipe(), 64)
        assertTrue(result.noSpace)
        assertNull(result.plan)
    }

    @Test fun `small ingredient stack is used to make room first`() {
        val slots = listOf(CraftSlot("shard", 64), CraftSlot("shard", 24), CraftSlot(null, 64))
        val plan = RuneCraftPlanner.plan(slots, recipe(), 1).plan!!
        assertEquals(listOf(64, 0, 64), plan.remaining)
        assertEquals(listOf(0, 1, 0), plan.added)
    }

    @Test fun `multiple ingredients aggregate across stacks but exclude untagged items`() {
        val cfg = recipe(ingredients = mapOf("shard" to 20, "rune-1" to 3))
        val slots = listOf(CraftSlot("shard", 12), CraftSlot("shard", 8), CraftSlot("rune-1", 2), CraftSlot("rune-1", 1), CraftSlot(null, 64))
        val plan = RuneCraftPlanner.plan(slots, cfg, 64).plan!!
        assertEquals(listOf(0, 0, 0, 0, 64), plan.remaining)
        assertEquals(1, plan.added.sum())
        val forged = slots.map { if (it.gem == "rune-1") it.copy(gem = null) else it }
        assertEquals(mapOf("rune-1" to 3), RuneCraftPlanner.plan(forged, cfg, 64).missing)
    }

    @Test fun `result merges only into metadata compatible stacks`() {
        val slots = listOf(CraftSlot("shard", 24), CraftSlot("rune", 63, true), CraftSlot("rune", 1, false))
        val plan = RuneCraftPlanner.plan(slots, recipe(3), 64).plan!!
        assertEquals(listOf(2, 1, 0), plan.added)
        assertEquals(1, plan.remaining[2])
    }

    @Test fun `large outputs split without exceeding stack size`() {
        val slots = listOf(CraftSlot("shard", 24), CraftSlot(null, 0), CraftSlot(null, 0))
        val plan = RuneCraftPlanner.plan(slots, recipe(130), 64).plan!!
        assertEquals(listOf(64, 64, 2), plan.added)
        assertTrue(RuneCraftPlanner.plan(slots.take(2), recipe(130), 64).noSpace)
    }

    @Test fun `result can equal material without losing unspent items`() {
        val slots = listOf(CraftSlot("shard", 40, true))
        val plan = RuneCraftPlanner.plan(slots, recipe(10, result = "shard"), 64).plan!!
        assertEquals(26, plan.remaining.single() + plan.added.single())
    }

    @Test fun `zero negative and missing material costs cannot create free recipes`() {
        assertFailsWith<IllegalArgumentException> { recipe(0) }
        assertFailsWith<IllegalArgumentException> { recipe(ingredients = emptyMap()) }
        assertFailsWith<IllegalArgumentException> { recipe(ingredients = mapOf("shard" to 0)) }
        assertFailsWith<IllegalArgumentException> { recipe(ingredients = mapOf("shard" to -1)) }
        assertFailsWith<IllegalArgumentException> { recipe(4097) }
    }

    @Test fun `randomized successful plans conserve materials output and unrelated items`() {
        val random = Random(546)
        repeat(300) {
            val slots = List(36) {
                when (random.nextInt(4)) {
                    0 -> CraftSlot("shard", random.nextInt(1, 65))
                    1 -> CraftSlot("rune", random.nextInt(1, 65), true)
                    2 -> CraftSlot(null, 0)
                    else -> CraftSlot(null, 64)
                }
            }
            val cfg = recipe(random.nextInt(1, 129), mapOf("shard" to random.nextInt(1, 129)))
            val plan = RuneCraftPlanner.plan(slots, cfg, 64).plan ?: return@repeat
            assertEquals(cfg.amount, plan.added.sum())
            assertEquals(cfg.ingredients.getValue("shard"), slots.indices.sumOf { i -> slots[i].amount - plan.remaining[i] })
            slots.indices.forEach { i ->
                assertTrue(plan.remaining[i] >= 0 && plan.remaining[i] + plan.added[i] <= 64)
                if (slots[i].gem != "shard") assertEquals(slots[i].amount, plan.remaining[i])
                if (plan.added[i] > 0) assertTrue(plan.remaining[i] == 0 || slots[i].acceptsOutput)
            }
        }
    }
}
