package com.zhideer.app.data.db

import androidx.room3.Database
import androidx.room3.RoomDatabase

/**
 * version 3：合并 T04/#5（calendar_entries 日程表）与 T05/#6（notes、note_list_items 便签表）。
 * 两条分支各自把库升到过 2，集成为统一的 3；当前应用尚无真实用户数据，
 * 由 AppContainer 的破坏性迁移兜底，接入真实用户数据前必须替换为保留数据的正式迁移。
 */
@Database(
    entities = [TaskEntity::class, CalendarEntryEntity::class, NoteEntity::class, NoteListItemEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class ZhiDeerDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun calendarEntryDao(): CalendarEntryDao
    abstract fun noteDao(): NoteDao

    companion object {
        const val NAME = "zhideer.db"
    }
}
