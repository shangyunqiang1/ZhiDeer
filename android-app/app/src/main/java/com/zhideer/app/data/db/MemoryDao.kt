package com.zhideer.app.data.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    /** 全部记忆（管理视角）：含停用与过期条目，按更新时间倒序。 */
    @Query("SELECT * FROM memories ORDER BY updatedAt DESC, createdAt DESC")
    fun observeAll(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun get(id: String): MemoryEntity?

    @Upsert
    suspend fun upsert(memory: MemoryEntity)

    /** 彻底删除：物理删除该记忆行，无回收站、无保留期。 */
    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteById(id: String)

    // ---------- 来源删除边界联动（T27/#28） ----------

    /**
     * 来源进入回收站联动：立即停用关联记忆并标记来源缺失，返回受影响行数。
     * 仅作用于尚未标记缺失的条目（幂等）：重复调用返回 0，不重复改写。
     */
    @Query(
        "UPDATE memories SET enabled = 0, sourceRefMissing = 1, updatedAt = :updatedAt " +
            "WHERE sourceRefType = :sourceType AND sourceRefId = :sourceId AND sourceRefMissing = 0",
    )
    suspend fun disableAndMarkSourceMissing(sourceType: String, sourceId: String, updatedAt: Long): Int

    /**
     * 来源从回收站恢复联动：清除来源缺失标记，返回受影响行数。
     * 不触碰 enabled（恢复来源不自动启用记忆）；仅作用于已标记条目（幂等）。
     */
    @Query(
        "UPDATE memories SET sourceRefMissing = 0, updatedAt = :updatedAt " +
            "WHERE sourceRefType = :sourceType AND sourceRefId = :sourceId AND sourceRefMissing = 1",
    )
    suspend fun clearSourceMissing(sourceType: String, sourceId: String, updatedAt: Long): Int

    /** 来源彻底删除联动：物理删除关联记忆（清理检索入口），返回删除行数。 */
    @Query(
        "DELETE FROM memories WHERE sourceRefType = :sourceType AND sourceRefId = :sourceId",
    )
    suspend fun deleteBySourceRef(sourceType: String, sourceId: String): Int
}
