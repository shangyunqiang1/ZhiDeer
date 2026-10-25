package com.zhideer.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zhideer.app.domain.model.ReminderDisplay
import com.zhideer.app.domain.model.formatAt
import com.zhideer.app.domain.usecase.CalendarReminderUseCases
import com.zhideer.app.domain.usecase.ReminderInput
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val calendarReminderDateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val calendarReminderTimeFmt = DateTimeFormatter.ofPattern("HH:mm")

/** 日程提醒添加表单的模式：相对开始提前（随日程移动）或独立时刻（不随移动）。 */
private enum class CalendarReminderAddMode { RELATIVE, ABSOLUTE }

/**
 * 日程提醒面板（T09/#10）：挂在日程详情中展示。
 * - 无提醒时明确展示无提醒状态；每条提醒显示换算后的具体时间与类型；
 * - 支持添加相对（开始前提前量，随日程改期移动）与独立时刻提醒、删除、启停；
 * - 保存前在表单内预览将提醒的具体时间；相同提醒由用例去重；
 * - 精确闹钟能力不足：保留提醒并展示未就绪原因，提供“去授权”修复入口
 *   （跳 ACTION_REQUEST_SCHEDULE_EXACT_ALARM）与“重新检查”重试；
 *   降级为非精确提醒必须经用户在对话框中显式确认，不悄悄降级；
 * - 已过期项明确标记“已过期，将不再提醒”；日程结束不影响提醒记录（结束≠完成）。
 * Root 将其挂接进日程详情即可（例如 CalendarDetailDialog 内），无需改动 CalendarScreen。
 */
