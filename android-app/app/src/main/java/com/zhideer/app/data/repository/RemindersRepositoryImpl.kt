package com.zhideer.app.data.repository

import com.zhideer.app.data.db.ReminderDao
import com.zhideer.app.data.db.toEntity
import com.zhideer.app.data.db.toModel
import com.zhideer.app.domain.model.Reminder
import com.zhideer.app.domain.repository.RemindersRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class RemindersRepositoryImpl(
    private val reminderDao: ReminderDao,
    private val ioDispatcher: CoroutineDispatcher,
) : RemindersRepository {

    override fun observeByTask(taskId: String): Flow<List<Reminder>> =
        reminderDao.observeByTask(taskId)
            .map { list -> list.map { it.toModel() } }
            .flowOn(ioDispatcher)

    override suspend fun listByTask(taskId: String): List<Reminder> = withContext(ioDispatcher) {
        reminderDao.listByTask(taskId).map { it.toModel() }
    }

    override suspend fun listAll(): List<Reminder> = withContext(ioDispatcher) {
        reminderDao.listAll().map { it.toModel() }
    }

    override suspend fun get(id: String): Reminder? = withContext(ioDispatcher) {
        reminderDao.get(id)?.toModel()
    }

    override suspend fun upsert(reminder: Reminder) = withContext(ioDispatcher) {
        reminderDao.upsert(reminder.toEntity())
    }

    override suspend fun delete(id: String) = withContext(ioDispatcher) {
        reminderDao.delete(id)
    }

    override suspend fun deleteByTask(taskId: String) = withContext(ioDispatcher) {
        reminderDao.deleteByTask(taskId)
    }
}
