package com.zhideer.app.data.db

import androidx.room3.Database
import androidx.room3.RoomDatabase

/**
 * version 15：T10/#11 新增 reminder_schedule_log 系统调度台账表（已调度/已投递/错过），
 * 供设备重启、时间/时区变化与权限变化后恢复提醒并合并错过提示。
 * version 14：T24/#25 新增 clock_handoffs 系统闹钟交接记录表（只记录本应用发起的
 * 待核对请求，无法读取系统闹钟内容）。
 * 历史：version 13：合并 T27/#28（memories 纯新增来源引用拆列 sourceRefType/sourceRefId/
 * sourceRefMissing——记忆依据的原始记录引用，来源删除边界联动按来源级联标记与清理）。
 * 历史：version 12：合并 T26/#27（memories 长期记忆表——用户显式保存并确认的偏好或事实，
 * 独立隐私数据表，无回收站列，删除即物理删除）。历史：
 * version 11：合并 T15/#16（occurrence_subtask_records 出现维度子任务步骤记录表，
 * 重复待办每一次出现按父待办子任务模板分别记录步骤勾选，唯一约束
 * (occurrenceId, subTaskId)）。历史：10 合并 T14/#15 单次延期（task_occurrences 主键
 * 从 (taskId, occurrenceEpochDay) 改为稳定 id，新增 originalEpochDay/effectiveEpochDay
 * 列支持同日两次与单次延期）与 T09/#10（calendar_reminders 日程提醒表挂接）；
 * 9 合并 T14/#15（tasks 增加 recurrenceExceptions 例外日列）与 T09/#10
 * （calendar_reminders 表）；5 合并 T06/#7（calendar_entries 外键 taskId）与
 * T07/#8（sub_tasks 子任务表）；6 合并 T08/#9（reminders 提醒表）；
 * 7 合并 T12/#13（task_occurrences 重复出现完成记录表，tasks 增加重复规则拆列；
 * T13/#14 MONTHLY 复用拆列存每月第几日）；
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
        OccurrenceSubTaskRecordEntity::class,
        MemoryEntity::class,
        ClockHandoffEntity::class,
        ReminderScheduleEntity::class,
    ],
    version = 15,
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
    abstract fun occurrenceSubTaskRecordDao(): OccurrenceSubTaskRecordDao
    abstract fun memoryDao(): MemoryDao
    abstract fun clockHandoffDao(): ClockHandoffDao

    /** T10/#11：系统调度台账（已调度/已投递/错过），供设备变化恢复使用。 */
    abstract fun reminderScheduleLogDao(): ReminderScheduleLogDao

    companion object {
        const val NAME = "zhideer.db"
    }
}
