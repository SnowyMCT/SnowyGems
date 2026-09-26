package mc233.`fun`.snowygems.config

import mc233.`fun`.snowygems.SnowyGems
import mc233.`fun`.snowygems.rune.RuneRecipeFiles
import mc233.`fun`.snowygems.util.DebugUtil
import taboolib.common.platform.function.getDataFolder
import taboolib.module.configuration.Configuration
import java.io.File

/** File backed CRUD used by the in-game editor. Every write is validated by a full reload. */
object EditorStore {
    enum class Kind { GEM, RUNE }
    data class Entry(val kind: Kind, val id: String, val file: File)

    private val categoryName = Regex("[\\p{L}\\p{N}_-]{1,48}")
    private fun base(kind: Kind) = File(getDataFolder(), if (kind == Kind.GEM) "gems" else "runes/packs")
    private fun categoryFile(kind: Kind, category: String): File {
        require(category.matches(categoryName)) { "分类名只能包含文字、数字、_、-，最多 48 字" }
        if (kind == Kind.RUNE && category == "forge") return File(getDataFolder(), "runes/forge.yml")
        val yaml = File(base(kind), "$category.yaml")
        return if (yaml.isFile) yaml else File(base(kind), "$category.yml")
    }

    fun categories(kind: Kind): List<String> =
        (if (kind == Kind.GEM) base(kind).listFiles { f -> f.isFile && f.extension.lowercase() in setOf("yml", "yaml") }?.toList()
         else RuneRecipeFiles.sources(File(getDataFolder(), "runes")).filter { it.isFile })
            ?.map { it.nameWithoutExtension }?.distinct()?.sorted() ?: emptyList()

    fun createCategory(kind: Kind, name: String) {
        val file = categoryFile(kind, name)
        require(categories(kind).none { it.equals(name, true) }) { "分类已存在" }
        file.parentFile.mkdirs()
        if (kind == Kind.GEM) file.writeText("Version: 1\n")
        else file.writeText("Recipes: {}\n")
        if (!SnowyGems.reloadAll()) {
            file.delete()
            SnowyGems.reloadAll()
            error("分类未通过配置校验")
        }
    }

    fun deleteCategory(kind: Kind, name: String) {
        require(kind != Kind.RUNE || name != "forge") { "内置 forge 分类不能删除" }
        val file = categoryFile(kind, name)
        require(file.isFile) { "分类不存在" }
        require(list(kind).none { it.file.canonicalFile == file.canonicalFile }) { "分类中还有条目，请先移动或删除" }
        val before = file.readBytes()
        require(file.delete()) { "无法删除分类文件" }
        if (!SnowyGems.reloadAll()) {
            file.writeBytes(before)
            SnowyGems.reloadAll()
            error("删除分类后配置未通过校验")
        }
    }

    fun move(entry: Entry, targetCategory: String): Entry {
        val target = categoryFile(entry.kind, targetCategory)
        require(target.isFile) { "目标分类不存在" }
        require(entry.file.canonicalFile != target.canonicalFile) { "已经在这个分类中" }
        val sourceBefore = entry.file.readBytes()
        val targetBefore = target.readBytes()
        val from = Configuration.loadFromFile(entry.file)
        val to = Configuration.loadFromFile(target)
        val sourceSection = from.getConfigurationSection(path(entry)) ?: error("条目不存在")
        val destination = if (entry.kind == Kind.GEM) entry.id else "Recipes.${entry.id}"
        require(!to.contains(destination)) { "目标分类已有同名条目" }
        try {
            for (key in sourceSection.getKeys(true)) {
                if (sourceSection.getConfigurationSection(key) == null) {
                    to.set("$destination.$key", sourceSection.get(key))
                }
            }
            from.set(path(entry), null)
            to.saveToFile(target)
            from.saveToFile(entry.file)
            require(SnowyGems.reloadAll()) { "移动后配置未通过校验" }
        } catch (e: Exception) {
            entry.file.writeBytes(sourceBefore)
            target.writeBytes(targetBefore)
            SnowyGems.reloadAll()
            throw IllegalStateException("移动失败：${e.message}", e)
        }
        return Entry(entry.kind, entry.id, target)
    }

