# Architecture — M4

## M4 composition and transaction boundaries

PluginRuntime 持有跨空闲 reload 的 LoadoutEditor、WorldSanitizer、PlayerIsolation；每次候选配置发布前，MatchContentLoader 校验 loadouts.yml、loot-tables.yml 及每张地图的 map-data/<id>/loot.yml。物品使用 StoredItem 的不可变字符串；Bukkit ItemStack 永不进入共享配置或长期快照。NativeItemSerializer 只调用服务器主线程的 serializeAsBytes/deserializeBytes，没有反射或 NMS。LootItemResolver 是额外物品 Provider 的扩展点。

LoadoutEditor 的 View 以 InventoryHolder 身份识别，UUID 定位管理员。EditorDraft 只有复制画笔和虚拟槽位；InventoryClick/Creative/Drag/Drop/Swap 路径均阻止真实转移。每个 ID 有编辑锁，文件层只有一个在途保存，避免两个不同 Loadout 的全文件覆盖丢更新。主线程校验、序列化整个候选配置；专用线程只写已捕获路径和 YAML 文本，临时文件 force/close 后 ATOMIC_MOVE 替换，没有非原子 fallback；主线程 pump 仅在成功后替换注册表。保存期间关闭 GUI/断线仍保留锁，完成后释放。完整 reload 拒绝活动编辑器或保存。

PaperMatches 在 STARTING 捕获 LoadoutDefinition 引用，生成不可变 InitialZone，按实际 World UUID 注册 sanitizer 并同步清理已加载 chunks。SpawnPreparation 保留 M3 出生区块 ticket 与取消 drain 语义，新 beforeLanding future 等待 PaperLootRuntime。Loot 完成后验证所有玩家仍可用，PlayerIsolation 先捕获全部原状态，再 journal 全部快照，最后应用装备并在同一 tick 传送。任何部分失败沿原有 abort/end 路径恢复原状态。纯事务 Gateway 允许注入 capture/apply/restore 故障测试。

PaperPlayerIsolation 保存原生 item payload、经验、模式、健康/饥饿/药水等，同时隔离末影箱和光标。结束时 PlayerIsolation 把 session 快照转入 UUID pending map，在线恢复并回大厅，成功才删除；离线/死亡/恢复异常保留。PlayerJoin 和原版 respawn 后重试，pending 阻止新 join。该服务不随普通 reload 重建；插件关闭/进程终止的离线恢复未持久化（M7）。不监听 PlayerDeathEvent 修改掉落和经验；M5 接续统一死亡语义。

WorldSanitizer 使用每个 World UUID 的 SanitationLedger，分别记录 blocks/entities 首次完成，失败不标记；ChunkLoad 与 EntitiesLoad 独立处理。准备物资前 ensure 强制获得该候选 chunk 的实体集合，防止先放物资再执行迟到的首次实体清理。方块只枚举 tile entities，Chest.getBlockInventory 清理物理半箱；Lootable 表先置空，随后清 inventory。村民在 Mob 分类删除之前保留；仅当前 session 的 PDC ground marker 可豁免迟到实体检查。没有全图扫描、定期清扫或自然生物事件禁令。

PaperLootRuntime 每个 Session 一次 NOT_STARTED→GENERATING→COMPLETE/FAILED；generate 再次调用返回已有结果。InitialZone 容器筛选、Area 交集/概率/点数在首次调用冻结。Loot 随机源从注入 Random 派生每局实例，避免两个正在运行的生成任务互相改变随机序列。每 tick 最多发出一组必要 chunk 请求或处理一个候选，异步 Future 完成后才在主线程 pin/sanitize/inspect。容器边缘预备相邻 chunk，避免读取双箱时隐式访问未清理半箱；物理双箱以两侧位置去重。Area 只请求被抽中的列所在 chunk，最高安全支撑面使用 M3 Cell 原语而不要求两格净空。所有 Item 标记 session PDC 并调用公开 setUnlimitedLifetime(true)，保持原生拾取/合并行为。

