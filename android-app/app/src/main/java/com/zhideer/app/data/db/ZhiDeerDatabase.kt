package com.zhideer.app.data.db

import androidx.room3.Database
import androidx.room3.RoomDatabase

/**
 * version 2（T05/#6）：新增便签表 notes 与 note_list_items。
 */
@Database(
    entities = [TaskEntity::class, NoteEntity::class, NoteListItemEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class ZhiDeerDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun noteDao(): NoteDao

    companion object {
        const val NAME = "zhideer.db"
    }
}
