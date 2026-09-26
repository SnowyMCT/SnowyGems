package mc233.`fun`.snowygems.gui

import mc233.`fun`.snowygems.Permissions
import mc233.`fun`.snowygems.config.EditorStore
import mc233.`fun`.snowygems.config.GemRegistry
import mc233.`fun`.snowygems.util.ColorUtil
import mc233.`fun`.snowygems.util.DebugUtil
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import taboolib.common.platform.function.submit
import taboolib.library.configuration.ConfigurationSection
import taboolib.library.xseries.XMaterial
import taboolib.module.ui.openMenu
import taboolib.module.ui.type.Anvil
import taboolib.module.ui.type.Linked
import taboolib.platform.util.buildItem

/** Every editor operation stays in inventory GUIs; free text is entered in an anvil GUI. */
object InGameEditor {
    private val contents get() = EditorTheme.contentSlots()
    private val fieldGroups = linkedMapOf(
        "基础" to listOf("Name", "Type", "Enabled", "Success", "Embed", "TrackApplied", "RandomGiveItem"),
        "外观" to listOf("Display", "Material", "Texture", "Color", "Glow", "Tips", "Gui", "SuccessTip", "FailTip"),
        "限制" to listOf("Require", "Slot", "ExclusiveGroup", "Cooldown", "Eat", "GiveItem")
    )
    private val actionTemplates = linkedMapOf(
        "空动作" to "Empty",
        "属性加成" to "Attribute name=health, operation=0, slot=auto, var=v+1, limit=10",
        "添加说明" to "LoreAdd lore=&7新说明",
        "修改名称" to "Name name=&f新名称",
        "无限耐久" to "Unbreakable",
        "点券" to "Point amount=0",
        "金币" to "Money amount=0",
        "经验等级" to "ExpLevel amount=0"
    )

    private fun icon(role: String, material: XMaterial, name: String, vararg lines: String) =
        EditorTheme.icon(role, material, name, lines.toList())

    private fun gemIcon(entry: EditorStore.Entry) = EditorStore.fields(entry).let { fields ->
        val material = XMaterial.matchXMaterial(fields["Material"]?.toString() ?: "PAPER").orElse(XMaterial.PAPER)
        EditorTheme.icon("EntryGem", material, ColorUtil.colorize(fields["Display"]?.toString() ?: "&f${entry.id}"),
            listOf("§8${entry.id}", "§7分类 §f${entry.file.nameWithoutExtension}", "§e左键 §7编辑"),
            mapOf("id" to entry.id, "category" to entry.file.nameWithoutExtension))
    }

    private fun pageIcon(enabled: Boolean, next: Boolean) = icon(
        if (next) { if (enabled) "Next" else "NextDisabled" } else { if (enabled) "Previous" else "PreviousDisabled" },
        if (enabled) XMaterial.ARROW else XMaterial.GRAY_STAINED_GLASS_PANE,
        if (next) "§e下一页" else "§e上一页"
    )

    private fun <T> Linked<T>.frame(rows: Int) {
        onClick(lock = true) { it.isCancelled = true }
        val border = (0..8) + ((rows - 1) * 9 until rows * 9) +
            (1 until rows - 1).flatMap { listOf(it * 9, it * 9 + 8) }
        onBuild { _, inventory ->
            for (slot in border) {
                if (inventory.getItem(slot)?.type?.isAir != false) {
                    inventory.setItem(slot, icon("Border", XMaterial.BLACK_STAINED_GLASS_PANE, " "))
                }
            }
        }
    }

    private fun next(player: Player, action: () -> Unit) = submit(delay = 1) {
        if (player.isOnline && player.hasPermission(Permissions.EDIT)) safe(player, action)
    }

    private fun safe(player: Player, action: () -> Unit) {
        if (!player.hasPermission(Permissions.EDIT)) return
        runCatching(action).onFailure { player.sendMessage("§c[SnowyGems] ${it.message}") }
    }

