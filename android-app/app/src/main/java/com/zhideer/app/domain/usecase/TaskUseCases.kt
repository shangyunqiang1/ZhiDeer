package com.zhideer.app.domain.usecase

import com.zhideer.app.domain.model.RecurrenceRule
import com.zhideer.app.domain.model.TaskDue
import com.zhideer.app.domain.model.TaskFilter
import com.zhideer.app.domain.model.TaskOccurrence
import com.zhideer.app.domain.model.TaskPriority
import com.zhideer.app.domain.model.TaskStatus
import com.zhideer.app.domain.model.TodoTask
import com.zhideer.app.domain.repository.TaskOccurrenceRepository
import com.zhideer.app.domain.repository.TasksRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** 待办编辑输入；截止两种形态互斥。 */
data class TaskInput(
    val title: String,
    val note: String = "",
    val priority: TaskPriority = TaskPriority.NORMAL,
    val category: String = "",
    val dueEpochDay: Long? = null,
    val dueAtMillis: Long? = null,
    val dueZoneId: String,
    // T12/#13 纯新增：重复规则（可空 = 普通待办）。GLOSSARY：重复待办按固定日期规则
    // 反复出现，每一次分别记录完成；不采用“完成后再重复推进同一记录”的模式。
    val recurrenceRule: RecurrenceRule? = null,
)

/**
 * 统一待办用例入口：创建、编辑、完成、延期、取消、回收站删除与过滤查询。
 * 完成、延期、取消分别记录时间戳；取消与删除（回收站）是不同的业务动作。
 * 完成/延期/取消均不触碰子任务（T07/#8：父子分别确认完成，互不联动）。
 */
