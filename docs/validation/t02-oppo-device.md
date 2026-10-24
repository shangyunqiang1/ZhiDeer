# T02 OPPO 设备验证准备

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
