# Gameplay and Detailed Setup

BattleRoyale 是 **Java 25 / Paper 26.2** 的多人 Battle Royale 插件。当前为 **1.0.0-rc.9 发布候选**：保留 M1–M9 的安全地图编辑/维护、诊断、配置迁移和比赛功能，适配新的 Paper 维度存储与 API。历史里程碑测试结果对应当时的 Paper 1.21.8 环境。

## 构建

配置 JDK 25 的 JAVA_HOME，然后执行：

```powershell
.\gradlew.bat clean test build
```

Gradle 9.1.0 Wrapper 已附带，首次构建需要网络。Linux/macOS 使用 `./gradlew clean test build`。
安装产物：`build/libs/battleroyale-1.0.0-rc.9.jar`。Paper API 为 compileOnly，JUnit 和测试探针不进入安装 JAR。

## 安装与开局

1. 将 JAR 放入使用 Java 25 的 Paper 26.2 的 plugins。首次启动生成 config/rooms/maps/zones/loadouts/loot-tables/lobby/ranking/cosmetics.yml 和默认两张地图的 map-data/<id>/loot.yml，已有配置不会覆盖。
2. config.yml 的 lobby.world 必须是已加载世界，默认 world；大厅返回点使用世界 spawn。
3. 将**已经关闭且不再被编辑**的 Paper 26.2 Overworld 维度模板放入 plugins/BattleRoyale/maps/city、maps/desert，或修改 maps.yml。保留有效 `data/minecraft/world_gen_settings.dat` 和 region/entities/poi/data。也可提供含 `dimensions/minecraft/overworld` 的完整 26.2 存档，插件只提取其中主世界维度；全局 datapack/自定义生成器依赖需另外安装。旧 level.dat 模板先在隔离环境升级、停服后导出。
4. 在 playable-area 内提供足够安全地面。启动/reload 校验将模板缺失或地图尺寸冲突的地图标记为不可用；其他有效地图继续运行。全局房间/圈规则损坏仍拒绝加载。
5. 玩家 /br join solo，人数达标自动开始 60 秒倒计时。三房部署示例的开局人数为 solo 4 人、duo 8 人、squad 16 人，对应至少四支满编队的人数；人数不足会取消并重置倒计时。已有 rooms.yml 需按配置说明更新。管理员 /br debug start solo 可绕过 minPlayers，但必须有在线参与者。
6. PREPARING 冻结 roster、均衡分队，按冻结人数确定并保存初始战区；先持久保存 Lobby 原快照并应用一次 Loadout，再按范围复制模板。STARTING 清理世界、规划安全备用落点、生成一次性物资，随后安排随机航线登机与跳伞。准备期间冻结物品操作并免伤；全部在线参赛者完成着陆、断线成员已登记备用位置且临时资源清理成功后，才进入 RUNNING、创建断线替身并开始保护与缩圈计时。
7. /br debug end solo 结束比赛，恢复原状态后应用固定大厅菜单、保留末影箱/经验等状态、取消任务、移除 UI/临时来源记录、返回大厅、卸载并删除副本。所有模式的正常淘汰在 tick 末按 Team 判断胜负，展示 60 秒后自动清理；debug end 在 RUNNING 直接清理，仅保存非正式审计结果、不计永久战绩，在 ENDING 跳过剩余展示。

源模板只读，不能使用大厅、运行中的世界、链接目录或被外部进程修改的模板。复制保留 seed / 维度生成设置；必要的新区块由模板设置生成，不预生成整个地图。比赛副本使用 `Server.getLevelDirectory()/dimensions/battleroyale_game/`，编辑/维护使用同级 `battleroyale_maintenance/`；旧 `runtime-worlds.directory` 只保留配置兼容，不控制实际目录。升级前先正常结束旧比赛，旧运行副本不会自动转换恢复。

## 开局准备进度与飞机跳伞

准备 BossBar 依次显示五个阶段：保存玩家状态、创建比赛地图、检查安全落点、布置地面物资、登机与跳伞，同时显示已用时间。能够计数的阶段显示已准备落点或已完成部署的人数；地图复制耗时未知，只显示阶段，不编造复制百分比。准备完成、取消或失败会移除本局界面。

飞机是比赛副本高空的临时真实方块平台。每局在初始圈内随机选择东西或南北航向及横向偏移，预加载有限航线区块并检查整条航线净空；最多等待 45 秒加载，地形阻挡或安全高度不足时中止本次准备，不覆盖地图建筑。平台只占用空气，移动或结束时仅恢复仍与自身放置状态一致的方块。

登机后等待 5 秒，随后沿航线飞行，航程最长 45 秒。玩家可走出机翼或跳离平台，调整视角使用临时鞘翅滑翔；航程结束仍在机上的玩家会被送出平台。每位玩家离机后最多有 90 秒完成着陆，超时、进入岩浆、接近世界底部、过远离开初始圈或落在初始圈外时会回到事先验证的安全备用落点。所有人提前完成部署时无需等满航程；先落地者等待其他人，期间仍受准备阶段保护。

临时鞘翅不可转移、丢弃或留作战利品，原胸甲由服务端单独保管。落地、断线、中止准备或停止插件时回收鞘翅并恢复原胸甲；断线先完成装备恢复，再捕获比赛状态并登记备用落点。整个过程处于 STARTING，未进入可续局的 RUNNING 快照；准备阶段崩溃按未完成比赛处理并恢复持久保存的大厅状态。只有在线参赛者均已着陆、断线成员已处理且飞机/装备清理成功，才开始正式战斗、保护倒计时和安全区时间线。异常清理会记录错误并保留房间和副本供管理员检查，不强制删除仍可能承载待恢复状态的世界。

## 大厅语言与进入比赛

