package com.zhideer.app.data.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CalendarEntryDao {

    /** 正常查询永远排除回收站记录。 */
    @Query("SELECT * FROM calendar_entries WHERE trashedAt IS NULL")
    fun observeNotTrashed(): Flow<List<CalendarEntryEntity>>

    /** 含回收站记录，仅用于恢复管理与诊断，不属于正常查询。 */
    @Query("SELECT * FROM calendar_entries WHERE id = :id")
    suspend fun get(id: String): CalendarEntryEntity?

    /** 关联维护专用：按 taskId 读取全部日程（含回收站记录），供解除关联等维护动作使用。 */
    @Query("SELECT * FROM calendar_entries WHERE taskId = :taskId")
    suspend fun getByTaskId(taskId: String): List<CalendarEntryEntity>

    /** 回收站观察（T16/#17）：仅回收站记录，供回收站界面与到期清理使用。 */
    @Query("SELECT * FROM calendar_entries WHERE trashedAt IS NOT NULL")
    fun observeTrashed(): Flow<List<CalendarEntryEntity>>

    /** 回收站一次性读取（T16/#17）：供到期清理批量判定。 */
    @Query("SELECT * FROM calendar_entries WHERE trashedAt IS NOT NULL")
    suspend fun listTrashed(): List<CalendarEntryEntity>

    @Upsert
    suspend fun upsert(entry: CalendarEntryEntity)

    @Query("UPDATE calendar_entries SET trashedAt = :trashedAt, updatedAt = :trashedAt WHERE id = :id")
    suspend fun trash(id: String, trashedAt: Long)

    /** 彻底删除（T16/#17）：物理删除该日程行；日程提醒由外键级联与用例显式清理兜底。 */
    @Query("DELETE FROM calendar_entries WHERE id = :id")
    suspend fun deleteById(id: String)
}
