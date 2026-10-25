package com.zhideer.app.data.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SubTaskDao {

    /** 正常查询永远排除回收站记录；排序以 sortOrder 为主、创建序兜底，保证稳定。 */
    @Query(
        "SELECT * FROM sub_tasks WHERE parentId = :parentId AND trashedAt IS NULL " +
            "ORDER BY sortOrder ASC, createdAt ASC, id ASC",
    )
    fun observeByParent(parentId: String): Flow<List<SubTaskEntity>>

    /** 同一稳定排序的一次性读取，供排序交换与进度统计使用。 */
    @Query(
        "SELECT * FROM sub_tasks WHERE parentId = :parentId AND trashedAt IS NULL " +
            "ORDER BY sortOrder ASC, createdAt ASC, id ASC",
    )
    suspend fun listNotTrashedByParent(parentId: String): List<SubTaskEntity>

    /** 含回收站记录，仅用于恢复管理与诊断，不属于正常查询。 */
    @Query("SELECT * FROM sub_tasks WHERE id = :id")
    suspend fun get(id: String): SubTaskEntity?

    /** 回收站观察（T16/#17）：仅回收站子任务，供回收站界面与到期清理使用。 */
    @Query("SELECT * FROM sub_tasks WHERE trashedAt IS NOT NULL ORDER BY trashedAt DESC, id ASC")
    fun observeTrashed(): Flow<List<SubTaskEntity>>

    /** 回收站一次性读取（T16/#17）：供到期清理批量判定。 */
    @Query("SELECT * FROM sub_tasks WHERE trashedAt IS NOT NULL")
    suspend fun listTrashed(): List<SubTaskEntity>

    @Upsert
    suspend fun upsert(subTask: SubTaskEntity)

    @Query("UPDATE sub_tasks SET trashedAt = :trashedAt, updatedAt = :trashedAt WHERE id = :id")
    suspend fun trash(id: String, trashedAt: Long)

    /** 父待办删除联动：其全部未删除子任务一起进入回收站，保留父子关系。 */
    @Query(
        "UPDATE sub_tasks SET trashedAt = :trashedAt, updatedAt = :trashedAt " +
            "WHERE parentId = :parentId AND trashedAt IS NULL",
    )
    suspend fun trashForParent(parentId: String, trashedAt: Long)

    /** 父待办恢复联动：恢复同批删除（trashedAt 相同）的子任务。 */
    @Query(
        "UPDATE sub_tasks SET trashedAt = NULL, updatedAt = :updatedAt " +
            "WHERE parentId = :parentId AND trashedAt = :trashedAt",
    )
    suspend fun restoreForParent(parentId: String, trashedAt: Long, updatedAt: Long)

    /** 该父待办下未删除子任务的最大排序值；无记录时返回 null。 */
    @Query("SELECT MAX(sortOrder) FROM sub_tasks WHERE parentId = :parentId AND trashedAt IS NULL")
    suspend fun maxSortOrder(parentId: String): Int?

    /** 彻底删除单个子任务（T16/#17）：物理删除该行。 */
    @Query("DELETE FROM sub_tasks WHERE id = :id")
    suspend fun deleteById(id: String)

    /** 彻底删除待办联动（T16/#17）：物理删除其全部子任务（含回收站记录）。 */
    @Query("DELETE FROM sub_tasks WHERE parentId = :parentId")
    suspend fun deleteByParent(parentId: String)
}