class TaskUseCases(
    private val repository: TasksRepository,
    private val idGen: () -> String,
    private val now: () -> Long,
    private val zoneId: String,
    // T07/#8 纯新增：待办进入回收站时的子任务联动钩子，默认无操作；
    // AppContainer 组装后注册为 SubTaskUseCases::trashForParent，
    // 保证父子一起删除保留完整关系。现有语义与既有调用不受影响。
    private val onTrashed: suspend (taskId: String) -> Unit = {},
    // T08/#9 纯新增：待办截止编辑/延期成功后的提醒重算钩子，默认无操作；
    // AppContainer 组装后注册为 ReminderUseCases::rescheduleForDueChange，
    // 相对提醒随截止移动、独立时刻提醒不动。不改既有语义。
    private val onDueChanged: suspend (taskId: String) -> Unit = {},
    // T08/#9 纯新增：待办取消成功后的提醒停止钩子，默认无操作
    // （GLOSSARY：取消待办同时停止该待办自身的提醒）。不改既有语义。
    private val onCancelled: suspend (taskId: String) -> Unit = {},
    // T11/#12 纯新增：待办完成/撤销完成成功后的提醒联动钩子，默认无操作。
    // AppContainer 组装后注册为：完成时停止该待办自身全部提醒
    // （issue #12：完成待办停止其自身不再适用的提醒）；撤销完成不动作——
    // 已停止的提醒不自动恢复（不假装恢复，界面如实提示需重新设置）。不改既有语义。
    private val onCompleted: suspend (taskId: String, completed: Boolean) -> Unit = { _, _ -> },
    // T12/#13 纯新增：重复出现完成记录仓库（可空 = 不启用重复能力）。
    // 供列表过滤合成“下一次未完成出现”状态，以及把整体完成转发为单次出现完成。
    private val occurrenceRepository: TaskOccurrenceRepository? = null,
) {

    suspend fun create(input: TaskInput): Result<TodoTask> {
        val error = validate(input)
        if (error != null) return Result.failure(IllegalArgumentException(error))
        val t = now()
        val task = TodoTask(
            id = idGen(),
            title = input.title.trim(),
            note = input.note,
            priority = input.priority,
            category = input.category.trim(),
            dueEpochDay = input.dueEpochDay,
            dueAtMillis = input.dueAtMillis,
            dueZoneId = input.dueZoneId,
            recurrenceRule = input.recurrenceRule,
            status = TaskStatus.ACTIVE,
            createdAt = t,
            updatedAt = t,
        )
        repository.upsertTask(task)
        return Result.success(task)
    }

    suspend fun update(id: String, input: TaskInput): Result<TodoTask> {
        val error = validate(input)
        if (error != null) return Result.failure(IllegalArgumentException(error))
        val current = repository.getTask(id)
            ?: return Result.failure(IllegalStateException("待办不存在或已永久删除"))
        val updated = current.copy(
            title = input.title.trim(),
            note = input.note,
            priority = input.priority,
            category = input.category.trim(),
            dueEpochDay = input.dueEpochDay,
            dueAtMillis = input.dueAtMillis,
            recurrenceRule = input.recurrenceRule,
            updatedAt = now(),
        )
        repository.upsertTask(updated)
        onDueChanged(id)
        return Result.success(updated)
    }

    /**
     * 完成或撤销完成；完成记录时间戳，不改变其他状态。
     * T12/#13 纯新增重复分支：重复待办的完成作用于“下一次未完成出现”
     * （最早一次未完成，可为错过的过去出现），整体状态保持进行中（GLOSSARY：
     * 每一次出现分别记录完成）；撤销完成撤销最近一次已完成的出现。
     */
    suspend fun complete(id: String, completed: Boolean): Result<TodoTask> {
        val current = repository.getTask(id)
            ?: return Result.failure(IllegalStateException("待办不存在或已永久删除"))
        val occurrenceRepo = occurrenceRepository
        if (current.recurrenceRule != null && occurrenceRepo != null) {
            return completeRecurringOccurrence(current, occurrenceRepo, completed)
        }
        val t = now()
        val updated = current.copy(
            status = if (completed) TaskStatus.COMPLETED else TaskStatus.ACTIVE,
            completedAt = if (completed) t else null,
            updatedAt = t,
        )
        repository.upsertTask(updated)
        // T11/#12：完成待办联动停止其自身全部提醒；撤销完成不触发——
        // 已停止的提醒不自动恢复（不假装恢复），状态与时间戳照常保留。
        if (completed) onCompleted(id, completed)
        return Result.success(updated)
    }

    /** 延期：设置新的截止并记录延期时间戳，状态保持未完成。 */
    suspend fun postpone(id: String, newDueEpochDay: Long?, newDueAtMillis: Long?): Result<TodoTask> {
        if (newDueEpochDay != null && newDueAtMillis != null) {
            return Result.failure(IllegalArgumentException("日期截止与时刻截止只能选择其一"))
        }
        val current = repository.getTask(id)
            ?: return Result.failure(IllegalStateException("待办不存在或已永久删除"))
        val t = now()
        val updated = current.copy(
            dueEpochDay = newDueEpochDay,
            dueAtMillis = newDueAtMillis,
            postponedAt = t,
            status = TaskStatus.ACTIVE,
            completedAt = null,
            updatedAt = t,
        )
        repository.upsertTask(updated)
        onDueChanged(id)
        return Result.success(updated)
    }

    /** 取消：决定不再执行并保留业务记录，同时与删除区分；提醒一并停止（#9）。 */
    suspend fun cancel(id: String): Result<TodoTask> {
        val current = repository.getTask(id)
            ?: return Result.failure(IllegalStateException("待办不存在或已永久删除"))
        val t = now()
        val updated = current.copy(
            status = TaskStatus.CANCELLED,
            cancelledAt = t,
            updatedAt = t,
        )
        repository.upsertTask(updated)
        onCancelled(id)
        return Result.success(updated)
    }

    /** 普通删除：标记回收站并从正常查询排除，不永久删除；子任务联动进入回收站（#8）。 */
    suspend fun trash(id: String): Result<Unit> {
        val current = repository.getTask(id)
            ?: return Result.failure(IllegalStateException("待办不存在或已永久删除"))
        repository.trashTask(id, now())
        onTrashed(id)
        return Result.success(Unit)
    }

    suspend fun get(id: String): TodoTask? = repository.getTask(id)

    /**
     * 过滤查询。T12/#13 纯新增重复分支：重复待办依据重复规则与各次完成记录
     * 合成“下一次未完成出现”状态后判定（错过不自动完成、不静默消失）；
     * 非重复待办判定与既有行为完全一致。
     */
    fun observe(filter: TaskFilter): Flow<List<TodoTask>> {
        val occurrenceRepo = occurrenceRepository
            ?: return repository.observeTasks().map { tasks ->
                tasks.filter { TaskDue.matches(it, filter, now()) }
            }
        return combine(repository.observeTasks(), occurrenceRepo.observeAll()) { tasks, occurrences ->
            val completedDaysByTask = occurrences.filter { it.completed }
                .groupBy { it.taskId }
                .mapValues { (_, rows) -> rows.map { it.occurrenceEpochDay }.toSet() }
            tasks.filter { task ->
                val recurring = RecurrenceUseCases.recurringState(
                    task,
                    completedDaysByTask[task.id] ?: emptySet(),
                    now(),
                )
                TaskDue.matches(task, filter, now(), recurring)
            }
        }
    }

    /** 重复待办的完成/撤销转发到出现维度：完成代表次（下一次未完成出现）。 */
    private suspend fun completeRecurringOccurrence(
        task: TodoTask,
        occurrenceRepo: TaskOccurrenceRepository,
        completed: Boolean,
    ): Result<TodoTask> {
        if (task.status == TaskStatus.CANCELLED) {
            return Result.failure(IllegalStateException("待办已取消，不再记录各次完成"))
        }
        val t = now()
        if (completed) {
            val completedDays = occurrenceRepo.listByTask(task.id)
                .filter { it.completed }
                .map { it.occurrenceEpochDay }
                .toSet()
            val next = RecurrenceUseCases.nextUnfinishedDay(
                task,
                completedDays,
                TaskDue.todayEpochDay(task.dueZoneId, t),
            )
            occurrenceRepo.upsert(
                TaskOccurrence(
                    taskId = task.id,
                    occurrenceEpochDay = next,
                    completed = true,
                    completedAt = t,
                    updatedAt = t,
                ),
            )
        } else {
            // 撤销最近一次已完成的出现；没有则原样返回（幂等）
            occurrenceRepo.listByTask(task.id)
                .filter { it.completed }
                .maxByOrNull { it.completedAt ?: 0L }
                ?.let { latest ->
                    occurrenceRepo.upsert(latest.copy(completed = false, completedAt = null, updatedAt = t))
                }
        }
        return Result.success(repository.getTask(task.id) ?: task)
    }

    private fun validate(input: TaskInput): String? = when {
        input.title.isBlank() -> "标题不能为空"
        input.dueEpochDay != null && input.dueAtMillis != null -> "日期截止与时刻截止只能选择其一"
        else -> RecurrenceRule.validate(input.recurrenceRule)
    }
}
