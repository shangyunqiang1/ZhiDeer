package com.zhideer.app.data.repository

import com.zhideer.app.data.db.SubTaskDao
import com.zhideer.app.data.db.toEntity
import com.zhideer.app.data.db.toModel
import com.zhideer.app.domain.model.SubTask
import com.zhideer.app.domain.repository.SubTasksRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class SubTasksRepositoryImpl(
    private val subTaskDao: SubTaskDao,
    private val ioDispatcher: CoroutineDispatcher,
) : SubTasksRepository {

    override fun observeByParent(parentId: String): Flow<List<SubTask>> =
        subTaskDao.observeByParent(parentId)
            .map { list -> list.map { it.toModel() } }
            .flowOn(ioDispatcher)

    override suspend fun listByParent(parentId: String): List<SubTask> = withContext(ioDispatcher) {
        subTaskDao.listNotTrashedByParent(parentId).map { it.toModel() }
    }

    override suspend fun get(id: String): SubTask? = withContext(ioDispatcher) {
        subTaskDao.get(id)?.toModel()
    }

    override suspend fun upsert(subTask: SubTask) = withContext(ioDispatcher) {
        subTaskDao.upsert(subTask.toEntity())
    }

    override suspend fun trash(id: String, trashedAtMillis: Long) = withContext(ioDispatcher) {
        subTaskDao.trash(id, trashedAtMillis)
    }

    override suspend fun trashForParent(parentId: String, trashedAtMillis: Long) =
        withContext(ioDispatcher) {
            subTaskDao.trashForParent(parentId, trashedAtMillis)
        }

    override suspend fun restoreForParent(parentId: String, trashedAtMillis: Long, updatedAtMillis: Long) =
        withContext(ioDispatcher) {
            subTaskDao.restoreForParent(parentId, trashedAtMillis, updatedAtMillis)
        }

    override suspend fun maxSortOrder(parentId: String): Int = withContext(ioDispatcher) {
        subTaskDao.maxSortOrder(parentId) ?: -1
    }

    override fun observeTrashedSubTasks(): Flow<List<SubTask>> =
        subTaskDao.observeTrashed()
            .map { list -> list.map { it.toModel() } }
            .flowOn(ioDispatcher)

    override suspend fun listTrashedSubTasks(): List<SubTask> = withContext(ioDispatcher) {
        subTaskDao.listTrashed().map { it.toModel() }
    }

    override suspend fun purge(id: String) = withContext(ioDispatcher) {
        subTaskDao.deleteById(id)
    }

    override suspend fun purgeByParent(parentId: String) = withContext(ioDispatcher) {
        subTaskDao.deleteByParent(parentId)
    }
}
