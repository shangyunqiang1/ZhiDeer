package com.zhideer.app.data.db

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import com.zhideer.app.domain.model.RecurrenceFrequency
import com.zhideer.app.domain.model.RecurrenceRule
import com.zhideer.app.domain.model.TaskPriority
import com.zhideer.app.domain.model.TaskStatus
import com.zhideer.app.domain.model.TodoTask
import java.time.DayOfWeek

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val note: String,
    val priority: String,
    val category: String,
    val dueEpochDay: Long?,
    val dueAtMillis: Long?,
    val dueZoneId: String,
    val status: String,
    val trashedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
    val postponedAt: Long?,
    val cancelledAt: Long?,
    // T12/#13 纯新增：重复规则拆列（可空 = 非重复待办）。
    // 出现锚点不冗余存储，由 createdAt 在 dueZoneId 下派生。
    val recurrenceFrequency: String? = null,
    val recurrenceIntervalDays: Int? = null,
    val recurrenceWeekdays: String? = null,
)

fun TaskEntity.toModel() = TodoTask(
    id = id,
    title = title,
    note = note,
    priority = TaskPriority.entries.firstOrNull { it.name == priority } ?: TaskPriority.NORMAL,
    category = category,
    dueEpochDay = dueEpochDay,
    dueAtMillis = dueAtMillis,
    dueZoneId = dueZoneId,
    status = TaskStatus.entries.firstOrNull { it.name == status } ?: TaskStatus.ACTIVE,
    trashedAt = trashedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    completedAt = completedAt,
    postponedAt = postponedAt,
    cancelledAt = cancelledAt,
    recurrenceRule = recurrenceFrequency?.toRecurrenceRule(recurrenceIntervalDays, recurrenceWeekdays),
)

fun TodoTask.toEntity() = TaskEntity(
    id = id,
    title = title,
    note = note,
    priority = priority.name,
    category = category,
    dueEpochDay = dueEpochDay,
    dueAtMillis = dueAtMillis,
    dueZoneId = dueZoneId,
    status = status.name,
    trashedAt = trashedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    completedAt = completedAt,
    postponedAt = postponedAt,
    cancelledAt = cancelledAt,
    recurrenceFrequency = recurrenceRule?.frequency?.name,
    recurrenceIntervalDays = recurrenceRule?.takeIf { it.frequency == RecurrenceFrequency.DAILY }?.intervalDays,
    recurrenceWeekdays = recurrenceRule
        ?.takeIf { it.frequency == RecurrenceFrequency.WEEKLY }
        ?.weekdays?.sorted()?.joinToString(",") { it.name },
)

private fun String.toRecurrenceRule(intervalDays: Int?, weekdaysRaw: String?): RecurrenceRule? = when (this) {
    RecurrenceFrequency.DAILY.name -> RecurrenceRule(
        frequency = RecurrenceFrequency.DAILY,
        intervalDays = intervalDays ?: 1,
    )
    RecurrenceFrequency.WEEKLY.name -> RecurrenceRule(
        frequency = RecurrenceFrequency.WEEKLY,
        weekdays = weekdaysRaw?.split(",")?.mapNotNull { raw ->
            DayOfWeek.entries.firstOrNull { it.name == raw }
        }?.toSet() ?: emptySet(),
    )
    else -> null
}
