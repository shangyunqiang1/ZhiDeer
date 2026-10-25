package com.zhideer.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
import com.zhideer.app.domain.model.RecurrenceRule
import java.time.LocalDate

/**
 * 单次延期对话框（T14/#15，issue #15 验收第 3 条）。
 * 公开 composable 供根界面挂接（同 RemindersPanel/SubTasksPanel 模式，不改动
 * TasksScreen/TaskDialogs）：把重复待办某一次出现移到指定日期。
 *
 * 预览明确说明（验收要求）：
 * - 原日期 X 的这一次将移到 Y，其余出现日期不变（单次延期不改变原后续日期）；
 * - 目标日 Y 已有常规出现时，说明这一天将同日出现两次、需分别完成。
 *
 * 保存回调 [onPostpone] 传目标日（epochDay）；确认前即时校验：
 * 日期格式 yyyy-MM-dd、目标日不得与原日相同。
 */
@Composable
fun OccurrencePostponeDialog(
    originalEpochDay: Long,
    rule: RecurrenceRule,
    anchorEpochDay: Long,
    onDismiss: () -> Unit,
    onPostpone: (targetEpochDay: Long) -> Unit,
) {
    var targetText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    /** 解析并校验目标日；合法返回 epochDay，否则返回 null 并设置错误提示。 */
    fun parseTarget(): Long? {
        val parsed = runCatching { LocalDate.parse(targetText.trim()) }.getOrNull()
        if (parsed == null) {
            error = "日期格式应为 yyyy-MM-dd"
            return null
        }
        val target = parsed.toEpochDay()
        if (target == originalEpochDay) {
            error = "目标日期与原日期相同，无需延期"
            return null
        }
        error = null
        return target
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("单次延期") },
        text = {
            Column {
                Text(
                    "原日期：${LocalDate.ofEpochDay(originalEpochDay)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = targetText,
                    onValueChange = { targetText = it; error = null },
                    label = { Text("延期到 yyyy-MM-dd") },
                    modifier = Modifier.fillMaxWidth(),
                )
                val parsed = runCatching { LocalDate.parse(targetText.trim()) }.getOrNull()
                if (parsed != null && parsed.toEpochDay() != originalEpochDay) {
                    val target = parsed.toEpochDay()
                    Text(
                        "原日期 ${LocalDate.ofEpochDay(originalEpochDay)} 的这一次将移到 " +
                            "${LocalDate.ofEpochDay(target)}，其余出现日期不变。",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    if (rule.occursOn(anchorEpochDay, target)) {
                        Text(
                            "${LocalDate.ofEpochDay(target)} 已有常规出现：这一天将同日出现两次，需分别完成。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                Text(
                    "只移动这一次，不改变重复规则与其余出现日期。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp),
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                parseTarget()?.let(onPostpone)
            }) { Text("延期") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
