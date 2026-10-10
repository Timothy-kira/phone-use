# AndroidWorld 结果汇总

**113 / 116 题通过，97.41%。** 逐题官方奖励值见 [RESULTS.json](RESULTS.json) 或 [RESULTS.csv](RESULTS.csv)。原始轨迹和运行资料本地存档，可供复现审核。

| 未通过任务 | 已确认的问题 | Benchmark 判断 |
| --- | --- | --- |
| ExpenseAddMultipleFromMarkor | 记录的备注、时间等业务条件不匹配。 | 没有官方判分错误的证据。 |
| MarkorMergeNotes | 合并文本丢失末尾换行，64 字符而预期 65。 | 精确文本操作和验证问题。 |
| OsmAndTrack | 未补齐后续途经点，没有保存轨迹，官方奖励 0。官方离线地图检查通过。 | 没有官方判分错误的证据。 |

任务由 MiniMax Code CLI / MiniMax-M3.1-Flash-Preview 在 AndroidWorld 模拟器上执行。判分条件未修改，基准运行关闭跨任务记忆。

[完整轨迹压缩包及说明](TRACE-PACKAGE.md) · [逐题轨迹索引](TRACE-INDEX.json)
