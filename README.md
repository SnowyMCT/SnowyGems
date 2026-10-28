# SnowyGems



> **像换装备零件一样，给武器镶宝石吧**
> 属性、附魔、常驻 BUFF、主动技能 —— 一套宝石系统全部覆盖。

---

## 这插件是干什么的



玩家把「宝石」镶嵌到装备上，装备获得**属性加成**、**附魔等级**或**常驻 BUFF**；宝石还能带**主动技能**——右键、攻击、挖矿、投掷时触发。



不想要了可以**拆下来**，但要付费，而且有概率把宝石弄坏。



一句话：把 Minecraft 的装备强化做成一套可以自由堆料的系统，而具体怎么堆，全由你写配置决定。


---



## 为什么值得一试



### ✦ 一份配置跨版本通用



绝大多数插件用「版本号 ≥ X 就支持 Y」的写法，每出一个新版本就要等作者更新。SnowyGems **启动时直接读服务端注册表**，把当前实际存在的属性、附魔、药水效果、物品读成快照。



于是：



- 服务端加了什么新属性/新附魔，插件**立刻就能用**

- 配置里写了当前版本没有的东西，**只跳过那一条**并记日志，其他宝石照常工作

- 你用**数据包自定义**的附魔、属性同样在注册表里，一样能识别



多个不同版本的子服，**共用同一份配置就行**。


### ✦ 拆卸是一套完整的系统，不是「点一下就没了」



```yaml

# 拆卸价格与返还率在 dismantle/ 目录里配置，独立于 gems/

Default:

  Rarity: 1

  Cost:

    Money: "100 + (rarity - 1) * 25 + (total - 1) * 10 + damage * 0.5 + enchants * 20"

    Points: 0

    Exp: 0

  Success: "80 - (rarity - 1) * 3 - (total - 1) * 2"

Rules:                  # 按 Priority 从低到高叠加，后匹配的字段覆盖前面的

  precious-gems:

    Priority: 100

    Match:

      GemIds: ["神生命宝石", "神速度宝石"]

    Rarity: 4

    Cost:

      Points: "rarity * 100 + same * 50"

    Success: "85 - rarity * 5 - damage * 0.05"
```



拆卸时**真正撤销**这颗宝石加的属性修饰符、把附魔降级或移除——不是只在 lore 上做做样子。费用不够会直接拒绝，**不会动你的装备**。


价格与返还率都写成表达式，可用变量 `rarity` `total` `same` `damage` `enchants` `unbreakable`，支持 `+ - * / ()` 与 `min` / `max` / `clamp`；同一颗宝石被多条规则命中时按 `Priority` 从低到高叠加。



顺带解决了一个老大难：**镶嵌属性宝石后，下界合金套自带的护甲值和盔甲韧性不会消失**。（原理是先把装备原生属性固化再追加自己的修饰符，这是 Minecraft 的机制坑，很多同类插件都栽在这里。）



### ✦ 技能引擎：服主自己就能写技能



技能绑在**装备的 lore 文本**上，不是硬编码在宝石里。想给某件装备加技能，往 lore 写一行标记就行。



```yaml

疾风步:

  Lore: "&b疾风步"

  Cooldown: 6.0

  CooldownTip: ActionBar{p=&b疾风冷却;s=&b%.2fs;empty=&8|;full=&b|;length=40}

  Skills:

    - Velocity{forward=1.8;up=0.35} ~onShiftUse

    - Particle{name=CLOUD;count=30} ~onShiftUse

    - Sound{name=ENTITY_ENDER_DRAGON_FLAP;pitch=1.6} ~onShiftUse

```



支持**触发标记**（右键 / 命中 / 投出 / 定时…）、**条件判断**、**概率触发**、**延迟与重复**、**形态切换**，以及从 lore 里动态读数值（`$LORE:$`）。


### ✦ 全中文，而且是真的全中文



- 玩家能看到的**每一句话**都在 `lang/` 目录里：`zh_CN` / `zh_TW` / `en_US` / `ko_KR` 四份，每份 **296 个文案节点**；改语言用 `config.yml` 的 `Lang:`，改完执行 `/sgem reload` 生效

