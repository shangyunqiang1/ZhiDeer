# 首版实施状态

规格：[#1](https://github.com/shangyunqiang1/ZhiDeer/issues/1)。集成分支：`codex/mvp-integration`，起点 `c594097168f0aea29a3ad3a2a16205429af28586`。状态日期：2026-10-25（Asia/Shanghai）。

2026-10-25：[#2](https://github.com/shangyunqiang1/ZhiDeer/issues/2) 验收条件已满足——固定 MyBrain 提交真实构建通过、持久化写入重读探针通过、工程基线确定为独立实现；issue 待关闭。[#3](https://github.com/shangyunqiang1/ZhiDeer/issues/3) 设备信息已核验但运行能力未验证，且早期证据 JSON 因 Temp 清理丢失需重采。无产品代码提交；探针与全部工具位于仓库外 `G:/ZhiDeer-tools`。

工程证据见 [T01](t01-engineering-baseline.md)，设备准备与证据见 [T02](t02-oppo-device.md)。目标手机 OPPO Reno10 5G 已通过 USB 调试授权，实际读取型号代码 PHW110、Android 15/API 35、ColorOS V15.0.0；通知、语音与系统时钟的运行能力仍待验证。没有已交付的产品 APK。

## 任务依赖快照

以下依据本轮读取的 GitHub Issues 正文；后续以远端最新状态和依赖为准。

| Ticket | 目标 | 前置任务 | 本地状态 |
| --- | --- | --- | --- |
| [#2](https://github.com/shangyunqiang1/ZhiDeer/issues/2) | [T01] 验证并确定工程基线 | 无 | 验证完成（2026-10-25）：构建通过+探针通过+基线=独立实现；待关闭 |
| [#3](https://github.com/shangyunqiang1/ZhiDeer/issues/3) | [T02] 验证 OPPO 设备能力 | 无 | 验证中，未完成 |
| [#4](https://github.com/shangyunqiang1/ZhiDeer/issues/4) | [T03] 离线管理单次待办 | #2 | 未启动 |
| [#5](https://github.com/shangyunqiang1/ZhiDeer/issues/5) | [T04] 管理本地日历安排 | #2 | 未启动 |
| [#6](https://github.com/shangyunqiang1/ZhiDeer/issues/6) | [T05] 记录和检索便签 | #2 | 未启动 |
| [#7](https://github.com/shangyunqiang1/ZhiDeer/issues/7) | [T06] 为待办安排执行时段 | #4、#5 | 未启动 |
| [#8](https://github.com/shangyunqiang1/ZhiDeer/issues/8) | [T07] 管理父待办和子任务 | #4 | 未启动 |
| [#9](https://github.com/shangyunqiang1/ZhiDeer/issues/9) | [T08] 为待办设置本地提醒 | #3、#4 | 未启动 |
| [#10](https://github.com/shangyunqiang1/ZhiDeer/issues/10) | [T09] 为日程设置并调整提醒 | #5、#9 | 未启动 |
| [#11](https://github.com/shangyunqiang1/ZhiDeer/issues/11) | [T10] 恢复设备变化后的提醒 | #9 | 未启动 |
| [#12](https://github.com/shangyunqiang1/ZhiDeer/issues/12) | [T11] 联动处理事项状态和提醒 | #7、#8、#10 | 未启动 |
| [#13](https://github.com/shangyunqiang1/ZhiDeer/issues/13) | [T12] 执行每日和每周重复待办 | #9 | 未启动 |
| [#14](https://github.com/shangyunqiang1/ZhiDeer/issues/14) | [T13] 执行每月重复待办 | #13 | 未启动 |
| [#15](https://github.com/shangyunqiang1/ZhiDeer/issues/15) | [T14] 调整重复待办与例外日期 | #13 | 未启动 |
| [#16](https://github.com/shangyunqiang1/ZhiDeer/issues/16) | [T15] 重复执行子任务步骤 | #8、#15 | 未启动 |
| [#17](https://github.com/shangyunqiang1/ZhiDeer/issues/17) | [T16] 恢复及彻底删除回收站事项 | #6、#12 | 未启动 |
| [#18](https://github.com/shangyunqiang1/ZhiDeer/issues/18) | [T17] 配置 DeepSeek 并进行中文对话 | #2 | 未启动 |
| [#19](https://github.com/shangyunqiang1/ZhiDeer/issues/19) | [T18] 授权上下文并查询真实事项 | #4、#5、#6、#18 | 未启动 |
| [#20](https://github.com/shangyunqiang1/ZhiDeer/issues/20) | [T19] 通过 AI 确认创建便签和待办 | #19 | 未启动 |
| [#21](https://github.com/shangyunqiang1/ZhiDeer/issues/21) | [T20] 通过 AI 创建日程和提醒 | #10、#20 | 未启动 |
| [#22](https://github.com/shangyunqiang1/ZhiDeer/issues/22) | [T21] 通过 AI 修改和删除事项 | #17、#21 | 未启动 |
| [#23](https://github.com/shangyunqiang1/ZhiDeer/issues/23) | [T22] 通过 AI 管理重复及父子待办 | #14、#16、#22 | 未启动 |
| [#24](https://github.com/shangyunqiang1/ZhiDeer/issues/24) | [T23] 语音输入与中文播报 | #3、#18 | 未启动 |
| [#25](https://github.com/shangyunqiang1/ZhiDeer/issues/25) | [T24] 交接系统时钟并核对状态 | #2、#3 | 未启动 |
| [#26](https://github.com/shangyunqiang1/ZhiDeer/issues/26) | [T25] 通过 AI 提出系统闹钟操作 | #21、#25 | 未启动 |
| [#27](https://github.com/shangyunqiang1/ZhiDeer/issues/27) | [T26] 手动保存和管理长期记忆 | #6、#18 | 未启动 |
| [#28](https://github.com/shangyunqiang1/ZhiDeer/issues/28) | [T27] 落实记忆与来源的删除边界 | #17、#19、#27 | 未启动 |
| [#29](https://github.com/shangyunqiang1/ZhiDeer/issues/29) | [T28] 在聊天中记住和检索偏好 | #20、#28 | 未启动 |
| [#30](https://github.com/shangyunqiang1/ZhiDeer/issues/30) | [T29] 主动生成有依据的参谋建议 | #7、#29 | 未启动 |
| [#31](https://github.com/shangyunqiang1/ZhiDeer/issues/31) | [T30] 确认采纳建议并拒绝过期操作 | #22、#30 | 未启动 |
| [#32](https://github.com/shangyunqiang1/ZhiDeer/issues/32) | [T31] 导出手动加密备份 | #4、#5、#6、#18 | 未启动 |
| [#33](https://github.com/shangyunqiang1/ZhiDeer/issues/33) | [T32] 核对备份并整库恢复 | #9、#19、#32 | 未启动 |
| [#34](https://github.com/shangyunqiang1/ZhiDeer/issues/34) | [T33] 完整备份恢复记忆和建议 | #31、#33 | 未启动 |
| [#35](https://github.com/shangyunqiang1/ZhiDeer/issues/35) | [T34] 汇总今天的真实安排 | #30 | 未启动 |
| [#36](https://github.com/shangyunqiang1/ZhiDeer/issues/36) | [T35] 查看权限和故障诊断 | #11、#24、#25、#29 | 未启动 |
| [#37](https://github.com/shangyunqiang1/ZhiDeer/issues/37) | [T36] 完成首版 OPPO 集成验收 | #23、#26、#34、#35、#36 | 未启动 |

## 继续执行

1. [#2](https://github.com/shangyunqiang1/ZhiDeer/issues/2) 验收条件已满足（2026-10-25，证据见 [T01](t01-engineering-baseline.md) 末章）：固定提交真实构建 BUILD SUCCESSFUL、文件级 Room 写入→关闭→重开→重读探针 PASSED、androidHostTest 62/62、工程基线确定为独立实现。待关闭（gh 无凭据，需用户 `gh auth login` 或手动关闭）。下游 #4/#5/#6/#18/#25 据此解锁。
2. OPPO 已授权 USB 调试；设备证据 JSON 因 Temp 清理丢失，实机重连后重新采集，并以独立 probe APK 完成通知、精确提醒、重启恢复、语音与系统时钟的运行验证（[T02](t02-oppo-device.md)）。
3. 仅当相应前置任务真正完成后推进依赖图；实现使用统一业务用例入口、真实持久化及受控平台适配器验证，交付时另补必要实机证据。
4. 每个实现单元在独立 worktree 中基于集成分支执行，合并前同步集成分支；审查并验证后再按任务跟踪约定关闭已满足验收条件的任务。

