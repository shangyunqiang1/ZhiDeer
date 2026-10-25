package com.zhideer.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zhideer.app.domain.model.DiagnosticsSnapshot
import com.zhideer.app.domain.model.ModelConnectionTest
import com.zhideer.app.domain.model.ReminderOwner
import com.zhideer.app.domain.model.SpeechEngineAvailability
import com.zhideer.app.domain.usecase.ClockHandoffUseCases
import com.zhideer.app.domain.usecase.DiagnosticsUseCases
import com.zhideer.app.platform.ReminderFixLauncher
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val diagnosticsTimeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * 权限与故障诊断页（T35/#36，规格用户故事 40、48、54、67、89）。
 *
 * - 权限、语音引擎、模型连接与交接状态都来自实时探针：每次进入/回到前台都重新核对，
 *   不展示过期成功；刷新后模型连接的成功结果不会保留；
 * - 自有提醒与系统时钟交接分成两个区块：前者来自 #11 调度台账（已调度 ≠ 已投递），
 *   后者来自 #25 交接记录（待核对 ≠ 已保存）；
 * - 未就绪项提供系统设置修复入口或回退说明，拒绝授权不影响手动功能；
 * - 失败记录与导出文本已脱敏，导出只写本机文本文件，不自动分享/发送。
 */
