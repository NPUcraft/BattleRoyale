# LastSector

LastSector 是 **Java 21 / Paper 1.21.8** 的多人 Battle Royale 插件项目。M1 Foundation、M2 Room & World 已完成；当前完成 **M3 — Game Start & Zone**：安全随机出生、连续移动的正方形安全区、圈外真实伤害、PvP 保护、BossBar 和局部粒子墙。完整淘汰、胜负和物品流程尚待后续里程碑。

## 构建

配置 JDK 21 的 JAVA_HOME，然后执行：

```powershell
.\gradlew.bat clean test build
```

Gradle 8.14 Wrapper 已附带，首次构建需要网络。Linux/macOS 使用 `./gradlew clean test build`。
安装产物：`build/libs/lastsector-0.1.0-SNAPSHOT.jar`。Paper API 为 compileOnly，JUnit 和测试探针不进入安装 JAR。

## 安装与开局

1. 将 JAR 放入 Paper 1.21.8 的 plugins。首次启动生成四个配置文件，已有配置不会覆盖。
2. config.yml 的 lobby.world 必须是已加载世界，默认 world；大厅返回点使用世界 spawn。
3. 将**已经关闭且不再被编辑**的 Overworld 模板放入 plugins/LastSector/maps/city、maps/desert，或修改 maps.yml。保留有效 level.dat、WorldGenSettings、region/entities/poi/data/datapacks。
4. 在 playable-area 内提供足够安全地面。模板目录缺失会在准备该局时失败；区域尺寸与圈配置冲突会在启动/reload 时直接拒绝。
5. 玩家 /ls join solo，人数达标自动倒计时。管理员 /ls debug start solo 可绕过 minPlayers，但必须有在线参与者。
6. 副本准备完成后进入 STARTING，按实际在线参与人数选初始 halfSize，规划所有出生点、准备必要区块。全部成功后同一 tick 传送，进入 RUNNING 并开始保护计时。
7. /ls debug end solo 结束比赛，取消任务、移除 UI/临时来源记录、返回大厅、卸载并删除副本。M3 不自动判断胜者。

源模板只读，不能使用大厅、运行中的世界、链接目录或被外部进程修改的模板。复制保留 seed / WorldGenSettings；必要的新区块由模板设置生成，不预生成整个地图。

## 命令与权限

- lastsector.command（默认所有人）：/ls、/ls help、/ls version。
- lastsector.play（默认所有人）：/ls rooms、/ls join <room>、/ls autojoin、/ls leave。
- lastsector.admin（默认 OP）：/ls reload、/ls debug rooms、/ls debug maps、/ls debug session <room>、/ls debug zone <room>、/ls debug protection <room>、/ls debug start <room>、/ls debug end <room>。

完整命令名 /lastsector；补全按权限提供。join/leave 只允许 WAITING/COUNTDOWN；autojoin 选择人数最多的可加入房间，同人数保持配置顺序。人数不足取消并重置倒计时。活动 Session 或未完成资源清理存在时禁止 reload；失败 reload 保留旧配置。

## M3 配置与旧配置迁移

- rooms.yml：countdown-seconds 默认 30；pvp-protection-seconds 默认配置 60，0 禁用；spawn.min-distance 默认 64，spawn.max-attempts-per-player 默认 200。
- zones.yml：初始人数阈值严格递增，half-size 至少 500，并覆盖房间最大人数。8 人选 500，9–16 人选 750，17–32 人选 1000。
- 默认四个 target-half-size 为 **400 / 250 / 125 / 50**，依次严格减小。第一目标必须小于**所有**初始档位，不做静默 clamp。
- 每个 Room 的每张候选地图，宽和深必须足以容纳该 Room 可达的最大初始 halfSize。诊断包含 Room、Map、Profile、要求尺寸和实际区域。
- config.yml 的 zone-ui 可控制 BossBar、粒子、间隔、视距、间距、高度和单次上限；默认 BossBar/粒子每 5 ticks，视距 64，粒子最多 300/玩家/次。
- 缺失整个 spawn、zone-ui 段时采用默认值；填写后严格校验类型、有限数值和范围。

