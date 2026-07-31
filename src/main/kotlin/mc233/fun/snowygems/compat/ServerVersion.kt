package mc233.`fun`.snowygems.compat

import org.bukkit.Bukkit
import taboolib.module.nms.MinecraftVersion

/**
 * 服务端版本信息.
 *
 * 版本号统一用 TabooLib 的 [MinecraftVersion.versionId] 表示: 主版本*10000 + 次版本*100 + 修订版本,
 * 例如 1.21.4 -> 12104, 1.21.11 -> 12111。这样可以直接用整数比较, 不需要解析字符串。
 *
 * ⚠️ 关于 26.1 / 26.2 这类新命名方案:
 * Mojang 从 2026 年起改用「年份.次版本」命名。TabooLib 的 versionId 仍按它自己的解析规则给值,
 * 无法假定它会把 26.1 算成 260100 还是别的。因此本插件**不依赖版本号做内容判定**——
 * 所有"这个版本有没有矛/铜盔甲/某个属性"的问题, 都改为直接问服务端的注册表(见 [Registries] / [Features])。
 * 版本号只用于极少数「同一个 API 在不同版本行为不同」的分支(如 AttributeModifier 的构造函数)。
 */
object ServerVersion {

    /** TabooLib 解析出的版本号, 如 12104 */
    val versionId: Int by lazy { runCatching { MinecraftVersion.versionId }.getOrDefault(0) }

    /** Bukkit 报告的版本字符串, 如 "1.21.4-R0.1-SNAPSHOT" */
    val bukkitVersion: String by lazy { runCatching { Bukkit.getBukkitVersion() }.getOrDefault("unknown") }

    /** 服务端实现名 + 版本, 如 "Paper-123 (MC: 1.21.4)" —— 只用于日志展示 */
    val serverName: String by lazy { runCatching { Bukkit.getVersion() }.getOrDefault("unknown") }

    /** 从 bukkitVersion 里取出的纯 MC 版本, 如 "1.21.4" / "26.1" */
    val minecraftVersion: String by lazy {
        bukkitVersion.substringBefore("-").ifBlank { "unknown" }
    }

    /** versionId 是否 >= 给定值. versionId 取不到(0)时一律返回 true, 按"新版本"处理 */
    fun atLeast(id: Int): Boolean = versionId == 0 || versionId >= id

    /** versionId 是否 < 给定值 */
    fun below(id: Int): Boolean = !atLeast(id)

    override fun toString() = "$minecraftVersion (versionId=$versionId)"

    // ── 少数真正需要按版本分支的 API 边界 ────────────────────────

    /**
     * 1.20.5 起 AttributeModifier 推荐用 (NamespacedKey, double, Operation, EquipmentSlotGroup) 构造,
     * 旧的 (UUID, String, ...) 构造被标记过时。1.21.9 左右开始旧构造在部分实现上已被移除,
     * 因此优先走新构造, 由 [AttributeCompat] 做实际的反射选择。
     */
    const val V1_20_5 = 12005

    /** 1.21 —— 锤(MACE)、部分新属性引入的分界 */
    const val V1_21 = 12100
}
