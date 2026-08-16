package mc233.`fun`.snowygems

import taboolib.common.platform.Plugin
import taboolib.platform.BukkitPlugin

/**
 * 插件主类.
 *
 * 生命周期全部由 [Bootstrap] 用 TabooLib 的自唤醒(@Awake + LifeCycle)声明
 *
 */
object SnowyGems : Plugin() {

    /** 需要 JavaPlugin 实例的地方(NamespacedKey 等)统一从这里取 */
    val plugin: BukkitPlugin get() = BukkitPlugin.getInstance()

    /** 重新读取全部配置(供 /sgem reload 与启用阶段共用, 保证两边顺序一致) */
    fun reloadAll() = Bootstrap.reloadAll()
}
