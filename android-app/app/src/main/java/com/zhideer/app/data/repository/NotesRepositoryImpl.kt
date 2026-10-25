package com.zhideer.app.data.repository

import com.zhideer.app.data.db.NoteDao
import com.zhideer.app.data.db.toEntity
import com.zhideer.app.data.db.toModel
import com.zhideer.app.domain.model.Note
import com.zhideer.app.domain.repository.NotesRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class NotesRepositoryImpl(
    private val noteDao: NoteDao,
    private val ioDispatcher: CoroutineDispatcher,
) : NotesRepository {

    override fun observeNotes(): Flow<List<Note>> =
        noteDao.observeNotTrashed()
            .map { list -> list.map { it.toModel() } }
            .flowOn(ioDispatcher)

    override suspend fun getNote(id: String): Note? = withContext(ioDispatcher) {
        noteDao.get(id)?.toModel()
    }

    override suspend fun upsertNote(note: Note) = withContext(ioDispatcher) {
        noteDao.replaceNote(
            note = note.toEntity(),
            items = note.items.mapIndexed { index, item -> item.toEntity(note.id, index) },
        )
    }

    override suspend fun trashNote(id: String, trashedAtMillis: Long) = withContext(ioDispatcher) {
        noteDao.trash(id, trashedAtMillis)
    }
}
