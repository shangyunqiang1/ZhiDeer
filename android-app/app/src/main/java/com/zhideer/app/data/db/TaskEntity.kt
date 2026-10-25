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
    // T13/#14：MONTHLY 复用 recurrenceIntervalDays 列存「每月第几日」（1-31），
    // 以 recurrenceFrequency 列区分两种语义，避免新增列导致数据库升版。
    // T14/#15 纯新增：例外日（epochDay）集合，逗号分隔序列化（与 recurrenceWeekdays 同风格）。
    // 被排除的出现日不再出现/不再要求完成；空串与 null 均视为无例外，旧数据天然兼容。
    val recurrenceFrequency: String? = null,
    val recurrenceIntervalDays: Int? = null,
    val recurrenceWeekdays: String? = null,
    val recurrenceExceptions: String? = null,
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
    recurrenceRule = recurrenceFrequency?.toRecurrenceRule(recurrenceIntervalDays, recurrenceWeekdays, recurrenceExceptions),
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
    recurrenceIntervalDays = recurrenceRule?.let { rule ->
        when (rule.frequency) {
            RecurrenceFrequency.DAILY -> rule.intervalDays
            RecurrenceFrequency.MONTHLY -> rule.monthDay
            else -> null
        }
    },
    recurrenceWeekdays = recurrenceRule
        ?.takeIf { it.frequency == RecurrenceFrequency.WEEKLY }
        ?.weekdays?.sorted()?.joinToString(",") { it.name },
    recurrenceExceptions = recurrenceRule
        ?.takeIf { it.exceptions.isNotEmpty() }
        ?.exceptions?.sorted()?.joinToString(",") { it.toString() },
)

private fun String.toRecurrenceRule(intervalDays: Int?, weekdaysRaw: String?, exceptionsRaw: String?): RecurrenceRule? {
    // T14/#15：例外日在三种频率下都有效；解析失败或空值一律视为无例外
    val exceptions = exceptionsRaw?.split(",")?.mapNotNull { it.trim().toLongOrNull() }?.toSet() ?: emptySet()
    return when (this) {
        RecurrenceFrequency.DAILY.name -> RecurrenceRule(
            frequency = RecurrenceFrequency.DAILY,
            intervalDays = intervalDays ?: 1,
            exceptions = exceptions,
        )
        RecurrenceFrequency.MONTHLY.name -> RecurrenceRule(
            frequency = RecurrenceFrequency.MONTHLY,
            monthDay = intervalDays ?: 1,
            exceptions = exceptions,
        )
        RecurrenceFrequency.WEEKLY.name -> RecurrenceRule(
            frequency = RecurrenceFrequency.WEEKLY,
            weekdays = weekdaysRaw?.split(",")?.mapNotNull { raw ->
                DayOfWeek.entries.firstOrNull { it.name == raw }
            }?.toSet() ?: emptySet(),
            exceptions = exceptions,
        )
        else -> null
    }
}