- 全部配置文件带中文注释，示例宝石也是中文

- 支持 `&` 颜色码，以及 `title:` / `actionbar:` / `none:` 三种发送方式前缀


### ✦ 排错不靠猜



宝石类插件最烦的是「镶嵌成功了，但啥也没变」。所以：



- `/sgem compat all` —— 配置自检，**区分**「你这个版本不支持」和「你写错了」两类问题，写错的还会给拼写建议

- `/sgem debug Reward,GemManager` —— **22 个 debug tag** 按范围过滤日志，能直接看到每条奖励为什么没生效

- 自带 **195 颗**示例宝石（`gems/` 下 10 个分类文件），从最接近你需求的那颗复制改起


---



## 功能一览



| 模块 | 内容 |

| --- | --- |

| **宝石类型** | 镶嵌类、`PlayerGem`（右键直接生效，如兑换券/药水）、`RandomGem`（开箱抽奖） |

| **奖励函数** | 属性、附魔、Lore 增删改、改名、无限耐久、修复、给点券/金币/经验、给物品、条件奖励等 |

| **适用范围** | 分类关键字（`WEAPON` `ARMOR` `SPEAR` `MACE`…）、后缀 `_SWORD`、前缀 `COPPER_*`、精确物品名、按 lore 匹配 |

| **技能引擎** | 触发标记、条件、概率、延迟、重复、形态切换、从 lore 读数值 |

| **常驻 BUFF** | 装备 lore 带标记即持续生效，可叠加等级 |

| **界面** | 宝石镶嵌台 + 用字符网格自定义的工作台菜单（12 种槽位 / 按钮类型） |

| **游戏内编辑器** | `/sgem editor` 直接改宝石与符文配方，保存即校验，失败自动回滚 |

| **拆卸** | 独立界面（`/sgem open 宝石拆卸台`），费用（金币/点券/经验）与返还率在 `dismantle/` 里按规则配 |

| **经济** | 金币走 Vault；点券支持内置账户或 PlayerPoints |

| **版本兼容** | 注册表快照 + 7 个可开关的版本功能模块，默认全自动 |

| **更新提示** | 服务器启动完成后静默检测一次，管理员上线也提示；可完全关闭 |

---

## 指令与权限

主命令 `/sgem`，权限节点 `snowygems.command`。每个子命令都有独立节点，另外提供 `snowygems.user` / `snowygems.admin` 两个**伞节点**，默认配置下**不需要动权限插件**。

共 **22 个节点**：2 个伞节点 + 20 个功能节点。



> **默认值**：`snowygems.user` 及其 8 个子节点 = **所有人可用**（`TRUE`）；`snowygems.admin` 及其 12 个子节点 = **仅 OP**（`OP`）。

### 节点层级

```text
snowygems.admin   [默认 OP]
├─ 12 个管理节点   [默认 OP]
└─ snowygems.user  [默认 TRUE]
   └─ 8 个玩家节点  [默认 TRUE]
```


- 给 `snowygems.admin` ⇒ 实际拿到全部 20 个功能节点（12 管理 + 8 玩家），因为 admin 的 children 里还挂了 `snowygems.user`。
- 给 `snowygems.user` ⇒ 只拿到 8 个玩家节点，任何管理节点都没有。

### 全部节点

