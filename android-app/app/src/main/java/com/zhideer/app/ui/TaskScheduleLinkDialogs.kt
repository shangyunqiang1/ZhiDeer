package com.zhideer.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import com.zhideer.app.domain.model.CalendarEntry
import com.zhideer.app.domain.model.TaskFilter
import com.zhideer.app.domain.model.TaskStatus
import com.zhideer.app.domain.model.TodoTask
import com.zhideer.app.domain.usecase.CalendarUseCases
import com.zhideer.app.domain.usecase.TaskScheduleLinkUseCases
import com.zhideer.app.domain.usecase.TaskUseCases
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val linkDateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val linkTimeFmt = DateTimeFormatter.ofPattern("HH:mm")

/** 日程时间文案；同日只显示一次日期，跨日显示两端日期时刻。 */
private fun entryRangeLabel(entry: CalendarEntry): String {
    val zone = runCatching { ZoneId.of(entry.timezoneId) }.getOrDefault(ZoneId.systemDefault())
    val start = Instant.ofEpochMilli(entry.startMillis).atZone(zone)
    val end = Instant.ofEpochMilli(entry.endMillis).atZone(zone)
    return if (start.toLocalDate() == end.toLocalDate()) {
        "${start.format(linkDateFmt)} ${start.format(linkTimeFmt)} ～ ${end.format(linkTimeFmt)}"
    } else {
        "${start.format(linkDateFmt)} ${start.format(linkTimeFmt)} ～ ${end.format(linkDateFmt)} ${end.format(linkTimeFmt)}"
    }
}

private fun taskStatusLabel(task: TodoTask): String = when (task.status) {
    TaskStatus.ACTIVE -> "进行中"
    TaskStatus.COMPLETED -> "已完成"
    TaskStatus.CANCELLED -> "已取消"
}

/**
 * 待办的关联日程管理对话框（T06/#7，供 Root 从待办详情挂接）：
 * 列出该待办的全部关联日程（含时间）、跳转日程详情、移除关联；
 * 添加关联支持从某日日程选择（已关联其他待办的日程不可选）或新建日程并自动关联。
 */
