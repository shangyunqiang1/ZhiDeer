package com.zhideer.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import com.zhideer.app.domain.model.TodoTask
import com.zhideer.app.domain.usecase.HomeUseCases
import com.zhideer.app.domain.usecase.NextUpItem
import com.zhideer.app.domain.usecase.TaskUseCases
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val homeDateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val homeTimeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * 首页（T34/#35）：真实数据仪表盘——下一事项、今日日程、待办统计与风险、
 * 快捷新建/完成/详情、便签与建议入口。断网或未配置 Key 均可浏览（全部本地真实记录）。
 * 参谋建议仅由用户主动点击触发。
 */
@Composable
fun HomeScreen(
    homeUseCases: HomeUseCases,
    taskUseCases: TaskUseCases,
    zoneId: ZoneId,
    creating: Boolean,
    onCreatingChange: (Boolean) -> Unit,
    onOpenNotes: () -> Unit,
    onOpenAdvisor: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenBin: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTaskDetail: (TodoTask) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snapshot by homeUseCases.observeHome().collectAsState(
        initial = com.zhideer.app.domain.usecase.HomeSnapshot(null, null, emptyList(), emptyList(), com.zhideer.app.domain.usecase.HomeStats(0, 0, 0)),
    )
    var error by remember { mutableStateOf<String?>(null) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            Text("首页", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 8.dp))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("今天到期", snapshot.stats.dueTodayCount, Modifier.weight(1f))
                StatCard("已过期", snapshot.stats.overdueCount, Modifier.weight(1f))
                StatCard("今日完成", snapshot.stats.completedTodayCount, Modifier.weight(1f))
            }
        }
        item {
            SectionTitle("下一事项")
            NextUpRow("待办", snapshot.nextTask, zoneId)
            NextUpRow("日程", snapshot.nextSchedule, zoneId)
            if (snapshot.nextTask == null && snapshot.nextSchedule == null) {
                EmptyHint("暂无接下来的安排")
            }
        }
        item {
            SectionTitle("今日日程")
            if (snapshot.todaySchedules.isEmpty()) {
                EmptyHint("今天没有日程")
            } else {
                snapshot.todaySchedules.forEach { entry ->
                    val zone = runCatching { ZoneId.of(entry.timezoneId) }.getOrDefault(zoneId)
                    val start = Instant.ofEpochMilli(entry.startMillis).atZone(zone)
                    val end = Instant.ofEpochMilli(entry.endMillis).atZone(zone)
                    Card(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Column(Modifier.padding(8.dp)) {
                            Text(entry.title, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${start.format(homeTimeFmt)} ～ ${end.format(homeTimeFmt)}（${entry.timezoneId}）",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (entry.location.isNotBlank()) Text("地点：${entry.location}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        item {
            SectionTitle("待办风险（已过期）")
            if (snapshot.overdueTasks.isEmpty()) {
                EmptyHint("没有过期待办")
            } else {
                snapshot.overdueTasks.take(5).forEach { task ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = false,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    scope.launch {
                                        taskUseCases.complete(task.id, true).onFailure { error = it.message }
                                    }
                                }
                            },
                        )
                        Column(Modifier.weight(1f).clickable { onOpenTaskDetail(task) }) {
                            Text(task.title)
                            Text(
                                task.dueEpochDay?.let { "${java.time.LocalDate.ofEpochDay(it).format(homeDateFmt)} 截止（已过期）" }
                                    ?: "已过期",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
        item {
            SectionTitle("快捷与入口")
            Column(Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onCreatingChange(true) }) { Text("新建待办") }
                    OutlinedButton(onClick = onOpenNotes) { Text("便签") }
                    OutlinedButton(onClick = onOpenAdvisor) { Text("参谋建议") }
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onOpenMemory) { Text("记忆") }
                    TextButton(onClick = onOpenBin) { Text("回收站") }
                    TextButton(onClick = onOpenBackup) { Text("备份") }
                    TextButton(onClick = onOpenSettings) { Text("设置") }
                }
                Text(
                    "提示：提醒入口在各事项详情；参谋建议仅在你点击时生成。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun StatCard(label: String, value: Int, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$value", style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun EmptyHint(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().padding(8.dp))
    }
}

@Composable
private fun NextUpRow(kindLabel: String, item: NextUpItem?, zoneId: ZoneId) {
    if (item == null) return
    Card(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Column(Modifier.padding(8.dp)) {
            Text("[$kindLabel] ${item.title}", style = MaterialTheme.typography.bodyLarge)
            val at = Instant.ofEpochMilli(item.atMillis).atZone(zoneId)
            val label = if (item.isDateOnly) "${at.format(homeDateFmt)}（日期截止，不含时刻）" else at.format(homeTimeFmt)
            Text(
                if (item.overdue) "$label（已过期）" else label,
                style = MaterialTheme.typography.bodySmall,
                color = if (item.overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
