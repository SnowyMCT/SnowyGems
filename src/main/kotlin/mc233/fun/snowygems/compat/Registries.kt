package mc233.`fun`.snowygems.compat

import mc233.`fun`.snowygems.util.DebugUtil
import org.bukkit.Keyed
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.attribute.Attribute
import org.bukkit.enchantments.Enchantment
import org.bukkit.potion.PotionEffectType

/**
 * 统一的注册表查询层 —— 多版本兼容的核心。
 *
 * 设计原则: **不猜版本, 只问服务端**。
 *
 * 1.21.4 → 26.2 之间, Mojang 持续新增属性(scale / block_interaction_range /
 * submerged_mining_speed…)、物品(矛 SPEAR、铜盔甲、锤 MACE)、附魔和药水效果。
 * 如果用 `if (version >= X) 支持Y` 的写法, 每出一个新版本都要改代码, 而且一旦版本号
 * 解析方式变了(比如 26.1 这种新命名)判断就全错。
 *
 * 这里改成: 启动时把服务端**当前实际存在**的注册表内容读成快照, 之后所有查询都基于它。
 * 服务端有就能用, 没有就优雅降级并给出可读日志, 不抛异常、不影响别的宝石。
 *
 * 好处:
 *   - 新版本加了什么, 插件自动就支持, 无需改代码
 *   - 老版本没有的东西, 配置里写了只跳过这一条并提示, 不会连锁失败
 *   - 服主用数据包自定义的附魔/属性同样能被识别(它们也在注册表里)
 */
object Registries {

    // ── 注册表快照 ──────────────────────────────────────────

    /** key(小写, 无命名空间) -> Attribute. 例: max_health / scale / block_interaction_range */
    val attributes: Map<String, Attribute> by lazy { snapshot("属性") { Registry.ATTRIBUTE } }

    /** key -> Enchantment. 例: sharpness / density / breach / wind_burst */
    val enchantments: Map<String, Enchantment> by lazy { snapshot("附魔") { Registry.ENCHANTMENT } }

    /** key -> PotionEffectType. 例: speed / oozing / infested / weaving / wind_charged */
    val effects: Map<String, PotionEffectType> by lazy { snapshot("药水效果") { Registry.EFFECT } }

    /** 当前服务端存在的所有 Material 名(大写). 用于判断 SPEAR / COPPER_HELMET / MACE 是否可用 */
    val materials: Set<String> by lazy {
        runCatching { Material.entries.mapTo(HashSet()) { it.name } }
            .onFailure { DebugUtil.err("Compat", "读取 Material 列表失败", it) }
            .getOrDefault(emptySet())
    }

    // ── 查询入口 ────────────────────────────────────────────

    /**
     * 按名字找属性. 依次尝试:
     *   1. 项目内的友好简写(health / move / scale …, 见 [AttributeAliases])
     *   2. 现代命名空间键(max_health / minecraft:scale)
     *   3. 老版本的静态字段名(GENERIC_MAX_HEALTH), 剥掉前缀后当键查
     *
     * 命中的键若归属于被 config.yml 停用的模块, 视为"不存在"并记一条日志 —— 混服场景下
     * 服主可以在低版本子服关掉高版本模块, 不必改配置。
     */
    fun attribute(name: String): Attribute? =
        lookup(name, attributes, "属性", AttributeAliases::candidatesOf, FeatureModules::blockedAttribute)

    /** 按名字找附魔, 支持现代键 / 旧 Bukkit 常量名(见 [EnchantAliases]) */
    fun enchantment(name: String): Enchantment? =
        lookup(name, enchantments, "附魔", { EnchantAliases.keyOf(it)?.let(::listOf) }, FeatureModules::blockedEnchantment)

    /** 按名字找药水效果, 支持现代键 / 旧常量名(见 [EffectAliases]) */
    fun effect(name: String): PotionEffectType? =
        lookup(name, effects, "药水效果", { EffectAliases.keyOf(it)?.let(::listOf) }, FeatureModules::blockedEffect)

    /** 该物品在当前版本是否存在且未被模块停用(如 1.21.11 才有的 SPEAR) */
    fun hasMaterial(name: String): Boolean {
        val key = name.trim().uppercase()
        if (key !in materials) return false
        val blocker = FeatureModules.blockedMaterial(key) ?: return true
        DebugUtil.logChanged("Compat", "block:物品:$key", blockMessage("物品", key, key, blocker))
        return false
    }

