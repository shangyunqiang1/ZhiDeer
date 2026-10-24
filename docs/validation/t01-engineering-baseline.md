# T01 工程基线验证（#2）

验证日期：2026-10-24（Asia/Shanghai）。关联规格：[#1](https://github.com/shangyunqiang1/ZhiDeer/issues/1)，任务：[#2](https://github.com/shangyunqiang1/ZhiDeer/issues/2)。

## 结论与门槛

**未通过，#2 仍未完成，尚未选定产品工程基线。** 已固定并实际检出 MyBrain，完成源码规则核查；首次构建在 Java 启动前置检查失败；后续已补齐并校验 portable JDK 21，随后通过官方包校验及进程代理启动 Gradle，最终失败于 Kotlin compiler artifact 的 TLS 下载，未运行测试、未生成 APK、未演示真实数据库写入重读。不得据此启动依赖基线的下游产品实现。

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

若选 MyBrain 改造，需要明确未来是否接受 GPL 衍生分发取舍；独立实现属于 Root 可根据规格自主决定的技术方案，不额外要求用户批准普通技术选型。最低 Android 版本不直接沿用上游 min SDK 26，需结合 T02 目标设备与平台能力决定。

工具链就绪后的可复现入口（来自该提交 `.github/workflows/verify-android.yml`，仍需本机运行确认）：

```powershell
.\gradlew.bat testAndroidHostTest --rerun-tasks -q
.\gradlew.bat :androidApp:assembleRelease --no-daemon
# 本地调试包可尝试：
.\gradlew.bat :androidApp:assembleDebug --no-daemon
```

之后必须补充统一 use case -> 真实 Room 写入 -> 关闭数据库 -> 重开 -> 查询相同记录的探针，并保留输出；不能用 Python SQLite 仿制 schema 代替上游持久化验证。完成依赖审计、与规格差异验证后再记录所选工程基线及后续构建测试命令。首次调查未安装工具链；随后已补充仓库外 portable JDK，见追加记录。没有宣称上述待办完成。

### 精确依赖审计起点（未批准复用）

以下为固定版本清单中的直接依赖坐标与版本样本，**仅核实坐标，不代表核实其授权**：`androidx.room3:room3-runtime:3.0.0`、`androidx.sqlite:sqlite-bundled:2.7.0`、`org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0`、`org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0`、`org.jetbrains.kotlinx:kotlinx-datetime:0.8.0`、`io.insert-koin:koin-bom:4.2.2`、`ai.koog:koog-agents:1.1.1`、`io.ktor:ktor-client-core:3.5.1`、`com.github.luben:zstd-jni:1.5.7-11`、`com.google.zxing:core:3.5.4`、`org.jetbrains.compose.runtime:runtime:1.11.1`、`org.jetbrains.compose.material3:material3:1.11.0-alpha07`、`androidx.work:work-runtime-ktx:2.11.2`、`org.robolectric:robolectric:4.16.1`。全量声明以同提交 `gradle/libs.versions.toml` 为准，不在此将样本伪称完整解析依赖树。

未核实项包括：各 artifact 实际下载内容与 POM/license、传递依赖解析图、native binary 附带授权、GPL 与实际引入依赖的兼容性、NOTICE/版权清单、离线缓存完整性。由于还没有拟复用的确定集合，本次没有作出复用许可批准。

工程选择责任：依据现有规格，Root 可自主提出独立实现的技术方案，且不必为普通实现细节逐项请求许可；但当前证据仍未满足“先验证构建与最小持久化路径”的 #2 门槛，不能据此关闭任务。接受 GPL 衍生分发义务属于会影响未来产品分发的取舍，如要选改造路线，应明确让用户知晓并确认其接受程度。独立实现建议本身不证明已具备可构建工程。


## 后续工具链推进记录

同日继续调查发现 `gradle/gradle-daemon-jvm.properties` 明确 `toolchainVersion=21`。Java 17 是上游 CI 的 wrapper 启动环境和应用字节码目标，不能把它当作本提交 daemon 实际版本。采用 portable JDK 21，不修改全局 PATH 或用户设置。

- 下载入口：[Microsoft 官方 OpenJDK 下载页](https://learn.microsoft.com/en-us/java/openjdk/download)，包 URL：`https://aka.ms/download-jdk/microsoft-jdk-21.0.12.1-windows-x64.zip`。
- 包大小 201096952 bytes，SHA256 `192441a9d27da813bada974bb88b4cf64d37a9589ed37f204374d411ca5ce07f`，与同 URL 加 `.sha256sum.txt` 的官方文件完全一致。解压至 `C:/Users/linlin/AppData/Local/Temp/zhideer-t01-tools/microsoft-jdk21/jdk-21.0.12.1+1`。
- `java -version` 实际输出：`openjdk version "21.0.12.1" 2026-08-18 LTS`、`Microsoft-14941484 (build 21.0.12.1+1-LTS)`。
- 最初尝试 Eclipse Adoptium 官方 API 指向的 Temurin ZIP，因下载速度过低取消，未解压或使用不完整包；Microsoft CDN 完成并校验。

实际构建命令只设置当前 PowerShell 进程变量，Gradle 缓存也留在任务临时目录：

```powershell
$env:JAVA_HOME='C:/Users/linlin/AppData/Local/Temp/zhideer-t01-tools/microsoft-jdk21/jdk-21.0.12.1+1'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
$env:GRADLE_USER_HOME='C:/Users/linlin/AppData/Local/Temp/zhideer-t01-tools/gradle-user-home'
.\gradlew.bat testAndroidHostTest :androidApp:assembleRelease --no-daemon
```

wrapper 已实际由 Java 执行，随后失败：

```text
Downloading https://services.gradle.org/distributions/gradle-9.6.1-bin.zip
java.io.IOException: Downloading from https://services.gradle.org/distributions/gradle-9.6.1-bin.zip failed: timeout
Caused by: java.net.SocketTimeoutException: Connect timed out
```

日志保存在仓库外 `C:/Users/linlin/AppData/Local/Temp/zhideer-t01-tools/mybrain-build.log`。这已排除本次运行的 Java 缺失问题，但尚未进入 Gradle 配置、依赖解析或 Android SDK/许可证检查。不能把未观察到的 SDK 许可问题写成实际阻塞，也没有执行自动接受 SDK 许可证。

另尝试官方 `https://downloads.gradle.org/distributions/gradle-9.6.1-bin.zip` 的 curl 下载和一次断点续传；官方校验文件内容为 `9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14`。只有完整包通过此校验才可预置缓存并继续，不得使用部分包或更改上游 Gradle 版本绕过验证。


### 最终实际构建结果与交接点

Gradle ZIP 最终完整下载成功：140682664 bytes，SHA256 与上述官方值完全一致；预置到任务 `GRADLE_USER_HOME/wrapper/dists/gradle-9.6.1-bin/4ticwg1pgcbps2hj28r8so764/gradle-9.6.1-bin.zip`。进程有 HTTP_PROXY、HTTPS_PROXY、ALL_PROXY、NO_PROXY 配置；HTTPS_PROXY 不含用户凭据。Java 默认不直接使用这些环境变量，因此将现有 HTTPS_PROXY 的 host/port 转换为当前进程 JAVA_OPTS 与任务专用 Gradle cache 的 gradle.properties 系统代理属性，不输出或提交代理地址。

实际命令仍是 `gradlew.bat testAndroidHostTest :androidApp:assembleRelease --no-daemon`。本轮成功显示 `Welcome to Gradle 9.6.1`，完成 settings 初始化，解析约408个依赖文件、约183MB；执行到 build-logic 构建任务，11分25秒后失败：

```text
> Task :build-logic:generateExternalPluginSpecBuilders
> Task :build-logic:extractPrecompiledScriptPluginPlugins
> Task :build-logic:compilePluginsBlocks FAILED
Could not resolve all files for configuration ':build-logic:kotlinBuildToolsApiClasspath'.
Could not download kotlin-compiler-embeddable-2.3.21.jar
Could not GET 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-compiler-embeddable/2.3.21/kotlin-compiler-embeddable-2.3.21.jar'.
The server may not support the client's requested TLS protocol versions: (TLSv1.2, TLSv1.3).
Remote host terminated the handshake
BUILD FAILED in 11m 25s
3 actionable tasks: 3 executed
```

这是当前最终构建结论。完整日志：`C:/Users/linlin/AppData/Local/Temp/zhideer-t01-tools/mybrain-build-proxy.log`。未降低 TLS 要求、未换版本或改上游构建文件。尚未到 Android SDK 检查、产品编译或测试执行，因此不能声称 SDK 已验证或测试通过。

随后对同一个 Maven Central 官方 artifact URL 作一次 curl 回退，成功取得59720376 bytes JAR；其 `.sha256` URL 返回404，官方 `.sha1` 可用且与本地一致：`c4b47f8e33cdbeedd8c1415ab9f9ade870388162`。另记录本地 SHA256 为 `d3e70fb011675e77ef00f1a68918d3cd91eed26058977d1e3ea2a37a293233af`（此SHA256没有官方校验值，不宣称官方SHA256校验通过）。已将校验后的相同 artifact 放入任务 Gradle cache 对应坐标/SHA1目录；尚未再次构建验证其能否解决问题，因用户要求交接而停止新增重试。

交接时无构建或下载会话仍运行。原始上游检出保持未修改；工具、日志、下载包及Gradle缓存全部保留在仓库外任务目录 `C:/Users/linlin/AppData/Local/Temp/zhideer-t01-tools`。下一会话可复用相同JAVA_HOME、GRADLE_USER_HOME以及任务cache中已配置的进程代理，再执行上述构建命令；wrapper已缓存，不必重复下载。

Root 已收到用户授权接受 Android SDK 许可并安装用户级 SDK，但本子任务未下载或安装 SDK，也未运行 sdkmanager。Root 另已核验实机 PHW110 / Android15 / API35 / ColorOS V15.0.0 / Asia/Shanghai，设备能力结果以 T02 报告为准。本报告不将设备连通当作通知、语音或系统闹钟验收通过。
