package mc233.`fun`.snowygems.compat

import mc233.`fun`.snowygems.util.DebugUtil

/**
 * 物品分类 —— Require 里 WEAPON / TOOL / ARMOR 这类关键字的实际判定依据
 *
 * 老实现把物品名硬编码成一串正则和 setOf("BOW","CROSSBOW","TRIDENT","MACE"), 后果是:
 * 1.21.11 加了矛(SPEAR), 铜盔甲(COPPER_HELMET…)出现后, 这些新物品不属于任何分类,
 * 服主写 `Require: [WEAPON]` 的宝石装不到矛上, 而且**没有任何报错**, 只是静默不匹配
 *
 * 这里改成: 分类由「后缀规则 + 具名清单」共同定义, 并且**只保留服务端实际存在的物品**
 * 新增材质(铜/下界合金/未来的新材质)靠后缀自动归类; 完全新形态的物品(矛/锤/鞘翅)
 * 走具名清单, 清单里写了但当前版本没有的条目会被过滤掉并记一条 debug 日志
 */
object ItemCategories {

    private val SUFFIX_RULES: List<Pair<String, Set<String>>> = listOf(
        // 武器
        "_SWORD" to setOf("WEAPON", "SWORD", "MELEE"),
        // 斧既是武器又是工具
        "_AXE" to setOf("WEAPON", "TOOL", "AXE", "MELEE"),
        // 矛(1.21.11+): 分材质等级(WOODEN_SPEAR/IRON_SPEAR/COPPER_SPEAR…), 靠后缀统一归类.
        // 矛既能近战又能投掷, 因此同时属于 WEAPON/MELEE/RANGED/SPEAR
        "_SPEAR" to setOf("WEAPON", "MELEE", "RANGED", "SPEAR"),
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

    fun matches(materialName: String, category: String): Boolean {
        val cats = index()[materialName.uppercase()] ?: return false
        return category.uppercase() in cats
    }

    fun categoriesOf(materialName: String): Set<String> =
        index()[materialName.uppercase()] ?: emptySet()

    @Volatile
    private var knownCategoriesCache: Set<String>? = null

    val knownCategories: Set<String>
        get() = knownCategoriesCache
            ?: index().values.flatten().toSortedSet().also { knownCategoriesCache = it }

    fun itemsOf(category: String): List<String> {
        val c = category.uppercase()
        return index().filterValues { c in it }.keys.sorted()
    }
}
