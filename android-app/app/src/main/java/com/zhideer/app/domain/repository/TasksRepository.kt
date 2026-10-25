package com.zhideer.app.domain.repository

import com.zhideer.app.domain.model.TodoTask
import kotlinx.coroutines.flow.Flow

/**
 * 待办持久化入口。普通查询永远排除回收站记录；
 * 回收站记录仍可通过 getTask 读取，恢复与彻底删除由回收站用例（T16/#17）交付。
 */
interface TasksRepository {
    fun observeTasks(): Flow<List<TodoTask>>
    suspend fun getTask(id: String): TodoTask?
    suspend fun upsertTask(task: TodoTask)
    suspend fun trashTask(id: String, trashedAtMillis: Long)

    /** 回收站观察（T16/#17 纯新增）：仅回收站记录。 */
    fun observeTrashedTasks(): Flow<List<TodoTask>>

    /** 回收站一次性读取（T16/#17 纯新增）：供到期清理批量判定。 */
    suspend fun listTrashedTasks(): List<TodoTask>

    /** 彻底删除（T16/#17 纯新增）：物理删除该待办行；从属数据由回收站用例级联清理。 */
    suspend fun purgeTask(id: String)
}
