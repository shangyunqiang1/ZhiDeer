package com.zhideer.app.data.repository

import com.zhideer.app.data.db.OccurrenceSubTaskRecordDao
import com.zhideer.app.data.db.toEntity
import com.zhideer.app.data.db.toModel
import com.zhideer.app.domain.model.OccurrenceSubTaskRecord
import com.zhideer.app.domain.repository.OccurrenceSubTaskRecordsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class OccurrenceSubTaskRecordsRepositoryImpl(
    private val dao: OccurrenceSubTaskRecordDao,
    private val ioDispatcher: CoroutineDispatcher,
) : OccurrenceSubTaskRecordsRepository {

    override fun observeByOccurrence(occurrenceId: String): Flow<List<OccurrenceSubTaskRecord>> =
        dao.observeByOccurrence(occurrenceId)
            .map { list -> list.map { it.toModel() } }
            .flowOn(ioDispatcher)

    override fun observeAll(): Flow<List<OccurrenceSubTaskRecord>> =
        dao.observeAll()
            .map { list -> list.map { it.toModel() } }
            .flowOn(ioDispatcher)

    override suspend fun listByOccurrence(occurrenceId: String): List<OccurrenceSubTaskRecord> =
        withContext(ioDispatcher) { dao.listByOccurrence(occurrenceId).map { it.toModel() } }

    override suspend fun listByTask(taskId: String): List<OccurrenceSubTaskRecord> =
        withContext(ioDispatcher) { dao.listByTask(taskId).map { it.toModel() } }

    override suspend fun upsert(record: OccurrenceSubTaskRecord) = withContext(ioDispatcher) {
        dao.upsert(record.toEntity())
    }
}
