package mc233.`fun`.snowygems.compat

import mc233.`fun`.snowygems.util.DebugUtil
import taboolib.module.configuration.Config
import taboolib.module.configuration.Configuration

/**
 * 按版本自动启停的功能模块
 *
 * config.yml 的 `Compat.Modules` 下每个模块可以取三种值:
 *   auto  —— 由服务端注册表决定: 该模块的代表性内容存在就启用, 不存在就停用(默认)
 *   true  —— 强制启用: 即使注册表里查不到也照常尝试, 失败时按 OnMissingFeature 处理
 *   false —— 强制停用: 即使服务端支持也不启用, 相关配置一律跳过
 *
 * 模块停用的实际效果: 归属该模块的属性/附魔/效果/物品分类, 在 [Registries] 查询时
 * 一律返回"不存在", 于是上层按既有的"跳过并提示"路径处理
 * 这样服主在混服(同一份配置发到不同版本的子服)时, 可以在低版本子服手动关掉高版本模块,
 * 避免控制台被"这个属性不存在"刷屏
 */
object FeatureModules {

    /** 模块定义: 模块名 -> (代表性能力的探测方式, 该模块管辖的注册表键) */
    private data class Module(
        val name: String,
        /** auto 模式下用它判断"服务端到底支不支持这个模块" */
        val probe: () -> Boolean,
        /** 归该模块管的属性键 */
        val attributes: Set<String> = emptySet(),
        /** 归该模块管的附魔键 */
        val enchantments: Set<String> = emptySet(),
        /** 归该模块管的药水效果键 */
        val effects: Set<String> = emptySet(),
        /** 归该模块管的物品名(精确匹配) */
        val materials: Set<String> = emptySet(),
        /**
         * 归该模块管的物品名后缀(如 "_SPEAR" 匹配 WOODEN_SPEAR/IRON_SPEAR/COPPER_SPEAR…)。
         * 矛这类**分材质等级**的新物品没有单一材质名, 只能靠后缀识别, 否则
         * hasMaterialRaw("SPEAR") 永远为 false, auto 模式检测不到 -> 矛兼容打不开。
         */
        val materialSuffixes: Set<String> = emptySet()
    )

    private val MODULES: List<Module> = listOf(
        Module(
            name = "Mace",
            probe = { Registries.hasMaterialRaw("MACE") },
            enchantments = setOf("density", "breach", "wind_burst"),
            materials = setOf("MACE")
        ),
        Module(
            name = "Spear",
            // 矛是分材质等级的物品(WOODEN_SPEAR/IRON_SPEAR/COPPER_SPEAR…), 没有单一 "SPEAR" 材质,
            // 所以探测要看"有没有任何以 _SPEAR 结尾(或恰为 SPEAR)的材质", 不能只查 hasMaterialRaw("SPEAR")。
            probe = { Registries.materials.any { it == "SPEAR" || it.endsWith("_SPEAR") } },
            enchantments = setOf("lunge"),  // 1.21.11 矛专属附魔「突进」
            materials = setOf("SPEAR"),
            materialSuffixes = setOf("_SPEAR")
        ),
        Module(
            name = "CopperEquipment",
            probe = { Registries.hasMaterialRaw("COPPER_HELMET") },
            materials = setOf(
                "COPPER_HELMET", "COPPER_CHESTPLATE", "COPPER_LEGGINGS", "COPPER_BOOTS",
                "COPPER_SWORD", "COPPER_PICKAXE", "COPPER_AXE", "COPPER_SHOVEL", "COPPER_HOE"
            )
        ),
        Module(
            name = "ModernAttributes",
            probe = { Registries.hasAttributeRaw("scale") },
            attributes = setOf(
                "scale", "block_interaction_range", "entity_interaction_range",
                "jump_strength", "gravity", "max_absorption", "safe_fall_distance",
                "fall_damage_multiplier", "explosion_knockback_resistance",
                "water_movement_efficiency", "burning_time", "oxygen_bonus",
                "sweeping_damage_ratio"
            )
        ),
        Module(
            name = "MiningAttributes",
            probe = { Registries.hasAttributeRaw("submerged_mining_speed") },
            attributes = setOf(
                "submerged_mining_speed", "mining_efficiency", "movement_efficiency",
                "block_break_speed", "sneaking_speed", "tempt_range"
            )
        ),
        Module(
            name = "TrialEffects",
            probe = { Registries.hasEffectRaw("oozing") },
            effects = setOf("oozing", "infested", "weaving", "wind_charged", "trial_omen", "raid_omen")
        ),
        Module(
            name = "WaypointAttributes",
            probe = { Registries.hasAttributeRaw("waypoint_transmit_range") },
            attributes = setOf("waypoint_transmit_range", "waypoint_receive_range", "camera_distance")
        )
    )

    @Config(value = "config.yml", autoReload = true, migrate = true)
    lateinit var conf: Configuration

    private var enabled: Map<String, Boolean> = emptyMap()

    private var attributeOwner: Map<String, String> = emptyMap()
    private var enchantmentOwner: Map<String, String> = emptyMap()
    private var effectOwner: Map<String, String> = emptyMap()
    private var materialOwner: Map<String, String> = emptyMap()

