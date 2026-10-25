package com.zhideer.app.ui

import androidx.compose.foundation.layout.Arrangement
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
import com.zhideer.app.domain.model.RecurrenceFrequency
import com.zhideer.app.domain.model.RecurrenceRule
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 重复规则编辑对话框（T12/#13 每日与每周；T13/#14 每月）。独立 composable 供根界面挂接
 * （不改动既有待办编辑器）：
 * - 无 / 每日（可选间隔天数）/ 每周（星期几多选）/ 每月（第 N 日，1-31），全部中文界面与提示；
 * - 每月第 N 日大于 28 时提示短月回退：2 月等短月落到当月最后一天，之后月份回到第 N 日
 *   （issue #14：不漂移）；下方预览会给出实际出现日期；
 * - 保存前预览具体规则摘要与 upcoming 出现日期（issue #13/#14 验收）；
 * - 保存回调 [onSave] 传 null 表示移除重复规则；非法配置即时提示且不触发保存。
 * [anchorEpochDay] 为出现锚点（新建时传今天，编辑既有待办时传
 * RecurrenceUseCases.anchorEpochDay(task)）；[todayEpochDay] 为预览起点。
 */
@Composable
fun RecurrenceEditorDialog(
    anchorEpochDay: Long,
    todayEpochDay: Long,
    initialRule: RecurrenceRule?,
    onDismiss: () -> Unit,
    onSave: (RecurrenceRule?) -> Unit,
) {
    var kind by remember {
        mutableStateOf(
            when {
                initialRule == null -> RecurrenceKind.NONE
                initialRule.frequency == RecurrenceFrequency.DAILY -> RecurrenceKind.DAILY
                initialRule.frequency == RecurrenceFrequency.MONTHLY -> RecurrenceKind.MONTHLY
                else -> RecurrenceKind.WEEKLY
            },
        )
    }
    var intervalText by remember {
        mutableStateOf(
            initialRule?.takeIf { it.frequency == RecurrenceFrequency.DAILY }?.intervalDays?.toString() ?: "1",
        )
    }
    var weekdays by remember {
        mutableStateOf(
            initialRule?.takeIf { it.frequency == RecurrenceFrequency.WEEKLY }?.weekdays ?: emptySet(),
        )
    }
    var monthDayText by remember {
        mutableStateOf(
            initialRule?.takeIf { it.frequency == RecurrenceFrequency.MONTHLY }?.monthDay?.toString() ?: "1",
        )
    }
    var error by remember { mutableStateOf<String?>(null) }

    fun buildRule(): RecurrenceRule? {
        val rule = when (kind) {
            RecurrenceKind.NONE -> null
            RecurrenceKind.DAILY -> {
                val interval = intervalText.trim().toIntOrNull()
                if (interval == null) {
                    error = "间隔天数应为正整数"
                    return null
                }
                RecurrenceRule(frequency = RecurrenceFrequency.DAILY, intervalDays = interval)
            }
            RecurrenceKind.WEEKLY -> RecurrenceRule(frequency = RecurrenceFrequency.WEEKLY, weekdays = weekdays)
            RecurrenceKind.MONTHLY -> {
                val monthDay = monthDayText.trim().toIntOrNull()
                if (monthDay == null) {
                    error = "每月第几日应为 1-31 的整数"
                    return null
                }
                RecurrenceRule(frequency = RecurrenceFrequency.MONTHLY, monthDay = monthDay)
            }
        }
        RecurrenceRule.validate(rule)?.let {
            error = it
            return null
        }
        error = null
        return rule
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重复") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row {
                    FilterChip(
                        selected = kind == RecurrenceKind.NONE,
                        onClick = { kind = RecurrenceKind.NONE; error = null },
                        label = { Text("不重复") },
                    )
                    FilterChip(
                        selected = kind == RecurrenceKind.DAILY,
                        onClick = { kind = RecurrenceKind.DAILY; error = null },
                        label = { Text("每日") },
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    FilterChip(
                        selected = kind == RecurrenceKind.WEEKLY,
                        onClick = { kind = RecurrenceKind.WEEKLY; error = null },
                        label = { Text("每周") },
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    FilterChip(
                        selected = kind == RecurrenceKind.MONTHLY,
                        onClick = { kind = RecurrenceKind.MONTHLY; error = null },
                        label = { Text("每月") },
                    )
                }
                if (kind == RecurrenceKind.DAILY) {
                    OutlinedTextField(
                        value = intervalText,
                        onValueChange = { intervalText = it; error = null },
                        label = { Text("间隔天数（1 表示每天）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (kind == RecurrenceKind.WEEKLY) {
                    Text("选择星期几（至少一个）", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        WEEKDAY_ORDER.forEach { day ->
                            FilterChip(
                                selected = day in weekdays,
                                onClick = {
                                    weekdays = if (day in weekdays) weekdays - day else weekdays + day
                                    error = null
                                },
                                label = { Text(RecurrenceRule.weekdayName(day)) },
                            )
                        }
                    }
                }
                if (kind == RecurrenceKind.MONTHLY) {
                    OutlinedTextField(
                        value = monthDayText,
                        onValueChange = { monthDayText = it; error = null },
                        label = { Text("每月第几日（1-31）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val monthDay = monthDayText.trim().toIntOrNull()
                    if (monthDay != null && monthDay > 28) {
                        // 含 29 日：非闰年 2 月只有 28 天（issue #14 覆盖闰年边界）
                        Text(
                            "2 月等短月不足 $monthDay 日时落到当月最后一天，之后月份回到第 $monthDay 日，不漂移。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                val current = buildRuleQuiet(kind, intervalText, weekdays, monthDayText)
                if (current != null) {
                    Text(
                        "规则：${current.summary()}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    val preview = recurrencePreviewDays(current, anchorEpochDay, todayEpochDay, count = 5)
                    if (preview.isNotEmpty()) {
                        Text(
                            "接下来：${preview.joinToString("、") { LocalDate.ofEpochDay(it).toString() }}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        "每一次出现分别记录完成，本次未完成不阻止下一次出现。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                buildRule()?.let(onSave)
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 待办行/详情用的规则摘要（中文，如「每天」「每周 一、三、五」）；无规则不显示。 */
@Composable
fun RecurrenceRuleSummaryText(rule: RecurrenceRule?, modifier: Modifier = Modifier) {
    if (rule != null) {
        Text(
            rule.summary(),
            style = MaterialTheme.typography.bodySmall,
            modifier = modifier,
        )
    }
}

/** 静默构建规则（仅用于实时预览，不弹错误）。 */
private fun buildRuleQuiet(kind: RecurrenceKind, intervalText: String, weekdays: Set<DayOfWeek>, monthDayText: String): RecurrenceRule? {
    val rule = when (kind) {
        RecurrenceKind.NONE -> return null
        RecurrenceKind.DAILY -> {
            val interval = intervalText.trim().toIntOrNull() ?: return null
            RecurrenceRule(frequency = RecurrenceFrequency.DAILY, intervalDays = interval)
        }
        RecurrenceKind.WEEKLY -> RecurrenceRule(frequency = RecurrenceFrequency.WEEKLY, weekdays = weekdays)
        RecurrenceKind.MONTHLY -> {
            val monthDay = monthDayText.trim().toIntOrNull() ?: return null
            RecurrenceRule(frequency = RecurrenceFrequency.MONTHLY, monthDay = monthDay)
        }
    }
    return if (RecurrenceRule.validate(rule) == null) rule else null
}

/** 预览 upcoming：从 [fromDay] 起（含当天）最多 [count] 次出现，锚点之前不出现。 */
fun recurrencePreviewDays(rule: RecurrenceRule, anchorEpochDay: Long, fromDay: Long, count: Int = 5): List<Long> {
    val start = maxOf(fromDay, anchorEpochDay)
    return rule.occurrencesBetween(anchorEpochDay, start, start + 366L).take(count)
}

private enum class RecurrenceKind { NONE, DAILY, WEEKLY, MONTHLY }

private val WEEKDAY_ORDER = listOf(
    DayOfWeek.MONDAY,
    DayOfWeek.TUESDAY,
    DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY,
    DayOfWeek.SATURDAY,
    DayOfWeek.SUNDAY,
)
