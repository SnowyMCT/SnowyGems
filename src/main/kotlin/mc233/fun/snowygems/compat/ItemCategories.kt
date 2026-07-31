package mc233.`fun`.snowygems.compat

import mc233.`fun`.snowygems.util.DebugUtil

/**
 * 物品分类 —— Require 里 WEAPON / TOOL / ARMOR 这类关键字的实际判定依据。
 *
 * 老实现把物品名硬编码成一串正则和 setOf("BOW","CROSSBOW","TRIDENT","MACE"), 后果是:
 * 1.21.11 加了矛(SPEAR), 铜盔甲(COPPER_HELMET…)出现后, 这些新物品不属于任何分类,
 * 服主写 `Require: [WEAPON]` 的宝石装不到矛上, 而且**没有任何报错**, 只是静默不匹配。
 *
 * 这里改成: 分类由「后缀规则 + 具名清单」共同定义, 并且**只保留服务端实际存在的物品**。
 * 新增材质(铜/下界合金/未来的新材质)靠后缀自动归类; 完全新形态的物品(矛/锤/鞘翅)
 * 走具名清单, 清单里写了但当前版本没有的条目会被过滤掉并记一条 debug 日志。
 */
object ItemCategories {

    /**
     * 按后缀归类的规则: 后缀 -> 分类集合.
     *
     * 一个物品会匹配到的后缀最多只有一个(后缀之间互不为后缀关系:
     * "DIAMOND_PICKAXE".endsWith("_AXE") 为 false, 因为那一段是 "KAXE"),
     * 所以这里的顺序不影响结果, 命中即止只是省几次比较。
     */
    private val SUFFIX_RULES: List<Pair<String, Set<String>>> = listOf(
        // 武器
        "_SWORD" to setOf("WEAPON", "SWORD", "MELEE"),
        // 斧既是武器又是工具
        "_AXE" to setOf("WEAPON", "TOOL", "AXE", "MELEE"),
        // 工具
        "_PICKAXE" to setOf("TOOL", "PICKAXE"),
        "_SHOVEL" to setOf("TOOL", "SHOVEL"),
        "_HOE" to setOf("TOOL", "HOE"),
        // 盔甲
        "_HELMET" to setOf("ARMOR", "HELMET", "HEAD"),
        "_CHESTPLATE" to setOf("ARMOR", "CHESTPLATE", "CHEST"),
        "_LEGGINGS" to setOf("ARMOR", "LEGGINGS", "LEGS"),
        "_BOOTS" to setOf("ARMOR", "BOOTS", "FEET")
    )

    /**
     * 按具体名字归类的清单. 这里写的是"所有版本可能出现的物品", 建索引时会用注册表过滤,
     * 当前版本不存在的自动剔除。
     *
     * 版本标注仅供阅读, 代码不依赖它做判断。
     */
    private val NAMED_RULES: Map<String, Set<String>> = buildMap {
        // ── 远程武器 ──────────────────────────────────────
        put("BOW", setOf("WEAPON", "RANGED", "BOW"))
        put("CROSSBOW", setOf("WEAPON", "RANGED", "CROSSBOW"))
        put("TRIDENT", setOf("WEAPON", "RANGED", "MELEE", "TRIDENT"))
        // ── 近战武器 ──────────────────────────────────────
        put("MACE", setOf("WEAPON", "MELEE", "MACE"))            // 1.21+
        put("SPEAR", setOf("WEAPON", "MELEE", "RANGED", "SPEAR")) // 1.21.11+ 矛(可投掷)
        // ── 工具 ──────────────────────────────────────────
        put("SHEARS", setOf("TOOL", "SHEARS"))
        put("FISHING_ROD", setOf("TOOL", "FISHING_ROD"))
        put("FLINT_AND_STEEL", setOf("TOOL", "FLINT_AND_STEEL"))
        put("BRUSH", setOf("TOOL", "BRUSH"))                     // 1.20+
        put("CARROT_ON_A_STICK", setOf("TOOL"))
        put("WARPED_FUNGUS_ON_A_STICK", setOf("TOOL"))
        put("SPYGLASS", setOf("TOOL"))
        // ── 特殊防具 ──────────────────────────────────────
        put("SHIELD", setOf("ARMOR", "SHIELD", "OFF_HAND"))
        put("ELYTRA", setOf("ARMOR", "CHEST", "ELYTRA"))
        put("TURTLE_HELMET", setOf("ARMOR", "HELMET", "HEAD"))
        put("CARVED_PUMPKIN", setOf("HEAD"))
        // 马 / 狼护甲
        put("WOLF_ARMOR", setOf("ARMOR", "ANIMAL_ARMOR"))        // 1.20.5+
        put("LEATHER_HORSE_ARMOR", setOf("ARMOR", "ANIMAL_ARMOR"))
        put("IRON_HORSE_ARMOR", setOf("ARMOR", "ANIMAL_ARMOR"))
        put("GOLDEN_HORSE_ARMOR", setOf("ARMOR", "ANIMAL_ARMOR"))
        put("DIAMOND_HORSE_ARMOR", setOf("ARMOR", "ANIMAL_ARMOR"))
    }

