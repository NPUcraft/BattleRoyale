# Roadmap

- **M1 Foundation — completed**：Java 21 / Paper 1.21.8、配置和模型、注册表、Provider 接口、基础命令与原始 59 项测试。
- **M2 Room & World — completed**：成员、独立倒计时、唯一 Session、选图、异步复制、安全加载/卸载/删除、多房间与取消/禁用。
- **M3 Game Start & Zone — completed（当前）**：严格圈配置及地图容量验证、按实际人数随机 InitialZone、安全间隔出生、有限异步区块准备、同 tick 传送、单调时间连续缩圈、保护期、圈外真实伤害、局部粒子墙和 BossBar、debug zone、故障回滚与真实 Paper 验证。旧 500 → 700 配置冲突已解决，默认目标 400/250/125/50。
- **M4 Loadout & Loot & World Rules — deferred**：装备和物品适配、LootPoint/LootArea/LootTable、容器生成与整理、物品隔离、世界交互规则。复用不可变 InitialZone。
- M5 Combat & DeathBox：完整伤害归因、助攻、淘汰、胜者、死亡盒与经验保留。M3 PvPHazardTracker 仅服务短期保护，不是完整战斗归因。
- M6 Team, Spectator & OfflineBody：Party、组队、友伤、观战、断线替身与重连。
- M7 Database & Recovery：SQLite/MySQL、永久数据与恢复数据分离、异常恢复、孤儿世界处理、autosave/停服保留策略。
- M8 Lobby, Economy, Cosmetics & Ranking：大厅、CoinsEngine/Vault、公平外观、积分和榜单。
- M9 Admin Tools, Stress Tests & Release：地图/装备工具、大图多房压力测试、故障演练和发布。

M3 RUNNING 已运行安全区与伤害，但不代表完整比赛完成：仍由 debug end 结束，没有统一死亡/淘汰或胜负判定。

