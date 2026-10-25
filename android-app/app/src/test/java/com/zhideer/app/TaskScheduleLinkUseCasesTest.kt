package com.zhideer.app

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.zhideer.app.data.db.ZhiDeerDatabase
import com.zhideer.app.data.repository.CalendarRepositoryImpl
import com.zhideer.app.data.repository.TasksRepositoryImpl
import com.zhideer.app.domain.model.TaskStatus
import com.zhideer.app.domain.usecase.CalendarInput
import com.zhideer.app.domain.usecase.CalendarUseCases
import com.zhideer.app.domain.usecase.TaskInput
import com.zhideer.app.domain.usecase.TaskScheduleLinkUseCases
import com.zhideer.app.domain.usecase.TaskUseCases
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
 * T06/#7 验收测试：待办与日程的关联（一个待办可关联多个日程、一个日程最多关联一个待办、
 * 稳定标识外键、改标题不破坏关联、双向零自动联动、删除语义与解除关联、
 * 完成待办时的未来日程展示与可选取消）。
 * 全部使用注入的固定时钟，走真实 Room 文件库与统一用例入口。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TaskScheduleLinkUseCasesTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private lateinit var dbFile: File
    private lateinit var db: ZhiDeerDatabase
    private lateinit var taskUseCases: TaskUseCases
    private lateinit var calendarUseCases: CalendarUseCases
    private lateinit var linkUseCases: TaskScheduleLinkUseCases
    private val clock = mutableListOf(0L)
    private val idSeq = AtomicInteger(0)

    private fun millis(date: String, time: String = "10:00"): Long =
        LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dbFile = context.getDatabasePath("t07_test.db")
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
        taskUseCases = TaskUseCases(
            repository = TasksRepositoryImpl(db.taskDao(), Dispatchers.IO),
            idGen = { "task-${idSeq.incrementAndGet()}" },
            now = { clock.last() },
            zoneId = zone.id,
        )
        calendarUseCases = CalendarUseCases(
            repository = CalendarRepositoryImpl(db.calendarEntryDao(), Dispatchers.IO),
            idGen = { "entry-${idSeq.incrementAndGet()}" },
            now = { clock.last() },
            zoneId = zone.id,
        )
        linkUseCases = TaskScheduleLinkUseCases(
            tasksRepository = TasksRepositoryImpl(db.taskDao(), Dispatchers.IO),
            calendarRepository = CalendarRepositoryImpl(db.calendarEntryDao(), Dispatchers.IO),
            now = { clock.last() },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun createTask(title: String): com.zhideer.app.domain.model.TodoTask {
        clock.add(millis("2026-10-25", "08:00"))
        return taskUseCases.create(
            TaskInput(title = title, dueZoneId = zone.id),
        ).getOrThrow()
    }

    private suspend fun createEntry(
        title: String,
        startDate: String = "2026-10-26",
        startTime: String = "09:00",
        endTime: String = "10:00",
    ): com.zhideer.app.domain.model.CalendarEntry {
        clock.add(millis("2026-10-25", "08:00"))
        return calendarUseCases.create(
            CalendarInput(
                title = title,
                startMillis = millis(startDate, startTime),
                endMillis = millis(startDate, endTime),
                timezoneId = zone.id,
            ),
        ).getOrThrow()
    }

    @Test
    fun 同一待办可关联多个日程且每个日程最多关联一个待办() = runBlocking {
        val task = createTask("写周报")
        val first = createEntry("周一写作段", "2026-10-26", "09:00", "10:00")
        val second = createEntry("周三写作段", "2026-10-28", "14:00", "15:00")

        assertTrue(linkUseCases.link(task.id, first.id).isSuccess)
        assertTrue(linkUseCases.link(task.id, second.id).isSuccess)

        // 一个待办的全部关联日程都可见，按开始时间排序
        val linked = linkUseCases.getEntriesForTask(task.id)
        assertEquals(listOf(first.id, second.id), linked.map { it.id })
        // 关联通过稳定标识（外键 taskId 存稳定 id）保存，不是文本副本
        assertEquals(task.id, linkUseCases.getEntriesForTask(task.id).first().taskId)
        assertEquals(task.id, linked.last().taskId)

        // 重复关联同一日程与同一待办是幂等操作
        assertTrue(linkUseCases.link(task.id, first.id).isSuccess)
        assertEquals(2, linkUseCases.getEntriesForTask(task.id).size)

        // 每个日程最多关联一个待办：已关联的日程不能直接改挂到另一个待办
        val other = createTask("开评审会")
        val conflict = linkUseCases.link(other.id, first.id)
        assertTrue(conflict.isFailure)
        assertEquals("该日程已关联其他待办，请先解除原有关联", conflict.exceptionOrNull()!!.message)
        assertEquals(
            listOf(task.id),
            linkUseCases.getEntriesForTask(task.id).map { it.taskId }.distinct(),
        )
        assertTrue(linkUseCases.getEntriesForTask(other.id).isEmpty())
    }

    @Test
    fun 关联通过稳定标识保存且修改双方标题不破坏关联() = runBlocking {
        val task = createTask("初稿待办")
        val entry = createEntry("初稿日程")
        assertTrue(linkUseCases.link(task.id, entry.id).isSuccess)

        // 双方各自改标题：关联保存在稳定 id 上，不受标题文本影响
        clock.add(millis("2026-10-25", "09:00"))
        taskUseCases.update(task.id, TaskInput(title = "改名后的待办", dueZoneId = zone.id)).getOrThrow()
        calendarUseCases.update(
            entry.id,
            CalendarInput(
                title = "改名后的日程",
                startMillis = entry.startMillis,
                endMillis = entry.endMillis,
                timezoneId = entry.timezoneId,
            ),
        ).getOrThrow()

        assertEquals("改名后的待办", linkUseCases.getTaskForEntry(entry.id)!!.title)
        assertEquals(listOf("改名后的日程"), linkUseCases.getEntriesForTask(task.id).map { it.title })

        // 关闭重开：关联仍通过稳定标识保留
        db.close()
        open()
        assertEquals(task.id, linkUseCases.getEntriesForTask(task.id).single().taskId)
        assertEquals("改名后的待办", linkUseCases.getTaskForEntry(entry.id)!!.title)

        // 流式关联查询实时 join 双方数据
        assertEquals("改名后的待办", linkUseCases.observeTaskForEntry(entry.id).first()!!.title)
        assertEquals(
            listOf("改名后的日程"),
            linkUseCases.observeEntriesForTask(task.id).first().map { it.title },
        )
    }

    @Test
    fun 日程结束不自动完成待办且完成待办不自动改变日程() = runBlocking {
        val task = createTask("晨会行动项")
        val entry = createEntry("晨会", "2026-10-25", "09:00", "10:00")
        assertTrue(linkUseCases.link(task.id, entry.id).isSuccess)
        val linkedBefore = calendarUseCases.get(entry.id)!!

        // 日程时间已过：只表示时间结束，待办不发生任何自动变化
        clock.add(millis("2026-10-25", "12:00"))
        val taskAfterEnd = taskUseCases.get(task.id)!!
        assertEquals(TaskStatus.ACTIVE, taskAfterEnd.status)
        assertNull(taskAfterEnd.completedAt)

        // 完成待办：关联日程记录零变化（不取消、不删除、不改任何字段）
        clock.add(millis("2026-10-25", "13:00"))
        taskUseCases.complete(task.id, completed = true).getOrThrow()
        val entryAfterComplete = calendarUseCases.get(entry.id)!!
        assertEquals(linkedBefore, entryAfterComplete)
        assertEquals(task.id, entryAfterComplete.taskId)
        assertNull(entryAfterComplete.trashedAt)
        // 日程仍出现在待办的关联列表中
        assertEquals(listOf(entry.id), linkUseCases.getEntriesForTask(task.id).map { it.id })

        // 完成待办后日程照常展示，日程状态与待办完成状态互不影响
        assertEquals(TaskStatus.COMPLETED, taskUseCases.get(task.id)!!.status)
        assertFalse(calendarUseCases.get(entry.id)!!.trashedAt != null)
    }

    @Test
    fun 删除日程保留待办且删除待办保留日程并解除关联() = runBlocking {
        val task = createTask("取快递")
        val entryA = createEntry("取快递时段一", "2026-10-26", "09:00", "10:00")
        val entryB = createEntry("取快递时段二", "2026-10-27", "09:00", "10:00")
        assertTrue(linkUseCases.link(task.id, entryA.id).isSuccess)
        assertTrue(linkUseCases.link(task.id, entryB.id).isSuccess)

        // 删除日程（回收站）：待办原样保留，不被删除或取消
        clock.add(millis("2026-10-25", "10:00"))
        calendarUseCases.trash(entryA.id).getOrThrow()
        val keptTask = taskUseCases.get(task.id)!!
        assertEquals(TaskStatus.ACTIVE, keptTask.status)
        assertNull(keptTask.trashedAt)
        // 日程记录仍在（回收站中可恢复），待办详情的关联列表不再显示回收站日程
        val trashedEntry = calendarUseCases.get(entryA.id)
        assertNotNull("回收站中的日程记录必须保留", trashedEntry)
        assertNotNull(trashedEntry!!.trashedAt)
        assertEquals(listOf(entryB.id), linkUseCases.getEntriesForTask(task.id).map { it.id })

        // 删除待办（回收站）：日程保留且全部解除关联（含已在回收站中的日程记录）
        clock.add(millis("2026-10-25", "11:00"))
        taskUseCases.trash(task.id).getOrThrow()
        assertEquals(2, linkUseCases.unlinkTask(task.id).getOrThrow())

        val keptEntryB = calendarUseCases.get(entryB.id)!!
        assertNull("删除待办后日程必须解除关联", keptEntryB.trashedAt)
        assertNull(keptEntryB.taskId)
        val keptEntryA = calendarUseCases.get(entryA.id)!!
        assertNotNull("回收站中的日程记录必须保留", keptEntryA.trashedAt)
        assertNull(keptEntryA.taskId)
        assertNotNull(taskUseCases.get(task.id))
    }

    @Test
    fun 完成待办时展示未来关联日程并可选择一并取消或默认保留() = runBlocking {
        val task = createTask("整理房间")
        val past = createEntry("昨天已过的整理时段", "2026-10-24", "09:00", "10:00")
        val ongoing = createEntry("进行中的整理时段", "2026-10-25", "07:00", "09:00")
        val future1 = createEntry("明天的整理时段", "2026-10-26", "09:00", "10:00")
        val future2 = createEntry("后天的整理时段", "2026-10-27", "09:00", "10:00")
        listOf(past, ongoing, future1, future2).forEach {
            assertTrue(linkUseCases.link(task.id, it.id).isSuccess)
        }
        // 时钟走到进行中时段
        clock.add(millis("2026-10-25", "08:00"))

        // 完成前展示未来关联日程：只含尚未开始的（已结束与进行中的不算）
        assertEquals(
            listOf(future1.id, future2.id),
            linkUseCases.futureEntriesForTask(task.id).map { it.id },
        )

        // 用户选择「一并取消这些日程」：仅未来日程进入回收站，其余不动
        clock.add(millis("2026-10-25", "08:30"))
        assertEquals(2, linkUseCases.cancelFutureEntries(task.id).getOrThrow())
        assertNotNull(calendarUseCases.get(future1.id)!!.trashedAt)
        assertNotNull(calendarUseCases.get(future2.id)!!.trashedAt)
        assertNull(calendarUseCases.get(past.id)!!.trashedAt)
        assertNull(calendarUseCases.get(ongoing.id)!!.trashedAt)
        assertEquals(
            listOf(past.id, ongoing.id),
            linkUseCases.getEntriesForTask(task.id).map { it.id },
        )

        // 默认保留：完成待办不调用取消，未来日程原样保留且关联不断
        val task2 = createTask("写读书笔记")
        val future3 = createEntry("读书时段", "2026-10-28", "09:00", "10:00")
        assertTrue(linkUseCases.link(task2.id, future3.id).isSuccess)
        clock.add(millis("2026-10-25", "09:00"))
        taskUseCases.complete(task2.id, completed = true).getOrThrow()
        val kept = calendarUseCases.get(future3.id)!!
        assertNull(kept.trashedAt)
        assertEquals(task2.id, kept.taskId)
        // 日程详情实时显示关联待办（含已完成状态）
        val linkedTask = linkUseCases.getTaskForEntry(future3.id)
        assertNotNull(linkedTask)
        assertEquals(TaskStatus.COMPLETED, linkedTask!!.status)
    }

    @Test
    fun 待办详情可查看全部关联日程含时间且日程详情实时显示关联待办() = runBlocking {
        val task = createTask("备考复习")
        val first = createEntry("上午复习段", "2026-10-26", "09:00", "10:00")
        val second = createEntry("晚上复习段", "2026-10-27", "20:00", "21:00")
        assertTrue(linkUseCases.link(task.id, first.id).isSuccess)
        assertTrue(linkUseCases.link(task.id, second.id).isSuccess)

        // 待办详情可见全部关联日程，且时间字段完整（含时间）
        val linked = linkUseCases.getEntriesForTask(task.id)
        assertEquals(
            listOf(first.id to first.startMillis, second.id to second.startMillis),
            linked.map { it.id to it.startMillis },
        )
        assertEquals(
            listOf(first.endMillis, second.endMillis),
            linked.map { it.endMillis },
        )
        assertEquals(
            listOf(first.id, second.id),
            linkUseCases.observeEntriesForTask(task.id).first().map { it.id },
        )

        // 日程详情实时显示关联待办：标题取自待办实时数据，改标题后读到新标题
        assertEquals(task.id, linkUseCases.getTaskForEntry(second.id)!!.id)
        clock.add(millis("2026-10-25", "12:00"))
        taskUseCases.update(task.id, TaskInput(title = "备考冲刺", dueZoneId = zone.id)).getOrThrow()
        assertEquals("备考冲刺", linkUseCases.getTaskForEntry(second.id)!!.title)

        // 未关联日程没有关联待办
        val unlinked = createEntry("未关联日程")
        assertNull(linkUseCases.getTaskForEntry(unlinked.id))
    }

    @Test
    fun 非法关联被拒绝且使用中文说明() = runBlocking {
        val task = createTask("正常待办")
        val entry = createEntry("正常日程")

        val noTask = linkUseCases.link("不存在", entry.id)
        assertTrue(noTask.isFailure)
        assertEquals("待办不存在或已永久删除", noTask.exceptionOrNull()!!.message)

        val noEntry = linkUseCases.link(task.id, "不存在")
        assertTrue(noEntry.isFailure)
        assertEquals("日程不存在或已永久删除", noEntry.exceptionOrNull()!!.message)

        // 回收站中的待办与日程都不能建立新关联
        taskUseCases.trash(task.id).getOrThrow()
        val trashedTask = linkUseCases.link(task.id, entry.id)
        assertTrue(trashedTask.isFailure)
        assertEquals("待办已删除（回收站中），不能建立关联", trashedTask.exceptionOrNull()!!.message)

        val task2 = createTask("另一待办")
        calendarUseCases.trash(entry.id).getOrThrow()
        val trashedEntry = linkUseCases.link(task2.id, entry.id)
        assertTrue(trashedEntry.isFailure)
        assertEquals("日程已删除（回收站中），不能建立关联", trashedEntry.exceptionOrNull()!!.message)

        val noUnlink = linkUseCases.unlink("不存在")
        assertTrue(noUnlink.isFailure)
        assertEquals("日程不存在或已永久删除", noUnlink.exceptionOrNull()!!.message)
    }
}
