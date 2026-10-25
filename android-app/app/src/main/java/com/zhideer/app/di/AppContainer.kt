package com.zhideer.app.di

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.zhideer.app.data.db.ZhiDeerDatabase
import com.zhideer.app.data.repository.CalendarRepositoryImpl
import com.zhideer.app.data.repository.NotesRepositoryImpl
import com.zhideer.app.data.repository.RemindersRepositoryImpl
import com.zhideer.app.data.repository.SubTasksRepositoryImpl
import com.zhideer.app.data.repository.TaskOccurrenceRepositoryImpl
import com.zhideer.app.data.repository.TasksRepositoryImpl
import com.zhideer.app.domain.repository.CalendarRepository
import com.zhideer.app.domain.repository.NotesRepository
import com.zhideer.app.domain.repository.RemindersRepository
import com.zhideer.app.domain.repository.SubTasksRepository
import com.zhideer.app.domain.repository.TaskOccurrenceRepository
import com.zhideer.app.domain.repository.TasksRepository
import com.zhideer.app.domain.usecase.CalendarUseCases
import com.zhideer.app.domain.usecase.NoteUseCases
import com.zhideer.app.domain.usecase.RecurrenceUseCases
import com.zhideer.app.domain.usecase.ReminderUseCases
import com.zhideer.app.domain.usecase.SubTaskUseCases
import com.zhideer.app.domain.usecase.TaskScheduleLinkUseCases
import com.zhideer.app.domain.usecase.TaskUseCases
import com.zhideer.app.platform.AlarmScheduler
import com.zhideer.app.platform.AndroidNotificationPoster
import com.zhideer.app.platform.NotificationPoster
import com.zhideer.app.platform.PlatformAlarmScheduler
import kotlinx.coroutines.Dispatchers
import java.time.ZoneId
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/**
 * 手动依赖容器：统一业务用例入口在此组装，测试可注入替身时钟与生成器。
 */
class AppContainer(
    context: Context,
    zoneId: ZoneId = ZoneId.systemDefault(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    val zoneId: ZoneId = zoneId

    val database: ZhiDeerDatabase = Room.databaseBuilder<ZhiDeerDatabase>(
        context = context,
        name = context.getDatabasePath(ZhiDeerDatabase.NAME).absolutePath,
    )
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        // T06/#7（日程外键 taskId）与 T07/#8（sub_tasks 子任务表）合并后数据库升至 5，
        // T08/#9（reminders 提醒表）后升至 6。当前应用尚无对外发布的
        // 真实用户数据，暂以破坏性迁移兜底（旧库重建，不保留历史数据）；
        // 接入真实用户数据前必须替换为保留数据的正式迁移。
        .fallbackToDestructiveMigration()
        .build()

    private val ioDispatcher: CoroutineContext = Dispatchers.IO

    val tasksRepository: TasksRepository = TasksRepositoryImpl(database.taskDao(), Dispatchers.IO)

    val subTasksRepository: SubTasksRepository = SubTasksRepositoryImpl(database.subTaskDao(), Dispatchers.IO)

    // 子任务用例先于待办用例组装，供待办删除时的子任务联动钩子引用（#8）。
    val subTaskUseCases: SubTaskUseCases = SubTaskUseCases(
        repository = subTasksRepository,
        tasksRepository = tasksRepository,
        idGen = { UUID.randomUUID().toString() },
        now = nowMillis,
    )

    // T08/#9 提醒：平台适配器与用例先于待办用例组装，供截止变化/取消/删除的联动钩子引用。
    val remindersRepository: RemindersRepository = RemindersRepositoryImpl(database.reminderDao(), Dispatchers.IO)

    val alarmScheduler: AlarmScheduler = PlatformAlarmScheduler(context)

    val notificationPoster: NotificationPoster = AndroidNotificationPoster(context)

    val reminderUseCases: ReminderUseCases = ReminderUseCases(
        repository = remindersRepository,
        tasksRepository = tasksRepository,
        scheduler = alarmScheduler,
        idGen = { UUID.randomUUID().toString() },
        now = nowMillis,
    )

    // T12/#13 纯新增：重复出现完成记录仓库先于待办用例组装，
    // 供列表过滤合成“下一次未完成出现”与完成转发使用。
    val taskOccurrenceRepository: TaskOccurrenceRepository = TaskOccurrenceRepositoryImpl(
        database.taskOccurrenceDao(),
        Dispatchers.IO,
    )

    val recurrenceUseCases: RecurrenceUseCases = RecurrenceUseCases(
        tasksRepository = tasksRepository,
        occurrenceRepository = taskOccurrenceRepository,
        now = nowMillis,
    )

    val taskUseCases: TaskUseCases = TaskUseCases(
        repository = tasksRepository,
        idGen = { UUID.randomUUID().toString() },
        now = nowMillis,
        zoneId = zoneId.id,
        // #8：父待办进入回收站时子任务联动删除，保留父子关系供恢复；
        // #9：待办删除时其提醒一并停止（取消闹钟并清除记录）
        onTrashed = { taskId ->
            subTaskUseCases.trashForParent(taskId)
            reminderUseCases.cancelReminders(taskId)
        },
        // #9：截止编辑/延期后相对提醒随截止移动，独立时刻提醒不动
        onDueChanged = { taskId -> reminderUseCases.rescheduleForDueChange(taskId) },
        // #9：取消待办同时停止该待办自身的提醒（GLOSSARY）
        onCancelled = { taskId -> reminderUseCases.cancelReminders(taskId) },
        // #13：重复待办的列表过滤与完成走出现维度（每一次分别记录完成）
        occurrenceRepository = taskOccurrenceRepository,
    )

    val calendarRepository: CalendarRepository = CalendarRepositoryImpl(database.calendarEntryDao(), Dispatchers.IO)

    val calendarUseCases: CalendarUseCases = CalendarUseCases(
        repository = calendarRepository,
        idGen = { UUID.randomUUID().toString() },
        now = nowMillis,
        zoneId = zoneId.id,
    )

    val notesRepository: NotesRepository = NotesRepositoryImpl(database.noteDao(), Dispatchers.IO)

    val noteUseCases: NoteUseCases = NoteUseCases(
        repository = notesRepository,
        idGen = { UUID.randomUUID().toString() },
        now = nowMillis,
    )

    val taskScheduleLinkUseCases: TaskScheduleLinkUseCases = TaskScheduleLinkUseCases(
        tasksRepository = tasksRepository,
        calendarRepository = calendarRepository,
        now = nowMillis,
    )
}
