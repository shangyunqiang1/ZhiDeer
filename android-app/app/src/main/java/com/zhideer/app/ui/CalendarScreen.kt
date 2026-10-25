package com.zhideer.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import com.zhideer.app.domain.model.CalendarEntry
import com.zhideer.app.domain.model.CalendarRange
import com.zhideer.app.domain.model.CalendarTimes
import com.zhideer.app.domain.usecase.CalendarInput
import com.zhideer.app.domain.usecase.CalendarUseCases
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

/** 日历列表过滤视图：选中日（月视图）、今日、明日、未来七天。 */
private enum class DayFilter(val label: String) {
    DAY("选中日"),
    TODAY("今日"),
    TOMORROW("明日"),
    NEXT_SEVEN_DAYS("未来七天"),
}

/**
 * 日程主界面：月视图网格 + 选中日列表 + 今日/明日/未来七天过滤 + 新建/编辑/详情 + 时区核对提示条。
 * 日期归属按每条日程所存时区解释；只展示真实持久记录。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(useCases: CalendarUseCases, deviceZone: ZoneId, calendarReminderUseCases: com.zhideer.app.domain.usecase.CalendarReminderUseCases) {
    val scope = rememberCoroutineScope()
    val nowMillis = remember { System.currentTimeMillis() }
    val today: LocalDate = remember { LocalDate.ofInstant(Instant.ofEpochMilli(nowMillis), deviceZone) }
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var selectedDay by remember { mutableStateOf(today) }
    var filter by remember { mutableStateOf(DayFilter.DAY) }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CalendarEntry?>(null) }
    var viewing by remember { mutableStateOf<CalendarEntry?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var zoneHandled by remember { mutableStateOf(false) }

    val entries by remember { useCases.observeAll() }.collectAsState(initial = emptyList())

    // 月视图标记：日程按所存时区展开到它占据的每个日期（跨日安排在每一天都出现）
    val markedDays: Set<LocalDate> = remember(entries, month) {
        val result = mutableSetOf<LocalDate>()
        for (dayOffset in 1..month.lengthOfMonth()) {
            val day = month.atDay(dayOffset)
            if (entries.any { CalendarTimes.spansDay(it, day) }) result.add(day)
        }
        result
    }
    val visibleList: List<CalendarEntry> = remember(entries, selectedDay, filter, nowMillis) {
        when (filter) {
            DayFilter.DAY -> entries.filter { CalendarTimes.spansDay(it, selectedDay) }
            DayFilter.TODAY -> entries.filter { CalendarTimes.matchesRange(it, CalendarRange.TODAY, nowMillis) }
            DayFilter.TOMORROW -> entries.filter { CalendarTimes.matchesRange(it, CalendarRange.TOMORROW, nowMillis) }
            DayFilter.NEXT_SEVEN_DAYS -> entries.filter {
                CalendarTimes.matchesRange(it, CalendarRange.NEXT_SEVEN_DAYS, nowMillis)
            }
        }.sortedBy { it.startMillis }
    }
    val mismatched: List<CalendarEntry> = remember(entries, deviceZone) {
        entries.filter { CalendarTimes.detectMismatch(it, deviceZone.id) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("日程") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) { Text("＋") }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DayFilter.entries.forEach { f ->
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

            if (filter == DayFilter.DAY) {
                MonthGrid(
                    month = month,
                    selected = selectedDay,
                    today = today,
                    marked = markedDays,
                    onPrevMonth = { month = month.minusMonths(1) },
                    onNextMonth = { month = month.plusMonths(1) },
                    onSelect = { selectedDay = it },
                )
                Text(
                    "${selectedDay.format(dateFmt)} 的日程",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            if (visibleList.isEmpty()) {
                Text(
                    "这里还没有日程",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(visibleList, key = { it.id }) { entry ->
                    CalendarRow(
                        entry = entry,
                        nowMillis = nowMillis,
                        onClick = { viewing = entry },
                    )
                    HorizontalDivider()
                }
            }

            // 时区核对提示条：只在存在不一致时出现，动作必须由用户显式点击，不自动改记录
            if (mismatched.isNotEmpty() && !zoneHandled) {
                ZoneMismatchBanner(
                    mismatched = mismatched,
                    deviceZone = deviceZone,
                    onKeepZone = {
                        scope.launch {
                            runCatching {
                                mismatched.forEach { useCases.confirmKeepZone(it.id).getOrThrow() }
                            }.onSuccess {
                                zoneHandled = true
                                error = null
                            }.onFailure { error = it.message }
                        }
                    },
                    onReinterpret = {
                        scope.launch {
                            runCatching {
                                mismatched.forEach { useCases.reinterpretInZone(it.id, deviceZone.id).getOrThrow() }
                            }.onSuccess {
                                zoneHandled = true
                                error = null
                            }.onFailure { error = it.message }
                        }
                    },
                )
            }
        }
    }

    if (creating) {
        CalendarEditorDialog(
            deviceZone = deviceZone,
            initial = null,
            onDismiss = { creating = false },
            onSave = { input ->
                scope.launch {
                    useCases.create(input)
                        .onSuccess {
                            creating = false
                            error = null
                        }
                        .onFailure { error = it.message }
                }
            },
        )
    }
    editing?.let { entry ->
        CalendarEditorDialog(
            deviceZone = deviceZone,
            initial = entry,
            onDismiss = { editing = null },
            onSave = { input ->
                scope.launch {
                    useCases.update(entry.id, input)
                        .onSuccess {
                            editing = null
                            error = null
                        }
                        .onFailure { error = it.message }
                }
            },
        )
    }
    viewing?.let { entry ->
        CalendarDetailDialog(
            entry = entry,
            deviceZone = deviceZone,
            reminderUseCases = calendarReminderUseCases,
            onDismiss = { viewing = null },
            onEdit = {
                viewing = null
                editing = entry
            },
            onTrash = {
                scope.launch {
                    useCases.trash(entry.id)
                        .onSuccess {
                            viewing = null
                            error = null
                        }
                        .onFailure { error = it.message }
                }
            },
        )
    }
}

/** 时区核对提示条：所存时区与设备时区不一致，提供两个显式确认按钮。 */
@Composable
private fun ZoneMismatchBanner(
    mismatched: List<CalendarEntry>,
    deviceZone: ZoneId,
    onKeepZone: () -> Unit,
    onReinterpret: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "有 ${mismatched.size} 条日程保存于时区 ${mismatched.first().timezoneId}，" +
                    "与设备当前时区 ${deviceZone.id} 不同。日程不会随设备时区静默变化，请核对：",
                style = MaterialTheme.typography.bodySmall,
            )
            Row {
                TextButton(onClick = onKeepZone) { Text("保持原时区") }
                TextButton(onClick = onReinterpret) { Text("按新时区重新解释") }
            }
        }
    }
}

