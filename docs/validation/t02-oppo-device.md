# T02 OPPO 设备验证准备

**当前状态（2026-10-24 22:33，Asia/Shanghai）**：手机已通过 USB 调试授权，单台 ADB 设备及设备元数据已核验；已完成只读 handler 发现。通知投递、准时提醒、重启恢复、语音和系统时钟运行验收仍未验证，尚无 probe APK，T02 未完成。以下历史记录按检查先后保留。

关联：https://github.com/shangyunqiang1/ZhiDeer/issues/3；规格 #1，用户故事39–48、65–70、90。

状态：未验证。2026-10-24 检查当前 PATH 未发现 adb；ANDROID_HOME 和 ANDROID_SDK_ROOT 未提供有效路径；常见 SDK 位置 C:\Users\linlin\AppData\Local\Android\Sdk、C:\Android\Sdk、G:\Android\Sdk 均不存在。没有调用 adb，不能断言没有连接手机。型号、Android/ColorOS 版本、应用构建基线均待提供。

## 采集

将 Android 官方 platform-tools 的 adb.exe 路径传给脚本；仅连接一台已授权 USB 调试的设备：

```powershell
.\tools\validation\device\Collect-DeviceEvidence.ps1 -AdbPath 'C:\path\platform-tools\adb.exe' -PackageName '已确定的实际包名' -OutputPath '.\device-evidence.json'
```

脚本只查询设备属性、时间、省电模式、默认语音服务及指定应用的 appops，不安装应用，不改权限，不重启手机，不保存序列号、完整通知、日历或日志。appops 返回仅是诊断线索，不能替代应用内 NotificationManager/AlarmManager 能力检查；不同系统不支持的查询保留退出码，不伪报成功。默认语音服务存在不能证明中文可用或离线可用。SDK 搜索仅覆盖上述位置，不代表全盘不存在。

## 验收记录模板

每轮记录：型号、Android/ColorOS版本、安全补丁、应用包名/versionCode/commit、测试时间及时区、联网/锁屏/充电状态、系统通知及精确提醒权限、厂商自启动和省电设置、操作步骤、预期时间、实际到达时间、屏幕可见结果、通过/失败/未验证及证据路径。只用合成测试内容，截图遮盖私人信息。所有项目当前均未验证。

| 编号 | 操作步骤 | 验收依据 |
|---|---|---|
| D01 通知 | 在应用创建未来两分钟的明确提醒；先允许通知，锁屏等待；分别核对通知、提示音、振动。再在系统设置拒绝通知，重试 | 分别记录三种可观察结果；拒绝时事项仍存在、提醒显示未就绪，提供修复入口；提交调度不等于投递 |
| D02 准时要求 | 拒绝精确提醒能力，明确要求准时；核对状态。随后用户在系统设置允许，再重新核查 | 未允许时不得悄悄降级；保留事项，提供选择；允许后记录预期与实际到达时间，不虚构全机型准时保证 |
| D03 重启 | 创建未来提醒和会在关机期间错过的提醒，保存证据；人工重启并解锁，再检查应用与未来投递 | 有效未来调度恢复；错过项合并提示、不逐项补响；不自动延期或完成待办。须实际重启，不能仅模拟广播 |
| D04 休眠/省电 | 分别在系统默认、省电模式和厂商后台限制下，锁屏等待同类提醒；记录具体设置与实际结果 | 分条件记录延迟/缺失及可见限制；一次成功不等于无限期休眠保证；测试后人工恢复原设置 |
| D05 时间变化 | 用合成事项记录创建时区；人工切换时区、手动改时间，分别观察提示及未来调度，测试后恢复 | 固定创建时区，不静默改事项；错过提醒不自动完成，要求确认调整 |
| D06 识别 | 按住说中文，松手转写，编辑文字；未点击发送前检查是否已外发/写入；点击发送后走正常提案确认 | 录音、识别、转写状态清晰；编辑后才发送；输入方式不绕过写入确认 |
| D07 麦克风回退 | 系统拒绝麦克风后按住说话；录音中离开页面；在无识别服务的独立测试环境复测 | 中文说明及键盘回退，离开后无麦克风指示/监听，不持久保存原始录音；不能在用户主力机随意禁用系统引擎 |
| D08 中文播报 | 首次启动核对默认关闭；允许后播报中文，点击停止；缺少引擎/中文语音数据时复测 | 默认不播报、可停止；缺失时说明并保留文字。记录实际引擎、语言检查及联网条件 |
| D09 系统时钟 | 在应用预览并确认一条测试闹钟提案，打开系统时钟，人工核对时间/标签并保存；测试后人工删除该测试闹钟 | 交接记录先保持待核对，跳转不冒充保存；打开列表能力单独记录，系统闹钟不自动生成知时鹿提醒 |
| D10 指定日期/失败 | 输入未来具体日期持续响铃请求；在无支持 handler 的独立环境复测交接 | 不丢日期，不承诺平台未验证能力；中文限制与通知提醒/手动系统时钟回退，失败不能标成功 |

