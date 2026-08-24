package mc233.`fun`.snowygems

import mc233.`fun`.snowygems.util.DebugUtil
import org.bukkit.Bukkit
import org.bukkit.permissions.Permission
import org.bukkit.permissions.PermissionDefault

/**
 * 权限节点集中定义与伞节点注册.
 *
 * 设计:
 *   - 每个子命令有独立的细粒度权限(如 snowygems.embed)。
 *   - 两个\"伞节点\"通过 Bukkit 的权限父子关系一次性授予一组子权限:
 *       snowygems.user   普通玩家常用指令(镶嵌台/查看/拆卸/使用/打开菜单) —— 默认所有人可用
 *       snowygems.admin  管理指令(领取/给予/调试/重载/兼容报告等) + 同时包含全部 user 权限 —— 默认 OP
 *   - 伞节点是通过在 ENABLE 阶段用 [Permission] 的 children 建立父子关系实现的, 因此
 *     给玩家 snowygems.user 就自动拥有其下所有子权限, 不必逐个赋权。
 *
 * TabooLib 的 @CommandBody(permission=...) 只做一次 player.hasPermission 判断, 父子关系由
 * Bukkit 权限系统解析, 所以这里注册好父子后, 细粒度节点会随伞节点一起生效。
 */
object Permissions {

    const val BASE = "snowygems.command"
    const val USER = "snowygems.user"
    const val ADMIN = "snowygems.admin"

    // 普通玩家指令
    const val EMBED = "snowygems.embed"
    const val INSPECT = "snowygems.inspect"
    const val DISMANTLE = "snowygems.dismantle"
    const val USE = "snowygems.use"
    const val OPEN = "snowygems.open"

    // 管理员指令
    const val VIEW = "snowygems.view"
    const val GIVE = "snowygems.give"
    const val DEBUG = "snowygems.debug"
    const val RELOAD = "snowygems.reload"
    const val COMPAT = "snowygems.compat"
    const val SKILLS = "snowygems.skills"
    const val TRIGGERS = "snowygems.triggers"
    const val MARK = "snowygems.mark"

    private val USER_NODES = listOf(BASE, EMBED, INSPECT, DISMANTLE, USE, OPEN)
    private val ADMIN_NODES = listOf(VIEW, GIVE, DEBUG, RELOAD, COMPAT, SKILLS, TRIGGERS, MARK)

    /**
     * 在 ENABLE 阶段注册权限父子关系. 重复注册(reload)会先移除旧的再重建, 保持幂等。
     */
    fun register() {
        val pm = Bukkit.getPluginManager()

        // 先注册全部细粒度节点(默认: user 组 true, admin 组 op)
        USER_NODES.forEach { ensure(it, PermissionDefault.TRUE) }
        ADMIN_NODES.forEach { ensure(it, PermissionDefault.OP) }

        // snowygems.user: 默认所有人; children = 全部普通玩家节点
        val userChildren = USER_NODES.associateWith { true }
        replace(USER, PermissionDefault.TRUE, userChildren)

        // snowygems.admin: 默认 OP; children = 全部管理节点 + user 伞(从而囊括玩家节点)
        val adminChildren = HashMap<String, Boolean>()
        ADMIN_NODES.forEach { adminChildren[it] = true }
        adminChildren[USER] = true
        replace(ADMIN, PermissionDefault.OP, adminChildren)

        DebugUtil.log("Command", "权限节点已注册: user=${USER_NODES.size} 个子权限, admin=${ADMIN_NODES.size}+user")
    }

    private fun ensure(name: String, def: PermissionDefault) {
        val pm = Bukkit.getPluginManager()
        if (pm.getPermission(name) == null) {
            pm.addPermission(Permission(name, def))
        }
    }

    private fun replace(name: String, def: PermissionDefault, children: Map<String, Boolean>) {
        val pm = Bukkit.getPluginManager()
        pm.getPermission(name)?.let { pm.removePermission(it) }
        val perm = Permission(name, def, HashMap(children))
        pm.addPermission(perm)
        runCatching { perm.recalculatePermissibles() }
    }
}