    fun open(player: Player) {
        if (!player.hasPermission(Permissions.EDIT)) return
        DebugUtil.log("Editor", "${player.name} 打开游戏内编辑器")
        player.openMenu<Linked<EditorStore.Kind>>(EditorTheme.title("Home", "§8◆ SnowyGems 编辑台")) {
            rows(3)
            frame(3)
            slots(listOf(EditorTheme.slot("HomeGem", 11), EditorTheme.slot("HomeRune", 15)))
            elements { EditorStore.Kind.entries }
            onGenerate { _, kind, _, _ ->
                if (kind == EditorStore.Kind.GEM) icon("HomeGem", XMaterial.DIAMOND, "§b◆ 宝石管理", "§7按分类浏览宝石", "§e点击进入")
                else icon("HomeRune", XMaterial.SMITHING_TABLE, "§d◆ 符文配方", "§7按配方包浏览", "§e点击进入")
            }
            onClick { event, kind -> event.isCancelled = true; next(player) { categories(player, kind) } }
            set(EditorTheme.slot("HomeHelp", 22), icon("HomeHelp", XMaterial.BOOK, "§e操作提示", "§7所有输入都在铁砧界面完成", "§7红色按钮会进入确认界面")) { isCancelled = true }
        }
    }

    private fun categories(player: Player, kind: EditorStore.Kind) {
        DebugUtil.log("Editor", "${player.name} 浏览 ${kind.name} 分类")
        player.openMenu<Linked<String>>(if (kind == EditorStore.Kind.GEM)
            EditorTheme.title("CategoriesGem", "§8◆ 宝石分类") else EditorTheme.title("CategoriesRune", "§8◆ 配方分类")) {
            rows(6); frame(6); slots(contents)
            elements { EditorStore.categories(kind) }
            onGenerate { _, category, _, _ -> EditorTheme.icon(
                if (kind == EditorStore.Kind.GEM) "CategoryGem" else "CategoryRune",
                if (kind == EditorStore.Kind.GEM) XMaterial.CHEST else XMaterial.BOOKSHELF,
                "§b◆ $category", listOf("§7条目 §f${EditorStore.list(kind).count { it.file.nameWithoutExtension == category }}",
                "§e左键 §7打开分类", "§c右键 §7删除空分类"),
                mapOf("category" to category, "count" to EditorStore.list(kind).count { it.file.nameWithoutExtension == category }.toString())
            ) }
            onClick { event, category ->
                event.isCancelled = true
                if (event.clickEventOrNull()?.click == ClickType.RIGHT) next(player) {
                    confirm(player, "删除分类 $category", { categories(player, kind) }) {
                        EditorStore.deleteCategory(kind, category); categories(player, kind)
                    }
                } else next(player) { entries(player, kind, category) }
            }
            set(EditorTheme.slot("Back", 45), icon("Back", XMaterial.ARROW, "§e返回首页")) { isCancelled = true; next(player) { open(player) } }
            set(EditorTheme.slot("Add", 49), icon("Add", XMaterial.LIME_DYE, "§a创建分类", "§7将创建独立配置文件")) {
                isCancelled = true
                input(player, "新分类名称", "", { categories(player, kind) }) {
                    EditorStore.createCategory(kind, it); categories(player, kind)
                }
            }
            setNextPage(EditorTheme.slot("Next", 50)) { _, has -> pageIcon(has, true) }
            setPreviousPage(EditorTheme.slot("Previous", 48)) { _, has -> pageIcon(has, false) }
        }
    }

    private fun entries(player: Player, kind: EditorStore.Kind, category: String) {
        player.openMenu<Linked<EditorStore.Entry>>(EditorTheme.title("Entries", "§8◆ $category", mapOf("category" to category))) {
            rows(6); frame(6); slots(contents)
            elements { EditorStore.list(kind).filter { it.file.nameWithoutExtension == category } }
            onGenerate { _, entry, _, _ -> if (kind == EditorStore.Kind.GEM) gemIcon(entry)
                else icon("EntryRune", XMaterial.ENCHANTED_BOOK, "§d${entry.id}", "§7配方分类 §f$category", "§e左键 §7编辑") }
            onClick { event, entry -> event.isCancelled = true; next(player) { detail(player, entry) } }
            set(EditorTheme.slot("Back", 45), icon("Back", XMaterial.ARROW, "§e返回分类")) { isCancelled = true; next(player) { categories(player, kind) } }
            set(EditorTheme.slot("Add", 49), icon("Add", XMaterial.LIME_DYE, "§a新建${if (kind == EditorStore.Kind.GEM) "宝石" else "配方"}", "§7创建于 $category")) {
                isCancelled = true
                input(player, "新条目 ID", "", { entries(player, kind, category) }) {
                    val entry = EditorStore.create(kind, it, category); detail(player, entry)
                }
            }
            setNextPage(EditorTheme.slot("Next", 50)) { _, has -> pageIcon(has, true) }
            setPreviousPage(EditorTheme.slot("Previous", 48)) { _, has -> pageIcon(has, false) }
        }
    }

