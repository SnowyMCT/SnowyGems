package mc233.`fun`.snowygems.update

import mc233.`fun`.snowygems.util.DebugUtil
import mc233.`fun`.snowygems.util.EntityTasks
import org.bukkit.event.player.PlayerJoinEvent
import taboolib.common.platform.event.SubscribeEvent

/**
 * 管理员上线时拉一次公告并推送"有新版本 / 公告"提示
 *
 * 延迟 40 tick(2 秒)再发: 一是等玩家客户端完全进服、聊天栏就绪, 二是给服务器刚启动那次
 * 异步检测留出完成时间(玩家紧跟着服务器启动登录的场景)。版本提示复用启动检测的缓存;
 * 公告由 [UpdateChecker.notifyOnJoin] 按节流规则(config.yml 的 AnnouncementThrottleMinutes)
 * 异步重新拉一次再播给该玩家, 已有请求在飞时只登记为等待者, 不重复联网
 */
object UpdateJoinListener {

    @SubscribeEvent
    fun onJoin(e: PlayerJoinEvent) {
        val player = e.player
        DebugUtil.log("Update", "玩家 ${player.name} 上线, 2 秒后尝试推送更新/公告提示")
        EntityTasks.later(player, 40) {
            if (player.isOnline) UpdateChecker.notifyOnJoin(player)
        }
    }
}
