package mc233.`fun`.snowygems.update

import mc233.`fun`.snowygems.util.DebugUtil
import org.bukkit.event.player.PlayerJoinEvent
import taboolib.common.platform.event.SubscribeEvent
import taboolib.common.platform.function.submit

/**
 * 管理员上线时推送\"有新版本 / 公告\"提示
 *
 * 延迟 40 tick(2 秒)再发: 一是等玩家客户端完全进服、聊天栏就绪, 二是给服务器刚启动那次
 * 异步检测留出完成时间(玩家紧跟着服务器启动登录的场景)检测结果由 [UpdateChecker] 缓存,
 * 这里不再重复联网
 */
object UpdateJoinListener {

    @SubscribeEvent
    fun onJoin(e: PlayerJoinEvent) {
        val player = e.player
        DebugUtil.log("Update", "玩家 ${player.name} 上线, 2 秒后尝试推送更新/公告提示")
        submit(delay = 40) {
            if (player.isOnline) UpdateChecker.notifyOnJoin(player)
        }
    }
}