    private fun detail(player: Player, entry: EditorStore.Entry) {
        val fields = EditorStore.fields(entry)
        player.openMenu<Linked<String>>(EditorTheme.title("Detail", "§8◆ ${entry.id}", mapOf("id" to entry.id))) {
            rows(4); frame(4); slots(listOf(10, 12, 14, 16, 22))
            elements { if (entry.kind == EditorStore.Kind.GEM) listOf("基础", "外观", "限制", "奖励", "奖池")
                else listOf("基础", "材料") }
            onGenerate { _, group, _, _ -> when (group) {
                "基础" -> icon("GroupBasic", XMaterial.WRITABLE_BOOK, "§e基础信息", "§7名称、类型及开关")
                "外观" -> icon("GroupAppearance", XMaterial.PAINTING, "§b外观显示", "§7图标、说明和光效")
                "限制" -> icon("GroupLimit", XMaterial.IRON_CHESTPLATE, "§a使用限制", "§7装备与使用条件")
                "奖励" -> icon("GroupRewards", XMaterial.ENCHANTED_BOOK, "§d奖励动作", "§7添加或修改动作")
                "奖池" -> icon("GroupPool", XMaterial.ENDER_CHEST, "§5随机奖池", "§7随机宝石的子宝石与权重")
                else -> icon("GroupIngredients", XMaterial.CHEST, "§d配方材料", "§7选择宝石并设置数量")
            } }
            onClick { event, group -> event.isCancelled = true; next(player) {
                when (group) {
                    "奖励" -> actions(player, entry)
                    "材料" -> ingredients(player, entry)
                    "奖池" -> pool(player, entry)
                    else -> fields(player, entry, group)
                }
            } }
            set(EditorTheme.slot("DetailSummary", 4), icon("DetailSummary", if (entry.kind == EditorStore.Kind.GEM) XMaterial.DIAMOND else XMaterial.SMITHING_TABLE,
                "§f${entry.id}", "§7分类 §f${entry.file.nameWithoutExtension}", "§7字段 §f${fields.size}")) { isCancelled = true }
            set(EditorTheme.slot("DetailBack", 27), icon("Back", XMaterial.ARROW, "§e返回条目列表")) { isCancelled = true; next(player) { entries(player, entry.kind, entry.file.nameWithoutExtension) } }
            set(EditorTheme.slot("DetailMove", 29), icon("DetailMove", XMaterial.HOPPER, "§b移动分类", "§7选择目标分类")) { isCancelled = true; next(player) { move(player, entry) } }
            set(EditorTheme.slot("DetailFields", 31), icon("DetailFields", XMaterial.BOOK, "§e全部字段", "§7浏览配置中的所有普通字段")) { isCancelled = true; next(player) { fields(player, entry, "全部") } }
            set(EditorTheme.slot("DetailDelete", 35), icon("DetailDelete", XMaterial.BARRIER, "§c删除 ${entry.id}", "§7下一步将再次确认")) { isCancelled = true; next(player) {
                confirm(player, "删除 ${entry.id}", { detail(player, entry) }) {
                    EditorStore.delete(entry); entries(player, entry.kind, entry.file.nameWithoutExtension)
                }
            } }
        }
    }

