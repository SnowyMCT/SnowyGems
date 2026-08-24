package mc233.`fun`.snowygems.update

import mc233.`fun`.snowygems.util.ColorUtil
import mc233.`fun`.snowygems.util.DebugUtil
import mc233.`fun`.snowygems.util.Lang
import org.bukkit.entity.Player
import taboolib.common.platform.function.console
import taboolib.common.platform.function.pluginVersion
import taboolib.common.platform.function.submit
import taboolib.common.platform.function.submitAsync
import taboolib.module.configuration.Config
import taboolib.module.configuration.Configuration
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 版本更新检测 + 公告系统.
 *
 * 行为(全部可在 config.yml 的 UpdateChecker 一节里配置):
 *   1. 服务器完全启动后(Done 之后)由 [mc233.fun.snowygems.Bootstrap.onActive] 静默触发一次 [runStartupCheck]
 *      —— 异步拉取远端 version.txt / ann.txt, 不阻塞主线程; 结果打印到控制台("后台提示")
 *   2. 拥有管理员权限的玩家上线时, 把\"有新版本 / 公告\"提示单独发给他(见 [notifyOnJoin])
 *
 * 网络: 用 JDK 自带的 HttpURLConnection, 自动遵循 JVM 系统代理(-Dhttp.proxyHost 等),
 * 插件本身不写死任何代理地址 —— 内网/需要代理访问 GitHub 的服主自行在启动参数里配置
 */
object UpdateChecker {

    @Config(value = "config.yml", autoReload = true, migrate = true)
    lateinit var conf: Configuration

    // ── 配置项(在 resolve() 里读取) ────────────────────────────
    private var updateEnabled = true
    private var announceEnabled = true
    private var adminPermission = "snowygems.admin"
    private var notifyAdminOnJoin = true
    private var versionUrl = "https://raw.githubusercontent.com/SnowyMCT/SnowyGems-VersionCheck/refs/heads/main/version.txt"
    private var announcementUrl = "https://raw.githubusercontent.com/SnowyMCT/SnowyGems-VersionCheck/main/ann.txt"
    private var timeoutSeconds = 8

    // ── 最近一次检测结果(供玩家上线提示复用, 不必每次上线都重新联网) ──
    @Volatile private var latestVersion: String? = null
    @Volatile private var latestDate: String? = null
    @Volatile private var updateAvailable = false
    @Volatile private var announcement: List<String> = emptyList()

    private val fetching = AtomicBoolean(false)

    fun resolve() {
        if (!::conf.isInitialized) return
        updateEnabled = conf.getBoolean("UpdateChecker.Enabled", true)
        announceEnabled = conf.getBoolean("UpdateChecker.Announcement", true)
        adminPermission = conf.getString("UpdateChecker.AdminPermission", "snowygems.admin") ?: "snowygems.admin"
        notifyAdminOnJoin = conf.getBoolean("UpdateChecker.NotifyAdminOnJoin", true)
        versionUrl = conf.getString("UpdateChecker.VersionUrl", versionUrl) ?: versionUrl
        announcementUrl = conf.getString("UpdateChecker.AnnouncementUrl", announcementUrl) ?: announcementUrl
        timeoutSeconds = conf.getInt("UpdateChecker.Timeout", 8).coerceIn(1, 60)
        DebugUtil.log("Update", "配置就绪: 更新检测=$updateEnabled 公告=$announceEnabled 权限=$adminPermission 上线提示=$notifyAdminOnJoin")
    }

    fun runStartupCheck() {
        if (!updateEnabled && !announceEnabled) {
            DebugUtil.log("Update", "更新检测与公告均已关闭, 跳过启动检测")
            return
        }
        fetch { reportToConsole() }
    }

    /**
     * 拉取远端数据(异步). 完成后回调 [after] 在异步线程执行完毕
     * 并发保护: 已有请求进行中时直接复用, 不重复联网
     */
    private fun fetch(after: () -> Unit) {
        if (!fetching.compareAndSet(false, true)) {
            DebugUtil.log("Update", "已有检测请求进行中, 跳过本次")
            return
        }
        submitAsync {
            try {
                if (updateEnabled) fetchVersion()
                if (announceEnabled) fetchAnnouncement()
                after()
            } catch (e: Throwable) {
                DebugUtil.err("Update", "检测更新/公告时出错", e)
            } finally {
                fetching.set(false)
            }
        }
    }

