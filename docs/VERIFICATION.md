# M4 验证记录

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

## 当前边界

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
