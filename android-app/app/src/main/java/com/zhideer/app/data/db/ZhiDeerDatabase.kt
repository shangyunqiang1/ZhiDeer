package com.zhideer.app.data.db

import androidx.room3.Database
import androidx.room3.RoomDatabase

/**
 * version 9：合并 T14/#15（tasks 增加 recurrenceExceptions 例外日列）与
 * T09/#10（新增 calendar_reminders 日程提醒表，外键级联 calendar_entries；
 * 与 reminders 待办提醒表分表，各自保留外键约束，归属由 Reminder.ownerType 区分）。
 * 历史：5 合并 T06/#7（calendar_entries 外键 taskId）与 T07/#8（sub_tasks 子任务表）；
 * 6 合并 T08/#9（reminders 提醒表）；7 合并 T12/#13（task_occurrences 重复出现完成记录表，
 * tasks 增加重复规则拆列；T13/#14 MONTHLY 复用拆列存每月第几日）；
 * 1→2→3 合并 T04/#5（calendar_entries 日程表）与 T05/#6（notes、note_list_items 便签表）。
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
        CalendarReminderEntity::class,
    ],
    version = 9,
    exportSchema = true,
)
abstract class ZhiDeerDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun subTaskDao(): SubTaskDao
    abstract fun calendarEntryDao(): CalendarEntryDao
    abstract fun noteDao(): NoteDao
    abstract fun reminderDao(): ReminderDao
    abstract fun taskOccurrenceDao(): TaskOccurrenceDao
    abstract fun calendarReminderDao(): CalendarReminderDao

    companion object {
        const val NAME = "zhideer.db"
    }
}
