package mc233.`fun`.snowygems.compat

/**
 * 属性名别名表.
 *
 * 三类名字都要能用:
 *   1. 本插件的中文友好简写 —— 老配置里已经在用的 health / move / damage
 *   2. 1.20.5 之前的 Bukkit 常量名 —— GENERIC_MAX_HEALTH
 *   3. 现代注册表键 —— max_health / scale / block_interaction_range
 *
 * 值是**候选键列表**: 同一个简写在不同版本对应的键可能不同, 按顺序取第一个服务端真有的
 * 新版本才有的属性(scale / 交互距离 / 水下挖掘速度…)在老版本上查不到, 会被上层跳过并提示,
 * 不会导致整条 Rewards 失败
 */
object AttributeAliases {

    private val ALIASES: Map<String, List<String>> = buildMap {
        // ── 生命 / 防御 ────────────────────────────────────
        alias("health", "max_health")
        alias("max_health", "max_health")
        alias("absorption", "max_absorption")            // 1.20.5+
        alias("armor", "armor")
        alias("armor_toughness", "armor_toughness")
        alias("toughness", "armor_toughness")
        alias("knockback_resistance", "knockback_resistance")
        alias("kbr", "knockback_resistance")
        alias("fall_damage", "fall_damage_multiplier")   // 1.20.5+
        alias("oxygen_bonus", "oxygen_bonus")            // 1.21.2+
        alias("burning_time", "burning_time")            // 1.21.2+
        alias("explosion_resistance", "explosion_knockback_resistance")

        // ── 攻击 ──────────────────────────────────────────
        alias("damage", "attack_damage")
        alias("attack_damage", "attack_damage")
        alias("attack_speed", "attack_speed")
        alias("attack_knockback", "attack_knockback")
        alias("attack_reach", "entity_interaction_range") // 1.20.5+ 攻击距离
        alias("sweeping", "sweeping_damage_ratio")       // 1.21+

        // ── 移动 ──────────────────────────────────────────
        alias("move", "movement_speed")
        alias("speed", "movement_speed")
        alias("movement_speed", "movement_speed")
        alias("movement_efficiency", "movement_efficiency")   // 1.21.2+
        alias("flying_speed", "flying_speed")
        alias("jump", "jump_strength")                   // 1.20.5+ 玩家也可用
        alias("jump_strength", "jump_strength")
        alias("safe_fall", "safe_fall_distance")         // 1.20.5+
        alias("step_height", "step_height")
        alias("gravity", "gravity")                      // 1.20.5+
        alias("water_movement", "water_movement_efficiency")
        alias("sneaking_speed", "sneaking_speed")        // 1.21.2+

        // ── 体型 / 交互距离 (1.20.5+, 用户点名要求) ─────────
        alias("scale", "scale")
        alias("size", "scale")
        alias("block_interaction_range", "block_interaction_range")
        alias("block_reach", "block_interaction_range")
        alias("entity_interaction_range", "entity_interaction_range")
        alias("entity_reach", "entity_interaction_range")

        // ── 挖掘 (1.21.2+, 用户点名要求) ───────────────────
        alias("submerged_mining_speed", "submerged_mining_speed")
        alias("water_mining", "submerged_mining_speed")
        alias("mining_efficiency", "mining_efficiency")  // 1.21.2+
        alias("block_break_speed", "block_break_speed")

        // ── 其它 ──────────────────────────────────────────
        alias("luck", "luck")
        alias("follow_range", "follow_range")
        alias("spawn_reinforcements", "spawn_reinforcements")
        alias("tempt_range", "tempt_range")              // 1.21.2+
        alias("waypoint_transmit_range", "waypoint_transmit_range")  // 26.x
        alias("waypoint_receive_range", "waypoint_receive_range")    // 26.x
        alias("camera_distance", "camera_distance")       // 26.x
    }

    /** 往表里登记一个别名 -> 候选键列表 */
    private fun MutableMap<String, List<String>>.alias(alias: String, vararg keys: String) {
        put(alias.lowercase(), keys.toList())
    }

    /** 取某个写法对应的候选注册表键, 没有登记则返回 null(上层会直接把它当键用) */
    fun candidatesOf(name: String): List<String>? = ALIASES[name.trim().lowercase()]

    /** 所有已登记的简写, 用于命令补全和文档 */
    fun knownAliases(): Set<String> = ALIASES.keys
}
