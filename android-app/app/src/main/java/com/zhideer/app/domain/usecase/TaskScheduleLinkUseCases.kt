package com.zhideer.app.domain.usecase

import com.zhideer.app.domain.model.CalendarEntry
import com.zhideer.app.domain.model.TodoTask
import com.zhideer.app.domain.repository.CalendarRepository
import com.zhideer.app.domain.repository.TasksRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 待办-日程关联用例（T06/#7，GLOSSARY：日程最多关联一个待办，一个待办可关联多个日程；
 * 关联通过稳定标识保存，双方完成状态分别处理、互不自动联动）。
 *
 * 独立于 TaskUseCases 与 CalendarUseCases：本类只维护关联关系本身，不重复实现
 * 待办或日程的创建/编辑/删除；删除待办（回收站）时由调用方配合 [unlinkTask] 解除关联但保留日程。
 */
class TaskScheduleLinkUseCases(
    private val tasksRepository: TasksRepository,
    private val calendarRepository: CalendarRepository,
    private val now: () -> Long,
) {

    /**
     * 建立关联：把日程挂到待办上（日程侧保存待办稳定 id）。
     * 幂等：重复关联同一对返回成功；日程已关联其他待办时拒绝，须先解除。
     */
    suspend fun link(taskId: String, entryId: String): Result<Unit> {
        val task = tasksRepository.getTask(taskId)
            ?: return Result.failure(IllegalStateException("待办不存在或已永久删除"))
        if (task.trashedAt != null) {
            return Result.failure(IllegalStateException("待办已删除（回收站中），不能建立关联"))
        }
        val entry = calendarRepository.getEntry(entryId)
            ?: return Result.failure(IllegalStateException("日程不存在或已永久删除"))
        if (entry.trashedAt != null) {
            return Result.failure(IllegalStateException("日程已删除（回收站中），不能建立关联"))
        }
        if (entry.taskId != null && entry.taskId != taskId) {
            return Result.failure(IllegalStateException("该日程已关联其他待办，请先解除原有关联"))
        }
        if (entry.taskId == taskId) return Result.success(Unit)
        calendarRepository.upsertEntry(entry.copy(taskId = taskId, updatedAt = now()))
        return Result.success(Unit)
    }

    /** 解除单个日程的关联。幂等：未关联时返回成功。 */
    suspend fun unlink(entryId: String): Result<Unit> {
        val entry = calendarRepository.getEntry(entryId)
            ?: return Result.failure(IllegalStateException("日程不存在或已永久删除"))
        if (entry.taskId == null) return Result.success(Unit)
        calendarRepository.upsertEntry(entry.copy(taskId = null, updatedAt = now()))
        return Result.success(Unit)
    }

    /**
     * 解除某待办的全部关联（含回收站中的日程记录），日程记录一律保留。
     * 供普通删除（回收站）待办时调用：删除待办保留日程并解除关联。
     * 返回解除关联的日程数。
     */
    suspend fun unlinkTask(taskId: String): Result<Int> {
        val linked = calendarRepository.getEntriesLinkedToTask(taskId)
        val t = now()
        linked.forEach { entry ->
            calendarRepository.upsertEntry(entry.copy(taskId = null, updatedAt = t))
        }
        return Result.success(linked.size)
    }

    /** 某待办的全部关联日程（含时间，按开始时间排序）；正常查询排除回收站记录。 */
    fun observeEntriesForTask(taskId: String): Flow<List<CalendarEntry>> =
        calendarRepository.observeEntries().map { entries ->
            entries.filter { it.taskId == taskId }.sortedBy { it.startMillis }
        }.distinctUntilChanged()

    suspend fun getEntriesForTask(taskId: String): List<CalendarEntry> =
        calendarRepository.getEntriesLinkedToTask(taskId)
            .filter { it.trashedAt == null }
            .sortedBy { it.startMillis }

    /** 某日程的关联待办（标题等字段取实时数据，改标题不破坏关联）；待办在回收站中时不返回。 */
    fun observeTaskForEntry(entryId: String): Flow<TodoTask?> =
        combine(calendarRepository.observeEntries(), tasksRepository.observeTasks()) { entries, tasks ->
            val taskId = entries.firstOrNull { it.id == entryId }?.taskId ?: return@combine null
            tasks.firstOrNull { it.id == taskId }
        }.distinctUntilChanged()

    suspend fun getTaskForEntry(entryId: String): TodoTask? {
        val taskId = calendarRepository.getEntry(entryId)?.taskId ?: return null
        val task = tasksRepository.getTask(taskId) ?: return null
        return if (task.trashedAt == null) task else null
    }

    /**
     * 某待办「尚未开始」的关联日程（开始时间晚于当前时刻，按开始时间排序），
     * 用于完成待办时向用户展示未来日程并让其选择是否一并取消；正常查询排除回收站记录。
     */
    suspend fun futureEntriesForTask(taskId: String): List<CalendarEntry> {
        val t = now()
        return calendarRepository.getEntriesLinkedToTask(taskId)
            .filter { it.trashedAt == null && it.startMillis > t }
            .sortedBy { it.startMillis }
    }

    /**
     * 「一并取消」完成待办时展示的未来关联日程：把它们移入回收站（与日程普通删除同语义），
     * 已结束与进行中的日程不受影响。返回取消的日程数。
     */
    suspend fun cancelFutureEntries(taskId: String): Result<Int> {
        val t = now()
        val futures = calendarRepository.getEntriesLinkedToTask(taskId)
            .filter { it.trashedAt == null && it.startMillis > t }
            .sortedBy { it.startMillis }
        futures.forEach { entry ->
            calendarRepository.trashEntry(entry.id, t)
        }
        return Result.success(futures.size)
    }
}