@Composable
fun DiagnosticsScreen(
    useCases: DiagnosticsUseCases,
    fixLauncher: ReminderFixLauncher,
    clockHandoffUseCases: ClockHandoffUseCases,
    exportDirectory: File,
    zoneId: ZoneId,
    appVersion: String = "0.1.0",
    onOpenAiConfig: () -> Unit = {},
) {
    val snapshot by useCases.snapshot.collectAsState()
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<ModelConnectionTest?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var exportedText by remember { mutableStateOf<String?>(null) }

    fun report(text: String?, isError: Boolean) {
        message = text
        messageIsError = isError
    }

    fun refresh() {
        scope.launch {
            runCatching { useCases.refresh() }
                .onFailure { report(it.message ?: "诊断刷新失败，请稍后重试", true) }
        }
    }

    // 进入页面与从系统设置返回时都重新探测：权限/服务状态变化后不得残留旧的可用状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun format(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(zoneId).format(diagnosticsTimeFmt)

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(8.dp))
        Text("权限与故障诊断", style = MaterialTheme.typography.headlineSmall)
        Text(
            "本页只展示系统可观察状态：权限、语音引擎与模型配置在每次进入或回到前台时重新核对；" +
                "修复动作需你本人在系统设置中确认。诊断不推断真实投递，也不自动外发任何内容。",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        message?.let {
            Text(
                it,
                color = if (messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        val current = snapshot
        if (current == null) {
            Text("正在检测系统能力…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp))
        } else {
            PermissionsSection(current, fixLauncher)
            ModelSection(
                snapshot = current,
                testResult = testResult,
                testing = testing,
                onTest = {
                    scope.launch {
                        testing = true
                        testResult = useCases.probeModelConnection()
                        testing = false
                    }
                },
                onOpenAiConfig = onOpenAiConfig,
            )
            VoiceSection(current, onProbeAgain = { refresh() })
            RemindersSection(current, onRefresh = { refresh() })
            ClockHandoffSection(
                snapshot = current,
                onOpenClock = {
                    scope.launch {
                        clockHandoffUseCases.openAlarmList()
                            .onSuccess { report("已打开系统时钟：请自行核对是否保存；待核对不等于已保存。", false) }
                            .onFailure { report(it.message ?: "无法打开系统时钟", true) }
                    }
                },
            )
            FailuresSection(
                snapshot = current,
                onClear = { scope.launch { useCases.clearFailures() } },
            )
            ExportSection(
                snapshot = current,
                onExport = {
                    scope.launch {
                        useCases.export(current, exportDirectory)
                            .onSuccess {
                                exportedText = it.readText()
                                report("已导出脱敏诊断文本：${it.absolutePath}（不会自动发送）", false)
                            }
                            .onFailure { report(it.message ?: "导出失败", true) }
                    }
                },
            )
        }

        Text(
            "说明：本页不会显示 API Key、原始录音或完整待办/日程内容；" +
                "诊断也不能替代真机核对（如 ColorOS 在锁屏/省电下的投递窗口）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Text(
            "应用版本：$appVersion",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(bottom = 24.dp),
        )
    }

    exportedText?.let { text ->
        AlertDialog(
            onDismissRequest = { exportedText = null },
            title = { Text("脱敏诊断文本（不会自动发送）") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(text, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { exportedText = null }) { Text("关闭") } },
        )
    }
}

@Composable
private fun PermissionsSection(snapshot: DiagnosticsSnapshot, fixLauncher: ReminderFixLauncher) {
    val permissions = snapshot.permissions
    SectionCard("系统权限") {
        StatusRow(
            label = "通知",
            ok = permissions.notificationsAllowed,
            okText = "正常",
            badText = "不可用：提醒可能不可见",
        ) {
            if (permissions.notificationsNeedFix) {
                TextButton(onClick = { fixLauncher.openNotificationSettings() }) { Text("去开启通知") }
            }
        }
        StatusRow(
            label = "精确提醒",
            ok = permissions.exactAlarmsAllowed,
            okText = "正常",
            badText = "不可用：提醒可能无法准时",
        ) {
            if (permissions.exactAlarmsNeedFix) {
                TextButton(onClick = { fixLauncher.openExactAlarmSettings() }) { Text("去授权") }
            }
        }
        StatusRow(
            label = "电池优化",
            ok = permissions.batteryOptimizationsIgnored,
            okText = "不受限制",
            badText = "受限：后台可能被延迟",
        ) {
            if (permissions.batteryOptimizationNeedsFix) {
                TextButton(onClick = { fixLauncher.openBatteryOptimizationSettings() }) { Text("电池设置") }
            }
        }
        Text(permissions.manualFallbackNote, style = MaterialTheme.typography.bodySmall)
        Text(
            "ColorOS 等厂商系统还需单独允许自启动与后台运行；具体名称以手机实际设置页为准。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun ModelSection(
    snapshot: DiagnosticsSnapshot,
    testResult: ModelConnectionTest?,
    testing: Boolean,
    onTest: () -> Unit,
    onOpenAiConfig: () -> Unit,
) {
    val model = snapshot.model
    SectionCard("模型连接") {
        Text(
            if (model.configured) {
                "已配置：模型 ${model.modelName ?: "未知"}"
            } else {
                "未配置：AI 对话与提案不可用，本地手动功能仍可用。"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        model.baseUrl?.let {
            Text("Base URL：$it", style = MaterialTheme.typography.bodySmall)
        }
        testResult?.let {
            Text(
                "本次测试（${formatTestTime(it.testedAtMillis)}）：${it.message}",
                color = if (it.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            "测试结果只在本次页面会话内有效；刷新后不显示旧的成功状态。连接失败会写入脱敏的最近失败。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Row {
            OutlinedButton(onClick = onTest, enabled = !testing, modifier = Modifier.padding(end = 8.dp)) {
                Text(if (testing) "测试中…" else "测试连接")
            }
            OutlinedButton(onClick = onOpenAiConfig) { Text("去配置模型") }
        }
    }
}

@Composable
private fun VoiceSection(snapshot: DiagnosticsSnapshot, onProbeAgain: () -> Unit) {
    val voice = snapshot.voice
    SectionCard("语音引擎") {
        Text(
            "语音识别：${recognitionText(voice.recognitionAvailable)}",
            style = MaterialTheme.typography.bodyMedium,
            color = if (voice.recognitionUnavailable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "中文播报：${speechOutputText(voice.speechOutput)}",
            style = MaterialTheme.typography.bodyMedium,
            color = if (voice.speechOutputUnavailable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        voice.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(voice.manualFallbackNote, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onProbeAgain) { Text("重新检测") }
    }
}

@Composable
private fun RemindersSection(snapshot: DiagnosticsSnapshot, onRefresh: () -> Unit) {
    val reminders = snapshot.reminders
    SectionCard("知时鹿自有提醒（已调度 ≠ 已投递）") {
        Text(
            reminders.nextReminderAtMillis?.let {
                "下次提醒：${formatReminderTime(it)}（${ownerText(reminders.nextReminderOwner)}）"
            } ?: "下次提醒：无",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "待投递调度：${reminders.pendingScheduleCount} 条；未就绪：${reminders.notReadyCount} 条；" +
                "未确认错过：${reminders.outstandingMissedCount} 条",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "最近核对：${reminders.lastRestoreTriggerLabel ?: "尚未核对"}" +
                (reminders.lastRestoreAtMillis?.let { "（${formatReminderTime(it)}）" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(reminders.schedulingNote, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onRefresh) { Text("重新核对并重建") }
    }
}

@Composable
private fun ClockHandoffSection(snapshot: DiagnosticsSnapshot, onOpenClock: () -> Unit) {
    val clock = snapshot.clockHandoff
    SectionCard("系统时钟交接（与自有提醒分开）") {
        Text(
            "待核对：${clock.pendingCheckCount} 条；失败/不可用：${clock.failedCount} 条",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("最近状态：${clock.latestStatusLabel ?: "无记录"}", style = MaterialTheme.typography.bodySmall)
        Text(clock.note, style = MaterialTheme.typography.bodySmall)
        Text(
            "回退选择：可改用知时鹿自有提醒；若系统闹钟与自有提醒同时存在，可能重复响铃，请自行核对。",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = onOpenClock) { Text("打开系统时钟核对") }
    }
}

@Composable
private fun FailuresSection(snapshot: DiagnosticsSnapshot, onClear: () -> Unit) {
    SectionCard("最近失败（已脱敏）") {
        if (snapshot.recentFailures.isEmpty()) {
            Text("暂无失败记录。", style = MaterialTheme.typography.bodySmall)
        } else {
            snapshot.recentFailures.forEach { failure ->
                Text(
                    "[${failure.category.label}] ${formatReminderTime(failure.occurredAtMillis)} ${failure.summary}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onClear) { Text("清空失败记录") }
        }
    }
}

@Composable
private fun ExportSection(snapshot: DiagnosticsSnapshot, onExport: () -> Unit) {
    SectionCard("导出脱敏诊断文本") {
        Text(
            "导出为纯文本文件，保存在本机应用目录；不含 API Key、原始录音或完整待办/日程内容，" +
                "不会自动分享或上传。",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = onExport) { Text("导出脱敏诊断文本") }
        Text(
            "提示：导出的是当前快照（${formatReminderTime(snapshot.capturedAtMillis)}）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun StatusRow(
    label: String,
    ok: Boolean,
    okText: String,
    badText: String,
    action: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (ok) okText else badText,
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
        action()
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

private fun formatReminderTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(diagnosticsTimeFmt)

private fun formatTestTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(diagnosticsTimeFmt)

private fun recognitionText(available: Boolean?): String = when (available) {
    true -> "可用"
    false -> "不可用（可改用键盘输入）"
    null -> "未确认（探测失败）"
}

private fun speechOutputText(availability: SpeechEngineAvailability): String = when (availability) {
    SpeechEngineAvailability.AVAILABLE -> "可用"
    SpeechEngineAvailability.CHINESE_UNAVAILABLE -> "不支持中文（文字回复仍保留）"
    SpeechEngineAvailability.ENGINE_MISSING -> "缺少可用引擎（文字回复仍保留）"
    SpeechEngineAvailability.UNKNOWN -> "未确认（探测超时或失败）"
}

private fun ownerText(owner: ReminderOwner?): String = when (owner) {
    ReminderOwner.TASK -> "待办提醒"
    ReminderOwner.CALENDAR -> "日程提醒"
    null -> "未知归属"
}
