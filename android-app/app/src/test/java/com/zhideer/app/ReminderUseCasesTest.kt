package com.zhideer.app

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.zhideer.app.data.db.ZhiDeerDatabase
import com.zhideer.app.data.repository.RemindersRepositoryImpl
import com.zhideer.app.data.repository.TasksRepositoryImpl
import com.zhideer.app.domain.model.TodoTask
import com.zhideer.app.domain.usecase.ReminderInput
import com.zhideer.app.domain.usecase.ReminderUseCases
import com.zhideer.app.domain.usecase.TaskInput
import com.zhideer.app.domain.usecase.TaskUseCases
import com.zhideer.app.platform.AlarmScheduler
import com.zhideer.app.platform.NotificationPoster
import com.zhideer.app.platform.ReminderAlarmReceiver
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
 * T08/#9 验收测试：为待办设置本地提醒。
 * - 截止与提醒分开：新建待办默认无提醒，显式设置才创建（不自动提醒）；
 * - 支持多个提醒、相同提醒去重，逐项显示具体时间与无提醒状态；
 * - 相对提醒随截止移动而移动、独立时刻提醒不动、过期项明确标记；
 * - 精确闹钟能力不足时保留提醒记录并标记未就绪，修复入口数据可见，
 *   降级为非精确提醒必须显式确认（allowInexact）；
 * - 待办取消/删除时提醒一并停止；
 * - 全部走真实 Room 文件库 + 统一用例入口 + Fake 调度器，close-reopen 重读。
 * 设备真实投递（通知/提示音/振动）留待统一实机验收。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReminderUseCasesTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private lateinit var dbFile: File
    private lateinit var db: ZhiDeerDatabase
    private lateinit var taskUseCases: TaskUseCases
    private lateinit var reminderUseCases: ReminderUseCases
    private lateinit var scheduler: FakeAlarmScheduler
    private val clock = mutableListOf(0L)
    private val idSeq = AtomicInteger(0)

    private fun millis(date: String, time: String = "10:00"): Long =
        LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dbFile = context.getDatabasePath("t09_test.db")
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
        scheduler = FakeAlarmScheduler()
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
            // 与 AppContainer 相同的组装：截止变化重算相对提醒；删除/取消停止提醒
            onTrashed = { taskId -> reminderUseCases.cancelReminders(taskId) },
            onDueChanged = { taskId -> reminderUseCases.rescheduleForDueChange(taskId) },
            onCancelled = { taskId -> reminderUseCases.cancelReminders(taskId) },
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
    ): TodoTask = taskUseCases.create(
        TaskInput(title = title, dueAtMillis = dueAtMillis, dueEpochDay = dueEpochDay, dueZoneId = zone.id),
    ).getOrThrow()

    private suspend fun displays(taskId: String) =
        reminderUseCases.observeDisplaysByTask(taskId).first()

    private suspend fun reminders(taskId: String) =
        reminderUseCases.observeByTask(taskId).first()

    private fun rel(minutes: Long) = ReminderInput(relativeOffsetMillis = minutes * 60_000L)

    private fun abs(date: String, time: String) = ReminderInput(remindAtMillis = millis(date, time))

    @Test
    fun 新建待办默认没有任何提醒显式设置后才创建() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val task = createTask(dueAtMillis = millis("2026-10-26", "18:00"))

        // 新安装/新建默认不自动提醒：记录为空，无提醒状态可观察，也不向系统提交任何调度
        assertTrue(displays(task.id).isEmpty())
        assertTrue(reminders(task.id).isEmpty())
        assertTrue(scheduler.exactCalls.isEmpty())
        assertTrue(scheduler.inexactCalls.isEmpty())
        assertTrue(scheduler.cancelled.isEmpty())

        // 显式设置后才创建
        clock.add(millis("2026-10-25", "09:05"))
        reminderUseCases.setReminders(task.id, listOf(rel(30))).getOrThrow()
        assertEquals(1, reminders(task.id).size)
        assertEquals(1, scheduler.exactCalls.size)
    }

    @Test
    fun 支持多个提醒相同提醒去重并逐项显示具体时间() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val task = createTask(dueAtMillis = millis("2026-10-26", "18:00"))
        val due = millis("2026-10-26", "18:00")

        val saved = reminderUseCases.setReminders(
            task.id,
            listOf(rel(30), rel(30), abs("2026-10-27", "09:00"), abs("2026-10-27", "09:00"), abs("2026-10-28", "08:30")),
        ).getOrThrow()

        // 相同提醒去重：相对偏移或绝对时刻相同只存一条
        assertEquals(3, saved.size)
        assertEquals(3, reminders(task.id).size)
        // 逐项显示具体时间：相对提醒换算为截止前 30 分钟，独立时刻即其本身
        assertEquals(
            listOf(due - 30 * 60_000L, millis("2026-10-27", "09:00"), millis("2026-10-28", "08:30")),
            displays(task.id).map { it.resolvedAtMillis },
        )
        // 三条提醒各自向系统提交一次精确调度
        assertEquals(
            listOf(due - 30 * 60_000L, millis("2026-10-27", "09:00"), millis("2026-10-28", "08:30")),
            scheduler.exactCalls.map { it.second },
        )

        // 重开读取：去重结果与具体时间完整保留
        db.close()
        open()
        assertEquals(3, reminders(task.id).size)
        assertEquals(
            listOf(due - 30 * 60_000L, millis("2026-10-27", "09:00"), millis("2026-10-28", "08:30")),
            displays(task.id).map { it.resolvedAtMillis },
        )
    }

    @Test
    fun 相对提醒随截止移动而独立时刻提醒不动() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val task = createTask(dueAtMillis = millis("2026-10-26", "18:00"))
        val saved = reminderUseCases.setReminders(
            task.id,
            listOf(rel(60), abs("2026-10-27", "09:00")),
        ).getOrThrow()
        val relative = saved.first { it.relativeOffsetMillis != null }
        val absolute = saved.first { it.remindAtMillis != null }
        assertEquals(millis("2026-10-26", "17:00"), scheduler.exactCallsFor(relative.id).last())
        assertEquals(1, scheduler.exactCallsFor(absolute.id).size)

        // 延期（postpone）：相对提醒随新截止重算，独立时刻提醒不动
        clock.add(millis("2026-10-25", "12:00"))
        taskUseCases.postpone(task.id, newDueEpochDay = null, newDueAtMillis = millis("2026-10-27", "18:00")).getOrThrow()
        assertEquals(millis("2026-10-27", "17:00"), scheduler.exactCallsFor(relative.id).last())
        assertEquals(
            displays(task.id).first { it.reminder.id == relative.id }.resolvedAtMillis,
            millis("2026-10-27", "17:00"),
        )
        val absCallsAfterPostpone = scheduler.exactCallsFor(absolute.id)
        assertEquals(listOf(millis("2026-10-27", "09:00")), absCallsAfterPostpone)

        // 编辑（update）：相对提醒继续随新截止移动，独立时刻提醒保持原时刻、无重复调度
        clock.add(millis("2026-10-25", "13:00"))
        taskUseCases.update(
            task.id,
            TaskInput(title = "装修新房", dueAtMillis = millis("2026-10-28", "10:00"), dueZoneId = zone.id),
        ).getOrThrow()
        assertEquals(millis("2026-10-28", "09:00"), scheduler.exactCallsFor(relative.id).last())
        assertEquals(absCallsAfterPostpone, scheduler.exactCallsFor(absolute.id))
        assertEquals(
            displays(task.id).first { it.reminder.id == absolute.id }.resolvedAtMillis,
            millis("2026-10-27", "09:00"),
        )

        // 重开读取：相对提醒按当前截止换算，独立时刻提醒原样保留
        db.close()
        open()
        assertEquals(millis("2026-10-28", "09:00"), displays(task.id).first { it.reminder.id == relative.id }.resolvedAtMillis)
        assertEquals(millis("2026-10-27", "09:00"), displays(task.id).first { it.reminder.id == absolute.id }.resolvedAtMillis)
    }

    @Test
    fun 过期提醒明确标记且不再向系统调度() = runBlocking {
        clock.add(millis("2026-10-25", "10:00"))
        val task = createTask(dueAtMillis = millis("2026-10-26", "18:00"))

        clock.add(millis("2026-10-25", "10:01"))
        reminderUseCases.setReminders(
            task.id,
            listOf(abs("2026-10-25", "08:00"), abs("2026-10-26", "08:00")),
        ).getOrThrow()

        val marks = displays(task.id)
        val expired = marks.first { it.reminder.remindAtMillis == millis("2026-10-25", "08:00") }
        val future = marks.first { it.reminder.remindAtMillis == millis("2026-10-26", "08:00") }
        assertTrue("过期提醒必须明确标记", expired.isOverdue)
        assertTrue(!future.isOverdue)
        // 过期提醒不再设置系统闹钟（只调度过未过期那条）
        assertEquals(
            listOf(millis("2026-10-26", "08:00")),
            scheduler.exactCalls.map { it.second },
        )
    }

    @Test
    fun 精确闹钟能力不足时保留提醒标记未就绪修复后恢复调度() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val task = createTask(dueAtMillis = millis("2026-10-26", "18:00"))
        scheduler.exactAllowed = false

        clock.add(millis("2026-10-25", "09:05"))
        val saved = reminderUseCases.setReminders(task.id, listOf(rel(30), abs("2026-10-27", "09:00"))).getOrThrow()

        // 能力不足：保留待办与提醒记录，逐条标记未就绪及原因，不悄悄降级
        assertEquals(2, saved.size)
        assertTrue(saved.all { it.notReadyReason != null })
        assertEquals(
            listOf(ReminderUseCases.NOT_READY_EXACT_ALARM, ReminderUseCases.NOT_READY_EXACT_ALARM),
            reminders(task.id).map { it.notReadyReason },
        )
        assertTrue(scheduler.exactCalls.isEmpty())
        assertTrue(scheduler.inexactCalls.isEmpty())
        // 未就绪标记即修复入口数据：界面据此展示修复按钮
        assertTrue(displays(task.id).all { it.reminder.notReadyReason == ReminderUseCases.NOT_READY_EXACT_ALARM })

        // 能力未恢复时重试：仍保持未就绪，不产生调度
        clock.add(millis("2026-10-25", "09:10"))
        reminderUseCases.repairNotReady(task.id).getOrThrow()
        assertTrue(reminders(task.id).all { it.notReadyReason == ReminderUseCases.NOT_READY_EXACT_ALARM })
        assertTrue(scheduler.exactCalls.isEmpty())

        // 授权后修复：清除未就绪标记并恢复精确调度
        scheduler.exactAllowed = true
        clock.add(millis("2026-10-25", "09:15"))
        val repaired = reminderUseCases.repairNotReady(task.id).getOrThrow()
        assertTrue(repaired.all { it.notReadyReason == null })
        assertEquals(
            listOf(millis("2026-10-26", "17:30"), millis("2026-10-27", "09:00")),
            scheduler.exactCalls.map { it.second },
        )
    }

    @Test
    fun 降级为非精确提醒必须显式确认且随截止移动保持() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val task = createTask(dueAtMillis = millis("2026-10-26", "18:00"))
        scheduler.exactAllowed = false

        // 用户显式确认降级：以非精确方式调度，不标记未就绪
        clock.add(millis("2026-10-25", "09:05"))
        val saved = reminderUseCases.setReminders(task.id, listOf(rel(30)), allowInexact = true).getOrThrow()
        assertTrue(scheduler.exactCalls.isEmpty())
        assertEquals(listOf(millis("2026-10-26", "17:30")), scheduler.inexactCalls.map { it.second })
        assertNull(saved.single().notReadyReason)
        assertTrue(saved.single().inexactConfirmed)

        // 截止移动后按已确认的降级方式重算，不回退成未就绪
        clock.add(millis("2026-10-25", "10:00"))
        taskUseCases.postpone(task.id, newDueEpochDay = null, newDueAtMillis = millis("2026-10-27", "18:00")).getOrThrow()
        assertEquals(listOf(millis("2026-10-26", "17:30"), millis("2026-10-27", "17:30")), scheduler.inexactCalls.map { it.second })
        assertNull(reminders(task.id).single().notReadyReason)

        // 重开读取：降级确认保留，能力仍未恢复时移动后按非精确重算（重开后调度器为全新替身）
        db.close()
        open()
        scheduler.exactAllowed = false
        assertTrue(reminders(task.id).single().inexactConfirmed)
        clock.add(millis("2026-10-25", "11:00"))
        taskUseCases.postpone(task.id, newDueEpochDay = null, newDueAtMillis = millis("2026-10-28", "18:00")).getOrThrow()
        assertEquals(listOf(millis("2026-10-28", "17:30")), scheduler.inexactCalls.map { it.second })
        assertNull(reminders(task.id).single().notReadyReason)
    }

    @Test
    fun 截止改为日期截止时相对提醒标记未就绪改回时刻后恢复() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val task = createTask(dueAtMillis = millis("2026-10-26", "18:00"))
        val saved = reminderUseCases.setReminders(
            task.id,
            listOf(rel(60), abs("2026-10-27", "09:00")),
        ).getOrThrow()
        val relative = saved.first { it.relativeOffsetMillis != null }
        val absolute = saved.first { it.remindAtMillis != null }
        assertEquals(2, scheduler.exactCalls.size)
        val absCallsBefore = scheduler.exactCallsFor(absolute.id)

        // 截止改为日期截止：相对提醒无法换算时刻，标记未就绪并停止闹钟；独立时刻提醒不动
        clock.add(millis("2026-10-25", "12:00"))
        taskUseCases.postpone(
            task.id,
            newDueEpochDay = LocalDate.parse("2026-10-27").toEpochDay(),
            newDueAtMillis = null,
        ).getOrThrow()
        assertEquals(ReminderUseCases.NOT_READY_NO_DUE_INSTANT, reminders(task.id).first { it.id == relative.id }.notReadyReason)
        assertNull(displays(task.id).first { it.reminder.id == relative.id }.resolvedAtMillis)
        assertTrue(scheduler.cancelled.contains(relative.id))
        assertEquals(absCallsBefore, scheduler.exactCallsFor(absolute.id))

        // 截止改回具体时刻：相对提醒恢复换算并重新调度
        clock.add(millis("2026-10-25", "13:00"))
        taskUseCases.postpone(task.id, newDueEpochDay = null, newDueAtMillis = millis("2026-10-28", "18:00")).getOrThrow()
        assertNull(reminders(task.id).first { it.id == relative.id }.notReadyReason)
        assertEquals(millis("2026-10-28", "17:00"), scheduler.exactCallsFor(relative.id).last())
    }

    @Test
    fun 待办取消或删除时提醒一并停止() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val taskA = createTask(title = "取消的待办", dueAtMillis = millis("2026-10-26", "18:00"))
        val taskB = createTask(title = "删除的待办", dueAtMillis = millis("2026-10-26", "19:00"))
        val savedA = reminderUseCases.setReminders(taskA.id, listOf(rel(30))).getOrThrow()
        val savedB = reminderUseCases.setReminders(taskB.id, listOf(abs("2026-10-27", "09:00"))).getOrThrow()
        assertEquals(2, scheduler.exactCalls.size)

        // 取消待办：停止该待办自身的提醒（GLOSSARY），提醒记录一并清除
        clock.add(millis("2026-10-25", "10:00"))
        taskUseCases.cancel(taskA.id).getOrThrow()
        assertTrue(scheduler.cancelled.containsAll(savedA.map { it.id }))
        assertTrue(reminders(taskA.id).isEmpty())

        // 删除进回收站：闹钟同样停止，记录清除
        clock.add(millis("2026-10-25", "11:00"))
        taskUseCases.trash(taskB.id).getOrThrow()
        assertTrue(scheduler.cancelled.containsAll(savedB.map { it.id }))
        assertTrue(reminders(taskB.id).isEmpty())

        // 重开读取：停止结果保持
        db.close()
        open()
        assertTrue(reminders(taskA.id).isEmpty())
        assertTrue(reminders(taskB.id).isEmpty())
    }

    @Test
    fun 删除单条提醒与启停控制调度() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val task = createTask(dueAtMillis = millis("2026-10-26", "18:00"))
        val saved = reminderUseCases.setReminders(task.id, listOf(rel(30), abs("2026-10-27", "09:00"))).getOrThrow()
        val first = saved[0]
        val second = saved[1]

        // 删除单条：取消其闹钟并移除记录
        clock.add(millis("2026-10-25", "09:30"))
        reminderUseCases.deleteReminder(first.id).getOrThrow()
        assertTrue(scheduler.cancelled.contains(first.id))
        assertEquals(listOf(second.id), reminders(task.id).map { it.id })
        assertTrue(reminderUseCases.deleteReminder(first.id).isFailure)

        // 停用：取消闹钟、保留记录；重新启用：恢复调度
        clock.add(millis("2026-10-25", "09:40"))
        reminderUseCases.setEnabled(second.id, enabled = false).getOrThrow()
        assertTrue(scheduler.cancelled.contains(second.id))
        assertTrue(!reminders(task.id).single().enabled)
        clock.add(millis("2026-10-25", "09:50"))
        reminderUseCases.setEnabled(second.id, enabled = true).getOrThrow()
        assertTrue(reminders(task.id).single().enabled)
        assertTrue(scheduler.exactCallsFor(second.id).contains(millis("2026-10-27", "09:00")))

        assertTrue(reminderUseCases.setEnabled("no-such-reminder", enabled = true).isFailure)
    }

    @Test
    fun 非法输入与不存在的待办返回中文错误() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val task = createTask(dueAtMillis = millis("2026-10-26", "18:00"))

        val noTask = reminderUseCases.setReminders("no-such-task", listOf(rel(30)))
        assertTrue(noTask.isFailure)
        assertEquals("待办不存在或已进入回收站", noTask.exceptionOrNull()!!.message)

        val empty = reminderUseCases.setReminders(task.id, listOf(ReminderInput()))
        assertTrue(empty.isFailure)
        assertEquals("提醒时间不能为空", empty.exceptionOrNull()!!.message)

        val both = reminderUseCases.setReminders(
            task.id,
            listOf(ReminderInput(relativeOffsetMillis = 30 * 60_000L, remindAtMillis = millis("2026-10-27", "09:00"))),
        )
        assertTrue(both.isFailure)
        assertEquals("相对提醒与独立时刻提醒只能选择其一", both.exceptionOrNull()!!.message)

        val nonPositive = reminderUseCases.setReminders(task.id, listOf(ReminderInput(relativeOffsetMillis = 0L)))
        assertTrue(nonPositive.isFailure)
        assertEquals("相对提醒需提前于截止时刻", nonPositive.exceptionOrNull()!!.message)

        // 日期截止不含具体时刻，相对提醒无法换算
        val dateOnly = createTask(title = "日期截止待办", dueEpochDay = LocalDate.parse("2026-10-30").toEpochDay())
        val dateOnlyRel = reminderUseCases.setReminders(dateOnly.id, listOf(rel(30)))
        assertTrue(dateOnlyRel.isFailure)
        assertEquals("日期截止不含具体时刻，无法设置相对提醒", dateOnlyRel.exceptionOrNull()!!.message)
        // 独立时刻提醒不受影响
        assertTrue(reminderUseCases.setReminders(dateOnly.id, listOf(abs("2026-10-29", "09:00"))).isSuccess)
    }

    @Test
    fun 提醒到达时由通知投递器发出待办通知() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val posted = mutableListOf<Triple<String, String, String>>()
        var channelReady = false
        val fake = object : NotificationPoster {
            override fun ensureChannel() {
                channelReady = true
            }

            override fun post(reminderId: String, taskId: String, title: String, text: String): Boolean {
                posted += Triple(reminderId, taskId, title)
                return true
            }
        }
        ReminderAlarmReceiver.posterFactory = { fake }

        val intent = ReminderAlarmReceiver.buildIntent(context, "rem-9", "task-1", "装修新房")
        ReminderAlarmReceiver().onReceive(context, intent)

        assertTrue(channelReady)
        assertEquals(listOf(Triple("rem-9", "task-1", "装修新房")), posted)
    }
}

/** 记录调用的调度器替身：可切换精确闹钟能力，供业务测试断言 schedule/cancel。 */
private class FakeAlarmScheduler : AlarmScheduler {

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

    fun exactCallsFor(reminderId: String): List<Long> =
        exactCalls.filter { it.first == reminderId }.map { it.second }
}