| 节点 | 默认 | 归属 | 控制什么 |
| --- | --- | --- | --- |
| `snowygems.user` | TRUE | 伞节点 | 打包下面 8 个玩家节点 |
| `snowygems.admin` | OP | 伞节点 | 打包 12 个管理节点 + `snowygems.user` |
| `snowygems.command` | TRUE | user | `/sgem` 主命令本身（所有子命令的入口） |
| `snowygems.open` | TRUE | user | `/sgem open <菜单>`；右键被 `/sgem mark` 标记过的方块也走这条 |
| `snowygems.embed` | TRUE | user | 「宝石镶嵌台」菜单：把宝石镶到装备上 |
| `snowygems.dismantle` | TRUE | user | 「宝石拆卸台」菜单：把已镶嵌的宝石拆下来 |
| `snowygems.rune` | TRUE | user | `/sgem rune`、「符文镶嵌台」、「符文分解台」 |
| `snowygems.inspect` | TRUE | user | `/sgem inspect`：只读查看装备上镶了什么 |
| `snowygems.catalog` | TRUE | user | `/sgem catalog`：只读图鉴 |
| `snowygems.use` | TRUE | user | 使用宝石（全部通道，见下节） |
| `snowygems.view` | OP | admin | `/sgem view`：浏览并领取宝石 |
| `snowygems.give` | OP | admin | `/sgem give` |
| `snowygems.history` | OP | admin | `/sgem history` |
| `snowygems.edit` | OP | admin | `/sgem editor` + 游戏内编辑器全程 |
| `snowygems.repair` | OP | admin | `/sgem repair` |
| `snowygems.mark` | OP | admin | `/sgem mark` |
| `snowygems.reload` | OP | admin | `/sgem reload` |
| `snowygems.migrate` | OP | admin | `/sgem migrate` |
| `snowygems.compat` | OP | admin | `/sgem compat` |
| `snowygems.skills` | OP | admin | `/sgem skills` |
| `snowygems.triggers` | OP | admin | `/sgem triggers` |
| `snowygems.debug` | OP | admin | `/sgem debug` |



### 玩家指令（`snowygems.user`，默认所有人可用）



| 指令 | 作用 | 权限节点 |
| --- | --- | --- |
| `/sgem open <菜单>` | 打开工作台菜单：`宝石镶嵌台` / `符文镶嵌台` / `符文分解台` / `宝石拆卸台` | `snowygems.open` + 该菜单的 `Permission` |
| `/sgem inspect` | 查看手持装备上镶了哪些宝石（只读，不会误触） | `snowygems.inspect` |
| `/sgem use` | 使用手持的兑换券 / 饮品类宝石 | `snowygems.use` |
| `/sgem catalog` | 打开宝石图鉴（只读） | `snowygems.catalog` |
| `/sgem rune` | 打开符文锻造界面（配方预览 / 合成） | `snowygems.rune` |

> **`snowygems.use` 是"玩家能不能用宝石"的总开关**，覆盖全部使用通道：

| 使用通道                            | 需要的权限 |
|---------------------------------| --- |
| `/sgem use`                     | `snowygems.use` |
| 手持宝石右键使用（兑换券、礼包等）               | `snowygems.use` |
| `PlayerGem.yml` 文件中的宝石          | `snowygems.use` |
| 菜单里的 `USE_GEM` 按钮（默认只有「符文分解台」有） | `snowygems.use` + 该菜单的 `Permission` |
| 把宝石镶嵌到装备                        | `snowygems.embed` + `snowygems.open`，**不受** `snowygems.use` 影响 |
| 把已镶嵌的宝石拆下来                      | `snowygems.dismantle` + `snowygems.open`，**不受** `snowygems.use` 影响 |

缺 `snowygems.use` 时统一提示「你没有使用宝石的权限」，**不扣物品、不执行奖励**；`Eat: true` 的宝石也不会被当成普通食物吃掉（原版吃喝事件被插件取消了），只会留在手里。



### 管理指令（`snowygems.admin`，默认 OP）



| 指令 | 作用 | 权限节点 |
| --- | --- | --- |
| `/sgem view` | 分类浏览全部宝石并领取 | `snowygems.view` |
| `/sgem give <玩家> <宝石ID> [数量]` | 发放宝石 | `snowygems.give` |
| `/sgem history <玩家>` | 查看某玩家最近 5 条操作记录（参数可点击补全） | `snowygems.history` |
| `/sgem editor` | 游戏内编辑器：改宝石 / 符文配方（铁砧输入，保存即校验，失败自动回滚） | `snowygems.edit` |
| `/sgem repair` | 补登记：把手持装备上"生效了但没记进已镶嵌列表"的宝石补回来（旧版本的 BUFF 类宝石） | `snowygems.repair` |
| `/sgem mark <embedder / rune-forge>` | 把所指方块标记成宝石镶嵌台 / 符文锻造台 | `snowygems.mark` |
| `/sgem reload` | 重载全部配置 | `snowygems.reload` |
| `/sgem migrate` | 转换旧文件 / 旧配置 | `snowygems.migrate` |
| `/sgem compat [all]` | 版本能力报告 + 配置自检 | `snowygems.compat` |
| `/sgem skills [关键字]` | 列出全部技能函数与用法 | `snowygems.skills` |
| `/sgem triggers` | 列出全部触发标记 | `snowygems.triggers` |
| `/sgem debug [tag,...]` | 开关调试日志 | `snowygems.debug` |



