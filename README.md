# LastSector

LastSector 是 **Java 21 / Paper 1.21.8** 的多人 Battle Royale 插件。M1–M4 已完成；当前为 **M5 — Combat, Elimination, DeathBox & Solo Outcome**：15 秒归因与助攻、统一淘汰、共享死亡盒、精确经验瓶和 60 秒胜者展示。M6 接续组队、观战与 OfflineBody。

## 构建

配置 JDK 21 的 JAVA_HOME，然后执行：

```powershell
.\gradlew.bat clean test build
```

Gradle 8.14 Wrapper 已附带，首次构建需要网络。Linux/macOS 使用 `./gradlew clean test build`。
安装产物：`build/libs/lastsector-0.1.0-SNAPSHOT.jar`。Paper API 为 compileOnly，JUnit 和测试探针不进入安装 JAR。

## 安装与开局

1. 将 JAR 放入 Paper 1.21.8 的 plugins。首次启动生成 config/rooms/maps/zones/loadouts/loot-tables.yml 和默认两张地图的 map-data/<id>/loot.yml，已有配置不会覆盖。
2. config.yml 的 lobby.world 必须是已加载世界，默认 world；大厅返回点使用世界 spawn。
3. 将**已经关闭且不再被编辑**的 Overworld 模板放入 plugins/LastSector/maps/city、maps/desert，或修改 maps.yml。保留有效 level.dat、WorldGenSettings、region/entities/poi/data/datapacks。
4. 在 playable-area 内提供足够安全地面。模板目录缺失会在准备该局时失败；区域尺寸与圈配置冲突会在启动/reload 时直接拒绝。
5. 玩家 /ls join solo，人数达标自动倒计时。管理员 /ls debug start solo 可绕过 minPlayers，但必须有在线参与者。
6. 副本准备完成后进入 STARTING，按实际在线参与人数选初始 halfSize，注册世界清理、处理已加载区块，规划出生点、完成一次性 Loot、捕获所有原状态并应用 Loadout。全部成功后同一 tick 传送，进入 RUNNING 并开始保护计时。
7. /ls debug end solo 结束比赛，恢复原始背包/经验/状态、取消任务、移除 UI/临时来源记录、返回大厅、卸载并删除副本。Solo 正常淘汰在 tick 末判断胜负，展示 60 秒后自动清理；debug end 在 RUNNING 直接清理、不生成结果，在 ENDING 跳过剩余展示。

源模板只读，不能使用大厅、运行中的世界、链接目录或被外部进程修改的模板。复制保留 seed / WorldGenSettings；必要的新区块由模板设置生成，不预生成整个地图。

## 命令与权限

- lastsector.command（默认所有人）：/ls、/ls help、/ls version。
- lastsector.play（默认所有人）：/ls rooms、/ls join <room>、/ls autojoin、/ls leave。
- lastsector.admin（默认 OP）：/ls reload、/ls debug rooms、/ls debug maps、/ls debug session <room>、/ls debug zone <room>、/ls debug protection <room>、/ls debug start <room>、/ls debug end <room>、/ls debug loot <room>、/ls debug deathboxes <room>、/ls admin loadout edit <room>。

完整命令名 /lastsector；补全按权限提供。join/leave 只允许 WAITING/COUNTDOWN；autojoin 选择人数最多的可加入房间，同人数保持配置顺序。人数不足取消并重置倒计时。活动 Session 或未完成资源清理存在时禁止 reload；失败 reload 保留旧配置。

## M3 配置与旧配置迁移

- rooms.yml：countdown-seconds 默认 30；pvp-protection-seconds 默认配置 60，0 禁用；spawn.min-distance 默认 64，spawn.max-attempts-per-player 默认 200。
- zones.yml：初始人数阈值严格递增，half-size 至少 500，并覆盖房间最大人数。8 人选 500，9–16 人选 750，17–32 人选 1000。
- 默认四个 target-half-size 为 **400 / 250 / 125 / 50**，依次严格减小。第一目标必须小于**所有**初始档位，不做静默 clamp。
- 每个 Room 的每张候选地图，宽和深必须足以容纳该 Room 可达的最大初始 halfSize。诊断包含 Room、Map、Profile、要求尺寸和实际区域。
- config.yml 的 zone-ui 可控制 BossBar、粒子、间隔、视距、间距、高度和单次上限；默认 BossBar/粒子每 5 ticks，视距 64，粒子最多 300/玩家/次。
- 缺失整个 spawn、zone-ui 段时采用默认值；填写后严格校验类型、有限数值和范围。

