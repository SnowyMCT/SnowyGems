package mc233.`fun`.snowygems.util

import mc233.`fun`.snowygems.config.GemConfig
import org.bukkit.Color
import org.bukkit.inventory.ItemStack
import taboolib.library.xseries.XMaterial
import taboolib.module.nms.getItemTag
import taboolib.module.nms.ItemTagData
import taboolib.platform.util.buildItem

/** NBT key 常量, 全部使用扁平(不含 '.') 的键名 —— TabooLib 的 ItemTag[key]= 写入时,
 *  只要 key 含 '.' 就会被当作嵌套路径(putDeep)处理, 而普通读取 tag[key] 是浅层读取,
 *  两者不对称会导致写入后读不出来, 因此这里的 key 一律不使用 '.' */
object GemNbt {
    const val GEM_ID = "SnowyGemsGemId"
    const val GEM_TYPE = "SnowyGemsGemType"
}

object ItemFactory {

    /** 根据 GemConfig 构建一个可交易/可使用的宝石实体物品 */
    fun build(cfg: GemConfig, amount: Int = 1): ItemStack {
        // 如果配置了 Texture(头颅材质), 必须强制使用 PLAYER_HEAD 材质, 否则材质字符串不会生效
        // (TabooLib 只在物品是 SkullMeta 时才会应用 skullTexture, 其余材质会被静默忽略)
        val hasTexture = !cfg.texture.isNullOrBlank()
        val mat = when {
            hasTexture -> XMaterial.PLAYER_HEAD
            else -> XMaterial.matchXMaterial(cfg.material ?: "PAPER").orElse(XMaterial.PAPER)
        }
        DebugUtil.log("ItemFactory", "构建宝石 id=${cfg.id} material=${cfg.material} texture=${if (hasTexture) "有" else "无"} -> 实际材质=$mat")
        val item = buildItem(mat) {
            this.amount = amount
            name = cfg.display.ifBlank { cfg.name }
            lore.addAll(cfg.tips)
            if (cfg.glow) shiny()
            if (hasTexture) {
                skullTexture = taboolib.platform.util.SkullTexture(cfg.texture!!)
            }
            if (!cfg.color.isNullOrBlank()) {
                color = resolveColor(cfg.color)
            }
            colored()
        }
        val tag = item.getItemTag()
        tag[GemNbt.GEM_ID] = ItemTagData(cfg.id)
        tag[GemNbt.GEM_TYPE] = ItemTagData(cfg.type.name)
        tag.saveTo(item)
        DebugUtil.log("ItemFactory", "宝石 ${cfg.id} 构建完成, 回读校验 GemId=${getGemId(item)}")
        return item
    }

    fun getGemId(item: ItemStack?): String? {
        item ?: return null
        if (item.type.isAir) return null
        return item.getItemTag()[GemNbt.GEM_ID]?.asString()
    }

    private fun resolveColor(name: String): Color? {
        return try {
            Color::class.java.getField(name.uppercase()).get(null) as? Color
        } catch (e: Exception) {
            null
        }
    }
}