@Composable
fun TaskScheduleLinkDialog(
    task: TodoTask,
    linkUseCases: TaskScheduleLinkUseCases,
    calendarUseCases: CalendarUseCases,
    zoneId: ZoneId,
    onDismiss: () -> Unit,
    onOpenEntry: (CalendarEntry) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var pickingDay by remember { mutableStateOf(false) }
    var dayText by remember { mutableStateOf(LocalDate.now(zoneId).format(linkDateFmt)) }
    var creatingSchedule by remember { mutableStateOf(false) }

    val linked by remember(task.id) { linkUseCases.observeEntriesForTask(task.id) }
        .collectAsState(initial = emptyList())
    val day = runCatching { LocalDate.parse(dayText.trim(), linkDateFmt) }.getOrNull()
    val dayEntries by remember(day) { calendarUseCases.observeOnDay(day ?: LocalDate.now(zoneId)) }
        .collectAsState(initial = emptyList())
    // 可选日程：该日未关联的日程；已关联本待办的显示状态，已关联其他待办的不出现
    val candidates = dayEntries.filter { it.taskId == null || it.taskId == task.id }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("「${task.title}」的关联日程") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (linked.isEmpty()) {
                    Text("还没有关联日程。日程表示计划在该时段处理这个待办。", style = MaterialTheme.typography.bodySmall)
                }
                linked.forEach { entry ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.title, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                entryRangeLabel(entry),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = { onOpenEntry(entry) }) { Text("详情") }
                        TextButton(onClick = {
                            scope.launch {
                                linkUseCases.unlink(entry.id)
                                    .onSuccess { error = null }
                                    .onFailure { error = it.message }
                            }
                        }) { Text("移除关联") }
                    }
                    HorizontalDivider()
                }
                TextButton(onClick = { pickingDay = !pickingDay }) {
                    Text(if (pickingDay) "收起添加关联" else "添加关联")
                }
                if (pickingDay) {
                    OutlinedTextField(
                        value = dayText,
                        onValueChange = { dayText = it; error = null },
                        label = { Text("从某日的日程中选择（yyyy-MM-dd）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (day == null) {
                        Text("日期格式应为 yyyy-MM-dd", color = MaterialTheme.colorScheme.error)
                    } else if (candidates.isEmpty()) {
                        Text("这一天没有可关联的日程", style = MaterialTheme.typography.bodySmall)
                    } else {
                        LazyColumn(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            items(candidates, key = { it.id }) { entry ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(entry.title, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            entryRangeLabel(entry),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    if (entry.taskId == task.id) {
                                        Text("已关联", style = MaterialTheme.typography.labelSmall)
                                    } else {
                                        TextButton(onClick = {
                                            scope.launch {
                                                linkUseCases.link(task.id, entry.id)
                                                    .onSuccess { error = null }
                                                    .onFailure { error = it.message }
                                            }
                                        }) { Text("关联") }
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                    Button(onClick = { creatingSchedule = true }) {
                        Text("新建日程并关联")
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )

    if (creatingSchedule) {
        CalendarEditorDialog(
            deviceZone = zoneId,
            initial = null,
            onDismiss = { creatingSchedule = false },
            onSave = { input ->
                scope.launch {
                    calendarUseCases.create(input)
                        .onSuccess { entry ->
                            linkUseCases.link(task.id, entry.id)
                                .onSuccess {
                                    creatingSchedule = false
                                    error = null
                                }
                                .onFailure { error = it.message }
                        }
                        .onFailure { error = it.message }
                }
            },
        )
    }
}

/**
 * 日程的关联待办管理对话框（T06/#7，供 Root 从日程详情挂接）：
 * 显示关联待办（标题实时读取，改标题不破坏关联）、跳转待办详情、解除关联；
 * 未关联时可从待办列表中选择建立关联。
 */
@Composable
fun ScheduleTaskLinkDialog(
    entry: CalendarEntry,
    linkUseCases: TaskScheduleLinkUseCases,
    taskUseCases: TaskUseCases,
    zoneId: ZoneId,
    onDismiss: () -> Unit,
    onOpenTask: (TodoTask) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var pickingTask by remember { mutableStateOf(false) }

    val linkedTask by remember(entry.id) { linkUseCases.observeTaskForEntry(entry.id) }
        .collectAsState(initial = null)
    val tasks by remember { taskUseCases.observe(TaskFilter.ALL) }.collectAsState(initial = emptyList())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("「${entry.title}」的关联待办") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(entryRangeLabel(entry), style = MaterialTheme.typography.bodySmall)
                if (linkedTask == null) {
                    Text("该日程还没有关联待办。", style = MaterialTheme.typography.bodySmall)
                } else {
                    val task = linkedTask!!
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(task.title, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "状态：${taskStatusLabel(task)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = { onOpenTask(task) }) { Text("详情") }
                        TextButton(onClick = {
                            scope.launch {
                                linkUseCases.unlink(entry.id)
                                    .onSuccess { error = null }
                                    .onFailure { error = it.message }
                            }
                        }) { Text("解除关联") }
                    }
                }
                if (linkedTask == null) {
                    TextButton(onClick = { pickingTask = !pickingTask }) {
                        Text(if (pickingTask) "收起待办列表" else "从待办列表中选择")
                    }
                    if (pickingTask) {
                        if (tasks.isEmpty()) {
                            Text("还没有待办", style = MaterialTheme.typography.bodySmall)
                        }
                        tasks.forEach { task ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(task.title, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "状态：${taskStatusLabel(task)}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                TextButton(onClick = {
                                    scope.launch {
                                        linkUseCases.link(task.id, entry.id)
                                            .onSuccess {
                                                pickingTask = false
                                                error = null
                                            }
                                            .onFailure { error = it.message }
                                    }
                                }) { Text("关联") }
                            }
                            HorizontalDivider()
                        }
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/**
 * 完成待办时的未来日程选择对话框（T06/#7，供 Root 在完成动作前挂接）：
 * 存在尚未开始的关联日程时弹出，用户可「一并取消这些日程」（移入回收站）或保留（默认保留）。
 * 两种选择都不改变日程与待办各自的完成状态语义：日程没有完成状态，取消即回收站删除。
 */
@Composable
fun CompleteTaskFutureSchedulesDialog(
    task: TodoTask,
    futureEntries: List<CalendarEntry>,
    onDismiss: () -> Unit,
    onKeep: () -> Unit,
    onCancelSchedules: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("待办已完成，关联日程如何处理？") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("「${task.title}」已标记为已完成。它还有 ${futureEntries.size} 个尚未开始的关联日程：")
                futureEntries.forEach { entry ->
                    Text(
                        "· ${entry.title}（${entryRangeLabel(entry)}）",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
                Text(
                    "选择「保留日程」则这些日程照常保留；日程不会因待办完成而自动完成或取消。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onKeep) { Text("保留日程") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onCancelSchedules) { Text("一并取消这些日程") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
