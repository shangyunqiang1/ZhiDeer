package com.zhideer.app

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.zhideer.app.data.db.ZhiDeerDatabase
import com.zhideer.app.data.repository.CalendarRemindersRepositoryImpl
import com.zhideer.app.data.repository.CalendarRepositoryImpl
import com.zhideer.app.data.repository.ReminderScheduleLogRepositoryImpl
import com.zhideer.app.data.repository.RemindersRepositoryImpl
import com.zhideer.app.data.repository.TasksRepositoryImpl
import com.zhideer.app.domain.model.ReminderOwner
import com.zhideer.app.domain.model.ReminderRestoreTrigger
import com.zhideer.app.domain.model.ReminderEnvironmentStatus
import com.zhideer.app.domain.model.TaskStatus
import com.zhideer.app.domain.repository.CalendarRemindersRepository
import com.zhideer.app.domain.repository.CalendarRepository
import com.zhideer.app.domain.repository.ReminderScheduleLogRepository
import com.zhideer.app.domain.repository.RemindersRepository
import com.zhideer.app.domain.repository.TasksRepository
import com.zhideer.app.domain.usecase.CalendarInput
import com.zhideer.app.domain.usecase.CalendarReminderUseCases
import com.zhideer.app.domain.usecase.CalendarUseCases
import com.zhideer.app.domain.usecase.ReminderInput
import com.zhideer.app.domain.usecase.ReminderRestoreUseCases
import com.zhideer.app.domain.usecase.ReminderUseCases
import com.zhideer.app.domain.usecase.TaskInput
import com.zhideer.app.domain.usecase.TaskUseCases
import com.zhideer.app.platform.AlarmScheduler
import com.zhideer.app.platform.ReminderCapabilityProbe
import com.zhideer.app.platform.ScheduleLedgerAlarmScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/**
 * T10/#11 验收测试：设备变化后的提醒恢复。
 *
 * - 重启（进程与系统闹钟全部丢失、Room 记录保留）后依据真实本地记录重建未来提醒；
 *   重复恢复不创建重复闹钟/重复记录（同调度键覆盖）；
 * - 设备关闭/休眠期间错过的提醒合并为可查看提示：不逐条补响、不自动延期、不自动完成；
 *   时间倒拨后也不会补响（错过即终态，拒绝“补响”）；
 * - 设备时区变化保留安排创建时区并提示核对，确认后才按新时区重新解释；
 * - 权限撤销/通知关闭/省电限制如实进入状态报告并提供修复依据（未就绪、消息）；
 * - 已调度 ≠ 已投递：报告明确 ColorOS 投递窗口，不承诺所有环境准点。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReminderRestoreUseCasesTest {

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")
    private lateinit var dbFile: File
    private lateinit var db: ZhiDeerDatabase
    private lateinit var tasksRepository: TasksRepository
    private lateinit var calendarRepository: CalendarRepository
    private lateinit var remindersRepository: RemindersRepository
    private lateinit var calendarRemindersRepository: CalendarRemindersRepository
    private lateinit var scheduleLogRepository: ReminderScheduleLogRepository
    private lateinit var scheduler: RestoreFakeAlarmScheduler
    private lateinit var ledgerScheduler: AlarmScheduler
    private lateinit var taskUseCases: TaskUseCases
    private lateinit var reminderUseCases: ReminderUseCases
    private lateinit var calendarUseCases: CalendarUseCases
    private lateinit var calendarReminderUseCases: CalendarReminderUseCases
    private lateinit var restoreUseCases: ReminderRestoreUseCases
    private lateinit var probe: FakeReminderCapabilityProbe
    private val clock = mutableListOf(0L)
    private val deviceZone = mutableListOf(shanghai)
    private val idSeq = AtomicInteger(0)

    private fun millis(date: String, time: String = "10:00"): Long =
        LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(shanghai).toInstant().toEpochMilli()

    private fun at(date: String, time: String, zone: ZoneId): Long =
        LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dbFile = context.getDatabasePath("t10_restore_test.db")
        dbFile.parentFile?.mkdirs()
        context.deleteDatabase(dbFile.name)
        open()
    }

    /** 与 AppContainer 相同装配；close/open 即模拟一次“进程丢失 + 系统闹钟丢失”，本地记录保留。 */
    private fun open() {
        db = Room.databaseBuilder<ZhiDeerDatabase>(
            context = ApplicationProvider.getApplicationContext<Context>(),
            name = dbFile.absolutePath,
        )
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        tasksRepository = TasksRepositoryImpl(db.taskDao(), Dispatchers.IO)
        calendarRepository = CalendarRepositoryImpl(db.calendarEntryDao(), Dispatchers.IO)
        remindersRepository = RemindersRepositoryImpl(db.reminderDao(), Dispatchers.IO)
        calendarRemindersRepository = CalendarRemindersRepositoryImpl(db.calendarReminderDao(), Dispatchers.IO)
        scheduleLogRepository = ReminderScheduleLogRepositoryImpl(db.reminderScheduleLogDao(), Dispatchers.IO)
        scheduler = RestoreFakeAlarmScheduler()
        // 生产装配：所有调度都经本装饰器写“已调度”台账，供恢复时区分已投递/错过
        ledgerScheduler = ScheduleLedgerAlarmScheduler(
            delegate = scheduler,
            scheduleLogRepository = scheduleLogRepository,
            now = { clock.last() },
        )
        probe = FakeReminderCapabilityProbe()
        reminderUseCases = ReminderUseCases(
            repository = remindersRepository,
            tasksRepository = tasksRepository,
            scheduler = ledgerScheduler,
            idGen = { "rem-${idSeq.incrementAndGet()}" },
            now = { clock.last() },
        )
        taskUseCases = TaskUseCases(
            repository = tasksRepository,
            idGen = { "task-${idSeq.incrementAndGet()}" },
            now = { clock.last() },
            zoneId = shanghai.id,
            onTrashed = { taskId -> reminderUseCases.cancelReminders(taskId) },
            onDueChanged = { taskId -> reminderUseCases.rescheduleForDueChange(taskId) },
            onCancelled = { taskId -> reminderUseCases.cancelReminders(taskId) },
            onCompleted = { taskId, completed -> if (completed) reminderUseCases.cancelReminders(taskId) },
        )
        calendarReminderUseCases = CalendarReminderUseCases(
            repository = calendarRemindersRepository,
            calendarRepository = calendarRepository,
            scheduler = ledgerScheduler,
            idGen = { "calrem-${idSeq.incrementAndGet()}" },
            now = { clock.last() },
        )
        calendarUseCases = CalendarUseCases(
            repository = calendarRepository,
            idGen = { "entry-${idSeq.incrementAndGet()}" },
            now = { clock.last() },
            zoneId = shanghai.id,
            onTimesChanged = { entryId -> calendarReminderUseCases.rescheduleForStartChange(entryId) },
            onTrashed = { entryId -> calendarReminderUseCases.cancelReminders(entryId) },
        )
        restoreUseCases = ReminderRestoreUseCases(
            tasksRepository = tasksRepository,
            calendarRepository = calendarRepository,
            remindersRepository = remindersRepository,
            calendarRemindersRepository = calendarRemindersRepository,
            scheduleLogRepository = scheduleLogRepository,
            reminderUseCases = reminderUseCases,
            calendarReminderUseCases = calendarReminderUseCases,
            taskUseCases = taskUseCases,
            calendarUseCases = calendarUseCases,
            capabilityProbe = probe,
            now = { clock.last() },
            deviceZone = { deviceZone.last() },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun createTask(
        title: String = "装修新房",
        dueAtMillis: Long? = null,
        dueEpochDay: Long? = null,
    ) = taskUseCases.create(
        TaskInput(title = title, dueAtMillis = dueAtMillis, dueEpochDay = dueEpochDay, dueZoneId = shanghai.id),
    ).getOrThrow()

    private suspend fun createEntry(
        title: String = "周会",
        startDate: String = "2026-10-26",
        startTime: String = "14:00",
        endTime: String = "15:00",
    ) = calendarUseCases.create(
        CalendarInput(
            title = title,
            startMillis = millis(startDate, startTime),
            endMillis = millis(startDate, endTime),
            timezoneId = shanghai.id,
        ),
    ).getOrThrow()

    private fun relative(minutes: Long) = ReminderInput(relativeOffsetMillis = minutes * 60_000L)

    @Test
    fun 重启后依据真实本地记录重建未来提醒且重复恢复不产生重复调度() = runBlocking {
        clock[0] = millis("2026-10-25", "09:00")
        val task = createTask(dueAtMillis = millis("2026-10-26", "10:00"))
        val taskReminder = reminderUseCases.setReminders(task.id, listOf(relative(30))).getOrThrow().single()
        val entry = createEntry()
        val entryReminder = calendarReminderUseCases.setReminders(entry.id, listOf(relative(60))).getOrThrow().single()
        assertEquals(2, scheduler.alarms.size)
        assertEquals(millis("2026-10-26", "09:30"), scheduler.alarms[taskReminder.id])
        assertEquals(millis("2026-10-26", "13:00"), scheduler.alarms["cal:${entryReminder.id}"])

        // 重启：进程内存与系统闹钟全部丢失，本地 Room 记录仍在
        db.close()
        open()
        assertTrue(scheduler.alarms.isEmpty())

        val report = restoreUseCases.restore(ReminderRestoreTrigger.DEVICE_BOOT)
        assertEquals(2, report.futureScheduledCount)
        assertEquals(2, scheduler.alarms.size)
        assertEquals(millis("2026-10-26", "09:30"), scheduler.alarms[taskReminder.id])
        assertEquals(millis("2026-10-26", "13:00"), scheduler.alarms["cal:${entryReminder.id}"])
        assertEquals(2, scheduleLogRepository.listPending().size)
        assertTrue(report.messages.any { it.contains("已调度") && it.contains("已投递") })
        assertTrue(report.messages.any { it.contains("不会创建重复闹钟") })

        // 重复恢复：同调度键覆盖系统闹钟，不产生第二条记录/第二个闹钟
        val second = restoreUseCases.restore(ReminderRestoreTrigger.DEVICE_BOOT)
        assertEquals(2, second.futureScheduledCount)
        assertEquals(2, scheduler.alarms.size)
        assertEquals(2, scheduleLogRepository.listPending().size)
        assertEquals(1, remindersRepository.listByTask(task.id).size)
        assertEquals(1, calendarRemindersRepository.listByEntry(entry.id).size)
    }

    @Test
    fun 错过提醒合并提示且不补响不自动延期或完成() = runBlocking {
        clock[0] = millis("2026-10-25", "09:00")
        val due = millis("2026-10-25", "10:00")
        val task = createTask(dueAtMillis = due)
        reminderUseCases.setReminders(task.id, listOf(relative(30))).getOrThrow()
        val secondTask = createTask(title = "交水电费", dueAtMillis = millis("2026-10-25", "11:00"))
        reminderUseCases.setReminders(secondTask.id, listOf(relative(15))).getOrThrow()
        assertEquals(2, scheduler.alarms.size)

        // 设备关闭/休眠跨过两个提醒时刻后重启（系统闹钟丢失、记录保留）
        db.close()
        open()
        clock[0] = millis("2026-10-25", "12:00")

        val report = restoreUseCases.restore(ReminderRestoreTrigger.DEVICE_BOOT)

        // 合并为可查看提示：两条一起报告，不逐条补响（系统无任何新闹钟）
        assertEquals(2, report.newlyMissedCount)
        assertEquals(2, report.missed.size)
        assertEquals(listOf("装修新房", "交水电费").sorted(), report.missed.map { it.ownerTitle }.sorted())
        assertTrue(scheduler.alarms.isEmpty())
        val prompt = report.missedPromptText()!!
        assertTrue(prompt.contains("2"))
        assertTrue(prompt.contains("不会逐条补响"))

        // 不自动延期/完成：业务记录保持设备状态变化前的真实值
        val after = tasksRepository.getTask(task.id)!!
        assertEquals(TaskStatus.ACTIVE, after.status)
        assertEquals(due, after.dueAtMillis)
        assertNull(after.completedAt)
        assertNull(after.postponedAt)
        assertEquals(due, after.dueAtMillis)
        // 提醒记录保留，过期仅在展示层标记，不删除
        assertEquals(1, remindersRepository.listByTask(task.id).size)

        // 重复恢复不重复记录
        val again = restoreUseCases.restore(ReminderRestoreTrigger.TIME_CHANGED)
        assertEquals(0, again.newlyMissedCount)
        assertEquals(2, again.missed.size)

        // 时间倒拨：错过的提醒仍不补响（终态抑制），提示仍可查看
        clock[0] = millis("2026-10-25", "09:00")
        restoreUseCases.restore(ReminderRestoreTrigger.TIME_CHANGED)
        assertTrue(scheduler.alarms.isEmpty())
        assertEquals(2, scheduleLogRepository.listOutstandingMissed().size)

        // 用户确认知悉后提示清空，但仍不补响
        assertEquals(2, restoreUseCases.acknowledgeMissed())
        assertTrue(restoreUseCases.observeOutstandingMissed().first().isEmpty())
        clock[0] = millis("2026-10-25", "09:00")
        restoreUseCases.restore(ReminderRestoreTrigger.TIME_CHANGED)
        assertTrue(scheduler.alarms.isEmpty())
    }

    @Test
    fun 设备时区变化保留创建时区且提示核对确认后才调整() = runBlocking {
        clock[0] = millis("2026-10-25", "09:00")
        val task = createTask(dueAtMillis = millis("2026-10-26", "10:00"))
        val taskReminder = reminderUseCases.setReminders(task.id, listOf(relative(30))).getOrThrow().single()
        val entry = createEntry(startDate = "2026-10-26", startTime = "14:00", endTime = "15:00")
        val entryReminder = calendarReminderUseCases.setReminders(entry.id, listOf(relative(60))).getOrThrow().single()

        // 设备时区从 Asia/Shanghai 切到 Asia/Tokyo，创建时区保持不变
        deviceZone[0] = ZoneId.of("Asia/Tokyo")
        val report = restoreUseCases.restore(ReminderRestoreTrigger.TIMEZONE_CHANGED)
        assertEquals(2, report.zoneAnchors.size)
        val taskAnchor = report.zoneAnchors.first { it.ownerType == ReminderOwner.TASK }
        assertEquals(task.id, taskAnchor.ownerId)
        assertEquals("Asia/Shanghai", taskAnchor.anchoredZoneId)
        assertEquals("Asia/Tokyo", taskAnchor.deviceZoneId)
        assertEquals(millis("2026-10-26", "09:30"), taskAnchor.nextReminderAtMillis)
        val entryAnchor = report.zoneAnchors.first { it.ownerType == ReminderOwner.CALENDAR }
        assertEquals("Asia/Shanghai", entryAnchor.anchoredZoneId)
        assertTrue(report.messages.any { it.contains("时区") && it.contains("不会静默修改") })

        // 未确认前不静默修改任何记录
        assertEquals("Asia/Shanghai", tasksRepository.getTask(task.id)!!.dueZoneId)
        assertEquals(millis("2026-10-26", "10:00"), tasksRepository.getTask(task.id)!!.dueAtMillis)
        assertEquals("Asia/Shanghai", calendarRepository.getEntry(entry.id)!!.timezoneId)
        assertEquals(millis("2026-10-26", "14:00"), calendarRepository.getEntry(entry.id)!!.startMillis)

        // 确认调整（待办）：保留墙上时间 10:00，按新时区重新解释，相对提醒随截止移动
        restoreUseCases.confirmZoneAdjustment(ReminderOwner.TASK, task.id).getOrThrow()
        val adjustedTask = tasksRepository.getTask(task.id)!!
        assertEquals("Asia/Tokyo", adjustedTask.dueZoneId)
        assertEquals(at("2026-10-26", "10:00", ZoneId.of("Asia/Tokyo")), adjustedTask.dueAtMillis)
        assertEquals(adjustedTask.dueAtMillis!! - 30 * 60_000L, scheduler.alarms[taskReminder.id])
        // 待办确认后不再出现在待核对列表
        val afterTaskAdjust = restoreUseCases.restore(ReminderRestoreTrigger.TIMEZONE_CHANGED)
        assertTrue(afterTaskAdjust.zoneAnchors.none { it.ownerType == ReminderOwner.TASK })

        // 确认调整（日程）：保留墙上时间 14:00，相对提醒随开始时刻移动
        restoreUseCases.confirmZoneAdjustment(ReminderOwner.CALENDAR, entry.id).getOrThrow()
        val adjustedEntry = calendarRepository.getEntry(entry.id)!!
        assertEquals("Asia/Tokyo", adjustedEntry.timezoneId)
        assertEquals(at("2026-10-26", "14:00", ZoneId.of("Asia/Tokyo")), adjustedEntry.startMillis)
        assertEquals(adjustedEntry.startMillis - 60 * 60_000L, scheduler.alarms["cal:${entryReminder.id}"])
    }

    @Test
    fun 权限撤销通知关闭与省电限制如实报告并在修复后恢复调度() = runBlocking {
        clock[0] = millis("2026-10-25", "09:00")
        val task = createTask(dueAtMillis = millis("2026-10-26", "10:00"))
        val reminder = reminderUseCases.setReminders(task.id, listOf(relative(30))).getOrThrow().single()
        val resolved = millis("2026-10-26", "09:30")

        // 精确提醒权限被撤销（设备能力探针与调度器能力同步关闭，模拟同一系统状态）
        probe.exactAlarmsAllowed = false
        db.close()
        open()
        probe.exactAlarmsAllowed = false
        scheduler.exactAllowed = false
        var report = restoreUseCases.restore(ReminderRestoreTrigger.EXACT_ALARM_PERMISSION_CHANGED)
        assertFalse(report.environment.exactAlarmsAllowed)
        assertTrue(report.environment.notificationsAllowed)
        assertEquals(1, report.notReadyCount)
        assertEquals(
            ReminderUseCases.NOT_READY_EXACT_ALARM,
            remindersRepository.listByTask(task.id).single().notReadyReason,
        )
        assertTrue(scheduler.alarms.isEmpty())
        assertTrue(report.messages.any { it.contains("精确提醒") })
        // 不悄悄降级为非精确提醒
        assertTrue(scheduler.inexactCalls.isEmpty())

        // 通知关闭 + 省电限制：如实报告并提供修复方向，同时不伪称可准时
        probe.notificationsAllowed = false
        probe.batteryOptimizationsIgnored = false
        report = restoreUseCases.restore(ReminderRestoreTrigger.APP_START)
        assertFalse(report.environment.notificationsAllowed)
        assertFalse(report.environment.batteryOptimizationsIgnored)
        assertTrue(report.messages.any { it.contains("通知") })
        assertTrue(report.messages.any { it.contains("省电") && it.contains("后台") })

        // 用户完成修复后重新核对：清除未就绪标记并恢复精确调度
        probe.exactAlarmsAllowed = true
        probe.notificationsAllowed = true
        probe.batteryOptimizationsIgnored = true
        scheduler.exactAllowed = true
        report = restoreUseCases.restore(ReminderRestoreTrigger.EXACT_ALARM_PERMISSION_CHANGED)
        assertEquals(0, report.notReadyCount)
        assertNull(remindersRepository.listByTask(task.id).single().notReadyReason)
        assertEquals(resolved, scheduler.alarms[reminder.id])
        assertTrue(report.environment.notificationsAllowed)
        assertTrue(report.messages.none { it.contains("通知权限已关闭") })
    }

    @Test
    fun 最近恢复报告可观察且下次提醒取最早已调度项() = runBlocking {
        clock[0] = millis("2026-10-25", "09:00")
        val task = createTask(dueAtMillis = millis("2026-10-27", "10:00"))
        reminderUseCases.setReminders(task.id, listOf(relative(30))).getOrThrow()
        val report = restoreUseCases.restore(ReminderRestoreTrigger.MANUAL)
        assertEquals(ReminderRestoreTrigger.MANUAL, restoreUseCases.observeLastReport().first()!!.trigger)
        assertEquals(millis("2026-10-27", "09:30"), report.nextReminder!!.scheduledAtMillis)
        assertEquals("装修新房", report.nextReminder!!.ownerTitle)
        assertTrue(report.missed.isEmpty())
        assertEquals(1, report.futureScheduledCount)
    }
}

/** 记录调用的调度器替身：restart 后为空即模拟系统闹钟丢失。 */
private class RestoreFakeAlarmScheduler : AlarmScheduler {
    val alarms = linkedMapOf<String, Long>()
    val inexactCalls = mutableListOf<Pair<String, Long>>()

    /** 与设备能力探针同步的精确调度能力；能力不足时不得悄悄降级。 */
    var exactAllowed: Boolean = true

    override fun canScheduleExact(): Boolean = exactAllowed

    override suspend fun scheduleExact(
        reminderId: String,
        triggerAtMillis: Long,
        taskId: String,
        title: String,
    ): Boolean {
        alarms[reminderId] = triggerAtMillis
        return true
    }

    override suspend fun scheduleInexact(
        reminderId: String,
        triggerAtMillis: Long,
        taskId: String,
        title: String,
    ): Boolean {
        inexactCalls += reminderId to triggerAtMillis
        alarms[reminderId] = triggerAtMillis
        return true
    }

    override suspend fun cancel(reminderId: String) {
        alarms.remove(reminderId)
    }
}

/** 可切换能力的探针替身。 */
private class FakeReminderCapabilityProbe : ReminderCapabilityProbe {
    var notificationsAllowed: Boolean = true
    var exactAlarmsAllowed: Boolean = true
    var batteryOptimizationsIgnored: Boolean = true

    override fun snapshot(): ReminderEnvironmentStatus = ReminderEnvironmentStatus(
        notificationsAllowed = notificationsAllowed,
        exactAlarmsAllowed = exactAlarmsAllowed,
        batteryOptimizationsIgnored = batteryOptimizationsIgnored,
    )
}
