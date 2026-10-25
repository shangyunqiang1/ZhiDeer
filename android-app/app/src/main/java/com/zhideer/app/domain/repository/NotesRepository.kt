package com.zhideer.app.domain.repository

import com.zhideer.app.domain.model.Note
import kotlinx.coroutines.flow.Flow

/**
 * 便签持久化入口。普通查询与搜索永远排除回收站记录；
 * 回收站记录仍可通过 [getNote] 按 id 读取，恢复与彻底删除界面由后续任务交付。
 */
interface NotesRepository {
    fun observeNotes(): Flow<List<Note>>
    suspend fun getNote(id: String): Note?
    suspend fun upsertNote(note: Note)
    suspend fun trashNote(id: String, trashedAtMillis: Long)
}
