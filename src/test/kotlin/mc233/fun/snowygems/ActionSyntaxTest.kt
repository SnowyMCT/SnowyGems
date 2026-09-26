package mc233.`fun`.snowygems

import mc233.`fun`.snowygems.config.ActionSyntax
import mc233.`fun`.snowygems.reward.RewardPhase
import mc233.`fun`.snowygems.reward.impl.RewardFactory
import kotlin.test.*

class ActionSyntaxTest {
    @Test fun `command reward accepts readable and existing potion gem keys`() {
        val modern = ActionSyntax.reward(mapOf(
            "action" to "Command", "command" to "bc 恭喜 %player_name%", "as" to "console"
        ))
        val existing = ActionSyntax.reward(mapOf(
            "action" to "Command", "commands" to "bc 恭喜 %player_name%", "console" to "true"
        ))
        assertNotNull(RewardFactory.create(modern.call))
        assertNotNull(RewardFactory.create(existing.call))
        assertNull(RewardFactory.create(ActionSyntax.reward(mapOf("action" to "Command")).call))
    }

    @Test fun `structured and legacy entries have identical runtime meaning`() {
            val rewards = listOf(
                mapOf("action" to "Attribute", "name" to "health", "operation" to 0, "slot" to "auto", "var" to "v+1", "limit" to 10),
                mapOf("action" to "Point", "amount" to -1000, "phase" to "remove"),
                "Attribute{name=health;operation=0;slot=auto;var=v+1;limit=10}"
            ).map(ActionSyntax::reward)
            assertEquals(rewards[0].call.name, rewards[2].call.name)
            assertEquals(rewards[0].call.args, rewards[2].call.args)
            assertTrue(rewards[1].matchesPhase(RewardPhase.REMOVE))
            assertFalse(rewards[1].matchesPhase(RewardPhase.APPLY))
            val skills = listOf(
                mapOf("action" to "PotionBuff", "type" to "SLOW_FALLING", "trigger" to "onTimer"),
                "PotionBuff{type=SLOW_FALLING} ~onTimer"
            ).map(ActionSyntax::skill)
            assertEquals(skills[0], skills[1])
            assertEquals(rewards[0].call.args, ActionSyntax.editorReward("Attribute name=health, operation=0, slot=auto, var=v+1, limit=10").call.args)
            assertFailsWith<IllegalArgumentException> { ActionSyntax.editorReward("Attribute name") }
    }

    @Test fun `nested skill actions round trip through readable YAML objects`() {
        val examples = listOf(
            "Chance{p=0.1;Potion{type=HASTE;level=2;duration=8}} ~onBreak",
            "If{c=y<62;All{Potion{type=water_breathing};Potion{type=conduit_power}}} ~onTimer",
            "Chance{p=0.35;All{Velocity{up=0.6};Sound{name=ENTITY_WIND_CHARGE_WIND_BURST}}} ~onAttack",
            "Reward{Chat{m=&e跳跃之蛋!}} ~onHit:EGG",
            "Repeat{times=3;interval=6;All{Damage{amount=4};Particle{name=SWEEP_ATTACK;count=8}}} ~onAttack @Entity",
            "RewardSwitch{Enchant{name=SILK_TOUCH;level=restore}}"
        )
        for (raw in examples) {
            val old = ActionSyntax.skill(raw)
            val yaml = ActionSyntax.skillMap(old)
            assertTrue("then" in yaml, raw)
            assertEquals(old, ActionSyntax.skill(yaml), raw)
        }
        val grouped = ActionSyntax.skillMap(ActionSyntax.skill(examples[1]))
        val branch = grouped["then"] as Map<*, *>
        assertEquals("All", branch["action"])
        assertEquals(2, (branch["then"] as List<*>).size)
        val switch = ActionSyntax.skill("Switch{s=精准;时运} ~onShiftUse")
        val switchMap = ActionSyntax.skillMap(switch)
        assertEquals("精准", switchMap["from"])
        assertEquals("时运", switchMap["to"])
        assertEquals(switch, ActionSyntax.skill(switchMap))
        val bare = ActionSyntax.skill("Reward{Unbreakable} ~onUse")
        assertEquals(bare, ActionSyntax.skill(ActionSyntax.skillMap(bare)))
        assertEquals("Unbreakable", (ActionSyntax.skillMap(bare)["then"] as Map<*, *>)["action"])
        val flatChildren = mapOf("action" to "Chance", "p" to "0.35", "trigger" to "onAttack", "then" to listOf(
            mapOf("action" to "Velocity", "up" to "0.6"),
            mapOf("action" to "Sound", "name" to "ENTITY_WIND_CHARGE_WIND_BURST")
        ))
        assertEquals(ActionSyntax.skill(examples[2]), ActionSyntax.skill(flatChildren))
    }

    @Test fun `nested conditional reward keeps its inner action and flag`() {
        val old = ActionSyntax.reward("Conditional{condition=y<62;roman=true;reward=ItemSet{Gem=符文碎片;Amount=10}} \$ignorable")
        val map = ActionSyntax.rewardMap(old)
        val nested = map["then"] as Map<*, *>
        assertEquals("ItemSet", nested["action"])
        val decoded = ActionSyntax.reward(map)
        assertEquals(old.call.args, decoded.call.args)
        assertEquals(old.flags, decoded.flags)
    }
}
