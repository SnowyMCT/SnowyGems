package mc233.`fun`.snowygems.compat

import mc233.`fun`.snowygems.skill.SkillFunctions
import mc233.`fun`.snowygems.util.DebugUtil
import mc233.`fun`.snowygems.util.Lang
import taboolib.common.LifeCycle
import taboolib.common.platform.Awake
import taboolib.common.platform.function.info
import taboolib.common.platform.function.warning

/**
 * 版本能力报告 —— 启动时把"这个服务端到底支持什么"讲清楚
 *
 * 目的是让服主在**配置写错之前**就知道自己的版本有什么、没有什么, 而不是等游戏里
 * 某颗宝石没反应再去翻日志
 *
 * 探测的东西全部来自服务端注册表, 不含任何版本号硬编码, 因此在 1.21.4 → 26.2
 * 乃至更新的版本上都能给出正确结果
 */
object CompatReport {

    /**
     * 本插件会用到、且在不同版本间有增删的能力点
     * 这张表只用于**报告**, 不参与任何功能判定 —— 功能一律现场查注册表
     *
     * 三元组: 显示名 / 探测方式 / 引入版本(仅供服主参考)
     */
    private val PROBES: List<Triple<String, () -> Boolean, String>> = listOf(
        // ── 物品 ──────────────────────────────────────────
        Triple("锤 MACE", { Registries.hasMaterialRaw("MACE") }, "1.21"),
        // 矛是分材质命名的物品(WOODEN_SPEAR … NETHERITE_SPEAR), 没有单一 "SPEAR" 材质,
        // 所以探测必须看"有没有任何以 _SPEAR 结尾的材质", 不能只查 hasMaterialRaw("SPEAR")。
        Triple("矛 SPEAR", { Registries.materials.any { it == "SPEAR" || it.endsWith("_SPEAR") } }, "1.21.11"),
        Triple("铜盔甲", { Registries.hasMaterialRaw("COPPER_HELMET") }, "1.21.9"),
        Triple("铜工具", { Registries.hasMaterialRaw("COPPER_PICKAXE") }, "1.21.9"),
        Triple("狼铠 WOLF_ARMOR", { Registries.hasMaterialRaw("WOLF_ARMOR") }, "1.20.5"),
        Triple("刷子 BRUSH", { Registries.hasMaterialRaw("BRUSH") }, "1.20"),

        // ── 属性(用户点名要求的三个都在这) ────────────────
        Triple("属性 scale(体型)", { Registries.hasAttributeRaw("scale") }, "1.20.5"),
        Triple("属性 block_interaction_range(方块交互距离)", { Registries.hasAttributeRaw("block_interaction_range") }, "1.20.5"),
        Triple("属性 entity_interaction_range(实体交互距离)", { Registries.hasAttributeRaw("entity_interaction_range") }, "1.20.5"),
        Triple("属性 submerged_mining_speed(水下挖掘)", { Registries.hasAttributeRaw("submerged_mining_speed") }, "1.21.2"),
        Triple("属性 mining_efficiency(挖掘效率)", { Registries.hasAttributeRaw("mining_efficiency") }, "1.21.2"),
        Triple("属性 movement_efficiency(移动效率)", { Registries.hasAttributeRaw("movement_efficiency") }, "1.21.2"),
        Triple("属性 jump_strength(跳跃力)", { Registries.hasAttributeRaw("jump_strength") }, "1.20.5"),
        Triple("属性 gravity(重力)", { Registries.hasAttributeRaw("gravity") }, "1.20.5"),
        Triple("属性 max_absorption(伤害吸收上限)", { Registries.hasAttributeRaw("max_absorption") }, "1.20.5"),
        Triple("属性 burning_time(燃烧时长)", { Registries.hasAttributeRaw("burning_time") }, "1.21.2"),
        Triple("属性 sweeping_damage_ratio(横扫伤害)", { Registries.hasAttributeRaw("sweeping_damage_ratio") }, "1.21"),
        Triple("属性 waypoint_transmit_range(路径点)", { Registries.hasAttributeRaw("waypoint_transmit_range") }, "26.x"),
        Triple("属性 camera_distance(视角距离)", { Registries.hasAttributeRaw("camera_distance") }, "26.x"),

        // ── 附魔 ──────────────────────────────────────────
        Triple("附魔 density(密度)", { Registries.hasEnchantmentRaw("density") }, "1.21"),
        Triple("附魔 breach(穿透)", { Registries.hasEnchantmentRaw("breach") }, "1.21"),
        Triple("附魔 wind_burst(风爆)", { Registries.hasEnchantmentRaw("wind_burst") }, "1.21"),
        Triple("附魔 lunge(突进/矛专属)", { Registries.hasEnchantmentRaw("lunge") }, "1.21.11"),

        // ── 药水效果 ──────────────────────────────────────
        Triple("效果 oozing(渗浆)", { Registries.hasEffectRaw("oozing") }, "1.21"),
        Triple("效果 infested(寄生)", { Registries.hasEffectRaw("infested") }, "1.21"),
        Triple("效果 weaving(盘丝)", { Registries.hasEffectRaw("weaving") }, "1.21"),
        Triple("效果 wind_charged(蓄风)", { Registries.hasEffectRaw("wind_charged") }, "1.21"),
        Triple("效果 trial_omen(试炼预兆)", { Registries.hasEffectRaw("trial_omen") }, "1.21"),
        Triple("效果 raid_omen(袭击预兆)", { Registries.hasEffectRaw("raid_omen") }, "1.21")
    )

