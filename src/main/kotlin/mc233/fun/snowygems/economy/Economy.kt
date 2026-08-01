package mc233.`fun`.snowygems.economy

import mc233.`fun`.snowygems.util.DebugUtil
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.severe
import taboolib.module.configuration.Configuration
import taboolib.platform.compat.depositBalance
import taboolib.platform.compat.isEconomySupported
import taboolib.platform.compat.withdrawBalance
import java.io.File
import java.util.UUID

/**
 * 通过反射对接 PlayerPoints 插件, 避免在编译期强制依赖它的 jar
 * 服务器未安装 PlayerPoints 时 [available] 为 false, 会自动回退到内置点券系统
 */
object PlayerPointsBridge {

    private val plugin by lazy { Bukkit.getPluginManager().getPlugin("PlayerPoints") }

    private val api: Any? by lazy {
        try {
            plugin?.javaClass?.getMethod("getAPI")?.invoke(plugin)
        } catch (e: Exception) {
            DebugUtil.log("PlayerPoints", "获取 PlayerPointsAPI 失败: ${e.message}")
            null
        }
    }

    val available: Boolean
        get() = plugin != null && plugin!!.isEnabled && api != null

    fun look(uuid: UUID): Long? {
        return try {
            (api!!.javaClass.getMethod("look", UUID::class.java).invoke(api, uuid) as? Number)?.toLong()
        } catch (e: Exception) {
            DebugUtil.log("PlayerPoints", "look 调用失败: ${e.message}")
            null
        }
    }

    fun give(uuid: UUID, amount: Long): Boolean {
        return try {
            (api!!.javaClass.getMethod("give", UUID::class.java, Int::class.javaPrimitiveType)
                .invoke(api, uuid, amount.toInt()) as? Boolean) ?: false
        } catch (e: Exception) {
            DebugUtil.log("PlayerPoints", "give 调用失败: ${e.message}")
            false
        }
    }

    fun take(uuid: UUID, amount: Long): Boolean {
        return try {
            (api!!.javaClass.getMethod("take", UUID::class.java, Int::class.javaPrimitiveType)
                .invoke(api, uuid, amount.toInt()) as? Boolean) ?: false
        } catch (e: Exception) {
            DebugUtil.log("PlayerPoints", "take 调用失败: ${e.message}")
            false
        }
    }
}

/**
 * 点券账户系统, 支持两种后端(由 config.yml 中 Points.Provider 决定):
 *  - Internal:      内置的 data/points.yml 简易账户系统(默认)
 *  - PlayerPoints:  对接已安装的 PlayerPoints 插件
 * 若配置为 PlayerPoints 但插件未安装/不可用, 会自动回退到 Internal 并输出一次警告
 */
object PointsEconomy {

    private val file by lazy { File(getDataFolder(), "data/points.yml") }
    private val storage by lazy {
        file.parentFile?.mkdirs()
        if (!file.exists()) file.createNewFile()
        Configuration.loadFromFile(file)
    }

    private fun usePlayerPoints(): Boolean {
        if (!DebugUtil.pointsProvider.equals("PlayerPoints", true)) return false
        if (PlayerPointsBridge.available) return true
        severe("[SnowyGems] Points.Provider 配置为 PlayerPoints, 但未检测到可用的 PlayerPoints 插件, 已回退到内置点券系统")
        return false
    }

    @Synchronized
    fun get(player: OfflinePlayer): Double {
        if (usePlayerPoints()) {
            return PlayerPointsBridge.look(player.uniqueId)?.toDouble() ?: 0.0
        }
        return storage.getDouble(player.uniqueId.toString(), 0.0)
    }

    @Synchronized
    fun add(player: OfflinePlayer, amount: Double): Double {
        if (usePlayerPoints()) {
            val amountLong = amount.toLong()
            val ok = if (amountLong >= 0) PlayerPointsBridge.give(player.uniqueId, amountLong)
            else PlayerPointsBridge.take(player.uniqueId, -amountLong)
            DebugUtil.log("Points", "PlayerPoints ${if (amountLong >= 0) "give" else "take"} ${player.uniqueId} amount=$amountLong -> $ok")
            return PlayerPointsBridge.look(player.uniqueId)?.toDouble() ?: 0.0
        }
        val newValue = get(player) + amount
        storage.set(player.uniqueId.toString(), newValue)
        storage.saveToFile()
        return newValue
    }
}

object MoneyEconomy {

    fun add(player: Player, amount: Double): Boolean {
        if (!isEconomySupported) {
            severe("未检测到 Vault 经济插件, Money 奖励未生效, 请安装 Vault + 经济插件, 或改用点券系统")
            return false
        }
        val ok = if (amount >= 0) {
            player.depositBalance(amount).transactionSuccess()
        } else {
            player.withdrawBalance(-amount).transactionSuccess()
        }
        DebugUtil.log("Money", "Vault ${if (amount >= 0) "deposit" else "withdraw"} ${player.name} amount=$amount -> $ok")
        return ok
    }
}
