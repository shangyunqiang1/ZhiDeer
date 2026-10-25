package com.zhideer.app.domain.repository

import com.zhideer.app.domain.model.Reminder
import kotlinx.coroutines.flow.Flow

/**
 * 日程提醒持久化入口（T09/#10）。提醒属于某个日程；
 * 日程删除（回收站）时由用例统一停止提醒，不影响关联待办自身的提醒。
 * 与 RemindersRepository（待办提醒）分接口，持久化分表、领域模型同为 Reminder。
 */
interface CalendarRemindersRepository {
    fun observeByEntry(entryId: String): Flow<List<Reminder>>
    suspend fun listByEntry(entryId: String): List<Reminder>

    /** 设备变化恢复（T10/#11）：全部日程提醒，按真实本地记录重建调度。 */
    suspend fun listAll(): List<Reminder>
    suspend fun get(id: String): Reminder?
    suspend fun upsert(reminder: Reminder)
    suspend fun delete(id: String)
    suspend fun deleteByEntry(entryId: String)
}
