package com.zhideer.app.domain.usecase

import com.zhideer.app.domain.model.SubTask
import com.zhideer.app.domain.repository.SubTasksRepository
import com.zhideer.app.domain.repository.TasksRepository
import kotlinx.coroutines.flow.Flow

/** 子任务排序方向：在兄弟子任务中与相邻项交换位置。 */
enum class SubTaskMove { UP, DOWN }

/** 子任务完成进度快照：[done] 为已完成数，[total] 为未进入回收站的子任务总数。 */
data class SubTaskProgress(val total: Int, val done: Int)

/**
 * 统一子任务用例入口：新增、编辑、完成/撤销、排序、回收站删除、进度与联动删除恢复。
 * - 子任务与父待办分别确认完成：本用例绝不读写父待办的状态与完成时间戳，
 *   也不因全部子任务完成而自动完成父待办（TaskUseCases 同样不联动子任务）；
 * - 普通删除使用回收站语义：记录不永久删除，父子关系保留；
 * - 父待办进入回收站时由 TaskUseCases 的联动钩子调用 trashForParent，
 *   恢复时调用 restoreForParent，保证父子一起删除与恢复时关系完整。
 */
class SubTaskUseCases(
    private val repository: SubTasksRepository,
    private val tasksRepository: TasksRepository,
    private val idGen: () -> String,
    private val now: () -> Long,
) {

    suspend fun create(parentId: String, title: String): Result<SubTask> {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("子任务标题不能为空"))
        }
        val parent = tasksRepository.getTask(parentId)
            ?: return Result.failure(IllegalStateException("父待办不存在或已进入回收站"))
        if (parent.trashedAt != null) {
            return Result.failure(IllegalStateException("父待办不存在或已进入回收站"))
        }
        val t = now()
        val subTask = SubTask(
            id = idGen(),
            parentId = parentId,
            title = trimmed,
            // 新子任务始终排在已有子任务之后
            sortOrder = repository.maxSortOrder(parentId) + 1,
            createdAt = t,
            updatedAt = t,
        )
        repository.upsert(subTask)
        return Result.success(subTask)
    }

    suspend fun update(id: String, title: String): Result<SubTask> {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("子任务标题不能为空"))
        }
        val current = repository.get(id)
            ?: return Result.failure(IllegalStateException("子任务不存在"))
        val updated = current.copy(title = trimmed, updatedAt = now())
        repository.upsert(updated)
        return Result.success(updated)
    }

    /** 完成/撤销子任务：只改子任务自身的完成状态与时间戳，绝不触碰父待办。 */
    suspend fun setDone(id: String, done: Boolean): Result<SubTask> {
        val current = repository.get(id)
            ?: return Result.failure(IllegalStateException("子任务不存在"))
        val t = now()
        val updated = current.copy(
            done = done,
            doneAt = if (done) t else null,
            updatedAt = t,
        )
        repository.upsert(updated)
        return Result.success(updated)
    }

    /**
     * 上移/下移：与相邻子任务交换排序值。
     * 已在顶部/底部时无操作并成功返回原记录，界面无需额外判断边界。
     */
    suspend fun move(id: String, direction: SubTaskMove): Result<SubTask> {
        val current = repository.get(id)
            ?: return Result.failure(IllegalStateException("子任务不存在"))
        val siblings = repository.listByParent(current.parentId)
        val index = siblings.indexOfFirst { it.id == id }
        val targetIndex = when (direction) {
            SubTaskMove.UP -> index - 1
            SubTaskMove.DOWN -> index + 1
        }
        if (targetIndex !in siblings.indices) return Result.success(current)
        val neighbor = siblings[targetIndex]
        val t = now()
        repository.upsert(current.copy(sortOrder = neighbor.sortOrder, updatedAt = t))
        repository.upsert(neighbor.copy(sortOrder = current.sortOrder, updatedAt = t))
        return Result.success(repository.get(id) ?: current)
    }

    /** 普通删除：子任务进入回收站并从正常查询排除，记录不永久删除，父子关系保留。 */
    suspend fun delete(id: String): Result<Unit> {
        val current = repository.get(id)
            ?: return Result.failure(IllegalStateException("子任务不存在"))
        repository.trash(current.id, now())
        return Result.success(Unit)
    }

    /** 父待办进入回收站时联动：其全部未删除子任务一起进入回收站，保留父子关系。幂等。 */
    suspend fun trashForParent(parentId: String): Result<Unit> {
        repository.trashForParent(parentId, now())
        return Result.success(Unit)
    }

    /** 父待办从回收站恢复时联动：恢复同批删除（trashedAt 相同）的子任务。幂等。 */
    suspend fun restoreForParent(parentId: String, trashedAtMillis: Long): Result<Unit> {
        repository.restoreForParent(parentId, trashedAtMillis, now())
        return Result.success(Unit)
    }

    /** 按 id 读取子任务（含回收站记录，用于打开原始记录与恢复管理）。 */
    suspend fun get(id: String): SubTask? = repository.get(id)

    /** 子任务完成进度 n/m，只统计未进入回收站的子任务。 */
    suspend fun progress(parentId: String): SubTaskProgress {
        val list = repository.listByParent(parentId)
        return SubTaskProgress(total = list.size, done = list.count { it.done })
    }

    /** 未完成子任务标题：供父待办提前完成时向用户展示核对。 */
    suspend fun pendingTitles(parentId: String): List<String> =
        repository.listByParent(parentId).filter { !it.done }.map { it.title }

    /** 观察某父待办的子任务：排除回收站，按 sortOrder 稳定排序。 */
    fun observeByParent(parentId: String): Flow<List<SubTask>> =
        repository.observeByParent(parentId)
}
