package com.zhideer.app.domain.usecase

import com.zhideer.app.domain.model.RecurrenceRule
import com.zhideer.app.domain.model.TaskDue
import com.zhideer.app.domain.model.RecurrenceAnchors
import com.zhideer.app.domain.model.TaskDue.RecurringDueState
import com.zhideer.app.domain.model.TaskOccurrence
import com.zhideer.app.domain.model.TaskStatus
import com.zhideer.app.domain.model.TodoTask
import com.zhideer.app.domain.repository.TaskOccurrenceRepository
import com.zhideer.app.domain.repository.TasksRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** 重复待办在某一日的出现视图：待办、出现日与该次完成状态。 */
data class RecurringTaskOccurrence(
    val task: TodoTask,
    val occurrenceEpochDay: Long,
    val completed: Boolean,
)

/**
 * 统一重复待办用例入口（T12/#13）。
 * GLOSSARY：重复待办按照固定日期规则反复出现，每一次分别记录完成或未完成，
 * 本次未完成不阻止下一次出现。出现锚点由待办 createdAt 在创建时锚定时区（dueZoneId）下
 * 派生（[anchorEpochDay]），不冗余存储、天然幂等；完成记录以 (taskId, 出现日) 主键存储，
 * 重复写入不产生重复记录。不实现“完成后再重复推进同一记录”的上游模式。
 */