    fun list(kind: Kind): List<Entry> {
        val root = getDataFolder()
        val files = if (kind == Kind.GEM) {
            File(root, "gems").listFiles { f -> f.isFile && f.extension.lowercase() in setOf("yml", "yaml") }?.toList() ?: emptyList()
        } else RuneRecipeFiles.sources(File(root, "runes")).filter { it.isFile }
        return files.flatMap { file ->
            val cfg = Configuration.loadFromFile(file)
            val ids = if (kind == Kind.GEM) cfg.getKeys(false).filter { it != "Version" }
            else cfg.getConfigurationSection("Recipes")?.getKeys(false)?.toList() ?: emptyList()
            ids.filter { (if (kind == Kind.GEM) cfg.getConfigurationSection(it)
                else cfg.getConfigurationSection("Recipes.$it")) != null }.map { Entry(kind, it, file) }
        }.sortedBy { it.id }
    }

    fun fields(entry: Entry): Map<String, Any> {
        val cfg = Configuration.loadFromFile(entry.file)
        val sec = cfg.getConfigurationSection(path(entry)) ?: return emptyMap()
        return sec.getKeys(false).associateWith { sec.get(it) ?: "" }
    }

    fun create(kind: Kind, id: String, category: String = if (kind == Kind.GEM) "CustomGem" else "custom"): Entry {
        require(id.matches(Regex("[\\p{L}\\p{N}_-]{1,48}"))) { "ID 只能包含文字、数字、_、-，最多 48 字" }
        require(list(kind).none { it.id == id }) { "ID 已存在" }
        val file = categoryFile(kind, category)
        file.parentFile.mkdirs()
        val firstGem = GemRegistry.ids().firstOrNull()
        require(kind == Kind.GEM || firstGem != null) { "先创建一颗宝石才能创建符文配方" }
        val entry = Entry(kind, id, file)
        edit(entry, allowMissing = true) { cfg ->
            if (kind == Kind.GEM) {
                cfg.set("$id.Name", id)
                cfg.set("$id.Material", "PAPER")
                cfg.set("$id.Display", "&f$id")
                cfg.set("$id.Rewards", emptyList<String>())
            } else {
                cfg.set("Recipes.$id.Display", id)
                cfg.set("Recipes.$id.Result", firstGem)
                cfg.set("Recipes.$id.Amount", 1)
                cfg.set("Recipes.$id.Ingredients.$firstGem", 1)
            }
        }
        return entry
    }

    fun delete(entry: Entry) = edit(entry) { it.set(path(entry), null) }

    fun setField(entry: Entry, key: String, input: String) {
        require(key.matches(Regex("[A-Za-z][A-Za-z0-9]*"))) { "字段名无效" }
        require(!key.equals("RemoveTip", true)) { "拆卸提示已移至独立拆卸方案与语言文件" }
        require(key !in setOf("Recipes", "Gems", "Ingredients", "Rewards", "Skills")) { "此字段请使用专用编辑操作" }
        val value: Any = when {
            key in setOf("Require", "Tips", "Gui", "Slot") -> input.split('|').map { it.trim() }.filter { it.isNotBlank() }
            key in setOf("Success", "Embed", "Amount") -> input.toIntOrNull() ?: error("请输入整数")
            key in setOf("Glow", "Eat", "GiveItem", "RandomGiveItem", "TrackApplied", "Enabled") -> input.toBooleanStrictOrNull() ?: error("请输入 true 或 false")
            key == "Cooldown" -> input.toDoubleOrNull() ?: error("请输入数字")
            else -> input
        }
        edit(entry) { it.set("${path(entry)}.$key", value) }
    }

    fun clearField(entry: Entry, key: String) {
        require(key.matches(Regex("[A-Za-z][A-Za-z0-9]*"))) { "字段名无效" }
        require(key !in setOf("Rewards", "Skills", "Ingredients", "Name", "Material", "Result", "Amount")) { "此字段不能清空" }
        edit(entry) { it.set("${path(entry)}.$key", null) }
    }

