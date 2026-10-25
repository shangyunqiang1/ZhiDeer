package com.zhideer.app.domain.repository

import com.zhideer.app.domain.model.Reminder
import kotlinx.coroutines.flow.Flow

/**
 * 提醒持久化入口。提醒属于某个待办；待办取消或删除时由用例统一停止提醒。
 */
interface RemindersRepository {
    fun observeByTask(taskId: String): Flow<List<Reminder>>
    suspend fun listByTask(taskId: String): List<Reminder>
    suspend fun get(id: String): Reminder?
    suspend fun upsert(reminder: Reminder)
    suspend fun delete(id: String)
    suspend fun deleteByTask(taskId: String)
}
