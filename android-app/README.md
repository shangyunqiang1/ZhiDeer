# 知时鹿（ZhiDeer）Android 应用

单模块 Kotlin + Jetpack Compose + Room 3 + 手动依赖注入（`app/src/main/java/com/zhideer/app/di/AppContainer.kt`）。
统一业务入口在 `domain/usecase/`，界面在 `ui/`，本地事务与实体在 `data/db/`。

## 构建与测试

在本目录（`android-app/`）执行：

```powershell
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

- 单元测试使用 Robolectric（`@Config(sdk = [36])`）与文件级 Room，测试内必须使用
  `AndroidSQLiteDriver`，不要改用 `BundledSQLiteDriver`（JVM 单测没有原生库）。
- Room schema JSON 在 `app/schemas/`，修改实体后必须提交对应版本 JSON。
- AGP 9.3 自带 Kotlin 插件，不要再添加 `org.jetbrains.kotlin.android`。

## 备份与恢复边界

备份入口：`domain/usecase/BackupUseCases.kt`；界面：`ui/BackupScreen.kt`。

**备份包含**

- Room v15 全部用户数据表（11 张业务表）：待办、子任务、日程、便签与清单项、提醒、
  重复出现记录、日程提醒、出现维度步骤记录、长期记忆（含来源引用与停用/来源缺失状态）、
  系统时钟交接记录；
- AI 会话历史（DataStore JSON）；
- 参谋建议的反馈、采纳执行记录与提案依据绑定（DataStore JSON）。建议本身按真实记录
  实时生成、不持久化，备份保存的是建议的使用与执行痕迹。

**绝不包含**

- API Key、模型标识、Base URL 与本机配置（备份路径从不读取任何配置存储）；
- 系统调度台账 `reminder_schedule_log`（本机已调度/已投递/错过等设备本地调度状态，
  不是用户记录）；
- 备份只写入用户指定的本地加密文件，不自动上传、不外发。

**恢复语义**

- 先只读核对口令/内容/格式/版本并展示摘要，经确认后才覆盖式整库替换（不合并）；
- 恢复的待办/日程提醒一律先暂停，经用户逐项确认与既有权限检查后才重新调度；
- 设备本地调度台账在恢复事务内清空，旧终态（已投递/已错过）不会抑制重新启用的提醒，
  也不会把本机未调度的事实伪装成已投递；
- 来源缺失（不可核验依据）的记忆恢复时强制停用，不把无法核对的偏好恢复成可用事实；
- 恢复前待确认的参谋提案一律失效，须重新生成建议并确认；旧建议不会直接执行；
- AI 上下文授权在恢复成功后重置，用户必须重新确认，恢复数据不等于恢复外发权限。

**旧备份边界（用户故事 88）**

应用无法修改用户已经导出的旧备份文件，也不会自动修改外部旧备份。删除当前数据
**不承诺**改写旧备份；恢复旧备份后，后来被彻底删除的记忆、事项或反馈记录可能重新出现，
需要时请在恢复后重新清理。备份口令独立于设备，忘记口令无法恢复备份内容。

## MVP 已知取舍

- `AppContainer` 与 `ZhiDeerDatabase` 目前使用 `fallbackToDestructiveMigration()`：
  升级 schema 版本会重建本地库。接入真实用户数据前必须替换为保留数据的正式迁移。
- 备份格式版本为 1，库版本必须与当前应用一致才允许恢复（不兼容备份直接拒绝，不覆盖数据）。