    fun setIngredient(entry: Entry, gemId: String, amount: Int) {
        require(entry.kind == Kind.RUNE && GemRegistry.get(gemId) != null) { "材料宝石不存在" }
        require(amount in 0..4096) { "数量须为 0..4096" }
        edit(entry) { it.set("${path(entry)}.Ingredients.$gemId", if (amount == 0) null else amount) }
    }

    fun setPoolWeight(entry: Entry, gemId: String, weight: Int) {
        require(entry.kind == Kind.GEM && GemRegistry.get(gemId) != null) { "奖池宝石不存在" }
        require(gemId != entry.id) { "奖池不能直接包含自身" }
        require(weight in 0..4096) { "权重须为 0..4096" }
        edit(entry) { it.set("${path(entry)}.Gems.$gemId", if (weight == 0) null else weight) }
    }

    fun actionCount(entry: Entry): Int = actions(entry).size
    fun actionFields(raw: Any): Map<String, String> = when (raw) {
        is Map<*, *> -> raw.entries.associate { it.key.toString() to it.value.toString() }
        else -> ActionSyntax.rewardMap(ActionSyntax.reward(raw)).mapValues { it.value.toString() }
    }
    fun actions(entry: Entry): List<Any> {
        val section = Configuration.loadFromFile(entry.file).getConfigurationSection(path(entry)) ?: return emptyList()
        return ActionSyntax.entries(section, if (entry.kind == Kind.GEM) "Rewards" else "Skills")
    }

    fun appendAction(entry: Entry, raw: String) {
        require(entry.kind == Kind.GEM) { "只有宝石可以设置奖励" }
        val parsed = ActionSyntax.editorReward(raw)
        require(!parsed.flags.contains("onRemove")) { "拆卸奖励已移至 dismantle/ 方案配置" }
        require(parsed.call.name.isNotBlank()) { "缺少奖励函数" }
        val values = actions(entry).toMutableList()
        values += ActionSyntax.rewardMap(parsed)
        setActions(entry, values)
    }

    fun setActionField(entry: Entry, index: Int, key: String, value: String?) {
        require(key.matches(Regex("[A-Za-z][A-Za-z0-9_]*"))) { "参数名无效" }
        require(!key.equals("phase", true) || value?.equals("remove", true) != true) { "拆卸规则应写入 dismantle/ 方案" }
        val values = actions(entry).toMutableList()
        require(index in values.indices) { "动作不存在" }
        val old = values[index]
        val map = if (old is Map<*, *>) old.entries.associate { it.key.toString() to it.value.toString() }.toMutableMap()
            else ActionSyntax.rewardMap(ActionSyntax.reward(old)).mapValues { it.value.toString() }.toMutableMap()
        if (value == null) map.remove(key) else map[key] = value
        values[index] = map
        setActions(entry, values)
    }

    fun removeAction(entry: Entry, index: Int) {
        val values = actions(entry).toMutableList()
        require(index in values.indices) { "动作不存在" }
        values.removeAt(index)
        setActions(entry, values)
    }

    private fun setActions(entry: Entry, values: List<Any>) = edit(entry) { it.set("${path(entry)}.Rewards", values) }
    private fun path(entry: Entry) = if (entry.kind == Kind.GEM) entry.id else "Recipes.${entry.id}"

    private fun edit(entry: Entry, allowMissing: Boolean = false, mutate: (Configuration) -> Unit) {
        if (!allowMissing) require(entry.file.isFile) { "配置文件不存在" }
        val existed = entry.file.isFile
        val before = if (existed) entry.file.readBytes() else null
        try {
            val cfg = if (existed) Configuration.loadFromFile(entry.file) else Configuration.empty()
            mutate(cfg)
            cfg.saveToFile(entry.file)
            require(SnowyGems.reloadAll()) { "修改后配置未通过校验" }
            DebugUtil.log("Editor", "保存 ${entry.kind} ${entry.id} 至 ${entry.file.name}，配置校验通过")
        } catch (e: Exception) {
            if (before == null) entry.file.delete() else entry.file.writeBytes(before)
            SnowyGems.reloadAll()
            DebugUtil.log("Editor", "回滚 ${entry.kind} ${entry.id}：${e.message}")
            throw IllegalStateException("未保存：${e.message}", e)
        }
    }
}
