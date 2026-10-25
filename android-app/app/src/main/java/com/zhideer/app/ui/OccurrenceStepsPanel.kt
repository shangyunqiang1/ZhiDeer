package com.zhideer.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.zhideer.app.domain.usecase.OccurrenceStepsSnapshot
import com.zhideer.app.domain.usecase.OccurrenceSubTaskUseCases
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val occurrenceStepTimeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * 出现步骤面板（T15/#16）：展示重复待办某一次出现的子任务步骤清单。
 * - 步骤清单来自父待办的子任务模板，勾选状态按该次出现分别记录：
 *   每次使用当期步骤重新开始，初始未完成，不沿用上次勾选状态；
 * - 勾选/撤销只写该次出现的步骤记录，绝不改动该次出现的完成状态，
 *   更不改动父待办状态（GLOSSARY：分别确认，互不联动）；
 * - 例外日与被单次延期走的原日没有步骤清单（显示为空提示）。
 * Root 将其挂接进出现详情即可（传入 [occurrenceEpochDay] 与 [originalEpochDay]），
 * 无需改动 TasksScreen/TaskDialogs/SubTasksPanel 等既有界面。
 */
@Composable
fun OccurrenceStepsPanel(
    useCases: OccurrenceSubTaskUseCases,
    taskId: String,
    occurrenceEpochDay: Long,
    modifier: Modifier = Modifier,
    originalEpochDay: Long = occurrenceEpochDay,
    zoneId: ZoneId = ZoneId.systemDefault(),
) {
    val scope = rememberCoroutineScope()
    val snapshot by remember(taskId, occurrenceEpochDay, originalEpochDay) {
        useCases.observeForOccurrence(taskId, occurrenceEpochDay, originalEpochDay)
    }.collectAsState(initial = OccurrenceStepsSnapshot(emptyList()))
    var error by remember { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("本次步骤", style = MaterialTheme.typography.titleSmall)
            Text(
                "已完成 ${snapshot.doneCount}/${snapshot.total}",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        if (snapshot.steps.isEmpty()) {
            Text(
                "该次出现没有步骤清单",
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
        snapshot.steps.forEach { step ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = step.done,
                    onCheckedChange = { checked ->
                        error = null
                        scope.launch {
                            useCases.toggleStep(
                                taskId = taskId,
                                occurrenceEpochDay = occurrenceEpochDay,
                                subTaskId = step.subTaskId,
                                done = checked,
                                originalEpochDay = originalEpochDay,
                            ).onFailure { error = it.message }
                        }
                    },
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        step.title,
                        style = if (step.done) {
                            MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.outline)
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                    )
                    step.doneAt?.let {
                        Text(
                            "完成于 " + Instant.ofEpochMilli(it).atZone(zoneId).format(occurrenceStepTimeFmt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
            HorizontalDivider()
        }
    }
}
