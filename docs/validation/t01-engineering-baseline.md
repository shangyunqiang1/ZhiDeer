# T01 工程基线验证（#2）

验证日期：2026-10-24（Asia/Shanghai）。关联规格：[#1](https://github.com/shangyunqiang1/ZhiDeer/issues/1)，任务：[#2](https://github.com/shangyunqiang1/ZhiDeer/issues/2)。

## 结论与门槛

**未通过，#2 仍未完成，尚未选定产品工程基线。** 已固定并实际检出 MyBrain，完成源码规则核查；构建在 Java 启动前置检查失败，未运行测试、未生成 APK、未演示真实数据库写入重读。不得据此启动依赖基线的下游产品实现。

建议在工具链恢复后先完成固定版本的构建及持久化探针，再决定：接受 GPL 分发约束并重构 MyBrain，或独立实现 Android 应用。源码证据显示后者更贴合本规格，但这是建议，不能把未验证的方案当作已选基线。

## 固定对象与环境

- 上游：https://github.com/mhss1/MyBrain
- 准确提交：`96cd36d40effd42bacb0cc25c8b6bf2a88a4d657`，提交时间 `2026-10-03T09:40:15+03:00`，标题 `Merge pull request #398 from mhss1/dev`。
- 仓库外检出：`C:/Users/linlin/AppData/Local/Temp/zhideer-mybrain-t01-20261024`；`git status --short` 无改动。
- Windows PowerShell；Git 可用；`Get-Command java,adb` 未发现命令，JAVA_HOME、ANDROID_HOME、ANDROID_SDK_ROOT 未设置；常规 Java/Android 安装目录未找到。只说明本次环境观察，不断言整机绝无安装。
- 所有下述配置均来自该提交：`gradle/libs.versions.toml` 的 compile/target SDK 37、min SDK 26、AGP 9.3.0、Kotlin 2.4.10、Room 3.0.0；wrapper 为 Gradle 9.6.1。`androidApp/build.gradle.kts` 使用 Java 17 源码/字节码目标。没有核实这些依赖在本机能否解析。
- `settings.gradle` 定义 `androidApp` Android 应用及多个 KMP library；`composeApp` 是共享库，不是 APK 应用模块。不要照搬旧版 `:app` 命令。

## 实际命令与结果

```powershell
git clone --depth 1 https://github.com/mhss1/MyBrain.git C:/Users/linlin/AppData/Local/Temp/zhideer-mybrain-t01-20261024
git rev-parse HEAD
.\gradlew.bat testAndroidHostTest :androidApp:assembleRelease --no-daemon
```

克隆成功，提交与上文一致；最后一条 Gradle 命令实际失败：

```text
ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
Please set the JAVA_HOME variable in your environment to match the location of your Java installation.
```

wrapper 未启动 Gradle，因此目前不能确认任务配置、依赖解析、SDK、编译或测试成功。第一次尝试曾误用 `:composeApp:assembleDebug`，同样在 Java 检查处失败；随后依据工作流纠正为上述命令，不能把第一次命令当作有效构建入口。

## 核心路径源码证据

以下路径均相对于固定上游提交，可通过 `https://github.com/mhss1/MyBrain/blob/96cd36d40effd42bacb0cc25c8b6bf2a88a4d657/<path>` 核查。

| 要求 | 上游事实与差距 |
| --- | --- |
| 本地待办写入与读取 | `tasks/domain/.../use_case/UpsertTaskUseCase.kt` 调用 TaskRepository；`tasks/data/.../TaskRepositoryImpl.kt` 的 upsertTask 经 DatabaseTransactionProvider 调用 taskDao.upsertTask；getTaskById 读取 taskDao。DAO 位于 `core/database/.../dao/TaskDao.kt`。真实运行未验证。 |
| 本地日程与待办分离 | 待办为 Room 数据；`calendar/data/src/androidMain/kotlin/com/mhss/app/data/CalendarRepositoryImpl.kt` 使用 Android CalendarContract / ContentResolver，当前日程依赖系统日历。没有本规格要求的本地日程与稳定待办关联实现。 |
| 截止与提醒分离 | `UpsertTaskUseCase.shouldScheduleAlarm` 对未完成且未来 dueDate 自动调度 alarm；Task 只有单个 alarmId。与默认不提醒及多个独立/相对提醒契约冲突。 |
| 重复实例 | 同一 use case 在已完成重复待办上推进 dueDate 并把 isCompleted 改回 false。与每次独立保留历史、未完成不阻止下一次出现冲突。 |
| 日期截止与时区 | `tasks/domain/.../model/Task.kt` 的 dueDate 为 Long?，无明确日期截止类型和创建时区字段，不能证明符合日期截止语义。 |
| AI 预览确认 | `ai/data/.../repository/AiRepositoryImpl.kt` 收到工具调用即调用 AiToolExecutor；后者 decode 后直接 tool.execute；`tools/TaskToolSet.kt` createTaskTool 直接 upsertTask。所查链路没有本规格的确认凭证、提案执行与幂等记录门槛，须重建。 |
| 测试复用 | `core/database/src/commonTest` 有 PagingQueriesTest、TaskDueDateSerializationTest、迁移测试等；androidHostTest 用 Robolectric SDK 36 创建真实 Room DB。现有 DAO 测试可参考，不能替代统一用例入口验收。未发现已运行的持久化跨重开演示。 |

源码缩写 `...` 表示各模块下 `src/commonMain/kotlin/com/mhss/app` 包路径；Android 日程实现路径另行写全。上述仅是静态核查，不是运行结论。

## 许可证及复用边界

固定提交根 LICENSE 为 GNU GPL version 3，README 也指向 GPLv3。若复制并改造上游，应先按其实际授权文本核对未来分发所需的许可证、版权、修改说明及对应源码交付；不能假定能作为闭源衍生应用分发。当前未复制上游代码到知时鹿。

依赖清单见固定提交 `gradle/libs.versions.toml`，覆盖 AndroidX、Compose、Koin、Koog、Ktor、zstd 等；本次未解析 artifacts、未完成传递依赖及逐项许可证审计。因此没有已批准的复用依赖集合，不能宣称整个依赖树均兼容或已可离线构建。独立实现也需要单独核对实际选用依赖，不因避免复制上游而免除此项。

## 取舍与待完成事项

改造路径有既有 UI、Room、模块及测试，但日程存储、自动提醒、重复生命周期与 AI 直接执行恰好处于本规格核心边界，迁移成本不能按换皮估算。独立实现可以从统一业务入口和本地数据模型开始，避免继承上述契约；代价是重新实现 UI 和平台适配，仍须真实 Android 构建与设备验证。

需要人工明确的产品决定：未来是否接受以 GPL 约束分发衍生应用；对“独立实现”建议的最终选择。最低 Android 版本不直接沿用上游 min SDK 26，需结合 T02 目标设备与平台能力决定。

工具链就绪后的可复现入口（来自该提交 `.github/workflows/verify-android.yml`，仍需本机运行确认）：

```powershell
.\gradlew.bat testAndroidHostTest --rerun-tasks -q
.\gradlew.bat :androidApp:assembleRelease --no-daemon
# 本地调试包可尝试：
.\gradlew.bat :androidApp:assembleDebug --no-daemon
```

之后必须补充统一 use case -> 真实 Room 写入 -> 关闭数据库 -> 重开 -> 查询相同记录的探针，并保留输出；不能用 Python SQLite 仿制 schema 代替上游持久化验证。完成依赖审计、与规格差异验证后再记录所选工程基线及后续构建测试命令。当前没有安装大型工具链，也没有宣称上述待办完成。

### 精确依赖审计起点（未批准复用）

以下为固定版本清单中的直接依赖坐标与版本样本，**仅核实坐标，不代表核实其授权**：`androidx.room3:room3-runtime:3.0.0`、`androidx.sqlite:sqlite-bundled:2.7.0`、`org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0`、`org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0`、`org.jetbrains.kotlinx:kotlinx-datetime:0.8.0`、`io.insert-koin:koin-bom:4.2.2`、`ai.koog:koog-agents:1.1.1`、`io.ktor:ktor-client-core:3.5.1`、`com.github.luben:zstd-jni:1.5.7-11`、`com.google.zxing:core:3.5.4`、`org.jetbrains.compose.runtime:runtime:1.11.1`、`org.jetbrains.compose.material3:material3:1.11.0-alpha07`、`androidx.work:work-runtime-ktx:2.11.2`、`org.robolectric:robolectric:4.16.1`。全量声明以同提交 `gradle/libs.versions.toml` 为准，不在此将样本伪称完整解析依赖树。

未核实项包括：各 artifact 实际下载内容与 POM/license、传递依赖解析图、native binary 附带授权、GPL 与实际引入依赖的兼容性、NOTICE/版权清单、离线缓存完整性。由于还没有拟复用的确定集合，本次没有作出复用许可批准。

工程选择责任：依据现有规格，Root 可自主提出独立实现的技术方案，且不必为普通实现细节逐项请求许可；但当前证据仍未满足“先验证构建与最小持久化路径”的 #2 门槛，不能据此关闭任务。接受 GPL 衍生分发义务属于会影响未来产品分发的取舍，如要选改造路线，应明确让用户知晓并确认其接受程度。独立实现建议本身不证明已具备可构建工程。
