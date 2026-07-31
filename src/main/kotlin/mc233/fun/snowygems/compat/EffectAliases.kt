package mc233.`fun`.snowygems.compat

/**
 * 药水效果名别名表: 1.20.5 之前的 Bukkit 常量名 -> 现代注册表键.
 *
 * 1.20.5 起 PotionEffectType 也改成注册表, DAMAGE_RESISTANCE / JUMP / CONFUSION
 * 这些旧名被移除。老配置(SnowyGems 早期的 PotionGem.yml)大量使用旧名, 必须映射。
 *
 * 1.21 新增的 oozing / infested / weaving / wind_charged 是现代键, 无需登记,
 * 在支持它们的版本上自动可用, 老版本上查不到会被跳过并提示。
 */
object EffectAliases {

    private val ALIASES: Map<String, String> = buildMap {
        put("DAMAGE_RESISTANCE", "resistance")
        put("FAST_DIGGING", "haste")
        put("SLOW_DIGGING", "mining_fatigue")
        put("INCREASE_DAMAGE", "strength")
        put("JUMP", "jump_boost")
        put("MOVEMENT_SPEED", "speed")
        put("SLOW", "slowness")
        put("HEAL", "instant_health")
        put("HARM", "instant_damage")
        put("CONFUSION", "nausea")
        put("BLIND", "blindness")
        put("NIGHTVISION", "night_vision")
        put("NIGHT_VISION", "night_vision")
        put("WATER_BREATHING", "water_breathing")
        put("FIRE_RESISTANCE", "fire_resistance")
        put("HEALTH_BOOST", "health_boost")
        put("DOLPHINS_GRACE", "dolphins_grace")
        put("BAD_OMEN", "bad_omen")
        put("HERO_OF_THE_VILLAGE", "hero_of_the_village")
        put("CONDUIT_POWER", "conduit_power")
        put("SLOW_FALLING", "slow_falling")
        put("UNLUCK", "unluck")
        put("LUCK", "luck")
        put("GLOWING", "glowing")
        put("LEVITATION", "levitation")
        put("ABSORPTION", "absorption")
        put("SATURATION", "saturation")
        put("REGENERATION", "regeneration")
        put("INVISIBILITY", "invisibility")
        put("WEAKNESS", "weakness")
        put("POISON", "poison")
        put("WITHER", "wither")
        put("HUNGER", "hunger")
        put("DARKNESS", "darkness")
        put("TRIAL_OMEN", "trial_omen")
        put("RAID_OMEN", "raid_omen")
    }

    /** 取旧名对应的现代键; 传入的本就是现代键时返回 null, 由上层直接当键查 */
    fun keyOf(name: String): String? = ALIASES[name.trim().uppercase()]
}
