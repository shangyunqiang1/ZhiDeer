package com.zhideer.app.data.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {

    /** 按待办观察提醒：以创建序为主、id 兜底，保证展示顺序稳定。 */
    @Query("SELECT * FROM reminders WHERE taskId = :taskId ORDER BY createdAt ASC, id ASC")
    fun observeByTask(taskId: String): Flow<List<ReminderEntity>>

    /** 同一稳定排序的一次性读取，供去重、重算与停止提醒使用。 */
    @Query("SELECT * FROM reminders WHERE taskId = :taskId ORDER BY createdAt ASC, id ASC")
    suspend fun listByTask(taskId: String): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun get(id: String): ReminderEntity?

    /** 设备变化恢复（T10/#11）：一次性读取全部待办提醒，按真实本地记录重建调度。 */
    @Query("SELECT * FROM reminders ORDER BY createdAt ASC, id ASC")
    suspend fun listAll(): List<ReminderEntity>

    @Upsert
    suspend fun upsert(reminder: ReminderEntity)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: String)

    /** 待办取消/删除联动：清除该待办全部提醒。 */
    @Query("DELETE FROM reminders WHERE taskId = :taskId")
    suspend fun deleteByTask(taskId: String)
}