## 后续探针实现接口

工程基线确定后，独立 probe APK 应仅使用合成数据，提供应用内能力快照：通知总开关和渠道状态、API 支持时 canScheduleExactAlarms、识别服务可用性、中文 TTS 初始化/语言结果、系统时钟 intent 是否有处理器；再提供由用户显式触发的通知调度和系统时钟交接。应用层快照和真实设备投递须分开保存。当前只交付主机采集脚本与验收方案，尚无编译运行的 probe APK，不满足 T02 完整验收。

## 探针应用已实现并构建（2026-10-25，未实机运行）

源码已合入集成分支：`tools/validation/device/probe-app/`（独立 Gradle 工程，纯 Java、无 AndroidX、minSdk 29 / target+compileSdk 37，仅合成测试数据）。debug APK 已实际构建成功（`BUILD SUCCESSFUL in 1m 22s`，33 任务；构建日志 `G:/ZhiDeer-tools/logs/probe-build.log`）。

- 快照（只读）：厂商/型号/Android/补丁/ColorOS 属性、通知总开关与渠道、canScheduleExactAlarms（API 31+）、`SpeechRecognizer.isRecognitionAvailable` 与 RecognitionService 组件、默认 TTS 引擎设置、SET_ALARM/SHOW_ALARMS 处理器。
- 显式动作：请求通知权限、立即测试通知、2 分钟精确提醒（接收器记录预定 vs 实际投递毫秒偏差，供 D01/D02）、取消提醒、中文 TTS 初始化+`isLanguageAvailable(zh_CN)`+合成播报（D08）、识别服务可用性（D07 线索）、SET_ALARM 预填交接（SKIP_UI=false，由用户在时钟界面确认保存，D09）、打开系统时钟列表。
- 结果同时写入 logcat（tag `ZhideerProbe`）与界面文本，便于截图与 `adb logcat -s ZhideerProbe` 采集；应用层快照与真实投递分开记录。
- 构建方式：`G:/ZhiDeer-tools/run-probe-build.ps1`（进程级环境变量；APK 位于 `probe-app/app/build/outputs/apk/debug/app-debug.apk`）。
- **未实机安装或运行**：通知、精确提醒、重启恢复、省电、语音、系统时钟的真实行为仍全部未验证。D01–D10 须在 OPPO 实机重连后按上文验收记录模板逐项执行，T02 不因此关闭。

最低 Android 版本需结合基线依赖、真实设备 API level 及目标能力决定，目前不定值。

## 官方依据

