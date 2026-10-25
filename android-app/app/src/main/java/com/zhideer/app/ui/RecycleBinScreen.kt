package com.zhideer.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import com.zhideer.app.domain.usecase.RecycleBinItem
import com.zhideer.app.domain.usecase.RecycleBinKind
import com.zhideer.app.domain.usecase.RecycleBinUseCases
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val recycleBinTimeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * 回收站界面（T16/#17，GLOSSARY：回收站）：
 * - 展示四类普通事项（待办/日程/便签/子任务）的回收站记录与删除时间，按删除时间倒序；
 * - 每条记录可恢复或立即彻底删除（彻底删除需二次确认，不可撤销）；
 * - 恢复待办时同批恢复其子任务（父子整体还原）；已解除的日程关联默认不自动重连，
 *   恢复结果明确提示「原日程关联已解除，可重新关联」；提醒不复活，错过的提醒只提示不补响；
 * - 回收站记录保留三十天，到期自动彻底清理（清理入口见 RecycleBinUseCases.purgeExpired）；
 * - 回收站内容不进入普通查询、搜索与 AI 数据源。
 * 由 Root/导航宿主挂载；[onBack] 提供返回入口（可空）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecycleBinScreen(
    useCases: RecycleBinUseCases,
    deviceZone: ZoneId = ZoneId.systemDefault(),
    onBack: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var filter by remember { mutableStateOf<RecycleBinKind?>(null) }
    // 待二次确认的彻底删除条目（对话框只有确认后才执行，防误触）
    var pendingPurge by remember { mutableStateOf<RecycleBinItem?>(null) }
    var busy by remember { mutableStateOf(false) }

    val allItems by remember { useCases.observeAll() }.collectAsState(initial = emptyList())
    val items = if (filter == null) allItems else allItems.filter { it.kind == filter }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("回收站") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Text("←") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 分类过滤：全部 / 四类
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(label = "全部", selected = filter == null) { filter = null }
                RecycleBinKind.entries.forEach { kind ->
                    FilterChip(
                        label = kind.label,
                        selected = filter == kind,
                    ) { filter = kind }
                }
            }
            Text(
                "回收站中的记录保留三十天，到期自动彻底清理；期间不触发提醒，也不进入搜索与 AI 查询。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (items.isEmpty()) {
                Text(
                    if (filter == null) "回收站为空" else "该分类下暂无回收站记录",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(items, key = { "${it.kind.name}-${it.id}" }) { item ->
                    RecycleBinRow(
                        item = item,
                        deviceZone = deviceZone,
                        busy = busy,
                        onRestore = {
                            scope.launch {
                                busy = true
                                val result = when (item.kind) {
                                    RecycleBinKind.TASK -> useCases.restoreTask(item.id)
                                    RecycleBinKind.SCHEDULE -> useCases.restoreEntry(item.id)
                                    RecycleBinKind.NOTE -> useCases.restoreNote(item.id)
                                    RecycleBinKind.SUB_TASK -> useCases.restoreSubTask(item.id)
                                }
                                busy = false
                                result.onSuccess { snackbar.showSnackbar(it.message) }
                                    .onFailure { e -> snackbar.showSnackbar(e.message ?: "恢复失败") }
                            }
                        },
                        onRequestPurge = { pendingPurge = item },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    // 彻底删除二次确认：明确不可撤销与级联范围
    pendingPurge?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingPurge = null },
            title = { Text("彻底删除") },
            text = {
                Text(
                    "确定彻底删除「${target.title}」吗？该操作不可撤销，" +
                        "其从属数据（子任务、清单项、提醒、重复完成记录等）将一并删除。" +
                        "如需保留，请选择恢复。",
                )
            },
            confirmButton = {
                Button(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            val result = when (target.kind) {
                                RecycleBinKind.TASK -> useCases.purgeTask(target.id)
                                RecycleBinKind.SCHEDULE -> useCases.purgeEntry(target.id)
                                RecycleBinKind.NOTE -> useCases.purgeNote(target.id)
                                RecycleBinKind.SUB_TASK -> useCases.purgeSubTask(target.id)
                            }
                            busy = false
                            pendingPurge = null
                            result.onSuccess { snackbar.showSnackbar("已彻底删除「${target.title}」") }
                                .onFailure { e -> snackbar.showSnackbar(e.message ?: "删除失败") }
                        }
                    },
                ) { Text("彻底删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingPurge = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text(if (selected) "• $label" else label) })
}

@Composable
private fun RecycleBinRow(
    item: RecycleBinItem,
    deviceZone: ZoneId,
    busy: Boolean,
    onRestore: () -> Unit,
    onRequestPurge: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.kind.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "  删除于 " +
                        Instant.ofEpochMilli(item.trashedAt).atZone(deviceZone).format(recycleBinTimeFmt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(item.title, style = MaterialTheme.typography.titleMedium)
            if (item.subtitle.isNotBlank()) {
                Text(
                    item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(enabled = !busy, onClick = onRestore) { Text("恢复") }
                TextButton(enabled = !busy, onClick = onRequestPurge) { Text("彻底删除") }
            }
        }
    }
}
