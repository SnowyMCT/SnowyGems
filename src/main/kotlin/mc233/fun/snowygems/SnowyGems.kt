package mc233.`fun`.snowygems

import mc233.`fun`.snowygems.manager.GemManager
import taboolib.common.platform.Plugin
import taboolib.platform.BukkitPlugin

/**
 * 插件主类.
 *
 * 这里刻意保持"空壳"——不写 onLoad / onEnable / onDisable。
 * 生命周期全部由 [Bootstrap] 用 TabooLib 的自唤醒(@Awake + LifeCycle)声明,
 * 各功能模块(BuffEngine / DebugUtil / Lang)也各自用 @Awake / @Schedule / @Config 自启,
 * 主类因此不需要 import 一堆模块再逐个 start(), 新增模块时这个文件零改动。
 */
object SnowyGems : Plugin() {

    val gemManager get() = GemManager

    /** 需要 JavaPlugin 实例的地方(NamespacedKey 等)统一从这里取 */
    val plugin: BukkitPlugin get() = BukkitPlugin.getInstance()

    /** 重新读取全部配置(供 /sgem reload 与启用阶段共用, 保证两边顺序一致) */
    fun reloadAll() = Bootstrap.reloadAll()
}
