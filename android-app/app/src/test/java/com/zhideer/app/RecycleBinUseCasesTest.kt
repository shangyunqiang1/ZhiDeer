package com.zhideer.app

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.zhideer.app.data.ai.RepositoryAiContextProvider
import com.zhideer.app.data.db.ZhiDeerDatabase
import com.zhideer.app.data.repository.CalendarRemindersRepositoryImpl
import com.zhideer.app.data.repository.CalendarRepositoryImpl
import com.zhideer.app.data.repository.NotesRepositoryImpl
import com.zhideer.app.data.repository.RemindersRepositoryImpl
import com.zhideer.app.data.repository.SubTasksRepositoryImpl
import com.zhideer.app.data.repository.TaskOccurrenceRepositoryImpl
import com.zhideer.app.data.repository.TasksRepositoryImpl
import com.zhideer.app.domain.model.CalendarEntry
import com.zhideer.app.domain.model.Note
import com.zhideer.app.domain.model.RecurrenceFrequency
import com.zhideer.app.domain.model.RecurrenceRule
import com.zhideer.app.domain.model.TaskFilter
import com.zhideer.app.domain.model.TodoTask
import com.zhideer.app.domain.usecase.AiContextCategory
import com.zhideer.app.domain.usecase.AiContextGrant
import com.zhideer.app.domain.usecase.CalendarInput
import com.zhideer.app.domain.usecase.CalendarReminderUseCases
import com.zhideer.app.domain.usecase.CalendarUseCases
import com.zhideer.app.domain.usecase.NoteInput
import com.zhideer.app.domain.usecase.NoteListItemInput
import com.zhideer.app.domain.usecase.NoteUseCases
import com.zhideer.app.domain.usecase.RecycleBinKind
import com.zhideer.app.domain.usecase.RecycleBinUseCases
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
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger

