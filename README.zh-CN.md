# BattleRoyale

[English](README.md) | [简体中文](README.zh-CN.md)

BattleRoyale 是面向 Paper 的多房间 Battle Royale 插件，提供随机战区、连续缩圈、动态物资、队伍、观战、离线替身、崩溃恢复、排名、经济和外观系统。

> 当前版本：**1.0.0-rc.10**
>
> 目标平台：**Paper 26.2** · **Java 25**
>
> 状态：**发布候选（Release Candidate）**

## 主要功能

- 独立多房间比赛与安全的地图模板副本。
- 随机正方形安全区，末圈持续缩到零；BossBar 用边长平方显示面积，例如 `100²`。
- 开局准备进度条，随机航线的移动飞机平台；每人临时穿上鞘翅，落地立即回收并恢复原胸甲。
- 容器默认 40% 概率抽取 1–3 次，野外补给点分区分散；比赛副本中的贵重储存块改为石头/深板岩，保留矿石与远古残骸。
- 野外补给用彩色粒子环和近距离音效指引，靠近 4 格后才生成物品；未开启时无掉落物/展示实体，持久领取记录防止重复发放。
- 每区块少量地表抽样并缓存，箱子和野外补给按自然区/建筑区选择品质，保持普通附魔上限。
- 小人数开局边长按 400/600/1000/1500 格分档，支持按地图设置多个初始中心；默认四阶段缩圈共 7 分 10 秒，后续圈心仍随机。
- 无坐标的圈心方向导航：下圈内显示中心距离，圈外显示最短进圈距离，另有空投方向箭头；顶部显示阶段总数和下圈面积。
- 空投在下降前至少 60 秒预告固定坐标，使用真正的黄色信标光柱，24 格三维球形范围内的存活参赛者仅获得速度 I，每次刷新持续 100 ticks。
- Solo、Duo、Squad 及可配置队伍人数。
- 容器 LootPoint、地面 LootArea 和原生物品 Loadout。
- 地图箱子、木桶、潜影盒等自动生成物资，持久标记防止重复补货。
- 短时 I 级药水、单星战斗烟花、装备 35% 概率获得单项安全 I–II 级附魔，以及有冷却和局内上限的自然敌怪额外掉落；物资不生成鞘翅。
- 每个空投先保证一件附魔钻石武器/护甲或弓，以及一个不死图腾，再加入随机物资。
- 空投附魔增强，少量装备可获得原版满级兼容组合；增加木石材料、金属锭、煤炭和低权重单颗钻石。
- 比赛世界每 15 秒尝试生成一匹成年驯服带鞍马，每局累计最多 16 匹；获胜烟花扩大为无伤害的多波大球效果。
- 结算逐秒显示自动返回倒计时，支持 `/br leave` 提前返回大厅。
- 伤害归因、助攻、共享死亡盒和观战模式。
- 可攻击的离线替身与断线重连。
- SQLite / MySQL 存储与基于检查点的崩溃恢复。
- Rating、统计以及日榜、周榜、月榜。
- 按客户端语言显示的大厅 GUI、可选一次性建造的空中广场、永久外观与可选经济商店。
- 登录恢复后回大厅，三种房间入口、大厅保护与跌落回拉。
- 大厅实时计分板每个房间仅一行，三房配置共五行；排队时右键红床退出。
- 插件界面跟随客户端语言：`zh` 系列显示中文，其它及未知玩家语言显示英文；菜单、聊天、比赛提示和大厅全息按玩家分别呈现。原版物品名使用 Minecraft 客户端自身的翻译，自定义文字与玩家名保留原样。
- 大厅 Rating TOP 8 展板读取真实玩家统计，约 30 秒刷新；右键下方讲台查看自己的战绩。
- 先确定随机或预设中心的初始战区，再按战区和缓冲范围复制比赛地形。
- CoinsEngine、ExcellentEconomy 和 Vault 适配器。
- 管理员地图编辑、地图校验和受控区块预生成。
- 诊断、有界性能指标和脱敏支持包。

## 环境要求

- **Paper 26.2**。
- **Java 25 或更新版本**，同时满足 Paper 和其他插件的运行要求。

