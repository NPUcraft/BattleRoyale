# 恢复边界

M7–M9 使用 V2 SQLite/MySQL 存储会话和原始玩家状态。恢复包括 RUNNING/ENDING、队伍、Zone 与剩余时间、战斗归因、已存在 DeathBox/离线身体、玩家物品状态、名次/冻结外观和 M9 地图元数据快照。停机不消耗持久游戏计时。已完成 Loot 不重新生成。

比赛结果通过带 checksum 的本地 outbox 与 Session 唯一键事务结算；重试不重复 wins、Rating、Kill Score 或周期统计。旧 M7 快照没有 M8 战绩资料时不猜测补发。

世界 region/entity 文件、playerdata、SQL 和 outbox 不是跨存储 ACID。最近检查点和世界保存之后仍有丢失窗口；不要宣称零损失。坏 checksum/不兼容规则安全弃局，原状态仍保留待恢复。孤儿 GAME 世界默认等待配置时间，只有可信 marker、无加载/引用且满足年龄条件才清理。

EDITOR/MAINTENANCE 使用单独目录和 marker 类型，不参与游戏恢复。编辑器不恢复草稿；未提交预生成不会在启动时自动成为模板。模板替换 journal 在维护孤儿清理前处理；回滚失败时保留并告警。未标记目录、链接或身份不符目录不自动删除。

断线管理员的原状态使用既有持久恢复通道，因此崩溃不依赖内存中的 editor session。管理员应备份整套数据，并通过 diagnose 检查 pending restore、orphan 和 storage degraded。
