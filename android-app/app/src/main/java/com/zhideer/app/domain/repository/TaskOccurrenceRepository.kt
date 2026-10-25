package com.zhideer.app.domain.repository

import com.zhideer.app.domain.model.TaskOccurrence
import kotlinx.coroutines.flow.Flow

/**
 * 重复待办出现记录的持久化入口（T12/#13；T14/#15 主键改造）。
 * 每行按稳定 id 主键幂等写入；同一 (taskId, originalEpochDay) 至多一行。
 * 回收站期间保留全部历史出现记录；仅彻底删除待办时一并清理（T16/#17）。
 */
interface TaskOccurrenceRepository {
    fun observeAll(): Flow<List<TaskOccurrence>>
    suspend fun listByTask(taskId: String): List<TaskOccurrence>
    suspend fun upsert(occurrence: TaskOccurrence)

    /** 彻底删除待办联动（T16/#17 纯新增）：物理删除其全部出现完成记录。 */
    suspend fun purgeByTask(taskId: String)
}
