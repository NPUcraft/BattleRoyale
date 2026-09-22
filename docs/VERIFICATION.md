# M7 验证记录

2026-09-21–22，Windows / Java 21.0.8 / Gradle 8.14 / Paper 1.21.8 build 60。当前 M7，下一步 M8。以下先记录 M7 实测，后保留 M6 与更早阶段历史证据；历史限制若与 M7 冲突，以本节为准。

## 自动测试

最终执行 `.\gradlew.bat clean test build`：**BUILD SUCCESSFUL，6 tasks executed**。JUnit XML 汇总 **381 tests，0 failures，0 errors，0 skipped**，保留 M1–M6 的 334 项并新增 47 项；该次构建设置真实 MySQL 测试端口，包含 MySQL contract。生产 JAR 为 15,341,733 bytes，核实含 SQLite/MySQL/Gson、无 probe/JUnit 类。新增真实 SQLite 文件事务/迁移、陈旧 revision 与并发写、租约抢占、非 owner 退休拒绝、整批 originals 回滚、generation 条件删除、命名有界 executor/主线程回调、合并写与退休栅栏、恢复启动时钟暂停、连接失败脱敏、DTO/checksum/canonical roundtrip、提交前不修改玩家与取消/失败补偿、暂停归因和缩圈计时、孤儿一小时边界/重启不重置/加载引用保护、重复 Room/world 分组和配置校验。

MySQL 使用实际 Docker `mysql:8.4`，仅发布 `127.0.0.1` 随机端口，独立 `lastsector_test` 库与账号。设置 `LASTSECTOR_MYSQL_TEST_PORT` 后运行 `MySqlRecoveryContractTest`，测试真实连接、重复迁移、revision、claim、事务和恢复 generation；未设置该变量时 Gradle 排除 mysql tag，不把空运行冒充集成通过。完整 Paper/MySQL 还见下文。

临时 MySQL 环境复现（仅本机、一次性测试凭据；测试结束后停止并移除该容器）：

```powershell
docker run -d --name lastsector-m7-mysql --label lastsector.test=m7 -p 127.0.0.1::3306 -e MYSQL_ROOT_PASSWORD=lastsector-isolated-root -e MYSQL_DATABASE=lastsector_test -e MYSQL_USER=lastsector_test -e MYSQL_PASSWORD=lastsector-isolated-test mysql:8.4
docker port lastsector-m7-mysql 3306
# 将上一步随机端口填入下面两个变量，等待 MySQL ready for connections。
$env:LASTSECTOR_MYSQL_TEST_PORT='<port>'
$env:M7_MYSQL_PORT='<port>'
.\gradlew.bat test build paperProbeJar
node scripts/paper-m7.mjs <stopped-paper-directory> <mineflayer-package-directory>
```

## 真实强杀与恢复：SQLite 和 MySQL

可复现脚本：`scripts/paper-m7.mjs <stopped-paper-directory> <mineflayer-package-directory>`。SQLite 默认；MySQL 设置 `M7_MYSQL_PORT`，脚本连接本次创建的 `lastsector-m7-mysql` 测试容器。脚本只复制源服到 `.run` 并绑定 127.0.0.1。探针 `paperProbeJar` 独立安装，不含在生产 JAR。

- SQLite 全场景目录：`.run/paper-m7-1790006855014`，退出码 0。
- MySQL 全场景目录：`.run/paper-m7-1790006865295`，退出码 0。
- 每个目录保存 `before.json`、`results.json` 和 `console-1.log` 至后续进程日志。已停止的成功测试目录保留供检查。

两个 provider 均真实创建两房间比赛，并以 Node 子进程句柄 `kill('SIGKILL')` 终止自己启动的 Java；没有使用 `/stop` 代替崩溃。在 RUNNING 中准备局内物品、部分取走的 DeathBox、既有离线身体和 mid-shrink 圈，再强杀并原目录重启。验证同一 Session/world/Team、圈不消耗停机时间、已离线身体保留剩余窗口、ALIVE 变完整 120 秒身体、重连背包/光标/XP/最大生命、DeathBox 内容且只有一个可交互载体。另一 Room 同时恢复并独立清理。死者与外部观众恢复原 Lobby 状态（原生物品解码为完整 NBT 比较，避免复合标签序列化顺序误报）。

在 runtime 写入方块、箱内物品和地面物品；测试 fixture 通过一次 `save-all flush` 明确建立世界文件持久基线，然后强杀。重启后保留三类数据、sanitizer ledger 和 Loot COMPLETE；生产检查点没有强制整世界保存，也没有重新 roll Loot。这验证已保存世界与恢复 metadata 的复用，不声称两套存储 ACID。

恢复比赛后继续淘汰敌人决出赢家；在 ENDING 再次强杀。恢复同一 outcome 与剩余 showcase，离线胜者收到 WINNER、玩家原状态恢复，结束后 runtime 世界删除、所有 original 恢复记录确认删除。

