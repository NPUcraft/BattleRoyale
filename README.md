# LastSector

LastSector 是 **Java 21 / Paper 1.21.8** 的多人 Battle Royale 插件。M1–M5 已完成；当前为 **M6 — Teams, Spectators & OfflineBody**：统一队伍、全程友伤保护、队伍胜负、死亡/外部观战、可受伤的离线替身和默认 120 秒比赛重连。下一里程碑为 M7 数据库与进程恢复。

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
6. PREPARING 冻结 roster、均衡分队、保存 Lobby 原快照并应用一次 Loadout；准备期间冻结物品操作。STARTING 按冻结人数选初始 halfSize，清理世界、规划出生点、生成一次性 Loot。全部成功后同 tick 传送在线成员，在各自出生点创建断线成员的替身，进入 RUNNING 并开始保护计时。
7. /ls debug end solo 结束比赛，恢复原始背包/经验/状态、取消任务、移除 UI/临时来源记录、返回大厅、卸载并删除副本。所有模式的正常淘汰在 tick 末按 Team 判断胜负，展示 60 秒后自动清理；debug end 在 RUNNING 直接清理、不生成结果，在 ENDING 跳过剩余展示。

源模板只读，不能使用大厅、运行中的世界、链接目录或被外部进程修改的模板。复制保留 seed / WorldGenSettings；必要的新区块由模板设置生成，不预生成整个地图。

## 命令与权限

- lastsector.command（默认所有人）：/ls、/ls help、/ls version。
- lastsector.play（默认所有人）：/ls rooms、/ls join <room>、/ls autojoin、/ls leave、/ls team、/ls spectate <room>。
- lastsector.admin（默认 OP）：/ls reload、/ls debug rooms、/ls debug maps、/ls debug session <room>、/ls debug zone <room>、/ls debug protection <room>、/ls debug start <room>、/ls debug end <room>、/ls debug loot <room>、/ls debug deathboxes <room>、/ls debug teams <room>、/ls debug offline <room>、/ls admin loadout edit <room>。

完整命令名 /lastsector；补全按权限提供。参赛 join 和未开局成员 leave 只允许 WAITING/COUNTDOWN；观战者可随时 /ls leave 恢复 Lobby，阵亡成员仍保留队伍历史；autojoin 选择人数最多的可加入房间，同人数保持配置顺序。人数不足取消并重置倒计时。活动 Session 或未完成资源清理存在时禁止 reload；失败 reload 保留旧配置。

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
直接修改生命值，绕过护甲、保护附魔、抗性和吸收伤害流程；可扣到 0，不保底 1 HP。卡顿后不会补打多次。在线只处理比赛世界中的 ALIVE；存活 OfflineBody 由同一 Session loop 在同一圈伤脉冲中按替身当前位置应用相同公式。

保护期从全部传送成功、进入 RUNNING 时开始，只拦截同局参与者之间可识别的玩家来源。覆盖近战、玩家投射物（箭、三叉戟、弩/烟花）、有来源的爆炸、TNT 放置/引爆链、玩家点火/蔓延与岩浆/流动、有害喷溅和滞留药水。天然环境和无玩家来源的怪物伤害继续生效。保护到期通知一次；共享来源继续服务整局友伤和战斗归因。队友之间可识别的玩家伤害整局拦截，包含 OfflineBody。详细归因边界见架构文档。

出生候选使用方块中心、水平欧氏间距，无重复位置。地面需完整实体支撑，脚/头通行且无液体、火、细雪、岩浆块、仙人掌、营火、浆果丛、凋零玫瑰、尖滴水石、蛛网或树叶。每局同时仅一项区块请求，每 tick 最多检查一列；不扫描整张地图。全部点位确认后才传送。规划期间断线保留冻结名单和 Team，登记比赛状态并从断线时开始计时，最终在已规划出生点放置替身，不重新分队。

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

- 淘汰后原版重生进入本局 SPECTATOR；保留 Lobby 原快照直到 /ls leave、退出或结束，Team 历史不变。
- 活动断线保存独立比赛快照并创建可攻击的 OfflineBody；到期/死亡统一淘汰，重连从替身恢复。进程崩溃恢复属于 M7。
- 离线待恢复快照只存在本插件实例内存中，跨正常空闲 reload 保留；插件禁用、崩溃和进程重启后的持久恢复属于 M7。第三方物品 Provider、Team 胜负和永久统计仍未实现。
- 崩溃/强杀、异常生成器停滞、文件锁可能保留带 marker 的目录；不自动扫描删除（M7）。
- 匿名红石/发射器、无来源的 TNT 矿车、第三方直接修改方块或制造 source-less 伤害，以及多来源混合火/岩浆，不能可靠还原玩家来源。见架构文档的具体限制。

[架构](docs/ARCHITECTURE.md) · [路线图](docs/ROADMAP.md) · [验证记录](docs/VERIFICATION.md)

## M5 战斗与死亡规则

有效、未取消的同局玩家伤害进入 session 级 CombatTracker；单调时钟默认窗口 **15 秒，边界包含**。过量伤害按剩余生命截断，公开 API 可识别的吸收消耗计入有效伤害。直接致死玩家优先；环境死亡选择窗口内最近攻击者。排除自己和非本局 UUID；助攻按窗口累计伤害 **≥4 HP 或占玩家总伤害 ≥20%**，排除 killer 并去重。淘汰玩家的历史攻击仍可归因；统计只在本局内存中。

