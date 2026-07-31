package mc233.`fun`.snowygems

import mc233.`fun`.snowygems.config.GemRegistry
import mc233.`fun`.snowygems.config.MenuRegistry
import mc233.`fun`.snowygems.config.SkillRegistry
import mc233.`fun`.snowygems.util.Banner
import mc233.`fun`.snowygems.util.DebugUtil
import mc233.`fun`.snowygems.util.Lang
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
 *   CONST   静态初始化(插件类还没实例化)
 *   INIT    主类实例化
 *   LOAD    插件加载        —— 只做展示和上报, 此时不要碰配置
 *   ENABLE  插件启用        —— 读配置、注册内容
 *   ACTIVE  服务器完全启动  —— 调度器已就绪, 其它插件(Vault/PlayerPoints)也都在了
 *   DISABLE 插件卸载
 *
 * 监听器(@SubscribeEvent)、定时任务(@Schedule)、配置(@Config) 都由 TabooLib 自己扫描注册,
 * 所以这里只剩"顺序有讲究"的那几件事。
 */
object Bootstrap {

    /** 加载阶段: 打横幅 + bStats, 不读配置 */
    @Awake(LifeCycle.LOAD)
    fun onLoad() {
        Banner.printStartup()
        Metrics(33021, pluginVersion, Platform.BUKKIT)
    }

    /** 启用阶段: 读全部配置 */
    @Awake(LifeCycle.ENABLE)
    fun onEnable() {
        reloadAll()
    }

    /** 服务器完全启动: 数字此时才是最终值, 汇报一次加载结果 */
    @Awake(LifeCycle.ACTIVE)
    fun onActive() {
        Banner.printSummary(
            gems = GemRegistry.ids().size,
            menus = MenuRegistry.names().size,
            skills = SkillRegistry.all().size,
            points = DebugUtil.pointsProvider
        )
    }

    @Awake(LifeCycle.DISABLE)
    fun onDisable() {
        Banner.printShutdown()
    }

    /**
     * 重新读取全部配置. 顺序有依赖关系, 不要打乱:
     *   DebugUtil 先读(后面的加载过程要按 Debug 开关决定是否输出日志)
     *   Lang 次之(注册表加载失败时的提示要能用上语言文件)
     *   然后才是宝石 / 菜单 / 技能
     */
    fun reloadAll() {
        DebugUtil.reload()
        Lang.reload()
        GemRegistry.reload()
        MenuRegistry.reload()
        SkillRegistry.reload()
    }
}