结束先恢复玩家状态，再停止任务；spawn 与 loot 都 drain 后才撤销 sanitizer/world-rules 注册和卸载世界。运行期间首加载清理失败也会中止对应 Session；所有匹配基于注册表真实 UUID，不匹配世界名前缀。WorldRules 独立监听 portal/create，普通 terrain/pearl/chorus/mob/time/weather 保持原版。测试探针的世界名前缀选择只在独立 integration JAR，生产路由不使用该方式。

## 身份和依赖方向

RoomDefinition 是不可变永久配置；GameSession 是一局比赛，不能重置复用。MapTemplate 是永久模板；GameWorld 是带 sessionId、roomId、实际 worldName、runtimePath 和 expectedSeed 的副本身份。所有玩家长期身份均为 UUID，不持有 Bukkit Player / World。

M1 的模型、注册表和 Provider 边界继续使用。M2 最小扩展：RoomDefinition 增加 Duration countdownDuration；GameSession 增加受控状态转换和成员方法；GameWorld / WorldProvider 增加所有权和取消验证所需参数。旧 RoomDefinition 构造器保留 30 秒默认值。M1 原有测试保留。

依赖方向：

- 命令 / Listener → RoomRuntimeService；命令不处理文件，Listener 不实现生命周期。
- RoomRuntimeService → GameSession / SessionManager / MapSelector / GameScheduler / PlayerGateway / WorldProvider / MatchLifecycle。
- PaperScheduler、PaperPlayers、PaperWorlds 实现服务器边界；纯 Java 领域模型不依赖 Bukkit。
- OnDemandWorldProvider → WorldFiles + WorldGateway + 可替换调度器/ExecutorService。
- PluginRuntime 组装适配器并管理 reload / close；LastSectorPlugin 只处理配置默认文件、生命周期、注册。
- FoundationService 仍负责配置及注册表原子发布；MessageService 统一输出消息与日志。
- economy/party/item/rating API 仍为预留接口，无第三方插件集成。

## Room runtime ownership

RoomRuntimeService 以 sessionId 分别持有倒计时任务和操作 token，以 player UUID → session UUID 索引成员。SessionManager 强制每个 room.id 最多一个 Session。空闲 Room 没有 Session，查询显示 WAITING；首次加入才创建唯一 UUID 的 WAITING Session。

Session 持有自己的 RoomDefinition 快照和不可变查询快照。最后一名等待玩家离开时旧 Session 进入 CLEANUP 并退休；下一次加入创建新 UUID。选图只在首次进入 PREPARING 时进行，且必须在 Room mapPool 内，之后不能更改。

autojoin 只考虑 WAITING / COUNTDOWN 且未满员的房间，选择人数最多者，同人数保留配置顺序较前者。任何成员不能同时属于两个房间；非等待态拒绝普通 join / leave。

## Session lifecycle

正常路径：WAITING → COUNTDOWN → PREPARING → STARTING → RUNNING → ENDING → CLEANUP → 从注册表退休。

- COUNTDOWN → WAITING：跌破 minPlayers，取消任务且重置，不暂停；重新达到阈值从完整配置秒数开始。
- WAITING → PREPARING：仅供 debug start，至少一名成员，仍执行所有地图/路径验证。
- WAITING / COUNTDOWN → CLEANUP：空房间退休或准备前失败。
- PREPARING / STARTING → CLEANUP：取消或失败。
- CLEANUP 为终态，不能变回 WAITING；新局用新 Session。
- RUNNING 成员标记 ALIVE，已断线者保持 DISCONNECTED。当前没有淘汰、胜者、队伍分配。

倒计时使用每秒一个可取消任务，关键秒数才发消息。满员不缩短时长。每个房间独立任务，不存在全局 currentGame/currentWorld/countdown。

## async copy → sync load

OnDemandWorldProvider 的两个专用文件工作线程复制模板；完成结果进入受锁保护的队列。唯一 server-thread pump 消费结果，并重新确认：

1. 插件未关闭；
2. Session 仍是注册表中的同一对象/UUID；
3. 状态仍为 PREPARING；
4. 操作 token 仍匹配；
5. 副本路径及 ownership marker 仍有效。

