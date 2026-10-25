package com.zhideer.app.domain.repository

import com.zhideer.app.domain.model.TaskOccurrence
import kotlinx.coroutines.flow.Flow

/**
 * 重复待办出现完成记录的持久化入口（T12/#13）。
 * 写入按 (taskId, occurrenceEpochDay) 主键幂等。
 */
interface TaskOccurrenceRepository {
    fun observeAll(): Flow<List<TaskOccurrence>>
    suspend fun listByTask(taskId: String): List<TaskOccurrence>
    suspend fun upsert(occurrence: TaskOccurrence)
}
