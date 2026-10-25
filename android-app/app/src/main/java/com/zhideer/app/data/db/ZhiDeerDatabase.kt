package com.zhideer.app.data.db

import androidx.room3.Database
import androidx.room3.RoomDatabase

/**
 * version 7：T12/#13 新增 task_occurrences 重复出现完成记录表
 * （主键 taskId+occurrenceEpochDay，每一次出现分别记录完成），tasks 增加重复规则拆列。
 * 历史：1→2→3 合并 T04/#5（calendar_entries 日程表）与 T05/#6（notes、note_list_items 便签表）；
 * 5 合并 T06/#7（calendar_entries 外键 taskId）与 T07/#8（sub_tasks 子任务表）；
 * 6 合并 T08/#9（reminders 提醒表）。
 * 当前应用尚无真实用户数据，由 AppContainer 的破坏性迁移兜底，接入真实用户数据前必须替换为保留数据的正式迁移。
 */
@Database(
    entities = [
        TaskEntity::class,
        SubTaskEntity::class,
        CalendarEntryEntity::class,
        NoteEntity::class,
        NoteListItemEntity::class,
        ReminderEntity::class,
        TaskOccurrenceEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class ZhiDeerDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun subTaskDao(): SubTaskDao
    abstract fun calendarEntryDao(): CalendarEntryDao
    abstract fun noteDao(): NoteDao
    abstract fun reminderDao(): ReminderDao
    abstract fun taskOccurrenceDao(): TaskOccurrenceDao

    companion object {
        const val NAME = "zhideer.db"
    }
}
