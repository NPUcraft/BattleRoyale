# LastSector

[English](README.md) | [简体中文](README.zh-CN.md)

LastSector 是面向 Paper 的多房间 Battle Royale 插件，提供随机战区、连续缩圈、动态物资、队伍、观战、离线替身、崩溃恢复、排名、经济和外观系统。

> 当前版本：**1.0.0-rc.1**
>
> 目标平台：**Paper 1.21.8** · **Java 21**
>
> 状态：**发布候选（Release Candidate）**

## 主要功能

- 独立多房间比赛与安全的地图模板副本。
- 随机正方形安全区、连续缩圈和圈外伤害。
- Solo、Duo、Squad 及可配置队伍人数。
- 容器 LootPoint、地面 LootArea 和原生物品 Loadout。
- 伤害归因、助攻、共享死亡盒和观战模式。
- 可攻击的离线替身与断线重连。
- SQLite / MySQL 存储与基于检查点的崩溃恢复。
- Rating、统计以及日榜、周榜、月榜。
- 大厅 GUI、永久外观与可选经济商店。
- CoinsEngine、ExcellentEconomy 和 Vault 适配器。
- 管理员地图编辑、地图校验和受控区块预生成。
- 诊断、有界性能指标和脱敏支持包。

## 环境要求

- **Paper 1.21.8**。
- **Java 21 或更新版本**，同时满足 Paper 和其他插件的运行要求。

已测试可选经济集成：**CoinsEngine 2.7.0**、**Vault 1.7.3**（需要已注册的经济后端），以及单独测试的 **ExcellentEconomy 2.8.0**。

LastSector 自身始终以 Java 21 为编译目标。已测试的 ExcellentEconomy 2.8.0 发行包需要更新的 Java 运行时，验证环境为 **Java 25**；请同时遵循该插件自身要求。没有经济插件时，比赛与免费外观仍可用，付费购买关闭。详见[经济集成](docs/ECONOMY.md)。

## 安装

1. 安装 Paper 1.21.8 和兼容的 Java 运行时。
2. 构建 LastSector，将 `lastsector-1.0.0-rc.1.jar` 放入 `plugins/`。
3. 首次启动服务器，生成默认配置。
4. 配置大厅、房间、地图、圈规则和存储。
5. 将已保存且未加载的地图模板放入 `plugins/LastSector/maps/`。
6. 重启服务器，执行 `/ls admin diagnose`。

没有有效地图模板时插件仍可启动，但房间需要可用地图才能开局。不要将正在加载的服务器世界作为模板。升级前备份配置、地图模板和数据库。

## 快速开始

1. 在 `config.yml` 设置大厅世界。
2. 添加包含 `level.dat` 的完整世界模板，并在 `maps.yml` 注册。
3. 在 `rooms.yml` 配置房间、地图池、队伍人数和圈配置。
4. 使用 `/ls admin loadout edit <room>` 配置初始装备。
5. 使用 `/ls admin map edit <map>` 设置可玩区域、LootPoint、LootArea 和观战点，保存元数据。
6. 执行 `/ls admin map validate <map>`；可追加 `--deep` 进行结构检查。
7. 使用 `/ls join <room>` 加入房间，达到配置人数后开始倒计时。

详见[管理指南](docs/ADMIN.md)、[地图制作](docs/MAPS.md)和[详细玩法配置](docs/GAMEPLAY.md)。

## 常用命令

```text
/ls rooms
/ls join <room>
/ls autojoin
/ls spectate <room>
/ls profile
/ls leaderboard
/ls shop
/ls cosmetics
/ls admin diagnose
/ls admin map list
/ls admin map edit <map>
/ls admin map validate <map>
```

管理命令默认仅 OP 可用。完整命令与权限见[命令参考](docs/COMMANDS.md)。

## 构建

使用 JDK 21 和项目自带 Gradle Wrapper：

```bash
./gradlew clean test build
```

Windows：

```powershell
.\gradlew.bat clean test build
.\gradlew.bat stressTest
.\gradlew.bat clean check build
```

产物为 `build/libs/lastsector-1.0.0-rc.1.jar`，旁边生成 `.sha256` 文件。构建 JAR 不提交到 Git。运行依赖版本已锁定；Paper API 仍为 provided SNAPSHOT。

源码基础包名为 `com.npucraft.lastsector`。MySQL 测试需要隔离测试数据库和 `LASTSECTOR_MYSQL_TEST_PORT` 环境变量；未设置时明确排除对应测试。测试环境、证据及实际规模见[验证记录](docs/VERIFICATION.md)。

## 恢复与经济安全边界

LastSector 支持基于检查点的崩溃恢复。Minecraft 世界文件与 SQL 存储不属于同一个跨存储 ACID 事务，因此恢复以安全和一致性为优先，不能保证零回滚。详见[恢复机制](docs/RECOVERY.md)。

外部经济插件与 LastSector 数据库无法参与通用的跨插件 ACID 事务。状态不确定的外观购买进入 `MANUAL_REVIEW`，不会自动再次扣款。详见[经济集成](docs/ECONOMY.md)。

## 项目状态与已知限制

`1.0.0-rc.1` 是**发布候选版本**。M1–M9 核心功能已完成自动化、压力、崩溃恢复和真实 Paper 集成测试，生产规模的长期部署仍待评估。

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