    private fun fields(player: Player, entry: EditorStore.Entry, group: String) {
        val current = EditorStore.fields(entry)
        val keys = if (entry.kind == EditorStore.Kind.RUNE) listOf("Display", "Result", "Amount", "Enabled")
        else if (group == "全部") (fieldGroups.values.flatten() + current.keys).distinct()
        else fieldGroups[group].orEmpty()
        val visible = keys.filter { it !in setOf("Rewards", "Skills", "Ingredients", "RemoveTip") }
        player.openMenu<Linked<String>>(EditorTheme.title("Fields", "§8◆ ${entry.id} · $group", mapOf("id" to entry.id, "group" to group))) {
            rows(6); frame(6); slots(contents); elements { visible }
            onGenerate { _, key, _, _ ->
                val value = current[key]?.toString() ?: "未设置"
                icon("Field", fieldIcon(key), "§e$key", "§7当前 §f${value.take(60)}", "§e左键 §7编辑",
                    "§c右键 §7清空可选字段")
            }
            onClick { event, key ->
                event.isCancelled = true
                if (event.clickEventOrNull()?.click == ClickType.RIGHT) next(player) {
                    confirm(player, "清空 $key", { fields(player, entry, group) }) {
                        EditorStore.clearField(entry, key); fields(player, entry, group)
                    }
                } else if (key in setOf("Glow", "Eat", "GiveItem", "RandomGiveItem", "TrackApplied", "Enabled")) next(player) {
                    EditorStore.setField(entry, key, (!(current[key]?.toString()?.toBoolean() ?: false)).toString())
                    fields(player, entry, group)
                } else input(player, "设置 $key", current[key]?.let(::displayValue) ?: "", { fields(player, entry, group) }) {
                    EditorStore.setField(entry, key, it); fields(player, entry, group)
                }
            }
            set(EditorTheme.slot("Back", 45), icon("Back", XMaterial.ARROW, "§e返回条目")) { isCancelled = true; next(player) { detail(player, entry) } }
            set(EditorTheme.slot("Add", 49), icon("Add", XMaterial.WRITABLE_BOOK, "§a自定义字段", "§7先输入字段名，再填写值")) { isCancelled = true
                input(player, "字段名", "", { fields(player, entry, group) }) { key ->
                    input(player, "设置 $key", "", { fields(player, entry, group) }) {
                        EditorStore.setField(entry, key, it); fields(player, entry, group)
                    }
                }
            }
            setNextPage(EditorTheme.slot("Next", 50)) { _, has -> pageIcon(has, true) }
            setPreviousPage(EditorTheme.slot("Previous", 48)) { _, has -> pageIcon(has, false) }
        }
    }

    private fun fieldIcon(key: String) = when (key) {
        "Material", "Texture", "Display", "Glow" -> XMaterial.PAINTING
        "Require", "Slot" -> XMaterial.IRON_CHESTPLATE
        "Success", "Cooldown" -> XMaterial.CLOCK
        "Result", "Amount" -> XMaterial.SMITHING_TABLE
        else -> XMaterial.PAPER
    }

    private fun displayValue(value: Any?): String = when (value) {
        is Collection<*> -> value.joinToString(" | ")
        else -> value?.toString() ?: "新值"
    }

    private fun move(player: Player, entry: EditorStore.Entry) {
        player.openMenu<Linked<String>>(EditorTheme.title("Move", "§8◆ 移动 ${entry.id}", mapOf("id" to entry.id))) {
            rows(6); frame(6); slots(contents)
            elements { EditorStore.categories(entry.kind).filter { it != entry.file.nameWithoutExtension } }
            onGenerate { _, category, _, _ -> icon("MoveCategory", XMaterial.CHEST, "§b$category", "§e点击移动到此分类") }
            onClick { event, category -> event.isCancelled = true; next(player) {
                confirm(player, "移动至 $category", { move(player, entry) }) {
                    detail(player, EditorStore.move(entry, category))
                }
            } }
            set(EditorTheme.slot("Back", 45), icon("Back", XMaterial.ARROW, "§e返回条目")) { isCancelled = true; next(player) { detail(player, entry) } }
            setNextPage(EditorTheme.slot("Next", 50)) { _, has -> pageIcon(has, true) }
            setPreviousPage(EditorTheme.slot("Previous", 48)) { _, has -> pageIcon(has, false) }
        }
    }

    private fun actions(player: Player, entry: EditorStore.Entry) {
        val values = EditorStore.actions(entry)
        player.openMenu<Linked<Int>>(EditorTheme.title("Actions", "§8◆ ${entry.id} · 奖励", mapOf("id" to entry.id))) {
            rows(6); frame(6); slots(contents); elements { values.indices.toList() }
            onGenerate { _, index, _, _ ->
                val fields = EditorStore.actionFields(values[index])
                icon("Action", XMaterial.ENCHANTED_BOOK, "§d#${index + 1} ${fields["action"] ?: "动作"}",
                    "§7${fields.entries.filter { it.key != "action" }.joinToString(", ") { "${it.key}=${it.value}" }.take(70)}",
                    "§e左键 §7编辑参数", "§c右键 §7删除动作")
            }
            onClick { event, index -> event.isCancelled = true
                if (event.clickEventOrNull()?.click == ClickType.RIGHT) next(player) {
                    confirm(player, "删除动作 #${index + 1}", { actions(player, entry) }) {
                        EditorStore.removeAction(entry, index); actions(player, entry)
                    }
                } else next(player) { actionDetail(player, entry, index) }
            }
            set(EditorTheme.slot("Back", 45), icon("Back", XMaterial.ARROW, "§e返回条目")) { isCancelled = true; next(player) { detail(player, entry) } }
            set(EditorTheme.slot("Add", 49), icon("Add", XMaterial.LIME_DYE, "§a新增奖励动作", "§7选择常用模板或自定义")) { isCancelled = true; next(player) { chooseAction(player, entry) } }
            setNextPage(EditorTheme.slot("Next", 50)) { _, has -> pageIcon(has, true) }
            setPreviousPage(EditorTheme.slot("Previous", 48)) { _, has -> pageIcon(has, false) }
        }
    }