**升级已有 M1/M2 配置时，必须修正旧的 500 → 700 冲突。** 插件不会覆盖 zones.yml；旧的首目标 700 会明确拒绝加载。可按随 JAR 附带的新四阶段配置迁移。

halfSize 始终表示正方形边长的一半。初始中心在 playable-area 内随机选取，后续中心在上一圈允许的偏移范围内选取，整个下一圈始终包含于上一圈。InitialZone 在整局内不可变，为以后 Loot 保留。

## 实时规则

ZonePhase 使用 WAITING / SHRINKING / FINAL，与 GameState 分离。中心和 halfSize 每个 server tick 按单调时钟的真实经过时间线性插值；卡顿后按真实进度推进。最后一圈保持 FINAL，并继续使用最后阶段伤害。

圈外距离是点到正方形的最短欧氏距离，边界算圈内。每真实秒最多一次扣血：
`min(baseDamage + outsideDistance * extraDamagePerBlock, maxDamage)`。
直接修改生命值，绕过护甲、保护附魔、抗性和吸收伤害流程；可扣到 0，不保底 1 HP。卡顿后不会补打多次。离线、非 ALIVE、死亡或不在比赛世界的玩家跳过。

保护期从全部传送成功、进入 RUNNING 时开始，只拦截同局参与者之间可识别的玩家来源。覆盖近战、玩家投射物（箭、三叉戟、弩/烟花）、有来源的爆炸、TNT 放置/引爆链、玩家点火/蔓延与岩浆/流动、有害喷溅和滞留药水。天然环境和无玩家来源的怪物伤害继续生效。保护到期通知一次，临时来源表清空。详细归因边界见架构文档。

出生候选使用方块中心、水平欧氏间距，无重复位置。地面需完整实体支撑，脚/头通行且无液体、火、细雪、岩浆块、仙人掌、营火、浆果丛、凋零玫瑰、尖滴水石、蛛网或树叶。每局同时仅一项区块请求，每 tick 最多检查一列；不扫描整张地图。全部点位确认后才传送。规划期间参与者断线会中止该次开局，避免人数档位与实际落地不符。

## 测试

`./gradlew test` 无需启动 Minecraft；报告位于 build/reports/tests/test/index.html。

```sh
./gradlew paperProbeJar
npm install --prefix scripts/integration
node scripts/paper-smoke.mjs "/path/to/stopped-paper-server"
node scripts/paper-m2.mjs "/path/to/stopped-paper-server"
node scripts/paper-m3.mjs "/path/to/stopped-paper-server"
```

输入服务端需有已接受的 eula.txt、paper.jar、libraries、versions、cache，以及可用的平地 world 模板。测试复制到独立 .run 目录，仅绑定 127.0.0.1，不修改源服务端。可选第二个参数指定已有 Mineflayer 的 package.json 目录。

M3 集成测试在独立配置中使用 wait=5 秒、shrink=10 秒、protection=20 秒；生产默认值没有缩短。探针在隔离副本中禁止自然刷怪/回血，并包含可操纵生命值、伤害、位置的测试命令，**仅限测试服**，绝不包含在安装 JAR。日志、消息、数据包、results.json 保存在各运行目录。

## 当前边界

- M3 死亡仍走原版 Minecraft；没有淘汰、胜者、DeathBox、复活/观战管理。死亡时跳过伤害并移除 UI；原版复活后若仍在比赛世界且 UUID 状态仍 ALIVE，会再次受到圈规则影响。
- 活动断线保留 UUID 为 DISCONNECTED，移除 UI、跳过扣血；没有 OfflineBody、重连恢复或自动淘汰（M6）。
- Loot、Loadout、物品隔离与 World Rules 在 M4；不要把 M3 当成完整竞技服。
- 崩溃/强杀、异常生成器停滞、文件锁可能保留带 marker 的目录；不自动扫描删除（M7）。
- 匿名红石/发射器、无来源的 TNT 矿车、第三方直接修改方块或制造 source-less 伤害，以及多来源混合火/岩浆，不能可靠还原玩家来源。见架构文档的具体限制。

[架构](docs/ARCHITECTURE.md) · [路线图](docs/ROADMAP.md) · [验证记录](docs/VERIFICATION.md)


