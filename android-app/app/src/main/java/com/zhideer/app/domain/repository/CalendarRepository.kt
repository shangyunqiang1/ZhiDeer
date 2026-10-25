package com.zhideer.app.domain.repository

import com.zhideer.app.domain.model.CalendarEntry
import kotlinx.coroutines.flow.Flow

/**
 * 日程持久化入口。普通查询永远排除回收站记录；
 * 回收站记录仍可通过 getEntry 读取，恢复界面由后续任务交付。
 */
interface CalendarRepository {
    fun observeEntries(): Flow<List<CalendarEntry>>
    suspend fun getEntry(id: String): CalendarEntry?
    suspend fun upsertEntry(entry: CalendarEntry)
    suspend fun trashEntry(id: String, trashedAtMillis: Long)
}