/** 月视图网格（自绘）：上/下月切换、星期表头、日期格与日程标记点。 */
@Composable
private fun MonthGrid(
    month: YearMonth,
    selected: LocalDate,
    today: LocalDate,
    marked: Set<LocalDate>,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onSelect: (LocalDate) -> Unit,
) {
    Column(Modifier.padding(horizontal = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onPrevMonth) { Text("上月") }
            Text(
                "${month.year}年${month.monthValue}月",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            TextButton(onClick = onNextMonth) { Text("下月") }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        val leadingBlank = month.atDay(1).dayOfWeek.value - 1 // 周一为第一天
        val totalCells = leadingBlank + month.lengthOfMonth()
        val padded = ((totalCells + 6) / 7) * 7
        for (week in 0 until padded / 7) {
            Row(Modifier.fillMaxWidth()) {
                for (cell in 0 until 7) {
                    val index = week * 7 + cell
                    val dayNum = index - leadingBlank + 1
                    val day = if (index < leadingBlank || dayNum > month.lengthOfMonth()) null else month.atDay(dayNum)
                    if (day == null) {
                        Box(Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        val isSelected = day == selected
                        Box(
                            Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .padding(2.dp)
                                .background(
                                    color = when {
                                        isSelected -> MaterialTheme.colorScheme.primaryContainer
                                        else -> MaterialTheme.colorScheme.surface
                                    },
                                    shape = CircleShape,
                                )
                                .clickable { onSelect(day) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    day.dayOfMonth.toString(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = when {
                                        isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                                        day == today -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                )
                                Box(
                                    Modifier
                                        .size(4.dp)
                                        .background(
                                            color = if (day in marked) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surface,
                                            shape = CircleShape,
                                        ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarRow(entry: CalendarEntry, nowMillis: Long, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                entry.title,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(rangeLabel(entry), style = MaterialTheme.typography.bodySmall)
            Text("时区：${entry.timezoneId}", style = MaterialTheme.typography.bodySmall)
            if (CalendarTimes.hasEnded(entry, nowMillis)) {
                // 结束只表示时间已过，不表示业务完成
                Text("已结束", style = MaterialTheme.typography.labelSmall)
            }
            if (entry.category.isNotBlank()) {
                Text("分类：${entry.category}", style = MaterialTheme.typography.bodySmall)
            }
        }
        TextButton(onClick = onClick) { Text("详情") }
    }
}

/** 开始～结束时间文案；同日只显示一次日期，跨日显示两端日期时刻。 */
private fun rangeLabel(entry: CalendarEntry): String {
    val zone = ZoneId.of(entry.timezoneId)
    val start = Instant.ofEpochMilli(entry.startMillis).atZone(zone)
    val end = Instant.ofEpochMilli(entry.endMillis).atZone(zone)
    return if (start.toLocalDate() == end.toLocalDate()) {
        "${start.format(dateFmt)} ${start.format(timeFmt)} ～ ${end.format(timeFmt)}"
    } else {
        "${start.format(dateFmt)} ${start.format(timeFmt)} ～ ${end.format(dateFmt)} ${end.format(timeFmt)}"
    }
}

/** 新建/编辑日程：标题、开始结束日期时刻（校验）、时区（创建时锚定保存）、地点、备注、分类。 */
@Composable
fun CalendarEditorDialog(
    deviceZone: ZoneId,
    initial: CalendarEntry?,
    onDismiss: () -> Unit,
    onSave: (CalendarInput) -> Unit,
) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var location by remember { mutableStateOf(initial?.location ?: "") }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var category by remember { mutableStateOf(initial?.category ?: "") }
    val initialZone: ZoneId = remember {
        initial?.timezoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: deviceZone
    }
    var startText by remember {
        mutableStateOf(
            initial?.startMillis?.let {
                Instant.ofEpochMilli(it).atZone(initialZone).toLocalDate().format(dateFmt)
            } ?: LocalDate.now(deviceZone).format(dateFmt),
        )
    }
    var startTimeText by remember {
        mutableStateOf(
            initial?.startMillis?.let {
                Instant.ofEpochMilli(it).atZone(initialZone).toLocalTime().format(timeFmt)
            } ?: "09:00",
        )
    }
    var endText by remember {
        mutableStateOf(
            initial?.endMillis?.let {
                Instant.ofEpochMilli(it).atZone(initialZone).toLocalDate().format(dateFmt)
            } ?: LocalDate.now(deviceZone).format(dateFmt),
        )
    }
    var endTimeText by remember {
        mutableStateOf(
            initial?.endMillis?.let {
                Instant.ofEpochMilli(it).atZone(initialZone).toLocalTime().format(timeFmt)
            } ?: "10:00",
        )
    }
    var zoneText by remember { mutableStateOf(initialZone.id) }
    var zoneMenuExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val zoneCandidates = remember(zoneText) {
        val query = zoneText.trim()
        ZoneId.getAvailableZoneIds()
            .filter { it.contains(query, ignoreCase = true) }
            .sorted()
            .take(60)
    }

    fun buildInput(): CalendarInput? {
        if (title.isBlank()) {
            error = "标题不能为空"
            return null
        }
        val zone = runCatching { ZoneId.of(zoneText.trim()) }.getOrNull()
        if (zone == null) {
            error = "时区无效"
            return null
        }
        val startDate = runCatching { LocalDate.parse(startText.trim(), dateFmt) }.getOrNull()
        val startTime = runCatching { LocalTime.parse(startTimeText.trim(), timeFmt) }.getOrNull()
        val endDate = runCatching { LocalDate.parse(endText.trim(), dateFmt) }.getOrNull()
        val endTime = runCatching { LocalTime.parse(endTimeText.trim(), timeFmt) }.getOrNull()
        if (startDate == null || endDate == null) {
            error = "日期格式应为 yyyy-MM-dd"
            return null
        }
        if (startTime == null || endTime == null) {
            error = "时刻格式应为 HH:mm"
            return null
        }
        val startMillis = startDate.atTime(startTime).atZone(zone).toInstant().toEpochMilli()
        val endMillis = endDate.atTime(endTime).atZone(zone).toInstant().toEpochMilli()
        if (endMillis < startMillis) {
            error = "结束时间不能早于开始时间"
            return null
        }
        error = null
        return CalendarInput(
            title = title,
            startMillis = startMillis,
            endMillis = endMillis,
            timezoneId = zone.id,
            location = location,
            note = note,
            category = category,
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建日程" else "编辑日程") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it; error = null },
                    label = { Text("标题（必填）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = startText,
                    onValueChange = { startText = it; error = null },
                    label = { Text("开始日期 yyyy-MM-dd") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = startTimeText,
                    onValueChange = { startTimeText = it; error = null },
                    label = { Text("开始时刻 HH:mm") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = endText,
                    onValueChange = { endText = it; error = null },
                    label = { Text("结束日期 yyyy-MM-dd（跨日可不同）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = endTimeText,
                    onValueChange = { endTimeText = it; error = null },
                    label = { Text("结束时刻 HH:mm") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = zoneText,
                        onValueChange = { zoneText = it; error = null },
                        label = { Text("时区（创建时锚定保存）") },
                        modifier = Modifier.weight(1f),
                    )
                    Box {
                        TextButton(onClick = { zoneMenuExpanded = true }) { Text("选择") }
                        DropdownMenu(
                            expanded = zoneMenuExpanded,
                            onDismissRequest = { zoneMenuExpanded = false },
                        ) {
                            if (zoneCandidates.isEmpty()) {
                                Text("无匹配时区", modifier = Modifier.padding(8.dp))
                            }
                            zoneCandidates.forEach { zoneId ->
                                DropdownMenuItem(
                                    text = { Text(zoneId) },
                                    onClick = {
                                        zoneText = zoneId
                                        zoneMenuExpanded = false
                                        error = null
                                    },
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("地点（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("分类（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = { buildInput()?.let(onSave) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 日程详情：查看全部字段（明确展示所存时区与确认时间），提供编辑与删除（回收站）。 */
@Composable
fun CalendarDetailDialog(
    entry: CalendarEntry,
    deviceZone: ZoneId,
    reminderUseCases: com.zhideer.app.domain.usecase.CalendarReminderUseCases,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onTrash: () -> Unit,
) {
    val zone = runCatching { ZoneId.of(entry.timezoneId) }.getOrDefault(deviceZone)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.title) },
        text = {
            Column {
                Text("时间：${rangeLabel(entry)}")
                Text("时区：${entry.timezoneId}（创建时锚定保存，不随设备时区静默变化）")
                if (entry.location.isNotBlank()) Text("地点：${entry.location}")
                if (entry.category.isNotBlank()) Text("分类：${entry.category}")
                if (entry.note.isNotBlank()) Text("备注：${entry.note}")
                if (CalendarTimes.hasEnded(entry, System.currentTimeMillis())) {
                    Text("状态：已结束（时间已过，不代表完成）")
                }
                entry.zoneConfirmedAt?.let {
                    Text("时区确认于：${formatMillis(it, zone)}")
                }
                Text("创建于：${formatMillis(entry.createdAt, zone)}")
                com.zhideer.app.ui.CalendarRemindersPanel(
                    useCases = reminderUseCases,
                    entryId = entry.id,
                    zoneId = zone,
                    startMillis = entry.startMillis,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(
                        "若设备时区与所存时区不一致，请在列表上方的提示条中核对。",
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onTrash) { Text("删除") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

private fun formatMillis(millis: Long, zoneId: ZoneId): String {
    val zoned = Instant.ofEpochMilli(millis).atZone(zoneId)
    return zoned.format(dateFmt) + " " + zoned.format(timeFmt)
}
