package com.zhideer.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.zhideer.app.domain.model.Note
import com.zhideer.app.domain.usecase.NoteInput
import com.zhideer.app.domain.usecase.NoteListItemInput
import com.zhideer.app.domain.usecase.NoteUseCases
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val noteTimeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * 便签列表主界面：置顶区+普通区、搜索实时过滤、新建/编辑、详情（清单项可直接勾选）。
 * 便签清单项的勾选完全独立于待办系统，不创建、关联或完成任何待办。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(useCases: NoteUseCases) {
    val scope = rememberCoroutineScope()
    var keyword by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Note?>(null) }
    var viewing by remember { mutableStateOf<Note?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val query = keyword.trim()
    val notes by remember(query) {
        if (query.isEmpty()) useCases.observe() else useCases.search(query)
    }.collectAsState(initial = emptyList())

    Scaffold(
        topBar = { TopAppBar(title = { Text("便签") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) { Text("＋") }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                label = { Text("搜索便签（标题、正文或清单）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (notes.isEmpty()) {
                Text(
                    if (query.isEmpty()) "这里还没有便签" else "没有匹配的便签",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                if (query.isEmpty()) {
                    val pinned = notes.filter { it.pinned }
                    val others = notes.filterNot { it.pinned }
                    if (pinned.isNotEmpty()) {
                        item(key = "header-pinned") { SectionHeader("置顶") }
                        items(pinned, key = { it.id }) { note ->
                            NoteRow(
                                note = note,
                                onTogglePin = {
                                    scope.launch {
                                        useCases.togglePin(note.id, !note.pinned)
                                            .onFailure { e -> error = e.message }
                                    }
                                },
                                onEdit = { editing = note },
                                onDelete = {
                                    scope.launch {
                                        useCases.trash(note.id)
                                            .onSuccess { error = null }
                                            .onFailure { e -> error = e.message }
                                    }
                                },
                                onOpen = { viewing = note },
                            )
                        }
                    }
                    if (others.isNotEmpty()) {
                        item(key = "header-notes") { SectionHeader("便签") }
                        items(others, key = { it.id }) { note ->
                            NoteRow(
                                note = note,
                                onTogglePin = {
                                    scope.launch {
                                        useCases.togglePin(note.id, !note.pinned)
                                            .onFailure { e -> error = e.message }
                                    }
                                },
                                onEdit = { editing = note },
                                onDelete = {
                                    scope.launch {
                                        useCases.trash(note.id)
                                            .onSuccess { error = null }
                                            .onFailure { e -> error = e.message }
                                    }
                                },
                                onOpen = { viewing = note },
                            )
                        }
                    }
                } else {
                    items(notes, key = { it.id }) { note ->
                        NoteRow(
                            note = note,
                            onTogglePin = {
                                scope.launch {
                                    useCases.togglePin(note.id, !note.pinned)
                                        .onFailure { e -> error = e.message }
                                }
                            },
                            onEdit = { editing = note },
                            onDelete = {
                                scope.launch {
                                    useCases.trash(note.id)
                                        .onSuccess { error = null }
                                        .onFailure { e -> error = e.message }
                                }
                            },
                            onOpen = { viewing = note },
                        )
                    }
                }
            }
        }
    }

    if (creating) {
        NoteEditorDialog(
            initial = null,
            onDismiss = { creating = false },
            onSave = { input ->
                scope.launch {
                    useCases.create(input)
                        .onSuccess { creating = false; error = null }
                        .onFailure { e -> error = e.message }
                }
            },
        )
    }
    editing?.let { note ->
        NoteEditorDialog(
            initial = note,
            onDismiss = { editing = null },
            onSave = { input ->
                scope.launch {
                    useCases.update(note.id, input)
                        .onSuccess { editing = null; error = null }
                        .onFailure { e -> error = e.message }
                }
            },
        )
    }
    viewing?.let { note ->
        NoteDetailDialog(
            note = note,
            onDismiss = { viewing = null },
            onToggleItem = { itemId, checked ->
                scope.launch {
                    useCases.toggleItem(note.id, itemId, checked)
                        .onSuccess { viewing = it; error = null }
                        .onFailure { e -> error = e.message }
                }
            },
            onEdit = { viewing = null; editing = note },
            onTogglePin = {
                scope.launch {
                    useCases.togglePin(note.id, !note.pinned)
                        .onSuccess { viewing = it; error = null }
                        .onFailure { e -> error = e.message }
                }
            },
            onDelete = {
                scope.launch {
                    useCases.trash(note.id)
                        .onSuccess { viewing = null; error = null }
                        .onFailure { e -> error = e.message }
                }
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun NoteRow(
    note: Note,
    onTogglePin: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    note.title.ifBlank { "（无标题）" },
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (note.items.isNotEmpty()) {
                    Text(
                        "清单 ${note.items.count { it.checked }}/${note.items.size}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            TextButton(onClick = onTogglePin) { Text(if (note.pinned) "取消置顶" else "置顶") }
            TextButton(onClick = onEdit) { Text("编辑") }
            TextButton(onClick = onDelete) { Text("删除") }
            TextButton(onClick = onOpen) { Text("详情") }
        }
        if (note.tags.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                note.tags.forEach { tag ->
                    AssistChip(onClick = onOpen, label = { Text(tag) })
                }
            }
        }
    }
    HorizontalDivider()
}

/** 新建/编辑便签：标题、正文、标签（逗号或空格分隔）、清单项增删改勾选。 */
@Composable
private fun NoteEditorDialog(
    initial: Note?,
    onDismiss: () -> Unit,
    onSave: (NoteInput) -> Unit,
) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var content by remember { mutableStateOf(initial?.content ?: "") }
    var tagsText by remember { mutableStateOf(initial?.tags?.joinToString(" ") ?: "") }
    var items by remember {
        mutableStateOf(
            initial?.items?.map { NoteListItemInput(id = it.id, text = it.text, checked = it.checked) }
                ?: emptyList(),
        )
    }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建便签" else "编辑便签") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it; error = null },
                    label = { Text("标题（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it; error = null },
                    label = { Text("正文") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = tagsText,
                    onValueChange = { tagsText = it },
                    label = { Text("标签（可用逗号或空格分隔）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("清单项", style = MaterialTheme.typography.labelLarge)
                items.forEachIndexed { index, item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = item.checked,
                            onCheckedChange = { checked ->
                                items = items.mapIndexed { i, it ->
                                    if (i == index) it.copy(checked = checked) else it
                                }
                            },
                        )
                        OutlinedTextField(
                            value = item.text,
                            onValueChange = { text ->
                                items = items.mapIndexed { i, it ->
                                    if (i == index) it.copy(text = text) else it
                                }
                            },
                            label = { Text("清单项 ${index + 1}") },
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            items = items.filterIndexed { i, _ -> i != index }
                        }) { Text("移除") }
                    }
                }
                TextButton(onClick = { items = items + NoteListItemInput(text = "") }) {
                    Text("添加清单项")
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (title.isBlank() && content.isBlank() && items.all { it.text.isBlank() }) {
                    error = "便签内容不能为空"
                } else {
                    onSave(
                        NoteInput(
                            title = title,
                            content = content,
                            tags = parseTags(tagsText),
                            items = items,
                        ),
                    )
                }
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 标签输入解析：中英文逗号或空白分隔，去除空白项并去重。 */
private fun parseTags(raw: String): List<String> =
    raw.split(',', '，', ' ', '\t', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

/** 便签详情：显示原文与清单项，清单项可直接勾选（独立于待办）。 */
@Composable
private fun NoteDetailDialog(
    note: Note,
    onDismiss: () -> Unit,
    onToggleItem: (String, Boolean) -> Unit,
    onEdit: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(note.title.ifBlank { "（无标题）" }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (note.content.isNotBlank()) {
                    Text(note.content, style = MaterialTheme.typography.bodyMedium)
                }
                if (note.items.isNotEmpty()) {
                    Text("清单", style = MaterialTheme.typography.labelLarge)
                    note.items.forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = item.checked,
                                onCheckedChange = { checked -> onToggleItem(item.id, checked) },
                            )
                            Text(item.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (note.tags.isNotEmpty()) {
                    Text("标签：${note.tags.joinToString("、")}", style = MaterialTheme.typography.bodySmall)
                }
                Text("创建于：${formatMillis(note.createdAt)}", style = MaterialTheme.typography.bodySmall)
                Text("更新于：${formatMillis(note.updatedAt)}", style = MaterialTheme.typography.bodySmall)
                if (note.trashedAt != null) {
                    Text("已在回收站", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onTogglePin) { Text(if (note.pinned) "取消置顶" else "置顶") }
                TextButton(onClick = onDelete) { Text("删除") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

private fun formatMillis(millis: Long): String {
    val zoned = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    return zoned.format(noteTimeFmt)
}
