# 命令与权限

`/ls` 为 `/lastsector` 别名。帮助/版本允许控制台；需要位置、背包或 GUI 的命令只允许玩家，错误参数会返回说明。

- lastsector.command：`help`、`version`。
- lastsector.play：`rooms`、`join <room>`、`autojoin`、`leave`、`team`、`spectate <room>`、`lobby`、`profile`、`leaderboard [rating|kill_score|wins|kills|assists|damage]`、`shop`、`cosmetics`。
- lastsector.admin：`reload`、`admin loadout edit <room>`；`debug perf|tasks|worlds|storage|recovery|economy|rooms|maps`；`debug stats <online player>`；`debug session|zone|protection|loot|deathboxes|teams|offline|start|end <room>`。
- lastsector.admin.map：`admin map list`；`info|edit|validate <map>`；`validate <map> --deep`；`pregenerate <map>`；`pregenerate cancel <map>`；`pregenerate commit <map> confirm`；编辑器内 `editor|save|discard|exit|spectator`、`area <minX> <maxX> <minZ> <maxZ>`、`name <display name>`、`ground <id|new> <table> <chance> <minSpawn> <maxSpawn> <attempts>`、`remove <id> confirm`。
- lastsector.admin.config：`admin config validate|backup`。
- lastsector.admin.cosmetic：`admin cosmetic grant|revoke <cached player> <cosmetic>`、`admin purchases`。
- lastsector.admin.diagnostics：`admin diagnose`、`admin supportbundle`。

普通玩家默认获得 command/play；所有管理权限默认 OP，admin 继承管理子权限。GUI 动作同样检查编辑状态，不能靠背包窗口绕过命令权限。

reload 只允许无比赛、无维护/编辑和持久操作已完成时进行；数据库变更需重启。没有任意路径世界删除、强行覆写模板或自动解决不明购买命令。
