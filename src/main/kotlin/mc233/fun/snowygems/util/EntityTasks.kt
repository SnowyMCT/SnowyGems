package mc233.`fun`.snowygems.util

import mc233.`fun`.snowygems.SnowyGems
import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import java.util.function.Consumer

/** Run entity work on the region that owns it, including after it moves to another region. */
object EntityTasks {

    fun owns(entity: Entity): Boolean {
        val method = Bukkit::class.java.methods.firstOrNull {
            it.name == "isOwnedByCurrentRegion" && it.parameterCount == 1 &&
                it.parameterTypes[0] == Entity::class.java
        } ?: return true // Bukkit/Spigot has one server thread.
        return method.invoke(null, entity) as Boolean
    }

    fun later(entity: Entity, delay: Long = 1, action: () -> Unit): Boolean {
        val ticks = delay.coerceAtLeast(1)
        val schedulerMethod = entity.javaClass.methods.firstOrNull {
            it.name == "getScheduler" && it.parameterCount == 0
        }
        if (schedulerMethod != null) {
            val scheduler = schedulerMethod.invoke(entity)
            val runDelayed = scheduler.javaClass.methods.first {
                it.name == "runDelayed" && it.parameterCount == 4
            }
            // Paper/Folia's API is absent from the older Bukkit compile stubs. Reflection keeps
            // the same JAR usable on both server families without linking that API on Spigot.
            return runDelayed.invoke(scheduler, SnowyGems.plugin,
                Consumer<Any> { action() }, null, ticks) != null
        }
        Bukkit.getScheduler().runTaskLater(SnowyGems.plugin, Runnable(action), ticks)
        return true
    }
}
