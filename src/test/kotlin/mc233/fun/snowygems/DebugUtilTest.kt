package mc233.`fun`.snowygems

import mc233.`fun`.snowygems.util.DebugUtil
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DebugUtilTest {
    @Test fun `debug all enables every tag and scoped commands enable their scope`() {
        DebugUtil.enable(listOf("Reward"))
        assertTrue(DebugUtil.accepts("reward"))
        assertFalse(DebugUtil.accepts("Skill"))

        DebugUtil.enable(emptyList())
        assertTrue(DebugUtil.accepts("Reward"))
        assertTrue(DebugUtil.accepts("Skill"))

        DebugUtil.toggle()
        assertFalse(DebugUtil.accepts("Reward"))
    }
}