    private fun chooseAction(player: Player, entry: EditorStore.Entry) {
        val choices = actionTemplates.keys.toList() + "自定义动作"
        player.openMenu<Linked<String>>(EditorTheme.title("ChooseAction", "§8◆ 选择奖励动作")) {
            rows(6); frame(6); slots(contents); elements { choices }
            onGenerate { _, choice, _, _ -> icon("ActionChoice",
                if (choice == "自定义动作") XMaterial.WRITABLE_BOOK else XMaterial.ENCHANTED_BOOK,
                "§d$choice", if (choice == "自定义动作") "§7在铁砧输入完整动作" else "§7${actionTemplates[choice]}"
            ) }
            onClick { event, choice -> event.isCancelled = true
                if (choice == "自定义动作") input(player, "新增奖励动作", "", { chooseAction(player, entry) }) {
                    EditorStore.appendAction(entry, it); actionDetail(player, entry, EditorStore.actionCount(entry) - 1)
                } else next(player) {
                    EditorStore.appendAction(entry, actionTemplates.getValue(choice))
                    actionDetail(player, entry, EditorStore.actionCount(entry) - 1)
                }
            }
            set(EditorTheme.slot("Back", 45), icon("Back", XMaterial.ARROW, "§e返回动作列表")) { isCancelled = true; next(player) { actions(player, entry) } }
            setNextPage(EditorTheme.slot("Next", 50)) { _, has -> pageIcon(has, true) }
            setPreviousPage(EditorTheme.slot("Previous", 48)) { _, has -> pageIcon(has, false) }
        }
    }

    private fun actionDetail(player: Player, entry: EditorStore.Entry, index: Int) {
        val raw = EditorStore.actions(entry).getOrNull(index) ?: return actions(player, entry)
        val values = EditorStore.actionFields(raw)
        player.openMenu<Linked<String>>(EditorTheme.title("ActionDetail", "§8◆ 动作 #${index + 1}", mapOf("index" to (index + 1).toString()))) {
            rows(6); frame(6); slots(contents); elements { values.keys.toList() }
            onGenerate { _, key, _, _ -> icon("ActionField", XMaterial.PAPER, "§e$key", "§f${values[key]?.take(65)}",
                "§e左键 §7修改", "§c右键 §7删除参数") }
            onClick { event, key -> event.isCancelled = true
                if (event.clickEventOrNull()?.click == ClickType.RIGHT && key != "action") next(player) {
                    EditorStore.setActionField(entry, index, key, null); actionDetail(player, entry, index)
                } else input(player, "设置 $key", values[key] ?: "", { actionDetail(player, entry, index) }) {
                    EditorStore.setActionField(entry, index, key, it); actionDetail(player, entry, index)
                }
            }
            set(EditorTheme.slot("Back", 45), icon("Back", XMaterial.ARROW, "§e返回动作列表")) { isCancelled = true; next(player) { actions(player, entry) } }
            set(EditorTheme.slot("Add", 49), icon("Add", XMaterial.LIME_DYE, "§a添加参数", "§7先输入参数名，再输入参数值")) { isCancelled = true
                input(player, "参数名", "", { actionDetail(player, entry, index) }) { key ->
                    input(player, "设置 $key", "", { actionDetail(player, entry, index) }) {
                        EditorStore.setActionField(entry, index, key, it); actionDetail(player, entry, index)
                    }
                }
            }
            setNextPage(EditorTheme.slot("Next", 50)) { _, has -> pageIcon(has, true) }
            setPreviousPage(EditorTheme.slot("Previous", 48)) { _, has -> pageIcon(has, false) }
        }
    }

