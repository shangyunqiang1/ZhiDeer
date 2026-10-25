package com.zhideer.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zhideer.app.domain.model.ClockHandoff
import com.zhideer.app.domain.model.ClockHandoffStatus
import com.zhideer.app.domain.usecase.ClockHandoffPreview
import com.zhideer.app.domain.usecase.ClockHandoffUseCases
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val handoffTimeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * 系统闹钟交接（T24/#25）：发起前预览时间与星期规则；发起后只显示「待核对」，
 * 绝不显示已保存；平台不支持或失败时给出手动时钟与自有提醒两条回退，并警告双响。
 */
@Composable
fun ClockHandoffScreen(useCases: ClockHandoffUseCases, zoneId: ZoneId) {
    val scope = rememberCoroutineScope()
    val handoffs by useCases.observeHandoffs().collectAsState(initial = emptyList())
    var hourText by remember { mutableStateOf("7") }
    var minuteText by remember { mutableStateOf("30") }
    var label by remember { mutableStateOf("系统闹钟") }
    var weekdays by remember { mutableStateOf(emptySet<Int>()) }
    var useDate by remember { mutableStateOf(false) }
    var dateText by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<ClockHandoffPreview?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    val capability = remember { useCases.capability() }

    fun buildPreview(): ClockHandoffPreview? {
        val hour = hourText.trim().toIntOrNull()
        val minute = minuteText.trim().toIntOrNull()
        if (hour == null || minute == null) {
            message = "时间需为数字：小时 0–23、分钟 0–59"; isError = true; return null
        }
        val day = if (useDate) {
            runCatching { java.time.LocalDate.parse(dateText.trim()).toEpochDay() }.getOrNull()
                ?: run {
                    message = "日期格式应为 yyyy-MM-dd"; isError = true; null
                }
        } else null
        if (useDate && day == null) return null
        return useCases.preview(hour, minute, label, day, weekdays)
            .onFailure { message = it.message; isError = true }
            .getOrNull()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("系统闹钟交接", style = MaterialTheme.typography.headlineSmall)
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                "说明：知时鹿只向系统时钟发起创建请求，跳转成功不代表闹钟已保存，也无法读取系统闹钟内容。" +
                    "请到系统时钟核对；本页记录始终保持「待核对」。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp),
            )
        }
        Text(
            if (capability.anyAvailable) {
                "当前系统时钟可用：创建=${if (capability.canCreateAlarm) "是" else "否"}，" +
                    "列表=${if (capability.canOpenAlarmList) "是" else "否"}"
            } else {
                "当前系统没有可用的时钟应用，请使用回退方案。"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(hourText, { hourText = it; message = null }, label = { Text("小时 0–23") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(minuteText, { minuteText = it; message = null }, label = { Text("分钟 0–59") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(label, { label = it }, label = { Text("标签（可选）") }, modifier = Modifier.fillMaxWidth())
        Text("星期规则（不选=仅响一次）", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        Row {
            (1..7).forEach { d ->
                FilterChip(
                    selected = d in weekdays,
                    onClick = { weekdays = if (d in weekdays) weekdays - d else weekdays + d; message = null },
                    label = { Text(ClockHandoff.WEEKDAY_NAMES[d] ?: "?") },
                    modifier = Modifier.padding(end = 2.dp),
                )
            }
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            FilterChip(
                selected = useDate,
                onClick = { useDate = !useDate; message = null },
                label = { Text("指定日历日期") },
            )
        }
        if (useDate) {
            OutlinedTextField(dateText, { dateText = it; message = null }, label = { Text("日期 yyyy-MM-dd") }, modifier = Modifier.fillMaxWidth())
            Text(
                "注意：系统时钟接口只接受小时与分钟，无法传达具体日期。指定日期时不会自动丢弃日期，" +
                    "请改为只设时间后在系统时钟中选择日期，或改用知时鹿提醒。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        message?.let {
            Text(it, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            OutlinedButton(onClick = { buildPreview()?.let { preview = it; message = null } }) { Text("预览") }
            OutlinedButton(onClick = {
                scope.launch {
                    useCases.openAlarmList()
                        .onSuccess { message = "已打开系统闹钟列表（请在系统内核对）"; isError = false }
                        .onFailure { message = it.message; isError = true }
                }
            }) { Text("打开系统闹钟") }
        }
        Text(useCases.fallbackHint(), style = MaterialTheme.typography.bodySmall)

        Text("交接记录（仅表示本应用发起过请求）", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        if (handoffs.isEmpty()) {
            Text("暂无交接记录", modifier = Modifier.padding(8.dp))
        } else {
            handoffs.forEach { h ->
                Card(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Column(Modifier.padding(8.dp)) {
                        Text("${h.timeText} · ${h.weekdayText} · ${h.label}")
                        Text(
                            "状态：${h.status.label}" +
                                if (h.status == ClockHandoffStatus.PENDING_CHECK) "（请在系统时钟核对，本应用无法确认）" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(h.note, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "发起于 " + Instant.ofEpochMilli(h.requestedAtMillis).atZone(zoneId).format(handoffTimeFmt),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { scope.launch { useCases.delete(h.id) } }) { Text("删除记录") }
                    }
                }
            }
        }
    }

    preview?.let { p ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("确认交接给系统时钟") },
            text = {
                Column {
                    Text("时间：${p.timeText}")
                    Text("星期：${p.weekdayText}")
                    if (p.label.isNotBlank()) Text("标签：${p.label}")
                    Text("说明：系统时钟可能只打开列表而不预填；发起后请手动核对并保存。")
                    if (!p.canHandoff) {
                        Text(
                            if (p.dateNotConveyable) "日期无法传达给系统时钟，不能发起。" else "当前系统没有可用的时钟应用。",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = p.canHandoff, onClick = {
                    scope.launch {
                        useCases.handoff(p)
                            .onSuccess {
                                preview = null
                                message = "已发起。请到系统时钟核对是否保存；本记录不代表已保存。"
                                isError = false
                            }
                            .onFailure {
                                preview = null
                                message = it.message
                                isError = true
                            }
                    }
                }) { Text("发起交接") }
            },
            dismissButton = { TextButton(onClick = { preview = null }) { Text("取消") } },
        )
    }
}
