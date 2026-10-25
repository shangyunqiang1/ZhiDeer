package com.zhideer.app.data.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    /** 正常查询永远排除回收站记录。 */
    @Query("SELECT * FROM tasks WHERE trashedAt IS NULL")
    fun observeNotTrashed(): Flow<List<TaskEntity>>

    /** 含回收站记录，仅用于恢复管理与诊断，不属于正常查询。 */
    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: String): TaskEntity?

    @Upsert
    suspend fun upsert(task: TaskEntity)

    @Query("UPDATE tasks SET trashedAt = :trashedAt, updatedAt = :trashedAt WHERE id = :id")
    suspend fun trash(id: String, trashedAt: Long)
}
