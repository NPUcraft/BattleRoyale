# 命令与权限

`/br` 为 `/battleroyale` 别名。帮助/版本允许控制台；需要位置、背包或 GUI 的命令只允许玩家，错误参数会返回说明。

- battleroyale.command：`help`、`version`。
- battleroyale.play：`rooms`、`join <room>`、`autojoin`、`vote`、`leave`、`team`、`spectate <room>`、`lobby`、`profile`、`leaderboard [rating|kill_score|wins|kills|assists|damage]`、`shop`、`cosmetics`。
- battleroyale.admin：`reload`、`admin loadout edit <room>`；`debug perf|tasks|worlds|storage|recovery|economy|rooms|maps`；`debug stats <online player>`；`debug session|zone|protection|loot|deathboxes|teams|offline|start|end <room>`。
- battleroyale.admin.map：`admin map list`；`info|edit|validate <map>`；`validate <map> --deep`；`pregenerate <map>`；`pregenerate cancel <map>`；`pregenerate commit <map> confirm`；编辑器内 `editor|save|discard|exit|spectator`、`area <minX> <maxX> <minZ> <maxZ>`、`name <display name>`、`ground <id|new> <table> <chance> <minSpawn> <maxSpawn> <attempts>`、`remove <id> confirm`。
- battleroyale.admin.config：`admin config validate|backup`。
- battleroyale.admin.cosmetic：`admin cosmetic grant|revoke <cached player> <cosmetic>`、`admin purchases`。
- battleroyale.admin.diagnostics：`admin diagnose`、`admin supportbundle`。

普通玩家默认获得 command/play；所有管理权限默认 OP，admin 继承管理子权限。GUI 动作同样检查编辑状态，不能靠背包窗口绕过命令权限。

reload 只允许无比赛、无维护/编辑和持久操作已完成时进行；数据库变更需重启。没有任意路径世界删除、强行覆写模板或自动解决不明购买命令。

## 开局区域投票（rc.11）

先用 `/br join <room>` 加入房间，再执行 `/br vote`，或在排队时右键指南针打开同一投票菜单。仅等待和倒计时阶段可投票，开始准备后截止。菜单按客户端语言显示，列出地图、候选区域名称、X/Z 范围、实时票数和自己的选择；候选较多时可翻页。

点击区域可投票或改票，每张地图最多保留自己的一票；“撤回我的投票”按钮撤回自己在本房间各地图的全部选票。退出房间会清票，重新加入不带回旧票。

区域投票不改变比赛地图抽选：选定地图后，只统计仍在房间中的玩家对该图的票数。最高票区域获选；平票时从并列最高者中随机选择，无人投票时从该图所有候选区域中随机选择，再在获选矩形内随机取初始圈心。矩形是圈心候选范围，不是安全区边界，也不锁定后续缩圈中心。

未配置命名区域时菜单会提示无候选，开局继续使用原有随机中心或旧版 `points` 规则。字段与边界校验见[配置说明](CONFIGURATION.md)。
