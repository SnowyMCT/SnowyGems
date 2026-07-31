package mc233.`fun`.snowygems

import mc233.`fun`.snowygems.compat.ConfigValidator
import mc233.`fun`.snowygems.compat.FeatureModules
import mc233.`fun`.snowygems.config.GemRegistry
import mc233.`fun`.snowygems.config.MenuRegistry
import mc233.`fun`.snowygems.config.SkillRegistry
import mc233.`fun`.snowygems.skill.SkillExecutor
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
     * 重新读取全部配置. 顺序有严格依赖, 不要打乱:
     *
     *   1. DebugUtil    —— 后面每一步是否输出日志都取决于它
     *   2. Lang         —— 加载过程中的提示要能用上语言文件
     *   3. FeatureModules —— 版本模块门禁, 必须先于任何注册表查询
     *   4. 宝石/菜单/技能注册表 —— 解析配置(其中会查属性/附魔/效果, 因此依赖第 3 步)
     *   5. SkillExecutor  —— 技能函数表
     *   6. ConfigValidator —— 自检, 依赖以上全部就位
     *
     * 第 3、5 步刻意不用 @Awake: 同一生命周期内多个 @Awake 方法的执行顺序不保证,
     * 一旦乱序就会出现"前半段配置按全模块启用解析""自检把所有函数误报成未注册"这类问题。
     */
    fun reloadAll() {
        DebugUtil.reload()
        Lang.reload()
        FeatureModules.resolve()
        GemRegistry.reload()
        MenuRegistry.reload()
        SkillRegistry.reload()
        SkillExecutor.registerBuiltins()
        ConfigValidator.validate()
    }
}
