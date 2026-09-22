# 配置版本与文件

主配置集现在 config-version: 2：config.yml、rooms.yml、maps.yml、zones.yml、loadouts.yml、loot-tables.yml、lobby.yml、ranking.yml、cosmetics.yml。

无版本视作 legacy v1。启动先预检所有存在文件的语法/版本，再备份待迁移文件到 config-backups/<timestamp-uuid>/，逐文件原子替换版本化数据；失败回滚已写文件，备份保留。未知更高版本拒绝加载，绝不向下覆盖。跨多个配置文件与断电不是单一事务，但每个替换原子、每份原件有备份，重启可识别已完成版本并继续；YAML 序列化可能规范化格式和注释。

`/ls admin config validate` 读取全部配置与地图内容，不发布运行状态、不迁移、不保存；`backup` 保存主配置集（不包含数据库与模板）。地图编辑器统一元数据使用独立 format-version=1/revision。首次 Save 前仍兼容 map-data/<id>/loot.yml。

config.yml 控制存储、恢复、大厅世界、外观经济来源、Zone UI、战斗与离线窗口；rooms.yml 引用地图池/loadout/zone；maps.yml 指向模板和基础区域；zones.yml 定义人数分档及缩圈阶段；loadouts.yml 保存原生物品；loot-tables.yml 定义物品权重；ranking.yml 定义 Rating、Kill Score、周期时区与缓存；lobby.yml 定义菜单；cosmetics.yml 定义纯视觉外观和价格。

map-maintenance.pregeneration.max-concurrent-chunks 默认 8（执行时限制 1–32）。维护粒子、地面采样与生成总量均有硬上限。debug 默认 false。

请在停服状态编辑地图文件；在线变更通过内置编辑器，以 revision 原子发布。手改后通过 validate/reload 或重启重新加载；不能对正在运行的 Session 热修改规则。
