package com.zhideer.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.zhideer.app.domain.model.TaskDue
import com.zhideer.app.domain.model.TaskFilter
import com.zhideer.app.domain.model.TaskPriority
import com.zhideer.app.domain.model.TaskStatus
import com.zhideer.app.domain.model.TodoTask
import com.zhideer.app.domain.usecase.CalendarUseCases
import com.zhideer.app.domain.usecase.SubTaskUseCases
import com.zhideer.app.domain.usecase.TaskInput
import com.zhideer.app.domain.usecase.TaskScheduleLinkUseCases
import com.zhideer.app.domain.usecase.TaskUseCases
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val timeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** 待办列表主界面：过滤、列表、新建/编辑、详情操作（完成/延期/取消/删除）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    useCases: TaskUseCases,
    zoneId: ZoneId,
    subTaskUseCases: SubTaskUseCases,
    linkUseCases: TaskScheduleLinkUseCases,
    calendarUseCases: CalendarUseCases,
    reminderUseCases: com.zhideer.app.domain.usecase.ReminderUseCases,
    recurrenceUseCases: com.zhideer.app.domain.usecase.RecurrenceUseCases,
    occurrenceSubTaskUseCases: com.zhideer.app.domain.usecase.OccurrenceSubTaskUseCases,
    initialDetailTask: TodoTask? = null,
    onDetailTaskConsumed: () -> Unit = {},
    initialCreating: Boolean = false,
    onCreatingConsumed: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf(TaskFilter.TODAY) }
    var editing by remember { mutableStateOf<TodoTask?>(null) }
    var creating by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<TodoTask?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var completeCandidate by remember { mutableStateOf<TodoTask?>(null) }
    var candidateFuture by remember { mutableStateOf(emptyList<com.zhideer.app.domain.model.CalendarEntry>()) }
    var cancelCandidate by remember { mutableStateOf<TodoTask?>(null) }
    var cancelFuture by remember { mutableStateOf(emptyList<com.zhideer.app.domain.model.CalendarEntry>()) }
    androidx.compose.runtime.LaunchedEffect(initialDetailTask) {
        if (initialDetailTask != null) {
            viewing = initialDetailTask
            onDetailTaskConsumed()
        }
    }
    androidx.compose.runtime.LaunchedEffect(initialCreating) {
        if (initialCreating) {
            creating = true
            onCreatingConsumed()
        }
    }

    val tasks by remember(filter) {
        useCases.observe(filter)
    }.collectAsState(initial = emptyList())

    Scaffold(
        topBar = { TopAppBar(title = { Text("知时鹿") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) { Text("＋") }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TaskFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = { Text(f.label) },
                    )
                }
            }
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (tasks.isEmpty()) {
                Text(
                    "这里还没有事项",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(tasks, key = { it.id }) { task ->
                    TaskRow(
                        task = task,
                        nowMillis = System.currentTimeMillis(),
                        onToggle = { completed ->
                            scope.launch {
                                if (completed) {
                                    val future = linkUseCases.futureEntriesForTask(task.id)
                                    if (future.isNotEmpty()) {
                                        completeCandidate = task
                                        candidateFuture = future
                                    } else {
                                        useCases.complete(task.id, true)
                                            .onFailure { error = it.message }
                                    }
                                } else {
                                    useCases.complete(task.id, false)
                                        .onFailure { error = it.message }
                                }
                            }
                        },
                        onClick = { viewing = task },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (creating) {
        TaskEditorDialog(
            zoneId = zoneId,
            initial = null,
            onDismiss = { creating = false },
            onSave = { input ->
                scope.launch {
                    useCases.create(input)
                        .onSuccess { creating = false; error = null }
                        .onFailure { error = it.message }
                }
            },
        )
    }
    editing?.let { task ->
        TaskEditorDialog(
            zoneId = zoneId,
            initial = task,
            onDismiss = { editing = null },
            onSave = { input ->
                scope.launch {
                    useCases.update(task.id, input)
                        .onSuccess { editing = null; error = null }
                        .onFailure { error = it.message }
                }
            },
        )
    }
    viewing?.let { task ->
        TaskDetailDialog(
            task = task,
            zoneId = zoneId,
            subTaskUseCases = subTaskUseCases,
            linkUseCases = linkUseCases,
            calendarUseCases = calendarUseCases,
            reminderUseCases = reminderUseCases,
            recurrenceUseCases = recurrenceUseCases,
            occurrenceSubTaskUseCases = occurrenceSubTaskUseCases,
            onDismiss = { viewing = null },
            onEdit = { viewing = null; editing = task },
            onPostponeTomorrow = {
                scope.launch {
                    val newDay = LocalDate.ofInstant(Instant.ofEpochMilli(System.currentTimeMillis()), zoneId)
                        .plusDays(1).toEpochDay()
                    useCases.postpone(task.id, newDay, null)
                        .onSuccess { viewing = null; error = null }
                        .onFailure { error = it.message }
                }
            },
            onCancelTask = {
                scope.launch {
                    val future = linkUseCases.futureEntriesForTask(task.id)
                    if (future.isNotEmpty()) {
                        cancelCandidate = task
                        cancelFuture = future
                    } else {
                        useCases.cancel(task.id).onSuccess { viewing = null; error = null }
                            .onFailure { error = it.message }
                    }
                }
            },
            onTrash = {
                scope.launch {
                    useCases.trash(task.id).onSuccess { viewing = null; error = null }
                        .onFailure { error = it.message }
                }
            },
        )
    }

    completeCandidate?.let { candidate ->
        CompleteTaskFutureSchedulesDialog(
            task = candidate,
            futureEntries = candidateFuture,
            onDismiss = { completeCandidate = null },
            onKeep = {
                scope.launch {
                    useCases.complete(candidate.id, true).onFailure { error = it.message }
                    completeCandidate = null
                }
            },
            onCancelSchedules = {
                scope.launch {
                    useCases.complete(candidate.id, true).onFailure { error = it.message }
                    linkUseCases.cancelFutureEntries(candidate.id)
                    completeCandidate = null
                }
            },
        )
    }

    cancelCandidate?.let { candidate ->
        CompleteTaskFutureSchedulesDialog(
            task = candidate,
            futureEntries = cancelFuture,
            onDismiss = { cancelCandidate = null },
            onKeep = {
                scope.launch {
                    useCases.cancel(candidate.id).onSuccess { viewing = null; error = null }
                        .onFailure { error = it.message }
                    cancelCandidate = null
                }
            },
            onCancelSchedules = {
                scope.launch {
                    useCases.cancel(candidate.id).onSuccess { viewing = null; error = null }
                        .onFailure { error = it.message }
                    linkUseCases.cancelFutureEntries(candidate.id)
                    cancelCandidate = null
                }
            },
            title = "待办已取消，关联日程如何处理？",
            leadText = "「${candidate.title}」将取消（保留记录并停止提醒）。它还有 ${cancelFuture.size} 个尚未开始的关联日程：",
        )
    }
}

@Composable
private fun TaskRow(
    task: TodoTask,
    nowMillis: Long,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = task.status == TaskStatus.COMPLETED,
            onCheckedChange = onToggle,
        )
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                task.title,
                style = if (task.status == TaskStatus.COMPLETED) MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.outline,
                ) else MaterialTheme.typography.bodyLarge,
            )
            val dueText = dueLabel(task, nowMillis)
            if (dueText != null) {
                Text(dueText, style = MaterialTheme.typography.bodySmall)
            }
            if (task.category.isNotBlank()) {
                Text("分类：${task.category}", style = MaterialTheme.typography.bodySmall)
            }
            if (task.recurrenceRule != null) {
                RecurrenceRuleSummaryText(task.recurrenceRule, Modifier)
            }
        }
        StatusTag(task)
        TextButton(onClick = onClick) { Text("详情") }
    }
}

@Composable
private fun StatusTag(task: TodoTask) {
    when (task.status) {
        TaskStatus.CANCELLED -> Text("已取消", style = MaterialTheme.typography.labelSmall)
        TaskStatus.COMPLETED -> Text("已完成", style = MaterialTheme.typography.labelSmall)
        TaskStatus.ACTIVE -> {}
    }
}

private fun dueLabel(task: TodoTask, nowMillis: Long): String? {
    val zone = ZoneId.of(task.dueZoneId)
    return when {
        task.dueEpochDay != null -> {
            val base = LocalDate.ofEpochDay(task.dueEpochDay).format(dateFmt) + " 截止"
            when {
                TaskDue.isOverdue(task, nowMillis) -> base + "（已过期）"
                TaskDue.isDueToday(task, nowMillis) -> base + "（今日到期）"
                else -> base
            }
        }
        task.dueAtMillis != null -> {
            val base = Instant.ofEpochMilli(task.dueAtMillis).atZone(zone).format(timeFmt) + " 截止"
            if (TaskDue.isOverdue(task, nowMillis)) base + "（已过期）" else base
        }
        else -> null
    }
}
