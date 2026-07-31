package mc233.`fun`.snowygems.compat

import mc233.`fun`.snowygems.config.GemRegistry
import mc233.`fun`.snowygems.config.SkillRegistry
import mc233.`fun`.snowygems.reward.RewardTokenParser
import mc233.`fun`.snowygems.skill.SkillFunctions
import mc233.`fun`.snowygems.skill.SkillLineParser
import mc233.`fun`.snowygems.util.DebugUtil
import mc233.`fun`.snowygems.util.ItemRequireMatcher
import taboolib.common.platform.function.info
import taboolib.common.platform.function.severe
import taboolib.common.platform.function.warning

/**
 * 配置自检 —— 加载完宝石/技能配置后, 把"当前版本跑不通的写法"一次性列出来。
 *
 * 解决的问题: 配置错误在游戏里表现为"宝石点了没反应", 服主根本不知道错在哪。
 * 现在改成加载时就报, 且报告直接说明是哪个文件的哪个条目、为什么不行。
 *
 * ## 两类问题必须区分开
 *
 * - [Level.VERSION] 写法本身没错, 只是当前服务端版本没有这个内容(如在 1.21.4 上写了矛)。
 *   这是**多版本配置的正常状态** —— 同一份配置发到不同版本的子服, 高版本内容在低版本上
 *   本就该静默跳过。所以只在 Debug 日志里记, 不打扰服主。
 * - [Level.MISTAKE] 名字拼错、函数不存在、漏写触发标记。这类**永远不会生效**, 必须报出来。
 *
 * 判据是 [FeatureModules] 的归属表: 归某个版本模块管的键 -> VERSION, 否则 -> MISTAKE。
 * 不做这个区分, 内置的新版本示例宝石会在 1.21.4 上刷出几十条"错误", 服主就再也不看这个报告了。
 *
 * 处理级别由 config.yml 的 `Compat.OnMissingFeature` 决定:
 *   skip   仅概要 + Debug 明细(默认)
 *   warn   MISTAKE 用 WARNING 输出, 排查配置时用
 *   strict MISTAKE 用 SEVERE 输出, 适合上线前自检
 *
 * 三种级别都**不阻止插件启动**: 宝石配置往往很大, 一条写错就拒绝启动会让整个服进不去,
 * 代价远高于收益; strict 的意义是"绝对不会被忽略的醒目报错"。
 */
object ConfigValidator {

    /** 问题的性质 —— 决定它是否需要打扰服主 */
    enum class Level {
        /** 当前版本没有这个内容, 换个版本就能用 —— 多版本配置的正常状态 */
        VERSION,

        /** 写错了, 任何版本都不会生效 */
        MISTAKE
    }

    data class Issue(val level: Level, val where: String, val what: String, val detail: String)

    private val issues = ArrayList<Issue>()

    /** 最近一次自检的全部问题 */
    fun lastIssues(): List<Issue> = issues.toList()

    /** 真正需要服主修的问题 */
    fun mistakes(): List<Issue> = issues.filter { it.level == Level.MISTAKE }

    /** 因版本而跳过的条目 */
    fun versionSkips(): List<Issue> = issues.filter { it.level == Level.VERSION }

    /** 跑一遍自检. 由 [mc233.fun.snowygems.Bootstrap.reloadAll] 在全部注册表就绪后调用 */
    fun validate() {
        issues.clear()
        validateGems()
        validateSkills()
        report()
    }

    // ── 宝石配置 ────────────────────────────────────────────

    private fun validateGems() {
        for (gem in GemRegistry.all()) {
            val where = "${gem.category}.yml -> ${gem.id}"
            for (entry in gem.require) {
                validateRequire(where, entry)
            }
            for (raw in gem.rewards) {
                if (raw.isNotBlank()) validateRewardLine(where, raw)
            }
        }
    }

    /**
     * 检查一条 Require.
     * 精确物品名在当前版本不存在时属于 VERSION(如 SPEAR), 分类关键字拼错属于 MISTAKE。
     */
    private fun validateRequire(where: String, entry: String) {
        val problem = ItemRequireMatcher.validate(entry) ?: return
        val name = entry.trim().uppercase()
        val module = FeatureModules.moduleOfMaterial(name)
        when {
            // 归某个版本模块管的物品: 当前版本没有它很正常
            module != null -> version(where, "Require: $entry", "物品属于 $module 模块, 当前服务端没有")
            // 服务端真有这个物品, 只是被停用的模块挡下了
            Registries.hasMaterialRaw(name) -> version(where, "Require: $entry", "物品被停用的模块挡下")
            else -> mistake(where, "Require: $entry", problem)
        }
    }

