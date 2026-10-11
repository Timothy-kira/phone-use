# Phone Use

Phone Use 插件与 AndroidWorld 审计档案。

默认分支 `插件` 保存可安装插件源码及 APK；`audit-reports` 分支保存结果汇总和完整评测轨迹。使用说明见 [Phone Use](docs/PHONE-USE.md)。

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

已提供覆盖全部 116 道题的完整评测轨迹：113 道通过题目的轨迹，以及剩余三题各自最新且已有官方评分的失败轨迹。包含完整调用、工具回执、截图、模型总结和官方评分；不含录像，版本号和哈希已脱敏。

[下载完整轨迹压缩包](https://github.com/Timothy-kira/phone-use/raw/refs/heads/audit-reports/audit/androidworld-traces.zip) · [轨迹包说明](https://github.com/Timothy-kira/phone-use/blob/audit-reports/audit/TRACE-PACKAGE.md) · [逐题索引](https://github.com/Timothy-kira/phone-use/blob/audit-reports/audit/TRACE-INDEX.json)

## MiniWoB++ 评测

**MiniWoB++：85/92，通过率 92.39%，未通过 7 项。**

使用 **MiniMax Code CLI / MiniMax-M3.1-Flash-Preview**，思考强度 **High**。官方原始奖励等于 1 才计通过。插件提供截图、Android 无障碍树、文本输入与网页工具，观测空间与官方标准协议不同。

已提供全部 92 项任务的独立轨迹压缩包，包含成功及失败记录、完整工具调用与回执、截图、已有录像、诊断和官方奖励；未生成的最终回复或用量在索引中标明。

[评测报告](audit/miniwob/REPORT.md) · [92 项结果清单](audit/miniwob/RESULTS.csv) · [逐题轨迹与下载](audit/miniwob/TRACE-INDEX.md) · [轨迹包说明](audit/miniwob/TRACE-PACKAGE.md)
