package mc233.`fun`.snowygems.skill

import mc233.`fun`.snowygems.util.DebugUtil

/**
 * 技能函数注册中心 —— 技能引擎的"可扩展"来源。
 *
 * 老实现是 SkillExecutor 里一个巨大的 `when (name)`, 每加一个技能函数就要改那个 when,
 * 而且服主只能用我预先写好的那十来个函数。
 *
 * 改成注册表后:
 *   - 每个函数是一个独立的 [SkillFunction], 自带名字、别名、说明、需要的版本能力
 *   - 引擎只负责按名字查表并执行, 不认识的名字会给出"你是不是想写 XXX"的近似建议
 *   - 服主能通过 /sgem skills 看到当前版本**实际可用**的全部函数及其参数
 *   - 某个函数依赖的能力在当前版本不存在时(如新药水效果), 注册时就标记为不可用,
 *     配置里用到它只会得到一条清晰提示, 不会抛异常
 */
object SkillFunctions {

    private val byName = LinkedHashMap<String, SkillFunction>()

    /** 注册一个技能函数. 名字和别名都会建立索引(全部小写) */
    fun register(function: SkillFunction) {
        val key = function.name.lowercase()
        if (byName.containsKey(key)) {
            DebugUtil.log("SkillExec", "技能函数 ${function.name} 被重复注册, 后者覆盖前者")
        }
        byName[key] = function
        for (alias in function.aliases) {
            byName[alias.lowercase()] = function
        }
    }

    /** 按名字查函数, 找不到返回 null */
    fun find(name: String): SkillFunction? = byName[name.trim().lowercase()]

    /** 全部已注册函数(去重, 按注册顺序) */
    fun all(): List<SkillFunction> = byName.values.distinct()

    /** 当前版本实际可用的函数 */
    fun available(): List<SkillFunction> = all().filter { it.isAvailable() }

    /** 因版本原因不可用的函数 */
    fun unavailable(): List<SkillFunction> = all().filterNot { it.isAvailable() }

    /**
     * 给写错名字的服主一点提示: 找出编辑距离最近的几个函数名。
     * 配置里写 `Blnk{...}` 这种拼写错误, 老实现只会说"未实现的技能函数", 服主无从下手。
     */
    fun suggest(name: String, limit: Int = 3): List<String> {
        val target = name.trim().lowercase()
        return all()
            .map { it.name to distance(target, it.name.lowercase()) }
            // 只在长度相近时给建议, 避免把毫不相干的函数名推给服主
            .filter { it.second <= (target.length / 2).coerceAtLeast(2) }
            .sortedBy { it.second }
            .take(limit)
            .map { it.first }
    }

    /** Levenshtein 编辑距离 */
    private fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
    }

    /** 清空注册表(重载时调用, 保证不残留上一次的注册) */
    fun clear() = byName.clear()
}
