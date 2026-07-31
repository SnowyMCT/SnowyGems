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
 * 统一的注册表查询层 —— 多版本兼容的核心
 *
 * 设计原则: **不猜版本, 只问服务端**
 *
 * 1.21.4 → 26.2 之间, Mojang 持续新增属性(scale / block_interaction_range /
 * submerged_mining_speed…)、物品(矛 SPEAR、铜盔甲、锤 MACE)、附魔和药水效果
 * 如果用 `if (version >= X) 支持Y` 的写法, 每出一个新版本都要改代码, 而且一旦版本号
 * 解析方式变了(比如 26.1 这种新命名)判断就全错
 *
 * 这里改成: 启动时把服务端**当前实际存在**的注册表内容读成快照, 之后所有查询都基于它
 * 服务端有就能用, 没有就优雅降级并给出可读日志, 不抛异常、不影响别的宝石
 *
 * 好处:
 *   - 新版本加了什么, 插件自动就支持, 无需改代码
 *   - 老版本没有的东西, 配置里写了只跳过这一条并提示, 不会连锁失败
 *   - 服主用数据包自定义的附魔/属性同样能被识别(它们也在注册表里)
 */
object Registries {

    // ── 注册表快照 ──────────────────────────────────────────

    val attributes: Map<String, Attribute> by lazy { snapshot("属性") { Registry.ATTRIBUTE } }

    val enchantments: Map<String, Enchantment> by lazy { snapshot("附魔") { Registry.ENCHANTMENT } }

    val effects: Map<String, PotionEffectType> by lazy { snapshot("药水效果") { Registry.EFFECT } }

    val materials: Set<String> by lazy {
        runCatching { Material.entries.mapTo(HashSet()) { it.name } }
            .onFailure { DebugUtil.err("Compat", "读取 Material 列表失败", it) }
            .getOrDefault(emptySet())
    }

    // ── 查询入口 ────────────────────────────────────────────

    fun attribute(name: String): Attribute? =
        lookup(name, attributes, "属性", AttributeAliases::candidatesOf, FeatureModules::blockedAttribute)

    fun enchantment(name: String): Enchantment? =
        lookup(name, enchantments, "附魔", { EnchantAliases.keyOf(it)?.let(::listOf) }, FeatureModules::blockedEnchantment)

    fun effect(name: String): PotionEffectType? =
        lookup(name, effects, "药水效果", { EffectAliases.keyOf(it)?.let(::listOf) }, FeatureModules::blockedEffect)

    fun hasMaterial(name: String): Boolean {
        val key = name.trim().uppercase()
        if (key !in materials) return false
        val blocker = FeatureModules.blockedMaterial(key) ?: return true
        DebugUtil.logChanged("Compat", "block:物品:$key", blockMessage("物品", key, key, blocker))
        return false
    }

    fun material(name: String): Material? {
        val key = name.trim().uppercase()
        if (!hasMaterial(key)) return null
        return runCatching { Material.valueOf(key) }.getOrNull()
    }

    // ── 不经模块门禁的原始查询 ──────────────────────────────
    // [FeatureModules] 要靠这些判断"服务端到底支不支持某模块", 若再走门禁就成了循环依赖;
    // [CompatReport] 也用它们报告服务端的真实能力(而非门禁后的结果)

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
