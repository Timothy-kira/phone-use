# MiniWoB++ 评测

**通过 85 / 92 项，通过率 92.39%，未通过 7 项。**

任务由 MiniMax Code CLI 执行，模型固定为 `minimax/MiniMax-M3.1-Flash-Preview`，思考强度为 **High**。每项独立模型会话与插件记忆，最多 30 个操作动作，批量操作逐项计数，模型阶段上限 10 分钟。

保留官方任务与原始奖励，严格以 `raw_reward == 1` 判定通过，小数奖励不计通过。模型自报完成不代替官方判分；模型操作期间无法访问评分内部状态。

插件观测包含截图、Android 无障碍树、文本输入与网页工具；部分任务附初始化录像和帧。此观测空间与官方标准协议不同。实际指令、初始画面及调度 seed 随题保留；Android 包未显式接收网页随机 seed，不能仅凭调度 seed 精确再生网页实例。本次属于初测，统计稳定性有限。

| 未通过任务 | 原始奖励 |
|---|---:|
| bisect-angle | -1.0 |
| circle-center | 0.9785725217822259 |
| find-midpoint | 0.9292893218813453 |
| highlight-text-2 | 0.0 |
| resize-textarea | 0.0 |
| right-angle | -1.0 |
| use-colorwheel-2 | 0.9477124183006536 |

缺失任务：无。

最终回复缺失：choose-date, circle-center, email-inbox-forward-nl-turk, login-user, simon-says, use-autocomplete。

CLI 用量不完整：choose-date, circle-center, email-inbox-forward-nl-turk, login-user, simon-says, use-autocomplete。终局后的 CLI 宽限时间结束可能造成这些字段缺失，均以原始记录为准。

[结果清单](RESULTS.csv) · [完整结果](RESULTS.json) · [逐题轨迹与下载](TRACE-INDEX.md) · [轨迹包说明](TRACE-PACKAGE.md)
