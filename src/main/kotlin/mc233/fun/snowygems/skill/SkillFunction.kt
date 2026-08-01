package mc233.`fun`.snowygems.skill

/**
 * 一个技能函数的定义
 *
 * 服主在 skills 目录下的 yml 里写的每一行(如 `Blink{distance=120} ~onHit:ARROW @Location`)
 * 都会被解析成 [SkillLine], 再由这里的 [execute] 真正执行
 *
 * @property name 函数名, 配置里就写这个(大小写不敏感)
 * @property aliases 别名, 兼容老配置或更顺口的写法
 * @property description 一句话说明, 显示在 /sgem skills 里
 * @property usage 参数示例, 显示在 /sgem skills 里
 * @property requires 依赖的能力说明(如"需要 1.21+ 的风爆附魔"). 为空表示所有版本可用
 */
abstract class SkillFunction(
    val name: String,
    val aliases: List<String> = emptyList(),
    val description: String = "",
    val usage: String = "",
    val requires: String = ""
) {

    /**
     * 执行这个技能函数.
     * @return true 表示确实产生了效果; false 表示因为条件不满足/版本不支持而跳过.
     *         返回值用于日志和"是否要给玩家反馈", 不影响同一定义里其它技能行的执行
     */
    abstract fun execute(ctx: SkillContext): Boolean

    /**
     * 当前服务端版本是否支持这个函数.
     * 默认全部可用; 依赖特定版本 API 的函数覆写它, 在注册时就把不可用的标出来,
     * 这样服主在 /sgem skills 里能直接看到"这个函数在你的版本上用不了", 而不是运行时踩坑
     */
    open fun isAvailable(): Boolean = true

    /** 便于在日志/命令里展示 */
    fun signature(): String = if (usage.isBlank()) name else "$name$usage"
}

fun skillFunction(
    name: String,
    aliases: List<String> = emptyList(),
    description: String = "",
    usage: String = "",
    requires: String = "",
    available: () -> Boolean = { true },
    body: (SkillContext) -> Boolean
): SkillFunction = object : SkillFunction(name, aliases, description, usage, requires) {
    override fun execute(ctx: SkillContext): Boolean = body(ctx)
    override fun isAvailable(): Boolean = available()
}
