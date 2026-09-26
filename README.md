# SnowyGems

基于 TabooLib 的宝石与符文插件。源码构建版本为 `0.0.7`。

0.0.7 修复随机点券兑换券对接 PlayerPoints 时因小数被拒绝的问题，并修复极小随机数的科学计数法解析。`Point` 表达式只求值一次，发放或扣除前向零取整，提示显示实际交易整数；Internal 与 PlayerPoints 使用相同奖励数额。旧配置 `100+2900*$RANDOM()` 可直接继续使用，实际发放 100～2999 点券。取整为 0、非有限数或绝对值超过 2147483647 的结果不生效。

0.0.7 支持对象式 YAML 动作配置。例如 `Rewards` 中依次写 `action: Attribute`、`name: health`、`var: "v+1"`；`Skills` 中用 `trigger: onTimer` 指定触发。旧文本行继续兼容。管理员可执行 `/sgem migrate` 备份并转换旧动作配置，或用 `/sgem editor` 在游戏内创建、修改、删除宝石与符文锻造配方。权限分别为 `snowygems.migrate`、`snowygems.edit`，包含在 `snowygems.admin` 中。

0.0.7 将拆卸价格与宝石返还成功率集中到 `dismantle/*.yml`。方案可按宝石 ID、分类、装备类别、珍贵程度、数量、耐久与附魔状态计算；金币、点券、经验分别计价并可组合。旧宝石文件的 `RemoveTip`、`phase: remove` / `$onRemove` 不再执行，旧 `config.yml` 的 `Dismantle` 节点也已忽略。

0.0.7 将游戏内编辑器改为全 GUI 操作：按分类管理宝石和符文配方，支持分类创建、条目移动、字段编辑、材料和奖励动作编辑，以及 GUI 删除确认。文字和数字在铁砧界面输入，不使用聊天输入。编辑器外观可在 `gui/editor.yml` 中调整。

0.0.7 补齐嵌套技能与 `Conditional` 嵌套奖励的对象式写法。`Chance`、`If`、`All` 等控制流使用 `then` 子动作，`/sgem migrate` 可将旧嵌套行递归转换，并检查转换前后解析结果一致。

`/sgem debug all` 会直接开启全部控制台调试日志；`/sgem debug <tag>` 开启指定范围，`/sgem debug off` 关闭。本次运行期间的调试状态不会因编辑器保存或 `/sgem reload` 丢失。

## 工作台入口

- `/sgem embed`：通用宝石镶嵌台，一件装备、一种宝石，预览后确认。
- `/sgem rune`：预览并确认符文合成、升级和二级回收，旧配方位于 `runes/forge.yml`，扩展配方位于 `runes/packs/`；玩家权限 `snowygems.rune`。
- `/sgem open 符文镶嵌台`：按颜色放入符文，确认后每个非空槽尝试一颗。
- `/sgem open 符文分解台`：将未镶嵌的示例一级符文分解为 10 个碎片。
- `/sgem inspect`：查看主手装备上的宝石；`/sgem dismantle`：打开拆卸台，放入装备后逐颗选择拆卸。

旧颜色符文在 `src/main/resources/gems/RuneGem.yml`，保留其原有 Lore 材料用途。已有服务器的 `gui/gui.yml` 和 `gui/rune.yml` 不会自动覆盖，更新布局时需要合并新确认按钮；旧菜单可空光标 Shift+右键装备进行镶嵌。

新镶嵌逐颗记录实际奖励变化。旧装备缺失的历史次数及增量无法恢复，旧物品仅在能安全撤销时允许拆卸，缺少必要记录时拒绝返还。Java 编译目标为 21；目标服务端兼容性、Vault/PlayerPoints 和实际背包交互仍需测试服验证。

插件启动和 `/sgem reload` 后会执行配置自检。缺失的 `config.yml`、语言、菜单、宝石和符文文件会由 TabooLib 以 `replace = false` 方式补回，不覆盖已有配置；自检日志会列出仍缺失的文件。

## 验证

`./gradlew build` 会编译、运行回归测试并打包。测试覆盖菜单尺寸、费用取整、奖池权重溢出、奖励阶段、撤销记录损坏与 Lore 局部撤销。详细联机验收步骤见 `docs/VERIFICATION.md`。

## 构建发行版本

发行版本用于正常使用, 不含 TabooLib 本体

```
./gradlew build
```

## 构建开发版本

开发版本包含 TabooLib 本体, 用于开发者使用, 但不可运行

```
./gradlew taboolibBuildApi -PDeleteCode
```

> 参数 -PDeleteCode 表示移除所有逻辑代码以减少体积

## 安全修复与新增功能

- `/sgem catalog`：玩家只读宝石图鉴，权限 `snowygems.catalog`，默认包含在普通玩家权限中。
- `/sgem history <玩家名>`：管理员查看本次启动以来缓存的最近 5 条操作；权限 `snowygems.history`。
- 日志保存在插件 `logs/operations-*.log`，最多 5 个约 1 MiB 文件。记录使用、镶嵌、工作台按钮、符文合成、拆卸与退款结果。
- `Embed`：同 ID 已记录宝石的数量上限，0 不限制；`ExclusiveGroup`：不同 ID 的宝石互斥组；`TrackApplied: false`：一次性使用后不写入可拆卸记录。
- 修复石等纯一次性效果自动不进入拆卸列表；包含不可撤销效果的旧记录禁止拆卸返还。无限耐久、物品标志和技能隐藏新增撤销记录。
- 技能隐藏仍能触发技能、BUFF 和读取等级。请先拆掉隐藏粉尘再修改其他镶嵌，避免恢复过期技能。
- 主动技能和定时技能共享成功施放冷却；同一个 BUFF 每名玩家每轮最多成功施放一次。技能 `Slot` 支持 `mainhand/offhand/head/chest/legs/feet`。
- PlayerPoints 故障时暂停点券交易，不会切换到另一套余额。
- 配置语法、重复 ID、关键引用错误会拒绝重载并保留旧宝石/菜单/技能/配方。`strict` 现在真正拒绝错误配置。

完整说明、配置示例和升级验收见 `docs/SAFETY_UPDATE.md`。

## 新默认内容

新增 9 颗匠心宝石、16 种远征符文、6 项配套技能和 32 条配方。独立文件按缺失释放，旧配置不覆盖，旧 ID 与 Lore 保持不变。新符文通过 `/sgem rune` 锻造、`/sgem embed` 镶嵌。内容清单、材料发放和升级验收见 [默认内容更新](docs/DEFAULT_CONTENT_UPDATE.md)。
