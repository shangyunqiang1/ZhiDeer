package com.zhideer.app

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.zhideer.app.data.db.ZhiDeerDatabase
import com.zhideer.app.data.repository.CalendarRemindersRepositoryImpl
import com.zhideer.app.data.repository.CalendarRepositoryImpl
import com.zhideer.app.data.repository.RemindersRepositoryImpl
import com.zhideer.app.data.repository.SubTasksRepositoryImpl
import com.zhideer.app.data.repository.TaskOccurrenceRepositoryImpl
import com.zhideer.app.data.repository.TasksRepositoryImpl
import com.zhideer.app.domain.model.RecurrenceFrequency
import com.zhideer.app.domain.model.RecurrenceRule
import com.zhideer.app.domain.model.TaskStatus
import com.zhideer.app.domain.model.schedulerKey
import com.zhideer.app.domain.repository.CalendarRepository
import com.zhideer.app.domain.repository.TaskOccurrenceRepository
import com.zhideer.app.domain.repository.TasksRepository
import com.zhideer.app.domain.usecase.CalendarInput
import com.zhideer.app.domain.usecase.CalendarReminderUseCases
import com.zhideer.app.domain.usecase.CalendarUseCases
import com.zhideer.app.domain.usecase.ReminderInput
import com.zhideer.app.domain.usecase.ReminderUseCases
import com.zhideer.app.domain.usecase.SubTaskUseCases
import com.zhideer.app.domain.usecase.TaskInput
import com.zhideer.app.domain.usecase.TaskScheduleLinkUseCases
import com.zhideer.app.domain.usecase.TaskUseCases
import com.zhideer.app.platform.AlarmScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * T11/#12 验收测试：完成、取消、删除（回收站）、重新激活事项时的提醒与关联联动。
 * - 完成待办停止其自身全部提醒，保留真实状态与时间戳；未来关联日程默认保留（界面
 *   经 CompleteTaskFutureSchedulesDialog 提示后由用户选择，domain 不隐式处置日程）；
 * - 撤销完成不自动恢复已停止的提醒（恢复清单如实：提醒已停止需重新设置，不假装恢复）；
 *   经确认重新设置后未来提醒重启、过去提醒不补响；
 * - 取消待办停止自身提醒、保留业务记录；关联日程保留待用户处置；
 * - 删除待办保留日程、解除全部关联、停止自身提醒，子任务同批进入回收站；
 * - 删除日程只停止其自身提醒，不影响关联待办及其提醒；
 * - 日程时间调整相对提醒随动、独立时刻提醒不动（回归保障）；
 * - 重复待办完成单次出现不停本体提醒（每次出现分别记录，提醒仍适用于未来出现）；
 * - 全链路组合场景 close-reopen 持久验证，联动结果均真实持久。
 * 组装与 AppContainer 相同的联动接线 + Fake 调度器，断言每次 schedule/cancel。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InterlockUseCasesTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private lateinit var dbFile: File
    private lateinit var db: ZhiDeerDatabase
    private lateinit var tasksRepo: TasksRepository
    private lateinit var calendarRepo: CalendarRepository
    private lateinit var occurrenceRepository: TaskOccurrenceRepository
    private lateinit var taskUseCases: TaskUseCases
    private lateinit var reminderUseCases: ReminderUseCases
    private lateinit var subTaskUseCases: SubTaskUseCases
    private lateinit var linkUseCases: TaskScheduleLinkUseCases
    private lateinit var calendarUseCases: CalendarUseCases
    private lateinit var calendarReminderUseCases: CalendarReminderUseCases
    private lateinit var scheduler: InterlockFakeAlarmScheduler
    private val clock = mutableListOf(0L)
    private val idSeq = AtomicInteger(0)

    private fun millis(date: String, time: String = "10:00"): Long =
        LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()

    private fun epochDay(date: String): Long = LocalDate.parse(date).toEpochDay()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dbFile = context.getDatabasePath("t11_test.db")
        dbFile.parentFile?.mkdirs()
        context.deleteDatabase(dbFile.name)
        open()
    }

    /** 组装与 AppContainer 相同的 T11/#12 联动接线（回调内实现，不改既有语义）。 */
    private fun open() {
        db = Room.databaseBuilder<ZhiDeerDatabase>(
            context = ApplicationProvider.getApplicationContext<Context>(),
            name = dbFile.absolutePath,
        )
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        val taskRepo = TasksRepositoryImpl(db.taskDao(), Dispatchers.IO)
        val subTaskRepo = SubTasksRepositoryImpl(db.subTaskDao(), Dispatchers.IO)
        val reminderRepo = RemindersRepositoryImpl(db.reminderDao(), Dispatchers.IO)
        val calRepo = CalendarRepositoryImpl(db.calendarEntryDao(), Dispatchers.IO)
        val calReminderRepo = CalendarRemindersRepositoryImpl(db.calendarReminderDao(), Dispatchers.IO)
        val occurrenceRepo = TaskOccurrenceRepositoryImpl(db.taskOccurrenceDao(), Dispatchers.IO)
        tasksRepo = taskRepo
        calendarRepo = calRepo
        occurrenceRepository = occurrenceRepo
        scheduler = InterlockFakeAlarmScheduler()

        subTaskUseCases = SubTaskUseCases(
            repository = subTaskRepo,
            tasksRepository = taskRepo,
            idGen = { "sub-${idSeq.incrementAndGet()}" },
            now = { clock.last() },
        )
        reminderUseCases = ReminderUseCases(
            repository = reminderRepo,
            tasksRepository = taskRepo,
            scheduler = scheduler,
            idGen = { "rem-${idSeq.incrementAndGet()}" },
            now = { clock.last() },
        )
        calendarReminderUseCases = CalendarReminderUseCases(
            repository = calReminderRepo,
            calendarRepository = calRepo,
            scheduler = scheduler,
            idGen = { "calrem-${idSeq.incrementAndGet()}" },
            now = { clock.last() },
        )
        linkUseCases = TaskScheduleLinkUseCases(
            tasksRepository = taskRepo,
            calendarRepository = calRepo,
            now = { clock.last() },
        )
        calendarUseCases = CalendarUseCases(
            repository = calRepo,
            idGen = { "entry-${idSeq.incrementAndGet()}" },
            now = { clock.last() },
            zoneId = zone.id,
            onTimesChanged = { entryId -> calendarReminderUseCases.rescheduleForStartChange(entryId) },
            onTrashed = { entryId -> calendarReminderUseCases.cancelReminders(entryId) },
        )
        taskUseCases = TaskUseCases(
            repository = taskRepo,
            idGen = { "task-${idSeq.incrementAndGet()}" },
            now = { clock.last() },
            zoneId = zone.id,
            // 与 AppContainer 相同的联动接线：
            onTrashed = { taskId ->
                subTaskUseCases.trashForParent(taskId)
                reminderUseCases.cancelReminders(taskId)
                // T11/#12：删除待办保留日程并解除关联（issue #12 验收）
                linkUseCases.unlinkTask(taskId)
            },
            onDueChanged = { taskId -> reminderUseCases.rescheduleForDueChange(taskId) },
            onCancelled = { taskId -> reminderUseCases.cancelReminders(taskId) },
            // T11/#12：完成待办联动停止其自身全部提醒；撤销完成不动作（不自动恢复）
            onCompleted = { taskId, completed ->
                if (completed) reminderUseCases.cancelReminders(taskId)
            },
            occurrenceRepository = occurrenceRepo,
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
        recurrenceRule: RecurrenceRule? = null,
    ) = taskUseCases.create(
        TaskInput(
            title = title,
            dueAtMillis = dueAtMillis,
            dueEpochDay = dueEpochDay,
            dueZoneId = zone.id,
            recurrenceRule = recurrenceRule,
        ),
    ).getOrThrow()

    private suspend fun createEntry(
        title: String,
        start: Long,
        end: Long = start + 60 * 60_000L,
    ) = calendarUseCases.create(
        CalendarInput(title = title, startMillis = start, endMillis = end, timezoneId = zone.id),
    ).getOrThrow()

    private fun rel(minutes: Long) = ReminderInput(relativeOffsetMillis = minutes * 60_000L)

    private fun abs(date: String, time: String) = ReminderInput(remindAtMillis = millis(date, time))

    private suspend fun reminders(taskId: String) = reminderUseCases.observeByTask(taskId).first()

    private suspend fun entryReminders(entryId: String) =
        calendarReminderUseCases.observeByEntry(entryId).first()

    private suspend fun displays(taskId: String) =
        reminderUseCases.observeDisplaysByTask(taskId).first()

    @Test
    fun 完成待办停止自身全部提醒_保留状态时间戳_关联日程与子任务不受影响() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val task = createTask(dueAtMillis = millis("2026-10-26", "18:00"))
        val other = createTask(title = "对照待办", dueAtMillis = millis("2026-10-26", "19:00"))
        val savedRems = reminderUseCases.setReminders(task.id, listOf(rel(30), abs("2026-10-27", "09:00"))).getOrThrow()
        val otherRem = reminderUseCases.setReminders(other.id, listOf(rel(10))).getOrThrow().single()
        assertEquals(3, scheduler.exactCalls.size)

        // 未来关联日程 + 未完成子任务：完成待办不得隐式处置它们
        val entry = createEntry("装修工期", start = millis("2026-10-26", "14:00"))
        linkUseCases.link(task.id, entry.id).getOrThrow()
        val sub = subTaskUseCases.create(task.id, "选主材").getOrThrow()

        // 完成待办：停止其自身全部提醒，保留真实状态及时间戳
        clock.add(millis("2026-10-25", "10:00"))
        taskUseCases.complete(task.id, completed = true).getOrThrow()

        val done = taskUseCases.get(task.id)!!
        assertEquals(TaskStatus.COMPLETED, done.status)
        assertEquals(millis("2026-10-25", "10:00"), done.completedAt)
        assertEquals(millis("2026-10-25", "10:00"), done.updatedAt)

        // 已关联提醒全部停止：调度器逐条取消 + 记录清除（Fake 调度器断言）
        assertTrue(scheduler.cancelled.containsAll(savedRems.map { it.id }))
        assertTrue(reminders(task.id).isEmpty())

        // 未来关联日程默认保留（正文：完成时提示是否取消，未选择即保留；不隐式取消）
        val kept = linkUseCases.getEntriesForTask(task.id)
        assertEquals(1, kept.size)
        assertNull(kept.single().trashedAt)
        assertEquals(task.id, kept.single().taskId)
        assertNotNull(calendarRepo.getEntry(entry.id))

        // 未完成子任务原状态不变
        val subAfter = subTaskUseCases.get(sub.id)!!
        assertFalse(subAfter.done)
        assertNull(subAfter.trashedAt)

        // 对照待办的提醒不受影响（联动只作用于完成的那条待办）
        assertEquals(listOf(otherRem.id), reminders(other.id).map { it.id })
        assertFalse(scheduler.cancelled.contains(otherRem.id))
    }

    @Test
    fun 撤销完成不自动恢复已停止提醒_经确认重新设置后未来提醒重启过去不补响() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val task = createTask(dueAtMillis = millis("2026-10-26", "18:00"))
        val entry = createEntry("装修工期", start = millis("2026-10-26", "14:00"))
        linkUseCases.link(task.id, entry.id).getOrThrow()
        reminderUseCases.setReminders(task.id, listOf(rel(30))).getOrThrow()

        // 完成：提醒已停止并清除
        clock.add(millis("2026-10-25", "10:00"))
        taskUseCases.complete(task.id, completed = true).getOrThrow()
        assertTrue(reminders(task.id).isEmpty())
        val callsAfterComplete = scheduler.exactCalls.toList()

        // 撤销完成（重新激活已完成待办）：状态回进行中，提醒不自动恢复（不假装恢复）
        clock.add(millis("2026-10-25", "11:00"))
        taskUseCases.complete(task.id, completed = false).getOrThrow()
        val restored = taskUseCases.get(task.id)!!
        assertEquals(TaskStatus.ACTIVE, restored.status)
        assertNull(restored.completedAt)
        // 恢复清单如实可观察：提醒记录为空（需经用户确认重新设置），无任何新调度
        assertTrue(reminders(task.id).isEmpty())
        assertEquals(callsAfterComplete, scheduler.exactCalls)
        // 恢复清单同时列出未来关联日程，供用户一并核对
        assertEquals(1, linkUseCases.futureEntriesForTask(task.id).size)

        // 经确认重启未来提醒：重新设置后未来提醒恢复调度，过去提醒不补响（仅标记过期）
        clock.add(millis("2026-10-25", "12:00"))
        val reSet = reminderUseCases.setReminders(
            task.id,
            listOf(abs("2026-10-25", "08:00"), abs("2026-10-28", "09:00")),
        ).getOrThrow()
        val past = reSet.first { it.remindAtMillis == millis("2026-10-25", "08:00") }
        val future = reSet.first { it.remindAtMillis == millis("2026-10-28", "09:00") }
        assertTrue(scheduler.exactCallsFor(future.id).contains(millis("2026-10-28", "09:00")))
        assertTrue(scheduler.exactCallsFor(past.id).isEmpty())
        assertTrue(displays(task.id).first { it.reminder.id == past.id }.isOverdue)
        assertFalse(displays(task.id).first { it.reminder.id == future.id }.isOverdue)
    }

    @Test
    fun 取消待办重新激活后经确认重启未来提醒过去不补响() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val task = createTask(title="取消后重新激活", dueAtMillis = millis("2026-10-26", "19:00"))
        val saved = reminderUseCases.setReminders(task.id, listOf(rel(20))).getOrThrow()

        // 取消：停止自身提醒、保留业务记录与取消时间戳
        clock.add(millis("2026-10-25", "10:00"))
        taskUseCases.cancel(task.id).getOrThrow()
        val cancelled = taskUseCases.get(task.id)!!
        assertEquals(TaskStatus.CANCELLED, cancelled.status)
        assertEquals(millis("2026-10-25", "10:00"), cancelled.cancelledAt)
        assertNull(cancelled.trashedAt)
        assertTrue(scheduler.cancelled.containsAll(saved.map { it.id }))
        assertTrue(reminders(task.id).isEmpty())

        // 重新激活（经确认设置新截止回到进行中），重新设置提醒后未来恢复调度
        clock.add(millis("2026-10-25", "11:00"))
        taskUseCases.postpone(task.id, newDueEpochDay = null, newDueAtMillis = millis("2026-10-30", "18:00")).getOrThrow()
        assertEquals(TaskStatus.ACTIVE, taskUseCases.get(task.id)!!.status)
        val reSet = reminderUseCases.setReminders(task.id, listOf(abs("2026-10-31", "09:00"))).getOrThrow()
        assertEquals(listOf(millis("2026-10-31", "09:00")), scheduler.exactCallsFor(reSet.single().id))
    }

    @Test
    fun 取消待办保留关联日程待用户处置() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val task = createTask(dueAtMillis = millis("2026-10-26", "18:00"))
        val future = createEntry("未来工期", start = millis("2026-10-26", "14:00"))
        val past = createEntry("已开始工期", start = millis("2026-10-25", "08:00"))
        linkUseCases.link(task.id, future.id).getOrThrow()
        linkUseCases.link(task.id, past.id).getOrThrow()

        // 取消待办：正文要求提示处理未来安排，未确认前 domain 不隐式取消/解除关联
        clock.add(millis("2026-10-25", "10:00"))
        taskUseCases.cancel(task.id).getOrThrow()

        val linked = linkUseCases.getEntriesForTask(task.id)
        assertEquals(2, linked.size)
        linked.forEach { entry ->
            assertNull(entry.trashedAt)
            assertEquals(task.id, entry.taskId)
        }
        // 未来日程清单可观察（供界面提示处置），保留与取消均由用户显式选择
        assertEquals(listOf(future.id), linkUseCases.futureEntriesForTask(task.id).map { it.id })
    }

    @Test
    fun 删除待办保留日程解除关联停止自身提醒_子任务同批入回收站() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val parent = createTask(dueAtMillis = millis("2026-10-26", "18:00"))
        val rems = reminderUseCases.setReminders(parent.id, listOf(rel(30))).getOrThrow()
        val doneSub = subTaskUseCases.create(parent.id, "已完成步骤").getOrThrow()
        subTaskUseCases.setDone(doneSub.id, done = true)
        val pendingSub = subTaskUseCases.create(parent.id, "未完成步骤").getOrThrow()
        val futureEntry = createEntry("未来工期", start = millis("2026-10-26", "14:00"))
        val pastEntry = createEntry("已开始工期", start = millis("2026-10-25", "08:00"))
        linkUseCases.link(parent.id, futureEntry.id).getOrThrow()
        linkUseCases.link(parent.id, pastEntry.id).getOrThrow()

        // 删除（回收站）：提醒停止 + 日程保留 + 解除关联 + 子任务同批
        clock.add(millis("2026-10-25", "10:00"))
        taskUseCases.trash(parent.id).getOrThrow()
        val trashedAt = taskUseCases.get(parent.id)!!.trashedAt
        assertNotNull(trashedAt)

        // 提醒停止：调度器逐条取消 + 记录清除
        assertTrue(scheduler.cancelled.containsAll(rems.map { it.id }))
        assertTrue(reminders(parent.id).isEmpty())

        // 关联日程全部保留且解除关联（正文：删除待办保留日程、解除关联）
        assertNotNull(calendarRepo.getEntry(futureEntry.id))
        assertNotNull(calendarRepo.getEntry(pastEntry.id))
        assertNull(calendarRepo.getEntry(futureEntry.id)!!.trashedAt)
        assertNull(calendarRepo.getEntry(pastEntry.id)!!.taskId)
        assertNull(calendarRepo.getEntry(futureEntry.id)!!.taskId)
        assertTrue(calendarRepo.getEntriesLinkedToTask(parent.id).isEmpty())

        // 子任务同批进入回收站（trashedAt 相同），完成状态原样保留，父子关系不破坏
        assertTrue(subTaskUseCases.observeByParent(parent.id).first().isEmpty())
        val doneAfter = subTaskUseCases.get(doneSub.id)!!
        val pendingAfter = subTaskUseCases.get(pendingSub.id)!!
        assertEquals(trashedAt, doneAfter.trashedAt)
        assertEquals(trashedAt, pendingAfter.trashedAt)
        assertTrue(doneAfter.done)
        assertFalse(pendingAfter.done)
        assertEquals(parent.id, doneAfter.parentId)
    }

    @Test
    fun 父子待办一起进入回收站停止提醒_整体恢复结构保留() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val parent = createTask(title = "父待办", dueAtMillis = millis("2026-10-26", "18:00"))
        val rems = reminderUseCases.setReminders(parent.id, listOf(rel(30))).getOrThrow()
        val sub = subTaskUseCases.create(parent.id, "子步骤").getOrThrow()

        // 前提事实：子任务当前模型没有自己的提醒体系（提醒只挂在待办上），
        // 「停止未完成子任务提醒」在数据层为幂等空操作，父待办提醒覆盖父子场景。
        assertTrue(reminderUseCases.setReminders(sub.id, listOf(rel(10))).isFailure)

        // 父子一起进入回收站：同批 trashedAt，父提醒停止
        clock.add(millis("2026-10-25", "10:00"))
        taskUseCases.trash(parent.id).getOrThrow()
        val trashedAt = taskUseCases.get(parent.id)!!.trashedAt!!
        assertEquals(trashedAt, subTaskUseCases.get(sub.id)!!.trashedAt)
        assertTrue(scheduler.cancelled.containsAll(rems.map { it.id }))
        assertTrue(reminders(parent.id).isEmpty())

        // 整体恢复结构保留：恢复父待办后可还原同批删除的子任务，父子关系完整
        tasksRepo.upsertTask(taskUseCases.get(parent.id)!!.copy(trashedAt = null, updatedAt = clock.last()))
        subTaskUseCases.restoreForParent(parent.id, trashedAt).getOrThrow()
        assertNull(taskUseCases.get(parent.id)!!.trashedAt)
        assertNull(subTaskUseCases.get(sub.id)!!.trashedAt)
        assertEquals(listOf(sub.id), subTaskUseCases.observeByParent(parent.id).first().map { it.id })
    }

    @Test
    fun 删除日程只停止自身提醒_不影响关联待办及其提醒() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val task = createTask(dueAtMillis = millis("2026-10-26", "18:00"))
        val taskRem = reminderUseCases.setReminders(task.id, listOf(rel(30))).getOrThrow().single()
        val entry = createEntry("装修工期", start = millis("2026-10-26", "14:00"))
        linkUseCases.link(task.id, entry.id).getOrThrow()
        val calRem = calendarReminderUseCases.setReminders(entry.id, listOf(rel(60))).getOrThrow().single()
        val taskRemCalls = scheduler.exactCallsFor(taskRem.id).toList()

        // 删除日程：只停止日程自身提醒（cal: 前缀键），待办及其提醒原样保留
        clock.add(millis("2026-10-25", "10:00"))
        calendarUseCases.trash(entry.id).getOrThrow()

        assertTrue(scheduler.cancelled.contains("cal:${calRem.id}"))
        assertTrue(entryReminders(entry.id).isEmpty())
        // 关联待办状态不变、提醒记录与闹钟均不动
        assertEquals(TaskStatus.ACTIVE, taskUseCases.get(task.id)!!.status)
        assertEquals(listOf(taskRem.id), reminders(task.id).map { it.id })
        assertEquals(taskRemCalls, scheduler.exactCallsFor(taskRem.id))
        assertFalse(scheduler.cancelled.contains(taskRem.id))
    }

    @Test
    fun 日程时间调整相对提醒随动独立提醒不动_回归保障() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val task = createTask(dueAtMillis = millis("2026-10-26", "18:00"))
        val taskRem = reminderUseCases.setReminders(task.id, listOf(abs("2026-10-27", "09:00"))).getOrThrow().single()
        val entry = createEntry("装修工期", start = millis("2026-10-26", "14:00"))
        linkUseCases.link(task.id, entry.id).getOrThrow()
        val calRel = calendarReminderUseCases.setReminders(entry.id, listOf(rel(60))).getOrThrow().single()

        // 基线：日程相对提醒按开始时刻调度，待办独立提醒按其自身时刻调度
        assertEquals(listOf(millis("2026-10-26", "13:00")), scheduler.exactCallsFor("cal:${calRel.id}"))
        val taskRemCalls = scheduler.exactCallsFor(taskRem.id).toList()

        // 日程改期：相对提醒随开始时刻移动；关联待办的独立时刻提醒不动
        clock.add(millis("2026-10-25", "10:00"))
        calendarUseCases.update(
            entry.id,
            CalendarInput(
                title = "装修工期",
                startMillis = millis("2026-10-27", "10:00"),
                endMillis = millis("2026-10-27", "11:00"),
                timezoneId = zone.id,
            ),
        ).getOrThrow()
        assertEquals(listOf(millis("2026-10-26", "13:00"), millis("2026-10-27", "09:00")), scheduler.exactCallsFor("cal:${calRel.id}"))
        assertEquals(taskRemCalls, scheduler.exactCallsFor(taskRem.id))
        assertFalse(scheduler.cancelled.contains(taskRem.id))
        // 过期展示提示：待办独立提醒时间已过时明确标记（展示层标记，不静默消失）
        clock.add(millis("2026-10-27", "09:01"))
        assertTrue(displays(task.id).first { it.reminder.id == taskRem.id }.isOverdue)
    }

    @Test
    fun 重复待办完成单次出现不停止本体提醒_每次出现分别记录() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val recurring = createTask(
            title = "每日拉伸",
            dueEpochDay = epochDay("2026-10-26"),
            recurrenceRule = RecurrenceRule(frequency = RecurrenceFrequency.DAILY, intervalDays = 1),
        )
        val rem = reminderUseCases.setReminders(recurring.id, listOf(abs("2026-10-27", "09:00"))).getOrThrow().single()
        assertEquals(1, scheduler.exactCalls.size)

        // 完成作用于「下一次未完成出现」：出现维度记录完成，本体保持进行中
        clock.add(millis("2026-10-25", "10:00"))
        taskUseCases.complete(recurring.id, completed = true).getOrThrow()
        val occurrences = occurrenceRepository.listByTask(recurring.id)
        assertEquals(1, occurrences.size)
        assertTrue(occurrences.single().completed)
        assertEquals(TaskStatus.ACTIVE, taskUseCases.get(recurring.id)!!.status)

        // 提醒仍适用于未来出现：记录与闹钟都不动（不因单次出现完成而停止）
        assertEquals(listOf(rem.id), reminders(recurring.id).map { it.id })
        assertFalse(scheduler.cancelled.contains(rem.id))
    }

    @Test
    fun 应用入口联测父子关联双方及提醒持久状态_重开保持() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val parent = createTask(title = "装修新房", dueAtMillis = millis("2026-10-26", "18:00"))
        val other = createTask(title = "对照待办", dueAtMillis = millis("2026-10-26", "19:00"))
        val parentRems = reminderUseCases.setReminders(parent.id, listOf(rel(30))).getOrThrow()
        val otherRem = reminderUseCases.setReminders(other.id, listOf(abs("2026-10-27", "09:00"))).getOrThrow().single()
        val sub = subTaskUseCases.create(parent.id, "选主材").getOrThrow()
        val entry = createEntry("装修工期", start = millis("2026-10-26", "14:00"))
        linkUseCases.link(parent.id, entry.id).getOrThrow()
        val calRem = calendarReminderUseCases.setReminders(entry.id, listOf(rel(60))).getOrThrow().single()

        // 完成：自身提醒停止，其余各方不动（未确认级联不发生）
        clock.add(millis("2026-10-25", "10:00"))
        taskUseCases.complete(parent.id, completed = true).getOrThrow()
        assertTrue(reminders(parent.id).isEmpty())
        assertEquals(listOf(otherRem.id), reminders(other.id).map { it.id })
        assertEquals(listOf("cal:${calRem.id}"), entryReminders(entry.id).map { it.schedulerKey() })
        assertFalse(subTaskUseCases.get(sub.id)!!.done)

        // 删除（回收站）：解除关联（日程与日程提醒保留）、子任务同批、自身提醒保持空
        clock.add(millis("2026-10-25", "11:00"))
        taskUseCases.trash(parent.id).getOrThrow()
        assertNull(calendarRepo.getEntry(entry.id)!!.taskId)
        assertNull(calendarRepo.getEntry(entry.id)!!.trashedAt)
        assertEquals(1, entryReminders(entry.id).size)
        assertTrue(calendarRepo.getEntriesLinkedToTask(parent.id).isEmpty())
        assertNotNull(taskUseCases.get(parent.id)!!.trashedAt)

        // close-reopen：联动结果全部真实持久
        db.close()
        open()
        assertTrue(reminders(parent.id).isEmpty())
        assertNotNull(taskUseCases.get(parent.id)!!.trashedAt)
        assertEquals(1, entryReminders(entry.id).size)
        assertNull(calendarRepo.getEntry(entry.id)!!.taskId)
        assertNotNull(subTaskUseCases.get(sub.id)!!.trashedAt)
        assertEquals(listOf(otherRem.id), reminders(other.id).map { it.id })
    }
}

/** 记录调用的调度器替身：可切换精确闹钟能力，供联动测试断言每次 schedule/cancel。 */
private class InterlockFakeAlarmScheduler : AlarmScheduler {

    var exactAllowed: Boolean = true
    private var scheduleResult: Boolean = true

    val exactCalls = mutableListOf<Pair<String, Long>>()
    val inexactCalls = mutableListOf<Pair<String, Long>>()
    val cancelled = mutableListOf<String>()

    override fun canScheduleExact(): Boolean = exactAllowed

    override suspend fun scheduleExact(
        reminderId: String,
        triggerAtMillis: Long,
        taskId: String,
        title: String,
    ): Boolean {
        exactCalls += reminderId to triggerAtMillis
        return scheduleResult
    }

    override suspend fun scheduleInexact(
        reminderId: String,
        triggerAtMillis: Long,
        taskId: String,
        title: String,
    ): Boolean {
        inexactCalls += reminderId to triggerAtMillis
        return scheduleResult
    }

    override suspend fun cancel(reminderId: String) {
        cancelled += reminderId
    }

    fun exactCallsFor(reminderId: String): List<Long> =
        exactCalls.filter { it.first == reminderId }.map { it.second }
}
