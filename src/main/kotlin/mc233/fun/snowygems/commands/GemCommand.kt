package mc233.`fun`.snowygems.commands

import mc233.`fun`.snowygems.SnowyGems
import mc233.`fun`.snowygems.config.GemRegistry
import mc233.`fun`.snowygems.config.MenuRegistry
import mc233.`fun`.snowygems.config.SkillRegistry
import mc233.`fun`.snowygems.gui.EmbedGui
import mc233.`fun`.snowygems.gui.GemGui
import mc233.`fun`.snowygems.gui.WorkbenchMenu
import mc233.`fun`.snowygems.manager.GemManager
import mc233.`fun`.snowygems.util.DebugUtil
import mc233.`fun`.snowygems.util.Lang
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import taboolib.common.platform.command.CommandBody
import taboolib.common.platform.command.CommandHeader
import taboolib.common.platform.command.mainCommand
import taboolib.common.platform.command.subCommand
import taboolib.expansion.createHelper

@CommandHeader(name = "sgem", permission = "snowygems.command")
object GemCommand {

    @CommandBody
    val main = mainCommand {
        createHelper()
    }

    /** /sgem view - 管理员分类浏览/领取宝石 */
    @CommandBody
    val view = subCommand {
        execute<Player> { sender, _, _ ->
            DebugUtil.log("Command", "${sender.name} 执行 /sgem view")
            GemGui.open(sender)
        }
    }

    /** /sgem embed - 打开宝石镶嵌台(属性/附魔/功能/BUFF 类宝石通用) */
    @CommandBody
    val embed = subCommand {
        execute<Player> { sender, _, _ ->
            DebugUtil.log("Command", "${sender.name} 执行 /sgem embed")
            EmbedGui.open(sender)
        }
    }

    /** /sgem inspect - 查看手持装备上已镶嵌的宝石 */
    @CommandBody
    val inspect = subCommand {
        execute<Player> { sender, _, _ ->
            DebugUtil.log("Command", "${sender.name} 执行 /sgem inspect, 手持=${sender.inventory.itemInMainHand.type}")
            GemGui.openInspect(sender)
        }
    }

    /** /sgem use - 直接使用手持的兑换券/药水类宝石 */
    @CommandBody
    val use = subCommand {
        execute<Player> { sender, _, _ ->
            val held = sender.inventory.itemInMainHand
            DebugUtil.log("Command", "${sender.name} 执行 /sgem use, 手持=${held.type} x${held.amount}")
            if (held.type == Material.AIR) {
                Lang.sendCommand(sender, "command.no-held-gem")
                return@execute
            }
            val result = GemManager.useDirectly(sender, held)
            DebugUtil.log("Command", "  useDirectly 返回 success=${result.success} consumed=${result.consumedGem} msg=${result.message}")
            Lang.sendRaw(sender, result.message)
            if (result.consumedGem && result.success) {
                val left = held.clone()
                left.amount -= 1
                sender.inventory.setItemInMainHand(if (left.amount <= 0) null else left)
            }
        }
    }

    /** /sgem open <菜单名> - 打开 gui.yml / rune.yml 中定义的工作台菜单 */
    @CommandBody
    val open = subCommand {
        dynamic("menu") {
            suggestion<CommandSender>(uncheck = true) { _, _ -> MenuRegistry.names().toList() }
            execute<Player> { sender, context, _ ->
                DebugUtil.log("Command", "${sender.name} 执行 /sgem open ${context["menu"]} (可用菜单=${MenuRegistry.names()})")
                WorkbenchMenu.open(sender, context["menu"])
            }
        }
    }

    /** /sgem give <玩家> <宝石ID> [数量] */
    @CommandBody
    val give = subCommand {
        dynamic("player") {
            dynamic("gem") {
                suggestion<CommandSender>(uncheck = true) { _, _ -> GemRegistry.ids().toList() }
                dynamic("amount") {
                    execute<CommandSender> { sender, context, _ ->
                        doGive(sender, context["player"], context["gem"], context["amount"].toIntOrNull() ?: 1)
                    }
                }
                execute<CommandSender> { sender, context, _ ->
                    doGive(sender, context["player"], context["gem"], 1)
                }
            }
        }
    }

    /**
     * /sgem debug              - 临时开关调试输出
     * /sgem debug <tag[,tag]>  - 只输出指定 tag, 传 all 恢复全部
     */
    @CommandBody
    val debug = subCommand {
        dynamic("tags") {
            suggestion<CommandSender>(uncheck = true) { _, _ ->
                listOf("all", "Registry", "Command", "Menu", "Workbench", "Embed", "GUI", "GemUse", "Protect", "GemManager", "ItemFactory", "Reward", "Skill", "SkillExec", "Buff", "Points", "Money")
            }
            execute<CommandSender> { sender, context, _ ->
                val raw = context["tags"]
                if (raw.equals("all", true)) {
                    DebugUtil.setTags(emptyList())
                    Lang.sendCommand(sender, "command.debug-scope-all")
                } else {
                    val list = raw.split(",", " ").filter { it.isNotBlank() }
                    DebugUtil.setTags(list)
                    Lang.sendCommand(sender, "command.debug-scope", "scope" to list.joinToString(","))
                }
            }
        }
        execute<CommandSender> { sender, _, _ ->
            val now = DebugUtil.toggle()
            val scope = if (DebugUtil.tags().isEmpty()) Lang.get("common.scope-all") else DebugUtil.tags().joinToString(",")
            if (now) Lang.sendCommand(sender, "command.debug-on", "scope" to scope)
            else Lang.sendCommand(sender, "command.debug-off")
        }
    }

    /** /sgem reload - 重新加载所有配置 */
    @CommandBody
    val reload = subCommand {
        execute<CommandSender> { sender, _, _ ->
            val start = System.currentTimeMillis()
            DebugUtil.log("Command", "${sender.name} 执行 /sgem reload")
            // 统一走主类的 reloadAll, 避免命令层和主类两份重载顺序不一致
            SnowyGems.reloadAll()
            val cost = System.currentTimeMillis() - start
            DebugUtil.log("Command", "重载完成, 耗时 ${cost}ms")
            Lang.sendCommand(sender, "command.reload", "time" to cost)
        }
    }

    private fun doGive(sender: CommandSender, playerName: String, gemId: String, amount: Int) {
        DebugUtil.log("Command", "${sender.name} 执行 /sgem give player=$playerName gem=$gemId amount=$amount")
        val target = Bukkit.getPlayer(playerName)
        if (target == null) {
            Lang.sendCommand(sender, "command.no-player", "player" to playerName)
            return
        }
        if (GemRegistry.get(gemId) == null) {
            Lang.sendCommand(sender, "command.no-gem", "gem" to gemId)
            return
        }
        GemManager.give(target, gemId, amount.coerceAtLeast(1))
        Lang.sendCommand(sender, "command.give", "player" to target.name, "gem" to gemId, "amount" to amount)
    }
}