实际 storage、盔甲、副手及光标由原生字节独立保存，包含仍存在的消失诅咒装备；不读取过滤后的原版 drops，不包含末影箱。已在致死攻击中损坏消失的装备不会凭空恢复。原版掉落、经验、itemsToKeep 和死亡消息被接管。

DeathBox 是 **54 格共享库存**，以 BARREL BlockDisplay、Interaction 与 TextDisplay 展示，不放置真实方块。浮字显示死者、原因/击杀者和固定淘汰用时。Y 限制在世界有效高度内。允许左/右键取出与 shift 快速取出；禁止放入、拖入、数字键、换副手、双击收集及 Creative 注入。每次交互/点击复查同局、ALIVE、RUNNING、同世界和默认 6 格距离。空盒保持至清理。

经验保存 **floor(当前总经验点数 / 2)**。从等级和进度重建当前可花费 XP，避免 Bukkit lifetime total 在附魔后陈旧；不是等级除以二。正数生成一瓶 Stored Experience，0 不生成。PDC 保存整数，投掷时复制到实体，ExpBottleEvent 精确设置；普通瓶不改。拒绝错误类型、负值及超范围载荷，不解析 lore。

所有模式使用 TeamOutcomeResolver；Solo 是单成员 Team。ServerTickEndEvent 统一处理本 tick 淘汰，最后多个 Team 同 tick 全灭判 TIE，赢家包括这些 Team 的所有固定成员；不同 tick 不拼接平局。结果不可变，只进入 ENDING 一次。默认展示 **60 真实秒**，停止圈、战斗和箱子访问，保留世界和死亡盒、Adventure WINNER/TIE 标题及中性烟花；存活替身在此时退休，不产生死亡盒。到期恢复并接入 M2 清理。

## M6 队伍、观战与离线替身

- PREPARING 将个人 roster 洗牌后 round-robin 分到 `ceil(N / teamSize)` 个 Team，大小相差至多 1 且不超过配置容量。队伍 ID 带 Session 作用域，成员从此固定；Party 输入边界预留，M6 只接受单人输入，不调用第三方 Party。teamSize > maxPlayers 允许配置，单 Team 开局在首个运行 tick 末直接获胜。
- active combatant = ALIVE，或 DISCONNECTED 且拥有存活/待落地的本局替身。淘汰批次结束后剩一个 active Team 即获胜，已死、已回 Lobby、离线队友都列入不可变 winnerIds。离线赢家下次登录收到队伍结果。
- 死亡观战优先存活队友，否则本局其他存活成员，也可自由飞行。只限制 LastSector 注册观众：公共 spectate-start 事件检查目标身份，teleport 事件阻止跨世界。外部 `/ls spectate <room>` 只接受 RUNNING/ENDING 且 allow-external-spectators=true 的房间，不占参赛名额、不分队、不进入胜负。退出/断线恢复原 Lobby 状态，观众不会产生替身。
- 载体采用**关闭 AI 的持久 Villager + 带名字/装备的 marker ArmorStand**，使用 Paper 公共 API；前者承担真实受伤和生命值，后者仅展示。没有 NPC 库、NMS、伪玩家或玩家皮肤保证。它不是完整 Player 模拟（碰撞、盔甲损耗和怪物行为按载体原版规则）。
- 替身保存比赛物品、盔甲、副手、光标、选中格、XP、生命/吸收、药水、食物、火焰/空气/摔落及位置朝向；与赛前 Lobby 快照分离。共享 M3/M5 provenance、CombatTracker 和 EliminationService，死亡/超时只提交一个 DeathBox，无原版散落物和 XP。
- 默认断线 **120 秒单调时间**。重连先隔离旧 playerdata，在截止前从当前替身恢复当前位置、受伤生命值、装备/物品和 XP；成功提交才移除替身，不重新应用 Loadout。恢复失败保留替身权威状态并踢回客户端供重试。已死/超时登录回 Lobby，替身生成失败或失效会安全淘汰，不能永久占用存活名额。
- 一个 Session loop 管理超时、圈伤、替身捕获和怪物辅助，无每替身定时任务。附近 `Monster` 无有效目标时可用公共 `Mob.setTarget` 指向替身；默认 radius=24、interval-ticks=20，每次至多检查 128 个附近实体。不扫描全世界，不改变原版实体活动距离和远距消失规则，也不保证所有怪物类型把载体当成玩家。
- UI：参赛者 Alive / Kills / Zone / 阶段时间 / 圈距；观众 Alive / Teams / Zone / 阶段时间。调试命令显示 Team 成员状态及替身 UUID、生命、位置、剩余时间。

配置 `disconnect.reconnect-seconds` 默认 120，允许 0–3600；`disconnect.mob-aggro.enabled` 默认 true，`radius` 为有限 0–64，`interval-ticks` 为 1–1200。缺整个段落时使用默认值；错误配置拒绝加载。0 秒为立即超时淘汰。

实服复现：`paperProbeJar` 后运行 `scripts/paper-m6-candidate.mjs`、`scripts/paper-m6.mjs`、`scripts/paper-m6-edges.mjs`，参数同 M3–M5 脚本。细节及公开 API fixture 边界见 [验证记录](docs/VERIFICATION.md)。

M7 将处理数据库、进程崩溃恢复和孤儿世界。第三方 Party、永久统计/排名、经济与外观商店尚未实现。匿名/第三方 source-less 伤害、红石责任链、混合火/岩浆来源等仍受 Paper 可观测来源限制，见 [架构](docs/ARCHITECTURE.md)。
