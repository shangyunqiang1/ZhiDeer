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

    fun matches(task: TodoTask, filter: TaskFilter, nowMillis: Long): Boolean {
        if (task.trashedAt != null) return false
        return when (filter) {
            TaskFilter.ALL -> true
            TaskFilter.COMPLETED -> task.status == TaskStatus.COMPLETED
            TaskFilter.TODAY -> task.status == TaskStatus.ACTIVE && isDueToday(task, nowMillis)
            TaskFilter.OVERDUE -> task.status == TaskStatus.ACTIVE && isOverdue(task, nowMillis)
            TaskFilter.UPCOMING -> task.status == TaskStatus.ACTIVE && hasDue(task) &&
                !isDueToday(task, nowMillis) && !isOverdue(task, nowMillis)
        }
    }
}
