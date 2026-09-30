# 可选经济兼容性

当前源码为 BattleRoyale **1.0.0-rc.8**，面向 Paper 26.2 / Java 25。本轮不更换经济依赖、货币服务或账户规则；提交前正式服仍运行上一版 rc.7，不能据此宣称改名后的 rc.8 已上线。下文 rc.6/rc.7 的启动、余额和停服结果属于历史验证。本轮隔离结果见[验证记录](VERIFICATION.md)，最终工件与正式部署回执以对应 [GitHub release](https://github.com/NPUcraft/BattleRoyale/releases) 为准。

## rc.6：CoinsEngine 组合与兼容边界

此前 rc.6/rc.7 隔离环境确认以下组合能够启动和正常停止：**CoinsEngine 2.7.0 + nightcore 2.15.0 + Vault 1.7.3-b131 + PlaceholderAPI 2.12.3**，运行平台为 Paper 26.2-129 / Java 25。CoinsEngine 注册 `coins` 货币与占位符，关闭时完成数据库连接池清理。CoinsEngine 2.7.0 与 nightcore 2.16.6 的组合启动失败，不能因为 NightCore 版本更高就视为兼容替换。Vault 在该实测组合中保留；PlaceholderAPI 用于验证占位符集成，不是 BattleRoyale 核心比赛的必装依赖。

[CoinsEngine 2.7.0 官方发布页](https://modrinth.com/plugin/excellenteconomy/version/2.7.0)目前位于 ExcellentEconomy 项目下，属于重命名前的过渡版本，服务器内插件名仍为 CoinsEngine。该版本与 [NightCore 2.15.0](https://modrinth.com/plugin/nightcore/version/2.15.0)的官方游戏版本范围均为 **1.21.8–1.21.11**；在 Paper 26.2 启动时仍输出版本不支持警告。本项目的隔离启动/停服实测不能扩大上游支持范围，也不证明所有菜单、第三方插件组合或长期负载都兼容。

### PlaceholderAPI 停服顺序处理

CoinsEngine 和 nightcore 在 STARTUP 阶段加载，而 PlaceholderAPI 在 POSTWORLD 阶段加载；本次停服观察到 PlaceholderAPI 先关闭。原有 NightCore 清理路径会在 CoinsEngine 关闭时继续注销占位符，此时 PlaceholderAPI 管理器已经失效，导致上游空指针异常并中断 CoinsEngine 后续清理。

rc.6 的 `NightCoreShutdownCompat` 在 PlaceholderAPI 禁用事件的早期、其管理器仍可用时，调用公开 `PAPI.removeExpansions(coinsPlugin)`，提前注销 CoinsEngine 自有扩展；服务器正在正常停止时也有同一路径的保护。只在 CoinsEngine、nightcore 和 PlaceholderAPI 均启用时处理，不使用反射、不修改第三方 JAR、不影响其他插件的占位符扩展。若公共接口不兼容则捕获异常并记录警告，不声称能修复任意 NightCore/PlaceholderAPI 版本。

最终综合报告 `.run/paper-rc6-1790784404697/results.json` 为 `passed`，覆盖两次真实 Paper 启动。两次正常停服均观察到“CoinsEngine 占位符已在 PlaceholderAPI 停止前安全注销”，随后 CoinsEngine 连接池完成关闭，进程退出码均为 0，未出现插件异常。综合探针同时通过原生物资、固定落点空投、取消与恢复、大厅展板及重启保留等服务端检查。

经济检查只使用隔离合成账户：通过公共 API 设置余额 **123**，增加 **20**，扣除 **7**，首次读取为 **136**；正常停止后重新启动，再次读取仍为 **136**。该账户没有加入 BattleRoyale 玩家排行榜。此结果验证经济 API 与数据库持久化，不是实际外观商店购买、购买账本、退款或真人 GUI 点击的端到端验证，也不证明代理服网络回路。测试仅使用本地生成地形样本与专用平坦世界，不包含完整远端 Survival-Main；空投由探针推进会话时间，不能称为真人实时等待验收。进一步证据见 [验证记录](VERIFICATION.md)，正式上线状态见 [BattleRoyale 部署记录](BATTLEROYALE.md)。

### 提供者选择与余额迁移

BattleRoyale 通用默认仍为 `economy.provider: auto`，优先序 `[coinsengine, excellenteconomy, vault]`，货币 ID 为 `coins`。要固定使用本轮 CoinsEngine 组合，可显式配置 `economy.provider: coinsengine`，然后用 `/br admin diagnose` 检查实际提供者、币种和商店状态。显式提供者不可用时不会悄悄切换另一经济来源；缺少可用提供者只关闭付费商店，比赛和免费外观继续可用。

CoinsEngine 与 ExcellentEconomy 是不同版本的账户服务，BattleRoyale 不会通过改一个提供者名称自动迁移玩家余额。切换前应停服备份原插件配置及数据库，核对玩家 UUID、货币 ID、数据库列、小数位和初始余额规则，再单独核验余额保存及重启结果。`Start_Value: 100` 是部署用的新账户初始余额，不修改已有账户，也不是 BattleRoyale 自动赠币或付费渠道。现有余额的迁移结果必须以独立验证证据为准。

## 历史 rc.3：ExcellentEconomy 组合

以下为更名前 **1.0.0-rc.3** 的历史状态，不表示当前候选版本使用 ExcellentEconomy。2026-09-30 曾在本地隔离 Paper 26.2-129 服务器验证 ExcellentEconomy 2.8.0、nightcore 2.16.6、Vault 1.7.3-b131 的启动和控制台命令，当时提供者诊断为 `active=excellenteconomy`、`currency=coins`、`available=true`、`shopEnabled=true`。这不是 rc.8 工件的部署记录。

当时的默认配置下，仅安装 ExcellentEconomy 2.8.0 与 nightcore 2.16.6 会在加载货币时因缺少 `net.milkbowl.vault.economy.Economy` 而禁用 ExcellentEconomy。加入 [Vault 1.7.3 官方发布](https://github.com/MilkBowl/Vault/releases/tag/1.7.3) 后，`coins` 和 `money` 注册成功；将 Vault 主货币设为 `coins` 后，`vault-info` 确认经济提供者为金币。这是 rc.3 那轮实际验证的部署组合。

rc.3 那轮未执行真人联机、实际外观购买、扣款或退款探针，因此服务可用诊断不等于交易全流程验证。M8/M9 的购买测试属于旧平台证据，不能替代 Paper 26.2 的实际购买验证。本地依赖启动日志和配置清单位于 `.run/rc3-economy-check/`、`.run/battle-setup-rc3/dependency-config-manifest.json`；更多测试边界见 [VERIFICATION.md](VERIFICATION.md)。

rc.3 时期 BattleRoyale 使用以下部署设置；它们不是 BattleRoyale 通用默认值：

- BattleRoyale 显式使用 `excellenteconomy` 提供者和 `coins` 货币。
- ExcellentEconomy 的 `config.yml` 设置 `Integration.Vault.Enabled: true`、`Integration.Vault.EconomyCurrency: coins`。
- ExcellentEconomy 和 nightcore 的 `engine.yml` 均设置 `Plugin.Language: cn`。上游的 `zh` 会回退英文；部署包还补充了 `lang_cn.yml` 中较新的帮助、错误和按钮翻译。
- `currencies/coins.yml` 保留货币 ID 与 `Column_Name: coins`，显示名称、前缀和符号为“金币”，金额显示为“100 金币”，`Decimal: false`。
- `Start_Value: 100` 仅给新建货币账户提供体验余额，不改变已有余额，不代表 BattleRoyale 默认赠币，也没有接入付费渠道。

nightcore 首次启动会解析 HikariCP 6.3.2、fastutil-core 8.5.16 和 SLF4J 2.0.17 等 Maven 运行库。本地测试曾遇到默认 Google Maven 镜像连接重置；随后下载完成并保留了库缓存。部署应保留服务器 `libraries/` 的目录结构与解析元数据，或提供可访问的下载源。

## 通用适配器与交易安全

经济适配器编译采用官方 2.7.0 过渡 API。可选 adapter 按配置延迟检测，捕获 LinkageError（包括 UnsupportedClassVersionError/NoClassDefFoundError）及服务缺失。auto 按 auto-priority 查找下一可用 provider；显式指定 provider 不擅自切换另一币种来源。没有 provider 时付费购买关闭，免费外观和比赛继续。

[ExcellentEconomy 公共 API](https://nightexpressdev.com/excellenteconomy/utility/developer-api/)，[Vault API](https://github.com/MilkBowl/VaultAPI)。BattleRoyale 不实现玩家货币账户；BigDecimal 金额在适配器边界严格检查精度，拒绝非有限或不可精确表达的值。

购买先持久记录意图再调用外部扣款；完成时事务保存 unlock/COMPLETED。失败补偿仅在账本状态确定时执行。跨插件无法实现通用 ACID，崩溃窗口中的扣款状态可能不明，此时进入 MANUAL_REVIEW，禁止猜测余额并重扣。`/br admin purchases` 查看记录，`/br admin diagnose` 显示完整待复核数量；管理员结合经济插件流水处理，不提供自动解决器。

## 历史 M8/M9：Paper 1.21.8

M8 与 M9 已在真实 Paper 1.21.8 上验证 CoinsEngine 2.7.0、Vault 1.7.3（CoinsEngine 注册的后端）、ExcellentEconomy 2.8.0。旧 CoinsEngine adapter 限 2.7.x 公共 API，不声称 2.6.x 兼容；当时的 2.7.0 环境安装 Vault，避免其默认货币初始化上游类缺失。ExcellentEconomy 2.8.0 / nightcore 2.16.2 的历史测试使用 Java 25 环境；当前 BattleRoyale 也以 Java 25 为编译和运行目标。

历史 M9 还实际验证了 Java 21 拒绝 ExcellentEconomy 2.8.0 后，auto-priority 回退到可用 Vault 后端，核心比赛继续运行；缺失全部经济插件时付费购买关闭。原始证据保留在私有历史归档中；当前 rc.8 不支持 Java 21 运行。
