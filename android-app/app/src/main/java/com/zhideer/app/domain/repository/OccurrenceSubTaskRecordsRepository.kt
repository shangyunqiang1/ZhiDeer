package com.zhideer.app.domain.repository

import com.zhideer.app.domain.model.OccurrenceSubTaskRecord
import kotlinx.coroutines.flow.Flow

/**
 * 出现维度子任务步骤记录的持久化入口（T15/#16）。
 * 每行按稳定 id 主键写入；唯一约束 (occurrenceId, subTaskId) 保证
 * 同一次出现的同一步骤至多一行，幂等由用例复用既有行实现。
 */
interface OccurrenceSubTaskRecordsRepository {
    /** 观察某次出现的全部步骤记录。 */
    fun observeByOccurrence(occurrenceId: String): Flow<List<OccurrenceSubTaskRecord>>

    /** 观察全部步骤记录，供按出现锚定合成。 */
    fun observeAll(): Flow<List<OccurrenceSubTaskRecord>>

    /** 某次出现的全部步骤记录（一次性读取）。 */
    suspend fun listByOccurrence(occurrenceId: String): List<OccurrenceSubTaskRecord>

    /** 某待办的全部步骤记录，供核对与诊断。 */
    suspend fun listByTask(taskId: String): List<OccurrenceSubTaskRecord>

    suspend fun upsert(record: OccurrenceSubTaskRecord)
}