通过后才调用 PaperWorlds.load。复制期间 debug end 使 token 失效，Session 保持 CLEANUP 直到复制结果得到安全处理；不会提前加载或把旧结果绑定到新局。成功但失效的副本只会删除，不会传送玩家。

WorldLoadEvent 可以同步重入取消/禁用，因此 load 返回后再次验证 token/关闭状态。若此时已失效，立即卸载再删除。关闭过程将 ExecutorService 的 shutdown 延迟到正在调用的同步 load 返回，以保证清理任务仍可提交。

## Runtime 路径与 WorldCreator

模板和 runtime 保留 M1 相对于插件数据目录的路径语义。runtime 必须是插件数据目录、Paper world container 的严格子目录。默认路径：

`<world-container>/plugins/LastSector/runtime/ls_<sanitized-room-id>_<完整sessionUUID>`

生成叶目录只含小写 ASCII 字母、数字、下划线和连字符；room 片段限制 24 字符，使用原始 id 而非 displayName，完整 UUID 保证局间唯一。既有目标目录绝不覆盖/合并。

WorldCreator 接收由已校验路径相对 world container 计算的名称，例如 `plugins/LastSector/runtime/ls_solo_<uuid>`，并使用独立 NamespacedKey `lastsector:<uuid>`。不传任意绝对路径、不创建符号链接、不修改 world container。加载后核对 Bukkit world name、实际 folder 和 seed。

