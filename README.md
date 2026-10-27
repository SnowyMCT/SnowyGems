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

Dismantle:

  Cost:

    Enabled: true

    Type: money       # money 金币 / points 点券 / exp 经验等级

    Amount: 100

  BreakChance: 20     # 拆卸时宝石损坏(不返还)的概率，0 = 必返还

```



拆卸时**真正撤销**这颗宝石加的属性修饰符、把附魔降级或移除——不是只在 lore 上做做样子。费用不够会直接拒绝，**不会动你的装备**。



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



- 玩家能看到的**每一句话**都在 `lang.yml` 里，**134 个文案节点**，改完保存即生效（唯一热重载的文件）

- 全部配置文件带中文注释，示例宝石也是中文

- 支持 `&` 颜色码，以及 `title:` / `actionbar:` / `none:` 三种发送方式前缀


### ✦ 排错不靠猜



宝石类插件最烦的是「镶嵌成功了，但啥也没变」。所以：



- `/sgem compat all` —— 配置自检，**区分**「你这个版本不支持」和「你写错了」两类问题，写错的还会给拼写建议

- `/sgem debug Reward,GemManager` —— **20 个 debug tag** 按范围过滤日志，能直接看到每条奖励为什么没生效

- 自带 27+ 颗示例宝石，从最接近你需求的那颗复制改起


---



## 功能一览



| 模块 | 内容 |

| --- | --- |

| **宝石类型** | 镶嵌类、`PlayerGem`（右键直接生效，如兑换券/药水）、`RandomGem`（开箱抽奖） |

| **奖励函数** | 属性、附魔、Lore 增删改、改名、无限耐久、修复、给点券/金币/经验、给物品、条件奖励等 |

| **适用范围** | 分类关键字（`WEAPON` `ARMOR` `SPEAR` `MACE`…）、后缀 `_SWORD`、前缀 `COPPER_*`、精确物品名、按 lore 匹配 |

| **技能引擎** | 触发标记、条件、概率、延迟、重复、形态切换、从 lore 读数值 |

| **常驻 BUFF** | 装备 lore 带标记即持续生效，可叠加等级 |

| **界面** | 内置镶嵌台 + 用字符网格自定义的工作台菜单（五种槽位类型） |

| **拆卸** | 独立命令与界面，费用（金币/点券/经验）与损坏概率可配 |

| **经济** | 金币走 Vault；点券支持内置账户或 PlayerPoints |

| **版本兼容** | 注册表快照 + 7 个可开关的版本功能模块，默认全自动 |

| **更新提示** | 服务器启动完成后静默检测一次，管理员上线也提示；可完全关闭 |

---

## 指令与权限

主命令 `/sgem`。每个子命令都有独立权限节点，另外提供两个**伞节点**，大多数服务器**不需要动权限插件**。


### 玩家指令（`snowygems.user`，默认所有人可用）


| 指令 | 作用 |

| --- | --- |

| `/sgem embed` | 打开宝石镶嵌台 |

| `/sgem inspect` | 查看手持装备上镶了哪些宝石（只读，不会误触） |

| `/sgem dismantle` | 拆卸宝石（扣费 + 损坏概率） |

| `/sgem use` | 使用手持的兑换券 / 饮品类宝石 |

| `/sgem open <菜单>` | 打开自定义工作台菜单 |

| `/sgem catalog` | 打开宝石图鉴 |



### 管理指令（`snowygems.admin`，默认 OP）



| 指令 | 作用 |

| --- | --- |

| `/sgem view` | 分类浏览全部宝石并领取 |

| `/sgem give <玩家> <宝石ID> [数量]` | 发放宝石 |

| `/sgem compat [all]` | 版本能力报告 + 配置自检 |

| `/sgem skills [关键字]` | 列出全部技能函数与用法 |

| `/sgem triggers` | 列出全部触发标记 |

| `/sgem debug [tag,...]` | 开关调试日志 |

| `/sgem mark ` | 标记所指方块为宝石/符文镶嵌台 |

| `/sgem migrate` | 转换旧文件/配置 |

| `/sgem reload` | 重载配置 |



> ⚠️ `/sgem view` 是**管理指令**——那个界面能无限量免费领任何宝石，不要将此权限发给玩家。



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

4. `/sgem give <你自己> [生命宝石]` 拿一颗，配一件盔甲，走一遍 `/sgem embed` 确认工作正常

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

