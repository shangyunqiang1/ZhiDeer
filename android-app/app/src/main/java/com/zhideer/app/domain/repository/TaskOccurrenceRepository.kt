package com.zhideer.app.domain.repository

import com.zhideer.app.domain.model.TaskOccurrence
import kotlinx.coroutines.flow.Flow

/**
 * 重复待办出现记录的持久化入口（T12/#13；T14/#15 主键改造）。
 * 每行按稳定 id 主键幂等写入；同一 (taskId, originalEpochDay) 至多一行。
 */
interface TaskOccurrenceRepository {
    fun observeAll(): Flow<List<TaskOccurrence>>
    suspend fun listByTask(taskId: String): List<TaskOccurrence>
    suspend fun upsert(occurrence: TaskOccurrence)
}