    private fun fetchVersion() {
        val body = httpGet(versionUrl) ?: run {
            DebugUtil.log("Update", "version.txt 拉取失败(网络不可达或超时), 本次跳过更新检测")
            return
        }
        var ver: String? = null
        var date: String? = null
        for (line in body.lines()) {
            val t = line.trim()
            when {
                t.startsWith("version:", true) -> ver = t.substringAfter(":").trim()
                t.startsWith("date:", true) -> date = t.substringAfter(":").trim()
            }
        }
        // 兼容\"整行就是版本号\"的极简写法
        if (ver == null) ver = body.trim().lines().firstOrNull { it.isNotBlank() }?.trim()
        latestVersion = ver
        latestDate = date
        val current = pluginVersion
        // 判定规则: 本地版本与远端版本"不完全一致"就提示更新, 不做大小比较。
        // 这样服主把本地版本改成任何 ≠ 远端的值(更低/更高/乱填)都会被提示去核对, 符合"以远端为准"的预期。
        // 去掉首尾空白后逐字符比较, 忽略大小写差异(避免 v1.0 与 V1.0 误报)。
        updateAvailable = ver != null && !ver.trim().equals(current.trim(), ignoreCase = true)
        DebugUtil.log("Update", "当前版本=$current 远端版本=$ver 日期=$date 需更新=$updateAvailable")
    }

    private fun fetchAnnouncement() {
        val body = httpGet(announcementUrl) ?: run {
            DebugUtil.log("Update", "ann.txt 拉取失败, 本次跳过公告")
            return
        }
        announcement = body.lines().map { it.trimEnd() }.dropLastWhile { it.isBlank() }
        DebugUtil.log("Update", "公告共 ${announcement.size} 行")
    }

    /** HTTP GET, 失败返回 null(不抛异常) */
    private fun httpGet(url: String): String? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutSeconds * 1000
                readTimeout = timeoutSeconds * 1000
                setRequestProperty("User-Agent", "SnowyGems-UpdateChecker")
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                DebugUtil.log("Update", "GET $url -> HTTP $code")
                conn.disconnect()
                return null
            }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()
            text
        } catch (e: Exception) {
            DebugUtil.log("Update", "GET $url 失败: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    // ── 输出 ────────────────────────────────────────────────

    /** 把检测结果打印到控制台(启动后的\"后台提示\") */
    private fun reportToConsole() {
        val sender = console()
        if (updateEnabled) {
            val latest = latestVersion
            when {
                // 拉取失败/解析失败: 绝不能判定为"已是最新", 明确提示检查失败
                // (常见于服务器未配 JVM 代理直连 GitHub 超时)
                latest == null -> sender.sendMessage(Lang.get("update.console-failed"))
                updateAvailable -> sender.sendMessage(Lang.get("update.console-available",
                    "current" to pluginVersion, "latest" to latest, "date" to (latestDate ?: "-")))
                else -> sender.sendMessage(Lang.get("update.console-latest", "current" to pluginVersion))
            }
        }
        if (announceEnabled && announcement.isNotEmpty()) {
            sender.sendMessage(Lang.get("update.announce-header"))
            announcement.forEach { sender.sendMessage(ColorUtil.colorize(it)) }
            sender.sendMessage(Lang.get("update.announce-footer"))
        }
    }

    /**
     * 管理员上线提示. 由 [UpdateJoinListener] 在玩家加入后延迟调用.
     * 只发给拥有管理员权限的玩家; 若结果尚未拉取到会静默返回
     */
    fun notifyOnJoin(player: Player) {
        if (!notifyAdminOnJoin) return
        if (!player.hasPermission(adminPermission) && !player.isOp) return
        var sentSomething = false
        if (updateEnabled && updateAvailable) {
            Lang.send(player, "update.player-available",
                "current" to pluginVersion, "latest" to (latestVersion ?: "?"), "date" to (latestDate ?: "-"))
            sentSomething = true
        }
        if (announceEnabled && announcement.isNotEmpty()) {
            Lang.send(player, "update.announce-header")
            announcement.forEach { player.sendMessage(ColorUtil.colorize(it)) }
            Lang.send(player, "update.announce-footer")
            sentSomething = true
        }
        DebugUtil.log("Update", "管理员 ${player.name} 上线提示: ${if (sentSomething) "已发送" else "无内容可发"}")
    }
}
