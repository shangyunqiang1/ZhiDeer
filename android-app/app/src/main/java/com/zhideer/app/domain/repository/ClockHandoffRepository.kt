package com.zhideer.app.domain.repository

import com.zhideer.app.domain.model.ClockHandoff
import kotlinx.coroutines.flow.Flow

interface ClockHandoffRepository {
    fun observeAll(): Flow<List<ClockHandoff>>
    suspend fun upsert(handoff: ClockHandoff)
    suspend fun delete(id: String)
}
