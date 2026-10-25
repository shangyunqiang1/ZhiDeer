package com.zhideer.app

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.zhideer.app.data.db.ZhiDeerDatabase
import com.zhideer.app.data.repository.CalendarRepositoryImpl
import com.zhideer.app.domain.model.CalendarRange
import com.zhideer.app.domain.model.CalendarTimes
import com.zhideer.app.domain.usecase.CalendarInput
import com.zhideer.app.domain.usecase.CalendarUseCases
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
 * T04/#5 验收测试：本地日程的创建/编辑/跨日归属/重开读取、非法时间拒绝、
 * 今日/明日/未来七天真实查询（绝不虚构）、创建时区保存与时区核对确认、
 * 日程结束不等于业务完成、回收站语义。
 * 全部使用注入的固定时钟与时区，走真实 Room 文件库与统一用例入口。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CalendarUseCasesTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private lateinit var dbFile: File
    private lateinit var db: ZhiDeerDatabase
    private lateinit var useCases: CalendarUseCases
    private val clock = mutableListOf(0L)
    private val idSeq = AtomicInteger(0)

    private fun millis(date: String, time: String = "10:00", zoneId: ZoneId = zone): Long =
        LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(zoneId).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dbFile = context.getDatabasePath("t04_test.db")
        dbFile.parentFile?.mkdirs()
        context.deleteDatabase(dbFile.name)
        open(zone.id)
    }

    private fun open(zoneId: String) {
        db = Room.databaseBuilder<ZhiDeerDatabase>(
            context = ApplicationProvider.getApplicationContext<Context>(),
            name = dbFile.absolutePath,
        )
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        useCases = CalendarUseCases(
            repository = CalendarRepositoryImpl(db.calendarEntryDao(), Dispatchers.IO),
            idGen = { "entry-${idSeq.incrementAndGet()}" },
            now = { clock.last() },
            zoneId = zoneId,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun input(
        title: String = "周会",
        startDate: String = "2026-10-26",
        startTime: String = "10:00",
        endDate: String = startDate,
        endTime: String = "11:00",
        zoneId: String = zone.id,
        location: String = "会议室A",
        note: String = "带笔记本",
        category: String = "工作",
    ) = CalendarInput(
        title = title,
        startMillis = millis(startDate, startTime, ZoneId.of(zoneId)),
        endMillis = millis(endDate, endTime, ZoneId.of(zoneId)),
        timezoneId = zoneId,
        location = location,
        note = note,
        category = category,
    )

    @Test
    fun 创建后关闭重开可读取相同字段与时区() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val created = useCases.create(input()).getOrThrow()
        assertEquals(zone.id, created.timezoneId)
        db.close()
        open(zone.id)

        val back = useCases.get(created.id)
        assertNotNull(back)
        assertEquals(created, back)
        assertEquals("周会", back!!.title)
        assertEquals(millis("2026-10-26", "10:00"), back.startMillis)
        assertEquals(millis("2026-10-26", "11:00"), back.endMillis)
        assertEquals("会议室A", back.location)
        assertEquals("带笔记本", back.note)
        assertEquals("工作", back.category)
        assertEquals(zone.id, back.timezoneId)
        assertNull(back.trashedAt)
        assertNull(back.zoneConfirmedAt)
    }

    @Test
    fun 编辑更新字段与更新时间且月日列表同步() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val entry = useCases.create(
            input(title = "初稿", startDate = "2026-10-26", startTime = "10:00", endTime = "11:00"),
        ).getOrThrow()
        clock.add(millis("2026-10-25", "10:30"))
        val updated = useCases.update(
            entry.id,
            input(
                title = "定稿",
                startDate = "2026-10-27",
                startTime = "14:00",
                endDate = "2026-10-27",
                endTime = "15:30",
                location = "线上",
                note = "改期",
                category = "沟通",
            ),
        ).getOrThrow()

        assertEquals("定稿", updated.title)
        assertEquals(millis("2026-10-27", "14:00"), updated.startMillis)
        assertEquals(millis("2026-10-27", "15:30"), updated.endMillis)
        assertEquals("线上", updated.location)
        assertEquals("改期", updated.note)
        assertEquals("沟通", updated.category)
        assertEquals(zone.id, updated.timezoneId)
        assertEquals(millis("2026-10-25", "10:30"), updated.updatedAt)
        assertEquals(entry.createdAt, updated.createdAt)

        // 旧日期不再出现，新日期出现：月视图/列表所依据的查询同步更新
        assertTrue(titlesOn(LocalDate.of(2026, 10, 26)).isEmpty())
        assertEquals(listOf("定稿"), titlesOn(LocalDate.of(2026, 10, 27)))
    }

    @Test
    fun 非法时间被拒绝且使用中文说明() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))

        val tooEarlyEnd = useCases.create(
            input(startDate = "2026-10-26", startTime = "10:00", endTime = "09:00"),
        )
        assertTrue(tooEarlyEnd.isFailure)
        assertEquals("结束时间不能早于开始时间", tooEarlyEnd.exceptionOrNull()!!.message)

        val blank = useCases.create(input(title = "   "))
        assertTrue(blank.isFailure)
        assertEquals("标题不能为空", blank.exceptionOrNull()!!.message)

        // 跨日反向同样拒绝：结束日期早于开始日期
        val crossDayReverse = useCases.create(
            input(startDate = "2026-10-27", startTime = "10:00", endDate = "2026-10-26", endTime = "18:00"),
        )
        assertTrue(crossDayReverse.isFailure)
        assertEquals("结束时间不能早于开始时间", crossDayReverse.exceptionOrNull()!!.message)

        // 边界：结束等于开始不早于开始，允许
        val equal = useCases.create(
            input(startDate = "2026-10-26", startTime = "10:00", endTime = "10:00"),
        )
        assertTrue(equal.isSuccess)

        // 编辑同样拒绝非法时间
        val entry = useCases.create(input()).getOrThrow()
        val badUpdate = useCases.update(
            entry.id,
            input(startDate = "2026-10-26", startTime = "12:00", endTime = "11:00"),
        )
        assertTrue(badUpdate.isFailure)
        assertEquals("结束时间不能早于开始时间", badUpdate.exceptionOrNull()!!.message)
    }

    @Test
    fun 跨日安排显示在正确的开始与结束日期() = runBlocking {
        clock.add(millis("2026-10-25", "08:00"))
        val overnight = useCases.create(
            input(
                title = "跨夜值守",
                startDate = "2026-10-25",
                startTime = "23:00",
                endDate = "2026-10-26",
                endTime = "01:00",
            ),
        ).getOrThrow()

        assertEquals(LocalDate.of(2026, 10, 25), CalendarTimes.startLocalDate(overnight))
        assertEquals(LocalDate.of(2026, 10, 26), CalendarTimes.endLocalDate(overnight))
        // 出现在开始日与结束日的列表，不虚构到其他日期
        assertEquals(listOf("跨夜值守"), titlesOn(LocalDate.of(2026, 10, 25)))
        assertEquals(listOf("跨夜值守"), titlesOn(LocalDate.of(2026, 10, 26)))

        // 普通（不跨日）日程只出现在自己那天，不串到相邻日期
        useCases.create(
            input(title = "普通日程", startDate = "2026-10-25", startTime = "10:00", endTime = "12:00"),
        ).getOrThrow()
        assertEquals(listOf("跨夜值守"), titlesOn(LocalDate.of(2026, 10, 26)))
        assertTrue(titlesOn(LocalDate.of(2026, 10, 27)).isEmpty())

        // 今日（10-25）范围查询包含跨夜日程
        assertTrue(CalendarTimes.matchesRange(overnight, CalendarRange.TODAY, clock.last()))
    }

    @Test
    fun 今日明日未来七天查询来自真实记录() = runBlocking {
        clock.add(millis("2026-10-25", "08:00"))
        // 空库时任何查询都不虚构记录
        assertTrue(titles(CalendarRange.TODAY).isEmpty())
        assertTrue(titles(CalendarRange.TOMORROW).isEmpty())
        assertTrue(titles(CalendarRange.NEXT_SEVEN_DAYS).isEmpty())

        useCases.create(input(title = "昨天的事", startDate = "2026-10-24", startTime = "10:00", endTime = "11:00"))
            .getOrThrow()
        useCases.create(input(title = "今天的事", startDate = "2026-10-25", startTime = "10:00", endTime = "11:00"))
            .getOrThrow()
        useCases.create(input(title = "明天的事", startDate = "2026-10-26", startTime = "10:00", endTime = "11:00"))
            .getOrThrow()
        useCases.create(input(title = "本周五的事", startDate = "2026-10-30", startTime = "10:00", endTime = "11:00"))
            .getOrThrow()
        useCases.create(input(title = "下下周的事", startDate = "2026-11-08", startTime = "10:00", endTime = "11:00"))
            .getOrThrow()

        assertEquals(listOf("今天的事"), titles(CalendarRange.TODAY))
        assertEquals(listOf("明天的事"), titles(CalendarRange.TOMORROW))
        assertEquals(
            listOf("今天的事", "明天的事", "本周五的事"),
            titles(CalendarRange.NEXT_SEVEN_DAYS).sorted(),
        )
        // 已过与远期记录不属于这三个范围，但仍是真实持久记录
        assertTrue(titles(CalendarRange.TODAY).none { it == "昨天的事" || it == "下下周的事" })
        assertEquals(
            listOf("下下周的事", "今天的事", "明天的事", "昨天的事", "本周五的事"),
            titlesAll().sorted(),
        )
    }

    @Test
    fun 日期归属按日程所存时区计算且不随设备时区静默改变() = runBlocking {
        clock.add(millis("2026-10-25", "12:00")) // 上海 12:00 = UTC 04:00
        val utcEntry = useCases.create(
            input(
                title = "UTC 日程",
                startDate = "2026-10-25",
                startTime = "20:00",
                endDate = "2026-10-25",
                endTime = "21:00",
                zoneId = "UTC",
            ),
        ).getOrThrow()
        // UTC 20:00 对应上海 10-26 04:00；按上海计算会误判为明日，按所存时区（UTC）应为今日
        assertEquals(LocalDate.of(2026, 10, 25), CalendarTimes.startLocalDate(utcEntry))
        assertTrue(CalendarTimes.matchesRange(utcEntry, CalendarRange.TODAY, clock.last()))
        assertEquals(listOf("UTC 日程"), titles(CalendarRange.TODAY))

        // 模拟设备时区变化后重开：记录字段不变，日期归属仍按所存时区计算
        db.close()
        open("America/New_York")
        val back = useCases.get(utcEntry.id)!!
        assertEquals("UTC", back.timezoneId)
        assertEquals(millis("2026-10-25", "20:00", ZoneId.of("UTC")), back.startMillis)
        assertTrue(CalendarTimes.matchesRange(back, CalendarRange.TODAY, clock.last()))
    }

    @Test
    fun 时区不一致可检测且保持原时区会记录确认时间戳() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val entry = useCases.create(input()).getOrThrow()

        assertTrue(CalendarTimes.detectMismatch(entry, "America/New_York"))
        assertFalse(CalendarTimes.detectMismatch(entry, zone.id))

        clock.add(millis("2026-10-25", "15:00"))
        val kept = useCases.confirmKeepZone(entry.id).getOrThrow()
        assertEquals(zone.id, kept.timezoneId)
        assertEquals(entry.startMillis, kept.startMillis)
        assertEquals(entry.endMillis, kept.endMillis)
        assertEquals(millis("2026-10-25", "15:00"), kept.zoneConfirmedAt)
        assertEquals(millis("2026-10-25", "15:00"), kept.updatedAt)
    }

    @Test
    fun 按新时区重新解释保留墙上时间并记录确认时间戳() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val entry = useCases.create(
            input(startDate = "2026-10-25", startTime = "10:00", endTime = "11:00"),
        ).getOrThrow()
        assertEquals(millis("2026-10-25", "10:00"), entry.startMillis)

        clock.add(millis("2026-10-25", "15:00"))
        val reinterpreted = useCases.reinterpretInZone(entry.id, "America/New_York").getOrThrow()
        assertEquals("America/New_York", reinterpreted.timezoneId)
        // 墙上时间 10:00-11:00 保留，按新时区重算 UTC 毫秒
        assertEquals(millis("2026-10-25", "10:00", ZoneId.of("America/New_York")), reinterpreted.startMillis)
        assertEquals(millis("2026-10-25", "11:00", ZoneId.of("America/New_York")), reinterpreted.endMillis)
        assertEquals(millis("2026-10-25", "15:00"), reinterpreted.zoneConfirmedAt)
        assertEquals(millis("2026-10-25", "15:00"), reinterpreted.updatedAt)

        // 重开后仍保留重写结果
        db.close()
        open(zone.id)
        val back = useCases.get(entry.id)!!
        assertEquals("America/New_York", back.timezoneId)
        assertEquals(reinterpreted.startMillis, back.startMillis)
        assertEquals(millis("2026-10-25", "15:00"), back.zoneConfirmedAt)
    }

    @Test
    fun 日程结束只表示时间已过不产生业务完成状态() = runBlocking {
        clock.add(millis("2026-10-25", "08:00"))
        val entry = useCases.create(
            input(title = "上午例会", startDate = "2026-10-25", startTime = "09:00", endTime = "10:00"),
        ).getOrThrow()
        assertFalse(CalendarTimes.hasEnded(entry, clock.last()))

        clock.add(millis("2026-10-25", "12:00"))
        val afterEnd = useCases.get(entry.id)!!
        // 时间已过，但记录未发生任何自动变化，不存在完成/取消之类的业务状态
        assertEquals(entry, afterEnd)
        assertNull(afterEnd.trashedAt)
        assertNull(afterEnd.zoneConfirmedAt)
        assertTrue(CalendarTimes.hasEnded(afterEnd, clock.last()))
        // 仍属于今日日程，且可在历史日期查询到真实记录
        assertEquals(listOf("上午例会"), titles(CalendarRange.TODAY))
        assertEquals(listOf("上午例会"), titlesOn(LocalDate.of(2026, 10, 25)))

        // 更早的历史日程仍可按日期查到，但不属于未来七天
        useCases.create(
            input(title = "历史日程", startDate = "2026-10-20", startTime = "09:00", endTime = "10:00"),
        ).getOrThrow()
        assertEquals(listOf("历史日程"), titlesOn(LocalDate.of(2026, 10, 20)))
        assertTrue(titles(CalendarRange.NEXT_SEVEN_DAYS).none { it == "历史日程" })
    }

    @Test
    fun 普通删除进入回收站并从正常查询排除但不永久删除() = runBlocking {
        clock.add(millis("2026-10-25", "09:00"))
        val entry = useCases.create(
            input(title = "将被删除", startDate = "2026-10-25", startTime = "10:00", endTime = "11:00"),
        ).getOrThrow()
        clock.add(millis("2026-10-25", "10:00"))
        useCases.trash(entry.id).getOrThrow()

        assertTrue(titlesAll().isEmpty())
        assertTrue(titles(CalendarRange.TODAY).isEmpty())
        assertTrue(titlesOn(LocalDate.of(2026, 10, 25)).isEmpty())

        val kept = useCases.get(entry.id)
        assertNotNull("回收站记录必须保留以供恢复", kept)
        assertEquals(millis("2026-10-25", "10:00"), kept!!.trashedAt)
        assertEquals(millis("2026-10-25", "10:00"), kept.updatedAt)
    }

    private suspend fun titles(range: CalendarRange): List<String> =
        useCases.observe(range).first().map { it.title }

    private suspend fun titlesOn(day: LocalDate): List<String> =
        useCases.observeOnDay(day).first().map { it.title }

    private suspend fun titlesAll(): List<String> =
        useCases.observeAll().first().map { it.title }
}
