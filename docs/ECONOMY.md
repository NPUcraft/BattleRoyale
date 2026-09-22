# 可选经济兼容性

M8 与 M9 已在真实 Paper 上验证 CoinsEngine 2.7.0、Vault 1.7.3（CoinsEngine 注册的后端）、ExcellentEconomy 2.8.0。旧 CoinsEngine adapter 限 2.7.x 公共 API，不声称 2.6.x 兼容；本次 2.7.0 环境安装 Vault，避免其默认货币初始化上游类缺失。

ExcellentEconomy 2.8.0 / nightcore 2.16.2 使用 Java 25 环境；LastSector 始终 target Java 21。编译采用官方 2.7.0 过渡 API。可选 adapter 按配置延迟检测，捕获 LinkageError（包括 UnsupportedClassVersionError/NoClassDefFoundError）及服务缺失。auto 按 auto-priority 查找下一可用 provider；显式指定 provider 不擅自切换另一币种来源。没有 provider 时付费购买关闭，免费外观和比赛继续。

[ExcellentEconomy 公共 API](https://nightexpressdev.com/excellenteconomy/utility/developer-api/)，[Vault API](https://github.com/MilkBowl/VaultAPI)。LastSector 不实现玩家货币账户；BigDecimal 金额在适配器边界严格检查精度，拒绝非有限或不可精确表达的值。

购买先持久记录意图再调用外部扣款；完成时事务保存 unlock/COMPLETED。失败补偿仅在账本状态确定时执行。跨插件无法实现通用 ACID，崩溃窗口中的扣款状态可能不明，此时进入 MANUAL_REVIEW，禁止猜测余额并重扣。`/ls admin purchases` 查看记录，`/ls admin diagnose` 显示完整待复核数量；管理员结合经济插件流水处理，不提供自动解决器。

M9 还实际验证了 Java 21 拒绝 ExcellentEconomy 2.8.0 后，auto-priority 回退到可用 Vault 后端，核心比赛继续运行；缺失全部经济插件时付费购买关闭。证据目录见 VERIFICATION.md。
