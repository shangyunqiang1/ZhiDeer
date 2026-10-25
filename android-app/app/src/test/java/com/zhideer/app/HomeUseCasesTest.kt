package com.zhideer.app

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.zhideer.app.data.db.ZhiDeerDatabase
import com.zhideer.app.data.repository.CalendarRepositoryImpl
import com.zhideer.app.data.repository.TasksRepositoryImpl
import com.zhideer.app.domain.model.TaskPriority
import com.zhideer.app.domain.usecase.CalendarInput
import com.zhideer.app.domain.usecase.CalendarUseCases
import com.zhideer.app.domain.usecase.HomeUseCases
import com.zhideer.app.domain.usecase.TaskInput
import com.zhideer.app.domain.usecase.TaskUseCases
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
 * T34/#35 验收测试：首页汇总全部来自真实持久记录——下一事项、今日日程（跨时区锚定）、
 * 过期分节、统计与完成后刷新、空库空态。只读零写入。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HomeUseCasesTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private lateinit var dbFile: File
    private lateinit var db: ZhiDeerDatabase
    private lateinit var tasks: TaskUseCases
    private lateinit var calendar: CalendarUseCases
    private lateinit var home: HomeUseCases
    private val clock = mutableListOf(0L)
    private val idSeq = AtomicInteger(0)

    private fun millis(date: String, time: String = "10:00"): Long =
        LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()

    private fun epochDay(date: String): Long = LocalDate.parse(date).toEpochDay()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dbFile = context.getDatabasePath("t35_home_test.db")
        dbFile.parentFile?.mkdirs()
        context.deleteDatabase(dbFile.name)
        open()
    }

    private fun open() {
        db = Room.databaseBuilder<ZhiDeerDatabase>(
            context = ApplicationProvider.getApplicationContext<Context>(),
            name = dbFile.absolutePath,
        ).setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        val taskRepo = TasksRepositoryImpl(db.taskDao(), Dispatchers.IO)
        val calRepo = CalendarRepositoryImpl(db.calendarEntryDao(), Dispatchers.IO)
        tasks = TaskUseCases(
            repository = taskRepo,
            idGen = { "t-${idSeq.incrementAndGet()}" },
            now = { clock.last() },
            zoneId = zone.id,
        )
        calendar = CalendarUseCases(
            repository = calRepo,
            idGen = { "c-${idSeq.incrementAndGet()}" },
            now = { clock.last() },
            zoneId = zone.id,
        )
        home = HomeUseCases(tasks, calendar, zone) { clock.last() }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun taskInput(title: String, dueDay: Long? = null, dueAt: Long? = null) = TaskInput(
        title = title, priority = TaskPriority.NORMAL, category = "",
        dueEpochDay = dueDay, dueAtMillis = dueAt, dueZoneId = zone.id,
    )

    @Test
    fun 空库时全部为空且统计为零() = runBlocking {
        clock.add(millis("2026-10-26", "09:00"))
        val snapshot = home.observeHome().first()
        assertNull(snapshot.nextTask)
        assertNull(snapshot.nextSchedule)
        assertTrue(snapshot.todaySchedules.isEmpty())
        assertTrue(snapshot.overdueTasks.isEmpty())
        assertEquals(0, snapshot.stats.dueTodayCount)
        assertEquals(0, snapshot.stats.overdueCount)
        assertEquals(0, snapshot.stats.completedTodayCount)
    }

    @Test
    fun 下一事项取最早的未来待办截止与日程开始() = runBlocking {
        clock.add(millis("2026-10-26", "09:00"))
        tasks.create(taskInput("晚些截止", dueDay = epochDay("2026-10-28"))).getOrThrow()
        tasks.create(taskInput("最近截止", dueAt = millis("2026-10-26", "15:00"))).getOrThrow()
        calendar.create(
            CalendarInput(
                title = "下午会议",
                startMillis = millis("2026-10-26", "14:00"),
                endMillis = millis("2026-10-26", "15:00"),
                timezoneId = zone.id,
            ),
        ).getOrThrow()
        calendar.create(
            CalendarInput(
                title = "明天行程",
                startMillis = millis("2026-10-27", "09:00"),
                endMillis = millis("2026-10-27", "10:00"),
                timezoneId = zone.id,
            ),
        ).getOrThrow()

        val snapshot = home.observeHome().first()
        assertEquals("最近截止", snapshot.nextTask!!.title)
        assertEquals(false, snapshot.nextTask.isDateOnly)
        assertEquals("下午会议", snapshot.nextSchedule!!.title)
        // 已开始的日程不算"下一日程"
        assertEquals(1, snapshot.todaySchedules.size)
    }

    @Test
    fun 过期与今天统计来自真实记录且完成后即时刷新() = runBlocking {
        clock.add(millis("2026-10-26", "09:00"))
        tasks.create(taskInput("过期项", dueDay = epochDay("2026-10-20"))).getOrThrow()
        tasks.create(taskInput("今天项", dueDay = epochDay("2026-10-26"))).getOrThrow()
        val done = tasks.create(taskInput("今天完成", dueDay = epochDay("2026-10-26"))).getOrThrow()

        var snapshot = home.observeHome().first()
        assertEquals(2, snapshot.stats.dueTodayCount)
        assertEquals(1, snapshot.stats.overdueCount)
        assertEquals(1, snapshot.overdueTasks.size)
        assertEquals("过期项", snapshot.overdueTasks.first().title)
        assertEquals(0, snapshot.stats.completedTodayCount)

        tasks.complete(done.id, completed = true)
        snapshot = home.observeHome().first()
        assertEquals(1, snapshot.stats.completedTodayCount)
        assertEquals(1, snapshot.stats.dueTodayCount)
    }

    @Test
    fun 今日日程按各自锚定时区归属且跨日日程计入() = runBlocking {
        clock.add(millis("2026-10-26", "09:00"))
        // 上海 23:00 开始跨到次日 01:00 的日程：归属今天
        calendar.create(
            CalendarInput(
                title = "跨午夜",
                startMillis = millis("2026-10-26", "23:00"),
                endMillis = millis("2026-10-27", "01:00"),
                timezoneId = zone.id,
            ),
        ).getOrThrow()
        // 悉尼时区（比上海早 3 小时）今天开始：按其锚定时区仍是今天
        val sydney = ZoneId.of("Australia/Sydney")
        val start = LocalDate.parse("2026-10-26").atTime(22, 0).atZone(sydney).toInstant().toEpochMilli()
        val end = LocalDate.parse("2026-10-26").atTime(23, 0).atZone(sydney).toInstant().toEpochMilli()
        calendar.create(
            CalendarInput(title = "悉尼晚间", startMillis = start, endMillis = end, timezoneId = sydney.id),
        ).getOrThrow()
        // 纯明天日程不计入
        calendar.create(
            CalendarInput(
                title = "明天",
                startMillis = millis("2026-10-27", "09:00"),
                endMillis = millis("2026-10-27", "10:00"),
                timezoneId = zone.id,
            ),
        ).getOrThrow()

        val snapshot = home.observeHome().first()
        // 按开始时间排序：悉尼 22:00（UTC+11，即 UTC 11:00）早于上海 23:00（UTC 15:00）
        assertEquals(listOf("悉尼晚间", "跨午夜"), snapshot.todaySchedules.map { it.title })
    }

    @Test
    fun 回收站与取消的待办不进入首页() = runBlocking {
        clock.add(millis("2026-10-26", "09:00"))
        val trashed = tasks.create(taskInput("回收项", dueAt = millis("2026-10-26", "15:00"))).getOrThrow()
        val cancelled = tasks.create(taskInput("取消项", dueAt = millis("2026-10-26", "16:00"))).getOrThrow()
        tasks.trash(trashed.id)
        tasks.cancel(cancelled.id)

        val snapshot = home.observeHome().first()
        assertNull(snapshot.nextTask)
        assertEquals(0, snapshot.stats.overdueCount)
        assertTrue(snapshot.overdueTasks.isEmpty())
    }
}
