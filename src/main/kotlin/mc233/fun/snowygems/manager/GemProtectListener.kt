package mc233.`fun`.snowygems.manager

import mc233.`fun`.snowygems.util.Lang
import mc233.`fun`.snowygems.util.DebugUtil
import mc233.`fun`.snowygems.util.ItemFactory
import org.bukkit.Material
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.inventory.PrepareItemCraftEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.inventory.EquipmentSlot
import taboolib.common.platform.event.SubscribeEvent

object GemProtectListener {

    /** 禁止把宝石当方块放置(头颅/告示牌等任何材质都一样拦) */
    @SubscribeEvent
    fun onPlace(e: BlockPlaceEvent) {
        val gemId = ItemFactory.getGemId(e.itemInHand) ?: return
        e.isCancelled = true
        DebugUtil.log("Protect", "阻止 ${e.player.name} 把宝石 $gemId (${e.itemInHand.type}) 放置为方块")
        warnPlace(e.player)
    }

    /** 禁止把宝石塞进展示框(展示框内的物品不保留自定义 NBT, 会导致宝石失效) */
    @SubscribeEvent
    fun onPutInItemFrame(e: PlayerInteractEntityEvent) {
        val frame = e.rightClicked as? ItemFrame ?: return
        // 框里已经有东西时, 右键是旋转而不是放入, 不用拦
        if (!frame.item.type.isAir) return
        val hand = if (e.hand == EquipmentSlot.OFF_HAND) e.player.inventory.itemInOffHand
        else e.player.inventory.itemInMainHand
        val gemId = ItemFactory.getGemId(hand) ?: return
        e.isCancelled = true
        DebugUtil.log("Protect", "阻止 ${e.player.name} 把宝石 $gemId 放进展示框")
        warnPlace(e.player)
    }


    /** 禁止食用宝石 */
    @SubscribeEvent
    fun onConsume(e: PlayerItemConsumeEvent) {
        val item = e.item ?: return
        val gemId = ItemFactory.getGemId(item) ?: return
        e.isCancelled = true
        DebugUtil.log("Protect", "阻止 ${e.player.name} 吃掉宝石 $gemId (${item.type})")
        warnEat(e.player)
    }

    /** 禁止拿着末影之眼右键*/
    @SubscribeEvent
    fun onInteract(e: PlayerInteractEvent) {
        if (e.action != org.bukkit.event.block.Action.RIGHT_CLICK_AIR &&
            e.action != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
        ) {
            return
        }
        if (e.hand != EquipmentSlot.HAND) return
        val item = e.player.inventory.itemInMainHand
        if (item.type != Material.ENDER_EYE) return
        val gemId = ItemFactory.getGemId(item) ?: return
        e.isCancelled = true
        DebugUtil.log("Protect", "阻止 ${e.player.name} 投掷末影之眼宝石 $gemId")
    }

    /**
     * 禁止用宝石进行合成（防止宝石被当作合成材料消耗掉）
     * 在合成准备阶段阻止
     */
    @SubscribeEvent
    fun onPrepareCraft(e: PrepareItemCraftEvent) {
        // 检查合成物品中是否包含宝石
        val matrix = e.inventory?.matrix ?: return

        for (item in matrix) {
            if (item != null && !item.type.isAir) {
                val gemId = ItemFactory.getGemId(item)
                if (gemId != null) {
                    // 发现宝石在合成矩阵中，清空合成结果
                    e.inventory?.result = null
                    DebugUtil.log("Protect", "阻止合成操作 - 合成格中检测到宝石 $gemId")
                    return
                }
            }
        }
    }

    /**
     * 禁止从合成结果中取出宝石
     * 如果玩家尝试从合成台取出含有宝石的结果，阻止这个操作
     */
    @SubscribeEvent
    fun onCraft(e: CraftItemEvent) {
        // 检查合成结果是否是宝石
        val result = e.recipe?.result ?: return

        // 如果合成结果是宝石，或者合成材料中有宝石
        val gemId = ItemFactory.getGemId(result)
        if (gemId != null) {
            e.isCancelled = true
            DebugUtil.log("Protect", "阻止 ${e.whoClicked.name} 从工作台取出宝石 $gemId")
//            if (e.whoClicked is Player) {
//                warn(e.whoClicked as Player)
//            }
            return
        }

        // 检查合成材料中是否有宝石（额外安全检查）
        val matrix = e.inventory?.matrix ?: return
        for (item in matrix) {
            if (item != null && !item.type.isAir) {
                val id = ItemFactory.getGemId(item)
                if (id != null) {
                    e.isCancelled = true
                    DebugUtil.log("Protect", "阻止 ${e.whoClicked.name} 使用宝石 $id 进行合成")
//                    if (e.whoClicked is Player) {
//                        warn(e.whoClicked as Player)
//                    }
                    return
                }
            }
        }
    }
    // 放置宝石 / 宝石塞展示框里时给玩家的输出信息
    private fun warnPlace(player: Player) {
        Lang.send(player, "gem.protect-place")
    }

    // 尝试食用宝石时给玩家的输出信息
    private fun warnEat(player: Player) {
        Lang.send(player, "gem.protect-eat")
    }
}
