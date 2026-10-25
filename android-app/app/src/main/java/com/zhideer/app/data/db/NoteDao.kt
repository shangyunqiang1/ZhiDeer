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

    /** 回收站观察（T16/#17）：仅回收站便签（含清单项），供回收站界面与到期清理使用。 */
    @Transaction
    @Query("SELECT * FROM notes WHERE trashedAt IS NOT NULL")
    fun observeTrashed(): Flow<List<NoteWithItems>>

    /** 回收站一次性读取（T16/#17）：供到期清理批量判定。 */
    @Transaction
    @Query("SELECT * FROM notes WHERE trashedAt IS NOT NULL")
    suspend fun listTrashed(): List<NoteWithItems>

    @Upsert
    suspend fun upsertNote(note: NoteEntity)

    @Query("DELETE FROM note_list_items WHERE noteId = :noteId")
    suspend fun deleteItemsForNote(noteId: String)

    /** 清单项计数（T16/#17）：供彻底删除的级联结果核对。 */
    @Query("SELECT COUNT(*) FROM note_list_items WHERE noteId = :noteId")
    suspend fun countItemsForNote(noteId: String): Int

    /** 彻底删除（T16/#17）：物理删除该便签行；清单项先显式清理再删便签，不依赖外键级联。 */
    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteById(id: String)

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