第三场比赛再强杀，故意只破坏数据库 payload 而不更新 checksum。重启拒绝该局，将其 ABANDONED；玩家原状态正常恢复，Room 可再加入；世界 marker 变 ORPHANED 并持久带 orphanedAt，默认等待期内保留目录。额外模拟 playerdata 已存 generation、SQL 仍 ORIGINAL/ACTIVE 的确认窗口，强杀重启后登录安全结束冲突会话，原 Lobby 背包不被旧比赛快照覆盖。正常停服后检查没有 ACTIVE/RECOVERING 行。最后把 SQLite 文件配置指向目录（MySQL 改为不可用端口），重启确认插件因 DB 不可用禁用，孤儿 marker 内容和世界目录完全未改变。

强杀时机器人连接的 ECONNRESET 是预期进程终止信号；插件没有事件处理/任务异常。默认一小时保留期由自动测试的固定 Instant 覆盖 3599/3600 秒边界，无需真实等待一小时。

## M1–M6 回归

原有 334 项测试全部保留。M6 边界 Paper 脚本 `.run/paper-m6-edges-1790005838398` 退出码 0：STARTING 断线、两房身体隔离、真实火/岩浆/mob 与圈淘汰、同 tick Team tie、观战跨世界拒绝、carrier 丢失、单 Team 首 tick 结算、禁用清理均通过。M6 主流程 `.run/paper-m6-1790006296317` 退出码 0，实际等待默认 120 秒身体超时、离线胜者通知、失败重连后重试与完整装备恢复均通过。M4 `.run/paper-m4-1790006569154` 退出码 0，涵盖原生物品/编辑器、隔离、Loot、sanitizer、落地失败回滚和空闲 reload 后离线恢复。回归脚本只更新 M7 banner、异步 bootstrap 与 reload gate 等待，保留原业务断言。

## 交付范围与限制

SQLite/MySQL 驱动包含在安装 JAR；没有 NMS、第三方 NPC 或把测试探针打入生产。永久玩家档案/累计 kills/wins、Rating、排行榜、CoinsEngine/Vault、自动备份、第三方 Party/provider、网络服迁移均未实现。M6 已记录的 source-less/混合环境 provenance、身体外观与部分怪物主动攻击限制继续保留。

SQL 恢复 metadata、playerdata、region/entity 文件不是同一事务。最近检查点之后的进度和 autosave 尚未保存的世界变化可能回退；generation 防止常规恢复重入，但不覆盖存储设备损坏或多份备份分别回滚。MySQL 测试是本机真实 MySQL 8.4，不是跨区域延迟/断网压测。未开展大世界、高并发长时间 soak；管理员仍应只允许插件拥有 runtime 目录。

---

# M6 历史验证记录

2026-09-21，Windows / Java 21.0.8 / Gradle 8.14 / Paper 1.21.8。这是 M6 完成时的历史记录；当前阶段见上方 M7。以下 M5/M4/M3 章节保留为当时证据，其临时断线/死亡/队伍行为已由本节覆盖。

## 自动测试与构建

最终执行 `.\gradlew.bat clean test build`，**BUILD SUCCESSFUL**（6 tasks executed）。JUnit XML 汇总 **334 tests，0 failures，0 errors，0 skipped**；保留 M1–M5 297 项并新增 37 项。生产 JAR 不包含 probe/JUnit/NMS/NPC 实现。

新增覆盖：Solo/Duo/Squad 人数容量、ceil 分队和均衡差值、确定性随机和 Session Team UUID；固定成员/死者和观众冠军、离线活体参与胜负、最终 Team 同 tick tie 与跨 tick 不合并；共享 CombatPolicy；外部观众独立 presence 和优先队友目标；120 秒精确截止、零窗口、重连/致死竞争和恢复失败可重试；离线 timeout 使用 15 秒近期来源、单份物品和 XP payload/统计、第二次淘汰拒绝；观众 DeathBox 访问拒绝；保留原快照直到观战结束且只恢复一次；旧配置缺省及 reconnect/radius/interval 下限和上限。

## 真实 Paper：候选先验验证

`scripts/paper-m6-candidate.mjs`，`.run/paper-m6-candidate-1789999500767`：AI-disabled Villager 接受公开 LivingEntity damage、真实箭实体、World.createExplosion、火焰、实际岩浆方块和 Zombie 攻击；equipment API 存在，移除后实体不可查询。zone 候选仅验证 setHealth 路径，独立生产 ZoneDamage 验证见下方。由此选择 invisible Villager 伤害载体 + marker ArmorStand 装备展示，未使用 NMS、NPC 插件或伪玩家。

## 真实 Paper：M6 主流程

`scripts/paper-m6.mjs`，`.run/paper-m6-1790002017329`，PASS：

