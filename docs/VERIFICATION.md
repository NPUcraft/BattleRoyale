# M3 验证记录

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



