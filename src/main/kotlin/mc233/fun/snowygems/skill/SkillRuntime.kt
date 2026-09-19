package mc233.`fun`.snowygems.skill

import mc233.`fun`.snowygems.config.SkillDef
import org.bukkit.entity.Player
import taboolib.platform.util.PlayerSessionMap

/** Shared by active skills and equipment timers; successful casts own the cooldown. */
object SkillRuntime {
    private val cooldowns = PlayerSessionMap<MutableMap<String, Long>>({ mutableMapOf() })
    private val running = mutableSetOf<Pair<java.util.UUID, String>>()
    var generation = 0L
        private set
    fun invalidate() { generation++ }

    fun remaining(player: Player, def: SkillDef): Double? {
        if (def.cooldown <= 0) return null
        val started = cooldowns.getOrCreate(player)?.get(def.id) ?: return null
        val left = def.cooldown - (System.nanoTime() - started) / 1_000_000_000.0
        return left.takeIf { it > 0 }
    }

    fun cast(player: Player, def: SkillDef, action: () -> Boolean): Boolean {
        if (remaining(player, def) != null) return false
        val key = player.uniqueId to def.id
        if (!running.add(key)) return false
        return try {
            action().also { if (it && def.cooldown > 0) cooldowns.getOrCreate(player)?.set(def.id, System.nanoTime()) }
        } finally { running.remove(key) }
    }

    fun accepts(def: SkillDef, slot: String): Boolean = def.slots.isEmpty() || slot in def.slots
}

/** Copies and delayed children share one budget, so nested Repeat cannot multiply without bound. */
class SkillBudget(private val limit: Int = 256, private val pendingLimit: Int = 128) {
    private var remaining = limit
    private var scheduled = 0
    fun consume(depth: Int): Boolean = depth <= 16 && remaining-- > 0
    fun reserveTask(): Boolean = scheduled++ < pendingLimit
}
