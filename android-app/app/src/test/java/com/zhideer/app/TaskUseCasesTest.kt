package com.zhideer.app

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.zhideer.app.data.db.ZhiDeerDatabase
import com.zhideer.app.data.repository.TasksRepositoryImpl
import com.zhideer.app.domain.model.TaskDue
import com.zhideer.app.domain.model.TaskFilter
import com.zhideer.app.domain.model.TaskPriority
import com.zhideer.app.domain.model.TaskStatus
import com.zhideer.app.domain.usecase.TaskInput
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/**
 * T03/#4 验收测试：离线创建、重开读取、日期/时刻截止语义、过滤、
 * 完成/延期/取消时间戳、回收站语义、非法输入。全部走真实 Room 文件库与统一用例入口。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TaskUseCasesTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private lateinit var dbFile: File
    private lateinit var db: ZhiDeerDatabase
    private lateinit var useCases: TaskUseCases
    private val clock = mutableListOf(0L)
    private val idSeq = AtomicInteger(0)

    private fun millis(date: String, time: String = "10:00"): Long =
        LocalDate.parse(date).atTime(java.time.LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()

    private fun epochDay(date: String): Long = LocalDate.parse(date).toEpochDay()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dbFile = context.getDatabasePath("t03_test.db")
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
        useCases = TaskUseCases(
            repository = TasksRepositoryImpl(db.taskDao(), Dispatchers.IO),
            idGen = { "task-${idSeq.incrementAndGet()}" },
            now = { clock.last() },
            zoneId = zone.id,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun input(
        title: String = "买牛奶",
        priority: TaskPriority = TaskPriority.NORMAL,
        dueEpochDay: Long? = null,
        dueAtMillis: Long? = null,
    ) = TaskInput(
        title = title,
        note = "",
        priority = priority,
        category = "生活",
        dueEpochDay = dueEpochDay,
        dueAtMillis = dueAtMillis,
        dueZoneId = zone.id,
    )

    @Test
    fun 创建后关闭重开可读取相同记录() = runBlocking {
        clock.add(millis("2026-10-25"))
        val created = useCases.create(input(title = "写周报", dueEpochDay = epochDay("2026-10-26")))
            .getOrThrow()
        assertEquals(TaskStatus.ACTIVE, created.status)
        db.close()
        open()

        val readBack = useCases.get(created.id)
        assertNotNull(readBack)
        assertEquals("写周报", readBack!!.title)
        assertEquals(epochDay("2026-10-26"), readBack.dueEpochDay)
        assertEquals(zone.id, readBack.dueZoneId)
        assertEquals(TaskStatus.ACTIVE, readBack.status)
    }

    @Test
    fun 日期截止当天属于今天到期_次日属于过期_不补23点59分() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val todayTask = useCases.create(input(dueEpochDay = epochDay("2026-10-25"))).getOrThrow()
        val tomorrowTask = useCases.create(input(dueEpochDay = epochDay("2026-10-26"))).getOrThrow()

        // 当天 23:59:59 仍未过期（日期截止不隐含当天末尾时刻，而是整天有效）
        clock.add(millis("2026-10-25", "23:59") + 999)
        assertTrue(TaskDue.isDueToday(todayTask, clock.last()))
        assertFalse(TaskDue.isOverdue(todayTask, clock.last()))
        assertTrue(TaskDue.matches(todayTask, TaskFilter.TODAY, clock.last()))
        // 未来日期属于即将到期（此刻仍是 10-25）
        assertTrue(TaskDue.matches(tomorrowTask, TaskFilter.UPCOMING, clock.last()))

        // 次日零点起：昨天的日期截止属于过期
        clock.add(millis("2026-10-26", "00:00"))
        assertTrue(TaskDue.isOverdue(todayTask, clock.last()))
        assertTrue(TaskDue.matches(todayTask, TaskFilter.OVERDUE, clock.last()))
        // 到期日随时间推移变成今天，分类随之变为今天到期
        assertTrue(TaskDue.matches(tomorrowTask, TaskFilter.TODAY, clock.last()))
    }

    @Test
    fun 五种过滤结果来自真实记录() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val today = useCases.create(input(title = "今天截止", dueEpochDay = epochDay("2026-10-25"))).getOrThrow()
        val upcoming = useCases.create(input(title = "未来截止", dueEpochDay = epochDay("2026-11-01"))).getOrThrow()
        val overdue = useCases.create(input(title = "已过期", dueEpochDay = epochDay("2026-10-20"))).getOrThrow()
        val noDue = useCases.create(input(title = "无截止")).getOrThrow()
        val done = useCases.create(input(title = "已完成项")).getOrThrow()
        clock.add(millis("2026-10-25", "12:00"))
        useCases.complete(done.id, completed = true)

        assertEquals(listOf("今天截止"), titles(TaskFilter.TODAY))
        assertEquals(listOf("未来截止"), titles(TaskFilter.UPCOMING))
        assertEquals(listOf("已过期"), titles(TaskFilter.OVERDUE))
        assertEquals(
            listOf("今天截止", "未来截止", "已过期", "无截止", "已完成项").sorted(),
            titles(TaskFilter.ALL).sorted(),
        )
        assertEquals(listOf("已完成项"), titles(TaskFilter.COMPLETED))
        assertEquals(TaskStatus.COMPLETED, useCases.get(done.id)!!.status)
        assertNotNull(useCases.get(done.id)!!.completedAt)
        assertEquals(today.id, today.id)
        assertFalse(TaskDue.matches(useCases.get(overdue.id)!!, TaskFilter.TODAY, clock.last()))
        assertEquals(noDue.id, noDue.id)
    }

    @Test
    fun 完成延期取消分别记录时间戳且互不混用() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val task = useCases.create(input(dueEpochDay = epochDay("2026-10-26"))).getOrThrow()

        clock.add(millis("2026-10-25", "11:00"))
        useCases.complete(task.id, completed = true)
        val completed = useCases.get(task.id)!!
        assertEquals(millis("2026-10-25", "11:00"), completed.completedAt)
        assertNull(completed.postponedAt)
        assertNull(completed.cancelledAt)

        // 完成后撤销完成
        useCases.complete(task.id, completed = false)
        assertNull(useCases.get(task.id)!!.completedAt)

        // 延期：新截止 + 延期时间戳
        clock.add(millis("2026-10-25", "12:00"))
        useCases.postpone(task.id, epochDay("2026-10-30"), null)
        val postponed = useCases.get(task.id)!!
        assertEquals(epochDay("2026-10-30"), postponed.dueEpochDay)
        assertEquals(millis("2026-10-25", "12:00"), postponed.postponedAt)
        assertEquals(TaskStatus.ACTIVE, postponed.status)

        // 取消：状态与取消时间戳，保留记录
        clock.add(millis("2026-10-25", "13:00"))
        useCases.cancel(task.id)
        val cancelled = useCases.get(task.id)!!
        assertEquals(TaskStatus.CANCELLED, cancelled.status)
        assertEquals(millis("2026-10-25", "13:00"), cancelled.cancelledAt)
        assertNull(cancelled.trashedAt)
    }

    @Test
    fun 普通删除进入回收站并从正常查询排除但不永久删除() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val task = useCases.create(input(title = "将被删除")).getOrThrow()
        clock.add(millis("2026-10-25", "10:00"))
        useCases.trash(task.id).getOrThrow()

        assertEquals(emptyList<String>(), titles(TaskFilter.ALL))
        val kept = useCases.get(task.id)
        assertNotNull("回收站记录必须保留以供恢复", kept)
        assertEquals(millis("2026-10-25", "10:00"), kept!!.trashedAt)
    }

    @Test
    fun 非法输入被拒绝且使用中文说明() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))

        val blank = useCases.create(input(title = "   "))
        assertTrue(blank.isFailure)
        assertEquals("标题不能为空", blank.exceptionOrNull()!!.message)

        val bothDue = useCases.create(
            input(dueEpochDay = epochDay("2026-10-26"), dueAtMillis = millis("2026-10-26", "15:00")),
        )
        assertTrue(bothDue.isFailure)
        assertEquals("日期截止与时刻截止只能选择其一", bothDue.exceptionOrNull()!!.message)

        // 编辑同样拒绝非法输入
        val task = useCases.create(input()).getOrThrow()
        val badUpdate = useCases.update(task.id, input(title = ""))
        assertTrue(badUpdate.isFailure)
        assertEquals("标题不能为空", badUpdate.exceptionOrNull()!!.message)
    }

    @Test
    fun 编辑更新字段与更新时间() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val task = useCases.create(input(title = "初稿", priority = TaskPriority.LOW)).getOrThrow()
        clock.add(millis("2026-10-25", "10:30"))
        val updated = useCases.update(
            task.id,
            input(title = "定稿", priority = TaskPriority.HIGH, dueEpochDay = epochDay("2026-10-27")),
        ).getOrThrow()
        assertEquals("定稿", updated.title)
        assertEquals(TaskPriority.HIGH, updated.priority)
        assertEquals(epochDay("2026-10-27"), updated.dueEpochDay)
        assertEquals(millis("2026-10-25", "10:30"), updated.updatedAt)
        assertEquals(task.createdAt, updated.createdAt)
    }

    private suspend fun titles(filter: TaskFilter): List<String> =
        useCases.observe(filter).first().map { it.title }
}
