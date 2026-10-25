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

    /** 关联维护专用：某待办关联的全部日程（含回收站记录），供解除关联等维护动作使用。 */
    suspend fun getEntriesLinkedToTask(taskId: String): List<CalendarEntry>
    suspend fun upsertEntry(entry: CalendarEntry)
    suspend fun trashEntry(id: String, trashedAtMillis: Long)
}
