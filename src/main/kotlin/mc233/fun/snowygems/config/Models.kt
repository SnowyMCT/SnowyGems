package mc233.`fun`.snowygems.config

import mc233.`fun`.snowygems.reward.ParsedReward
import mc233.`fun`.snowygems.skill.SkillLine

/**
 * 宝石的三种基础形态:
 *  - NORMAL:      放入镶嵌台/工作台, 先点宝石再点装备 (强化/镶嵌/粉尘)
 *  - PLAYER_GEM:  玩家手持右键(或吃/喝)直接生效, 不需要目标装备 (兑换券/药水)
 *  - RANDOM_GEM:  右键后按权重从 [randomPool] 中抽取一个子宝石并当场生效
 */
enum class GemType {
    NORMAL, PLAYER_GEM, RANDOM_GEM;

    companion object {
        fun parse(raw: String?): GemType {
            if (raw.isNullOrBlank()) return NORMAL
            val n = raw.uppercase().replace("_", "").replace("-", "")
            return when (n) {
                "PLAYERGEM" -> PLAYER_GEM
                "RANDOMGEM" -> RANDOM_GEM
                else -> NORMAL
            }
        }
    }
}

/**
 * 对应 gems 目录下 yml 文件中的一个宝石(或强化石/粉尘/药水/兑换券)配置节点
 */
data class GemConfig(
    val id: String,
    val name: String,
    val type: GemType = GemType.NORMAL,
    val require: List<String> = emptyList(),
    val display: String = "",
    val tips: List<String> = emptyList(),
    val texture: String? = null,
    val material: String? = null,
    val glow: Boolean = false,
    val success: Int = 100,
    val embed: Int = 0,
    val color: String? = null,
    val eat: Boolean = false,
    val successTip: String? = null,
    val removeTip: String? = null,
    val failTip: String? = null,
    val rewards: List<String> = emptyList(),
    /**
     * [rewards] 的预解析结果, 注册表加载时一次性解析, 避免每次镶嵌/使用都重复解析配置行.
     * 与 [rewards] 一一对应(跳过空白行), 仅作执行用; 校验/报告仍读原始 [rewards].
     */
    val parsedRewards: List<ParsedReward> = emptyList(),
    /** 仅 RANDOM_GEM 使用: 子宝石ID -> 权重 */
    val randomPool: Map<String, Int> = emptyMap(),
    /** 该宝石限定只能在这些界面里使用(配置里的 `Gui:` 列表), 为空表示通用宝石镶嵌台受理 */
    val gui: List<String> = emptyList(),
    /** 分类, 来自其所在的配置文件名(不含扩展名), 用于 /sgem view 分类浏览 */
    val category: String = "Other"
)

data class MenuItemDef(
    val char: Char,
    val type: String,
    val material: String? = null,
    val display: String? = null,
    val glow: Boolean = false,
    val tips: List<String> = emptyList(),
    val require: List<String> = emptyList(),
    val amount: Int = 1,
    val texture: String? = null,
    val gem: String? = null,
    val gui: String? = null
)

data class MenuLayout(
    val name: String,
    val title: String,
    val page: Int = 1,
    val rows: List<String>,
    val items: Map<Char, MenuItemDef>
) {
    val size get() = rows.size * 9
}

data class SkillDef(
    val id: String,
    val lore: String? = null,
    val cooldown: Double = 0.0,
    val cooldownTip: String? = null,
    val skills: List<String> = emptyList(),
    /** [skills] 的预解析结果, 注册表加载时一次性解析, 避免每次触发事件都重复解析全部技能行 */
    val parsedSkills: List<SkillLine> = emptyList(),
    /** 预解析后按触发标记分组的技能行(一行多触发会出现在多组), 事件触发时 O(1) 取用 */
    val byTrigger: Map<String, List<SkillLine>> = emptyMap(),
    /** 预解析后的 onTimer 行, BUFF 引擎每秒 tick 直接取用, 不再每次 filter */
    val timerLines: List<SkillLine> = emptyList(),
    /** [lore] 去色并 trim 后的触发标记, 触发匹配时直接 contains, 避免每次事件都重复去色 */
    val loreClean: String = ""
)