可选内置大厅是带护栏、绿植、照明和三座屋顶门廊的空中广场，不需要 WorldEdit 或额外大厅插件。首次建造有原方块备份及持久标记，重启不会覆盖装修。专用服务器需明确启用建筑并统一世界名称；具体字段见[大厅与地图配置](CONFIGURATION.md#rc3专用中文大厅)。

普通玩家登录时，先完成持久状态恢复，再回到大厅中心并取得按客户端语言显示的快捷物品。活动比赛的合法断线重连继续回到该局，防止通过重登逃离战斗；待恢复状态不会被大厅物品提前覆盖。指南针打开房间列表，末影之眼快速加入；个人战绩、排行榜、商店、外观分类与大厅全息按每位玩家的客户端语言呈现：zh 系列中文，其它及未知语言英文；控制台中文。自定义文字和玩家名原样保留，原版物品名称使用 Minecraft 自身的客户端翻译。

三个门廊讲台对应 `solo`、`duo`、`squad`，右键加入配置中同名房间；它们是入口菜单台，不是常驻加载的比赛世界传送门。加入后在大厅等待倒计时，房间准备成功才传送到独立比赛副本。等待和倒计时期间仍免伤、免饥饿并有跌落回拉；想取消排队，可右键快捷栏末格的红床，或使用 `/br leave`。`/br lobby` 不会直接绕过一个尚未离开的房间，也不能让进行中的存活玩家逃离比赛。队伍在准备阶段锁定并分配，`/br team` 查看结果；当前没有邀请好友组成固定 Party 的功能。

建议三个房间共用一个随机种子的自然地形模板，部署示例将可玩边界设置为 X/Z 各 `-10000` 至 `10000`。每局在此范围内随机选取完整落在边界内的初始安全区，按冻结人数确定圈大小；模板种子保留，各局位置可以变化。新比赛只复制初始圈外扩 512 格缓冲所覆盖的完整 region、实体与 POI 文件，维护编辑仍采用完整副本。范围复制不会预生成整张地图，也不能替代地形安全与物资覆盖检查，详见[配置说明](CONFIGURATION.md)。

## 实时房间侧栏、彩色提示与红床退出

大厅右侧计分板每秒更新在线人数和房间情况。每个房间只用一行显示名称、当前人数/上限，以及简短状态或倒计时秒数；不显示开局门槛、还需人数或分隔行。三房加在线人数与操作提示共五行。加入排队后仍能查看各房间，进入比赛或离开大厅后收起。每页最多五个房间，更多房间默认每八秒翻页。标题、开关和翻页间隔可在 `lobby.yml` 的 `sidebar` 段设置，不需要额外计分板插件。

品牌和标题使用青蓝色，说明为浅灰色，命令、参数和数字为金黄色；绿色表示成功，红色表示错误，橙色表示警告。大厅全息按标题、玩法说明和操作提示分行高亮；比赛 BossBar、淘汰播报、死亡箱与胜利标题使用同一配色。界面保留原有文字与命令，玩家或配置中的标签不会被解析执行。

加入房间后，在 `WAITING` 或 `COUNTDOWN` 阶段，快捷栏第 9 格会出现红色“退出房间”床。选中并右键即可离开排队并返回大厅，也可继续使用 `/br leave`。红床不能丢弃、移动或放置；原槽位物品保存在临时按钮内，离队时恢复。比赛准备前先恢复该槽位，再保存大厅状态并应用比赛装备，因此退出床不会被存入比赛装备，也不能在准备中或比赛中用于逃离。

普通聊天显示为“青蓝玩家名 `»` 浅灰正文”，分隔符为金色；加入和离开提示分别以绿色和橙色标记。正文保持原样，不把玩家输入的 `<red>` 等标签当作颜色或命令。管理员可用 `config.yml` 的 `chat.format-enabled` 与 `chat.connection-messages` 开关控制，两者默认 `true`，修改需重启；专用聊天插件可以更高事件优先级接管，详见[配置说明](CONFIGURATION.md#rc4颜色样式与聊天)。

此处描述功能用法；是否已部署、服务端探针覆盖以及真人右键操作验证以[验证记录](VERIFICATION.md)为准。

## 安全区指引、物资与结算返回

比赛行动栏用随视角转动的八方向箭头和水平距离指引下一圈；箭头始终指向目标圈中心。下一目标大小为 0 时改为“最终收拢点”，安全区完全消失后显示红色“安全区已消失”，不再把零面积中心标为安全位置。人在目标圈外时显示红色边界距离，进入目标圈后显示绿色中心距离，到达中心时显示圆点。行动栏省去圈心坐标，并在存在预告或尚有物资的有效空投时，追加最近空投的金色箭头与距离，不占用物品槽。BossBar 显示下一圈面积，使用正方形边长平方的写法：half-size 为 50 时边长为 100，显示 `100² ㎡`，对应真实面积 10000 平方米；不会将面积数值再次平方。归零后显示 `0² ㎡`。红色尘埃墙与亮色边缘按玩家附近采样，靠近角落时两条边共享粒子预算；这只是视觉引导，不改变世界碰撞边界。新安装资源默认视距 80 格、水平/垂直间距 1.5 格、玩家下方 4 格至上方 12 格、每次每玩家最多 400 个，已有配置的数值不会自动覆盖。

地图中的通用存储容器可以自动生成物资：普通/陷阱箱、双箱、木桶、潜影盒、漏斗、发射器和投掷器都可参与，熔炉、酿造台等专用槽位不参与。范围限制在本局初始圈内，后台每 tick 最多检查 16 个候选；rc.9 默认合格空容器有 40% 概率从 `basic` 表条目抽取 1–3 次；自动容器使用自身的抽取次数，不叠加该表为配置物资点设置的次数。已有物品、玩家放置的容器、地图配置的独立物资点及空投箱会跳过。每箱每局只决定一次，双箱不会生成两份；没抽中或已经拿空都不会重抽，区块重载和比赛恢复继续保留处理标记。

每轮空投先预告坐标：临近缩圈时，在下一安全区内寻找安全落点；没有下一圈时使用当前圈。目标圈已为 0 时没有合法安全落点，该轮按有界尝试规则取消，不在已消失的安全区强行生成空投。聊天广播固定 X/Y/Z，并在落点旁显示原生信标的金色光柱；此时只有预告和信标，没有可领取的木桶。公告后完整等待 60 秒才开始下降，即使首轮等待不足 60 秒也照常等足；空投计时不暂停缩圈。默认下降 8 秒后右键木桶领取，光柱在落地后最多持续 120 秒，物资取空则提前撤下，撤下不删除箱子。信标、玻璃与底座受保护，不能拆取；仅为半径 24 格三维球形范围内、同局同世界且在线存活的非观战成员提供速度 I；原生信标每 80 ticks 刷新，效果持续 100 ticks（5 秒），无第二增益，不覆盖更强或持续更久的已有速度效果，离开范围或清理信标后自然到期；取消、到期、取空或结束时恢复插件所占地形。光柱可见距离仍受区块加载与客户端渲染距离限制。公告后不会换坐标，落点被占用、变得危险或已不在安全区内时取消并通知。寻找阶段最多尝试 24 个候选，找不到安全落点同样取消。

每个成功落地的空投保证一件附魔钻石武器/护甲或附魔弓，以及一个不死图腾，再额外抽取空投表默认 8–12 次随机物资；这两件保证物资不计入随机抽取次数。保证物资先入箱，箱子满了只舍弃溢出的随机物资，不挤掉保证物资。任何随机物资与保证物资都不提供鞘翅。

空投装备使用更高附魔档位：随机装备有 80% 概率进入附魔抽取，保证装备必定进入。进入后按 70% 强化、25% 稀有、5% 极品抽取；强化通常为主附魔 II–III，稀有提高主附魔并追加耐久，极品使用原版上限并追加适合该物品的兼容附魔。所有等级都受原版上限限制，不加入诅咒或冲突附魔；5% 指进入附魔抽取后的档位概率，不是每个随机物品的极品概率。普通物资仍沿用下面的低等级规则。基础表和空投表另加入木头、圆石、石头、铁锭、金锭、煤和低权重钻石，数量与出现概率由表内配置决定。

普通生成装备有约 35% 概率附带一项适用的安全附魔，通常为 I–II，快速装填和致密仅 I；没有诅咒或多项叠加。食物、药水和烟花不会因这条规则获得附魔。新药水包含普通与喷溅两种形式，全部为 I 级：隐身 30 秒、防火 60 秒、治疗/伤害瞬时、剧毒 8 秒；喷溅按原版命中距离衰减。战斗烟花为短程、单颗小球烟花星的原生火箭，可直接燃放或装弩使用。管理员可使用 [11 个原生物资预设及配置规则](CONFIGURATION.md#rc6开局人数原生物资与怪物奖励) 调整表内权重和数量。

存活参赛者在本局比赛世界击杀自然生成的敌对怪物，可获得少量额外物资；默认 35% 概率抽取一次，每名玩家每 10 秒最多一次尝试，概率未中也计冷却，每局最多 64 次成功奖励批次。直接玩家伤害和玩家投射物可计入，环境磨血后的无玩家来源击杀、宠物击杀、刷怪笼/刷怪蛋/发射器、试炼刷怪笼、繁殖/分裂/转化等生成来源不计入；玩家、被动动物和离线替身不是奖励目标。最多可配置三次抽取，每次不超过 8 个物品且遵守原生堆叠上限，原版掉落与经验保留。来源排除、冷却与局内额度随正常存档保存，不能通过重登或正常恢复重置。

恢复后的已开始或已认领空投轮次不会补发，即使停机时尚未落地；之后的轮次正常继续。自动容器和空投的开关、表、概率、次数及视觉参数见 [配置说明](CONFIGURATION.md)。它们与配置的地面物资区域各自工作；正式 `survival` 地图的地面物资密度和部署专用装备见 [BattleRoyale 部署记录](BATTLEROYALE.md)，不等于插件通用默认值。

比赛进入 `ENDING` 后，行动栏显示“返回大厅倒计时”，默认展示 60 秒后自动返回。使用 `/br leave` 可以提前回大厅，但须等待结果持久化完成；尚在保存或恢复/存储未就绪时会提示稍后重试。提前返回会恢复原大厅状态并移除比赛界面，本局成员身份、队伍历史和结果仍保留；必须等本局结算清理完毕后才能加入另一局。存活玩家在准备和进行中的比赛里仍不能用 `/br leave` 逃离。

这些是当前 rc.9 的功能规则；服务端公共 API 探针与真人客户端、多人操作的验收范围不同，此处不代表已部署或完成真人验收，具体进度以 [BattleRoyale](BATTLEROYALE.md) 与 [验证记录](VERIFICATION.md) 为准。

## 带鞍马匹与获胜烟花

进行中的比赛会在当前安全区内少量生成成年、已驯服且带鞍的原生马匹，不绑定专属主人，供参赛者骑乘。默认每局累计最多 16 匹（可配置 1–64），每轮寻找间隔 15 秒、最多检查 24 个候选点，生成点彼此至少相隔 64 格；需要平坦的 3×3 安全地面和足够净空，水面、危险方块和被生物占用的位置不接受。因此复杂地形可能生成不足上限，插件不会强行填满。

额度记录的是本局累计生成位置，不是当前存活马匹数。马匹死亡、被移除或马鞍被拿走都不会补刷，也不会重新补鞍；正常保存和比赛恢复保留原生马匹状态及累计额度。旧比赛缺少有效账本时停止补充，避免重复生成。大厅、准备、结算阶段不生成比赛马匹，比赛结束后随副本清理；该功能不会遍历删除地图中的自然马。

获胜庆祝使用多色大球与爆裂组合烟花，每次分三波、间隔 8 ticks；单波最多 8 发，同一场比赛最多同时保留 32 发，清理会取消待发波次并移除剩余烟花。庆祝烟花带专用身份并拦截其伤害，物资中的战斗烟花仍按正常战斗规则处理。客户端实际渲染和真人骑乘体验需单独验收，本节只说明实现与配置规则。

默认中文菜单、侧栏和大厅标题使用“大逃杀”，英文使用 `BattleRoyale`。rc.8 全面更名为 BattleRoyale，使用 `/br`（完整命令 `/battleroyale`）、`plugins/BattleRoyale`、`battleroyale.*` 权限及 `com.npucraft.battleroyale` 包；没有旧命令别名或自动读取旧目录的兼容分支。旧安装需备份后离线迁移；已有自定义标题不会被资源文件自动覆盖。

## 命令与权限

- battleroyale.command（默认所有人）：/br、/br help、/br version。
- battleroyale.play（默认所有人）：/br rooms、/br join <room>、/br autojoin、/br leave、/br team、/br spectate <room>。
- battleroyale.admin（默认 OP）：/br reload、/br debug rooms、/br debug maps、/br debug session <room>、/br debug zone <room>、/br debug protection <room>、/br debug start <room>、/br debug end <room>、/br debug loot <room>、/br debug deathboxes <room>、/br debug teams <room>、/br debug offline <room>、/br admin loadout edit <room>。

完整命令名 /battleroyale；补全按权限提供。参赛 join 和未开局成员 leave 只允许 WAITING/COUNTDOWN；ENDING 参赛者在结果持久化后可用 /br leave 提前回大厅，成员身份保留至正常清理；观战者可通过 /br leave 恢复 Lobby，阵亡成员仍保留队伍历史。autojoin 选择人数最多的可加入房间，同人数保持配置顺序。人数不足取消并重置倒计时。活动 Session 或未完成资源清理存在时禁止 reload；失败 reload 保留旧配置。

## M3 配置与旧配置迁移

- rooms.yml：rc.6 新安装资源的 countdown-seconds 为 60，已有文件不自动覆盖；pvp-protection-seconds 默认配置 60，0 禁用；spawn.min-distance 默认 64，spawn.max-attempts-per-player 默认 200。
- zones.yml：初始人数阈值严格递增，half-size 至少 500，并覆盖房间最大人数。8 人选 500，9–16 人选 750，17–32 人选 1000。
- rc.9 默认四个 target-half-size 为 **400 / 250 / 125 / 0**，依次严格减小。只有最后目标可为 0；第一目标必须小于**所有**初始档位，不做静默 clamp。旧文件最后目标仍为正数也会继续收拢至 0，不会静默改写已加载的配置；已有文件不会被新资源覆盖。
- 每个 Room 的每张候选地图，宽和深必须足以容纳该 Room 可达的最大初始 halfSize。诊断包含 Room、Map、Profile、要求尺寸和实际区域。
- config.yml 的 zone-ui 可控制行动栏导航、BossBar、粒子、间隔、视距、间距、高度和单次上限。rc.5 新安装资源默认每 5 ticks 更新、视距 80、最多 400 粒子/玩家/次；已有数值保留，整个旧段缺失时的兼容默认见 [配置说明](CONFIGURATION.md)。
- 缺失整个 spawn、zone-ui 段时采用默认值；填写后严格校验类型、有限数值和范围。

**升级已有 M1/M2 配置时，必须修正旧的 500 → 700 冲突。** 插件不会覆盖 zones.yml；旧的首目标 700 会明确拒绝加载。可按随 JAR 附带的新四阶段配置迁移。

halfSize 始终表示正方形边长的一半。初始中心在 playable-area 内随机选取，后续中心在上一圈允许的偏移范围内选取，整个下一圈始终包含于上一圈。InitialZone 在整局内不可变，用于本局 Loot 范围筛选，后续缩圈不会重新生成配置的容器点和地面物资。rc.5 自动容器在初次发现时决定一次，空投则独立按缩圈轮次调度。

## 实时规则

ZonePhase 使用 WAITING / SHRINKING / FINAL，与 GameState 分离。中心和 halfSize 每个 server tick 按单调时钟的真实经过时间线性插值；卡顿后按真实进度推进。最后目标为 0 时，在该阶段配置的 shrink-seconds 内平滑收拢至零，之后进入 FINAL，继续使用最后阶段伤害。

旧配置若保留正数末目标，会先到达已公告的位置和大小，再不暂停地沿原最后阶段的半径缩速继续收拢至 0；收尾中心固定，仍显示同一最后阶段，不增加轮数。极端配置的额外收尾最多 24 小时。恢复保留先前已公告的目标、当前几何和剩余时长；旧版本保存的正数 FINAL 会从保存的位置和大小继续收拢，停机不会消耗剩余时间。此兼容不跳过原有配置哈希检查，修改正在恢复的比赛规则仍会按恢复策略处理。

圈外距离是点到正方形的最短欧氏距离，正面积圈的边界算圈内；大小为 0 时不存在安全位置，正中心也算圈外，并停止绘制边界粒子。每真实秒最多一次扣血：
`min(baseDamage + outsideDistance * extraDamagePerBlock, maxDamage)`。安全区归零后，该次伤害至少为 1 HP，包括最后阶段配置伤害为 0 的情况，避免中心或零伤害配置造成永久免伤。
直接修改生命值，绕过护甲、保护附魔、抗性和吸收伤害流程；可扣到 0，不保底 1 HP。卡顿后不会补打多次。在线只处理比赛世界中的 ALIVE；存活 OfflineBody 由同一 Session loop 在同一圈伤脉冲中按替身当前位置应用相同公式。

保护期从全部部署完成、进入 RUNNING 时开始，只拦截同局参与者之间可识别的玩家来源。覆盖近战、玩家投射物（箭、三叉戟、弩/烟花）、有来源的爆炸、TNT 放置/引爆链、玩家点火/蔓延与岩浆/流动、有害喷溅和滞留药水。天然环境和无玩家来源的怪物伤害继续生效。保护到期通知一次；共享来源继续服务整局友伤和战斗归因。队友之间可识别的玩家伤害整局拦截，包含 OfflineBody。详细归因边界见架构文档。

出生候选使用方块中心、水平欧氏间距，无重复位置。地面需完整实体支撑，脚/头通行且无液体、火、细雪、岩浆块、仙人掌、营火、浆果丛、凋零玫瑰、尖滴水石、蛛网或树叶。每局同时仅一项区块请求，每 tick 最多检查一列；不扫描整张地图。全部点位确认后才传送至备用位置并接入登机流程，这些点也是跳伞异常回拉和断线替身的安全落点。规划或飞行期间断线保留冻结名单和 Team，登记比赛状态并从断线时开始计时，进入 RUNNING 时在已规划落点放置替身，不重新分队。

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

`rooms.yml` 的 `loadout` 引用 `loadouts.yml` 中共享 ID。默认 default 是空装备，solo/squad 共用。管理员在大厅执行 `/br admin loadout edit solo`，点击自己背包选择复制画笔，左键填目标槽、右键清空；GUI 0–35 为普通槽，36–40 依次为头盔/胸甲/护腿/靴子/副手，45 循环选择初始快捷栏，49 保存，53 取消。真实背包不移动；关闭窗口丢弃草稿。每个 Loadout 同时只允许一个编辑者，保存完成前锁不释放。GUI 保存可以用于活动比赛，但仅影响以后进入 STARTING 的 Session。完整 reload 还要求没有编辑窗口和未完成保存。

`loadouts.yml` 槽位使用 Paper 原生物品字节的 Base64、format=`paper-native`、version=1。通过 GUI 编辑，不要手工拼接 NBT。名称、Lore、附魔、耐久、药水、模型数据、原生组件及 PDC 由 Paper 原生序列化保存。物资表支持原生 `minecraft:` key 及上述 `battleroyale:` 原生预设；ItemsAdder/Oraxen 等没有实现集成。

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

Area 每局判定一次激活概率与目标点数，仅在其与 InitialZone 的交集内取点。rc.9 将交集划分为不重叠的小区域并打乱顺序，每个区域随机寻找一个落点，使有限物资分散到圈内，而不是反复在整张区域任意取点。Y 是物品脚部高度范围，采用可安全支撑的最高地表，允许单格净空；不会向地下洞穴全面扫描，也不预加载整个区域。

每图最多 1024 个配置容器、128 个 Area；单 Area 的 max-spawns 上限为 1024，所有 Area 的 max-spawns 合计上限为 2048，每点 max-attempts 为 1–256。除此之外，单局地面生成共用 **800 次候选尝试**和从首次地面请求起 **45 秒**的预算，失败地形也消耗次数。达到任一预算后停止发起新地面区块请求，已经发出的请求仍会等待并处理完毕，保留已生成物资，因此 45 秒不是强制取消在途请求或整局 STARTING 的绝对截止时间。出生点规划与物资生成仍受原有 120 秒准备任务超时约束，飞机阶段另计自身时限；单局地面物品实体仍最多 10000 个。

本次正式服配置方案将 `survival` 的目标地面落点设为 **320–400**，它是部署配置，不是插件通用默认值，也不是保证生成的实体数量。水面、树冠、危险地面、区块加载时间和上述总预算都可能降低实际落点数；每个成功落点又可能按物资表生成多堆物品。`/br debug loot <room>` 会显示生成状态、点/Area 计数、物品数、missed-spawns、candidate-attempts、candidate-budget-exhausted 与清理区块数，可据此判断地形和预算影响。

比赛装备应用前保存 storage/armor/offhand、快捷栏、XP、模式、生命/饥饿/饱和、药水、火焰/跌落状态；另隔离末影箱、光标、吸收生命、疲劳和飞行状态。结束丢弃局内所得并恢复原状态；淘汰者立即进入待恢复队列，原版重生到 Lobby 后重试；其余离线/死亡者在结束后进入队列，上线/重生后重试，成功才清除。未恢复不能加入新局。

比赛世界首次加载的区块清除原版容器物品/战利品表、物品实体、经验球与普通生物；保留村民、盔甲架、展示/悬挂实体及矿车（清空带库存实体）。rc.9 还会在比赛副本中分批替换模板自带的贵重整块储存材料：铁、金、钻石、绿宝石、下界合金、三种原矿储存块、煤、青金石、红石，以及明确列出的铜块氧化/涂蜡变体。Y ≥ 0 替换为石头，Y < 0 替换为深板岩；原版矿石、深层矿石与远古残骸保留，源模板不改。

方块处理每 tick 最多检查 4096 个位置、替换 256 块，不主动加载或常驻整个地图；未处理完的模板贵重块也不能靠抢先破坏、爆炸或活塞取得。玩家新放置的块及空投信标/底座等插件生成块有排除标记，不会被当作模板资源清除。扫描游标和排除信息随区块持久化，正常恢复继续既有进度；缺少这一功能版本标记的旧活动比赛不会追扫玩家已改造的区块。后续自然生物、物品和 XP 正常存在，清理不会重复。除上述模板资源清理及临时结构保护外，昼夜、天气、自然刷怪与方块破坏/放置/爆炸保持原版；仅比赛世界禁止 Nether/End 门、End gateway 传送及门生成，珍珠/紫颂果保留。

M4 实服验证脚本：`node scripts/paper-m4.mjs <stopped-paper-directory> [mineflayer-package-directory]`。测试探针开关 `-Dbattleroyale.probe.m4=true`，不会关闭自然刷怪/昼夜/天气；站立测试客户端使用探针免伤。正式 JAR 不包含探针。

## 当前边界

- 淘汰后原版重生进入本局 SPECTATOR；保留 Lobby 原快照直到 /br leave、退出或结束，Team 历史不变。
- 活动断线保存独立比赛快照并创建可攻击的 OfflineBody；到期/死亡统一淘汰，重连从替身恢复。M7 另提供进程崩溃恢复。
- 原状态已持久化，跨正常停服、禁用与崩溃恢复；Team 胜负已实现。永久统计和第三方物品 Provider 后续实现。
- 崩溃/强杀、文件锁可能保留带 marker 的目录；M7 只处理有效所有权标记的直接子目录，无标记、链接与身份不匹配的目录保留人工检查。
- 匿名红石/发射器、无来源的 TNT 矿车、第三方直接修改方块或制造 source-less 伤害，以及多来源混合火/岩浆，不能可靠还原玩家来源。见架构文档的具体限制。

[架构](ARCHITECTURE.md) · [路线图](ROADMAP.md) · [验证记录](VERIFICATION.md)

## M5 战斗与死亡规则

有效、未取消的同局玩家伤害进入 session 级 CombatTracker；单调时钟默认窗口 **15 秒，边界包含**。过量伤害按剩余生命截断，公开 API 可识别的吸收消耗计入有效伤害。直接致死玩家优先；环境死亡选择窗口内最近攻击者。排除自己和非本局 UUID；助攻按窗口累计伤害 **≥4 HP 或占玩家总伤害 ≥20%**，排除 killer 并去重。淘汰玩家的历史攻击仍可归因；统计只在本局内存中。

实际 storage、盔甲、副手及光标由原生字节独立保存，包含仍存在的消失诅咒装备；不读取过滤后的原版 drops，不包含末影箱。已在致死攻击中损坏消失的装备不会凭空恢复。原版掉落、经验、itemsToKeep 和死亡消息被接管。

DeathBox 是 **54 格共享库存**，以 BARREL BlockDisplay、Interaction 与 TextDisplay 展示，不放置真实方块。浮字显示死者、原因/击杀者和固定淘汰用时。Y 限制在世界有效高度内。允许左/右键取出与 shift 快速取出；禁止放入、拖入、数字键、换副手、双击收集及 Creative 注入。每次交互/点击复查同局、ALIVE、RUNNING、同世界和默认 6 格距离。空盒保持至清理。

经验保存 **floor(当前总经验点数 / 2)**。从等级和进度重建当前可花费 XP，避免 Bukkit lifetime total 在附魔后陈旧；不是等级除以二。正数生成一瓶 Stored Experience，0 不生成。PDC 保存整数，投掷时复制到实体，ExpBottleEvent 精确设置；普通瓶不改。拒绝错误类型、负值及超范围载荷，不解析 lore。

所有模式使用 TeamOutcomeResolver；Solo 是单成员 Team。ServerTickEndEvent 统一处理本 tick 淘汰，最后多个 Team 同 tick 全灭判 TIE，赢家包括这些 Team 的所有固定成员；不同 tick 不拼接平局。结果不可变，只进入 ENDING 一次。默认展示 **60 真实秒**，停止圈、战斗和箱子访问，保留世界和死亡盒、Adventure WINNER/TIE 标题及中性烟花；存活替身在此时退休，不产生死亡盒。到期恢复并接入 M2 清理。

## M6 队伍、观战与离线替身

- PREPARING 将个人 roster 洗牌后 round-robin 分到 `ceil(N / teamSize)` 个 Team，大小相差至多 1 且不超过配置容量。队伍 ID 带 Session 作用域，成员从此固定；Party 输入边界预留，M6 只接受单人输入，不调用第三方 Party。teamSize > maxPlayers 允许配置，单 Team 开局在首个运行 tick 末直接获胜。
- active combatant = ALIVE，或 DISCONNECTED 且拥有存活/待落地的本局替身。淘汰批次结束后剩一个 active Team 即获胜，已死、已回 Lobby、离线队友都列入不可变 winnerIds。离线赢家下次登录收到队伍结果。
- 死亡观战优先存活队友，否则本局其他存活成员，也可自由飞行。只限制 BattleRoyale 注册观众：公共 spectate-start 事件检查目标身份，teleport 事件阻止跨世界。外部 `/br spectate <room>` 只接受 RUNNING/ENDING 且 allow-external-spectators=true 的房间，不占参赛名额、不分队、不进入胜负。退出/断线恢复原 Lobby 状态，观众不会产生替身。
- 载体采用**关闭 AI 的持久 Villager + 带名字/装备的 marker ArmorStand**，使用 Paper 公共 API；前者承担真实受伤和生命值，后者仅展示。没有 NPC 库、NMS、伪玩家或玩家皮肤保证。它不是完整 Player 模拟（碰撞、盔甲损耗和怪物行为按载体原版规则）。
- 替身保存比赛物品、盔甲、副手、光标、选中格、XP、生命/吸收、药水、食物、火焰/空气/摔落及位置朝向；与赛前 Lobby 快照分离。共享 M3/M5 provenance、CombatTracker 和 EliminationService，死亡/超时只提交一个 DeathBox，无原版散落物和 XP。
- 默认断线 **120 秒单调时间**。重连先隔离旧 playerdata，在截止前从当前替身恢复当前位置、受伤生命值、装备/物品和 XP；成功提交才移除替身，不重新应用 Loadout。恢复失败保留替身权威状态并踢回客户端供重试。已死/超时登录回 Lobby，替身生成失败或失效会安全淘汰，不能永久占用存活名额。
- 一个 Session loop 管理超时、圈伤、替身捕获和怪物辅助，无每替身定时任务。附近 `Monster` 无有效目标时可用公共 `Mob.setTarget` 指向替身；默认 radius=24、interval-ticks=20，每次至多检查 128 个附近实体。不扫描全世界，不改变原版实体活动距离和远距消失规则，也不保证所有怪物类型把载体当成玩家。
- UI：参赛者 Alive / Kills / Zone / 阶段时间 / 圈距；观众 Alive / Teams / Zone / 阶段时间。调试命令显示 Team 成员状态及替身 UUID、生命、位置、剩余时间。

配置 `disconnect.reconnect-seconds` 默认 120，允许 0–3600；`disconnect.mob-aggro.enabled` 默认 true，`radius` 为有限 0–64，`interval-ticks` 为 1–1200。缺整个段落时使用默认值；错误配置拒绝加载。0 秒为立即超时淘汰。

实服复现：`paperProbeJar` 后运行 `scripts/paper-m6-candidate.mjs`、`scripts/paper-m6.mjs`、`scripts/paper-m6-edges.mjs`，参数同 M3–M5 脚本。细节及公开 API fixture 边界见 [验证记录](VERIFICATION.md)。

M7 已实现数据库、进程崩溃恢复和孤儿世界管理。永久统计/排名、经济与外观商店已在 M8 实现；第三方 Party 仍未实现。匿名/第三方 source-less 伤害、红石责任链、混合火/岩浆来源等仍受 Paper 可观测来源限制，见 [架构](ARCHITECTURE.md)。

## M7 存储与恢复

安装包已包含 SQLite JDBC 3.53.4.0、MySQL Connector/J 9.4.0 和 Gson 2.13.2，无需额外下载驱动。默认配置：

```yaml
storage:
  type: sqlite
  sqlite:
    file: data/battleroyale.db
  mysql:
    host: 127.0.0.1
    port: 3306
    database: battleroyale
    username: battleroyale
    password: ""
    connection-timeout-ms: 5000
recovery:
  enabled: true
  checkpoint-seconds: 5
  orphan-delete-after-minutes: 60
```

SQLite 路径相对 `plugins/BattleRoyale`；不得穿越目录或指向链接。MySQL 需事先创建数据库与专用账号，授予该库建表及读写权限；把 `storage.type` 改为 `mysql` 并填写凭据。数据库设置变更必须重启服务器。不要让不相关服务器共享同一个恢复数据库。迁移只创建/升级恢复表，不自动重置或删除现有数据库；遇到更新版本的 schema 会拒绝启动。

插件加载后异步完成迁移、读取、租约与恢复，登录玩家在恢复期间保持冻结，日志出现 `Recovery bootstrap complete` 后再完成重连或大厅路由。数据库不可用时插件禁用并保留 runtime 目录；运行中故障会显示 DEGRADED、重试并合并检查点，已有比赛继续，新比赛被阻止。`recovery.enabled: false` 只关闭续局恢复，原 Lobby 状态持久化仍为必须步骤。

- `/br debug storage`：provider、连接健康、schema、队列、合并等待数、最近成功检查点、脱敏失败类型。
- `/br debug recovery`：启动状态、恢复/放弃数量、孤儿世界、持久玩家记录数（包含比赛中的原状态）。
- `/br debug session <room>`：增加当前 revision、已落盘 revision 和 degraded 状态。

原 Lobby 背包、末影箱、光标、经验、模式、vitals/药水等先在数据库提交，再应用 Loadout 或外部观战状态。成功恢复后保存玩家 generation 标记与 playerdata，再异步确认和删除恢复记录；删除失败期间冻结物品操作并重试，避免重复注入。损坏的原状态记录不会被静默丢弃，保留给管理员修复。

每局使用完整 V1 DTO、SHA-256 与递增 revision；正常每 5 秒检查点，关键阶段/淘汰/离线/重连/DeathBox 变化优先。可配置间隔最少 1 秒。只恢复 RUNNING 和 ENDING；不完整准备、Loot 未完成或配置/文件校验不符则放弃该局，原状态转待恢复。战斗快照保留 Team、击杀助攻、当前背包、身体、近期归因、圈、保护期、Loot 完成标记与 sanitizer 集合。所有比赛计时保存剩余/已过时长，停机不消耗比赛时间。崩溃时 ALIVE 变为完整重连窗口的身体；已离线者续用原剩余时间。死亡/外部观众回 Lobby，不恢复镜头目标。

恢复加载原 runtime 世界，不复制模板、不重新生成配置的容器点或地面 Loot。rc.5 自动容器沿用本局处理标记，已开始或已认领的空投轮次不补发。启用原版 autosave，检查点不调用整世界 `save()`。**数据库与 region/entity 文件不是同一事务，属于尽力恢复；强杀可能回退最近几秒的比赛状态，世界文件可能比数据库更旧。** 不提供跨数据库/世界文件的 exactly-once 保证。请在服务器停止或一致备份流程下同时备份数据库与 runtime 数据；本阶段没有自动备份系统。

正常 `/stop` 或禁用是结束比赛：在线玩家恢复、离线玩家保留待恢复记录、正常删除世界并完成会话。只有非正常进程终止续局。无法恢复或无引用的有效标记世界持久写入 ORPHANED 和首次发现 UTC 时间，默认至少保留 60 分钟；启动及每 5 分钟检查。删除前复核路径、marker、时间、数据库引用和加载状态；Windows 文件占用仅有限重试。不要通过其他插件或后台手工加载 BattleRoyale runtime 目录。

详细设计、测试证据和限制见 [架构](ARCHITECTURE.md)、[验证](VERIFICATION.md)。永久档案、累计统计、Rating 与经济外观已在 M8 实现；Party 集成仍延期。

## M8 大厅与永久数据

新增 `lobby.yml`、`ranking.yml`、`cosmetics.yml`，已有文件不会覆盖。大厅默认 0/1/4/7/8 格为房间选择、快速加入、我的战绩、排行榜、外观商店，使用 `battleroyale:lobby_action` PDC。大厅登录／返回先完成 M7 pending restore 或比赛重连，再清空随身背包并应用 canonical 菜单；**末影箱不受大厅菜单重置影响**。比赛中的 ALIVE／DISCONNECTED 玩家不能用 `/br lobby` 逃跑；阵亡观战者先 `/br leave`。菜单锁只针对 BattleRoyale 控件和 GUI，不全局禁止普通背包操作。

新增玩家命令：`/br lobby`、`/br profile`、`/br leaderboard [rating|kill_score|wins|kills|assists|damage]`、`/br shop`、`/br cosmetics`。管理员：`/br admin cosmetic grant|revoke <player> <id>`、`/br admin purchases`、`/br debug economy`、`/br debug stats <player>`。Grant/revoke 使用服务器缓存中的玩家身份；revoke 同步解除该永久装备，但当前比赛保留冻结外观。购买、装备限定大厅，付费购买的确认/取消菜单按玩家客户端语言显示。

Profile 异步加载；未完成或 DB 失败时不能 join/autojoin/购买。比赛中不按每次击杀或每帧查询永久库。UUID 是身份，名字只用于显示并在登录更新。

### 结算与排名

Schema **V2** 增加永久档案、比赛/玩家结果、周期统计、解锁、装备、购买账本；从 V1 原地迁移并保留恢复表。正式 NORMAL/TIE 结果以 `sessionId` 唯一键做一次事务：结果、累计统计、Rating、Kill Score、日/周/月统计全部成功或全部回滚。ADMIN_END、RECOVERY_ABANDONED、INTERNAL_ABORT 不计正式战绩；无法可信解码的损坏快照不制造战绩。已有正常结果的 ENDING 提前清理不会撤销或重复结算。

队伍最后一位 active combatant 淘汰时按 tick 批次记录名次；四队中两队同 tick 淘汰均为第 3，最终两队同 tick 淘汰均为第 1。所有成员获得相同队伍名次，阵亡/离线冠军队员也完整获得 win +1。

Rating 默认初始 1000、下限 0，按 `(placement-1)/(teamCount-1)` 匹配 `ranking.yml` 分段；单队为 0。最高分永久保存。Kill Score 独立为 kills×10 + assists×3；不替代 kills，也不因死亡扣分。比赛开始冻结 ratingBefore，提交时事务读取当前 Rating 再应用名次变化，以免覆盖后台修改；存在外部改分时历史 ratingBefore 不保证等于 ratingAfter−ratingDelta。

周期按 completedAt 与 `statistics.period-time-zone`（默认 UTC）固化：DAY `YYYY-MM-DD`、WEEK ISO 周一开始的 week-based year `YYYY-Www`、MONTH `YYYY-MM`。后续修改时区不会重新归类历史记录。累计榜使用当前 Rating；周期榜使用该周期 Rating Gained / Kill Score Gained。历史不删除、不按月重置；GUI 支持分页和前期/后期/当前期，空期显示“暂无数据”。SQL LIMIT/OFFSET、有稳定 tie-break 和索引；缓存默认 30 秒并合并同一查询。

### 结果 outbox 与恢复

`plugins/BattleRoyale/result-outbox` 保存带版本/checksum 的不可变 JSON，文件 force + 原子 rename 后才允许正常世界清理。DB 写失败会重试；进程重启即使比赛世界已删除，也可独立重放结果。永久事务提交后删除该 outbox 文件；重复提交返回已有结果。M7 快照同时保存名次批次、累计有效敌方伤害、死亡/存活时长、冻结 Rating/外观及未完成结果；停机时间不计存活时长。

M7 V1 老快照仍可恢复游戏，但没有 M8 名次/伤害/冻结档案的历史，**不追溯猜测该旧比赛的永久战绩**。M8 后创建的比赛具有完整记录。DB、文件系统和 Minecraft playerdata/world 仍不构成同一个 ACID 事务；完全未写入任何持久介质的瞬间崩溃仍有检查点窗口。

### 经济提供者

配置 `economy.provider: auto|coinsengine|excellenteconomy|vault|none`，默认 auto-priority `[coinsengine, excellenteconomy, vault]`，`currency: coins`、`shop-enabled: true`。显式提供者不可用时不回退其他提供者；缺失/不兼容时只关闭付费商店并提示，比赛仍能运行。免费外观不调用经济 API。

- **CoinsEngine 2.7.x**：独立 legacy static API adapter；历史 M8/M9 实测 2.7.0 + nightcore 2.15.0 + Vault 1.7.3，Paper 1.21.8 / Java 21。不声称支持不同二进制接口的 CoinsEngine 2.6.x。2.7.0 默认货币初始化在无 Vault 的实测环境中发生上游 NoClassDefFoundError，实测组合保留 Vault。
- **ExcellentEconomy 2.8.0**：独立 ServicesManager API adapter；历史实测 2.8.0 + nightcore 2.16.2 + Vault 1.7.3，Paper 1.21.8 / **Java 25**。此上游版本为 Java 25 字节码。BattleRoyale 使用官方 2.7.0 过渡发布包中的新版公共接口，所调用的方法与 2.8.0 一致；自 rc.2 起 BattleRoyale 自身也编译为 Java 25，不修改上游 JAR，不使用反射。
- **Vault 1.7.3 / API 1.7.1**：通过 ServicesManager 获取 Economy；实测 CoinsEngine 提供的 Vault service。Vault 使用其默认货币，`economy.currency` 不会伪造多货币选择。

上述历史组合不代表已经通过 Paper 26.2 联机购买验证；经济插件自身也须适配目标平台。依赖均为 compileOnly/softdepend，不打入 BattleRoyale JAR。第三方经济调用默认 server thread；内部使用 BigDecimal，double 仅在边界转换，不能精确表示、超出范围或违反货币小数位的价格会拒绝，不静默四舍五入。

公开来源：[ExcellentEconomy 开发 API](https://nightexpressdev.com/excellenteconomy/utility/developer-api/)、[CoinsEngine 2.7.0 官方发布](https://modrinth.com/plugin/excellenteconomy/version/2.7.0)、[Vault API](https://github.com/MilkBowl/VaultAPI)、[Vault 1.7.3](https://github.com/MilkBowl/Vault/releases/tag/1.7.3)。

### 购买与纯外观边界

本地账本记录 PENDING → WITHDRAWING → COMPLETED；解锁与完成状态同一 DB 事务。扣款成功但解锁事务失败时，先确认没有已提交结果，再尝试退款；成功记 REFUNDED，失败或无法判定记 MANUAL_REVIEW。启动遇到不明确的 PENDING/WITHDRAWING 不重扣、不猜余额、不自动补发。`/br admin purchases` 为只读核查；本阶段没有自动解决不明确交易的命令。**外部经济与本库没有跨插件 ACID / exactly-once 购买保证。**

KILL_EFFECT、WIN_EFFECT、DEATHBOX_SKIN、LOBBY_EFFECT 每类最多装备一个；解锁永久保存，缺失定义保留 ownership 并回退默认视觉。提供粒子击杀、无伤闪电、胜利烟花、末影/金色死亡盒和大厅星光示例。比赛 roster 冻结外观，恢复使用本局快照；击杀使用 killer，DeathBox 使用 deceased，Team/tie 所有胜者均有庆祝。大厅效果为单一循环（每玩家 3 粒子/秒、总上限 300/次），加入房间/退出即停止。视觉不改变装备、伤害、碰撞、掉落、拾取距离或游戏优势。

未实现 Seasons、MMR 匹配、Party、NPC/Citizens、PlaceholderAPI 要求或任何付费战斗优势。M5 provenance 与 M6 公共 API 替身的既有限制继续适用。


## M9 管理与发布

管理入口：`/br admin map list`、`edit <map>`、`validate <map> [--deep]`、`pregenerate <map>`。预生成需要独立 `pregenerate commit <map> confirm`。`/br admin diagnose`、`/br debug perf`、`/br debug worlds`、`/br admin supportbundle` 提供维护信息。完整说明见 [ADMIN](ADMIN.md)、[MAPS](MAPS.md)、[COMMANDS](COMMANDS.md)、[CONFIGURATION](CONFIGURATION.md)、[RECOVERY](RECOVERY.md)、[ECONOMY](ECONOMY.md)。

升级前结束比赛/维护并完成玩家恢复，停服备份整个 Paper level、插件目录、数据库和模板；替换 JAR，检查 config v1→v2 迁移日志，再运行 config validate / diagnose。未知更高配置版本拒绝加载。无模板的首次安装允许核心启动，但地图不可开局。Paper 升级后的世界不能直接降级，回退须恢复完整旧备份。

`gradlew.bat stressTest` 是独立压力测试，不随普通 test/check 执行。`scripts/soak/paper-soak.mjs` 的历史验证使用隔离 Paper 1.21.8 与 10 个协议客户端，默认 20 轮 × 5 房间。当前 Mineflayer 4.39.0 / minecraft-data 3.117.0 / minecraft-protocol 1.68.0 最高支持 26.1，不能直接据此运行或宣称通过原生 26.2 协议回归。历史测试范围及实测数字见 [VERIFICATION](VERIFICATION.md)，不等同于当前版本大量真实玩家容量承诺。

构建同时生成 JAR 和 `.jar.sha256`；compileOnly Paper/经济 API 不进入生产 JAR。Gradle lockfile 固定解析的传递依赖版本；Paper API 固定为 `26.2.build.129-stable`，不再使用旧 `1.21.8-R0.1-SNAPSHOT` 坐标。所有 runtime 库均固定版本。
