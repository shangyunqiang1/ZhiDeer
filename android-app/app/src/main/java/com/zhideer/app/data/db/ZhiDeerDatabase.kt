package com.zhideer.app.data.db

import androidx.room3.Database
import androidx.room3.RoomDatabase

/**
 * version 2（T04/#5）：新增 calendar_entries 日程表。
 */
@Database(entities = [TaskEntity::class, CalendarEntryEntity::class], version = 2, exportSchema = true)
abstract class ZhiDeerDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun calendarEntryDao(): CalendarEntryDao

    companion object {
        const val NAME = "zhideer.db"
    }
}
