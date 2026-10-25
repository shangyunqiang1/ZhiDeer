package com.zhideer.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zhideer.app.domain.model.TaskPriority
import com.zhideer.app.domain.model.TodoTask
import com.zhideer.app.domain.usecase.TaskInput
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val editorDateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val editorTimeFmt = DateTimeFormatter.ofPattern("HH:mm")

/** 新建/编辑待办：标题、优先级、类别、日期截止或时刻截止（互斥）。非法输入即时提示。 */
@Composable
fun TaskEditorDialog(
    zoneId: ZoneId,
    initial: TodoTask?,
    onDismiss: () -> Unit,
    onSave: (TaskInput) -> Unit,
) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var category by remember { mutableStateOf(initial?.category ?: "") }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var priority by remember { mutableStateOf(initial?.priority ?: TaskPriority.NORMAL) }
    var dueKind by remember {
        mutableStateOf(
            when {
                initial?.dueEpochDay != null -> DueKind.DATE
                initial?.dueAtMillis != null -> DueKind.TIME
                else -> DueKind.NONE
            },
        )
    }
    var dateText by remember {
        mutableStateOf(
            initial?.dueEpochDay?.let { LocalDate.ofEpochDay(it).format(editorDateFmt) } ?: "",
        )
    }
    var timeText by remember {
        mutableStateOf(
            initial?.dueAtMillis?.let {
                Instant.ofEpochMilli(it).atZone(zoneId).toLocalTime().format(editorTimeFmt)
            } ?: "",
        )
    }
    var recurrenceRule by remember { mutableStateOf(initial?.recurrenceRule) }
    var showRecurrence by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun buildInput(): TaskInput? {
        if (title.isBlank()) {
            error = "标题不能为空"
            return null
        }
        val dueDay: Long?
        val dueAt: Long?
        when (dueKind) {
            DueKind.NONE -> { dueDay = null; dueAt = null }
            DueKind.DATE -> {
                val d = runCatching { LocalDate.parse(dateText.trim(), editorDateFmt) }.getOrNull()
                if (d == null) {
                    error = "日期格式应为 yyyy-MM-dd"
                    return null
                }
                dueDay = d.toEpochDay(); dueAt = null
            }
            DueKind.TIME -> {
                val d = runCatching { LocalDate.parse(dateText.trim(), editorDateFmt) }.getOrNull()
                val t = runCatching { LocalTime.parse(timeText.trim(), editorTimeFmt) }.getOrNull()
                if (d == null || t == null) {
                    error = "时刻格式应为 yyyy-MM-dd 与 HH:mm"
                    return null
                }
                dueDay = null
                dueAt = d.atTime(t).atZone(zoneId).toInstant().toEpochMilli()
            }
        }
        error = null
        return TaskInput(
            title = title,
            note = note,
            priority = priority,
            category = category,
            dueEpochDay = dueDay,
            dueAtMillis = dueAt,
            dueZoneId = zoneId.id,
            recurrenceRule = recurrenceRule,
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建待办" else "编辑待办") },        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it; error = null },
                    label = { Text("标题（必填）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("类别（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row {
                    TaskPriority.entries.forEach { p ->
                        FilterChip(
                            selected = priority == p,
                            onClick = { priority = p },
                            label = { Text(p.label) },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }
                Text("截止（可选）", style = MaterialTheme.typography.labelLarge)
                Row {
                    FilterChip(
                        selected = dueKind == DueKind.NONE,
                        onClick = { dueKind = DueKind.NONE; error = null },
                        label = { Text("无") },
                    )
                    FilterChip(
                        selected = dueKind == DueKind.DATE,
                        onClick = { dueKind = DueKind.DATE; error = null },
                        label = { Text("日期截止") },
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    FilterChip(
                        selected = dueKind == DueKind.TIME,
                        onClick = { dueKind = DueKind.TIME; error = null },
                        label = { Text("时刻截止") },
                    )
                }
                if (dueKind != DueKind.NONE) {
                    OutlinedTextField(
                        value = dateText,
                        onValueChange = { dateText = it; error = null },
                        label = { Text("日期 yyyy-MM-dd") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (dueKind == DueKind.TIME) {
                    OutlinedTextField(
                        value = timeText,
                        onValueChange = { timeText = it; error = null },
                        label = { Text("时刻 HH:mm") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text("重复（可选）", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    FilterChip(
                        selected = recurrenceRule != null,
                        onClick = { showRecurrence = true },
                        label = { Text(if (recurrenceRule == null) "不重复" else "已设置") },
                    )
                    if (recurrenceRule != null) {
                        TextButton(onClick = { recurrenceRule = null }) { Text("清除") }
                    }
                }
                RecurrenceRuleSummaryText(recurrenceRule)
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                buildInput()?.let(onSave)
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
    if (showRecurrence) {
        val anchorDay = initial?.let {
            Instant.ofEpochMilli(it.createdAt).atZone(zoneId).toLocalDate().toEpochDay()
        } ?: LocalDate.now(zoneId).toEpochDay()
        RecurrenceEditorDialog(
            anchorEpochDay = anchorDay,
            todayEpochDay = LocalDate.now(zoneId).toEpochDay(),
            initialRule = recurrenceRule,
            onDismiss = { showRecurrence = false },
            onSave = { recurrenceRule = it; showRecurrence = false },
        )
    }
}

private enum class DueKind { NONE, DATE, TIME }

/** 待办详情：查看全部字段，提供编辑、延期到明天、取消、删除（回收站）。 */
@Composable
fun TaskDetailDialog(
    task: TodoTask,
    zoneId: ZoneId,
    subTaskUseCases: com.zhideer.app.domain.usecase.SubTaskUseCases,
    linkUseCases: com.zhideer.app.domain.usecase.TaskScheduleLinkUseCases,
    calendarUseCases: com.zhideer.app.domain.usecase.CalendarUseCases,
    reminderUseCases: com.zhideer.app.domain.usecase.ReminderUseCases,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onPostponeTomorrow: () -> Unit,
    onCancelTask: () -> Unit,
    onTrash: () -> Unit,
) {
    var showLinkDialog by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(task.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("优先级：${task.priority.label}")
                if (task.category.isNotBlank()) Text("分类：${task.category}")
                if (task.note.isNotBlank()) Text("备注：${task.note}")
                task.dueEpochDay?.let {
                    Text("日期截止：${LocalDate.ofEpochDay(it).format(editorDateFmt)}（不隐含时刻）")
                }
                task.dueAtMillis?.let {
                    val zoned = Instant.ofEpochMilli(it).atZone(zoneId)
                    Text("时刻截止：${zoned.format(editorDateFmt)} ${zoned.format(editorTimeFmt)}")
                }
                Text("状态：${statusLabel(task)}")
                task.postponedAt?.let { Text("延期于：${formatMillis(it, zoneId)}") }
                task.completedAt?.let { Text("完成于：${formatMillis(it, zoneId)}") }
                task.cancelledAt?.let { Text("取消于：${formatMillis(it, zoneId)}") }
                SubTasksPanel(
                    useCases = subTaskUseCases,
                    parentId = task.id,
                    zoneId = zoneId,
                    modifier = Modifier.padding(top = 8.dp),
                )
                com.zhideer.app.ui.RemindersPanel(
                    useCases = reminderUseCases,
                    taskId = task.id,
                    zoneId = zoneId,
                    dueAtMillis = task.dueAtMillis,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = { showLinkDialog = true }) { Text("关联日程") }
                TextButton(onClick = onPostponeTomorrow) { Text("延期到明天") }
                TextButton(onClick = onCancelTask) { Text("取消待办") }
                TextButton(onClick = onTrash) { Text("删除") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
    if (showLinkDialog) {
        TaskScheduleLinkDialog(
            task = task,
            linkUseCases = linkUseCases,
            calendarUseCases = calendarUseCases,
            zoneId = zoneId,
            onDismiss = { showLinkDialog = false },
            onOpenEntry = { showLinkDialog = false },
        )
    }
}

private fun statusLabel(task: TodoTask): String = when (task.status) {
    com.zhideer.app.domain.model.TaskStatus.ACTIVE -> "进行中"
    com.zhideer.app.domain.model.TaskStatus.COMPLETED -> "已完成"
    com.zhideer.app.domain.model.TaskStatus.CANCELLED -> "已取消"
}

private fun formatMillis(millis: Long, zoneId: ZoneId): String {
    val zoned = Instant.ofEpochMilli(millis).atZone(zoneId)
    return zoned.format(editorDateFmt) + " " + zoned.format(editorTimeFmt)
}
