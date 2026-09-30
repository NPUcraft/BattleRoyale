# 地图制作与维护

模板世界位于插件数据目录的 maps/<id>，只读用于复制。maps.yml 保存基础路径/名称/二维 playable-area；map-data/<id>/loot.yml 是旧版容器点和地面区域配置。第一次编辑保存后，map-data/<id>/metadata.json 成为该地图区域、名称、Loot 与 spectator 的统一权威文件。该文件具有 format-version=1、递增 revision；不存在时继续读取旧配置，不覆盖旧 loot.yml。

当前运行平台为 Paper 26.2 / Java 25。模板可以是导出的主世界维度目录（含 `data/minecraft/world_gen_settings.dat`），或含 `dimensions/minecraft/overworld` 的完整 26.2 存档。插件只复制所选主世界维度，保留其 seed/generator；完整存档中的其他维度和全局 datapack 不会一同安装到服务器。依赖自定义生成器或 datapack 的地图需在目标服务器另行配置相同依赖。旧版 `level.dat` 模板必须先在隔离环境升级并停服导出，插件不会修改原模板来升级格式。

比赛副本写入 `<level-directory>/dimensions/battleroyale_game/<leaf>`；编辑与维护副本写入 `<level-directory>/dimensions/battleroyale_maintenance/<leaf>`。`level-directory` 是 Paper `Server.getLevelDirectory()`，`leaf` 包含清理后的 room 标识和完整 Session UUID。世界 key 分别使用 `battleroyale_game:<leaf>` 与 `battleroyale_maintenance:<leaf>`。旧 `runtime-worlds.directory` 不再选择实际路径。

## 编辑器

`/br admin map edit <map>` 获取同图唯一维护锁，复制模板到 `battleroyale_maintenance` 维度 namespace，marker type=EDITOR，包含 mapId/editorUUID/createdAt 和布局身份；不会创建 Room 或 GameSession。管理员背包/末影箱/经验等通过现有持久状态隔离流程保存。编辑器禁止挖掘、放置、掉落、拾取、换手和容器物品操作；建筑制作仍在模板制作环境中完成。

工具使用 PDC 标识：

- area：左键第一角、右键第二角，仅 X/Z；显示宽、深、中心。
- container：右键支持的 Container，GUI 选 LootTable；同一块再次操作保留点 ID。Remove 需要二次确认，只删元数据。
- ground：两角包含 Y，之后执行 `/br admin map ground new <table> <chance> <minSpawn> <maxSpawn> <attempts>`；用已有 ID 可替换该区域。
- spectator：右键保存当前位置、yaw/pitch；不替代优先队友观战目标。
- inspect：检查边界、命中容器点/区域和校验信息。
- menu：打开编辑器菜单。

命令也可设置 `/br admin map area <minX> <maxX> <minZ> <maxZ>`、`spectator`、`name <display name>`、`remove <id> confirm`。粒子只画附近边界/角点，每名编辑者每轮最多 64，所有编辑者合计最多 256，每秒一次。

Save 对整个草稿验证，原子替换统一 metadata.json，先备份旧元数据，最多保留五份；revision 冲突拒绝保存。无效草稿可继续编辑但不能保存。Save 不退出；Exit/Discard 放弃未保存变化，恢复管理员，卸载并删除拥有的副本。断线、失败或禁用默认丢弃。崩溃后不恢复编辑会话；有可信 marker 的维护孤儿安全清理，管理员持久原状态单独恢复。

## 校验

`/br admin map list`、`info <map>`、`validate <map>`、`validate <map> --deep`。

离线检查路径、维度的 `data/minecraft/world_gen_settings.dat`、区域、Room/Zone 尺寸、LootTable 引用、重复 ID/坐标、边界与 spectator。深度检查创建短期副本，检查真实容器、spectator 高度/chunk，以及每个地面区域最多 8 个地表采样；不会扫描整个体积。ERROR 禁止新局选图，WARNING 保留可用，UTF-8 报告写 reports/。单图错误隔离，不使其他地图或核心禁用。

## 预生成

`/br admin map pregenerate <map>` 使用 MAINTENANCE 副本。仅请求 playable-area 覆盖的 chunk，每图并发默认 8，可配置为 1–32；总量上限 1,000,000。每 5% 显示进度/耗时。Paper 公共异步 chunk API 控制生成速度；最终保存/卸载世界仍是 Paper 主线程操作，不能保证大世界提交无停顿。

完成后 `/br admin map pregenerate commit <map> confirm` 才提交。取消不会提交。受控目录替换前持久写入 journal，旧模板移动到 .backup-UUID；失败尝试回滚，启动时先处理未完成日志，未确认的生成不会自动提交。不能证明安全时保留文件并拒绝操作。备份不自动删除。

完整存档模板只替换其中的 `dimensions/minecraft/overworld`，备份位于该维度旁；直接导出的维度模板则替换模板根目录。模板和维护副本必须支持同文件系统原子目录移动，不能跨文件系统复制作为降级方案。旧布局留下的维护 journal 需要停服人工核查，不自动转换。

比赛创建时冻结 MapTemplate 与完整 metadata revision；编辑 Save 只供新比赛选取。恢复快照也携带旧元数据，避免重启时替换已有比赛规则。
