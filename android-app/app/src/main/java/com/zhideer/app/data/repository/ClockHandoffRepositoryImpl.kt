package com.zhideer.app.data.repository

import com.zhideer.app.data.db.ClockHandoffDao
import com.zhideer.app.data.db.toEntity
import com.zhideer.app.data.db.toModel
import com.zhideer.app.domain.model.ClockHandoff
import com.zhideer.app.domain.repository.ClockHandoffRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class ClockHandoffRepositoryImpl(
    private val dao: ClockHandoffDao,
    private val ioDispatcher: CoroutineDispatcher,
) : ClockHandoffRepository {

    override fun observeAll(): Flow<List<ClockHandoff>> =
        dao.observeAll().map { list -> list.map { it.toModel() } }.flowOn(ioDispatcher)

    override suspend fun upsert(handoff: ClockHandoff) = withContext(ioDispatcher) {
        dao.upsert(handoff.toEntity())
    }

    override suspend fun delete(id: String) = withContext(ioDispatcher) {
        dao.deleteById(id)
    }
}