1. 四人 Duo 确认两队 2/2。同队 melee / arrow / TNT / lava / 有害 splash 拦截，敌队 melee 有效。保护时间设 0，证明友伤策略在保护期之外仍有效。
2. 满四人房间仍允许外部观众进入；不改变 Team/participant 数量。阵亡原版重生进入 runtime SPECTATOR，自动镜头优先存活队友；同房间目标允许、另一房间目标拒绝，自由飞行允许，/ls leave 完整恢复原状态。
3. RUNNING 退出创建带姓名/装备的 body。同队伤害无效，敌队造成的生命损失在重连后保留；原生 carried inventory / 盔甲 / 副手 / cursor / selected slot / 101 XP 精确恢复，Speed 保留。Health Boost 最大生命值 24 在载体和重连玩家保持一致，不重复变成 28。
4. 注入重连 teleport 拒绝：客户端被隔离并踢出，body 留存，DeathBox 数量不增加；随后真实重新登录成功，body 移除，不重新发 Loadout。
5. 真实箭杀死断线替身，统一 DeathBox；死亡后登录只恢复原 Lobby 物品。另一敌人完整等待默认 120 秒，超时淘汰，合计 3 个 DeathBox，没有重复比赛物品恢复。
6. 获胜 Team 的已阵亡且已回 Lobby 队友收到 WINNER；最后阶段仍存活的获胜队友也断线，Outcome 包含他，ENDING 退休其 body 不新增死亡盒；ENDING 重新登录收到 WINNER 和原 Lobby 状态。
7. debug end 跳过展示正确恢复在线/阵亡/外部观众；外部观众退出后登录回 Lobby，无替身；disable 清全部 runtime worlds。

## 真实 Paper：边界与失败路径

`scripts/paper-m6-edges.mjs`，`.run/paper-m6-edges-1790002167656`，PASS：

- 五人 Squad 分成 3/2；在 STARTING 真正断线，冻结名单/Team 保留，其余人完成开局，在规划点存在 body；保护期间敌人不能伤 body；重连拿到原本空的本局 Loadout 状态。
- 两个世界各有独立 body。同队爆炸阻止、敌队爆炸有效；实际火焰和 lava 方块扣血；附近 Zombie 从无目标经生产目标辅助攻击 body。
- 为隔离原版目标竞争，Zombie fixture 使用近处 Creative 观察者维持实体活动，不直接调用 setTarget；其余角色不会成为攻击目标。先前 Survival 观察者场景出现僵尸优先追玩家；无人近处则可停用 AI 或远距 despawn。实现只在无有效目标时辅助，未改变全局怪物激活范围，不声称所有怪物/无人区等同攻击玩家。
- body 被移动到已加载的远处圈外位置后，生产 ZoneDamage 使用该实时位置淘汰，最近玩家伤害被归因，登录恢复 Lobby。fixture 显式 force-load 目的区块，避免将测试变成第三方强制传送至未加载区块的失效检查。
- 控制同一实际 server tick 淘汰最终两队的所有剩余成员，TIE winnerIds=5，包含早先阵亡 Bob。原版 spectate-cause 跨世界传送被拒绝；阵亡观众退出/登录回 Lobby；ENDING 外部观众结束恢复；另一局的 body 不被清掉。
- 外部移除活体 carrier，下一次 session 检查安全淘汰且只生成一个盒，不保留 immortal DISCONNECTED。
- 单 Team 开局在第一个 tick 边界直接结算，不需要先发生死亡，也不生成 DeathBox。
- disable 后所有载体/装备实体、UI 和世界退出生命周期。

## M3–M5 回归

- M3：`.run/paper-m3-1790002094058` PASS，安全出生/同 tick 落地、模板 WorldGenSettings、保护来源、连续圈/FINAL 真伤、BossBar/particles、多房间、拒绝落地回滚、准备取消和 disable。
- M4：`.run/paper-m4-1790002202353` PASS，原生物品、GUI/原子保存/锁、Loadout、隔离恢复、清理和 Loot、Portal、idle reload pending restore、回滚和 disable。
- M5：`.run/paper-m5-1790002336495` PASS，42-stack 真实共享 GUI、XP101→50、交互限制/爆炸保护、kill/assist、完整默认 60 秒展示、箭/落摔归因、Solo tie、死亡画面退出登录和 cleanup。
- M5 edges：`.run/paper-m5-edges-1790001688519` PASS，实际客户端投掷 PDC 经验瓶精确释放 347 XP，以及真实圈伤致死/最近来源/唯一淘汰。

回归保留原有效断言。M6 语义相关 fixture 更新：需要继续 RUNNING 的独立房间放入至少两个 Team（单 Team 现在立即获胜）；M5 死亡者先进入 spectator，再 `/ls leave` 校验原背包恢复；版本 banner 更新为 M6。

## 证据与测试边界

脚本复制已停止 Paper 目录到各自 `.run`，只绑定 127.0.0.1；不触碰已有运行服务器。命令 `node scripts/paper-m6.mjs <stopped-paper-directory> <mineflayer-package-directory>`，candidate/edges/M3/M4/M5 参数相同。每个目录保留 console.log、results.json 和消息。测试探针单独 paperProbeJar，M3/M5/M6 世界 fixture 和伤害注入不打进生产包。

