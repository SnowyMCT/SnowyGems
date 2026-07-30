package mc233.`fun`.snowygems

import mc233.`fun`.snowygems.config.GemRegistry
import mc233.`fun`.snowygems.config.MenuRegistry
import mc233.`fun`.snowygems.config.SkillRegistry
import mc233.`fun`.snowygems.manager.GemManager
import mc233.`fun`.snowygems.skill.BuffEngine
import mc233.`fun`.snowygems.util.DebugUtil
import mc233.`fun`.snowygems.util.Lang
import taboolib.common.platform.Platform
import taboolib.common.platform.Plugin
import taboolib.common.platform.function.info
import taboolib.module.metrics.Metrics
import taboolib.platform.BukkitPlugin

object SnowyGems : Plugin() {

    val gemManager get() = GemManager

    override fun onLoad() {
        info("SnowyMC 荣誉出品")
        info("SnowyGems Loading...")
        Metrics(33021, BukkitPlugin.getInstance().description.version, Platform.BUKKIT)
        info("  ____                                        ____                    ")
        info(" / ___|   _ __     ___   __      __  _   _   / ___|   ___   _ __ ___  ")
        info("  ___ \\  | '_ \\   / _ \\  \\ \\ /\\ / / | | | | | |  _   / _ \\ | '_ ` _ \\ ")
        info("  ___) | | | | | | (_) |  \\ V  V /  | |_| | | |_| | |  __/ | | | | | |")
        info(" |____/  |_| |_|  \\___/    \\_/\\_/    \\__, |  \\____|  \\___| |_| |_| |_|")
        info("                                     |___/                            ")
    }

    override fun onEnable() {
        reloadAll()
        // GemUseListener / SkillTriggerListener / MenuListener 均使用 TabooLib @SubscribeEvent, 会自动注册
        BuffEngine.start()
        info("SnowyGems 已启用")
        if (DebugUtil.enabled) {
            DebugUtil.log("Registry", "启用完成: 宝石=${GemRegistry.ids().size} 菜单=${MenuRegistry.names().size} 技能=${SkillRegistry.all().size}")
            DebugUtil.log("Registry", "点券后端=${DebugUtil.pointsProvider}")
            DebugUtil.log("Registry", "调试模式已开启, 平时请把 config.yml 的 Debug 改回 false, 否则日志量很大")
        }
    }

    override fun onDisable() {
        info("SnowyGems Disabled")
    }

    fun reloadAll() {
        DebugUtil.reload()
        Lang.reload()
        GemRegistry.reload()
        MenuRegistry.reload()
        SkillRegistry.reload()
    }

}
