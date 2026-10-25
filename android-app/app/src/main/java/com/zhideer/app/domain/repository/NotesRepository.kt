package com.zhideer.app.domain.repository

import com.zhideer.app.domain.model.Note
import kotlinx.coroutines.flow.Flow

/**
 * 便签持久化入口。普通查询与搜索永远排除回收站记录；
 * 回收站记录仍可通过 [getNote] 按 id 读取，恢复与彻底删除由回收站用例（T16/#17）交付。
 */
interface NotesRepository {
    fun observeNotes(): Flow<List<Note>>
    suspend fun getNote(id: String): Note?
    suspend fun upsertNote(note: Note)
    suspend fun trashNote(id: String, trashedAtMillis: Long)

    /** 回收站观察（T16/#17 纯新增）：仅回收站便签。 */
    fun observeTrashedNotes(): Flow<List<Note>>

    /** 回收站一次性读取（T16/#17 纯新增）：供到期清理批量判定。 */
    suspend fun listTrashedNotes(): List<Note>

    /** 彻底删除（T16/#17 纯新增）：物理删除该便签及其全部清单项。 */
    suspend fun purgeNote(id: String)
}
