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
 * 服务器未安装 PlayerPoints 时 [available] 为 false, 暂停点券交易
 */
object PlayerPointsBridge {

    private val plugin get() = Bukkit.getPluginManager().getPlugin("PlayerPoints")?.takeIf { it.isEnabled }
    private val api: Any? get() = runCatching { plugin?.let { it.javaClass.getMethod("getAPI").invoke(it) } }.getOrNull()
    val available: Boolean get() = api != null

    fun look(uuid: UUID): Long? {
        return try {
            (api!!.javaClass.getMethod("look", UUID::class.java).invoke(api, uuid) as? Number)?.toLong()
        } catch (e: Exception) {
            DebugUtil.log("PlayerPoints", "look 调用失败: ${e.message}")
            null
        }
    }

    fun give(uuid: UUID, amount: Long): Boolean {
        if (amount !in 0..Int.MAX_VALUE.toLong()) return false
        return try {
            (api!!.javaClass.getMethod("give", UUID::class.java, Int::class.javaPrimitiveType)
                .invoke(api, uuid, amount.toInt()) as? Boolean) ?: false
        } catch (e: Exception) {
            DebugUtil.log("PlayerPoints", "give 调用失败: ${e.message}")
            false
        }
    }

    fun take(uuid: UUID, amount: Long): Boolean {
        if (amount !in 0..Int.MAX_VALUE.toLong()) return false
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
 * 若配置为 PlayerPoints 但插件未安装/不可用, 暂停交易并输出一次警告
 */
object PointsEconomy {

    private val file by lazy { File(getDataFolder(), "data/points.yml") }
    private val storage by lazy {
        file.parentFile?.mkdirs()
        if (!file.exists()) file.createNewFile()
        Configuration.loadFromFile(file)
    }

    private var warnedProvider: String? = null
    private fun provider(): PointsProvider {
        val name = DebugUtil.pointsProvider
        val selected = selectPointsProvider(name, name.trim().equals("PlayerPoints", true) && PlayerPointsBridge.available)
        if (selected == PointsProvider.UNAVAILABLE) {
            if (warnedProvider != name) severe("[SnowyGems] 点券后端 $name 不可用，暂停点券交易；不会切换到内置账户")
            warnedProvider = name
        } else warnedProvider = null
        return selected
    }

    @Synchronized
    fun get(player: OfflinePlayer): Double {
        when (provider()) {
            PointsProvider.UNAVAILABLE -> return Double.NaN
            PointsProvider.PLAYER_POINTS -> return PlayerPointsBridge.look(player.uniqueId)?.toDouble() ?: Double.NaN
            PointsProvider.INTERNAL -> Unit
        }
        return storage.getDouble(player.uniqueId.toString(), 0.0)
    }

    @Synchronized
    fun add(player: OfflinePlayer, amount: Double): Double {
        tryAdd(player, amount)
        return get(player)
    }

    /** 返回交易结果，调用者不可用扣费后的余额推断成功与否。 */
    @Synchronized
    fun tryAdd(player: OfflinePlayer, amount: Double): Boolean {
        if (!amount.isFinite()) return false
        if (amount == 0.0) return true
        val provider = provider()
        if (provider == PointsProvider.UNAVAILABLE) return false
        if (provider == PointsProvider.PLAYER_POINTS) {
            if (kotlin.math.abs(amount) > Int.MAX_VALUE || amount != amount.toLong().toDouble()) return false
            val amountLong = amount.toLong()
            val ok = if (amountLong >= 0) PlayerPointsBridge.give(player.uniqueId, amountLong)
            else PlayerPointsBridge.take(player.uniqueId, -amountLong)
            DebugUtil.log("Points", "PlayerPoints ${if (amountLong >= 0) "give" else "take"} ${player.uniqueId} amount=$amountLong -> $ok")
            return ok
        }
        val current = get(player)
        val newValue = current + amount
        if (!newValue.isFinite() || newValue < 0.0 || !current.isFinite()) return false
        storage.set(player.uniqueId.toString(), newValue)
        return try {
            storage.saveToFile()
            true
        } catch (e: Exception) {
            storage.set(player.uniqueId.toString(), current)
            DebugUtil.err("Points", "保存点券交易失败", e)
            false
        }
    }
}

object MoneyEconomy {

    fun add(player: Player, amount: Double): Boolean {
        if (!amount.isFinite()) return false
        if (amount == 0.0) return true
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

internal enum class PointsProvider { INTERNAL, PLAYER_POINTS, UNAVAILABLE }
internal fun selectPointsProvider(name: String, available: Boolean): PointsProvider = when (name.trim().lowercase()) {
    "internal" -> PointsProvider.INTERNAL
    "playerpoints" -> if (available) PointsProvider.PLAYER_POINTS else PointsProvider.UNAVAILABLE
    else -> PointsProvider.UNAVAILABLE
}
