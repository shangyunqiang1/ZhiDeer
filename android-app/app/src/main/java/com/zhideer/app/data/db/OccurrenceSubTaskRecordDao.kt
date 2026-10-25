package com.zhideer.app.data.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface OccurrenceSubTaskRecordDao {

    /** 观察某次出现的全部步骤记录，供步骤面板合成清单与进度。 */
    @Query("SELECT * FROM occurrence_subtask_records WHERE occurrenceId = :occurrenceId ORDER BY id ASC")
    fun observeByOccurrence(occurrenceId: String): Flow<List<OccurrenceSubTaskRecordEntity>>

    /** 观察全部步骤记录，供按出现锚定合成（出现行变化时同步刷新）。 */
    @Query("SELECT * FROM occurrence_subtask_records")
    fun observeAll(): Flow<List<OccurrenceSubTaskRecordEntity>>

    /** 某次出现的全部步骤记录（一次性读取，供幂等写入与进度统计）。 */
    @Query("SELECT * FROM occurrence_subtask_records WHERE occurrenceId = :occurrenceId ORDER BY id ASC")
    suspend fun listByOccurrence(occurrenceId: String): List<OccurrenceSubTaskRecordEntity>

    /** 某待办的全部步骤记录，供核对与诊断。 */
    @Query("SELECT * FROM occurrence_subtask_records WHERE taskId = :taskId ORDER BY id ASC")
    suspend fun listByTask(taskId: String): List<OccurrenceSubTaskRecordEntity>

    /** 按稳定 id 主键写入；同一次出现的同一步骤由用例保证复用同一行。 */
    @Upsert
    suspend fun upsert(record: OccurrenceSubTaskRecordEntity)
}