**升级已有 M1/M2 配置时，必须修正旧的 500 → 700 冲突。** 插件不会覆盖 zones.yml；旧的首目标 700 会明确拒绝加载。可按随 JAR 附带的新四阶段配置迁移。

halfSize 始终表示正方形边长的一半。初始中心在 playable-area 内随机选取，后续中心在上一圈允许的偏移范围内选取，整个下一圈始终包含于上一圈。InitialZone 在整局内不可变，用于本局 Loot 范围筛选，后续缩圈不会重新生成物资。

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

## M4 装备与物资配置

`rooms.yml` 的 `loadout` 引用 `loadouts.yml` 中共享 ID。默认 default 是空装备，solo/squad 共用。管理员在大厅执行 `/ls admin loadout edit solo`，点击自己背包选择复制画笔，左键填目标槽、右键清空；GUI 0–35 为普通槽，36–40 依次为头盔/胸甲/护腿/靴子/副手，45 循环选择初始快捷栏，49 保存，53 取消。真实背包不移动；关闭窗口丢弃草稿。每个 Loadout 同时只允许一个编辑者，保存完成前锁不释放。GUI 保存可以用于活动比赛，但仅影响以后进入 STARTING 的 Session。完整 reload 还要求没有编辑窗口和未完成保存。

`loadouts.yml` 槽位使用 Paper 原生物品字节的 Base64、format=`paper-native`、version=1。通过 GUI 编辑，不要手工拼接 NBT。名称、Lore、附魔、耐久、药水、模型数据、原生组件及 PDC 由 Paper 原生序列化保存。只支持原生 `minecraft:` Loot key；ItemsAdder/Oraxen 等没有实现集成。

`loot-tables.yml` 的每张表有 min-rolls/max-rolls 和 entries（item、weight、min-amount、max-amount）。按权重有放回抽取，数量闭区间随机并按物品最大堆叠拆分。表内权重必须为正、总和不溢出；每批最多 128 rolls、4096 个物品。

每张 maps.yml 地图必须提供 `map-data/<map-id>/loot.yml`。文件必需，默认示例为空；插件不覆盖已有数据。配置示例（请按实际模板修改坐标）：

```yaml
containers:
  - id: courtyard-chest
    x: 12
    y: 64
    z: -8
    loot-table: basic
areas:
  - id: courtyard-ground
    min-x: -20
    max-x: 20
    min-y: 60
    max-y: 80
    min-z: -20
    max-z: 20
    loot-table: basic
    activation-chance: 0.75
    min-spawns: 2
    max-spawns: 6
    max-attempts: 30
```

坐标必须位于 playable-area，ID 不重复，引用表必须存在。Container 点仅在 InitialZone 内激活；边界包含。无效容器记录警告并跳过，不创建箱子。跨区块双箱先清理两个物理半箱，再填共享库存；重复指向同一箱体不会再填。空槽随机分配，溢出丢弃并告警，不丢地面。

Area 每局判定一次激活概率与点数，仅采样其与 InitialZone 的交集。Y 是物品脚部高度范围，采用可安全支撑的最高地表，允许单格净空；不会向地下洞穴全面扫描。每点有限尝试，不预加载整个区域。地图最多 1024 容器/128 Areas，每 Area 最多 256 点、每点 256 尝试；单局最多 10000 地面物品实体。与出生规划共用 120 秒 STARTING 上限，过大的配置可能安全中止，应按地图负载调整。`/ls debug loot <room>` 显示生成状态、点/Area 计数、物品数与清理区块数。

比赛装备应用前保存 storage/armor/offhand、快捷栏、XP、模式、生命/饥饿/饱和、药水、火焰/跌落状态；另隔离末影箱、光标、吸收生命、疲劳和飞行状态。结束丢弃局内所得并恢复原状态；淘汰者立即进入待恢复队列，原版重生到 Lobby 后重试；其余离线/死亡者在结束后进入队列，上线/重生后重试，成功才清除。未恢复不能加入新局。

比赛世界首次加载的区块清除原版容器物品/战利品表、物品实体、经验球与普通生物；保留村民、盔甲架、展示/悬挂实体及矿车（清空带库存实体）。后续自然生物、物品和 XP 正常存在，清理不会重复。昼夜、天气、自然刷怪与方块破坏/放置/爆炸保持原版；仅比赛世界禁止 Nether/End 门、End gateway 传送及门生成，珍珠/紫颂果保留。