    private fun pool(player: Player, entry: EditorStore.Entry) {
        val raw = EditorStore.fields(entry)["Gems"]
        val weights = when (raw) {
            is ConfigurationSection -> raw.getKeys(false).associateWith { raw.get(it)?.toString() ?: "?" }
            is Map<*, *> -> raw.entries.associate { it.key.toString() to it.value.toString() }
            else -> emptyMap()
        }
        player.openMenu<Linked<String>>(EditorTheme.title("Pool", "§8◆ ${entry.id} · 奖池", mapOf("id" to entry.id))) {
            rows(6); frame(6); slots(contents); elements { weights.keys.toList() }
            onGenerate { _, id, _, _ -> icon("PoolEntry", XMaterial.ENDER_EYE, "§d$id", "§7权重 §f${weights[id]}",
                "§e左键 §7修改权重", "§c右键 §7移除") }
            onClick { event, id -> event.isCancelled = true
                if (event.clickEventOrNull()?.click == ClickType.RIGHT) next(player) {
                    confirm(player, "移除奖池项 $id", { pool(player, entry) }) {
                        EditorStore.setPoolWeight(entry, id, 0); pool(player, entry)
                    }
                } else input(player, "奖池 $id 权重", weights[id] ?: "1", { pool(player, entry) }) {
                    EditorStore.setPoolWeight(entry, id, it.toIntOrNull() ?: error("请输入整数")); pool(player, entry)
                }
            }
            set(EditorTheme.slot("Back", 45), icon("Back", XMaterial.ARROW, "§e返回宝石")) { isCancelled = true; next(player) { detail(player, entry) } }
            set(EditorTheme.slot("Add", 49), icon("Add", XMaterial.LIME_DYE, "§a加入子宝石", "§7从宝石列表中选择")) { isCancelled = true; next(player) { choosePool(player, entry) } }
            setNextPage(EditorTheme.slot("Next", 50)) { _, has -> pageIcon(has, true) }
            setPreviousPage(EditorTheme.slot("Previous", 48)) { _, has -> pageIcon(has, false) }
        }
    }

    private fun choosePool(player: Player, entry: EditorStore.Entry) {
        player.openMenu<Linked<String>>(EditorTheme.title("ChoosePool", "§8◆ 选择奖池宝石")) {
            rows(6); frame(6); slots(contents); elements { GemRegistry.ids().filter { it != entry.id }.sorted() }
            onGenerate { _, id, _, _ -> icon("Selection", XMaterial.DIAMOND, "§b$id", "§e点击设置权重") }
            onClick { event, id -> event.isCancelled = true
                input(player, "奖池 $id 权重", "1", { choosePool(player, entry) }) {
                    EditorStore.setPoolWeight(entry, id, it.toIntOrNull() ?: error("请输入整数")); pool(player, entry)
                }
            }
            set(EditorTheme.slot("Back", 45), icon("Back", XMaterial.ARROW, "§e返回奖池")) { isCancelled = true; next(player) { pool(player, entry) } }
            setNextPage(EditorTheme.slot("Next", 50)) { _, has -> pageIcon(has, true) }
            setPreviousPage(EditorTheme.slot("Previous", 48)) { _, has -> pageIcon(has, false) }
        }
    }

    private fun ingredients(player: Player, entry: EditorStore.Entry) {
        val raw = EditorStore.fields(entry)["Ingredients"]
        val amounts = when (raw) {
            is ConfigurationSection -> raw.getKeys(false).associateWith { raw.get(it)?.toString() ?: "?" }
            is Map<*, *> -> raw.entries.associate { it.key.toString() to it.value.toString() }
            else -> emptyMap()
        }
        player.openMenu<Linked<String>>(EditorTheme.title("Ingredients", "§8◆ ${entry.id} · 材料", mapOf("id" to entry.id))) {
            rows(6); frame(6); slots(contents); elements { amounts.keys.toList() }
            onGenerate { _, id, _, _ -> icon("IngredientEntry", XMaterial.DIAMOND, "§b$id", "§7数量 §f${amounts[id]}",
                "§e左键 §7修改数量", "§c右键 §7移除") }
            onClick { event, id -> event.isCancelled = true
                if (event.clickEventOrNull()?.click == ClickType.RIGHT) next(player) {
                    confirm(player, "移除材料 $id", { ingredients(player, entry) }) {
                        EditorStore.setIngredient(entry, id, 0); ingredients(player, entry)
                    }
                } else input(player, "材料 $id 数量", amounts[id] ?: "1", { ingredients(player, entry) }) {
                    EditorStore.setIngredient(entry, id, it.toIntOrNull() ?: error("请输入整数")); ingredients(player, entry)
                }
            }
            set(EditorTheme.slot("Back", 45), icon("Back", XMaterial.ARROW, "§e返回配方")) { isCancelled = true; next(player) { detail(player, entry) } }
            set(EditorTheme.slot("Add", 49), icon("Add", XMaterial.LIME_DYE, "§a添加材料", "§7从宝石列表中选择")) { isCancelled = true; next(player) { chooseIngredient(player, entry) } }
            setNextPage(EditorTheme.slot("Next", 50)) { _, has -> pageIcon(has, true) }
            setPreviousPage(EditorTheme.slot("Previous", 48)) { _, has -> pageIcon(has, false) }
        }
    }

