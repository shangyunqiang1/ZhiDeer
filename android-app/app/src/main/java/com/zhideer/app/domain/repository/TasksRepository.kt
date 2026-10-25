package com.zhideer.app.domain.repository

import com.zhideer.app.domain.model.TodoTask
import kotlinx.coroutines.flow.Flow

/**
 * 待办持久化入口。普通查询永远排除回收站记录；
 * 回收站记录仍可通过 getTask 读取，恢复界面由后续任务交付。
 */
interface TasksRepository {
    fun observeTasks(): Flow<List<TodoTask>>
    suspend fun getTask(id: String): TodoTask?
    suspend fun upsertTask(task: TodoTask)
    suspend fun trashTask(id: String, trashedAtMillis: Long)
}
