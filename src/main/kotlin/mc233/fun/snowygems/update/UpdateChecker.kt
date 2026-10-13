package mc233.`fun`.snowygems.update

import mc233.`fun`.snowygems.util.ColorUtil
import mc233.`fun`.snowygems.util.DebugUtil
import mc233.`fun`.snowygems.util.EntityTasks
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
 *   2. 拥有管理员权限的玩家上线时, 由 [UpdateJoinListener] 调用 [notifyOnJoin]:
 *      - 版本提示直接用上次拉到的缓存, 不额外联网
 *      - 公告按 [announceThrottleMinutes](config.yml 的 AnnouncementThrottleMinutes, 单位分钟)节流:
 *        距上次拉取超过该分钟数才异步重拉一次(最长 [timeoutSeconds] 秒), 拉完把公告播给该玩家;
 *        仍在节流窗口内就直接播上次的结果。多个管理员同时上线只拉一次, 后到的登记为等待者,
 *        拉完一起投递(见 [joinWaiters])
 *      - 拉取失败(网络/超时/HTTP 错误)发 update-console-failed 的文案,
 *        请求成功但远端 ann.txt 为空发 update-announce-empty 的文案, 两种都不静默
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
    /** 管理员上线拉公告的节流: 距上次拉取不足该分钟数就不重新联网, 0 = 不节流 */
    private var announceThrottleMinutes = 5L

    // ── 最近一次检测结果(供玩家上线提示复用, 不必每次上线都重新联网) ──
    @Volatile private var latestVersion: String? = null
    @Volatile private var latestDate: String? = null
    @Volatile private var updateAvailable = false
    @Volatile private var announcement: List<String> = emptyList()

    private val fetching = AtomicBoolean(false)

    // ── 管理员上线"拉一次公告": 节流 + 并发等待者 ──────────────
    /** 上一次公告拉取**结束**的时间戳(成功/失败都算); 0 = 本次运行还没拉过 */
    @Volatile private var lastAnnounceAttemptAt = 0L
    /** 上一次公告拉取的结果; null = 还没拉过(那时 lastAnnounceAttemptAt 是 0, 管理员上线必定触发一次拉取) */
    @Volatile private var lastAnnounceResult: AnnounceResult? = null
    /** 正在等本次拉取结果的管理员, 由 [joinLock] 保护 */
    private val joinWaiters = ArrayList<Player>()
    private val joinLock = Any()

    fun resolve() {
        if (!::conf.isInitialized) return
        updateEnabled = conf.getBoolean("UpdateChecker.Enabled", true)
        announceEnabled = conf.getBoolean("UpdateChecker.Announcement", true)
        adminPermission = conf.getString("UpdateChecker.AdminPermission", "snowygems.admin") ?: "snowygems.admin"
        notifyAdminOnJoin = conf.getBoolean("UpdateChecker.NotifyAdminOnJoin", true)
        versionUrl = conf.getString("UpdateChecker.VersionUrl", versionUrl) ?: versionUrl
        announcementUrl = conf.getString("UpdateChecker.AnnouncementUrl", announcementUrl) ?: announcementUrl
        timeoutSeconds = conf.getInt("UpdateChecker.Timeout", 8).coerceIn(1, 60)
        announceThrottleMinutes = conf.getInt("UpdateChecker.AnnouncementThrottleMinutes", 5).toLong().coerceAtLeast(0L)
        DebugUtil.log("Update", "配置就绪: 更新检测=$updateEnabled 公告=$announceEnabled 权限=$adminPermission 上线提示=$notifyAdminOnJoin 公告节流=${announceThrottleMinutes}分钟")
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
                // 本次拉取结束: 把结果投递给等待中的管理员(上线触发的那些), 没有等待者时什么都不做
                deliverAnnouncementToWaiters()
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
        // 这样服主把本地版本改成任何 ≠ 远端的值(更低/更高/乱填)都会被提示去核对
        // 去掉首尾空白后逐字符比较, 忽略大小写差异(避免 v1.0 与 V1.0 误报)。
        updateAvailable = ver != null && !ver.trim().equals(current.trim(), ignoreCase = true)
        DebugUtil.log("Update", "当前版本=$current 远端版本=$ver 日期=$date 需更新=$updateAvailable")
    }

    /**
     * 拉取公告. 结果写进 [announcement] / [lastAnnounceResult] / [lastAnnounceAttemptAt].
     * 失败时保留上一次的公告内容: 网络抖一下不该把已有公告当成"空的"
     */
    private fun fetchAnnouncement() {
        val body = httpGet(announcementUrl)
        lastAnnounceAttemptAt = System.currentTimeMillis()
        if (body == null) {
            lastAnnounceResult = AnnounceResult.FAILED
            DebugUtil.log("Update", "ann.txt 拉取失败(网络不可达或超时), 保留上次公告")
            return
        }
        announcement = parseAnnouncement(body)
        lastAnnounceResult = if (announcement.isEmpty()) AnnounceResult.EMPTY else AnnounceResult.CONTENT
        DebugUtil.log("Update", "公告共 ${announcement.size} 行, 结果=${lastAnnounceResult}")
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
        var versionFailed = false
        if (updateEnabled) {
            val latest = latestVersion
            when {
                // 拉取失败/解析失败: 绝不能判定为"已是最新", 明确提示检查失败
                // (常见于服务器未配 JVM 代理直连 GitHub 超时)
                latest == null -> {
                    versionFailed = true
                    sender.sendMessage(Lang.get("update.console-failed"))
                }
                updateAvailable -> sender.sendMessage(Lang.get("update.console-available",
                    "current" to pluginVersion, "latest" to latest, "date" to (latestDate ?: "-")))
                else -> sender.sendMessage(Lang.get("update.console-latest", "current" to pluginVersion))
            }
        }
        // 公告: 有内容就整段打印; 拉取失败/远端为空也要明说一声, 绝不静默当成"没有公告"
        // 与上面版本那句是同一个键时只发一次, 免得控制台出现两行一模一样的报错
        val result = lastAnnounceResult
        if (announceEnabled && result != null && !(result == AnnounceResult.FAILED && versionFailed)) {
            sendAnnouncement(result) { sender.sendMessage(it) }
        }
    }

    /**
     * 管理员上线提示. 由 [UpdateJoinListener] 在玩家加入后延迟调用, 只发给拥有管理员权限的玩家.
     * 版本提示复用上次拉到的缓存(不再联网); 公告走 [notifyAnnouncementOnJoin]: 按节流规则异步拉一次再播放
     */
    fun notifyOnJoin(player: Player) {
        if (!notifyAdminOnJoin) return
        if (!player.hasPermission(adminPermission) && !player.isOp) return
        val versionSent = updateEnabled && updateAvailable
        if (versionSent) {
            Lang.send(player, "update.player-available",
                "current" to pluginVersion, "latest" to (latestVersion ?: "?"), "date" to (latestDate ?: "-"))
        }
        if (announceEnabled) notifyAnnouncementOnJoin(player)
        DebugUtil.log("Update", "管理员 ${player.name} 上线提示: 版本=${if (versionSent) "已发送" else "无内容"}, 公告=${if (announceEnabled) "按节流规则异步拉取并播放" else "已关闭"}")
    }

    /**
     * 管理员上线: 拉一次公告并播给他看(全程异步, 不阻塞主线程). 三种情况:
     *   1. 距上次拉取不足 [announceThrottleMinutes] 分钟 -> 不联网, 直接播上一次的结果(公告或兜底文案)
     *   2. 已有请求在飞(开服检测 / 另一个管理员刚触发) -> 只登记为等待者, 拉完一起投递, 不重复联网
     *   3. 其余情况 -> 自己发起一次异步拉取, 拉完投递给所有等待者
     */
    private fun notifyAnnouncementOnJoin(player: Player) {
        if (!throttleElapsed(lastAnnounceAttemptAt, System.currentTimeMillis(), announceThrottleMinutes)) {
            DebugUtil.log("Update", "公告节流中(${announceThrottleMinutes} 分钟内拉取过), ${player.name} 直接播放上次结果")
            deliverAnnouncementLater(player, lastAnnounceResult ?: AnnounceResult.FAILED)
            return
        }
        synchronized(joinLock) { joinWaiters.add(player) }
        if (!fetching.compareAndSet(false, true)) {
            DebugUtil.log("Update", "已有检测请求在飞, ${player.name} 登记为公告等待者")
            return
        }
        DebugUtil.log("Update", "${player.name} 上线, 异步拉取一次公告(超时 ${timeoutSeconds}s)")
        submitAsync {
            try {
                fetchAnnouncement()
            } catch (e: Throwable) {
                lastAnnounceAttemptAt = System.currentTimeMillis()
                lastAnnounceResult = AnnounceResult.FAILED
                DebugUtil.err("Update", "上线拉取公告时出错", e)
            } finally {
                fetching.set(false)
                deliverAnnouncementToWaiters()
            }
        }
    }

    /** 拉取结束后投递给所有等待中的管理员; 没有等待者时直接返回(开服那次检测走这里不做任何事) */
    private fun deliverAnnouncementToWaiters() {
        val targets = synchronized(joinLock) {
            if (joinWaiters.isEmpty()) return
            joinWaiters.toList().also { joinWaiters.clear() }
        }
        val result = lastAnnounceResult ?: AnnounceResult.FAILED
        targets.forEach { deliverAnnouncementLater(it, result) }
    }

    /** 延迟 1 tick 投递(EntityTasks = 实体所在区域的调度器, Folia 安全); 玩家已离线就丢弃 */
    private fun deliverAnnouncementLater(player: Player, result: AnnounceResult) {
        if (!player.isOnline) return
        runCatching {
            EntityTasks.later(player, 1) { if (player.isOnline) sendAnnouncement(result) { player.sendMessage(it) } }
        }.onFailure { DebugUtil.log("Update", "公告投递给 ${player.name} 失败: ${it.javaClass.simpleName}") }
    }

    /**
     * 把公告(或兜底文案)写进 [emit]. 出口由调用方决定: 玩家用 Player.sendMessage,
     * 控制台用 TabooLib 的 console().sendMessage(两边类型不同, 不能共用一个参数).
     *   有内容   -> 头 + 正文 + 尾
     *   拉取失败  -> update-console-failed(网络/代理问题)
     *   远端为空  -> update-announce-empty(不是你的网络问题)
     */
    private fun sendAnnouncement(result: AnnounceResult, emit: (String) -> Unit) {
        when (result) {
            AnnounceResult.FAILED -> emit(Lang.get("update.console-failed"))
            AnnounceResult.EMPTY -> emit(Lang.get("update.announce-empty"))
            AnnounceResult.CONTENT -> {
                emit(Lang.get("update.announce-header"))
                announcement.forEach { emit(ColorUtil.colorize(it)) }
                emit(Lang.get("update.announce-footer"))
            }
        }
    }
}

/**
 * 公告拉取的结果.
 * CONTENT = 拉到了内容; FAILED = 网络/超时/HTTP 非 2xx; EMPTY = 请求成功但远端文件里没有内容
 * 后两种各有兜底文案(见 UpdateChecker.sendAnnouncement), 绝不静默当成"没有公告"
 */
internal enum class AnnounceResult { CONTENT, FAILED, EMPTY }

/** 远端正文 -> 公告行(逐行去掉行尾空白, 丢掉末尾空行); 整份都是空白时得到空列表 */
internal fun parseAnnouncement(body: String): List<String> =
    body.lines().map { it.trimEnd() }.dropLastWhile { it.isBlank() }

/** 距上次拉取是否已超过节流时间; minutes <= 0 表示不节流(每次上线都重新拉) */
internal fun throttleElapsed(lastAttemptAt: Long, now: Long, minutes: Long): Boolean =
    minutes <= 0L || now - lastAttemptAt >= minutes * 60_000L
