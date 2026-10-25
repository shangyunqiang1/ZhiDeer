package com.zhideer.app.data.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ClockHandoffDao {

    @Query("SELECT * FROM clock_handoffs ORDER BY requestedAtMillis DESC")
    fun observeAll(): Flow<List<ClockHandoffEntity>>

    @Upsert
    suspend fun upsert(entity: ClockHandoffEntity)

    @Query("DELETE FROM clock_handoffs WHERE id = :id")
    suspend fun deleteById(id: String)
}