    /**
     * 物品名 -> 它所属的全部分类. 只包含当前服务端存在、且未被停用模块挡下的物品。
     *
     * 之所以做成缓存而不是每次现场判断: Require 匹配在菜单渲染时会被高频调用。
     * 之所以**不用 `by lazy`**: 模块启停状态会在 /sgem reload 时重算, lazy 只算一次,
     * 会让"关掉 Spear 模块后重载"这种操作看不到效果。
     */
    @Volatile
    private var categoryIndex: Map<String, Set<String>>? = null

    private fun index(): Map<String, Set<String>> =
        categoryIndex ?: buildIndex().also { categoryIndex = it }

    /** 重载配置时调用: 丢弃缓存, 下次查询按最新的模块门禁重建 */
    fun invalidate() {
        categoryIndex = null
        knownCategoriesCache = null
        DebugUtil.log("Compat", "物品分类索引已失效, 将按最新模块状态重建")
    }

    private fun buildIndex(): Map<String, Set<String>> {
        val result = HashMap<String, Set<String>>()
        var gated = 0
        for (name in Registries.materials) {
            // 被 config.yml 停用的模块所管辖的物品不参与分类,
            // 这样低版本子服关掉 Spear 模块后, Require:[WEAPON] 就不会再把矛算进去
            if (FeatureModules.blockedMaterial(name) != null) {
                gated++
                continue
            }
            val categories = LinkedHashSet<String>()
            for ((suffix, cats) in SUFFIX_RULES) {
                if (name.endsWith(suffix)) {
                    categories += cats
                    break
                }
            }
            NAMED_RULES[name]?.let { categories += it }
            if (categories.isNotEmpty()) {
                result[name] = categories
            }
        }
        // 报告清单里声明了但当前版本不存在的物品, 便于服主理解为什么某条 Require 不生效.
        // 这里用 Raw 查询: 要报的是"服务端真的没有这个物品", 而不是"被模块挡住了"
        // (被模块挡住的已经单独计入 gated)
        val absent = NAMED_RULES.keys.filterNot { Registries.hasMaterialRaw(it) }
        if (absent.isNotEmpty()) {
            DebugUtil.log("Compat", "当前版本不存在的物品(已从分类中剔除): $absent")
        }
        DebugUtil.log(
            "Compat",
            "物品分类索引建立完成: ${result.size} 种物品可被分类关键字匹配" +
                if (gated > 0) " (另有 $gated 种因模块停用被排除)" else ""
        )
        return result
    }

    /** 该物品是否属于某个分类关键字(大小写不敏感) */
    fun matches(materialName: String, category: String): Boolean {
        val cats = index()[materialName.uppercase()] ?: return false
        return category.uppercase() in cats
    }

    /** 取物品的全部分类, 用于 debug 输出 */
    fun categoriesOf(materialName: String): Set<String> =
        index()[materialName.uppercase()] ?: emptySet()

    @Volatile
    private var knownCategoriesCache: Set<String>? = null

    /** 当前版本所有可用的分类关键字, 供 Require 校验和命令补全 */
    val knownCategories: Set<String>
        get() = knownCategoriesCache
            ?: index().values.flatten().toSortedSet().also { knownCategoriesCache = it }

    /** 某个分类在当前版本下有哪些物品, 用于 /sgem compat 展示 */
    fun itemsOf(category: String): List<String> {
        val c = category.uppercase()
        return index().filterValues { c in it }.keys.sorted()
    }
}
