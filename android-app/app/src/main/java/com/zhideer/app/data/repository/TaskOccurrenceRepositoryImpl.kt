package com.zhideer.app.data.repository

import com.zhideer.app.data.db.TaskOccurrenceDao
import com.zhideer.app.data.db.toEntity
import com.zhideer.app.data.db.toModel
import com.zhideer.app.domain.model.TaskOccurrence
import com.zhideer.app.domain.repository.TaskOccurrenceRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class TaskOccurrenceRepositoryImpl(
    private val dao: TaskOccurrenceDao,
    private val ioDispatcher: CoroutineDispatcher,
) : TaskOccurrenceRepository {

    override fun observeAll(): Flow<List<TaskOccurrence>> =
        dao.observeAll()
            .map { list -> list.map { it.toModel() } }
            .flowOn(ioDispatcher)

    override suspend fun listByTask(taskId: String): List<TaskOccurrence> = withContext(ioDispatcher) {
        dao.listByTask(taskId).map { it.toModel() }
    }

    /** 按稳定 id 主键幂等写入（同一 (taskId, originalEpochDay) 至多一行）。 */
    override suspend fun upsert(occurrence: TaskOccurrence) = withContext(ioDispatcher) {
        dao.upsert(occurrence.toEntity())
    }
}