真实 socket 客户端执行加入/退出/重连、GUI 和收到标题；箭/火/lava/Zombie 使用真实实体或方块。melee/爆炸 owner 和药水目标边界部分用公开事件/DamageSource 注入；spectate-start 检查是公开事件调用而非完整原版 spectator 菜单点击；跨世界传送使用真实 public teleport(SPECTATE)。同 tick tie 是同步公开 damage 调用。装备/皮肤并未由人工 Minecraft 图形客户端视觉验收。

失败证据保留：最初 ArmorStand dropChance 不支持导致创建失败，已限制为 Mob 并验证安全淘汰；最初远处未加载区块测试触发 body failure，改为显式加载以隔离圈伤；Zombie 测试曾受到其他在线目标/远距活动规则干扰，改成上述受控 fixture。没有将失败轮次标为通过。

## 当前限制

进程 crash/restart 恢复属于 M7；仅同 JVM 内的快照/结果通知。无第三方 Party 集成、数据库、永久 stats/rating、经济或外观系统。载体不是 Player，没有真实玩家皮肤或完整玩家物理/饥饿模拟；Monster 辅助受原版活动距离、目标选择和类型接口限制。匿名爆炸、第三方 source-less damage、复杂火/lava 合流等 provenance 边界沿用 M3/M5；不能从公共事件不存在的数据猜测责任人。

---

# M5 历史验证记录

## 构建与自动测试

2026-09-21，Java 21.0.8 / Gradle Wrapper 8.14 / Windows，执行 `.\gradlew.bat clean test build`：**BUILD SUCCESSFUL；297 tests，0 failures，0 errors，0 skipped**。保留原 M1–M4 的 236 项，新增 61 项。报告见 `build/reports/tests/test/index.html`。生产安装包 `build/libs/lastsector-0.1.0-SNAPSHOT.jar`；独立 paperProbeJar 不进入生产包。

新增测试覆盖单调时间 15 秒包含边界、直接致死优先/环境最近来源、助攻 OR 阈值/窗口分母、自伤/跨局/无效伤害、过量伤害与吸收上限；XP 等级边界与整数溢出；唯一淘汰提交和展示失败不重复 payload；不可变结果/物品数据、同 tick 最后两人平局与跨 tick 不合并；队伍房间不套 Solo、DISCONNECTED 不产生免费胜利；访问距离/世界/身份/状态；60 秒真实时间、效果上限、取消和异常；defer→respawn→结束不重复恢复、ENDING debug end；旧配置缺省及新配置范围。

## M5 真实 Paper 1.21.8

主验证 `.run/paper-m5-1789802874614/` 完整通过，使用五个 Mineflayer 1.21.8 协议客户端及只含 public API 的独立探针；安装服务器为 Paper 1.21.8-60-main@29c8822。通过项：

1. 近战实际 damage/death 事件，三人累计伤害分配 killer 与 assist；原版 drops=0、XP=0、keepInventory=false，淘汰后原版 respawn 回 Lobby 并恢复 M4 原快照。
2. 完整 36 storage +4 armor +offhand +cursor 的 42 个 stack，含消失诅咒皮甲，逐项原生物品内容校验；允许实际战斗造成的耐久变化。末影箱不进入 payload。101 当前 XP 生成一瓶 50 XP，真实客户端投掷后 ExpBottleEvent=50；普通瓶仍是原版 3–11 XP。347 XP 原生字节/PDC roundtrip、错误类型/负值/零/过大载荷拒绝。
3. 两个客户端实际右键 Interaction，打开同一个 54 格 Inventory，正常点击与 shift 取出、另一 viewer 同步，底部 shift 存入失败。数字键、换副手、双击、中键、丢弃、Creative 和 Drag 由公开事件注入验证拒绝，未声称每种真人键盘操作都做了端到端测试。
4. 所有物品被两个客户端取空后，三个展示实体仍存在；实际 `World.createExplosion` 在不破坏地形的测试设置下未销毁实体。淘汰玩家和跨房间访问被策略拒绝。
5. Solo 剩一人进入 ENDING，客户端收到 WINNER；保留世界/盒子，环境伤害不扣血，完整默认 60 秒后自动恢复赢家并清理。另一 teamSize=4 房间剩一人仍 RUNNING，死亡盒分别归属各房间。
6. 真实 Arrow 实体命中致死；近战→坠落在窗口内记 killer，等待超过 15 秒后坠落不记 killer。debug end 可跳过 ENDING。
7. 通过一条探针命令在**同一真实服务端 tick 内同步调用两次公开伤害 API**杀死最后两人，真实 PlayerDeathEvent + ServerTickEndEvent 得到双赢家 TIE 和客户端标题；这是自动注入场景，不是真人自然同时死亡演练。
8. 真实自有烟花爆炸事件触发时，以公开 damage event 对不受赢家保护的 Lobby 玩家注入伤害，确认 PDC + registry 路径取消；并未把这种验证表述成真人受到烟花物理爆炸。
9. 禁用客户端自动复活，死在死亡画面后退出；清理该局，再登录/原版 respawn，同 JVM pending 快照恢复成功。disable 清理仍运行的队伍房间并恢复最后玩家，源模板 level.dat SHA-256 不变。

