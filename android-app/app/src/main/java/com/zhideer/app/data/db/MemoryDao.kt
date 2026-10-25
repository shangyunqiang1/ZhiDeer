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
}