    private var materialSuffixOwner: Map<String, String> = emptyMap()

    var onMissingFeature: String = "skip"
        private set

    var reportOnStartup: Boolean = true
        private set

    fun resolve() {
        if (!::conf.isInitialized) {
            DebugUtil.log("Compat", "config.yml 尚未注入, 全部模块按 auto 处理")
        }
        onMissingFeature = readString("Compat.OnMissingFeature", "skip").lowercase()
        reportOnStartup = readBoolean("Compat.ReportOnStartup", true)

        val result = LinkedHashMap<String, Boolean>()
        for (module in MODULES) {
            val raw = readString("Compat.Modules.${module.name}", "auto").lowercase()
            val supported = runCatching { module.probe() }.getOrDefault(false)
            val on = when (raw) {
                "true", "on", "yes", "enable", "enabled" -> true
                "false", "off", "no", "disable", "disabled" -> false
                else -> supported
            }
            result[module.name] = on
            DebugUtil.log(
                "Compat",
                "模块 ${module.name}: 配置=$raw 服务端支持=$supported -> ${if (on) "启用" else "停用"}"
            )
        }
        enabled = result
        buildOwnerIndex()
        // 门禁变了, 物品分类索引必须重建 —— 否则关掉 Spear 后重载, Require:[WEAPON] 仍会算上矛
        ItemCategories.invalidate()
    }

    /** 建立"注册表键 -> 模块"的反查表 */
    private fun buildOwnerIndex() {
        val attr = HashMap<String, String>()
        val ench = HashMap<String, String>()
        val eff = HashMap<String, String>()
        val mat = HashMap<String, String>()
        val suffix = HashMap<String, String>()
        for (module in MODULES) {
            module.attributes.forEach { attr[it] = module.name }
            module.enchantments.forEach { ench[it] = module.name }
            module.effects.forEach { eff[it] = module.name }
            module.materials.forEach { mat[it] = module.name }
            module.materialSuffixes.forEach { suffix[it.uppercase()] = module.name }
        }
        // 把服务端实际存在、且命中某个模块后缀的物品也纳入 materialOwner,
        // 这样 IRON_SPEAR / COPPER_SPEAR 等具体矛材质都会被 Spear 模块正确门禁与归类。
        for (name in Registries.materials) {
            if (mat.containsKey(name)) continue
            val owner = suffix.entries.firstOrNull { name.endsWith(it.key) }?.value ?: continue
            mat[name] = owner
        }
        attributeOwner = attr
        enchantmentOwner = ench
        effectOwner = eff
        materialOwner = mat
        materialSuffixOwner = suffix
    }

    // ── 门禁查询(供 Registries 调用) ────────────────────────

    /** 某个模块是否启用. 未知模块名按启用处理 */
    fun isEnabled(module: String): Boolean = enabled[module] ?: true

    /** 该属性键是否被某个已停用的模块挡住; 返回挡住它的模块名, 没被挡返回 null */
    fun blockedAttribute(key: String): String? = blockedBy(attributeOwner[key])

    fun blockedEnchantment(key: String): String? = blockedBy(enchantmentOwner[key])

    fun blockedEffect(key: String): String? = blockedBy(effectOwner[key])

    fun blockedMaterial(name: String): String? = blockedBy(materialOwner[name])

    private fun blockedBy(module: String?): String? {
        if (module == null) return null
        return if (isEnabled(module)) null else module
    }

    /** 全部模块的启停状态, 供 /sgem compat 展示 */
    fun states(): Map<String, Boolean> = enabled

    /** 已停用的模块名 */
    fun disabledModules(): List<String> = enabled.filterValues { !it }.keys.toList()

    // ── 归属查询(不看启停, 只看"这东西是不是某个版本模块管的") ──
    // 配置自检要靠这些区分两种失败: "写了个高版本才有的东西"(正常, 跳过就行)
    // 与"名字拼错了"(真错误, 必须提醒服主)

    /** 该物品名是否为某个版本模块管辖(如 SPEAR 属于 Spear 模块), 返回模块名 */
    fun moduleOfMaterial(name: String): String? {
        val key = name.trim().uppercase()
        materialOwner[key]?.let { return it }
        // 精确名没命中时按后缀兜底(IRON_SPEAR -> Spear), 供配置自检区分"版本没有" vs "拼错了"
        return materialSuffixOwner.entries.firstOrNull { key.endsWith(it.key) }?.value
    }

    /** 该属性键是否为某个版本模块管辖 */
    fun moduleOfAttribute(key: String): String? = attributeOwner[key]

    /** 该附魔键是否为某个版本模块管辖 */
    fun moduleOfEnchantment(key: String): String? = enchantmentOwner[key]

    /** 该药水效果键是否为某个版本模块管辖 */
    fun moduleOfEffect(key: String): String? = effectOwner[key]

    // ── 配置读取(带 lateinit 保护) ──────────────────────────

    private fun readString(path: String, default: String): String =
        runCatching { if (::conf.isInitialized) conf.getString(path, default) ?: default else default }
            .getOrDefault(default)

    private fun readBoolean(path: String, default: Boolean): Boolean =
        runCatching { if (::conf.isInitialized) conf.getBoolean(path, default) else default }
            .getOrDefault(default)
}
