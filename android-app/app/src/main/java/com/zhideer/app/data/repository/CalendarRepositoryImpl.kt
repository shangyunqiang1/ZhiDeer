package com.zhideer.app.data.repository

import com.zhideer.app.data.db.CalendarEntryDao
import com.zhideer.app.data.db.toEntity
import com.zhideer.app.data.db.toModel
import com.zhideer.app.domain.model.CalendarEntry
import com.zhideer.app.domain.repository.CalendarRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class CalendarRepositoryImpl(
    private val calendarEntryDao: CalendarEntryDao,
    private val ioDispatcher: CoroutineDispatcher,
) : CalendarRepository {

    override fun observeEntries(): Flow<List<CalendarEntry>> =
        calendarEntryDao.observeNotTrashed()
            .map { list -> list.map { it.toModel() } }
            .flowOn(ioDispatcher)

    override suspend fun getEntry(id: String): CalendarEntry? = withContext(ioDispatcher) {
        calendarEntryDao.get(id)?.toModel()
    }

    override suspend fun upsertEntry(entry: CalendarEntry) = withContext(ioDispatcher) {
        calendarEntryDao.upsert(entry.toEntity())
    }

    override suspend fun trashEntry(id: String, trashedAtMillis: Long) = withContext(ioDispatcher) {
        calendarEntryDao.trash(id, trashedAtMillis)
    }
}