    private fun chooseIngredient(player: Player, entry: EditorStore.Entry) {
        player.openMenu<Linked<String>>(EditorTheme.title("ChooseIngredient", "§8◆ 选择材料宝石")) {
            rows(6); frame(6); slots(contents); elements { GemRegistry.ids().sorted() }
            onGenerate { _, id, _, _ -> icon("Selection", XMaterial.DIAMOND, "§b$id", "§e点击设置数量") }
            onClick { event, id -> event.isCancelled = true
                input(player, "材料 $id 数量", "1", { chooseIngredient(player, entry) }) {
                    EditorStore.setIngredient(entry, id, it.toIntOrNull() ?: error("请输入整数")); ingredients(player, entry)
                }
            }
            set(EditorTheme.slot("Back", 45), icon("Back", XMaterial.ARROW, "§e返回材料")) { isCancelled = true; next(player) { ingredients(player, entry) } }
            setNextPage(EditorTheme.slot("Next", 50)) { _, has -> pageIcon(has, true) }
            setPreviousPage(EditorTheme.slot("Previous", 48)) { _, has -> pageIcon(has, false) }
        }
    }

    private fun confirm(player: Player, title: String, cancel: () -> Unit, apply: () -> Unit) {
        player.openMenu<Linked<Int>>(EditorTheme.title("Confirm", "§8◆ 确认操作")) {
            rows(3); frame(3); slots(emptyList()); elements { emptyList() }
            set(EditorTheme.slot("ConfirmYes", 11), icon("ConfirmYes", XMaterial.LIME_CONCRETE, "§a确认", "§7$title")) { isCancelled = true; next(player) { apply() } }
            set(EditorTheme.slot("ConfirmInfo", 13), icon("ConfirmInfo", XMaterial.PAPER, "§f$title", "§7请确认此次修改")) { isCancelled = true }
            set(EditorTheme.slot("ConfirmNo", 15), icon("ConfirmNo", XMaterial.RED_CONCRETE, "§c取消", "§7返回上一步")) { isCancelled = true; next(player, cancel) }
        }
    }

    private fun input(player: Player, title: String, initial: String, cancel: () -> Unit, apply: (String) -> Unit) {
        next(player) { inputNow(player, title, initial, cancel, apply) }
    }

    private fun inputNow(player: Player, title: String, initial: String, cancel: () -> Unit, apply: (String) -> Unit) {
        if (!player.hasPermission(Permissions.EDIT)) return
        var value = initial
        player.openMenu<Anvil>(EditorTheme.title("Input", "§8◆ $title", mapOf("name" to title))) {
            set(0, icon("InputText", XMaterial.PAPER, initial.ifBlank { "输入内容" }, "§7在上方输入框修改文字"))
            set(1, icon("InputCancel", XMaterial.BARRIER, "§c取消"))
            set(2, icon("InputSave", XMaterial.EMERALD, "§a点击保存"))
            onRename { _, text, inventory ->
                value = text
                inventory.setItem(2, icon("InputSave", XMaterial.EMERALD, "§a保存：${text.take(40)}"))
            }
            onClick(lock = true) { event ->
                event.isCancelled = true
                when (event.rawSlot) {
                    1 -> next(player, cancel)
                    2 -> next(player) {
                        val text = value.trim()
                        require(text.isNotEmpty()) { "内容不能为空" }
                        apply(text)
                    }
                }
            }
        }
    }
}
