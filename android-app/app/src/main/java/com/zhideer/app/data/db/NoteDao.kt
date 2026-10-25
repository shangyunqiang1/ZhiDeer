package com.zhideer.app.data.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    /** 正常查询永远排除回收站记录。 */
    @Transaction
    @Query("SELECT * FROM notes WHERE trashedAt IS NULL")
    fun observeNotTrashed(): Flow<List<NoteWithItems>>

    /** 含回收站记录，仅用于按 id 打开原始记录、恢复管理与诊断，不属于正常查询与搜索。 */
    @Transaction
    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: String): NoteWithItems?

    @Upsert
    suspend fun upsertNote(note: NoteEntity)

    @Query("DELETE FROM note_list_items WHERE noteId = :noteId")
    suspend fun deleteItemsForNote(noteId: String)

    @Upsert
    suspend fun upsertItems(items: List<NoteListItemEntity>)

    /** 整体替换便签与其清单项：先写便签，再删旧清单项后写入，保证增删一致。 */
    @Transaction
    suspend fun replaceNote(note: NoteEntity, items: List<NoteListItemEntity>) {
        upsertNote(note)
        deleteItemsForNote(note.id)
        upsertItems(items)
    }

    @Query("UPDATE notes SET trashedAt = :trashedAt, updatedAt = :trashedAt WHERE id = :id")
    suspend fun trash(id: String, trashedAt: Long)
}
