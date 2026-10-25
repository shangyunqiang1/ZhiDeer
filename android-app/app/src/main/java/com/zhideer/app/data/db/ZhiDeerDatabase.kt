package com.zhideer.app.data.db

import androidx.room3.Database
import androidx.room3.RoomDatabase

/**
 * version 10：合并 T14/#15 单次延期（task_occurrences 主键从 (taskId, occurrenceEpochDay)
 * 改为稳定 id，新增 originalEpochDay/effectiveEpochDay 列支持同日两次与单次延期）与
 * T09/#10（calendar_reminders 日程提醒表挂接）。历史：9 合并 T14/#15（tasks 增加
 * recurrenceExceptions 例外日列）与 T09/#10（calendar_reminders 表）；
 * 5 合并 T06/#7（calendar_entries 外键 taskId）与 T07/#8（sub_tasks 子任务表）；
 * 6 合并 T08/#9（reminders 提醒表）；7 合并 T12/#13（task_occurrences 重复出现完成记录表，
 * tasks 增加重复规则拆列；T13/#14 MONTHLY 复用拆列存每月第几日）；
 * 1→2→3 合并 T04/#5（calendar_entries 日程表）与 T05/#6（notes、note_list_items 便签表）。
 * 当前应用尚无真实用户数据，由 AppContainer 的破坏性迁移兜底（旧 task_occurrences
 * 记录随之重建，出现记录本就按当前规则与锚点即时派生，无保留价值）；
 * 接入真实用户数据前必须替换为保留数据的正式迁移。
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
    version = 10,
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
