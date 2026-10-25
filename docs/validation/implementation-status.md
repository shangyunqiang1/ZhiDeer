# 首版实施状态（2026-10-25 22:45 更新）

规格：[#1](https://github.com/shangyunqiang1/ZhiDeer/issues/1)。集成分支：`codex/mvp-integration`，起点 `c594097`，当前 tip 见 `git log`。状态日期：2026-10-25（Asia/Shanghai）。
本文件是快照，**以 GitHub Issues 的开放/关闭状态与验收评论为准**。

## 总览

- 37 个 issue 中 **27 个已关闭**；开放：#1（规格）、#3、#11、#18、#24、#25、#26、#31、#36、#37。
- 工程：`android-app/` 单模块 Kotlin + Compose + Room（schema v15，含 15 个 schema JSON）、手动 DI。
- 测试：集成分支全量单测 **367 个 / 26 个测试类 / 0 失败**，`:app:assembleDebug` 通过（日志 `G:/ZhiDeer-tools/logs/build-integration.log`）。
- 设备：OPPO Reno10 5G（PHW110，Android 15 / API 35，ColorOS V15.0.0，Asia/Shanghai）。

## 已关闭（27）

| Ticket | 目标 | 证据 |
|---|---|---|
| #2 | 工程基线 | 构建 + 持久化探针通过，结论=独立实现（`docs/validation/t01-engineering-baseline.md`） |
| #4 / #5 / #6 | 待办 / 日程 / 便签 | 单测 + 模拟器冒烟 |
| #7 / #8 | 待办×日程关联 / 父子与子任务 | 单测（`TaskScheduleLinkUseCasesTest`、`SubTaskUseCasesTest`） |
| #9 / #10 | 待办提醒 / 日程提醒 | 实机产品级投递证据（`docs/validation/t02-oppo-device.md` 2026-10-25 晚章节） |
| #12–#17 | 状态联动 / 重复 / 例外 / 步骤 / 回收站 | 单测（`InterlockUseCasesTest`、`RecurrenceUseCasesTest`、`OccurrenceSubTaskUseCasesTest`、`RecycleBinUseCasesTest`） |
| #19–#22 | 授权上下文 / AI 创建与修改 | 单测 + MockWebServer |
| #23 | AI 管理重复及父子待办 | 合并 `ba0543f`，新增 10 例端到端测试 |
| #27–#29 | 长期记忆 / 删除边界 / 聊天记忆 | 单测（`MemoryUseCasesTest`、`AiMemoryTest`） |
| #30 | 参谋建议 | 单测（`AdvisorUseCasesTest`） |
| #31 | 采纳建议与拒绝过期操作 | 合并 `8f9029b`，新增 15 例（UI 实机走查待补） |
| #32 / #33 | 加密备份导出 / 核对并整库恢复 | 单测（`BackupUseCasesTest`） |
| #34 | 完整备份恢复记忆与建议 | 合并 `f3874a2`，新增 7 例 |
| #35 | 首页真实数据仪表盘与五底栏 | 单测 + 设备截图 |

## 开放与推进状态（10）

| Ticket | 状态 | 说明 |
|---|---|---|
| #1 规格 | 待全部子任务关闭后关闭 | — |
| #3 设备能力 | **部分实证** | D01/D02/D04/D09/D10 有运行证据；D03 重启恢复真机已验（见 `docs/validation/t10-restore.md`），D05 改时间/时区、D06/D07 麦克风与识别、D08 TTS 待本轮补齐 |
| #11 恢复设备变化后的提醒 | **实现已合并**；真机 D1/D2 已通过 | 待 D3/D4/D5/D7 真机步骤后关闭；实测 ColorOS 默认拦截第三方 BOOT_COMPLETED，靠应用启动兜底 |
| #18 DeepSeek 配置与对话 | 代码与 Mock 测试就绪 | **等用户在小鹿页填入 Key** 后做真实连接证据 |
| #24 语音输入与中文播报 | **实现已合并** `9260f55`；真机验收进行中 | 无识别服务→键盘回退；TTS 默认关闭 |
| #25 系统时钟交接 | 实现完成并合并 `6943036` | 真机 D09 + 模拟器 D10 无 handler 证据齐；待 #3 关闭 |
| #26 AI 系统闹钟提案 | 实现中（独立工作树） | 依赖 #25 代码（已合并） |
| #31 采纳建议与拒绝过期 | 实现已合并 `8f9029b` | 代码与测试齐；UI 实机走查待补后关闭 |
| #36 权限与故障诊断 | 实现中（独立工作树） | 复用 #11 能力探针 / #24 语音 / #18 连接测试 |
| #37 首版 OPPO 集成验收 | 待 #23/#26/#34/#35/#36 全绿后执行 | 映射 AT-01~AT-12 |

## 环境与工具（摘要，详见 `G:/ZhiDeer-tools/handoff-20261025.md`）

- 构建：`G:/ZhiDeer-tools/run-build-worktree.ps1 -Root <仓库或工作树>`，日志 `G:/ZhiDeer-tools/logs/build-<name>.log`。
- 工作树：`git -c safe.directory=* -C G:/ZhiDeer worktree add G:/ZhiDeer-worktrees/<name> -b <branch> codex/mvp-integration`。
- 证据：`G:/ZhiDeer-tools/evidence/device/`（截图、`dumpsys`、`logcat events` 摘要）。
- 已知：AGP 9.3 自带 Kotlin（勿加 kotlin-android 插件）；Robolectric 测试用 `AndroidSQLiteDriver`；C 盘易满，临时文件已重定向到 G 盘；并行构建时 MockWebServer 偶发 localhost 超时（已放宽测试超时）。
