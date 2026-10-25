package com.zhideer.app

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.zhideer.app.data.db.ZhiDeerDatabase
import com.zhideer.app.data.repository.CalendarRemindersRepositoryImpl
import com.zhideer.app.data.repository.CalendarRepositoryImpl
import com.zhideer.app.data.repository.RemindersRepositoryImpl
import com.zhideer.app.data.repository.TasksRepositoryImpl
import com.zhideer.app.domain.model.CalendarEntry
import com.zhideer.app.domain.model.ReminderOwner
import com.zhideer.app.domain.usecase.CalendarInput
import com.zhideer.app.domain.usecase.CalendarReminderUseCases
import com.zhideer.app.domain.usecase.CalendarUseCases
import com.zhideer.app.domain.usecase.ReminderInput
import com.zhideer.app.domain.usecase.ReminderUseCases
import com.zhideer.app.domain.usecase.TaskInput
import com.zhideer.app.domain.usecase.TaskUseCases
import com.zhideer.app.platform.AlarmScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
 * T09/#10 验收测试：为日程设置并调整提醒。
 * - 日程可设置多个提醒：相对开始时刻提前量（随日程移动）或独立时刻，相同提醒去重；
 * - 改期（update/时区重解释）后相对提醒随开始时刻移动，独立时刻提醒不动，过期项明确标记；
 * - 日程删除（回收站）时停止其自身提醒，不影响关联待办自身的提醒；
 * - 提醒与日程结束状态分离：日程已结束不停止提醒记录，过期提醒在展示层标记；
 * - 精确闹钟能力不足时保留提醒并标记未就绪，修复入口数据可见，降级必须显式确认；
 * - 调度键区分归属：同一提醒 id 的待办提醒与日程提醒不会互相覆盖；
 * - 全部走真实 Room 文件库 + 统一用例入口 + Fake 调度器，close-reopen 重读。
 * 设备真实投递（通知/提示音/振动）留待统一实机验收。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CalendarReminderUseCasesTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private lateinit var dbFile: File
    private lateinit var db: ZhiDeerDatabase
    private lateinit var taskUseCases: TaskUseCases
    private lateinit var reminderUseCases: ReminderUseCases
    private lateinit var calendarUseCases: CalendarUseCases
    private lateinit var calendarReminderUseCases: CalendarReminderUseCases
    private lateinit var scheduler: CalendarFakeAlarmScheduler
    private val clock = mutableListOf(0L)
    private val idSeq = AtomicInteger(0)

    private fun millis(date: String, time: String = "10:00"): Long =
        LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dbFile = context.getDatabasePath("t10_test.db")
        dbFile.parentFile?.mkdirs()
        context.deleteDatabase(dbFile.name)
        open()
    }

    private fun open() {
        db = Room.databaseBuilder<ZhiDeerDatabase>(
            context = ApplicationProvider.getApplicationContext<Context>(),
            name = dbFile.absolutePath,
        )
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        val taskRepo = TasksRepositoryImpl(db.taskDao(), Dispatchers.IO)
        val calendarRepo = CalendarRepositoryImpl(db.calendarEntryDao(), Dispatchers.IO)
        scheduler = CalendarFakeAlarmScheduler()
        // 既有待办提醒体系（与 AppContainer 相同组装），验证零回归
        reminderUseCases = ReminderUseCases(
            repository = RemindersRepositoryImpl(db.reminderDao(), Dispatchers.IO),
            tasksRepository = taskRepo,
            scheduler = scheduler,
            idGen = { "rem-${idSeq.incrementAndGet()}" },
            now = { clock.last() },
        )
        taskUseCases = TaskUseCases(
            repository = taskRepo,
            idGen = { "task-${idSeq.incrementAndGet()}" },
            now = { clock.last() },
            zoneId = zone.id,
            onTrashed = { taskId -> reminderUseCases.cancelReminders(taskId) },
            onDueChanged = { taskId -> reminderUseCases.rescheduleForDueChange(taskId) },
            onCancelled = { taskId -> reminderUseCases.cancelReminders(taskId) },
        )
        // 日程提醒体系（与 AppContainer 相同组装）
        calendarReminderUseCases = CalendarReminderUseCases(
            repository = CalendarRemindersRepositoryImpl(db.calendarReminderDao(), Dispatchers.IO),
            calendarRepository = calendarRepo,
            scheduler = scheduler,
            idGen = { "calrem-${idSeq.incrementAndGet()}" },
            now = { clock.last() },
        )
        calendarUseCases = CalendarUseCases(
            repository = calendarRepo,
            idGen = { "entry-${idSeq.incrementAndGet()}" },
            now = { clock.last() },
            zoneId = zone.id,
            onTimesChanged = { entryId -> calendarReminderUseCases.rescheduleForStartChange(entryId) },
            onTrashed = { entryId -> calendarReminderUseCases.cancelReminders(entryId) },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun createEntry(
        title: String = "周会",
        startDate: String = "2026-10-26",
        startTime: String = "10:00",
        endTime: String = "11:00",
    ): CalendarEntry = calendarUseCases.create(
        CalendarInput(
            title = title,
            startMillis = millis(startDate, startTime),
            endMillis = millis(startDate, endTime),
            timezoneId = zone.id,
        ),
    ).getOrThrow()

    private suspend fun displays(entryId: String) =
        calendarReminderUseCases.observeDisplaysByEntry(entryId).first()

    private suspend fun reminders(entryId: String) =
        calendarReminderUseCases.observeByEntry(entryId).first()

    private fun rel(minutes: Long) = ReminderInput(relativeOffsetMillis = minutes * 60_000L)

    private fun abs(date: String, time: String) = ReminderInput(remindAtMillis = millis(date, time))

    @Test
    fun 新建日程默认没有任何提醒显式设置后才创建() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val entry = createEntry()

        // 提醒与日程分别设置：新建日程不自动产生提醒，也不向系统提交任何调度
        assertTrue(displays(entry.id).isEmpty())
        assertTrue(reminders(entry.id).isEmpty())
        assertTrue(scheduler.exactCalls.isEmpty())
        assertTrue(scheduler.inexactCalls.isEmpty())
        assertTrue(scheduler.cancelled.isEmpty())

        // 显式设置后才创建
        clock.add(millis("2026-10-25", "09:05"))
        calendarReminderUseCases.setReminders(entry.id, listOf(rel(15))).getOrThrow()
        assertEquals(1, reminders(entry.id).size)
        assertEquals(1, scheduler.exactCalls.size)
    }

    @Test
    fun 支持多个提醒相同提醒去重并逐项显示具体时间() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val entry = createEntry()
        val start = millis("2026-10-26", "10:00")

        val saved = calendarReminderUseCases.setReminders(
            entry.id,
            listOf(rel(15), rel(15), abs("2026-10-26", "08:30"), abs("2026-10-26", "08:30"), abs("2026-10-25", "20:00")),
        ).getOrThrow()

        // 相同提醒去重：相对提前量或独立时刻相同只存一条
        assertEquals(3, saved.size)
        assertEquals(3, reminders(entry.id).size)
        // 逐项显示具体时间：相对提醒换算为开始前 15 分钟，独立时刻即其本身
        assertEquals(
            listOf(start - 15 * 60_000L, millis("2026-10-26", "08:30"), millis("2026-10-25", "20:00")),
            displays(entry.id).map { it.resolvedAtMillis },
        )
        // 三条提醒各自向系统提交一次精确调度
        assertEquals(
            listOf(start - 15 * 60_000L, millis("2026-10-26", "08:30"), millis("2026-10-25", "20:00")),
            scheduler.exactCalls.map { it.second },
        )
        // 提醒归属日程：模型按 ownerType 区分，可与其他归属提醒区分
        assertTrue(saved.all { it.ownerType == ReminderOwner.CALENDAR })

        // 重开读取：去重结果与具体时间完整保留（离线重开一致）
        db.close()
        open()
        assertEquals(3, reminders(entry.id).size)
        assertEquals(
            listOf(start - 15 * 60_000L, millis("2026-10-26", "08:30"), millis("2026-10-25", "20:00")),
            displays(entry.id).map { it.resolvedAtMillis },
        )
    }

    @Test
    fun 改期后相对提醒随开始移动而独立时刻提醒不动() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val entry = createEntry()
        val saved = calendarReminderUseCases.setReminders(
            entry.id,
            listOf(rel(60), abs("2026-10-26", "08:00")),
        ).getOrThrow()
        val relative = saved.first { it.relativeOffsetMillis != null }
        val absolute = saved.first { it.remindAtMillis != null }
        assertEquals(millis("2026-10-26", "09:00"), scheduler.exactCallsFor(relative.id).last())
        assertEquals(1, scheduler.exactCallsFor(absolute.id).size)

        // 编辑改期：相对提醒随新开始时刻重算，独立时刻提醒不动
        clock.add(millis("2026-10-25", "12:00"))
        calendarUseCases.update(
            entry.id,
            CalendarInput(
                title = "周会",
                startMillis = millis("2026-10-27", "14:00"),
                endMillis = millis("2026-10-27", "15:00"),
                timezoneId = zone.id,
            ),
        ).getOrThrow()
        assertEquals(millis("2026-10-27", "13:00"), scheduler.exactCallsFor(relative.id).last())
        assertEquals(
            displays(entry.id).first { it.reminder.id == relative.id }.resolvedAtMillis,
            millis("2026-10-27", "13:00"),
        )
        val absCallsAfterUpdate = scheduler.exactCallsFor(absolute.id)
        assertEquals(listOf(millis("2026-10-26", "08:00")), absCallsAfterUpdate)

        // 时区重解释改期：开始时刻同样变化，相对提醒继续随动，独立时刻提醒保持
        clock.add(millis("2026-10-25", "13:00"))
        calendarUseCases.reinterpretInZone(entry.id, "Asia/Tokyo").getOrThrow()
        assertEquals(millis("2026-10-27", "12:00"), scheduler.exactCallsFor(relative.id).last())
        assertEquals(absCallsAfterUpdate, scheduler.exactCallsFor(absolute.id))
        assertEquals(
            displays(entry.id).first { it.reminder.id == absolute.id }.resolvedAtMillis,
            millis("2026-10-26", "08:00"),
        )

        // 重开读取：相对提醒按当前开始时刻换算，独立时刻提醒原样保留
        db.close()
        open()
        assertEquals(
            millis("2026-10-27", "12:00"),
            displays(entry.id).first { it.reminder.id == relative.id }.resolvedAtMillis,
        )
        assertEquals(
            millis("2026-10-26", "08:00"),
            displays(entry.id).first { it.reminder.id == absolute.id }.resolvedAtMillis,
        )
    }

    @Test
    fun 过期提醒明确标记且不再向系统调度() = runBlocking {
        clock.add(millis("2026-10-25", "10:00"))
        val entry = createEntry()

        clock.add(millis("2026-10-25", "10:01"))
        calendarReminderUseCases.setReminders(
            entry.id,
            listOf(abs("2026-10-25", "08:00"), abs("2026-10-26", "08:00")),
        ).getOrThrow()

        val marks = displays(entry.id)
        val expired = marks.first { it.reminder.remindAtMillis == millis("2026-10-25", "08:00") }
        val future = marks.first { it.reminder.remindAtMillis == millis("2026-10-26", "08:00") }
        assertTrue("过期提醒必须明确标记", expired.isOverdue)
        assertTrue(!future.isOverdue)
        // 过期提醒保留记录但不再设置系统闹钟（只调度未过期那条）
        assertEquals(
            listOf(millis("2026-10-26", "08:00")),
            scheduler.exactCalls.map { it.second },
        )
    }

    @Test
    fun 日程结束不停用提醒记录提醒与结束状态分离() = runBlocking {
        // 日程已结束只表示时间已过（GLOSSARY：结束≠完成），提醒记录保留、展示层标记过期
        clock.add(millis("2026-10-25", "09:00"))
        val entry = createEntry(startDate = "2026-10-25", startTime = "09:00", endTime = "10:00")
        calendarReminderUseCases.setReminders(entry.id, listOf(abs("2026-10-25", "09:30"))).getOrThrow()

        clock.add(millis("2026-10-25", "11:00"))
        val marks = displays(entry.id)
        assertEquals(1, marks.size)
        assertTrue(marks.single().isOverdue)
        assertTrue(reminders(entry.id).single().enabled)
    }

    @Test
    fun 精确闹钟能力不足时保留提醒标记未就绪修复后恢复调度() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val entry = createEntry()
        scheduler.exactAllowed = false

        clock.add(millis("2026-10-25", "09:05"))
        val saved = calendarReminderUseCases.setReminders(entry.id, listOf(rel(15), abs("2026-10-26", "08:00"))).getOrThrow()

        // 能力不足：保留日程与提醒记录，逐条标记未就绪及原因，不悄悄降级
        assertEquals(2, saved.size)
        assertTrue(saved.all { it.notReadyReason != null })
        assertEquals(
            listOf(CalendarReminderUseCases.NOT_READY_EXACT_ALARM, CalendarReminderUseCases.NOT_READY_EXACT_ALARM),
            reminders(entry.id).map { it.notReadyReason },
        )
        assertTrue(scheduler.exactCalls.isEmpty())
        assertTrue(scheduler.inexactCalls.isEmpty())
        // 未就绪标记即修复入口数据：界面据此展示修复按钮
        assertTrue(displays(entry.id).all { it.reminder.notReadyReason == CalendarReminderUseCases.NOT_READY_EXACT_ALARM })

        // 能力未恢复时重试：仍保持未就绪，不产生调度
        clock.add(millis("2026-10-25", "09:10"))
        calendarReminderUseCases.repairNotReady(entry.id).getOrThrow()
        assertTrue(reminders(entry.id).all { it.notReadyReason == CalendarReminderUseCases.NOT_READY_EXACT_ALARM })
        assertTrue(scheduler.exactCalls.isEmpty())

        // 授权后修复：清除未就绪标记并恢复精确调度
        scheduler.exactAllowed = true
        clock.add(millis("2026-10-25", "09:15"))
        val repaired = calendarReminderUseCases.repairNotReady(entry.id).getOrThrow()
        assertTrue(repaired.all { it.notReadyReason == null })
        assertEquals(
            listOf(millis("2026-10-26", "09:45"), millis("2026-10-26", "08:00")),
            scheduler.exactCalls.map { it.second },
        )
    }

    @Test
    fun 降级为非精确提醒必须显式确认且随改期保持() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val entry = createEntry()
        scheduler.exactAllowed = false

        // 用户显式确认降级：以非精确方式调度，不标记未就绪
        clock.add(millis("2026-10-25", "09:05"))
        val saved = calendarReminderUseCases.setReminders(entry.id, listOf(rel(15)), allowInexact = true).getOrThrow()
        assertTrue(scheduler.exactCalls.isEmpty())
        assertEquals(listOf(millis("2026-10-26", "09:45")), scheduler.inexactCalls.map { it.second })
        assertNull(saved.single().notReadyReason)
        assertTrue(saved.single().inexactConfirmed)

        // 改期后按已确认的降级方式重算，不回退成未就绪
        clock.add(millis("2026-10-25", "10:00"))
        calendarUseCases.update(
            entry.id,
            CalendarInput(
                title = "周会",
                startMillis = millis("2026-10-27", "14:00"),
                endMillis = millis("2026-10-27", "15:00"),
                timezoneId = zone.id,
            ),
        ).getOrThrow()
        // inexactCalls 累积记录：初次降级调度 + 改期后按降级方式重算
        assertEquals(
            listOf(millis("2026-10-26", "09:45"), millis("2026-10-27", "13:45")),
            scheduler.inexactCalls.map { it.second },
        )
        assertNull(reminders(entry.id).single().notReadyReason)

        // 重开读取：降级确认保留，能力仍未恢复时改期后按非精确重算（重开后调度器为全新替身）
        db.close()
        open()
        scheduler.exactAllowed = false
        assertTrue(reminders(entry.id).single().inexactConfirmed)
        clock.add(millis("2026-10-25", "11:00"))
        calendarUseCases.update(
            entry.id,
            CalendarInput(
                title = "周会",
                startMillis = millis("2026-10-28", "14:00"),
                endMillis = millis("2026-10-28", "15:00"),
                timezoneId = zone.id,
            ),
        ).getOrThrow()
        assertEquals(listOf(millis("2026-10-28", "13:45")), scheduler.inexactCalls.map { it.second })
        assertNull(reminders(entry.id).single().notReadyReason)
    }

    @Test
    fun 调度键区分归属同id的待办与日程提醒不互相覆盖() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val task = taskUseCases.create(
            TaskInput(title = "装修新房", dueAtMillis = millis("2026-10-26", "18:00"), dueZoneId = zone.id),
        ).getOrThrow()
        val entry = createEntry(title = "看装修进度")

        reminderUseCases.setReminders(task.id, listOf(rel(30))).getOrThrow()
        calendarReminderUseCases.setReminders(entry.id, listOf(rel(60))).getOrThrow()
        val taskRow = reminderUseCases.observeByTask(task.id).first().single()
        val entryRow = calendarReminderUseCases.observeByEntry(entry.id).first().single()
        // 各自提醒按归属区分（GLOSSARY：关联待办和日程各自的提醒可区分）
        assertEquals(ReminderOwner.TASK, taskRow.ownerType)
        assertEquals(ReminderOwner.CALENDAR, entryRow.ownerType)

        // 触发双方重算后断言调度键：日程提醒键带归属前缀，
        // 即使记录 id 相同也不会与待办提醒共用同一 PendingIntent/通知键
        reminderUseCases.rescheduleForDueChange(task.id).getOrThrow()
        calendarReminderUseCases.rescheduleForStartChange(entry.id).getOrThrow()
        val taskKeys = scheduler.exactCalls.filter { it.second == millis("2026-10-26", "17:30") }.map { it.first }
        val entryKeys = scheduler.exactCalls.filter { it.second == millis("2026-10-26", "09:00") }.map { it.first }
        assertTrue(taskKeys.isNotEmpty())
        assertTrue(entryKeys.isNotEmpty())
        assertTrue("待办与日程提醒的调度键必须可区分", taskKeys.none { it in entryKeys })
        assertTrue(entryKeys.all { it.startsWith("cal:") })
    }

    @Test
    fun 日程删除时停止自身提醒且不影响关联待办提醒() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val task = taskUseCases.create(
            TaskInput(title = "装修新房", dueAtMillis = millis("2026-10-26", "18:00"), dueZoneId = zone.id),
        ).getOrThrow()
        val entry = createEntry(title = "看装修进度")
        reminderUseCases.setReminders(task.id, listOf(rel(30))).getOrThrow()
        calendarReminderUseCases.setReminders(entry.id, listOf(rel(60))).getOrThrow()
        val taskReminderCalls = scheduler.exactCalls.filter { !it.first.startsWith("cal:") }

        // 删除日程（回收站）：停止日程自身提醒（取消闹钟并清除记录）
        clock.add(millis("2026-10-25", "10:00"))
        calendarUseCases.trash(entry.id).getOrThrow()
        assertTrue(scheduler.cancelled.isNotEmpty())
        assertTrue(reminders(entry.id).isEmpty())
        assertTrue(displays(entry.id).isEmpty())

        // 关联待办自身的提醒不受影响：记录保留、调度调用未被追加或取消
        assertEquals(taskReminderCalls, scheduler.exactCalls.filter { !it.first.startsWith("cal:") })
        assertEquals(1, reminderUseCases.observeByTask(task.id).first().size)

        // 重开读取：停止结果保持，待办提醒仍在
        db.close()
        open()
        assertTrue(reminders(entry.id).isEmpty())
        assertEquals(1, reminderUseCases.observeByTask(task.id).first().size)
    }

    @Test
    fun 非法输入与不存在的日程返回中文错误() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val entry = createEntry()

        val noEntry = calendarReminderUseCases.setReminders("no-such-entry", listOf(rel(15)))
        assertTrue(noEntry.isFailure)
        assertEquals("日程不存在或已进入回收站", noEntry.exceptionOrNull()!!.message)

        val empty = calendarReminderUseCases.setReminders(entry.id, listOf(ReminderInput()))
        assertTrue(empty.isFailure)
        assertEquals("提醒时间不能为空", empty.exceptionOrNull()!!.message)

        val both = calendarReminderUseCases.setReminders(
            entry.id,
            listOf(ReminderInput(relativeOffsetMillis = 15 * 60_000L, remindAtMillis = millis("2026-10-26", "08:00"))),
        )
        assertTrue(both.isFailure)
        assertEquals("相对提醒与独立时刻提醒只能选择其一", both.exceptionOrNull()!!.message)

        val nonPositive = calendarReminderUseCases.setReminders(entry.id, listOf(ReminderInput(relativeOffsetMillis = 0L)))
        assertTrue(nonPositive.isFailure)
        assertEquals("相对提醒需提前于开始时刻", nonPositive.exceptionOrNull()!!.message)

        // 已进入回收站的日程不能再设置提醒
        calendarUseCases.trash(entry.id).getOrThrow()
        val trashed = calendarReminderUseCases.setReminders(entry.id, listOf(rel(15)))
        assertTrue(trashed.isFailure)
        assertEquals("日程不存在或已进入回收站", trashed.exceptionOrNull()!!.message)
    }

    @Test
    fun 删除单条提醒与启停控制调度() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val entry = createEntry()
        val saved = calendarReminderUseCases.setReminders(entry.id, listOf(rel(15), abs("2026-10-26", "08:00"))).getOrThrow()
        val first = saved[0]
        val second = saved[1]

        // 删除单条：取消其闹钟（键带归属前缀）并移除记录
        clock.add(millis("2026-10-25", "09:30"))
        calendarReminderUseCases.deleteReminder(first.id).getOrThrow()
        assertTrue(scheduler.cancelled.contains("cal:" + first.id))
        assertEquals(listOf(second.id), reminders(entry.id).map { it.id })
        assertTrue(calendarReminderUseCases.deleteReminder(first.id).isFailure)

        // 停用：取消闹钟、保留记录；重新启用：恢复调度
        clock.add(millis("2026-10-25", "09:40"))
        calendarReminderUseCases.setEnabled(second.id, enabled = false).getOrThrow()
        assertTrue(scheduler.cancelled.contains("cal:" + second.id))
        assertTrue(!reminders(entry.id).single().enabled)
        clock.add(millis("2026-10-25", "09:50"))
        calendarReminderUseCases.setEnabled(second.id, enabled = true).getOrThrow()
        assertTrue(reminders(entry.id).single().enabled)
        assertTrue(scheduler.exactCallsFor(second.id).contains(millis("2026-10-26", "08:00")))

        assertTrue(calendarReminderUseCases.setEnabled("no-such-reminder", enabled = true).isFailure)
    }
}