@Composable
fun CalendarRemindersPanel(
    useCases: CalendarReminderUseCases,
    entryId: String,
    zoneId: ZoneId,
    startMillis: Long,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val displays by remember(entryId) {
        useCases.observeDisplaysByEntry(entryId)
    }.collectAsState(initial = emptyList())
    var mode by remember { mutableStateOf(CalendarReminderAddMode.RELATIVE) }
    var minutesText by remember { mutableStateOf("") }
    var dateText by remember { mutableStateOf("") }
    var timeText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var notReadyDialogShown by remember { mutableStateOf(false) }

    fun onFailure(e: Throwable) {
        error = e.message
    }

    fun currentInputs(): List<ReminderInput> = displays.map {
        if (it.reminder.isRelative) {
            ReminderInput(relativeOffsetMillis = it.reminder.relativeOffsetMillis)
        } else {
            ReminderInput(remindAtMillis = it.reminder.remindAtMillis)
        }
    }

    fun add(input: ReminderInput?) {
        if (input == null) return
        scope.launch {
            useCases.setReminders(entryId, currentInputs() + input)
                .onSuccess { saved ->
                    error = null
                    minutesText = ""
                    dateText = ""
                    timeText = ""
                    notReadyDialogShown = saved.any { it.notReadyReason != null }
                }
                .onFailure(::onFailure)
        }
    }

    fun parseInput(): ReminderInput? = when (mode) {
        CalendarReminderAddMode.RELATIVE -> {
            val minutes = minutesText.trim().toLongOrNull()
            when {
                minutes == null || minutes <= 0 -> {
                    error = "请输入大于 0 的提前分钟数"
                    null
                }
                else -> ReminderInput(relativeOffsetMillis = minutes * 60_000L)
            }
        }
        CalendarReminderAddMode.ABSOLUTE -> {
            val d = runCatching { LocalDate.parse(dateText.trim(), calendarReminderDateFmt) }.getOrNull()
            val t = runCatching { LocalTime.parse(timeText.trim(), calendarReminderTimeFmt) }.getOrNull()
            when {
                d == null || t == null -> {
                    error = "请输入正确的日期（yyyy-MM-dd）和时间（HH:mm）"
                    null
                }
                else -> ReminderInput(
                    remindAtMillis = d.atTime(t).atZone(zoneId).toInstant().toEpochMilli(),
                )
            }
        }
    }

    // 保存前逐项显示具体时间：表单预览本次将提醒的时刻
    val previewText: String? = when (mode) {
        CalendarReminderAddMode.RELATIVE -> {
            val minutes = minutesText.trim().toLongOrNull()
            if (minutes != null && minutes > 0) {
                calendarPreviewMillis(startMillis - minutes * 60_000L, zoneId)
            } else {
                null
            }
        }
        CalendarReminderAddMode.ABSOLUTE -> {
            val d = runCatching { LocalDate.parse(dateText.trim(), calendarReminderDateFmt) }.getOrNull()
            val t = runCatching { LocalTime.parse(timeText.trim(), calendarReminderTimeFmt) }.getOrNull()
            if (d != null && t != null) {
                calendarPreviewMillis(d.atTime(t).atZone(zoneId).toInstant().toEpochMilli(), zoneId)
            } else {
                null
            }
        }
    }

    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("提醒", style = MaterialTheme.typography.titleSmall)
            if (displays.any { it.reminder.notReadyReason != null }) {
                Text(
                    "有提醒未就绪",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
        if (displays.isEmpty()) {
            // 无提醒状态：提醒与日程开始时间分别设置，创建日程不自动产生提醒
            Text(
                "暂无提醒，显式添加后才会在此时间通知你",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        displays.forEach { item ->
            CalendarReminderRow(
                item = item,
                zoneId = zoneId,
                onToggle = { checked ->
                    scope.launch {
                        useCases.setEnabled(item.reminder.id, checked).onFailure(::onFailure)
                    }
                },
                onDelete = {
                    scope.launch {
                        useCases.deleteReminder(item.reminder.id).onFailure(::onFailure)
                    }
                },
            )
            HorizontalDivider()
        }
        if (displays.any { it.reminder.notReadyReason != null }) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "部分提醒因缺少精确闹钟权限暂未就绪",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    scope.launch {
                        useCases.repairNotReady(entryId)
                            .onSuccess { repaired ->
                                notReadyDialogShown = repaired.any { it.notReadyReason != null }
                            }
                            .onFailure(::onFailure)
                    }
                }) { Text("重新检查") }
                TextButton(onClick = { launchExactAlarmSettings(context) }) { Text("去授权") }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            FilterChip(
                selected = mode == CalendarReminderAddMode.RELATIVE,
                onClick = { mode = CalendarReminderAddMode.RELATIVE; error = null },
                label = { Text("开始前") },
            )
            FilterChip(
                selected = mode == CalendarReminderAddMode.ABSOLUTE,
                onClick = { mode = CalendarReminderAddMode.ABSOLUTE; error = null },
                label = { Text("独立时刻") },
            )
        }
        when (mode) {
            CalendarReminderAddMode.RELATIVE -> OutlinedTextField(
                value = minutesText,
                onValueChange = { minutesText = it; error = null },
                label = { Text("提前分钟数（相对开始，随日程移动）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            CalendarReminderAddMode.ABSOLUTE -> Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value = dateText,
                    onValueChange = { dateText = it; error = null },
                    label = { Text("日期 yyyy-MM-dd") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = timeText,
                    onValueChange = { timeText = it; error = null },
                    label = { Text("时间 HH:mm") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        previewText?.let {
            Text(
                "将提醒于 $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        TextButton(onClick = { add(parseInput()) }) { Text("添加提醒") }
    }

    if (notReadyDialogShown) {
        AlertDialog(
            onDismissRequest = { notReadyDialogShown = false },
            title = { Text("精确提醒权限不足") },
            text = {
                Text("提醒已保存但暂无法准时触发（未授予精确闹钟权限）。你可以去系统设置授权，或显式确认后降级为非精确提醒（可能延迟）。")
            },
            confirmButton = {
                TextButton(onClick = {
                    notReadyDialogShown = false
                    launchExactAlarmSettings(context)
                }) { Text("去授权") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { notReadyDialogShown = false }) { Text("暂不处理") }
                    // 降级为非精确提醒：必须在此显式确认，不悄悄降级
                    TextButton(onClick = {
                        notReadyDialogShown = false
                        scope.launch {
                            useCases.setReminders(entryId, currentInputs(), allowInexact = true)
                                .onFailure(::onFailure)
                        }
                    }) { Text("确认降级") }
                }
            },
        )
    }
}

@Composable
private fun CalendarReminderRow(
    item: ReminderDisplay,
    zoneId: ZoneId,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val reminder = item.reminder
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = reminder.enabled, onCheckedChange = onToggle)
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(
                    if (reminder.isRelative) {
                        "开始前 ${reminder.relativeOffsetMillis!! / 60_000L} 分钟"
                    } else {
                        "独立时刻"
                    },
                    item.formatAt(zoneId),
                ).joinToString("　"),
                style = if (reminder.enabled) {
                    MaterialTheme.typography.bodyMedium
                } else {
                    MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.outline)
                },
            )
            if (item.isOverdue) {
                Text(
                    "已过期，将不再提醒",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            reminder.notReadyReason?.let {
                Text(
                    "未就绪：$it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!reminder.enabled) {
                Text(
                    "已停用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        TextButton(onClick = onDelete) { Text("删除") }
    }
}

private fun calendarPreviewMillis(millis: Long, zoneId: ZoneId): String {
    val zoned = Instant.ofEpochMilli(millis).atZone(zoneId)
    return zoned.format(calendarReminderDateFmt) + " " + zoned.format(calendarReminderTimeFmt)
}
