package com.zhideer.app.domain.usecase

import com.zhideer.app.domain.model.CalendarEntry
import com.zhideer.app.domain.model.CalendarTimes
import com.zhideer.app.domain.model.TaskFilter
import com.zhideer.app.domain.model.TodoTask
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** 下一事项：下一个截止的待办或下一个开始的日程（分别呈现，不以 AI 生成替代数据库事实）。 */
data class NextUpItem(
    val kind: String, // "task" | "schedule"
    val id: String,
    val title: String,
    val atMillis: Long,
    val isDateOnly: Boolean,
    val overdue: Boolean,
)

data class HomeStats(
    val dueTodayCount: Int,
    val overdueCount: Int,
    val completedTodayCount: Int,
)

data class HomeSnapshot(
    val nextTask: NextUpItem?,
    val nextSchedule: NextUpItem?,
    val todaySchedules: List<CalendarEntry>,
    val overdueTasks: List<TodoTask>,
    val stats: HomeStats,
)

/**
 * 首页真实数据汇总（T34/#35）：只读、实时、离线可用。
 * 全部数据来自真实持久记录（待办过滤含重复出现 occurrence 判定；日程按各自锚定时区归属今天）。
 */
class HomeUseCases(
    private val taskUseCases: TaskUseCases,
    private val calendarUseCases: CalendarUseCases,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Long = System::currentTimeMillis,
) {

    fun observeHome(): Flow<HomeSnapshot> = combine(
        taskUseCases.observe(TaskFilter.ALL),
        taskUseCases.observe(TaskFilter.TODAY),
        taskUseCases.observe(TaskFilter.OVERDUE),
        calendarUseCases.observeAll(),
    ) { all, today, overdue, schedules ->
        buildSnapshot(all, today, overdue, schedules)
    }

    private fun buildSnapshot(
        all: List<TodoTask>,
        dueToday: List<TodoTask>,
        overdue: List<TodoTask>,
        schedules: List<CalendarEntry>,
    ): HomeSnapshot {
        val nowMillis = now()
        val today = LocalDate.ofInstant(Instant.ofEpochMilli(nowMillis), zoneId)

        val nextTask = all
            .filter { it.status == com.zhideer.app.domain.model.TaskStatus.ACTIVE && it.trashedAt == null }
            .mapNotNull { t ->
                when {
                    t.dueAtMillis != null -> NextUpItem("task", t.id, t.title, t.dueAtMillis!!, false, t.dueAtMillis!! < nowMillis)
                    t.dueEpochDay != null -> {
                        val at = LocalDate.ofEpochDay(t.dueEpochDay!!)
                            .atStartOfDay(ZoneId.of(t.dueZoneId)).toInstant().toEpochMilli()
                        NextUpItem("task", t.id, t.title, at, true, t.dueEpochDay!! < today.toEpochDay())
                    }
                    else -> null
                }
            }
            .minByOrNull { it.atMillis }

        val nextSchedule = schedules
            .filter { it.trashedAt == null && it.startMillis > nowMillis }
            .map { NextUpItem("schedule", it.id, it.title, it.startMillis, false, false) }
            .minByOrNull { it.atMillis }

        val todaySchedules = schedules
            .filter { it.trashedAt == null }
            .filter { entry ->
                val zone = runCatching { ZoneId.of(entry.timezoneId) }.getOrDefault(zoneId)
                LocalDate.ofInstant(Instant.ofEpochMilli(entry.startMillis), zone) == today ||
                    LocalDate.ofInstant(Instant.ofEpochMilli(entry.endMillis), zone) == today
            }
            .sortedBy { it.startMillis }

        val completedToday = all.count { t ->
            t.status == com.zhideer.app.domain.model.TaskStatus.COMPLETED &&
                t.completedAt != null &&
                LocalDate.ofInstant(Instant.ofEpochMilli(t.completedAt!!), zoneId) == today
        }

        return HomeSnapshot(
            nextTask = nextTask,
            nextSchedule = nextSchedule,
            todaySchedules = todaySchedules,
            overdueTasks = overdue.sortedBy { it.updatedAt },
            stats = HomeStats(dueToday.size, overdue.size, completedToday),
        )
    }
}