主验证结果、服务端日志和客户端消息保存在运行目录的 results.json、console.log、messages.json。早期夹具失败保留：超大致死伤害击碎皮甲，以及探针给非耐久物品写入 Damage=0 导致比较失败；修正夹具后完整通过，不将失败运行计入成功结果。

补充验证 `.run/paper-m5-edges-1789996249728/` 完整通过：真实客户端投掷 PDC 经验瓶，ExpBottleEvent 精确为 **347 XP**；玩家先受近战再被实际 M3 圈伤 setHealth 致死，killfeed 为 Zone、最近攻击者获 kill，随后恢复与清理成功。前一轮仅因脚本区分 `Zone`/`zone` 大小写而失败，修正断言后重跑通过。

## M3 / M4 当前代码回归

- `.run/paper-m3-1789996016289/` 完整通过：保护期近战/箭/TNT、药水/火/岩浆来源、自然伤害、到期恢复、连续缩圈、真实圈伤穿透护甲/抗性、UI、独立房间及取消/回滚/disable。
- `.run/paper-m4-1789996021939/` 完整通过：原生序列化、装备 GUI、共享锁、完整状态隔离、首次世界清理和一次性 Loot、传送门规则、离线快照跨 idle reload 恢复、准备失败回滚和 disable。
- M3 的早期运行因 debug session 新增玩家状态导致旧脚本误把玩家 WAITING 当房间 WAITING，已改成明确解析 session 状态后重跑。有效断言未删除。回归包含预期的拒绝落地故障注入日志，不宣称全日志零 ERROR。

## 复现与边界

先运行 `.\gradlew.bat build paperProbeJar`，再执行 `node scripts/paper-m5.mjs <已停止的Paper目录> <含mineflayer依赖的目录>`；补充用 `scripts/paper-m5-edges.mjs`。M3/M4 脚本用相同参数。每次复制到独立 `.run` 目录，仅绑定 loopback，原模板只读；fixture 在 M3/M5 运行副本中使用和平难度/关闭刷怪与自然回血，避免静止测试客户端随机死亡，生产插件不修改这些规则。

M6：Team winner、Spectator、OfflineBody/比赛内重连。M7：离线快照持久化、crash recovery。永久 stats/rating、经济、第三方物品 Provider 未实现。DISCONNECTED contender 保守阻止自动 Solo 结算，需 debug end。

公开 API 无法完整还原匿名红石/发射器、无 owner/坐标的 TNT 矿车/床锚连锁、多来源混合火/岩浆、第三方 source-less damage 或绕过事件的方块移动；无可用 causing entity 的持续毒/凋零也有来源缺口。第三方插件在之后事件优先级取消死亡或重新修改掉落未做兼容保证。来源表/伤害历史有硬上限，极端溢出逐出旧记录。大图压力、完整客户端操作组合、所有火/岩浆/虚空视觉位置与第三方战斗插件未穷举。

---

# M4 历史验证记录

## 构建与自动测试

Java 21.0.8 / Paper 1.21.8-60-main@29c8822 / Gradle Wrapper 8.14 / Windows。指定命令 `.\gradlew.bat clean test build`，BUILD SUCCESSFUL。**236 tests，0 failures，0 errors，0 skipped**；保留 M1–M3 原有 191 tests，新增 45 项。额外 `paperProbeJar` 构建成功；生产 JAR 不含探针、Paper API、JUnit 或 Lombok。

新增覆盖：不可变 Loadout/草稿、显式装备槽位与控制按钮、格式/版本/Base64/槽位错误、原子替换失败保留原文件；严格地图元数据/物品引用与配置类型；加权可重复随机、数量范围/拆堆、零次抽取、权重溢出与概率端点、Area 交集；首次区块/实体分别记账、失败重试/重入与不同实例隔离；捕获全员后再应用、部分失败回滚、重复应用、不同 Session 隔离、离线/失败恢复保留及禁止覆盖；Loot future 成功/失败控制落地；原状态恢复先于大厅传送和世界释放。

配置单元测试注入纯物品解析器；Paper 原生注册表及物品元数据必须在实服测试，不用伪造 Bukkit 注册表掩盖服务器依赖。

## 真实 Paper M4

最终完整运行：`.run/paper-m4-1789797914908/`。可复现脚本 `scripts/paper-m4.mjs`，独立源模板 `.run/paper-smoke-1789750716466`，三个 Mineflayer 客户端；仅绑定 127.0.0.1。探针用公开 API 在 WorldLoad、生产 sanitizer 注册前播种旧容器和实体，随后执行真实生产清理。**这是新副本加载时的测试夹具，不声称这些实体来自原始模板的磁盘 entity 文件。** 原模板 level.dat 的 SHA-256 在测试前后相同。

