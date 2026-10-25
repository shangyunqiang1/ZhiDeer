package com.zhideer.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import com.zhideer.app.domain.model.SubTask
import com.zhideer.app.domain.usecase.SubTaskMove
import com.zhideer.app.domain.usecase.SubTaskUseCases
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val subTaskTimeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * 子任务面板（T07/#8）：挂在父待办详情中展示。
 * - 子任务清单与完成进度 n/m，可勾选完成/撤销；
 * - 支持添加、编辑、删除（回收站语义）、上移/下移；
 * - 子任务与父待办分别确认完成，此处勾选绝不改动父待办状态。
 * Root 将其挂接进父待办详情即可，无需改动 TasksScreen/TaskDialogs。
 */
@Composable
fun SubTasksPanel(
    useCases: SubTaskUseCases,
    parentId: String,
    zoneId: ZoneId,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val subTasks by remember(parentId) {
        useCases.observeByParent(parentId)
    }.collectAsState(initial = emptyList())
    var newTitle by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<SubTask?>(null) }

    fun onFailure(e: Throwable) {
        error = e.message
    }

    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("子任务", style = MaterialTheme.typography.titleSmall)
            Text(
                "已完成 ${subTasks.count { it.done }}/${subTasks.size}",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        if (subTasks.isEmpty()) {
            Text(
                "还没有子任务，把要做的步骤逐步添加进来",
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
        subTasks.forEach { sub ->
            SubTaskRow(
                sub = sub,
                zoneId = zoneId,
                onToggle = { checked ->
                    scope.launch {
                        useCases.setDone(sub.id, checked).onFailure(::onFailure)
                    }
                },
                onMoveUp = {
                    scope.launch {
                        useCases.move(sub.id, SubTaskMove.UP).onFailure(::onFailure)
                    }
                },
                onMoveDown = {
                    scope.launch {
                        useCases.move(sub.id, SubTaskMove.DOWN).onFailure(::onFailure)
                    }
                },
                onEdit = { editing = sub },
                onDelete = {
                    scope.launch {
                        useCases.delete(sub.id).onFailure(::onFailure)
                    }
                },
            )
            HorizontalDivider()
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            OutlinedTextField(
                value = newTitle,
                onValueChange = { newTitle = it; error = null },
                label = { Text("添加子任务（必填）") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                scope.launch {
                    useCases.create(parentId, newTitle)
                        .onSuccess {
                            newTitle = ""
                            error = null
                        }
                        .onFailure(::onFailure)
                }
            }) { Text("添加") }
        }
    }

    editing?.let { sub ->
        SubTaskEditorDialog(
            initial = sub,
            onDismiss = { editing = null },
            onSave = { title ->
                scope.launch {
                    useCases.update(sub.id, title)
                        .onSuccess {
                            editing = null
                            error = null
                        }
                        .onFailure(::onFailure)
                }
            },
        )
    }
}

@Composable
private fun SubTaskRow(
    sub: SubTask,
    zoneId: ZoneId,
    onToggle: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = sub.done, onCheckedChange = onToggle)
        Column(Modifier.weight(1f)) {
            Text(
                sub.title,
                style = if (sub.done) {
                    MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.outline)
                } else {
                    MaterialTheme.typography.bodyMedium
                },
            )
            sub.doneAt?.let {
                Text(
                    "完成于 " + formatSubTaskMillis(it, zoneId),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        TextButton(onClick = onMoveUp) { Text("上移") }
        TextButton(onClick = onMoveDown) { Text("下移") }
        TextButton(onClick = onEdit) { Text("编辑") }
        TextButton(onClick = onDelete) { Text("删除") }
    }
}

/** 编辑子任务标题的小对话框；空白标题即时提示。 */
@Composable
private fun SubTaskEditorDialog(
    initial: SubTask,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var title by remember { mutableStateOf(initial.title) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑子任务") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it; error = null },
                    label = { Text("标题（必填）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (title.isBlank()) {
                    error = "子任务标题不能为空"
                } else {
                    onSave(title)
                }
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun formatSubTaskMillis(millis: Long, zoneId: ZoneId): String =
    Instant.ofEpochMilli(millis).atZone(zoneId).format(subTaskTimeFmt)
