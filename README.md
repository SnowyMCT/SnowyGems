# SnowyGems

基于 TabooLib 的宝石与符文插件。源码构建版本为 `0.0.4`；当前工作区的改动尚未发布。

## 工作台入口

- `/sgem embed`：通用宝石镶嵌台，一件装备、一种宝石，预览后确认。
- `/sgem rune`：预览并确认符文合成、升级和二级回收，配方位于 `runes/forge.yml`；玩家权限 `snowygems.rune`。
- `/sgem open 符文镶嵌台`：按颜色放入符文，确认后每个非空槽尝试一颗。
- `/sgem open 符文分解台`：将未镶嵌的示例一级符文分解为 10 个碎片。
- `/sgem inspect`、`/sgem dismantle`：查看与拆卸主手装备上的宝石。

新符文示例在 `src/main/resources/gems/RuneGem.yml`。已有服务器的 `gui/gui.yml` 和 `gui/rune.yml` 不会自动覆盖，更新布局时需要合并新确认按钮；旧菜单可空光标 Shift+右键装备进行镶嵌。

新镶嵌逐颗记录实际奖励变化。旧装备缺失的历史次数及增量无法恢复，旧物品拆卸保留兼容行为。Java 编译目标为 21；目标服务端兼容性、Vault/PlayerPoints 和实际背包交互仍需测试服验证。

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
