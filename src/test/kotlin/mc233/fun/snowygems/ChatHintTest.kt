package mc233.`fun`.snowygems

import mc233.`fun`.snowygems.util.ChatHint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `/sgem history` 漏参数时的可点击用法提示.
 *
 * 闲聊序列化在单测里不可用(TabooLib 的聊天模块要服务端提供的 bungee/adventure 类),
 * 所以这里断言的是**点击目标**与**语言文件**这两处真正容易出错的地方:
 * 点一下能不能补出可用命令、四种语言的提示是否都在.
 */
class ChatHintTest {

    private fun lang(file: String): String =
        requireNotNull(javaClass.getResourceAsStream("/lang/$file")) { "找不到语言文件 $file" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    @Test fun `player shortcut runs the same command with the name appended`() {
        val targets = ChatHint.targets("Monostar14")
        assertEquals("/sgem history ", targets.suggest, "点参数占位应当补全出可继续输入的命令")
        assertEquals("/sgem history Monostar14", targets.run, "点自己的入口应当直接带名字执行")
    }

    @Test fun `console and offline senders get no self shortcut`() {
        assertNull(ChatHint.targets(null).run)
    }

    @Test fun `every language file carries the usage hint texts`() {
        val keys = listOf(
            "audit-usage-command" to true,
            "audit-usage-player" to true,
            "audit-usage-player-hover" to true,
            "audit-usage-self" to true,
            "audit-usage-self-hover" to true
        )
        for (file in listOf("zh_CN.yml", "zh_TW.yml", "en_US.yml", "ko_KR.yml")) {
            val text = lang(file)
            val lines = text.lineSequence().map { it.trimStart('\uFEFF').substringBefore(':').trim() }.toSet()
            for ((key, _) in keys) {
                assertTrue(key in lines, "$file 缺少语言节点 $key")
            }
            // 一键查自己的那条必须带 {player}, 否则提示文字会与实际点击目标不一致
            val selfHover = text.lineSequence().first { it.startsWith("audit-usage-self-hover:") }
            assertTrue("{player}" in selfHover, "$file 的 audit-usage-self-hover 缺少 {player} 占位符")
        }
    }
}