### 菜单的细粒度权限（写在 `gui/*.yml` 里）



`/sgem open` 只是入口：玩家既要有 `snowygems.open`，也要有该菜单自己的 `Permission`（缺菜单权限时会给出明确提示，不会静默无效）。这两个条件是**并列**的，谁都不能替代谁，也不构成层级。默认值：



- `gui/gui.yml` → `宝石镶嵌台` = `snowygems.embed`，`符文镶嵌台` = `snowygems.rune`

- `gui/rune.yml` → `符文分解台` = `snowygems.rune`

- `gui/dis.yml` → `宝石拆卸台` = `snowygems.dismantle`



> 删掉某个菜单的 `Permission:` 行 = **不限制**（只受 `snowygems.open` 约束），而且不会报错 —— 想按页授权就保留它。



> ⚠️ `/sgem view` 是**管理指令**——那个界面能无限量免费领任何宝石，不要将此权限发给玩家。



> 0.1.1 起 `/sgem embed`、`/sgem dismantle` 已删除，统一改为 `/sgem open 宝石镶嵌台` 与 `/sgem open 宝石拆卸台`；权限语义不变（仍是 `snowygems.embed` / `snowygems.dismantle`），已发出去的权限节点继续有效。

### 常见角色怎么配（LuckPerms 示例）

| 想要的效果                      | 需要的节点 |
|----------------------------| --- |
| 1.普通玩家                     | 什么都不用配（默认全开） |
| 2.只能开礼包 / 喝饮品，不能装也不能拆      | `snowygems.command` + `snowygems.use` |
| 3.能装能拆，不能用宝石               | `snowygems.command` + `snowygems.open` + `snowygems.embed` + `snowygems.dismantle`（想让他看已镶嵌列表再加 `snowygems.inspect`） |
| 4.管理员                      | `snowygems.admin`（OP 默认已有） |
| 5.有 `snowygems.user` 但禁用宝石 | 追加负权限 `-snowygems.use` |
| 6.禁止玩家把宝石拆回来               | 追加负权限 `-snowygems.dismantle` |

> 上表第 2 种种组合**不能给 `snowygems.user`**，因为伞节点里包含 `snowygems.use`。逐个点授的命令形式：

```text
/lp group assembler permission set snowygems.command true
/lp group assembler permission set snowygems.open true
/lp group assembler permission set snowygems.embed true
/lp group assembler permission set snowygems.dismantle true
/lp group assembler permission set snowygems.inspect true
```

只想从普通玩家身上拿掉一个能力，用负权限覆盖即可：

```text
/lp group default permission set snowygems.use false
```

### 注意事项

- 默认 `snowygems.user` 是**冗余**的：8 个玩家节点的 default 本身就是 `TRUE`。伞节点只有在"你把叶子默认值改成 `FALSE`"或"用负权限逐项清理"时才有意义。
- `snowygems.open` 是菜单的总入口：只有 `snowygems.embed` / `snowygems.dismantle` 而没有 `snowygems.open` 时，镶嵌台和拆卸台连门都进不去；右键 `sgem mark` 标记过的方块同样失效，因为它内部是派发 `sgem open`。
- 权限只挡入口，不挡已经生效的效果：装备上已镶的宝石（属性、lore、技能触发器）没有任何权限检查，收回 `snowygems.embed` 不会让玩家手上的装备变弱。
- 菜单权限在打开那一刻判定一次；玩家已在界面里时被撤销权限，进行中的那次镶嵌仍可能完成，而拆卸台每点一次都会复查 `snowygems.dismantle`。
- `/sgem reload` 只重建两个伞节点（先删后加），叶子节点仅在"注册表里还不存在"时补注册 —— 改了叶子的默认值必须重启服务器才生效。
- `config.yml` 的 `UpdateChecker.AdminPermission`（默认 `snowygems.admin`）只决定"管理员上线时要不要私发更新/公告提示"，不授予任何功能；OP 本身也会收到。