M4 测试保留自然刷怪、昼夜、天气规则，使用 NORMAL 难度；探针给静止客户端免伤，避免自然史莱姆打断状态断言。生产插件没有该测试免伤逻辑。管理员 Alice/Carol 为 OP，Bob 为普通玩家；探针 JAR 不进入安装包。

实际通过：

1. Paper 原生 sword/potion 字节回环：名称、Lore、附魔、耐久、自定义模型、PDC、基础药水与自定义药效等价；损坏字节明确拒绝。
2. 实际打开 GUI，从管理员背包复制画笔、填槽、保存、原子文件落盘；原背包、盔甲、副手、快捷栏、经验未改变。共享 ID 第二编辑者拒绝，普通玩家无权限。真实 shift-click 被拦截；数字键/中键/双击/丢弃/副手切换、Creative 与跨区域 Drag 通过公开事件注入验证取消，**不是对每种客户端输入的全套端到端物理演练**。
3. 两房间三玩家获取同一保存的装备，原物品/末影箱隔离；等级/总经验归零，SURVIVAL、满生命/食物、清除原速度药效。结束后恢复原物品、模式、选择槽、XP、12 HP/14 食物/3 饱和与原速度药效。
4. 手工箱物品与原版 LootTable 清除、未配置 barrel 清空；旧 Item/XP orb/cow/zombie 消失，Villager、ArmorStand、TextDisplay、ItemFrame、chest minecart 保留，矿车库存清空。自然刷怪/昼夜/天气 gamerule 保持启用。Painting 保留分支基于非 Mob 类型实现，未单独放置绘画做客户端观察。
5. InitialZone 内箱子有物资，圈外点不激活，无效 AIR 点明确警告跳过；跨区块双箱两条配置只填一次。Area 的 1/0 概率分支、两个生成点、实际物品数量及公开 `isUnlimitedLifetime` 检查通过。检查前加载对应实体区块，允许原版物品合并，避免把未加载实体误判为消失。
6. 重放 ChunkLoad/EntitiesLoad 不清新物品/新牛，不补已清空的箱子；另实际请求卸载并重载独立 chunk，等待 Paper 临时 request ticket 到期后确认新实体仍在。破坏双箱后产生原版 bread 掉落。
7. 用公开事件触发 Nether player/entity portal 和 PortalCreate：仅比赛世界取消，大厅保持可用；珍珠/紫颂果事件不取消。未声称真人穿过全部 Nether/End 传送门的端到端验证。
8. 结束 solo 恢复在线玩家并丢弃获得的钻石/XP；squad 继续运行。修改共享 Loadout 清空装备后，已运行的 squad 仍保持原装备，下一次 STARTING 使用空装备。
9. Bob 对局中退出；结束两个房间并完成整个插件配置 reload 后，Bob 登录恢复原物品、经验/状态并回大厅。快照没有随旧 Session 或空闲 runtime 销毁。
10. 人为取消安全落地传送，应用装备后的原状态恢复，世界删除；正常 disable 恢复在线玩家后删除剩余副本。启动与禁用阶段无 ERROR。整份日志存在**预期的故障注入 ERROR**（Safe spawn teleport rejected），不是“全日志零错误”。

M4 `results.json`、`console.log`、`messages.json` 与 `logs/latest.log` 保存在该运行目录。早期失败运行保留用于定位测试夹具问题（空槽 AIR/null、未加载实体、Paper 临时区块 ticket、和平难度自动回血）；它们不计入最终通过结果。

## M3 当前改动回归

`.run/paper-m3-1789797660468/` 完整通过：两个房间、同 tick 安全落地、WorldGenSettings、保护内/外伤害与药水/点火/岩浆来源、WAITING→SHRINKING→FINAL、真实圈伤穿透护甲/抗性、BossBar/particle 数据包、单局结束、落地失败回滚、取消后的迟到结果、disable 清理。该回归发生在最终光标保护调整之前；最终 M4 运行覆盖更新后的装备恢复与大厅返回路径。M1/M2 自动测试持续全过，历史实服记录保留在下方，不把旧实服结果重新标成当前代码运行。

## M4 当时边界（已由上方 M5 说明更新）

- 死亡仍为原版掉落/XP/复活；M5 实现 DeathBox、淘汰、胜者和统一死亡流程。结束时已死亡玩家的原快照等待原版重生重试；若其他插件拒绝返回或死者占用世界，沿 M2 安全卸载拒绝路径保留世界，不能强制删除。
- OfflineBody 与比赛中重连恢复属于 M6；M4 只实现结束后原状态待恢复。
- 快照只在当前插件实例内存中。正常 idle reload 保留；禁用/崩溃/强杀/进程重启后的离线库存恢复属于 M7，不作持久保证。
- 第三方自定义物品 Provider、跨插件经济/物品语义尚未集成；原生序列化能保留 payload，不等于支持第三方玩法。
- Paper 1.21.8 原生物品字节与 Item.setUnlimitedLifetime 满足本阶段需求，没有发现需要 NMS 的限制；只在该 Paper 版本实测，不保证原生数据格式向旧 Minecraft 版本降级兼容。物品仍能正常拾取、合并、受环境破坏。
- 地面 Loot 采用最高安全地表，不搜索全部洞穴；大图压力、全部 GUI 客户端组合、第三方生成器/战斗/权限插件兼容未穷举。极端不返回的区块生成 Future 保持 CLEANUP 等待 drain，沿用 M3 边界。

