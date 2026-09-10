package mc233.`fun`.snowygems

import mc233.`fun`.snowygems.compat.ConfigValidator
import mc233.`fun`.snowygems.compat.FeatureModules
import mc233.`fun`.snowygems.config.GemRegistry
import mc233.`fun`.snowygems.config.MenuRegistry
import mc233.`fun`.snowygems.config.SkillRegistry
import mc233.`fun`.snowygems.config.ConfigurationHealth
import mc233.`fun`.snowygems.gui.EmbedGui
import mc233.`fun`.snowygems.gui.MenuHolder
import mc233.`fun`.snowygems.gui.GemGui
import mc233.`fun`.snowygems.rune.RuneRecipeRegistry
import mc233.`fun`.snowygems.manager.DismantleService
import mc233.`fun`.snowygems.manager.MarkBlockManager
import mc233.`fun`.snowygems.skill.SkillExecutor
import mc233.`fun`.snowygems.update.UpdateChecker
import mc233.`fun`.snowygems.util.Banner
import mc233.`fun`.snowygems.util.DebugUtil
import mc233.`fun`.snowygems.util.Lang
import org.bukkit.Bukkit
import taboolib.common.LifeCycle
import taboolib.common.platform.Awake
import taboolib.common.platform.Platform
import taboolib.common.platform.function.pluginVersion
import taboolib.module.metrics.Metrics

/**
 * 生命周期编排.
 *
 * TabooLib 的自唤醒把 Bukkit 那三个方法拆成了六个更精确的阶段, 每个阶段该干什么一目了然:
 *
 * 监听器(@SubscribeEvent)、定时任务(@Schedule)、配置(@Config) 都由 TabooLib 自己扫描注册,
 * 所以这里只剩"顺序有讲究"的那几件事
 */
object Bootstrap {

    /** 加载阶段: 打横幅 + bStats, 不读配置 */
    @Awake(LifeCycle.LOAD)
    fun onLoad() {
        Banner.printStartup()
        Metrics(33021, pluginVersion, Platform.BUKKIT)
    }

    /** 启用阶段: 注册权限节点 + 读全部配置 */
    @Awake(LifeCycle.ENABLE)
    fun onEnable() {
        Permissions.register()
        reloadAll()
    }

    /** 服务器完全启动: 数字此时才是最终值, 汇报一次加载结果, 并静默检测更新/公告 */
    @Awake(LifeCycle.ACTIVE)
    fun onActive() {
        Banner.printSummary(
            gems = GemRegistry.ids().size,
            menus = MenuRegistry.names().size,
            skills = SkillRegistry.all().size,
            points = DebugUtil.pointsProvider
        )
        // Done 之后静默跑一次: 异步拉取远端版本/公告, 结果打印到控制台
        UpdateChecker.runStartupCheck()
    }

    @Awake(LifeCycle.DISABLE)
    fun onDisable() {
        closeWorkbenches()
        Banner.printShutdown()
    }

    fun reloadAll() {
        closeWorkbenches()
        DebugUtil.reload()
        Lang.reload()
        FeatureModules.resolve()
        GemRegistry.reload()
        RuneRecipeRegistry.reload()
        MenuRegistry.reload()
        SkillRegistry.reload()
        SkillExecutor.registerBuiltins()
        DismantleService.resolve()
        MarkBlockManager.load()
        UpdateChecker.resolve()
        ConfigValidator.validate()
        ConfigurationHealth.check()
    }

    /** 在注册表或监听器失效前触发关闭回收，避免重载和停服吞掉投入的物品。 */
    private fun closeWorkbenches() {
        GemGui.invalidateSessions()
        RuneRecipeRegistry.invalidate()
        Bukkit.getOnlinePlayers().forEach { player ->
            when (player.openInventory.topInventory.holder) {
                is MenuHolder, is EmbedGui.EmbedHolder -> player.closeInventory()
            }
        }
    }
}
