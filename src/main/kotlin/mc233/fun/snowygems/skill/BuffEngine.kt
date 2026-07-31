package mc233.`fun`.snowygems.skill

import mc233.`fun`.snowygems.config.SkillRegistry
import mc233.`fun`.snowygems.util.ColorUtil
import mc233.`fun`.snowygems.util.DebugUtil
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import taboolib.common.platform.Schedule

/**
 * 简化版 BUFF 引擎: 每秒扫描在线玩家的装备/手持物品, 若其 Lore 命中某个技能/BUFF 的 [Lore]
 * 标记文本, 则执行该定义中带 ~onTimer 标记的 PotionBuff{...} 技能行.
 * (完整的 Kether 式技能引擎不在本次实现范围内, 这里只覆盖 skills/DefaultBuffs.yml 中出现的用法)
 *
 * 定时任务用 TabooLib 的 [Schedule] 注解声明, 服务器调度器就绪后自动开始, 主类不需要调 start().
 */
object BuffEngine {

    /** 每 20 tick(1 秒) 扫描一次. 同步执行: 药水效果必须在主线程施加 */
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
                val timerLines = def.skills.map { SkillLineParser.parse(it) }.filter { it.triggers.contains("onTimer") }
                if (timerLines.isEmpty()) continue
                DebugUtil.logChanged(
                    "Buff", "match:${player.uniqueId}:${item.type}:${def.id}",
                    "${player.name} 的 ${item.type} 命中 BUFF 定义 ${def.id}, ${timerLines.size} 行 onTimer (相同内容不再重复输出)"
                )
                for (line in timerLines) {
                    execute(player, item, line)
                }
            }
        }
    }

    private fun execute(player: Player, item: ItemStack, line: SkillLine) {
        when (line.name.lowercase()) {
            "potionbuff" -> {
                val typeName = line.args["type"] ?: run {
                    DebugUtil.log("Buff", "  PotionBuff 缺少 type 参数, 跳过")
                    return
                }
                val type = resolveType(typeName) ?: run {
                    DebugUtil.logChanged(
                        "Buff", "badtype:$typeName",
                        "  无法识别的药水效果名: $typeName (请使用 1.21 的效果ID, 如 SPEED/SLOWNESS/REGENERATION)"
                    )
                    return
                }
                // BUFF 引擎每秒(20tick)扫描并续期一次, 时长设为 9 秒即可: 既覆盖到下次刷新,
                // 又能在脱下装备后 ~9 秒内自然消失. 配置未写 duration 时用此默认值.
                val durationSeconds = SkillValue.resolveInt(line.args["duration"] ?: "9", item, 9)
                val rawLevel = line.args["level"] ?: "1"
                val level = SkillValue.resolveInt(rawLevel, item, 1).coerceAtLeast(1)
                DebugUtil.logChanged(
                    "Buff", "apply:${player.uniqueId}:${type.name}",
                    "  给 ${player.name} 施加 ${type.name} 等级=$level (level原始=$rawLevel 解析=${SkillValue.resolve(rawLevel, item)}) " +
                        "时长=${durationSeconds}s (每秒续期, 相同内容不再重复输出)"
                )
                player.addPotionEffect(PotionEffect(type, (durationSeconds * 20).coerceAtLeast(40), level - 1, true, false))
            }
            else -> DebugUtil.logChanged("Buff", "unimpl:${line.name}", "  未实现的 BUFF 函数: ${line.name} (当前仅支持 PotionBuff)")
        }
    }

    private fun resolveType(name: String): PotionEffectType? {
        val raw = name.trim()
        // 老配置里的 1.8~1.13 旧效果名 -> 现代注册表名
        val mapped = LEGACY_TYPES[raw.uppercase()] ?: raw
        for (c in listOf(mapped.uppercase(), mapped.lowercase(), raw.uppercase(), raw.lowercase())) {
            @Suppress("DEPRECATION")
            val byName = PotionEffectType.getByName(c)
            if (byName != null) return byName
        }
        // 命名空间键兜底
        for (key in listOf(mapped.lowercase(), raw.lowercase())) {
            try {
                @Suppress("DEPRECATION")
                val byKey = org.bukkit.Registry.EFFECT.get(org.bukkit.NamespacedKey.minecraft(key))
                if (byKey != null) return byKey
            } catch (ignored: Exception) {
            }
        }
        // 反射静态字段(在仍保留旧字段的版本上兜底)
        return try {
            @Suppress("DEPRECATION")
            PotionEffectType::class.java.getField(mapped.uppercase()).get(null) as? PotionEffectType
        } catch (e: Exception) {
            null
        }
    }

    /** 旧药水效果名 -> 现代注册表名. 1.20.5+ 起旧名(DAMAGE_RESISTANCE 等)被移除 */
    private val LEGACY_TYPES = mapOf(
        "DAMAGE_RESISTANCE" to "RESISTANCE",
        "FAST_DIGGING" to "HASTE",
        "SLOW_DIGGING" to "MINING_FATIGUE",
        "INCREASE_DAMAGE" to "STRENGTH",
        "JUMP" to "JUMP_BOOST",
        "MOVEMENT_SPEED" to "SPEED",
        "SLOW" to "SLOWNESS",
        "HEAL" to "INSTANT_HEALTH",
        "HARM" to "INSTANT_DAMAGE",
        "CONFUSION" to "NAUSEA",
        "UNLUCK" to "UNLUCK"
    )
}