/**
 * T16/#17 验收测试：回收站的查看、恢复与彻底删除。
 * - 四类普通事项（待办/日程/便签/子任务）统一观察：原记录与删除时间，按删除时间倒序；
 * - 恢复回到正常查询：父子整体恢复（同批）、恢复后提醒不复活（错过提醒只提示不补响）、
 *   已解除的日程关联默认不自动重连（恢复结果明确提示，语义在此固定）；
 * - 彻底删除级联清理从属数据（子任务/清单项/提醒/occurrence/关联残留），
 *   四类普通事项之外的数据不误清理（本工程暂无记忆存储）；
 * - 三十天到期自动清理：29/30/31 天边界、时区无关的绝对毫秒判定、幂等；
 * - 回收站期间不进入普通搜索与 AI 上下文快照（防御性断言生产 provider）。
 * 全部走真实 Room 文件库 + 统一用例入口 + Fake 调度器，固定时钟注入。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RecycleBinUseCasesTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private lateinit var dbFile: File
    private lateinit var db: ZhiDeerDatabase
    private lateinit var scheduler: RecycleBinFakeAlarmScheduler
    private lateinit var taskUseCases: TaskUseCases
    private lateinit var subTaskUseCases: SubTaskUseCases
    private lateinit var calendarUseCases: CalendarUseCases
    private lateinit var noteUseCases: NoteUseCases
    private lateinit var reminderUseCases: ReminderUseCases
    private lateinit var calendarReminderUseCases: CalendarReminderUseCases
    private lateinit var linkUseCases: TaskScheduleLinkUseCases
    private lateinit var recycleBinUseCases: RecycleBinUseCases
    private lateinit var aiProvider: RepositoryAiContextProvider
    private lateinit var occurrenceRepo: TaskOccurrenceRepositoryImpl
    private val clock = mutableListOf(0L)
    private val idSeq = AtomicInteger(0)

    private fun millis(date: String, time: String = "10:00", zoneId: ZoneId = zone): Long =
        LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(zoneId).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dbFile = context.getDatabasePath("t16_test.db")
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
        val subTaskRepo = SubTasksRepositoryImpl(db.subTaskDao(), Dispatchers.IO)
        val calendarRepo = CalendarRepositoryImpl(db.calendarEntryDao(), Dispatchers.IO)
        val noteRepo = NotesRepositoryImpl(db.noteDao(), Dispatchers.IO)
        scheduler = RecycleBinFakeAlarmScheduler()
        reminderUseCases = ReminderUseCases(
            repository = RemindersRepositoryImpl(db.reminderDao(), Dispatchers.IO),
            tasksRepository = taskRepo,
            scheduler = scheduler,
            idGen = { "rem-${idSeq.incrementAndGet()}" },
            now = { clock.last() },
        )
        calendarReminderUseCases = CalendarReminderUseCases(
            repository = CalendarRemindersRepositoryImpl(db.calendarReminderDao(), Dispatchers.IO),
            calendarRepository = calendarRepo,
            scheduler = scheduler,
            idGen = { "crem-${idSeq.incrementAndGet()}" },
            now = { clock.last() },
        )
        subTaskUseCases = SubTaskUseCases(subTaskRepo, taskRepo, { "sub-${idSeq.incrementAndGet()}" }, { clock.last() })
        linkUseCases = TaskScheduleLinkUseCases(taskRepo, calendarRepo, { clock.last() })
        occurrenceRepo = TaskOccurrenceRepositoryImpl(db.taskOccurrenceDao(), Dispatchers.IO)
        taskUseCases = TaskUseCases(
            repository = taskRepo,
            idGen = { "task-${idSeq.incrementAndGet()}" },
            now = { clock.last() },
            zoneId = zone.id,
            // 与 AppContainer 相同的组装：父待办进入回收站联动子任务、停止提醒、解除日程关联
            onTrashed = { taskId ->
                subTaskUseCases.trashForParent(taskId)
                reminderUseCases.cancelReminders(taskId)
                linkUseCases.unlinkTask(taskId)
            },
            onDueChanged = { taskId -> reminderUseCases.rescheduleForDueChange(taskId) },
            onCancelled = { taskId -> reminderUseCases.cancelReminders(taskId) },
            onCompleted = { taskId, completed -> if (completed) reminderUseCases.cancelReminders(taskId) },
            occurrenceRepository = occurrenceRepo,
        )
        calendarUseCases = CalendarUseCases(
            repository = calendarRepo,
            idGen = { "entry-${idSeq.incrementAndGet()}" },
            now = { clock.last() },
            zoneId = zone.id,
            onTrashed = { entryId -> calendarReminderUseCases.cancelReminders(entryId) },
        )
        noteUseCases = NoteUseCases(noteRepo, { "note-${idSeq.incrementAndGet()}" }, { clock.last() })
        aiProvider = RepositoryAiContextProvider(taskRepo, calendarRepo, noteRepo, zone, { clock.last() })
        recycleBinUseCases = RecycleBinUseCases(
            tasksRepository = taskRepo,
            subTasksRepository = subTaskRepo,
            calendarRepository = calendarRepo,
            notesRepository = noteRepo,
            taskOccurrenceRepository = occurrenceRepo,
            reminderUseCases = reminderUseCases,
            calendarReminderUseCases = calendarReminderUseCases,
            taskScheduleLinkUseCases = linkUseCases,
            now = { clock.last() },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------- 工具 ----------

    private suspend fun createTask(title: String = "装修新房"): TodoTask =
        taskUseCases.create(TaskInput(title = title, dueZoneId = zone.id)).getOrThrow()

    private suspend fun createEntry(title: String = "周会"): CalendarEntry =
        calendarUseCases.create(
            CalendarInput(
                title = title,
                startMillis = millis("2026-10-26", "10:00"),
                endMillis = millis("2026-10-26", "11:00"),
                timezoneId = zone.id,
            ),
        ).getOrThrow()

    private suspend fun createNote(title: String = "购物清单"): Note =
        noteUseCases.create(
            NoteInput(
                title = title,
                content = "周末采购",
                items = listOf(
                    NoteListItemInput(text = "牛奶"),
                    NoteListItemInput(text = "面包"),
                ),
            ),
        ).getOrThrow()

    private suspend fun trashedItems() = recycleBinUseCases.observeAll().first()

    // ---------- 观察 ----------

    @Test
    fun 四类事项回收站统一观察含删除时间且按删除时间倒序() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val task = createTask()
        val entry = createEntry()
        val note = createNote()
        clock.add(millis("2026-10-25", "09:10"))
        val sub = subTaskUseCases.create(task.id, "量尺寸").getOrThrow()

        // 依次进入回收站，删除时间各不相同
        clock.add(millis("2026-10-25", "10:00"))
        subTaskUseCases.delete(sub.id).getOrThrow()
        clock.add(millis("2026-10-25", "11:00"))
        calendarUseCases.trash(entry.id).getOrThrow()
        clock.add(millis("2026-10-25", "12:00"))
        noteUseCases.trash(note.id).getOrThrow()
        clock.add(millis("2026-10-25", "13:00"))
        taskUseCases.trash(task.id).getOrThrow()

        val items = trashedItems()
        assertEquals(4, items.size)
        // 按删除时间倒序：最近删除的在前
        assertEquals(
            listOf("装修新房", "购物清单", "周会", "量尺寸"),
            items.map { it.title },
        )
        assertTrue(items.zipWithNext().all { (a, b) -> a.trashedAt >= b.trashedAt })
        assertEquals(
            listOf(
                RecycleBinKind.TASK to millis("2026-10-25", "13:00"),
                RecycleBinKind.NOTE to millis("2026-10-25", "12:00"),
                RecycleBinKind.SCHEDULE to millis("2026-10-25", "11:00"),
                RecycleBinKind.SUB_TASK to millis("2026-10-25", "10:00"),
            ),
            items.map { it.kind to it.trashedAt },
        )
        // 展示原记录信息而非匿名占位
        assertTrue(items.all { it.title.isNotBlank() })
    }

    @Test
    fun 正常查询与搜索始终不含回收站内容恢复后重新出现() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val task = createTask(title="装修新房")
        val entry = createEntry(title = "独特日程名")
        val note = createNote(title = "独特便签名")
        clock.add(millis("2026-10-25", "10:00"))
        val sub = subTaskUseCases.create(task.id, "独特步骤名").getOrThrow()

        clock.add(millis("2026-10-25", "11:00"))
        subTaskUseCases.delete(sub.id).getOrThrow()
        calendarUseCases.trash(entry.id).getOrThrow()
        noteUseCases.trash(note.id).getOrThrow()
        taskUseCases.trash(task.id).getOrThrow()

        // 回收站期间：普通查询与搜索全部排除
        assertTrue(taskUseCases.observe(TaskFilter.ALL).first().isEmpty())
        assertTrue(calendarUseCases.observeAll().first().isEmpty())
        assertTrue(noteUseCases.search("独特便签名").first().isEmpty())
        assertTrue(subTaskUseCases.observeByParent(task.id).first().isEmpty())

        // 恢复后回到正常查询
        clock.add(millis("2026-10-25", "12:00"))
        recycleBinUseCases.restoreTask(task.id).getOrThrow()
        recycleBinUseCases.restoreEntry(entry.id).getOrThrow()
        recycleBinUseCases.restoreNote(note.id).getOrThrow()

        assertEquals(listOf("装修新房"), taskUseCases.observe(TaskFilter.ALL).first().map { it.title })
        assertEquals(listOf("独特日程名"), calendarUseCases.observeAll().first().map { it.title })
        assertEquals(listOf("独特便签名"), noteUseCases.search("独特便签名").first().map { it.title })
    }

    // ---------- 恢复 ----------

    @Test
    fun 恢复待办同批恢复子任务且回到正常查询() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val parent = createTask()
        clock.add(millis("2026-10-25", "09:10"))
        val a = subTaskUseCases.create(parent.id, "量尺寸").getOrThrow()
        val b = subTaskUseCases.create(parent.id, "买材料").getOrThrow()
        clock.add(millis("2026-10-25", "09:20"))
        subTaskUseCases.setDone(a.id, done = true).getOrThrow()

        clock.add(millis("2026-10-25", "10:00"))
        taskUseCases.trash(parent.id).getOrThrow()
        assertTrue(subTaskUseCases.observeByParent(parent.id).first().isEmpty())

        clock.add(millis("2026-10-25", "11:00"))
        val result = recycleBinUseCases.restoreTask(parent.id).getOrThrow()

        assertEquals(RecycleBinKind.TASK, result.kind)
        assertEquals(2, result.restoredSubTaskCount)
        // 父子整体恢复，完成状态等业务数据原样保留
        assertEquals(listOf("装修新房"), taskUseCases.observe(TaskFilter.ALL).first().map { it.title })
        assertEquals(listOf("量尺寸", "买材料"), subTaskUseCases.observeByParent(parent.id).first().map { it.title })
        assertTrue(subTaskUseCases.get(a.id)!!.done)
        assertNull(subTaskUseCases.get(a.id)!!.trashedAt)
        assertNull(subTaskUseCases.get(b.id)!!.trashedAt)
        assertNull(taskUseCases.get(parent.id)!!.trashedAt)

        // 重开数据库后恢复结果仍在
        db.close()
        open()
        assertEquals(listOf("量尺寸", "买材料"), subTaskUseCases.observeByParent(parent.id).first().map { it.title })
    }

    @Test
    fun 恢复待办不自动重连已解除的日程关联且明确提示() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val task = createTask()
        val entry = createEntry(title = "处理装修的时段")
        linkUseCases.link(task.id, entry.id).getOrThrow()

        clock.add(millis("2026-10-25", "10:00"))
        taskUseCases.trash(task.id).getOrThrow()
        // trash 联动已解除关联，日程记录保留在正常查询
        assertNull(calendarUseCases.get(entry.id)!!.taskId)

        clock.add(millis("2026-10-25", "11:00"))
        val result = recycleBinUseCases.restoreTask(task.id).getOrThrow()

        // 恢复策略（本测试固定语义）：默认不自动重连已解除的日程关联，避免复连已删除或已改期的日程
        assertNull(calendarUseCases.get(entry.id)!!.taskId)
        // 恢复结果明确提示原关联已解除，可在界面重新关联
        assertTrue(result.message.contains("原日程关联已解除"))
        assertTrue(result.message.contains("重新关联"))
    }

    @Test
    fun 恢复待办后提醒不复活重新设置时错过不补响未来按权限处理() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val task = createTask()
        clock.add(millis("2026-10-25", "09:10"))
        reminderUseCases.setReminders(
            task.id,
            listOf(ReminderInput(remindAtMillis = millis("2026-10-26", "09:00"))),
        ).getOrThrow()

        clock.add(millis("2026-10-25", "10:00"))
        taskUseCases.trash(task.id).getOrThrow()
        // trash 联动已停止提醒（取消闹钟并清除记录）
        assertTrue(reminderUseCases.observeByTask(task.id).first().isEmpty())

        clock.add(millis("2026-10-25", "11:00"))
        val result = recycleBinUseCases.restoreTask(task.id).getOrThrow()
        // 恢复策略：提醒不假装复活（记录已随删除停止），恢复结果明确提示需重新设置
        assertTrue(reminderUseCases.observeByTask(task.id).first().isEmpty())
        assertTrue(result.message.contains("提醒"))

        // 恢复后重新设置：错过（已过去）的提醒不补响，未来的按权限状态处理
        clock.add(millis("2026-10-26", "12:00"))
        val reminders = reminderUseCases.setReminders(
            task.id,
            listOf(
                ReminderInput(remindAtMillis = millis("2026-10-26", "09:00")), // 已错过
                ReminderInput(remindAtMillis = millis("2026-10-27", "09:00")), // 未来
            ),
        ).getOrThrow()
        val missed = reminders.first { it.remindAtMillis == millis("2026-10-26", "09:00") }
        val future = reminders.first { it.remindAtMillis == millis("2026-10-27", "09:00") }
        // 错过提醒只提示不补响：不向系统调度，仅展示层标记过期
        assertTrue(scheduler.exactCalls.none { it.first == missed.id })
        assertTrue(scheduler.exactCalls.any { it.first == future.id })
        val display = reminderUseCases.observeDisplaysByTask(task.id).first()
            .first { it.reminder.id == missed.id }
        assertTrue(display.isOverdue)

        // 未来提醒在精确闹钟权限未授予时保持未就绪标记，不悄悄降级
        scheduler.exactAllowed = false
        clock.add(millis("2026-10-26", "13:00"))
        val repaired = reminderUseCases.repairNotReady(task.id).getOrThrow()
        val notReady = repaired.first { it.id == future.id }
        assertEquals(
            ReminderUseCases.NOT_READY_EXACT_ALARM,
            notReady.notReadyReason,
        )
    }

    @Test
    fun 恢复日程与便签回到正常查询且清单项完整() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val entry = createEntry()
        val note = createNote()

        clock.add(millis("2026-10-25", "10:00"))
        calendarUseCases.trash(entry.id).getOrThrow()
        noteUseCases.trash(note.id).getOrThrow()
        assertTrue(calendarUseCases.observeAll().first().isEmpty())

        clock.add(millis("2026-10-25", "11:00"))
        val entryResult = recycleBinUseCases.restoreEntry(entry.id).getOrThrow()
        val noteResult = recycleBinUseCases.restoreNote(note.id).getOrThrow()
        assertEquals(RecycleBinKind.SCHEDULE, entryResult.kind)
        assertEquals(RecycleBinKind.NOTE, noteResult.kind)

        val restoredEntry = calendarUseCases.get(entry.id)!!
        assertNull(restoredEntry.trashedAt)
        assertEquals(millis("2026-10-26", "10:00"), restoredEntry.startMillis)
        val restoredNote = noteUseCases.get(note.id)!!
        assertNull(restoredNote.trashedAt)
        assertEquals(listOf("牛奶", "面包"), restoredNote.items.map { it.text })
        // 便签清单项勾选状态保留
        assertTrue(restoredNote.items.all { !it.checked })
    }

    @Test
    fun 恢复子任务父待办正常时单独恢复父待办在回收站时随父整体恢复() = runBlocking {
        // 场景 A：父待办正常，子任务单独删除后可单独恢复
        clock.add(millis("2026-10-25", "09:00"))
        val parentA = createTask(title="父待办A")
        clock.add(millis("2026-10-25", "09:10"))
        val subA = subTaskUseCases.create(parentA.id, "步骤A").getOrThrow()
        clock.add(millis("2026-10-25", "10:00"))
        subTaskUseCases.delete(subA.id).getOrThrow()

        clock.add(millis("2026-10-25", "11:00"))
        val resultA = recycleBinUseCases.restoreSubTask(subA.id).getOrThrow()
        assertEquals(RecycleBinKind.SUB_TASK, resultA.kind)
        assertEquals(listOf("步骤A"), subTaskUseCases.observeByParent(parentA.id).first().map { it.title })
        assertNull(taskUseCases.get(parentA.id)!!.trashedAt)
        assertFalse(resultA.restoredWithParent)

        // 场景 B：父待办在回收站时恢复子任务，随父待办整体恢复（父子一致，不出现父在回收站而子在外）
        clock.add(millis("2026-10-25", "12:00"))
        val parentB = createTask(title="父待办B")
        clock.add(millis("2026-10-25", "12:10"))
        val subB = subTaskUseCases.create(parentB.id, "步骤B").getOrThrow()
        clock.add(millis("2026-10-25", "13:00"))
        taskUseCases.trash(parentB.id).getOrThrow()

        clock.add(millis("2026-10-25", "14:00"))
        val resultB = recycleBinUseCases.restoreSubTask(subB.id).getOrThrow()
        assertTrue(resultB.restoredWithParent)
        assertTrue(resultB.message.contains("整体恢复"))
        assertNull(taskUseCases.get(parentB.id)!!.trashedAt)
        assertEquals(listOf("步骤B"), subTaskUseCases.observeByParent(parentB.id).first().map { it.title })

        // 场景 C：父待办恢复只还原同批删除的子任务，不同批（单独删除）的子任务留在回收站
        clock.add(millis("2026-10-25", "15:00"))
        val parentC = createTask(title="父待办C")
        clock.add(millis("2026-10-25", "15:10"))
        val subC1 = subTaskUseCases.create(parentC.id, "同批步骤").getOrThrow()
        clock.add(millis("2026-10-25", "15:20"))
        val subC2 = subTaskUseCases.create(parentC.id, "单独删除步骤").getOrThrow()
        clock.add(millis("2026-10-25", "15:30"))
        subTaskUseCases.delete(subC2.id).getOrThrow()
        clock.add(millis("2026-10-25", "16:00"))
        taskUseCases.trash(parentC.id).getOrThrow()
        clock.add(millis("2026-10-25", "17:00"))
        recycleBinUseCases.restoreTask(parentC.id).getOrThrow()
        assertEquals(listOf("同批步骤"), subTaskUseCases.observeByParent(parentC.id).first().map { it.title })
        assertNotNull(subTaskUseCases.get(subC2.id)!!.trashedAt)
    }

    @Test
    fun 恢复不存在或未删除记录返回明确结果() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val task = createTask()
        val entry = createEntry()
        val note = createNote()

        val missingTask = recycleBinUseCases.restoreTask("no-such-task")
        assertTrue(missingTask.isFailure)
        assertEquals("待办不存在或已永久删除", missingTask.exceptionOrNull()!!.message)

        val missingEntry = recycleBinUseCases.restoreEntry("no-such-entry")
        assertTrue(missingEntry.isFailure)
        assertEquals("日程不存在或已永久删除", missingEntry.exceptionOrNull()!!.message)

        val missingNote = recycleBinUseCases.restoreNote("no-such-note")
        assertTrue(missingNote.isFailure)
        assertEquals("便签不存在或已永久删除", missingNote.exceptionOrNull()!!.message)

        val missingSub = recycleBinUseCases.restoreSubTask("no-such-sub")
        assertTrue(missingSub.isFailure)
        assertEquals("子任务不存在", missingSub.exceptionOrNull()!!.message)

        // 未在回收站中的记录：恢复为幂等无操作
        assertTrue(recycleBinUseCases.restoreTask(task.id).isSuccess)
        assertTrue(recycleBinUseCases.restoreEntry(entry.id).isSuccess)
        assertTrue(recycleBinUseCases.restoreNote(note.id).isSuccess)
    }

    // ---------- 彻底删除 ----------

    @Test
    fun 彻底删除待办级联清理子任务提醒出现记录与关联残留() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        // 被彻底删除对象：重复待办（已完成一次出现）+ 子任务 + 提醒 + 关联日程
        val recurring = taskUseCases.create(
            TaskInput(
                title = "每天锻炼",
                dueEpochDay = LocalDate.parse("2026-10-26").toEpochDay(),
                dueZoneId = zone.id,
                recurrenceRule = RecurrenceRule(frequency = RecurrenceFrequency.DAILY, intervalDays = 1),
            ),
        ).getOrThrow()
        clock.add(millis("2026-10-25", "09:05"))
        taskUseCases.complete(recurring.id, completed = true).getOrThrow()
        assertTrue(occurrenceRepo.listByTask(recurring.id).isNotEmpty())

        clock.add(millis("2026-10-25", "09:10"))
        val sub = subTaskUseCases.create(recurring.id, "热身").getOrThrow()
        reminderUseCases.setReminders(
            recurring.id,
            listOf(ReminderInput(remindAtMillis = millis("2026-10-26", "09:00"))),
        ).getOrThrow()
        val entry = createEntry(title = "关联日程")
        linkUseCases.link(recurring.id, entry.id).getOrThrow()
        // 另一条无关待办与其提醒，用于验证不误清理
        val other = createTask(title="无关待办")
        reminderUseCases.setReminders(
            other.id,
            listOf(ReminderInput(remindAtMillis = millis("2026-10-28", "09:00"))),
        ).getOrThrow()

        clock.add(millis("2026-10-25", "10:00"))
        taskUseCases.trash(recurring.id).getOrThrow()
        clock.add(millis("2026-10-25", "11:00"))
        recycleBinUseCases.purgeTask(recurring.id).getOrThrow()

        // 物理删除：连回收站读取也不存在
        assertNull(taskUseCases.get(recurring.id))
        assertNull(subTaskUseCases.get(sub.id))
        assertTrue(reminderUseCases.observeByTask(recurring.id).first().isEmpty())
        // 出现完成记录一并清理（回收站期间则保留历史不动）
        assertTrue(occurrenceRepo.listByTask(recurring.id).isEmpty())
        // 关联残留清理：日程记录保留但不再指向已删除待办
        assertNull(calendarUseCases.get(entry.id)!!.taskId)
        assertEquals("关联日程", calendarUseCases.get(entry.id)!!.title)

        // 四类普通事项之外的数据不误清理：无关待办及其提醒、关联日程记录全部保留
        assertNotNull(taskUseCases.get(other.id))
        assertEquals(1, reminderUseCases.observeByTask(other.id).first().size)

        // 重复彻底删除：记录已不存在，返回失败（中文可诊断）
        val again = recycleBinUseCases.purgeTask(recurring.id)
        assertTrue(again.isFailure)
        assertEquals("待办不存在或已永久删除", again.exceptionOrNull()!!.message)
    }

    @Test
    fun 彻底删除日程便签子任务级联清理从属数据() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val entry = createEntry(title="有提醒的日程")
        calendarReminderUseCases.setReminders(
            entry.id,
            listOf(ReminderInput(remindAtMillis = millis("2026-10-26", "09:30"))),
        ).getOrThrow()
        val note = createNote(title="有清单的便签")
        val sub = subTaskUseCases.create(createTask(title="宿主待办").id, "独立删除的步骤").getOrThrow()

        clock.add(millis("2026-10-25", "10:00"))
        calendarUseCases.trash(entry.id).getOrThrow()
        noteUseCases.trash(note.id).getOrThrow()
        subTaskUseCases.delete(sub.id).getOrThrow()

        clock.add(millis("2026-10-25", "11:00"))
        recycleBinUseCases.purgeEntry(entry.id).getOrThrow()
        recycleBinUseCases.purgeNote(note.id).getOrThrow()
        recycleBinUseCases.purgeSubTask(sub.id).getOrThrow()

        // 日程及其提醒全部物理删除
        assertNull(calendarUseCases.get(entry.id))
        assertTrue(db.calendarReminderDao().listByEntry(entry.id).isEmpty())
        // 便签及其清单项全部物理删除
        assertNull(noteUseCases.get(note.id))
        assertEquals(0, db.noteDao().countItemsForNote(note.id))
        // 子任务物理删除；宿主待办不受影响
        assertNull(subTaskUseCases.get(sub.id))
        assertNotNull(taskUseCases.get(sub.parentId))

        // 已彻底删除的记录不能再恢复
        assertTrue(recycleBinUseCases.restoreEntry(entry.id).isFailure)
        assertTrue(recycleBinUseCases.restoreNote(note.id).isFailure)
        assertTrue(recycleBinUseCases.restoreSubTask(sub.id).isFailure)
    }

    // ---------- 三十天到期自动清理 ----------

    @Test
    fun 三十天到期清理29天保留30与31天清理() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val t29 = createTask(title="二十九天")
        val t30 = createTask(title="三十天")
        val t31 = createTask(title="三十一天")
        val n29 = createNote(title="便签二十九天")
        val n30 = createNote(title="便签三十天")
        val e29 = createEntry(title="日程二十九天")
        val e30 = createEntry(title="日程三十天")
        val subOf29 = subTaskUseCases.create(t29.id, "未到期步骤").getOrThrow()
        val subOf30 = subTaskUseCases.create(t30.id, "已到期步骤").getOrThrow()

        val base = millis("2026-11-24", "09:00") // 判定时刻 T
        // trashedAt 精确构造：T-29d / T-30d / T-31d
        tasksRepositoryDirect().trashTask(t29.id, base - 29 * DAY)
        tasksRepositoryDirect().trashTask(t30.id, base - 30 * DAY)
        tasksRepositoryDirect().trashTask(t31.id, base - 31 * DAY)
        notesRepositoryDirect().trashNote(n29.id, base - 29 * DAY)
        notesRepositoryDirect().trashNote(n30.id, base - 30 * DAY)
        calendarRepositoryDirect().trashEntry(e29.id, base - 29 * DAY)
        calendarRepositoryDirect().trashEntry(e30.id, base - 30 * DAY)
        subTasksRepositoryDirect().trash(subOf29.id, base - 29 * DAY)
        subTasksRepositoryDirect().trash(subOf30.id, base - 30 * DAY)
        // 父待办（未到期/已到期）不在本次断言路径内：子任务不随父同批（单独构造删除时间）
        clock.add(base)
        val result = recycleBinUseCases.purgeExpired(base - 30 * DAY)

        assertEquals(2, result.purgedTasks) // t30、t31
        assertEquals(1, result.purgedNotes)
        assertEquals(1, result.purgedEntries)
        assertEquals(1, result.purgedSubTasks)

        // 29 天整：保留（不足三十天不清）
        assertNotNull(taskUseCases.get(t29.id))
        assertNotNull(noteUseCases.get(n29.id))
        assertNotNull(calendarUseCases.get(e29.id))
        assertNotNull(subTaskUseCases.get(subOf29.id))
        // 30 天整到期即清理；31 天已过期清理
        assertNull(taskUseCases.get(t30.id))
        assertNull(taskUseCases.get(t31.id))
        assertNull(noteUseCases.get(n30.id))
        assertNull(calendarUseCases.get(e30.id))
        assertNull(subTaskUseCases.get(subOf30.id))
    }

    @Test
    fun 到期清理按绝对毫秒判定与时区无关() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val task = createTask(title="时区边界待办")
        // trashedAt 用 UTC+14（太平洋基里蒂马）的挂钟表达生成，cutoff 用 UTC 生成：
        // 判定只依赖绝对毫秒，与表达时区无关
        val kiritimati = ZoneId.of("Pacific/Kiritimati")
        val trashedAt: Long = ZonedDateTime.of(2026, 9, 24, 12, 0, 0, 0, kiritimati).toInstant().toEpochMilli()
        tasksRepositoryDirect().trashTask(task.id, trashedAt)

        // 该绝对时刻恰好在 cutoff（T-30d）之前 1 毫秒：31 天边界附近仍被清理
        val utc = ZoneId.of("UTC")
        val nowMillis: Long = ZonedDateTime.of(2026, 10, 24, 22, 0, 0, 0, utc).toInstant().toEpochMilli() + 1
        clock.add(nowMillis)
        val cutoff: Long = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMillis), utc)
            .minusDays(30).toInstant().toEpochMilli()
        val result = recycleBinUseCases.purgeExpired(cutoff)
        assertEquals(1, result.purgedTasks)
        assertNull(taskUseCases.get(task.id))

        // 比 cutoff 晚 1 毫秒删除的记录必须保留
        val task2 = createTask(title="边界内待办")
        tasksRepositoryDirect().trashTask(task2.id, cutoff + 1)
        val result2 = recycleBinUseCases.purgeExpired(cutoff)
        assertEquals(0, result2.purgedTasks)
        assertNotNull(taskUseCases.get(task2.id))
    }

    @Test
    fun 到期清理幂等且不触碰未到期与未删除数据() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val expired = createTask(title="已到期待办")
        val keep = createTask(title="未到期待办")
        val alive = createTask(title="未删除待办")
        reminderUseCases.setReminders(
            alive.id,
            listOf(ReminderInput(remindAtMillis = millis("2026-11-01", "09:00"))),
        ).getOrThrow()
        val expiredSub = subTaskUseCases.create(expired.id, "到期步骤").getOrThrow()
        val keepSub = subTaskUseCases.create(keep.id, "保留步骤").getOrThrow()

        val base = millis("2026-11-24", "09:00")
        tasksRepositoryDirect().trashTask(expired.id, base - 30 * DAY)
        tasksRepositoryDirect().trashTask(keep.id, base - 29 * DAY)
        subTasksRepositoryDirect().trash(expiredSub.id, base - 30 * DAY)
        clock.add(base)

        val first = recycleBinUseCases.purgeExpired(base - 30 * DAY)
        assertEquals(1, first.purgedTasks)
        assertEquals(1, first.purgedSubTasks)

        // 幂等：第二次清理无任何动作
        val second = recycleBinUseCases.purgeExpired(base - 30 * DAY)
        assertEquals(0, second.purgedTasks)
        assertEquals(0, second.purgedSubTasks)
        assertEquals(0, second.purgedEntries)
        assertEquals(0, second.purgedNotes)

        // 未到期的回收站记录与未删除记录及其从属数据全部保留
        assertNotNull(taskUseCases.get(keep.id))
        assertEquals("保留步骤", subTaskUseCases.get(keepSub.id)!!.title)
        assertNotNull(taskUseCases.get(alive.id))
        assertEquals(1, reminderUseCases.observeByTask(alive.id).first().size)
        // 记忆和隐私数据不套用三十天规则：本工程无记忆存储，三十天清理只作用于四类普通事项
        // （数据库中不存在第四类之外的清理目标，未删除的提醒记录原样保留已如上断言）。
    }

    @Test
    fun 父待办到期清理时其回收站子任务一并清理() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val parent = createTask(title="到期父待办")
        val sub = subTaskUseCases.create(parent.id, "联动步骤").getOrThrow()

        val base = millis("2026-11-24", "09:00")
        clock.add(base - 30 * DAY)
        taskUseCases.trash(parent.id).getOrThrow() // 父子同批删除

        clock.add(base)
        val result = recycleBinUseCases.purgeExpired(base - 30 * DAY)
        assertEquals(1, result.purgedTasks)
        // 子任务随父待办彻底删除，不再单独出现在回收站
        assertNull(taskUseCases.get(parent.id))
        assertNull(subTaskUseCases.get(sub.id))
        assertTrue(trashedItems().isEmpty())
    }

    // ---------- AI 数据源防御 ----------

    @Test
    fun 回收站内容不进入AI上下文快照恢复后可见() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        // 截止在快照窗口内（今天起未来七天），保证正常时可进入快照
        val task = taskUseCases.create(
            TaskInput(
                title = "AI可见待办",
                dueEpochDay = LocalDate.parse("2026-10-26").toEpochDay(),
                dueZoneId = zone.id,
            ),
        ).getOrThrow()
        val entry = createEntry(title = "AI可见日程")
        val note = createNote(title = "AI可见便签")
        val grant = AiContextGrant(
            setOf(AiContextCategory.TASKS, AiContextCategory.SCHEDULE, AiContextCategory.NOTES),
        )

        suspend fun snapshot() = aiProvider.loadSnapshot(grant)
        assertEquals(listOf("AI可见待办"), snapshot().tasks.map { it.title })
        assertEquals(listOf("AI可见日程"), snapshot().schedule.map { it.title })
        assertEquals(listOf("AI可见便签"), snapshot().notes.map { it.title })

        clock.add(millis("2026-10-25", "10:00"))
        taskUseCases.trash(task.id).getOrThrow()
        calendarUseCases.trash(entry.id).getOrThrow()
        noteUseCases.trash(note.id).getOrThrow()

        // 回收站期间：AI 查询或建议数据源不包含回收站内容（防御性断言生产 provider）
        val trashed = snapshot()
        assertTrue(trashed.tasks.isEmpty())
        assertTrue(trashed.schedule.isEmpty())
        assertTrue(trashed.notes.isEmpty())

        clock.add(millis("2026-10-25", "11:00"))
        recycleBinUseCases.restoreTask(task.id).getOrThrow()
        recycleBinUseCases.restoreEntry(entry.id).getOrThrow()
        recycleBinUseCases.restoreNote(note.id).getOrThrow()

        assertEquals(listOf("AI可见待办"), snapshot().tasks.map { it.title })
        assertEquals(listOf("AI可见日程"), snapshot().schedule.map { it.title })
        assertEquals(listOf("AI可见便签"), snapshot().notes.map { it.title })
    }

    // ---------- 直连仓库（精确构造 trashedAt 用） ----------

    private fun tasksRepositoryDirect() = TasksRepositoryImpl(db.taskDao(), Dispatchers.IO)
    private fun subTasksRepositoryDirect() = SubTasksRepositoryImpl(db.subTaskDao(), Dispatchers.IO)
    private fun calendarRepositoryDirect() = CalendarRepositoryImpl(db.calendarEntryDao(), Dispatchers.IO)
    private fun notesRepositoryDirect() = NotesRepositoryImpl(db.noteDao(), Dispatchers.IO)

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}

private class RecycleBinFakeAlarmScheduler : AlarmScheduler {
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
}
