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

    @Upsert
    suspend fun upsert(entry: CalendarEntryEntity)

    @Query("UPDATE calendar_entries SET trashedAt = :trashedAt, updatedAt = :trashedAt WHERE id = :id")
    suspend fun trash(id: String, trashedAt: Long)
}