- [Android 定时提醒](https://developer.android.com/develop/background-work/services/alarms)：精确提醒能力检查及重启恢复要求。
- [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)：识别可用性与生命周期。
- [TextToSpeech](https://developer.android.com/reference/android/speech/tts/TextToSpeech)：引擎初始化和语言支持。
- [AlarmClock](https://developer.android.com/reference/android/provider/AlarmClock)：公开系统时钟 intent 及参数。

这些 API 文档提供测试设计依据，不能替代 OPPO 运行证据。

## 主机脚本验证

运行 `pwsh -NoProfile -File tools/validation/device/Test-Collector.ps1`。可控 fake adb 验证缺少工具时失败、无设备、未授权、多设备、adb 查询失败及单台设备下部分 appops 不支持的路径；确认输出保持 NOT_VERIFIED 且不包含设备序列号。2026-10-24 上述检查通过，PowerShell AST 解析无错误。这仅验证采集脚本行为，不代表 Android 平台或 OPPO 能力通过。

## 官方 portable adb 补充核查

2026-10-24 从 `https://dl.google.com/android/repository/platform-tools-latest-windows.zip` 下载到仓库外任务缓存，未安装到系统 PATH，未接受 SDK 许可证、安装应用或改设备设置。实际版本：Android Debug Bridge 1.0.41，platform-tools 37.0.1-15733141，Windows 10.0.19045。

调用 `adb devices` 退出码为 0，设备行计数为 0，无 device、unauthorized 或 offline 条目。该结果只说明此刻 adb 未发现设备，不能证明手机物理上未接入；仍可能缺少 USB 调试、驱动或数据连接。未输出或保存设备序列号。未调用 tcpip/connect、授权、权限修改、重启或应用安装。因没有单台已授权设备，未执行设备属性采集，未生成真实手机证据。

用户提供的设备名称为 **OPPO Reno10 5G**、厂商系统版本为 **ColorOS 15.0**，尚未由设备属性核验；Android 版本和型号代码仍未知，不从 ColorOS 名称推断 Android 版本。所有平台能力验收继续保持未验证，T02 未完成。

## USB 状态复核补充

用户随后两次报告“已连接”。Root 在每次报告后使用相同 portable adb 实际复查，`adb devices` 均退出码 0、设备行数 0；Root 使用 `Get-PnpDevice` 查询时未找到名称包含 OPPO、Android、ADB 或 MTP 的设备。以上是当前主机可见性证据，不将用户所说的连接视为 USB 调试已核验，也不据此断定驱动、数据线或其他具体故障原因。

已向用户提供检查 USB 调试、文件传输模式、手机授权弹窗及数据线的建议；尚无手机属性或设备能力数据。通知、准时提醒、重启恢复、语音及系统时钟继续未验证，T02 不关闭。

## 后续 USB 数据连接新证据

在上述未发现 PnP 名称的检查之后，Root 再次执行 `Get-PnpDevice`，现已观察到 **Status OK / Class WPD / FriendlyName OPPO Reno10 5G**。这表明此时 Windows 已识别手机的 USB 数据连接，不能继续沿用早先“未发现手机 PnP 条目”的状态作为当前结论。

同一次检查中，`adb devices` 仍退出码 0、设备行数 0；`ADB_SERVER_SOCKET` 与 `ANDROID_ADB_SERVER_PORT` 均未配置。因此，USB 数据设备现已可见，但 ADB 调试设备尚未出现，原因未确定。用户已被询问 USB 调试开关和授权状态，当前尚未回答。未取得手机属性或设备能力测试证据，T02 仍未完成。

## 用户接入步骤：找到并启用 USB 调试

用户反馈找不到 USB 调试选项。依据 Root 已核查的 OPPO 官方说明，可按以下步骤接入：

1. 打开手机“设置 → 关于本机 → 版本信息／版本”，连续点击“版本号”7次；按屏幕提示输入锁屏凭据以启用开发者模式。
2. 对 ColorOS 14.1–16，进入“设置 → 系统与更新 → 开发者选项”，找到并启用“USB 调试”。菜单名称以手机实际显示为准。
3. 保持手机解锁并连接此电脑；若出现 USB 调试授权弹窗，核对电脑后由用户确认授权。
4. 授权后再进行 ADB 设备发现；仅看到 WPD 或手机名称不等于已完成调试授权。

官方来源：[OPPO 开启开发者选项说明](https://support.oppo.com/au/answer/?aid=neu9332)、[OPPO USB 调试说明](https://support.oppo.com/en/answer/?aid=neu7554)。

以上仅记录已提供给用户的可复现接入步骤，不表示已启用 USB 调试、已授权此电脑或完成任何 Android 平台能力验证。本次文档补充未重复底层设备检测，T02 继续未完成。


## USB 授权成功后的设备及 handler 核查

2026-10-24 22:32（Asia/Shanghai），Root 实际采集到单台已授权设备，设备查询退出码为 0。仓库外证据 `reno10-evidence.json` 核验如下：

| 属性 | 实际结果 |
|---|---|
| 厂商／型号代码 | OPPO／PHW110 |
| Android／API level | 15／35 |
| 安全补丁 | 2026-09-01 |
| ro.build.version.oplusrom | V15.0.0 |
| 时区 | Asia/Shanghai |
| low_power | 0（采集时系统低电量模式标志，不能代表所有厂商后台限制均关闭） |
| 默认识别服务设置 | 空值 |
| 默认 TTS 设置 | null |

用户提供的市场名称为 OPPO Reno10 5G；设备代码和 Android 版本现已有真实属性证据。以上新状态取代前文“未发现 ADB 设备／尚未授权”的当前结论，历史检查结果仍保留。

22:33:45，执行 `adb shell cmd package help` 确认语法后，使用 `query-services/query-activities --components --user current -a <action>` 仅查询当前用户的公开 intent handlers，各次退出码均为 0：

| 查询 | Action | 结果 |
|---|---|---|
| query-services | android.speech.RecognitionService | No services found |
| query-services | android.intent.action.TTS_SERVICE | 发现1个 TTS 服务 |
| query-activities | android.intent.action.SET_ALARM | 发现系统时钟处理器 |
| query-activities | android.intent.action.SHOW_ALARMS | 发现系统时钟处理器 |

完整组件查询证据仅留在仓库外 `reno10-handler-evidence.json`。识别查询未发现标准服务，是后续缺少引擎回退测试的线索，不扩大为设备不存在任何语音产品的结论。TTS handler 存在而默认设置为 null，仍须通过应用实际初始化、中文语言检查和播报验证；系统时钟 handler 存在也不证明交接或保存已成功。shell 查询不能替代应用 UID 下的能力检查与 manifest 可见性配置验证。

本轮未启动任何闹钟、录音或播报，未安装应用、修改权限、重启手机或读取私人记录；不含设备唯一标识。尚无可运行 probe APK，通知、准时提醒、重启恢复、省电、麦克风回退、中文播报和系统闹钟实际交接均继续未验证，不能关闭 #3。

## 2026-10-25 实机验收（OPPO PHW110）实际结果

**设备与时间**：OPPO PHW110、Android 15（API 35）、安全补丁 2026-09-01、ColorOS V15.0.0、Asia/Shanghai；adb 序列号已脱敏不记录。安装对象：探针 `com.zhideer.probe`（合成数据）与产品 `com.zhideer.app`（v0.1.0, versionCode 1）。证据目录仓库外 `G:/ZhiDeer-tools/evidence/device/`。

### 已实证项

| 编号 | 结果 | 证据 |
|---|---|---|
| D01 通知（允许路径） | **通过**：通知真实显示于系统横幅与通知中心；渠道 `probe_exact` importance=4（HIGH）、`mVibrationEnabled=true`、提示音为系统默认 | `d01-delivered.png`；`dumpsys notification --noredact`：`AppSettings: com.zhideer.probe importance=DEFAULT userSet=true`、`android.title=探针：手动触发的立即通知`、`mShowBanner=true` |
| D01 通知（拒绝路径） | **通过**：应用级通知开关关闭时 `POST_NOTIFICATION: ignore`、`importance=NONE`，通知不可见；探针保持调用成功但界面与日志如实记录，不伪装成功 | 早期截图与 `dumpsys package ... POST_NOTIFICATIONS: granted=false` |
| D02 精确提醒（能力不足路径） | **通过**：`canScheduleExactAlarms=false` 时探针不降级，先跳转 `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` 系统页并提供入口 | 系统「闹钟和提醒 → 允许设置闹钟和提醒」页截图 |
| D02 精确提醒（允许后准时性） | **通过**：用户授权后 `AlarmManager` 实际注册 `type=RTC_WAKEUP exactAllowReason=policy_permission`；锁屏期间通知投递（`notification_visibility` 事件 + NotificationRecord） | `dumpsys alarm`、`logcat -b events`、`d02-*` 证据 |
| D07 语音识别 | **通过（能力缺失结论）**：应用 UID 下 `SpeechRecognizer.isRecognitionAvailable=false`，`queryIntentServices(RecognitionService)` 返回空 → 中文说明 + 键盘回退为必需路径；不得静默外发或持久保存录音 | 探针 logcat `RecognitionService 组件: 无` |
| D08 中文播报 | **通过**：`TextToSpeech` 初始化成功（引擎 `com.yuemeng.speechsuite`），`isLanguageAvailable(zh_CN)=AVAILABLE`，`speak()` 返回 0，**用户人工确认听到中文播报** | logcat + 用户确认（本机非默认引擎设置，仍可播报） |
| D09 系统时钟交接 | **通过并发现硬约束**：① 缺失 `com.android.alarm.permission.SET_ALARM` 声明时系统抛 `SecurityException: requires com.android.alarm.permission.SET_ALARM`（探针首版即如此）；② 具备权限时 ColorOS 15 的 `HandleApiActivity`/`AlarmClockCTS` 接收 `SET_ALARM` 但**只打开闹钟列表、不预填编辑器**，extras 未被采用；③ `SHOW_ALARMS` 有处理器。结论：交接记录必须保持「待核对」，不得声称已保存；不得承诺平台未验证的预填能力 | logcat SecurityException、`d09-clock-opened.png`（列表无预填项）、handler 查询 |

### 未完成项与原因（如实记录）

- **D03 重启恢复**：需真实重启设备并解锁；本轮未执行（避免中断用户正在使用的手机），未验证。
- **D04 休眠/省电**：需在默认/省电/厂商后台限制三种条件下各等待一段时间；未执行，未验证。
- **D05 时间变化**：需人工改系统时区与时间；未执行，未验证。
- **D06 麦克风拒绝与录音生命周期**：产品尚未实现语音输入（#23/#24 未交付），无法在应用层验证录音生命周期；已由 D07 固定"无识别服务"这一环境事实。
- **D10 指定日期交接与失败回退**：由 D09 结论推导为"不承诺预填、保持待核对"，无支持 handler 的独立环境未构造，未验证。
- **产品级提醒真实投递（#9/#10）**：产品 `com.zhideer.app` 的通知总开关在本轮结束时仍为 `ignore`，产品内创建提醒并在锁屏等待的端到端投递**尚未验证**；平台侧路径（精确闹钟注册 + 锁屏通知投递）已由探针实证。

### 结论

#3 的验收标准要求"通知、显式准时提醒、重启恢复及省电限制下的可观察结果"与"系统语音识别、中文 TTS、麦克风拒绝及缺少引擎的回落能力"逐项核对。本轮已实证通知投递、精确提醒准时性与不当降级、中文 TTS、识别服务缺失、系统时钟交接的真实边界；**重启恢复、省电限制、时间变化、麦克风拒绝与产品级提醒投递仍未验证**，因此 #3 不宣称全部通过。

## 2026-10-25 晚 产品级提醒真实投递复核（#9 / #10）

**设备与基线**：OPPO PHW110、Android 15（API 35）、ColorOS V15.0.0、Asia/Shanghai；安装集成分支 tip `6943036` 构建的 `app-debug.apk`（v0.1.0）。测试期临时调整屏幕超时为 30 分钟并开启充电常亮，测试后恢复原 `screen_off_timeout=60000`。证据目录仓库外 `G:/ZhiDeer-tools/evidence/device/`。

### 权限路径（应用级实测）

| 项目 | 状态 | 命令/界面结果 |
| --- | --- | --- |
| 通知（应用级） | 允许 | `cmd appops get com.zhideer.app POST_NOTIFICATION` → 默认允许；通知渠道 `zhideer_reminders` importance=4（HIGH）、系统默认提示音、`mVibrationEnabled=true` |
| 精确闹钟：初始 | **未授权** | `SCHEDULE_EXACT_ALARM` 默认模式；应用详情页显示「有提醒未就绪 / 未就绪：未授予精确闹钟权限，提醒暂无法准时触发」，提供 重新检查 / 去授权 / 确认降级；提醒记录保留，未隐式降级（截图 `t08-reminder-added.png`、`d02-grant-page.png`） |
| 精确闹钟：授权 | 允许 | 点击「去授权」跳转系统「闹钟和提醒 → 允许设置闹钟和提醒」；开启开关后 `cmd appops get … SCHEDULE_EXACT_ALARM` → `Uid mode: allow` |
| adb 直接改 appops | 被拒绝 | `cmd appops set com.zhideer.app SCHEDULE_EXACT_ALARM allow` → `SecurityException: uid 2000 does not have MANAGE_APP_OPS_MODES`，与既有“必须用户手动在系统设置开启”的结论一致 |

### #9 待办提醒（T08）

1. 在应用内新建待办「T08」，时刻截止 `2026-10-25 20:40`，显式添加「截止前 1 分钟」提醒 → 详情页展示目标时刻 `2026-10-25 20:39`。
2. 授权前：显示未就绪与修复入口；授权后点击「重新检查」，未就绪标记消失。
3. `dumpsys alarm` 实测（授权后）：`RTC_WAKEUP … origWhen=2026-10-25 20:39:00.000 … exactAllowReason=permission component=com.zhideer.app/.platform.ReminderAlarmReceiver`，但同一行 `windowLength=166119`（平台附加投递窗口）。
4. 锁屏等待（`mScreenState=DOZE_SUSPEND`）：实际于 **20:41:31** 投递（+2m31s）。`logcat -b events` 记录 `notification_enqueue: [10309,21250,com.zhideer.app,… channel=zhideer_reminders …]`；通知中心可见「T08 / 知时鹿提醒：时间到了，来看看「T08」」（截图 `d01-app-notif-shade.png`）。

### #10 日程提醒（T09）

1. 新建日程「T09」，`2026-10-25 20:52 ～ 21:00`，时区 `Asia/Shanghai`（创建时锚定）；在详情页添加**独立时刻**提醒 `2026-10-25 20:51`。
2. 授权后 `dumpsys alarm` 出现第二条 `RTC_WAKEUP … origWhen=2026-10-25 20:51:00.000 exactAllowReason=permission`，`windowLength=242880`。
3. 锁屏等待：实际于 **20:51:31** 投递（+31s）；通知中心出现分组摘要（`ranker_group`，计数 2），展开可见「T09 知时鹿提醒：时间到了，来看看「T09」」与上一条 T08（截图 `t09-shade.png`）。
4. 相对/独立提醒的改期行为、日程删除停止自身提醒且不影响关联待办提醒、去重由 `CalendarReminderUseCasesTest` / `ReminderUseCasesTest` 覆盖。

### 本轮发现与结论

1. **ColorOS 15 对已授权精确闹钟仍附加投递窗口**（windowLength 2–4 分钟）：权限允许只代表平台接受精确调度请求，不保证锁屏/待机下秒级投递。观测延迟：2m31s、31s。
2. 屏幕熄灭进入 DOZE_SUSPEND 时提醒仍能真实投递，通知中心可见；同渠道通知被系统自动分组。
3. 因此「调度请求提交成功」与「真实触发」必须分别记录，#9/#10 的“准时”不得写成“任何电源状态下秒级准时”；该限制由 #11（恢复设备变化后的提醒）与 #36（故障诊断）继续承接。
4. 提示音与振动：本轮只核对到渠道配置（系统默认提示音、振动开启），未逐项人工听感确认。