---
# M3 历史验证记录（以下保留原里程碑证据）

## 环境、构建与产物

- Java：Oracle JDK 21.0.8，Windows 11 amd64。
- Paper：1.21.8-60-main@29c8822，API 1.21.8-R0.1-SNAPSHOT。
- Gradle：Wrapper 8.14，Kotlin DSL。
- 最终指定构建：`.\gradlew.bat clean test build`，BUILD SUCCESSFUL。
- 额外完整构建：`.\gradlew.bat clean test build paperProbeJar --warning-mode all`。
- 结果：**BUILD SUCCESSFUL；191 tests，0 failures，0 errors，0 skipped**，无弃用警告。相对 M2 的 123 tests 新增 68 项；保留原有有效覆盖，更新了已被 M3 替换的 staging/旧圈配置断言。

安装产物：build/libs/lastsector-0.1.0-SNAPSHOT.jar。检查确认包含 ZoneRuntime、SpawnPreparation、展开后的 plugin.yml；不包含 test probe、Paper API、JUnit、Lombok。paperProbeJar 是独立测试产物，不能部署到正式服。

## 自动测试

- 配置 53 项：旧配置校验/原子发布、首目标必须小于全部初始档位、严格递减、空阶段、非法时间/伤害/阈值、人数覆盖、Room/Map/Profile 容量诊断、不可达大档位不误拒绝、spawn/UI 数值和无数据 Particle 类型。
- ZoneRuntime 19 项，原有 Zone 22 项：实际人数 8/9/16/17 分档；随机中心可重复且包含；精确容纳/区域过小；数千次内包含；偏移极值；progress 0/.5/1 及超界；FakeClock 等待、连续收缩、跨阶段、零等待与 FINAL；侧边/角落距离、伤害上限、零伤害、可致死生命扣除、延迟后不爆发补打。
- SpawnPlanner 5 项：初始圈内、可重复、间距、恰好等于最小间距、不同高度只比较 XZ、不可能条件的有限尝试、minDistance=0 仍不重复、无安全地面不能发布部分 plan。
- SpawnPreparation 8 项：等待所有异步区块才单批传送、同一 tick 最多一个候选处理、取消先 drain 后释放、chunk failure、失败一次、有限尝试失败、传送失败、禁用/迟到结果无副作用、120 秒准备超时、危险支撑/脚/头策略。
- Protection 3 项：0 秒关闭、恰好到期、同局参与者/跨局/非成员/无来源环境/自身伤害策略、块流动/实体/燃烧来源和清空隔离。
- ParticleWall 3 项：halfSize=500/5000/50000 时局部范围、边角两条边、Y 范围和每次最多 300 点，远离边界零采样。
- MatchLifecycle 6 项：STARTING 不提前 RUNNING、取消后迟到 ready 无效、初始圈/异步出生失败、运行异常回滚、禁用阻止迟到成功、单局循环异常不停止另一局。
- 保留并扩展 M1/M2：RoomRuntime 23、WorldProvider 15、WorldFiles 17、GameSession 5、MapSelector 2、PlayableArea 3、RoomDefinition 5、Registry 2。覆盖成员、倒计时、世界所有权、NBT/seed、只读模板、路径/链接安全、重入和关闭；新增文件线程 shutdown drain 与 InitialZone 一次赋值。

纯数学、时钟、调度器和边界均可无 Minecraft 测试。文件链接测试在 Windows 权限不足时采用 NTFS junction，没有跳过。Bukkit 方块/伤害/客户端行为以真实服务端验证补充，不用自制物理模拟冒充 Paper。

## 真实 Paper M3

脚本：scripts/paper-m3.mjs；测试探针仅使用公共 API。三名非 OP Mineflayer 客户端 LSAlice / LSBob / LSCarol，管理命令通过控制台执行。

最终完整运行目录：`.run/paper-m3-1789793488180/`（此前完整通过记录保留在 `.run/paper-m3-1789792621863/`）。独立测试配置：房间人数上限 8、初始 halfSize=500、wait=5 秒、shrink=10 秒、保护 20 秒；模板 playable-area ±600。生产默认配置没有改短。测试探针在副本加载时关闭自然刷怪/自然回血并设置和平难度；怪物来源测试仅在该次同步调用临时恢复 NORMAL。此规则只在测试 JAR 内，防止模板保存的难度/史莱姆干扰站立客户端的伤害测量。

实际通过：

