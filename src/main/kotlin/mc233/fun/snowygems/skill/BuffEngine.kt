package mc233.`fun`.snowygems.skill

import mc233.`fun`.snowygems.config.SkillRegistry
import mc233.`fun`.snowygems.util.ColorUtil
import mc233.`fun`.snowygems.util.DebugUtil
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import taboolib.common.platform.Schedule

/**
 * BUFF 引擎: 每秒扫描在线玩家的装备/手持物品, 若其 Lore 命中某个技能/BUFF 定义的 Lore 标记,
 * 就执行该定义中带 ~onTimer 标记的技能行
 *
 * 与老实现的区别: 不再自己实现 PotionBuff 和药水名解析, 而是把技能行交给 [SkillExecutor]
 * 好处是常驻 BUFF 和主动技能共享同一套函数表 —— 服主在 onTimer 上可以用 Potion、也可以用
 * If / Chance / All 组合出"血量低于一半时才给抗性"这类条件 BUFF, 无需引擎额外支持
 *
 * 定时任务用 [Schedule] 注解声明, 服务器调度器就绪后自动开始, 主类不需要调 start()
 */
object BuffEngine {

    /** 每 20 tick(1 秒) 扫描一次. 同步执行: 药水效果和物品读写都必须在主线程 */
    @Schedule(period = 20, async = false)
    fun run() {
        for (player in Bukkit.getOnlinePlayers()) {
            try {
                tick(player)
            } catch (e: Exception) {
                // 忽略单个玩家 tick 出错, 不影响其他玩家
                DebugUtil.err("Buff", "为 ${player.name} 执行 BUFF tick 时出错", e)
            }
        }
    }

    private fun equipmentOf(player: Player): List<ItemStack> {
        val list = ArrayList<ItemStack>()
        list.add(player.inventory.itemInMainHand)
        list.add(player.inventory.itemInOffHand)
        player.inventory.armorContents.forEach { it?.let { i -> list.add(i) } }
        return list.filter { it.type != Material.AIR }
    }

    private fun tick(player: Player) {
        for (item in equipmentOf(player)) {
            val lore = item.itemMeta?.lore ?: continue
            for (def in SkillRegistry.withLore()) {
                val marker = def.lore ?: continue
                // 用去色+trim 的容错匹配: BUFF 标记尾部常带空格(如 "生命提升 "), 而写进装备的行
                // 是 "生命提升4"(数字紧贴), 直接 contains 带色带空格的 marker 会匹配失败 -> buff 从不触发
                if (lore.none { ColorUtil.loreMatches(it, marker) }) continue
                val timerLines = def.skills.map { SkillLineParser.parse(it) }
                    .filter { it.triggers.contains("onTimer") }
                if (timerLines.isEmpty()) continue
                DebugUtil.logChanged(
                    "Buff", "match:${player.uniqueId}:${item.type}:${def.id}",
                    "${player.name} 的 ${item.type} 命中 BUFF 定义 ${def.id}, ${timerLines.size} 行 onTimer (相同内容不再重复输出)"
                )
                for (line in timerLines) {
                    // 交给统一的函数表执行, BUFF 和主动技能共享全部函数
                    SkillExecutor.execute(player, item, line, trigger = "onTimer")
                }
            }
        }
    }
}