可选经济组合保持 **CoinsEngine 2.7.0 + nightcore 2.15.0 + Vault 1.7.3-b131 + PlaceholderAPI 2.12.3**。rc.9 已在隔离 Paper 26.2 / Java 25 环境验证启动、正常关闭和余额持久化，具体范围见验证记录。CoinsEngine 2.7.0 搭配 nightcore 2.16.6 的启动检查失败，不能随意互换依赖版本。

[CoinsEngine 2.7.0 官方发布页](https://modrinth.com/plugin/excellenteconomy/version/2.7.0)和 [NightCore 2.15.0 官方发布页](https://modrinth.com/plugin/nightcore/version/2.15.0)标注的游戏版本范围均为 1.21.8–1.21.11，在 26.2 启动时仍会输出不支持该版本的警告；上述是本地实测，**不代表上游官方支持 26.2**。

没有经济插件时比赛与免费外观仍可用，付费购买关闭。100 金币初始余额属于服务器部署设置，并非 BattleRoyale 通用默认值。提供者可用诊断不等于真实商店购买或退款验收。依赖组合与边界见[经济集成](docs/ECONOMY.md)，本次测试状态见[验证记录](docs/VERIFICATION.md)。

## 安装

1. 安装 Paper 26.2 和兼容的 Java 运行时。
2. 构建 BattleRoyale，将 `battleroyale-1.0.0-rc.10.jar` 放入 `plugins/`。
3. 首次启动服务器，生成默认配置。
4. 配置大厅、房间、地图、圈规则和存储。
5. 将已保存且未加载的地图模板放入 `plugins/BattleRoyale/maps/`。
6. 重启服务器，执行 `/br admin diagnose`。

没有有效地图模板时插件仍可启动，但房间需要可用地图才能开局。不要将正在加载的服务器世界作为模板。升级前备份配置、地图模板和数据库。

rc.8 全面更名为 BattleRoyale：命令为 `/br` 和 `/battleroyale`，数据目录为 `plugins/BattleRoyale`，权限为 `battleroyale.*`，Java 包名为 `com.npucraft.battleroyale`。不保留旧命令别名，也不会自动迁移旧目录、数据库 schema 或命名空间。旧安装须先备份并离线迁移，不能同时放入新旧两个插件 JAR。

## 快速开始

1. 在 `config.yml` 设置大厅世界。
2. 添加已保存的 Paper 26.2 主世界维度（含 `data/minecraft/world_gen_settings.dat`），或完整的 26.2 存档，并在 `maps.yml` 注册。
3. 在 `rooms.yml` 配置房间、地图池、队伍人数和圈配置。
4. 使用 `/br admin loadout edit <room>` 配置初始装备。
5. 使用 `/br admin map edit <map>` 设置可玩区域、LootPoint、LootArea 和观战点，保存元数据。
6. 执行 `/br admin map validate <map>`；可追加 `--deep` 进行结构检查。
7. 使用 `/br join <room>` 加入房间。三房部署示例使用 60 秒开局倒计时，Solo/Duo/Squad 最低人数为 4/8/16，对应四支满编队；已有配置文件不会自动覆盖，需要 Duo 时须配置该房间。

详见[管理指南](docs/ADMIN.md)、[地图制作](docs/MAPS.md)、[详细玩法配置](docs/GAMEPLAY.md)和[BattleRoyale 部署记录](docs/BATTLEROYALE.md)。

Paper 26.2 的比赛副本位于 `<level-name>/dimensions/battleroyale_game/`，编辑和预生成副本使用 `battleroyale_maintenance/`。旧地图应先在独立 Paper 服务器中转换为新格式，旧的进行中比赛副本不会自动迁移。

## 大厅语言与三种房间

专用大厅可使用已加载的 `battleroyale_lobby` 世界，在 `lobby.yml` 明确启用内置建筑，将地板高度设为 200，并与 `config.yml` 的大厅世界名称保持一致。蓝图包含单人、双人、四人三座屋顶门廊和中央指南台；默认不对已有世界自动施工。首次施工保留原方块备份及世界 UUID 标记，之后重启不会覆盖装修。

玩家登录完成恢复后回大厅，右键讲台或使用指南针加入 `solo`、`duo`、`squad`。右侧计分板每秒更新，每个房间仅一行名称、人数和简短状态或倒计时；加在线人数与操作提示，三房共五行，超过五个房间自动翻页。排队玩家继续留在大厅并受保护，取消排队可右键快捷栏末格的红床或使用 `/br leave`；活动比赛断线重连优先回到原比赛。默认房名、菜单和全息分别按每位玩家的客户端语言显示，房间 ID 与自定义文字保持原样。

三个房间可共用 `Survival-Main` 自然地形模板；部署示例的 X、Z 各为 -10000 至 10000，每局从中随机选择完整落在边界内的初始战区。比赛复制范围为初始圈加四周 512 格缓冲，按完整 region 文件向外取整；地图编辑维护仍复制完整模板。模板保留同一随机种子，不会每局更换种子或自动预生成整张地图。

完整配置与生产检查建议见[配置说明](docs/CONFIGURATION.md)和[玩法说明](docs/GAMEPLAY.md)。上述是可部署方案，不表示任何远端服务器已完成升级或验收。

## 常用命令

```text
/br lobby
/br rooms
/br join <room>
/br autojoin
/br spectate <room>
/br profile
/br leaderboard
/br shop
/br cosmetics
/br admin diagnose
/br admin map list
/br admin map edit <map>
/br admin map validate <map>
```

管理命令默认仅 OP 可用。完整命令与权限见[命令参考](docs/COMMANDS.md)。

## 构建

使用 JDK 25 和项目自带 Gradle Wrapper：

```bash
./gradlew clean test build
```

Windows：

```powershell
.\gradlew.bat clean test build
.\gradlew.bat stressTest
.\gradlew.bat clean check build
```

产物为 `build/libs/battleroyale-1.0.0-rc.10.jar`，旁边生成 `.sha256` 文件。构建 JAR 不提交到 Git。运行依赖版本已锁定；Paper API 固定为 `26.2.build.129-stable`，由服务器提供。

源码基础包名为 `com.npucraft.battleroyale`。MySQL 测试需要隔离测试数据库和 `BATTLEROYALE_MYSQL_TEST_PORT` 环境变量；未设置时明确排除对应测试。测试环境、证据及实际规模见[验证记录](docs/VERIFICATION.md)。

## 恢复与经济安全边界

BattleRoyale 支持基于检查点的崩溃恢复。Minecraft 世界文件与 SQL 存储不属于同一个跨存储 ACID 事务，因此恢复以安全和一致性为优先，不能保证零回滚。详见[恢复机制](docs/RECOVERY.md)。

外部经济插件与 BattleRoyale 数据库无法参与通用的跨插件 ACID 事务。状态不确定的外观购买进入 `MANUAL_REVIEW`，不会自动再次扣款。详见[经济集成](docs/ECONOMY.md)。

## 项目状态与已知限制

`1.0.0-rc.8` 是**发布候选版本**。本次 clean build 通过 606 项单元测试与 8 项压力测试，另有 3 项 Windows 符号链接权限测试跳过；当前真实 Paper 验收及客户端限制见[验证记录](docs/VERIFICATION.md)。M1–M9 和此前候选版本结果保留为历史证据。最终工件摘要与正式部署回执以对应 [GitHub release](https://github.com/NPUcraft/BattleRoyale/releases) 为准，本文不宣称 rc.8 已部署。生产规模的长期运行仍待评估。

- 尚未接入 Party provider、赛季或基于技能的匹配。
- 离线替身是可攻击实体，不是真实玩家皮肤 NPC。
- 部分间接伤害归因受 Bukkit/Paper 可观测事件限制。
- SQL、世界文件和外部经济无法实现全局 ACID。
- 深度地形校验采用有界采样；大型世界保存和卸载可能造成暂停。

完整边界见[验证记录](docs/VERIFICATION.md)和[路线图](docs/ROADMAP.md)。本项目为私有仓库，尚未选择项目许可证。

## 文档

- [管理指南](docs/ADMIN.md)
- [命令与权限](docs/COMMANDS.md)
- [地图制作](docs/MAPS.md)
- [配置说明](docs/CONFIGURATION.md)
- [详细玩法配置](docs/GAMEPLAY.md)
- [崩溃恢复](docs/RECOVERY.md)
- [经济集成](docs/ECONOMY.md)
- [架构](docs/ARCHITECTURE.md)
- [验证记录](docs/VERIFICATION.md)
- [路线图](docs/ROADMAP.md)
- [更新日志](CHANGELOG.md)