    /** 探测结果: 显示名 -> 是否可用 */
    val results: Map<String, Boolean> by lazy {
        PROBES.associate { (label, probe, _) ->
            label to runCatching { probe() }.getOrDefault(false)
        }
    }

    /** 引入版本标注, 供报告展示 */
    val sinceOf: Map<String, String> by lazy {
        PROBES.associate { (label, _, since) -> label to since }
    }

    /**
     * 启动时打印一份简报.
     *
     * 用 ACTIVE 阶段: 注册表要等服务端完全启动才是最终状态(数据包附魔也要这时候才注册完)
     */
    @Awake(LifeCycle.ACTIVE)
    fun report() {
        if (!FeatureModules.reportOnStartup) {
            DebugUtil.log("Compat", "Compat.ReportOnStartup=false, 跳过启动简报(仍可用 /sgem compat 查看)")
            return
        }
        info("服务端 ${ServerVersion.serverName}")
        info("兼容层: ${Registries.describe()} · ${AttributeCompat.describe()}")
        val supported = results.filterValues { it }
        val missing = results.filterValues { !it }
        info("版本能力: 支持 ${supported.size} 项, 当前版本没有 ${missing.size} 项")
        // 详细清单只在 Debug 下输出, 平时不刷屏
        if (DebugUtil.enabled) {
            supported.keys.forEach { DebugUtil.log("Compat", "  ✔ $it") }
            missing.keys.forEach { DebugUtil.log("Compat", "  ✘ $it (需要 ${sinceOf[it]})") }
        }
        val offModules = FeatureModules.disabledModules()
        if (offModules.isNotEmpty()) {
            info("已停用的功能模块: ${offModules.joinToString(", ")} (config.yml 的 Compat.Modules)")
        }
        val unavailableFunctions = SkillFunctions.unavailable()
        if (unavailableFunctions.isNotEmpty()) {
            warning("以下技能函数在当前版本不可用: ${unavailableFunctions.map { it.name }}")
        }
    }

    /** 配置自检部分: 写错的条目总是列出, 版本跳过只在 verbose 下列 */
    private fun checkLines(verbose: Boolean): List<String> = buildList {
        val mistakes = ConfigValidator.mistakes()
        val skips = ConfigValidator.versionSkips()
        if (mistakes.isEmpty() && skips.isEmpty()) {
            add(Lang.get("compat.check-clean"))
            return@buildList
        }
        if (mistakes.isNotEmpty()) {
            add(Lang.get("compat.check-mistakes", "count" to mistakes.size))
            mistakes.forEach { add(Lang.get("compat.check-entry", "text" to it.line)) }
        }
        if (skips.isNotEmpty()) {
            add(Lang.get("compat.check-skips", "count" to skips.size))
            if (verbose) skips.forEach { add(Lang.get("compat.check-skip-entry", "text" to it.line)) }
        }
    }

    /**
     * 生成给玩家/控制台看的多行报告(供 /sgem compat 使用).
     *
     * 每一行的文案都来自 lang.yml 的 compat.* 节点, 代码只负责填数据
     * @param verbose 是否列出每一项能力的明细
     */
    fun lines(verbose: Boolean): List<String> = buildList {
        val supported = results.filterValues { it }.keys
        val missing = results.filterValues { !it }.keys
        add(Lang.get("compat.divider"))
        add(Lang.get("compat.title"))
        add(Lang.get("compat.server", "server" to ServerVersion.serverName))
        add(Lang.get("compat.version", "mc" to ServerVersion.minecraftVersion, "id" to ServerVersion.versionId))
        add(Lang.get("compat.registry", "registry" to Registries.describe()))
        add(Lang.get("compat.attribute-api", "api" to AttributeCompat.describe()))
        add("")
        add(Lang.get("compat.summary", "ok" to supported.size, "missing" to missing.size))
        if (verbose) {
            add("")
            supported.forEach { add(Lang.get("compat.entry-ok", "name" to it)) }
            missing.forEach {
                add(Lang.get("compat.entry-missing", "name" to it, "since" to (sinceOf[it] ?: "?")))
            }
        } else {
            if (missing.isNotEmpty()) {
                val brief = missing.take(6).joinToString("&7, &f") + if (missing.size > 6) " &7…" else ""
                add(Lang.get("compat.missing-brief", "list" to brief))
            }
            add(Lang.get("compat.more-hint"))
        }
        add("")
        // 功能模块启停状态: 详细模式逐个列出, 简略模式只报已停用的
        val modules = FeatureModules.states()
        if (verbose && modules.isNotEmpty()) {
            add(Lang.get("compat.modules-title"))
            modules.forEach { (name, on) ->
                add(Lang.get(if (on) "compat.module-on" else "compat.module-off", "name" to name))
            }
        } else {
            val off = FeatureModules.disabledModules()
            if (off.isNotEmpty()) {
                add(Lang.get("compat.modules-brief", "list" to off.joinToString("&7, &f")))
            }
        }
        add("")
        add(
            Lang.get(
                "compat.functions",
                "ok" to SkillFunctions.available().size,
                "bad" to SkillFunctions.unavailable().size
            )
        )
        // 配置自检结果 —— ConfigValidator 的日志提示服主"用 /sgem compat all 查看明细",
        // 所以这里必须真的展示, 否则那句提示是空头承诺
        addAll(checkLines(verbose))
        add(Lang.get("compat.divider"))
    }
}
