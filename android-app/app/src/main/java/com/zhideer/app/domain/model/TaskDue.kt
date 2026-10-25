package com.zhideer.app.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 待办列表过滤：今天、即将到期、过期、全部、已完成。结果一律来自真实持久记录。 */
enum class TaskFilter(val label: String) {
    TODAY("今天"),
    UPCOMING("即将到期"),
    OVERDUE("过期"),
    ALL("全部"),
    COMPLETED("已完成"),
}

/**
 * 截止语义（GLOSSARY：日期截止到期当天属于今日到期，次日属于过期，不隐含 23:59）。
 * 判定统一使用任务创建时锚定的 [TodoTask.dueZoneId]。
 *
 * 重复待办（T12/#13）以出现维度判定：[RecurringDueState] 携带“下一次未完成出现”，
 * 由调用方（TaskUseCases）依据重复规则与各次完成记录合成后传入四参 [matches]。
 */
object TaskDue {

    fun todayEpochDay(zoneId: String, nowMillis: Long): Long =
        LocalDate.ofInstant(Instant.ofEpochMilli(nowMillis), ZoneId.of(zoneId)).toEpochDay()

    /** 该待办是否有截止。 */
    fun hasDue(task: TodoTask): Boolean = task.dueEpochDay != null || task.dueAtMillis != null

    /** 日期截止：到期当天为 true。 */
    fun isDueToday(task: TodoTask, nowMillis: Long): Boolean {
        val today = todayEpochDay(task.dueZoneId, nowMillis)
        return when {
            task.dueEpochDay != null -> task.dueEpochDay == today
            task.dueAtMillis != null ->
                LocalDate.ofInstant(Instant.ofEpochMilli(task.dueAtMillis), ZoneId.of(task.dueZoneId)).toEpochDay() == today
            else -> false
        }
    }

    /** 已过期：日期截止晚于今天（到期当天不算），或时刻截止时刻已过。 */
    fun isOverdue(task: TodoTask, nowMillis: Long): Boolean {
        val today = todayEpochDay(task.dueZoneId, nowMillis)
        return when {
            task.dueEpochDay != null -> task.dueEpochDay < today
            task.dueAtMillis != null -> task.dueAtMillis < nowMillis
            else -> false
        }
    }

    fun matches(task: TodoTask, filter: TaskFilter, nowMillis: Long): Boolean =
        matches(task, filter, nowMillis, recurring = null)

    /**
     * T12/#13 纯新增：带重复出现状态的过滤判定。
     * 重复待办按“下一次未完成出现”呈现，错过未处理的下一次未完成出现按逾期呈现
     * （GLOSSARY：错过不自动完成、不静默消失）；既有非重复行为不变（[recurring] 为 null 时）。
     */
    fun matches(task: TodoTask, filter: TaskFilter, nowMillis: Long, recurring: RecurringDueState?): Boolean {
        if (task.trashedAt != null) return false
        if (recurring != null) {
            return when (filter) {
                TaskFilter.ALL -> true
                TaskFilter.COMPLETED -> task.status == TaskStatus.COMPLETED
                TaskFilter.TODAY -> task.status == TaskStatus.ACTIVE &&
                    recurring.nextUnfinishedDay == recurring.todayEpochDay
                TaskFilter.OVERDUE -> task.status == TaskStatus.ACTIVE &&
                    recurring.nextUnfinishedDay != null && recurring.nextUnfinishedDay < recurring.todayEpochDay
                TaskFilter.UPCOMING -> task.status == TaskStatus.ACTIVE &&
                    recurring.nextUnfinishedDay != null && recurring.nextUnfinishedDay > recurring.todayEpochDay
            }
        }
        return when (filter) {
            TaskFilter.ALL -> true
            TaskFilter.COMPLETED -> task.status == TaskStatus.COMPLETED
            TaskFilter.TODAY -> task.status == TaskStatus.ACTIVE && isDueToday(task, nowMillis)
            TaskFilter.OVERDUE -> task.status == TaskStatus.ACTIVE && isOverdue(task, nowMillis)
            TaskFilter.UPCOMING -> task.status == TaskStatus.ACTIVE && hasDue(task) &&
                !isDueToday(task, nowMillis) && !isOverdue(task, nowMillis)
        }
    }

    /**
     * 重复待办的出现判定状态（T12/#13）：下一次未完成出现日（epochDay）与该待办
     * 创建时区下的今天。由 TaskUseCases.observe 依据重复规则与各次完成记录合成。
     */
    data class RecurringDueState(
        val nextUnfinishedDay: Long?,
        val todayEpochDay: Long,
    )
}