/** 记录调用的调度器替身：键为归属区分后的调度键，可切换精确闹钟能力。 */
private class CalendarFakeAlarmScheduler : AlarmScheduler {

    var exactAllowed: Boolean = true
    private var scheduleResult: Boolean = true

    val exactCalls = mutableListOf<Pair<String, Long>>()
    val inexactCalls = mutableListOf<Pair<String, Long>>()
    val cancelled = mutableListOf<String>()

    override fun canScheduleExact(): Boolean = exactAllowed

    override fun scheduleExact(
        reminderId: String,
        triggerAtMillis: Long,
        taskId: String,
        title: String,
    ): Boolean {
        exactCalls += reminderId to triggerAtMillis
        return scheduleResult
    }

    override fun scheduleInexact(
        reminderId: String,
        triggerAtMillis: Long,
        taskId: String,
        title: String,
    ): Boolean {
        inexactCalls += reminderId to triggerAtMillis
        return scheduleResult
    }

    override fun cancel(reminderId: String) {
        cancelled += reminderId
    }

    /** 按日程提醒记录 id 解析调度键查询（日程提醒键带 "cal:" 归属前缀）。 */
    fun exactCallsFor(reminderId: String): List<Long> =
        exactCalls.filter { it.first == "cal:$reminderId" }.map { it.second }
}