1. 自然倒计时和 debug start，两个 Session 同时 RUNNING，世界身份分离。
2. 实际参与人数选 halfSize=500；两名玩家都在初始圈内，floor=GRASS_BLOCK、feet/head=AIR，XZ 间距至少 64。
3. PlayerTeleportEvent 的 server tick 记录一致，确认同 tick 落地；点位不再使用默认 world spawn。
4. 两个副本 level.dat 的完整 WorldGenSettings 与模板一致，包含 seed/generator 设置。
5. 保护期的 Paper damage pipeline：近战玩家来源、箭矢 DamageSource、玩家 TNT 爆炸来源扣血被阻止；自然 FALL、怪物和自然 LAVA 正常扣血。
6. 在真实 Paper 上用探针发出 PotionSplashEvent、AreaEffectCloudApplyEvent、BlockIgnite/Combust、Bucket/BlockFromTo 事件，验证对应目标 intensity/名单/点燃/流动来源防护。**这些是服务端公开事件注入，不是客户端实际投掷所有药水、放置全部方块的端到端演练。**
7. 保护结束通知只出现一次；到期后玩家来源扣除 4 HP，药水/云目标不再过滤。
8. WAITING→SHRINKING，连续两次查询 halfSize 减小，当前圈仍包含于初始圈；InitialZone 全程不变；最终 FINAL halfSize=50、next=N/A。
9. FINAL 圈内不扣血；圈外 10 格以末阶段公式扣 4.3 HP，穿全套钻石 Protection IV、Resistance V 仍从 20 降到 15.7；圈外 500 格扣 19 HP。
10. 客户端收到 boss_bar 和 world_particles 数据包，结束收到移除 BossBar 包。**没有声称人工目视效果验收**；硬上限和局部复杂度由纯采样测试证明。
11. end solo 返回大厅、删除该世界、移除 UI；squad 保持运行。
12. 探针取消一次落地 teleport，STARTING 正确回滚，玩家在大厅，另一房间继续运行。日志中的该次 IllegalStateException/ERROR 是预期故障注入。
13. 连续 debug start/end 无迟到开局或遗留世界；disable 清理剩余会话，无关闭段 ERROR/Exception。
14. 模板 city/level.dat SHA-256 前后一致。

运行目录包含 console.log、bot-messages.json、packets.json、results.json。客户端为保持测试存活反复传送到圈中心；高速位置确认可产生 moved-too-quickly 测试警告，不作为生产性能结论。没有大型地图/大量房间压力测试。

首轮探针曾错误使用 damage(amount, projectile)，未构造真实投射物 DamageSource，已改为公共 DamageSource.Builder。另修复正常停服时文件清理晚于插件类加载器关闭：正常 disable 最多等待 30 秒 drain；后续关闭无 zip-file-closed 错误。

## M1 / M2 当前代码回归

- M1 管理命令与配置：`.run/paper-smoke-1789792800867/`。启动、help/version/alias/debug、无效 reload 保留旧快照、有效 reload、无效启动禁用，全通过。
- M2 三客户端生命周期：`.run/paper-m2-1789792922141/`。权限、重复加入、倒计时取消、活动 reload 拒绝、双房间、完整 WorldGenSettings、单局清理、新 Session 身份、损坏模板隔离、即时取消、双世界 disable 全通过。唯一更新的原 M2 staging 断言改为 M3 安全落地。
- 历史基线：M1 59 tests，M2 123 tests。旧运行记录仍在 .run/paper-smoke-1789750716466 与 .run/paper-m2-1789790524421。

## 已知临时行为与未验证范围

- M3 可扣至死亡，但没有淘汰、胜者、DeathBox、死亡观战和复活管理。原版死亡/掉落/复活仍生效，死亡时跳过圈伤/UI；原版复活到比赛世界后 ALIVE UUID 会继续受圈规则影响。自动“淘汰后不再参与”属于 M5。
- 活动断线为 DISCONNECTED，移除 UI、跳过扣血，无 OfflineBody/重连恢复（M6）。
- 崩溃/强杀和孤儿 marker 恢复未实现（M7）。WorldLoad/Unload 内同步重入 disable 不能等待当前主线程栈，仍靠 guard 安全收尾；大文件 IO 超过关闭等待上限可能遗留目录。
- 极端第三方生成器不返回时，超时中止开局后保留 CLEANUP 等待其 chunk future，避免带着未完成请求强行复用该房间。
- 仅在公开来源存在时识别玩家间接伤害；匿名红石/发射器、部分 TNT 矿车/连锁爆炸、source-less 自定义伤害、第三方直接改块/活塞搬动、天然与多人火/岩浆合流可能无法准确归因。混合有益/有害药水按目标整体过滤。没有完整 Combat attribution。
- 火箭/三叉戟、channeling lightning、床/重生锚的公共 API 分支已实现并编译，但未在本次三客户端脚本中逐一进行真实碰撞/引爆演练；不将它们计入已实测项。
- 尚未进行人眼视觉验收、巨型地图性能压测、第三方生成器/权限/战斗插件兼容测试。
- 原 500→700 默认圈配置冲突已经解决；旧配置现在明确拒绝并要求迁移，不再作为未完成问题。
