package com.zhideer.app.domain.usecase

import com.zhideer.app.domain.model.TaskDue
import com.zhideer.app.domain.model.TaskFilter
import com.zhideer.app.domain.model.TaskPriority
import com.zhideer.app.domain.model.TaskStatus
import com.zhideer.app.domain.model.TodoTask
import com.zhideer.app.domain.repository.TasksRepository
import kotlinx.coroutines.flow.Flow
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
)

/**
 * 统一待办用例入口：创建、编辑、完成、延期、取消、回收站删除与过滤查询。
 * 完成、延期、取消分别记录时间戳；取消与删除（回收站）是不同的业务动作。
 */
class TaskUseCases(
    private val repository: TasksRepository,
    private val idGen: () -> String,
    private val now: () -> Long,
    private val zoneId: String,
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
            updatedAt = now(),
        )
        repository.upsertTask(updated)
        return Result.success(updated)
    }

    /** 完成或撤销完成；完成记录时间戳，不改变其他状态。 */
    suspend fun complete(id: String, completed: Boolean): Result<TodoTask> {
        val current = repository.getTask(id)
            ?: return Result.failure(IllegalStateException("待办不存在或已永久删除"))
        val t = now()
        val updated = current.copy(
            status = if (completed) TaskStatus.COMPLETED else TaskStatus.ACTIVE,
            completedAt = if (completed) t else null,
            updatedAt = t,
        )
        repository.upsertTask(updated)
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
        return Result.success(updated)
    }

    /** 取消：决定不再执行并保留业务记录，同时与删除区分。 */
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
        return Result.success(updated)
    }

    /** 普通删除：标记回收站并从正常查询排除，不永久删除。 */
    suspend fun trash(id: String): Result<Unit> {
        val current = repository.getTask(id)
            ?: return Result.failure(IllegalStateException("待办不存在或已永久删除"))
        repository.trashTask(id, now())
        return Result.success(Unit)
    }

    suspend fun get(id: String): TodoTask? = repository.getTask(id)

    fun observe(filter: TaskFilter): Flow<List<TodoTask>> =
        repository.observeTasks().map { tasks ->
            tasks.filter { TaskDue.matches(it, filter, now()) }
        }

    private fun validate(input: TaskInput): String? = when {
        input.title.isBlank() -> "标题不能为空"
        input.dueEpochDay != null && input.dueAtMillis != null -> "日期截止与时刻截止只能选择其一"
        else -> null
    }
}
