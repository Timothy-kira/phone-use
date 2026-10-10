# Phone Use

Phone Use 插件与 AndroidWorld 审计档案。当前插件版本 **0.8.25**。

默认分支 `插件` 保存可安装插件源码及 APK；`audit-reports` 分支保存结果汇总。使用说明见 [Phone Use](docs/PHONE-USE.md)。

## AndroidWorld 评测

**累计通过：113 / 116。** 任务由 **MiniMax Code CLI / MiniMax-M3.1-Flash-Preview** 在 AndroidWorld 模拟器上执行。

| 未通过任务 | 失败原因 | Benchmark 判断 |
| --- | --- | --- |
| ExpenseAddMultipleFromMarkor | 记录已保存，但备注、时间等业务条件不匹配。 | 暂无官方评分错误的证据。 |
| MarkorMergeNotes | 合并文本丢失末尾换行，实际 64 字符，预期 65 字符。 | 属于精确文本操作与验证问题。 |
| OsmAndTrack | 只完成两个地点，未补齐后续途经点，也未保存轨迹，官方评分 0.0。 | 官方离线地图校验通过，暂无评分错误的证据。 |

判分条件未修改。基准运行关闭跨任务记忆，采用独立任务配置，并在模型操作期间封存评分状态。

官方 [OsmAnd 历史修复](https://github.com/google-research/android_world/commit/ab451217332ae43fe22f9fc4af783a8da80db86f) 已包含在本地评分代码中。

[评测结果汇总](https://github.com/Timothy-kira/phone-use/tree/audit-reports/audit)
