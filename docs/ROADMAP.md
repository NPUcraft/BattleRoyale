# Roadmap

- **M1 Foundation — completed**：Java 21 / Paper 1.21.8、配置和模型、注册表、Provider 接口、基础命令与原始 59 项测试。
- **M2 Room & World — completed**：成员、独立倒计时、唯一 Session、选图、异步复制、安全加载/卸载/删除、多房间与取消/禁用。
- **M3 Game Start & Zone — completed**：严格圈配置及地图容量验证、按实际人数随机 InitialZone、安全间隔出生、有限异步区块准备、同 tick 传送、单调时间连续缩圈、保护期、圈外真实伤害、局部粒子墙和 BossBar、debug zone、故障回滚与真实 Paper 验证。旧 500 → 700 配置冲突已解决，默认目标 400/250/125/50。
- **M4 Loadout & Loot & World Rules — completed**：原生物品字节、共享 Loadout 虚拟编辑器与原子保存、玩家状态快照和同 JVM 离线恢复、首次区块/实体清理、容器与地面物资、InitialZone 筛选、一次性生成、世界 UUID 传送门规则。
- **M5 Combat & DeathBox — completed**：共享 M3 来源、15 秒归因和助攻、幂等淘汰、实际库存死亡盒、精确 Stored XP Bottle、Solo tick 末胜负/平局和 60 秒 ENDING 展示。
- **M6 Team, Spectator & OfflineBody — completed**：均衡自动队伍、固定成员、共享友伤策略、Team winner/tie、死亡与外部观战、公共 API 替身、120 秒重连/超时、原背包隔离和清理。仅预留 Party 输入接口，第三方集成后续实现。
- **M7 Database & Recovery — completed**：异步 SQLite/MySQL、版本/checksum/revision、原状态提交屏障、RUNNING/ENDING 崩溃恢复、暂停比赛计时、租约、autosave 与延迟孤儿清理。正常停服结束比赛。
- **M8 Lobby, Economy, Cosmetics & Ranking — completed（当前）**：canonical 大厅、异步永久档案、幂等结果/outbox、Team placement Rating、Kill Score、历史日/周/月榜、CoinsEngine/ExcellentEconomy/Vault、永久外观和购买审计。
- **M9 Admin Tools, Map Editing, Diagnostics, Stress Tests & Release Hardening — next**：管理员地图编辑、诊断、规模压力和发布加固。

M6 Solo/Duo/Squad 共用 Team Outcome 和完整比赛生命周期；在线死亡与离线替身淘汰共用一次性 DeathBox。比赛内重连已实现；数据库与进程崩溃恢复已在 M7 实现；永久档案/统计/Rating 与经济外观业务已在 M8 实现；跨插件购买不确定状态保留 MANUAL_REVIEW，人工处理工具后续完善。
