package mc233.`fun`.snowygems.util

import taboolib.common.platform.function.console
import taboolib.common.platform.function.info
import taboolib.common.platform.function.pluginVersion
import taboolib.common.platform.function.runningPlatform
import taboolib.module.chat.Components
import taboolib.module.chat.StandardColors

/**
 * 控制台启动/关闭横幅.
 *
 * 主类里塞一堆 info("...") 画 ASCII 字很难看也不好改, 统一挪到这里.
 * 用 TabooLib 的 [Components] 组件化上色, 逐行做蓝→青渐变
 */
object Banner {

    /** SnowyGems 字样 */
    private val LOGO = listOf(
        "  ____                                       ____                     ",
        " / ___|  _ __    ___   __      __ _   _     / ___|   ___  _ __ ___    ",
        " \\___ \\ | '_ \\  / _ \\  \\ \\ /\\ / /| | | |   | |  _   / _ \\| '_ ` _ \\   ",
        "  ___) || | | || (_) |  \\ V  V / | |_| |   | |_| | |  __/| | | | | |  ",
        " |____/ |_| |_| \\___/    \\_/\\_/   \\__, |    \\____|  \\___||_| |_| |_|  ",
        "                                  |___/                              "
    )

    /** 从深蓝到亮青, 按行号取色, 让 logo 有层次 */
    private val GRADIENT = listOf(
        StandardColors.DARK_BLUE,
        StandardColors.BLUE,
        StandardColors.DARK_AQUA,
        StandardColors.AQUA,
        StandardColors.AQUA,
        StandardColors.DARK_AQUA
    )

    fun printStartup() {
        line()
        LOGO.forEachIndexed { index, row ->
            send(row, GRADIENT.getOrElse(index) { StandardColors.AQUA })
        }
        send("                      SnowyMC 荣誉出品  ·  雪之宝石", StandardColors.WHITE)
        send("                版本 $pluginVersion  ·  平台 $runningPlatform", StandardColors.GRAY)
        line()
    }

    /**
     * 服务器完全启动后的加载汇总.
     * 放在 ACTIVE 阶段打印, 数字才是最终值(ENABLE 时其它插件可能还没就位).
     */
    fun printSummary(gems: Int, menus: Int, skills: Int, points: String) {
        info("加载完成: 宝石 $gems 个 · 菜单 $menus 个 · 技能 $skills 个 · 点券后端 $points")
        if (DebugUtil.enabled) {
            val scope = if (DebugUtil.tags().isEmpty()) "全部" else DebugUtil.tags().joinToString(",")
            info("调试模式已开启, 输出范围: $scope —— 排查完请把 config.yml 的 Debug 改回 false, 否则日志量很大")
        }
    }

    fun printShutdown() {
        send("SnowyGems 已卸载, 感谢使用", StandardColors.GRAY)
    }

    private fun send(text: String, color: StandardColors) {
        console().sendMessage(Components.text(text).color(color).toLegacyText())
    }

    private fun line() {
        send("-".repeat(70), StandardColors.DARK_GRAY)
    }
}
