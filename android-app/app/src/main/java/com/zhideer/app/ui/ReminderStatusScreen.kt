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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zhideer.app.domain.model.ReminderRestoreTrigger
import com.zhideer.app.domain.usecase.ReminderRestoreUseCases
import com.zhideer.app.platform.ReminderFixLauncher
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val reminderStatusTimeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * 提醒状态与设备变化恢复页（T10/#11，规格用户故事 40–43、89 的提醒部分）。
 *
 * - 设备能力如实展示（通知/精确提醒/电池优化），每项提供系统设置修复入口；
 * - 最近一次核对报告：重建数量、未就绪、下次提醒与中文说明；
 * - 错过提醒合并为一张提示卡：不逐条补响、不自动延期/完成；
 * - 时区核对项：保留创建时区，用户确认后才按当前时区调整，绝不静默修改；
 * - 明确说明“已调度 ≠ 已投递”，ColorOS 15 实测仍有 2–4 分钟投递窗口。
 */
@Composable
fun ReminderStatusScreen(
    useCases: ReminderRestoreUseCases,
    fixLauncher: ReminderFixLauncher,
    zoneId: ZoneId,
) {
    val scope = rememberCoroutineScope()
    val report by useCases.observeLastReport().collectAsState(initial = null)
    val missed by useCases.observeOutstandingMissed().collectAsState(initial = emptyList())
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            runCatching { useCases.restore(ReminderRestoreTrigger.MANUAL) }
                .onSuccess { error = null }
                .onFailure { error = it.message }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(8.dp))
        Text("提醒状态与设备变化恢复", style = MaterialTheme.typography.headlineSmall)
        Text(
            "设备重启、系统时间或时区变化、权限变化后，按真实本地记录重建未来提醒；" +
                "错过的提醒只合并提示，不逐条补响，也不会自动延期或完成事项。",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

        SectionCard("设备能力（点击右侧按钮前往系统设置修复）") {
            val environment = report?.environment
            CapabilityRow("通知", environment?.notificationsAllowed, "去开启通知") {
                fixLauncher.openNotificationSettings()
            }
            CapabilityRow("精确提醒", environment?.exactAlarmsAllowed, "去授权") {
                fixLauncher.openExactAlarmSettings()
            }
            CapabilityRow("电池优化", environment?.batteryOptimizationsIgnored, "电池设置") {
                fixLauncher.openBatteryOptimizationSettings()
            }
            Text(
                "ColorOS 等厂商系统还需单独允许自启动与后台运行；具体名称以手机实际设置页为准。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        report?.let { current ->
            SectionCard("最近一次核对") {
                Text(
                    "触发：${current.trigger.label}；重建未来提醒 ${current.futureScheduledCount} 条，" +
                        "未就绪 ${current.notReadyCount} 条，跳过 ${current.skippedCount} 条。",
                    style = MaterialTheme.typography.bodySmall,
                )
                current.nextReminder?.let { next ->
                    Text(
                        "下次提醒：${next.ownerTitle}　${formatReminderAt(next.scheduledAtMillis, zoneId)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                current.messages.forEach { message ->
                    Text("· $message", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (missed.isNotEmpty()) {
            SectionCard("错过的提醒（合并提示）") {
                Text(
                    "有 ${missed.size} 条提醒在设备关闭、休眠或省电限制期间错过。" +
                        "不会逐条补响，也不会自动延期或完成事项。",
                    style = MaterialTheme.typography.bodySmall,
                )
                missed.forEach { entry ->
                    Text(
                        "${entry.ownerTitle}　${formatReminderAt(entry.scheduledAtMillis, zoneId)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = {
                    scope.launch {
                        val cleared = useCases.acknowledgeMissed()
                        notice = "已确认知悉 $cleared 条错过提醒。它们不会补响。"
                    }
                }) { Text("我已知悉，不再提示") }
            }
        }

        val zoneAnchors = report?.zoneAnchors.orEmpty()
        if (zoneAnchors.isNotEmpty()) {
            SectionCard("时区核对（安排保留创建时区）") {
                Text(
                    "以下安排仍按创建时区保存；保持原时区无需任何操作，确认调整后才会按当前设备时区重新解释。",
                    style = MaterialTheme.typography.bodySmall,
                )
                zoneAnchors.forEach { anchor ->
                    Text(
                        "${anchor.ownerTitle}：创建时区 ${anchor.anchoredZoneId}，当前设备 ${anchor.deviceZoneId}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    anchor.nextReminderAtMillis?.let { nextAt ->
                        val anchoredZone = runCatching { ZoneId.of(anchor.anchoredZoneId) }.getOrElse { zoneId }
                        Text(
                            "　下一次提醒（按创建时区）：${formatReminderAt(nextAt, anchoredZone)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = {
                        scope.launch {
                            useCases.confirmZoneAdjustment(anchor.ownerType, anchor.ownerId)
                                .onSuccess {
                                    notice = "已按当前时区调整「${anchor.ownerTitle}」，相对提醒已随新时刻移动。"
                                    refresh()
                                }
                                .onFailure { error = it.message }
                        }
                    }) { Text("按当前时区调整") }
                }
            }
        }

        OutlinedButton(onClick = { notice = null; refresh() }, modifier = Modifier.padding(vertical = 8.dp)) {
            Text("立即重新核对并重建")
        }

        Text(
            "如实说明：提醒“已调度”不等于“已投递”。OPPO Reno10（ColorOS 15）实测锁屏待机投递延迟 " +
                "30 秒～2 分 31 秒，省电模式下曾达 6 分 13 秒；本应用无法保证所有机型、所有省电设置下精确到秒，" +
                "也不会在无法保证时假装准时。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(bottom = 16.dp),
        )
    }
}

@Composable
private fun CapabilityRow(
    label: String,
    available: Boolean?,
    fixLabel: String,
    onFix: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                when (available) {
                    true -> "正常"
                    false -> "不可用（提醒可能无法准时或不可见）"
                    null -> "核对中"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (available == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onFix) { Text(fixLabel) }
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

private fun formatReminderAt(millis: Long, zoneId: ZoneId): String =
    Instant.ofEpochMilli(millis).atZone(zoneId).format(reminderStatusTimeFmt)
