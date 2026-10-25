package com.zhideer.app.data.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

/** 日程提醒 DAO（T09/#10），结构与 ReminderDao 对应，按 entryId 归属。 */
@Dao
interface CalendarReminderDao {

    /** 按日程观察提醒：以创建序为主、id 兜底，保证展示顺序稳定。 */
    @Query("SELECT * FROM calendar_reminders WHERE entryId = :entryId ORDER BY createdAt ASC, id ASC")
    fun observeByEntry(entryId: String): Flow<List<CalendarReminderEntity>>

    /** 同一稳定排序的一次性读取，供去重、重算与停止提醒使用。 */
    @Query("SELECT * FROM calendar_reminders WHERE entryId = :entryId ORDER BY createdAt ASC, id ASC")
    suspend fun listByEntry(entryId: String): List<CalendarReminderEntity>

    @Query("SELECT * FROM calendar_reminders WHERE id = :id")
    suspend fun get(id: String): CalendarReminderEntity?

    /** 设备变化恢复（T10/#11）：一次性读取全部日程提醒，按真实本地记录重建调度。 */
    @Query("SELECT * FROM calendar_reminders ORDER BY createdAt ASC, id ASC")
    suspend fun listAll(): List<CalendarReminderEntity>

    @Upsert
    suspend fun upsert(reminder: CalendarReminderEntity)

    @Query("DELETE FROM calendar_reminders WHERE id = :id")
    suspend fun delete(id: String)

    /** 日程删除/取消联动：清除该日程全部提醒。 */
    @Query("DELETE FROM calendar_reminders WHERE entryId = :entryId")
    suspend fun deleteByEntry(entryId: String)
}