---



## 配置长什么样



一颗最简单的宝石：



```yaml

生命宝石:                      # 宝石 ID，全局唯一

  Name: 生命宝石

  Require:

    - ARMOR                   # 只能镶在盔甲上

  Display: "&c&l生命宝石"

  Tips:

    - "&7镶嵌后生命上限 +1"

  Material: REDSTONE

  SuccessTip: "&f生命上限&c&l+1"

  Rewards:

    - Attribute{name=health;operation=0;slot=auto;var=v+1;limit=5}

```



一颗给矛用的新版本宝石：



```yaml

突进宝石:

  Name: 突进宝石

  Require:

    - SPEAR                   # 七种材质的矛全都匹配

  Display: "&e&l突进宝石"

  Tips:

    - "&7为矛的突进等级 +1(上限3级)"

    - "&8需要 1.21.11+ 服务端"

  Material: BREEZE_ROD

  Glow: true

  Rewards:

    - Enchant{name=lunge;limit=3}

```



一颗带成功率、多重奖励的稀有宝石：



```yaml

战锤大师宝石:

  Name: 战锤大师宝石

  Require:

    - MACE

  Display: "&8&l战锤大师宝石"

  Material: HEAVY_CORE

  Glow: true

  Success: 40                  # 40% 成功率

  SuccessTip: "&f锤已成为神话武器"

  FailTip: "&c镶嵌失败了..."

  Rewards:

    - Enchant{name=density;limit=5}

    - Enchant{name=breach;limit=4}

    - Enchant{name=wind_burst;limit=3}

    - Attribute{name=safe_fall;operation=0;slot=hand;var=v+3;limit=9}

```



`gems/` 目录下的文件名会成为 `/sgem view` 里的**分类名**，所以按用途分文件就自动有了分类。



---



## 环境要求



| 项目 | 要求 |
| --- | --- |
| 服务端 | Paper / Spigot 及其衍生（Leaves、Purpur 等），支持 Folia |
| 版本 | **1.21.4 及以上**，含 1.21.5 ~ 1.21.11、26.x |
| Java | 21+ |
| 硬依赖 | **无** |
| 可选 | Vault（金币奖励）、PlayerPoints（点券后端） |



---



## 安装



1. 把 jar 放进 `plugins/`

2. 启动服务器，插件会释放默认配置

3. 跑一次 `/sgem compat` —— 先看清自己这个版本**有什么、没有什么**

4. `/sgem give <你自己> 生命宝石` 拿一颗，配一件盔甲，走一遍 `/sgem open 宝石镶嵌台` 确认工作正常

5. 照抄 `gems/` 里最接近你需求的示例改起来



---



## 文档



完整手册（安装、宝石配置、技能与 BUFF、菜单、拆卸、权限、多版本兼容、排错、汉化、更新日志）：



文档地址：https://www.mc233.fun/index.php?p=wiki&slug=snowygems



---



## 当前状态



**版本 0.1.1**，



欢迎试用与反馈，尤其是这几类信息对我最有帮助：



- 「配了但没效果」的具体配置片段（附 `/sgem compat all` 输出）

- 你希望有但目前没有的奖励函数 / 技能函数

- 高版本（1.21.11 / 26.x）上矛与新属性的实际表现



有问题请带上：服务端实现与版本号、`/sgem compat all` 完整输出、相关配置片段、开对应 debug tag 后完整操作一次的日志。



---



*SnowyGems · by SnowyMC*

> 本文使用 AI 编写

