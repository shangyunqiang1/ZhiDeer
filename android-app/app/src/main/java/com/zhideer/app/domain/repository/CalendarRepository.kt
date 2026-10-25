package com.zhideer.app.domain.repository

import com.zhideer.app.domain.model.CalendarEntry
import kotlinx.coroutines.flow.Flow

/**
 * 日程持久化入口。普通查询永远排除回收站记录；
 * 回收站记录仍可通过 getEntry 读取，恢复与彻底删除由回收站用例（T16/#17）交付。
 */
interface CalendarRepository {
    fun observeEntries(): Flow<List<CalendarEntry>>
    suspend fun getEntry(id: String): CalendarEntry?

    /** 关联维护专用：某待办关联的全部日程（含回收站记录），供解除关联等维护动作使用。 */
    suspend fun getEntriesLinkedToTask(taskId: String): List<CalendarEntry>
    suspend fun upsertEntry(entry: CalendarEntry)
    suspend fun trashEntry(id: String, trashedAtMillis: Long)

    /** 回收站观察（T16/#17 纯新增）：仅回收站记录。 */
    fun observeTrashedEntries(): Flow<List<CalendarEntry>>

    /** 回收站一次性读取（T16/#17 纯新增）：供到期清理批量判定。 */
    suspend fun listTrashedEntries(): List<CalendarEntry>

    /** 彻底删除（T16/#17 纯新增）：物理删除该日程行；日程提醒由回收站用例先停止再删。 */
    suspend fun purgeEntry(id: String)
}