class RecurrenceUseCases(
    private val tasksRepository: TasksRepository,
    private val occurrenceRepository: TaskOccurrenceRepository,
    private val now: () -> Long,
) {

    /** 为尚无规则的待办设置重复规则（规则须至少一个有效配置）。 */
    suspend fun create(taskId: String, rule: RecurrenceRule): Result<TodoTask> {
        RecurrenceRule.validate(rule)?.let { return Result.failure(IllegalArgumentException(it)) }
        val task = tasksRepository.getTask(taskId)
            ?: return Result.failure(IllegalStateException("待办不存在或已永久删除"))
        if (task.trashedAt != null) {
            return Result.failure(IllegalStateException("待办已进入回收站"))
        }
        if (task.recurrenceRule != null) {
            return Result.failure(IllegalStateException("该待办已设置重复规则，请使用编辑修改"))
        }
        val updated = task.copy(recurrenceRule = rule, updatedAt = now())
        tasksRepository.upsertTask(updated)
        return Result.success(updated)
    }

    /** 修改既有重复规则（规则须至少一个有效配置；传 null 表示移除）。 */
    suspend fun update(taskId: String, rule: RecurrenceRule?): Result<TodoTask> {
        RecurrenceRule.validate(rule)?.let { return Result.failure(IllegalArgumentException(it)) }
        val task = tasksRepository.getTask(taskId)
            ?: return Result.failure(IllegalStateException("待办不存在或已永久删除"))
        if (task.trashedAt != null) {
            return Result.failure(IllegalStateException("待办已进入回收站"))
        }
        val updated = task.copy(recurrenceRule = rule, updatedAt = now())
        tasksRepository.upsertTask(updated)
        return Result.success(updated)
    }

    /** 移除重复规则，待办保留为普通待办；各次完成记录保留供核对。 */
    suspend fun remove(taskId: String): Result<TodoTask> = update(taskId, null)

    /**
     * 规则在 [startDay, endDay] 内的全部出现日（按创建锚点与时区，升序）。
     * 仅供核对与预览；取消/回收站待办的规则几何结果仍可读，但不再进入列表过滤。
     */
    suspend fun occurrencesForRange(taskId: String, startDay: Long, endDay: Long): Result<List<Long>> {
        val task = tasksRepository.getTask(taskId)
            ?: return Result.failure(IllegalStateException("待办不存在或已永久删除"))
        val rule = task.recurrenceRule
            ?: return Result.failure(IllegalStateException("该待办不是重复待办"))
        return Result.success(rule.occurrencesBetween(anchorEpochDay(task), startDay, endDay))
    }

    /**
     * 完成或撤销某一次出现：完成记录独立时间戳；重复调用同一出现幂等（主键约束）。
     * 仅 ACTIVE 的重复待办可记录；非出现日、非重复待办被拒绝并返回中文说明。
     */
    suspend fun completeOccurrence(
        taskId: String,
        occurrenceEpochDay: Long,
        completed: Boolean,
    ): Result<TaskOccurrence> {
        val task = tasksRepository.getTask(taskId)
            ?: return Result.failure(IllegalStateException("待办不存在或已永久删除"))
        if (task.trashedAt != null) {
            return Result.failure(IllegalStateException("待办已进入回收站"))
        }
        if (task.status == TaskStatus.CANCELLED) {
            return Result.failure(IllegalStateException("待办已取消，不再记录各次完成"))
        }
        val rule = task.recurrenceRule
            ?: return Result.failure(IllegalStateException("该待办不是重复待办"))
        if (!rule.occursOn(anchorEpochDay(task), occurrenceEpochDay)) {
            return Result.failure(IllegalArgumentException("该日期不属于重复计划"))
        }
        val t = now()
        val row = TaskOccurrence(
            taskId = taskId,
            occurrenceEpochDay = occurrenceEpochDay,
            completed = completed,
            completedAt = if (completed) t else null,
            updatedAt = t,
        )
        occurrenceRepository.upsert(row)
        return Result.success(row)
    }

    /** 观察某日在重复计划中出现的待办及其该次完成状态（排除取消与回收站）。 */
    fun observeForDay(day: Long): Flow<List<RecurringTaskOccurrence>> =
        combine(tasksRepository.observeTasks(), occurrenceRepository.observeAll()) { tasks, occurrences ->
            val doneByTask = occurrences.filter { it.completed }
                .groupBy { it.taskId }
                .mapValues { (_, rows) -> rows.map { it.occurrenceEpochDay }.toSet() }
            tasks.mapNotNull { task ->
                val rule = task.recurrenceRule ?: return@mapNotNull null
                if (task.trashedAt != null || task.status != TaskStatus.ACTIVE) return@mapNotNull null
                if (!rule.occursOn(anchorEpochDay(task), day)) return@mapNotNull null
                RecurringTaskOccurrence(
                    task = task,
                    occurrenceEpochDay = day,
                    completed = day in (doneByTask[task.id] ?: emptySet()),
                )
            }
        }

    /** 观察某待办的各次完成记录（供详情核对，按出现日升序）。 */
    fun observeOccurrences(taskId: String): Flow<List<TaskOccurrence>> =
        occurrenceRepository.observeAll().map { rows ->
            rows.filter { it.taskId == taskId }.sortedBy { it.occurrenceEpochDay }
        }

    companion object {

        /** 出现锚点：createdAt 在创建时锚定时区（dueZoneId）下的日期。 */
        fun anchorEpochDay(task: TodoTask): Long = RecurrenceAnchors.anchorEpochDay(task)

        /**
         * 下一次未完成出现：从锚点起最早一次尚未标记完成的出现在今天或过去（含错过）；
         * 全部已完/无错过时为今天之后的最近一次出现。规则有效则恒有解。
         */
        fun nextUnfinishedDay(task: TodoTask, completedDays: Set<Long>, todayEpochDay: Long): Long {
            val rule = task.recurrenceRule ?: error("非重复待办没有出现序列")
            val anchor = anchorEpochDay(task)
            val pastUnfinished = rule.occurrencesBetween(anchor, anchor, todayEpochDay)
                .firstOrNull { it !in completedDays }
            return pastUnfinished ?: rule.nextOnOrAfter(anchor, todayEpochDay + 1)
        }

        /** 合成列表过滤所需的重复出现状态；非重复待办返回 null。 */
        fun recurringState(task: TodoTask, completedDays: Set<Long>, nowMillis: Long): RecurringDueState? {
            if (task.recurrenceRule == null) return null
            val today = TaskDue.todayEpochDay(task.dueZoneId, nowMillis)
            return RecurringDueState(
                nextUnfinishedDay = nextUnfinishedDay(task, completedDays, today),
                todayEpochDay = today,
            )
        }
    }
}
