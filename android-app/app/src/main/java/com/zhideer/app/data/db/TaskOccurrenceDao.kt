package com.zhideer.app.data.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskOccurrenceDao {

    /** 观察全部出现完成记录，供列表过滤合成“下一次未完成出现”状态。 */
    @Query("SELECT * FROM task_occurrences")
    fun observeAll(): Flow<List<TaskOccurrenceEntity>>

    /** 某待办的全部出现完成记录，供代表次计算与各次记录核对。 */
    @Query("SELECT * FROM task_occurrences WHERE taskId = :taskId")
    suspend fun listByTask(taskId: String): List<TaskOccurrenceEntity>

    @Upsert
    suspend fun upsert(occurrence: TaskOccurrenceEntity)

    /** 彻底删除待办联动（T16/#17）：物理删除其全部出现完成记录（回收站期间保留历史不动）。 */
    @Query("DELETE FROM task_occurrences WHERE taskId = :taskId")
    suspend fun deleteByTask(taskId: String)
}
