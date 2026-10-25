package com.zhideer.app.data.repository

import com.zhideer.app.data.db.CalendarReminderDao
import com.zhideer.app.data.db.toCalendarEntity
import com.zhideer.app.data.db.toModel
import com.zhideer.app.domain.model.Reminder
import com.zhideer.app.domain.repository.CalendarRemindersRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class CalendarRemindersRepositoryImpl(
    private val calendarReminderDao: CalendarReminderDao,
    private val ioDispatcher: CoroutineDispatcher,
) : CalendarRemindersRepository {

    override fun observeByEntry(entryId: String): Flow<List<Reminder>> =
        calendarReminderDao.observeByEntry(entryId)
            .map { list -> list.map { it.toModel() } }
            .flowOn(ioDispatcher)

    override suspend fun listByEntry(entryId: String): List<Reminder> = withContext(ioDispatcher) {
        calendarReminderDao.listByEntry(entryId).map { it.toModel() }
    }

    override suspend fun get(id: String): Reminder? = withContext(ioDispatcher) {
        calendarReminderDao.get(id)?.toModel()
    }

    override suspend fun upsert(reminder: Reminder) = withContext(ioDispatcher) {
        calendarReminderDao.upsert(reminder.toCalendarEntity())
    }

    override suspend fun delete(id: String) = withContext(ioDispatcher) {
        calendarReminderDao.delete(id)
    }

    override suspend fun deleteByEntry(entryId: String) = withContext(ioDispatcher) {
        calendarReminderDao.deleteByEntry(entryId)
    }
}
