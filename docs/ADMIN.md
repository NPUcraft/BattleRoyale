# BattleRoyale 管理指南（1.0.0-rc.2）

BattleRoyale 面向 Paper 26.2 / Java 25。此版本为发布候选，不宣称 1.0 stable。将 `battleroyale-1.0.0-rc.2.jar` 放入 plugins 后启动；默认 SQLite 自动创建 V2 数据库。没有地图模板时核心仍启用，地图不可开局；运行 `/br admin diagnose` 查看问题。

## 首次配置

配置已加载的大厅世界名称；将已保存并关闭的 Paper 26.2 主世界维度复制到 `plugins/BattleRoyale/maps/<id>`，必须包含 `data/minecraft/world_gen_settings.dat` 及原有 region/entities/poi/data 内容。也可提供完整 26.2 存档，插件从其中的 `dimensions/minecraft/overworld` 读取主世界维度。仅含旧 `level.dat` 的模板不会自动升级：先在独立 Paper 26.2 环境升级备份副本，停服后导出。不要把正在加载的服务器世界当模板，也不要使用链接或 junction。配置 maps.yml、rooms.yml、zones.yml，检查每个引用地图能容纳 Room 最大 InitialZone。`/br admin map validate <id>` 后可进行深度校验。房间/Zone 规则、原生 loadout 编辑与 Loot 表语义见 README；`/br admin loadout edit <room>` 编辑原生物品。

比赛副本位于 `<level-directory>/dimensions/battleroyale_game/`，编辑器和维护副本位于 `<level-directory>/dimensions/battleroyale_maintenance/`；`level-directory` 由 Paper 的 `Server.getLevelDirectory()` 决定。旧 `runtime-worlds.directory` 仅为配置兼容而保留，不再控制这些路径。不要手动移动、替换或删除已加载的维度。

## 地图维护

`/br admin map edit <map>` 创建 EDITOR 副本，保存管理员原状态到现有持久恢复系统后发放工具。编辑器独立于 GameSession，关闭、退出和断线默认丢弃草稿。`/br admin map editor` 打开 Info / Validate / Save / Discard；详细工具与命令见 MAPS.md。编辑和维护期间新局跳过该图，现有比赛继续使用其元数据快照。

预生成完成后只进入待确认状态。检查进度，再执行 `/br admin map pregenerate commit <map> confirm`；不想提交则 `/br admin map pregenerate cancel <map>`。模板目录整体替换，保留旧目录备份；不会把 region 文件覆盖到原模板中。备份由管理员另行归档，插件不提供任意路径删除命令。

## 数据库与经济

SQLite 为默认；MySQL 使用独立库/账号，驱动包含在 JAR。更换存储配置需要重启。先备份数据库再升级；不要在运行中复制 SQLite 单个 DB 文件而遗漏 WAL。使用 SQLite 在线备份机制，或停服后备份整个数据目录。经济为可选，参见 ECONOMY.md。`/br admin purchases` 只读列出待人工复核购买，不自动修正不明扣款。

## 诊断与支持

`/br admin diagnose` 汇总 OK/WARN/ERROR；缺失经济通常为 WARN。`/br debug perf` 给出纳秒计时、队列/缓存/资源数量；`/br debug worlds` 在后台扫描自有 GAME/EDITOR/MAINTENANCE 世界，显示加载状态、Session、路径与孤儿标记；`/br debug tasks` 显示任务总数及资源指标。

`/br admin supportbundle` 输出 support/*.zip。包由明确条目构成，不打包目录，不包含玩家背包、末影箱、聊天、余额、原始恢复 payload 或全局 server log；配置按结构脱敏。errors.txt 记录独立组件失败。管理员分享前仍应检查地图与房间名称等站点信息。

## 权限

默认普通玩家：battleroyale.command（帮助/版本）、battleroyale.play（房间、组队信息、观战、大厅、档案、榜单、商店）。观战仍受房间允许外部观战规则约束。

默认 OP：battleroyale.admin（reload、debug、loadout；继承全部管理子权限）、battleroyale.admin.map（地图编辑/校验/预生成）、battleroyale.admin.config（配置校验/备份）、battleroyale.admin.cosmetic（grant/revoke/purchases）、battleroyale.admin.diagnostics（diagnose/supportbundle）。可单独授予子权限；详细命令见 COMMANDS.md。

## 升级与回退

1. 先结束所有比赛并退出地图编辑/预生成，确认原始玩家状态已恢复；停服并备份完整 Paper level 目录、插件目录、数据库和地图模板。
2. 替换 JAR，核对发布的 SHA-256。
3. 启动，审查配置迁移、存储 schema 与恢复日志。
4. 运行 `/br admin config validate` 与 `/br admin diagnose`，先验证测试房间。

无 config-version 的 M1–M8 文件作为 v1，备份到 config-backups 后迁移到 v2。未知更高版本拒绝加载且不覆盖。YAML 输出可能规范化注释/格式，原文件备份保留。1.21.8 的进行中比赛和旧 runtime 目录不在新维度布局下继续恢复，也不自动接管或删除。Paper 升级后的世界不能直接降级；回退应恢复同一时间点整套服务器世界、配置、数据库和模板备份。