这是通过 Paper 公共 [WorldCreator API](https://jd.papermc.io/paper/1.21.8/org/bukkit/WorldCreator.html) 实现的路径关系，并在真实 1.21.8 上验证。实现行为也核对过 [Paper 1.21.8 createWorld 源码](https://github.com/PaperMC/Paper/blob/ver/1.21.8/paper-server/src/main/java/org/bukkit/craftbukkit/CraftServer.java)；源码仅作参考，插件无 CraftBukkit 依赖。

自定义插件数据目录若在 world container 外会明确失败；不使用路径绕过。M2 面向普通 Overworld 模板，不能自动推断第三方 ChunkGenerator 插件的配置/依赖。

## 模板验证与 copy filter

模板必须是配置注册的、插件数据目录内的真实目录，不能与 runtime 或已加载世界重叠，不能带 LastSector runtime marker。复制前完整检查源树，无符号链接、Windows junction/reparse 或 canonical redirect。

level.dat 必须是 gzip 标准 NBT compound，含 Data.WorldGenSettings.seed 和非空 dimensions。自有只读解析器有大小/深度/集合长度上限，不使用 NMS，不重写数据。源目录必须处于关闭状态，复制过程中管理员不应编辑它。

复制保留 level.dat、region、entities、poi、data、datapacks 及其他正常内容。明确排除所有同名：

- session.lock：不继承运行锁；
- uid.dat：由 Paper 为新副本产生独立 UUID；
- playerdata、stats、advancements：不继承模板作者的玩家数据；
- .lastsector-runtime：不可从源继承所有权标记。

level.dat 原样复制，保留原 seed 与 WorldGenSettings。不是先创建随机世界再覆盖 region，也不预生成 chunk。副本加载后检查 seed；集成测试另比较完整 WorldGenSettings。所有目录遍历 NOFOLLOW，复制完成后再次检查副本树。

## Ownership marker 与删除授权

新建唯一空目录后写入 .lastsector-runtime（Java Properties）：sessionId、roomId、mapId、worldName、createdAt。复制中途失败也只能通过同一验证器清理；无法确认标记时保留目录并报告原因。

递归删除只允许生成算法预期的 runtime root **直接子目录**，并验证：

- 路径与 GameWorld 身份、配置 root 和相对 worldName 完全对应；
- 不是 runtime root、world container、插件根目录、模板或任何保护世界，且不与其重叠；
- 所有祖先和树内条目没有 symlink、junction/reparse、canonical redirect；
- 合法 marker 中 sessionId、roomId、mapId、worldName 匹配，createdAt 可解析；
- 已经在 server thread 确认成功卸载；或资源从未交给 Bukkit 加载。

PaperWorlds 按实际 folder 和 worldName 确认世界身份。卸载前把所有在线占用者（包括外部误入玩家）移回大厅，仍有人或 unload 返回 false 就报错并禁止删除。provider 只 release 自己持有的资源，未知描述不能授权卸载/删除。

删除前先做整个树的链接预检，再逐条复验且不跟随链接。marker 最后删除；若最终目录删除失败则恢复 marker。文件锁最多重试 3 次（100/200ms 间隔），只在 worker sleep。拒绝/失败记录 SEVERE/ERROR；不“尽量删除”未知目录，不扫描清理 ls_*。

这些边界防止配置错误和已存在的链接越界；不把管理员或其它进程在检查间恶意替换文件系统目录视为受支持的并发使用方式。Java NIO 在 Windows 没有跨整棵目录树的原子锁；运维须保持模板及 runtime 目录由插件独占管理。

## STARTING：初始圈、出生规划与区块

POSTWORLD enable/reload 先确认大厅存在。大厅返回仍使用 world spawn；M2 的“游戏世界默认 spawn staging”已完全替换。

RoomRuntimeService 最小增加 MatchLifecycle 边界：世界加载成功后 session.starting(world)，委托 start，成功回调再进入 RUNNING 并启动运行循环。end 调用 stop，并等待准备任务 drain 后才 release 世界。旧的 PlayerGateway.stage 已移除。

PaperMatches 在 STARTING 开始时冻结在线、非 DISCONNECTED 的参与名单，按**这份实际名单人数**选 ZoneProfile 的第一个覆盖档位。初始中心均匀采样 playable-area 向内缩 halfSize 后的范围，精确容纳时使用唯一中心。Session.initialZone 只能在 STARTING 赋值一次，之后不可变。

SpawnPlanner 是纯 Java 增量规划器，注入 RandomGenerator，使用方块中心坐标。每名玩家最多配置的尝试次数，按水平欧氏距离检查 minDistance，即使 minDistance=0 也拒绝重复。所有安全点完成前不公开 plan，不传送任何人。

SpawnPreparation 通过 SpawnTerrain 访问世界，每局至多一个未完成区块请求，每 tick 至多检查一个候选列。PaperSpawnTerrain 使用公共 getChunkAtAsync(..., true)，完成后主线程检查高度和脚/头空间；只保留已接受点位的 plugin chunk tickets，拒绝点的 ticket 立即释放。保留 seed / WorldGenSettings，让 Paper 按模板规则生成必要新块；不预生成整个初始区。

SafeSpawnPolicy 要求完整支撑方块、脚和头可通行且不含危险物。Paper 适配器检查边界盒、液体、树叶及火、细雪、仙人掌、岩浆块、营火、浆果丛、凋零玫瑰、尖滴水石、蛛网。使用保守表面策略，不向下无限扫描寻找洞穴。找不到位置就失败回滚，不临时搭平台。

所有候选完成后复核名单仍在线，在同一主线程调用中依次同步 teleport，故落地属于同一个 server tick；每次传送前检查 Session 仍为当前 STARTING。任意失败均由 RoomRuntimeService 将已经移动的玩家也送回大厅。Bukkit 没有多玩家原子传送 API，这里保证“先完整规划、同 tick 尝试、失败全局回滚”，不声称能让多个独立 teleport 原子提交。名单在规划中变化则中止，不偷偷改人数档位。

正常取消停止新请求并等待已发出的 chunk future 完成后释放 tickets 和世界；迟到结果不能调用 ready。准备超过 120 个真实秒报告失败并进入清理，仍等待 Paper 的未完成请求安全收尾。异常第三方生成器若永不返回，CLEANUP 会保留世界等待，而不会继续开局。禁用时取消本地 future/任务、移除 tickets，不再运行插件 continuation；底层生成由 Paper 的世界卸载流程停止。

## ZoneRuntime 与单调时间

ZonePhase（WAITING、SHRINKING、FINAL）独立于 GameState。ZoneRuntime 持有 immutable initial、current、next、阶段索引与阶段起点，注入 RandomGenerator。GameClock 使用 System.nanoTime；创建审计时间仍可用 java.time.Clock，但玩法计时不使用 currentTimeMillis。

每阶段先等待，再线性插值中心 X/Z 和 halfSize；progress 在 0..1 内，完成时直接使用精确 target 对象。下一目标中心偏移最多 current.halfSize-target.halfSize，确保整圈包含。真实时间跨越多个阶段时一次推进到正确阶段，最后进入 FINAL、next=N/A，保留最后一圈和最后阶段伤害。

ZoneProfile 首目标必须小于所有 initial buckets 的最小 halfSize，后续目标严格减小。房间必须被人数阈值覆盖；地图容量只检查该房间实际可达档位的最大 halfSize，不错误要求不可达的大档位。默认 400/250/125/50 已修正旧 500→700 冲突，无 clamp/跳阶段。

## 单一运行循环、真实伤害与 UI

每个运行 Session 只有一个 SessionLoop。每 tick 更新几何，再用独立时间门限/间隔执行工作：DamagePulse 每真实秒最多一次；BossBar 和粒子默认每 5 ticks。卡顿后不累积补打伤害。tick 异常先取消自己的 task，再走同一 Session 回滚；不会停止其他房间。

只有在线、ALIVE、未死亡且位于本局世界的玩家参与伤害和 UI。ZoneDamage 使用距离正方形最近点的欧氏距离；边界含在圈内。距离>0 时 amount=min(base+distance*extra,max)，否则 0。Context 保留 sessionId、stageIndex、outsideDistance、amount 和 ZONE 来源，为后续淘汰入口保留小型上下文。Paper 适配器检查有限生命/上限，直接 setHealth(clamp(health-amount,0,maxHealth))，不调用 Player.damage；可致死，不经过护甲、抗性、吸收及普通 DamageEvent 流程。

PaperZoneUi 按 UUID 持有每位玩家的 Adventure BossBar，不长期保存 Player。文字包括阶段、WAITING/SHRINKING/FINAL、剩余秒数和圈内/圈外距离；进度为该阶段当前 phase 的剩余时长比例，FINAL=1。断线、死亡/离开世界、结束、禁用时移除。

ParticleWall 是纯采样器：先将四条边与玩家 XZ 视距圆裁剪，只采样局部可见线段；默认水平间距 2.5、垂直间距 1.5、高度 playerY-3..playerY+6，每次最多 300 点。只发给对应玩家，不广播，不按全周长扫描，不读取地形；运行成本由视距和 hard cap 限制。高度采用玩家局部范围，不模拟真实地形墙。

## PvP 保护与临时来源

ProtectionWindow 从统一传送成功、RUNNING 的单调时间开始；0 秒禁用，到期发一次通知。ProtectionPolicy 只阻止同一 Session 不同参与者之间的已识别玩家来源，天然/怪物伤害继续生效，无友伤或团队逻辑。

PvPProtectionListener 使用公共 DamageSource causing/direct entity、Projectile.shooter、TNT source、AreaEffectCloud.source，补充 Firework.spawningEntity 和 LightningStrike.causingPlayer。玩家投射物覆盖箭、三叉戟、弩、烟花；有害药水在 PotionSplashEvent 调整对应玩家 intensity，AreaEffectCloudApplyEvent 移除对应玩家。混有有益/有害效果的同一药水会整体阻止作用于受保护对手，因为这些公开事件按目标暴露强度/列表，不能在同一命中中逐种修改。

PvPHazardTracker 每个 Session 单独持有 UUID/方块坐标来源表，仅在保护期填充：玩家 lava bucket→流动；点火→蔓延；TNT 放置/priming→TNT entity；投射物/滞留云。床和重生锚的有效右键交互也记录块来源（床两半），伤害可用 block / block-state 坐标关联。燃烧事件可识别来源时阻止点燃，避免后续无来源 FIRE_TICK。清除熄灭/破坏/替换/取液体位置；到期或结束清空全部数据，不做击杀/助攻历史。

这些边界不是全局免伤：自然 fall、drowning、lava、fire、cactus、suffocation、starvation、lightning 和无玩家来源 mob 仍可伤害玩家。玩家召唤的 channeling lightning 在公开 causingPlayer 存在时属于玩家来源。

已知归因边界：纯红石/发射器无法一般性确定“最后责任玩家”；TNT 矿车、匿名连锁/床锚爆炸若事件既无 entity owner 又无 block/state 坐标，无法识别；第三方 source-less damage、直接方块变更/活塞搬动绕过已监听事件可能丢失或残留来源；多玩家/天然火或岩浆合流只保留传播到该格的来源，不能还原完整因果。保护外来源不补建历史。完整 Combat attribution 在 M5，不使用 NMS 猜测。

## 断线与临时死亡

WAITING/COUNTDOWN 断线移除 roster 并重新判断阈值。活动断线保留 UUID 为 DISCONNECTED，detach UI、停止圈伤；没有 OfflineBody/重连位置恢复（M6）。

M3 可真实死亡，仍使用原版掉落、死亡画面和复活规则，没有 ELIMINATED、胜负判断、DeathBox、死亡观战或经验保留。死者暂时跳过 UI/圈伤；若原版复活到本局世界且状态仍 ALIVE，会继续参与圈规则。这是明确的临时行为，M5 统一处理死亡和淘汰。


## Cleanup、reload 与 disable

debug end 使操作 token 失效，运行态先 ENDING 再 CLEANUP。停止 SessionLoop、移除 BossBar、清空 hazards，在线玩家返回大厅。等待出生准备 drain 后卸载 save=false，异步删除。完成后退休 Session、清除 UUID membership；下一局创建新 id。

删除失败可以继续新局，因为世界名唯一；保留目录与 marker，日志写出实际路径。卸载失败同样释放 Room，但 provider 继续跟踪仍加载的资源，因此 reload 被阻止，disable 时再次尝试安全卸载。M2 不自动扫描/删除遗留目录。

reload 要求 Room 无成员、无 Session（纯空闲通过惰性创建表示）、无文件任务、无已加载副本。FoundationService 验证候选配置和 Paper 适配资源后才发布，新 runtime 替换旧空闲 runtime；错误保留旧快照。

disable 先关闭 MatchLifecycle，停止所有运行/出生任务、移除 UI、tickets 和 hazards，然后取消倒计时/废弃 token并送回大厅，关闭 world completion queue、卸载副本。文件工作线程自行处理已取消复制和删除，不再调用 Bukkit。正常禁用最多等待 30 秒让已提交文件任务结束，避免 Paper 关闭插件类加载器后工作线程才首次加载清理类。WorldLoad/Unload 同步重入 disable 时不能等待当前主线程栈退出，因此跳过该等待并告警；超过等待上限/强杀也可能留下 marker。M7 再处理恢复与 orphan scanner。

默认保留 Paper 自身 autosave 行为；正常结束以 save=false 卸载随即删除的副本。M7 Recovery 实现时必须重新评估周期保存、停服保留及恢复语义。

## Thread model 与后续边界

Session、membership、timer、token、loaded-world registry 仅由 server thread 修改。Player/World、传送、WorldCreator、load/unload 只在 Paper 适配器内且有主线程断言。复制/删除/源树遍历/NBT 校验位于专用 worker；只传递不可变 GameWorld、原子计数和受锁队列。Future 在运行期间由 server-thread pump 完成；关闭后的完成回调不会修改 Session。

经济、Party、RatingCalculator 继续保持接口边界。ItemSerializer 已有 NativeItemSerializer 实现，LootItemResolver 提供原生 minecraft 命名空间。完整 Combat/淘汰（M5）、组队/观战/OfflineBody（M6）、数据库/恢复（M7）及第三方物品 Provider 尚未实现。

公共 API 参考：[异步区块](https://jd.papermc.io/paper/1.21.8/org/bukkit/World.html)、[DamageSource](https://jd.papermc.io/paper/1.21.8/org/bukkit/damage/DamageSource.html)、[喷溅药水](https://jd.papermc.io/paper/1.21.8/org/bukkit/event/entity/PotionSplashEvent.html)、[滞留云](https://jd.papermc.io/paper/1.21.8/org/bukkit/event/entity/AreaEffectCloudApplyEvent.html)。
