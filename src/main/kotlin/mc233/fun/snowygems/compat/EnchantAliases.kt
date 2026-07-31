package mc233.`fun`.snowygems.compat

/**
 * 附魔名别名表: 1.13 之前的 Bukkit 常量名 -> 现代注册表键.
 *
 * 1.20.5 起 org.bukkit.enchantments.Enchantment 从静态常量改成了注册表, 旧字段
 * (DURABILITY / DAMAGE_ALL / ARROW_DAMAGE …) 全部消失。老配置里写的旧名必须映射过来,
 * 否则解析失败 -> 附魔宝石静默无效果。
 *
 * 现代键(sharpness / density / breach / wind_burst)不需要登记, 直接就能查到。
 */
object EnchantAliases {

    private val ALIASES: Map<String, String> = buildMap {
        // ── 武器 ──────────────────────────────────────────
        put("DAMAGE_ALL", "sharpness")
        put("DAMAGE_UNDEAD", "smite")
        put("DAMAGE_ARTHROPODS", "bane_of_arthropods")
        put("SWEEPING", "sweeping_edge")
        put("FIRE_ASPECT", "fire_aspect")
        put("KNOCKBACK", "knockback")
        put("LOOT_BONUS_MOBS", "looting")

        // ── 工具 ──────────────────────────────────────────
        put("DIG_SPEED", "efficiency")
        put("SILK_TOUCH", "silk_touch")
        put("DURABILITY", "unbreaking")
        put("LOOT_BONUS_BLOCKS", "fortune")

        // ── 弓 / 弩 ───────────────────────────────────────
        put("ARROW_DAMAGE", "power")
        put("ARROW_KNOCKBACK", "punch")
        put("ARROW_FIRE", "flame")
        put("ARROW_INFINITE", "infinity")

        // ── 盔甲 ──────────────────────────────────────────
        put("PROTECTION_ENVIRONMENTAL", "protection")
        put("PROTECTION_FIRE", "fire_protection")
        put("PROTECTION_FALL", "feather_falling")
        put("PROTECTION_EXPLOSIONS", "blast_protection")
        put("PROTECTION_PROJECTILE", "projectile_protection")
        put("OXYGEN", "respiration")
        put("WATER_WORKER", "aqua_affinity")
        put("THORNS", "thorns")
        put("DEPTH_STRIDER", "depth_strider")
        put("FROST_WALKER", "frost_walker")
        put("BINDING_CURSE", "binding_curse")

        // ── 钓竿 ──────────────────────────────────────────
        put("LUCK", "luck_of_the_sea")
        put("LURE", "lure")

        // ── 三叉戟 ────────────────────────────────────────
        put("LOYALTY", "loyalty")
        put("IMPALING", "impaling")
        put("RIPTIDE", "riptide")
        put("CHANNELING", "channeling")

        // ── 弩 ────────────────────────────────────────────
        put("MULTISHOT", "multishot")
        put("QUICK_CHARGE", "quick_charge")
        put("PIERCING", "piercing")

        // ── 通用 ──────────────────────────────────────────
        put("MENDING", "mending")
        put("DURABILITY_MENDING", "mending")
        put("VANISHING_CURSE", "vanishing_curse")

        // ── 1.21 锤专属(现代键即可, 这里登记大写写法方便老习惯) ──
        put("DENSITY", "density")
        put("BREACH", "breach")
        put("WIND_BURST", "wind_burst")
    }

    /** 取旧名对应的现代键; 传入的本就是现代键时返回 null, 由上层直接当键查 */
    fun keyOf(name: String): String? = ALIASES[name.trim().uppercase()]
}