M4 实服验证脚本：`node scripts/paper-m4.mjs <stopped-paper-directory> [mineflayer-package-directory]`。测试探针开关 `-Dlastsector.probe.m4=true`，不会关闭自然刷怪/昼夜/天气；站立测试客户端使用探针免伤。正式 JAR 不包含探针。

## 当前边界

- M5 淘汰后通过原版死亡画面/重生回 Lobby，恢复原快照一次，保留 ELIMINATED 至比赛退休；没有 Spectator。
- 活动断线保留 UUID 为 DISCONNECTED，移除 UI、跳过扣血；没有 OfflineBody、重连恢复或自动淘汰（M6）。
- 离线待恢复快照只存在本插件实例内存中，跨正常空闲 reload 保留；插件禁用、崩溃和进程重启后的持久恢复属于 M7。第三方物品 Provider、Team 胜负和永久统计仍未实现。
- 崩溃/强杀、异常生成器停滞、文件锁可能保留带 marker 的目录；不自动扫描删除（M7）。
- 匿名红石/发射器、无来源的 TNT 矿车、第三方直接修改方块或制造 source-less 伤害，以及多来源混合火/岩浆，不能可靠还原玩家来源。见架构文档的具体限制。

[架构](docs/ARCHITECTURE.md) · [路线图](docs/ROADMAP.md) · [验证记录](docs/VERIFICATION.md)

## M5 战斗与死亡规则

有效、未取消的同局玩家伤害进入 session 级 CombatTracker；单调时钟默认窗口 **15 秒，边界包含**。过量伤害按剩余生命截断，公开 API 可识别的吸收消耗计入有效伤害。直接致死玩家优先；环境死亡选择窗口内最近攻击者。排除自己和非本局 UUID；助攻按窗口累计伤害 **≥4 HP 或占玩家总伤害 ≥20%**，排除 killer 并去重。淘汰玩家的历史攻击仍可归因；统计只在本局内存中。

实际 storage、盔甲、副手及光标由原生字节独立保存，包含仍存在的消失诅咒装备；不读取过滤后的原版 drops，不包含末影箱。已在致死攻击中损坏消失的装备不会凭空恢复。原版掉落、经验、itemsToKeep 和死亡消息被接管。

DeathBox 是 **54 格共享库存**，以 BARREL BlockDisplay、Interaction 与 TextDisplay 展示，不放置真实方块。浮字显示死者、原因/击杀者和固定淘汰用时。Y 限制在世界有效高度内。允许左/右键取出与 shift 快速取出；禁止放入、拖入、数字键、换副手、双击收集及 Creative 注入。每次交互/点击复查同局、ALIVE、RUNNING、同世界和默认 6 格距离。空盒保持至清理。

经验保存 **floor(当前总经验点数 / 2)**。从等级和进度重建当前可花费 XP，避免 Bukkit lifetime total 在附魔后陈旧；不是等级除以二。正数生成一瓶 Stored Experience，0 不生成。PDC 保存整数，投掷时复制到实体，ExpBottleEvent 精确设置；普通瓶不改。拒绝错误类型、负值及超范围载荷，不解析 lore。

`teamSize == 1` 才运行 SoloOutcomeResolver；ServerTickEndEvent 统一处理本 tick 淘汰，最后两名同 tick 淘汰判 TIE，两人均为赢家，不同 tick 不拼接平局。结果不可变，只进入 ENDING 一次。默认展示 **60 真实秒**，停止圈、战斗和箱子访问，保留世界和实体，Adventure WINNER/TIE 标题及每 5 秒最多一轮中性烟花（至多 12 轮）；自有烟花以 PDC + registry 保护伤害。到期恢复剩余玩家并接入 M2 清理。

队伍房间保留淘汰/盒子/统计，但不自动产生 Solo 冠军。有 DISCONNECTED 参赛者时也不授予免费 Solo 胜利，需要管理员结束，等待 M6 定义离线淘汰。原版重生前退出的淘汰玩家在同 JVM 的 pending restore 队列中等待登录/重生；不保证进程重启恢复。

已有 config.yml 不覆盖；缺失整个新段采用默认，可手动添加：

```yaml
combat:
  attribution-seconds: 15
  assist:
    min-damage: 4.0
    min-damage-share: 0.20
match:
  winner-showcase-seconds: 60
deathbox:
  interaction-distance: 6.0
```

归因窗口上限 600 秒、助攻占比 0–1、展示 0–3600 秒、距离至多 16 格；非法配置拒绝加载。`/ls debug deathboxes <room>` 显示数量、ID、死者、位置及剩余非空 stack，debug session 包含临时统计和结果。
