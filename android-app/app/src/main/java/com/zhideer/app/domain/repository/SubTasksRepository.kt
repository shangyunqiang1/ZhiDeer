package com.zhideer.app.domain.repository

import com.zhideer.app.domain.model.SubTask
import kotlinx.coroutines.flow.Flow

/**
 * 子任务持久化入口。普通查询永远排除回收站记录；
 * 回收站记录仍可通过 get 读取，恢复由 SubTaskUseCases 的联动用例与回收站任务交付。
 */
interface SubTasksRepository {
    /** 观察某父待办下的子任务（排除回收站），按 sortOrder 稳定排序。 */
    fun observeByParent(parentId: String): Flow<List<SubTask>>

    /** 一次性读取某父待办下的子任务（排除回收站），按同一稳定排序。 */
    suspend fun listByParent(parentId: String): List<SubTask>

    /** 按 id 读取子任务（含回收站记录）。 */
    suspend fun get(id: String): SubTask?

    suspend fun upsert(subTask: SubTask)

    /** 单个子任务进入回收站。 */
    suspend fun trash(id: String, trashedAtMillis: Long)

    /** 父待办删除联动：其全部未删除子任务一起进入回收站。 */
    suspend fun trashForParent(parentId: String, trashedAtMillis: Long)

    /** 父待办恢复联动：恢复同批删除（trashedAt 相同）的子任务。 */
    suspend fun restoreForParent(parentId: String, trashedAtMillis: Long, updatedAtMillis: Long)

    /** 该父待办下未删除子任务的最大排序值，无记录时返回 -1。 */
    suspend fun maxSortOrder(parentId: String): Int
}
