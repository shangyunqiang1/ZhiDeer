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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilterChip
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
import com.zhideer.app.domain.model.Memory
import com.zhideer.app.domain.model.MemoryType
import com.zhideer.app.domain.usecase.MemoryInput
import com.zhideer.app.domain.usecase.MemoryUseCases
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val memoryTimeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * 长期记忆主界面：列表（含状态标签）、搜索实时过滤、新建/编辑（内容、类型、有效期）、
 * 停用/启用与彻底删除确认。记忆是独立于便签与待办的隐私数据：
 * - 只有用户在此显式保存并确认的内容成为长期记忆，普通聊天或便签不自动提取；
 * - 删除是彻底删除，不进入回收站、不适用普通事项三十天保留规则，删除前二次确认。
 * 公开 composable，由 Root 挂导航。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(useCases: MemoryUseCases) {
    val scope = rememberCoroutineScope()
    var keyword by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Memory?>(null) }
    var viewing by remember { mutableStateOf<Memory?>(null) }
    var deleting by remember { mutableStateOf<Memory?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val query = keyword.trim()
    val memories by remember(query) {
        if (query.isEmpty()) useCases.observe() else useCases.search(query)
    }.collectAsState(initial = emptyList())

    Scaffold(
        topBar = { TopAppBar(title = { Text("记忆") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) { Text("＋") }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                label = { Text("搜索记忆（按内容关键词定位）") },
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
            if (memories.isEmpty()) {
                Text(
                    if (query.isEmpty()) "这里还没有记忆" else "没有匹配的记忆",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(memories, key = { it.id }) { memory ->
                    MemoryRow(
                        memory = memory,
                        onToggleEnabled = {
                            scope.launch {
                                useCases.setEnabled(memory.id, !memory.enabled)
                                    .onSuccess { error = null }
                                    .onFailure { e -> error = e.message }
                            }
                        },
                        onEdit = { editing = memory },
                        onDelete = { deleting = memory },
                        onOpen = { viewing = memory },
                    )
                }
            }
        }
    }

    if (creating) {
        MemoryEditorDialog(
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
    editing?.let { memory ->
        MemoryEditorDialog(
            initial = memory,
            onDismiss = { editing = null },
            onSave = { input ->
                scope.launch {
                    useCases.update(memory.id, input)
                        .onSuccess { editing = null; error = null }
                        .onFailure { e -> error = e.message }
                }
            },
        )
    }
    viewing?.let { memory ->
        MemoryDetailDialog(
            memory = memory,
            onDismiss = { viewing = null },
            onEdit = { viewing = null; editing = memory },
            onToggleEnabled = {
                scope.launch {
                    useCases.setEnabled(memory.id, !memory.enabled)
                        .onSuccess { viewing = it; error = null }
                        .onFailure { e -> error = e.message }
                }
            },
            onDelete = { viewing = null; deleting = memory },
        )
    }
    deleting?.let { memory ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("彻底删除这条记忆？") },
            text = {
                Text(
                    "“${memory.content.take(30)}${if (memory.content.length > 30) "…" else ""}”" +
                        "将被彻底删除且不可恢复；记忆不进入回收站、没有三十天保留期。" +
                        "如只是暂时不想使用，建议改为停用。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        useCases.delete(memory.id)
                            .onSuccess { deleting = null; error = null }
                            .onFailure { e -> error = e.message }
                    }
                }) { Text("彻底删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun MemoryRow(
    memory: Memory,
    onToggleEnabled: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(memory.content, style = MaterialTheme.typography.bodyLarge)
                Text(
                    memoryStatusLine(memory),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onToggleEnabled) { Text(if (memory.enabled) "停用" else "启用") }
            TextButton(onClick = onEdit) { Text("编辑") }
            TextButton(onClick = onDelete) { Text("删除") }
            TextButton(onClick = onOpen) { Text("详情") }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AssistChip(onClick = onOpen, label = { Text(memory.type.label) })
            AssistChip(onClick = onOpen, label = { Text(memory.source.label) })
        }
    }
    HorizontalDivider()
}

/** 状态行：确认状态 + 停用/过期提示 + 创建与更新时间。 */
private fun memoryStatusLine(memory: Memory): String {
    val parts = mutableListOf<String>()
    parts.add(if (memory.confirmed) "已确认" else "未确认")
    if (!memory.enabled) parts.add("已停用")
    val now = System.currentTimeMillis()
    if (memory.expiresAt != null && memory.expiresAt <= now) parts.add("已过期")
    else if (memory.expiresAt != null) parts.add("有效期至 ${formatMillis(memory.expiresAt)}")
    parts.add("创建 ${formatMillis(memory.createdAt)}")
    parts.add("更新 ${formatMillis(memory.updatedAt)}")
    return parts.joinToString(" · ")
}

/** 新建/编辑记忆：内容、类型（偏好/事实）与有效期（yyyy-MM-dd，留空即长期有效）。 */
@Composable
private fun MemoryEditorDialog(
    initial: Memory?,
    onDismiss: () -> Unit,
    onSave: (MemoryInput) -> Unit,
) {
    var content by remember { mutableStateOf(initial?.content ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: MemoryType.FACT) }
    var expiresText by remember {
        mutableStateOf(initial?.expiresAt?.let { formatDate(it) } ?: "")
    }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建记忆" else "编辑记忆") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "保存后将成为长期记忆，可随时查看、编辑、停用或彻底删除。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it; error = null },
                    label = { Text("内容（偏好或事实）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("类型", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MemoryType.entries.forEach { t ->
                        FilterChip(
                            selected = type == t,
                            onClick = { type = t },
                            label = { Text(t.label) },
                        )
                    }
                }
                OutlinedTextField(
                    value = expiresText,
                    onValueChange = { expiresText = it; error = null },
                    label = { Text("有效期（yyyy-MM-dd，留空即长期有效）") },
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
                if (content.isBlank()) {
                    error = "记忆内容不能为空"
                    return@TextButton
                }
                val expiresAt = if (expiresText.isBlank()) {
                    null
                } else {
                    val parsed = runCatching {
                        LocalDate.parse(expiresText.trim())
                            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    }.getOrNull()
                    if (parsed == null) {
                        error = "有效期格式应为 yyyy-MM-dd，例如 2026-12-31"
                        return@TextButton
                    }
                    parsed
                }
                onSave(MemoryInput(content = content, type = type, expiresAt = expiresAt))
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 记忆详情：原文、类型、来源、确认状态、创建/更新/验证时间与有效期。 */
@Composable
private fun MemoryDetailDialog(
    memory: Memory,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onToggleEnabled: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记忆详情") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(memory.content, style = MaterialTheme.typography.bodyMedium)
                Text("类型：${memory.type.label}", style = MaterialTheme.typography.bodySmall)
                Text("来源：${memory.source.label}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "确认状态：${if (memory.confirmed) "已确认" else "未确认"}" +
                        " · ${if (memory.enabled) "启用中" else "已停用"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("创建于：${formatMillis(memory.createdAt)}", style = MaterialTheme.typography.bodySmall)
                Text("更新于：${formatMillis(memory.updatedAt)}", style = MaterialTheme.typography.bodySmall)
                Text("验证于：${formatMillis(memory.verifiedAt)}", style = MaterialTheme.typography.bodySmall)
                Text(
                    if (memory.expiresAt == null) "有效期：长期有效" else "有效期至：${formatMillis(memory.expiresAt)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onToggleEnabled) { Text(if (memory.enabled) "停用" else "启用") }
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
    return zoned.format(memoryTimeFmt)
}

private fun formatDate(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString()
