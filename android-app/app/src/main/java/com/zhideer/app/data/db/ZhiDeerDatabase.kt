package com.zhideer.app.data.db

import androidx.room3.Database
import androidx.room3.RoomDatabase

/**
 * version 4：T06/#7（calendar_entries 新增可空外键 taskId，待办-日程通过稳定标识关联）。
 * 此前 version 3：合并 T04/#5（calendar_entries 日程表）与 T05/#6（notes、note_list_items 便签表）。
 * 当前应用尚无真实用户数据，由 AppContainer 的破坏性迁移兜底，接入真实用户数据前必须替换为保留数据的正式迁移。
 */
@Database(
    entities = [TaskEntity::class, CalendarEntryEntity::class, NoteEntity::class, NoteListItemEntity::class],
    version = 4,
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