    /** 取 Material, 当前版本不存在或被模块停用则返回 null(不抛 IllegalArgumentException) */
    fun material(name: String): Material? {
        val key = name.trim().uppercase()
        if (!hasMaterial(key)) return null
        return runCatching { Material.valueOf(key) }.getOrNull()
    }

    // ── 不经模块门禁的原始查询 ──────────────────────────────
    // [FeatureModules] 要靠这些判断"服务端到底支不支持某模块", 若再走门禁就成了循环依赖;
    // [CompatReport] 也用它们报告服务端的真实能力(而非门禁后的结果)。

    fun hasAttributeRaw(key: String): Boolean = key in attributes

    fun hasEnchantmentRaw(key: String): Boolean = key in enchantments

    fun hasEffectRaw(key: String): Boolean = key in effects

    fun hasMaterialRaw(name: String): Boolean = name.trim().uppercase() in materials

    /** 供启动日志用的一行概要 */
    fun describe(): String =
        "属性 ${attributes.size} · 附魔 ${enchantments.size} · 效果 ${effects.size} · 物品 ${materials.size}"

    /** 解析 NamespacedKey, 非法输入返回 null */
    fun keyOf(raw: String): NamespacedKey? = runCatching {
        val k = raw.trim().lowercase()
        if (k.contains(':')) {
            val (ns, path) = k.split(':', limit = 2)
            NamespacedKey(ns, path)
        } else {
            NamespacedKey.minecraft(k)
        }
    }.getOrNull()

    // ── 内部实现 ────────────────────────────────────────────

    /**
     * 三种注册表的查询逻辑完全一致, 收在这里:
     * 先按别名表给出的候选键查, 再把用户写的名字本身当键查(顺带剥掉 GENERIC_ 之类的老前缀),
     * 命中后过一遍模块门禁。
     *
     * @param aliases 别名表: 用户写法 -> 候选注册表键
     * @param blocker 门禁: 注册表键 -> 挡下它的模块名(null 表示放行)
     */
    private inline fun <T> lookup(
        name: String,
        table: Map<String, T>,
        kind: String,
        aliases: (String) -> List<String>?,
        blocker: (String) -> String?
    ): T? {
        val raw = name.trim()
        if (raw.isEmpty()) return null
        val candidates = (aliases(raw) ?: emptyList()) + keyCandidates(raw)
        for (key in candidates) {
            val value = table[key] ?: continue
            val blockedBy = blocker(key) ?: return value
            DebugUtil.logChanged("Compat", "block:$kind:$key", blockMessage(kind, raw, key, blockedBy))
        }
        return null
    }

    /**
     * 由用户写的名字派生出所有可能的注册表键:
     *   "GENERIC_MAX_HEALTH" -> generic_max_health, max_health
     *   "minecraft:scale"    -> scale
     *   "Scale"              -> scale
     */
    private fun keyCandidates(raw: String): List<String> {
        val noNamespace = raw.lowercase().substringAfter(':')
        return buildList {
            add(noNamespace)
            // 老 Bukkit 常量普遍带这些前缀, 而注册表键里没有
            for (prefix in LEGACY_PREFIXES) {
                if (noNamespace.startsWith(prefix)) add(noNamespace.removePrefix(prefix))
            }
        }.distinct()
    }

    private val LEGACY_PREFIXES = listOf("generic_", "player_", "zombie_", "horse_")

    /**
     * 把一个 Bukkit 注册表读成 key -> 值 的快照.
     * 注册表在某些服务端实现/版本上可能不存在或不可迭代, 因此整个过程包在 runCatching 里,
     * 失败时返回空表并记一条错误日志, 而不是让插件启动失败。
     */
    private fun <T : Keyed> snapshot(label: String, supplier: () -> Registry<T>): Map<String, T> =
        runCatching {
            supplier().associateByKey().also { DebugUtil.log("Compat", "$label 注册表快照: ${it.size} 项") }
        }.onFailure {
            DebugUtil.err("Compat", "$label 注册表读取失败, 相关功能将不可用", it)
        }.getOrDefault(emptyMap())

    private fun <T : Keyed> Registry<T>.associateByKey(): Map<String, T> =
        LinkedHashMap<String, T>().also { map -> forEach { map[it.key.key.lowercase()] = it } }

    private fun blockMessage(kind: String, requested: String, key: String, blocker: String) =
        "$kind $requested(键=$key) 被停用的模块 $blocker 挡下 —— " +
            "如需启用请把 config.yml 的 Compat.Modules.$blocker 改为 auto 或 true"
}
