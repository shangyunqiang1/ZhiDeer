package com.zhideer.app.data.repository

import com.zhideer.app.data.db.TaskDao
import com.zhideer.app.data.db.toEntity
import com.zhideer.app.data.db.toModel
import com.zhideer.app.domain.model.TodoTask
import com.zhideer.app.domain.repository.TasksRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class TasksRepositoryImpl(
    private val taskDao: TaskDao,
    private val ioDispatcher: CoroutineDispatcher,
) : TasksRepository {

    override fun observeTasks(): Flow<List<TodoTask>> =
        taskDao.observeNotTrashed()
            .map { list -> list.map { it.toModel() } }
            .flowOn(ioDispatcher)

    override suspend fun getTask(id: String): TodoTask? = withContext(ioDispatcher) {
        taskDao.get(id)?.toModel()
    }

    override suspend fun upsertTask(task: TodoTask) = withContext(ioDispatcher) {
        taskDao.upsert(task.toEntity())
    }

    override suspend fun trashTask(id: String, trashedAtMillis: Long) = withContext(ioDispatcher) {
        taskDao.trash(id, trashedAtMillis)
    }

    override fun observeTrashedTasks(): Flow<List<TodoTask>> =
        taskDao.observeTrashed()
            .map { list -> list.map { it.toModel() } }
            .flowOn(ioDispatcher)

    override suspend fun listTrashedTasks(): List<TodoTask> = withContext(ioDispatcher) {
        taskDao.listTrashed().map { it.toModel() }
    }

    override suspend fun purgeTask(id: String) = withContext(ioDispatcher) {
        taskDao.deleteById(id)
    }
}