    /** 检查一行 Rewards. 只做"名字能不能解析"的静态检查, 不执行任何逻辑 */
    private fun validateRewardLine(where: String, raw: String) {
        val call = runCatching { RewardTokenParser.parseLine(raw).call }.getOrElse {
            mistake(where, "Rewards: $raw", "这一行语法无法解析: ${it.message}")
            return
        }
        when (call.name.lowercase()) {
            "attribute" -> call.args["name"]?.let { name ->
                if (Registries.attribute(name) == null) {
                    classify(
                        where, "Attribute{name=$name}", "属性",
                        AttributeAliases.candidatesOf(name) ?: listOf(name.lowercase()),
                        FeatureModules::moduleOfAttribute, Registries::hasAttributeRaw
                    )
                }
            }
            "enchant" -> call.args["name"]?.let { name ->
                if (Registries.enchantment(name) == null) {
                    classify(
                        where, "Enchant{name=$name}", "附魔",
                        listOf(EnchantAliases.keyOf(name) ?: name.lowercase()),
                        FeatureModules::moduleOfEnchantment, Registries::hasEnchantmentRaw
                    )
                }
            }
            "potion", "potionbuff" -> call.args["type"]?.let { name ->
                if (Registries.effect(name) == null) {
                    classify(
                        where, "Potion{type=$name}", "药水效果",
                        listOf(EffectAliases.keyOf(name) ?: name.lowercase()),
                        FeatureModules::moduleOfEffect, Registries::hasEffectRaw
                    )
                }
            }
        }
    }

    /**
     * 判定一个解析失败的名字该算 VERSION 还是 MISTAKE.
     *
     * @param keys 该写法对应的候选注册表键
     * @param moduleOf 键 -> 管辖它的版本模块名
     * @param existsRaw 键在服务端注册表里是否真的存在(不看模块门禁)
     */
    private inline fun classify(
        where: String,
        what: String,
        kind: String,
        keys: List<String>,
        moduleOf: (String) -> String?,
        existsRaw: (String) -> Boolean
    ) {
        keys.firstNotNullOfOrNull { moduleOf(it) }?.let { module ->
            version(where, what, "$kind 属于 $module 模块, 当前服务端没有或该模块已停用")
            return
        }
        if (keys.any(existsRaw)) {
            version(where, what, "$kind 被停用的模块挡下")
            return
        }
        mistake(
            where, what,
            "当前版本 ${ServerVersion.minecraftVersion} 的注册表里没有这个$kind, 请检查拼写"
        )
    }

    // ── 技能配置 ────────────────────────────────────────────

    private fun validateSkills() {
        for (def in SkillRegistry.all()) {
            val where = "skills -> ${def.id}"
            for (raw in def.skills) {
                if (raw.isNotBlank()) validateSkillLine(where, raw)
            }
        }
    }

    private fun validateSkillLine(where: String, raw: String) {
        val line = runCatching { SkillLineParser.parse(raw) }.getOrElse {
            mistake(where, raw, "这一行语法无法解析: ${it.message}")
            return
        }
        // RewardSwitch 是形态切换的容器, 由 Switch 自己解释, 不参与函数表校验
        if (line.name.equals("RewardSwitch", true)) return

        val function = SkillFunctions.find(line.name) ?: run {
            val suggestion = SkillFunctions.suggest(line.name)
            mistake(
                where, line.name,
                "不是已注册的技能函数" + if (suggestion.isEmpty()) " (用 /sgem skills 查看全部)"
                else ", 你是不是想写: ${suggestion.joinToString(" / ")}"
            )
            return
        }
        if (!function.isAvailable()) {
            version(where, line.name, "该函数在当前版本不可用: ${function.requires}")
        }
        // 没有触发标记的技能行永远不会被触发, 是典型笔误(忘了写 ~onUse)
        if (line.triggers.isEmpty() && !line.name.equals("Switch", true)) {
            mistake(
                where, raw.take(50),
                "这一行没有写触发标记(~onUse 之类), 永远不会被触发. 可用标记见 /sgem triggers"
            )
        }
    }

    // ── 记录与报告 ──────────────────────────────────────────

    private fun version(where: String, what: String, detail: String) {
        issues.add(Issue(Level.VERSION, where, what, detail))
    }

    private fun mistake(where: String, what: String, detail: String) {
        issues.add(Issue(Level.MISTAKE, where, what, detail))
    }

    private fun report() {
        val skips = versionSkips()
        val errors = mistakes()
        // 版本跳过是正常状态, 只给一行概要 + Debug 明细
        if (skips.isNotEmpty()) {
            info("配置中有 ${skips.size} 处内容当前版本不支持, 已跳过(属正常, 用 /sgem compat all 查看明细)")
            skips.forEach { DebugUtil.log("Compat", "  [跳过] [${it.where}] ${it.what} -> ${it.detail}") }
        }
        if (errors.isEmpty()) {
            DebugUtil.log("Compat", "配置自检通过, 未发现写错的条目")
            return
        }
        val mode = FeatureModules.onMissingFeature
        val header = "配置自检发现 ${errors.size} 处写错的条目(这些在任何版本都不会生效)"
        when (mode) {
            "strict" -> {
                severe(header)
                errors.forEach { severe("  [${it.where}] ${it.what} -> ${it.detail}") }
                severe("插件仍会启动, 但相关宝石/技能会静默跳过这些条目。")
            }
            "warn" -> {
                warning(header)
                errors.forEach { warning("  [${it.where}] ${it.what} -> ${it.detail}") }
            }
            else -> {
                info("$header —— 用 /sgem compat all 或把 Compat.OnMissingFeature 改为 warn 查看明细")
                errors.forEach { DebugUtil.log("Compat", "  [${it.where}] ${it.what} -> ${it.detail}") }
            }
        }
    }
}
