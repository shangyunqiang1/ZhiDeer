package com.zhideer.app

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.zhideer.app.data.db.ZhiDeerDatabase
import com.zhideer.app.data.repository.TaskOccurrenceRepositoryImpl
import com.zhideer.app.data.repository.TasksRepositoryImpl
import com.zhideer.app.domain.model.RecurrenceFrequency
import com.zhideer.app.domain.model.RecurrenceRule
import com.zhideer.app.domain.model.TaskDue
import com.zhideer.app.domain.model.TaskFilter
import com.zhideer.app.domain.model.TaskStatus
import com.zhideer.app.domain.model.TodoTask
import com.zhideer.app.domain.usecase.RecurrenceUseCases
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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/**
 * T12/#13 验收测试：重复待办按固定日期规则反复出现（GLOSSARY），
 * 每一次出现分别记录完成或未完成，本次未完成不阻止下一次出现；
 * 不采用“完成后再重复推进同一记录”的上游模式。
 * 全部走真实 Room 文件库与统一用例入口，固定注入时钟。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RecurrenceUseCasesTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private lateinit var dbFile: File
    private lateinit var db: ZhiDeerDatabase
    private lateinit var taskUseCases: TaskUseCases
    private lateinit var recurrenceUseCases: RecurrenceUseCases
    private lateinit var occurrenceRepository: TaskOccurrenceRepositoryImpl
    private val clock = mutableListOf(0L)
    private val idSeq = AtomicInteger(0)
    private val callbackLog = mutableListOf<String>()

    private fun millis(date: String, time: String = "10:00", z: ZoneId = zone): Long =
        LocalDate.parse(date).atStartOfDay(z).toLocalDate()
            .atTime(java.time.LocalTime.parse(time)).atZone(z).toInstant().toEpochMilli()

    private fun epochDay(date: String): Long = LocalDate.parse(date).toEpochDay()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dbFile = context.getDatabasePath("t12_test.db")
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
        occurrenceRepository = TaskOccurrenceRepositoryImpl(db.taskOccurrenceDao(), Dispatchers.IO)
        taskUseCases = TaskUseCases(
            repository = TasksRepositoryImpl(db.taskDao(), Dispatchers.IO),
            idGen = { "task-${idSeq.incrementAndGet()}" },
            now = { clock.last() },
            zoneId = zone.id,
            occurrenceRepository = occurrenceRepository,
            onTrashed = { callbackLog += "trashed" },
            onDueChanged = { callbackLog += "dueChanged" },
            onCancelled = { callbackLog += "cancelled" },
        )
        recurrenceUseCases = RecurrenceUseCases(
            tasksRepository = TasksRepositoryImpl(db.taskDao(), Dispatchers.IO),
            occurrenceRepository = occurrenceRepository,
            now = { clock.last() },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun dailyInput(title: String = "晨间打卡", interval: Int = 1) = TaskInput(
        title = title,
        dueZoneId = zone.id,
        recurrenceRule = RecurrenceRule(frequency = RecurrenceFrequency.DAILY, intervalDays = interval),
    )

    private fun weeklyInput(
        title: String = "健身",
        weekdays: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
    ) = TaskInput(
        title = title,
        dueZoneId = zone.id,
        recurrenceRule = RecurrenceRule(frequency = RecurrenceFrequency.WEEKLY, weekdays = weekdays),
    )

    private fun monthlyInput(title: String = "月末对账", monthDay: Int = 31) = TaskInput(
        title = title,
        dueZoneId = zone.id,
        recurrenceRule = RecurrenceRule(frequency = RecurrenceFrequency.MONTHLY, monthDay = monthDay),
    )

    // ---------- 规则校验 ----------

    @Test
    fun 无效重复规则被拒绝且提示中文() = runBlocking {
        assertEquals("每日重复需间隔至少 1 天", RecurrenceRule.validate(RecurrenceRule(RecurrenceFrequency.DAILY, intervalDays = 0)))
        assertEquals("每日重复需间隔至少 1 天", RecurrenceRule.validate(RecurrenceRule(RecurrenceFrequency.DAILY, intervalDays = -2)))
        assertEquals(
            "每周重复需选择至少一个星期几",
            RecurrenceRule.validate(RecurrenceRule(RecurrenceFrequency.WEEKLY, weekdays = emptySet())),
        )
        assertNull(RecurrenceRule.validate(null))
        assertNull(RecurrenceRule.validate(RecurrenceRule(RecurrenceFrequency.DAILY, intervalDays = 1)))

        // 用例入口同样拒绝
        clock.add(millis("2026-10-25"))
        val created = taskUseCases.create(dailyInput()).getOrThrow()
        val bad = recurrenceUseCases.create(created.id, RecurrenceRule(RecurrenceFrequency.DAILY, intervalDays = 0))
        assertTrue(bad.isFailure)
        assertEquals("每日重复需间隔至少 1 天", bad.exceptionOrNull()!!.message)

        val badWeekly = taskUseCases.create(
            TaskInput(
                title = "无效周规则",
                dueZoneId = zone.id,
                recurrenceRule = RecurrenceRule(RecurrenceFrequency.WEEKLY, weekdays = emptySet()),
            ),
        )
        assertTrue(badWeekly.isFailure)
        assertEquals("每周重复需选择至少一个星期几", badWeekly.exceptionOrNull()!!.message)
    }

    // ---------- occurrence 生成 ----------

    @Test
    fun 每日规则按创建锚点与间隔生成出现() = runBlocking {
        clock.add(millis("2026-10-25"))
        val task = taskUseCases.create(dailyInput(title = "每三天浇花", interval = 3)).getOrThrow()

        val days = recurrenceUseCases.occurrencesForRange(task.id, epochDay("2026-10-25"), epochDay("2026-11-07"))
            .getOrThrow()
        assertEquals(
            listOf("2026-10-25", "2026-10-28", "2026-10-31", "2026-11-03", "2026-11-06").map(::epochDay),
            days,
        )
        // 锚点之前不出现
        val before = recurrenceUseCases.occurrencesForRange(task.id, epochDay("2026-10-20"), epochDay("2026-10-24"))
            .getOrThrow()
        assertEquals(emptyList<Long>(), before)
    }

    @Test
    fun 每周规则跨周按星期几生成出现() = runBlocking {
        // 2026-10-25 是周日；每周一三五从锚点（当天为起点下限）起生成
        clock.add(millis("2026-10-25"))
        val task = taskUseCases.create(weeklyInput()).getOrThrow()

        val days = recurrenceUseCases.occurrencesForRange(task.id, epochDay("2026-10-24"), epochDay("2026-11-07"))
            .getOrThrow()
        assertEquals(
            listOf("2026-10-26", "2026-10-28", "2026-10-30", "2026-11-02", "2026-11-04", "2026-11-06").map(::epochDay),
            days,
        )
    }

    @Test
    fun 出现锚点使用创建时锚定的时区() = runBlocking {
        // 同一 UTC 时刻，奥克兰（UTC+13）的日期比上海早一天，锚点与出现序列必须随创建时区
        val shanghai = ZoneId.of("Asia/Shanghai")
        val auckland = ZoneId.of("Pacific/Auckland")
        val utcInstant = LocalDate.parse("2026-10-25").atTime(20, 0).atZone(shanghai).toInstant().toEpochMilli()
        clock.add(utcInstant)
        assertEquals("2026-10-25", LocalDate.ofInstant(java.time.Instant.ofEpochMilli(utcInstant), shanghai).toString())
        assertEquals("2026-10-26", LocalDate.ofInstant(java.time.Instant.ofEpochMilli(utcInstant), auckland).toString())

        val shanghaiTask = taskUseCases.create(
            TaskInput(title = "上海打卡", dueZoneId = shanghai.id, recurrenceRule = RecurrenceRule(RecurrenceFrequency.DAILY)),
        ).getOrThrow()
        val aucklandTask = taskUseCases.create(
            TaskInput(title = "奥克兰打卡", dueZoneId = auckland.id, recurrenceRule = RecurrenceRule(RecurrenceFrequency.DAILY)),
        ).getOrThrow()

        assertEquals(
            listOf(epochDay("2026-10-25"), epochDay("2026-10-26")),
            recurrenceUseCases.occurrencesForRange(shanghaiTask.id, epochDay("2026-10-25"), epochDay("2026-10-26")).getOrThrow(),
        )
        assertEquals(
            listOf(epochDay("2026-10-26")),
            recurrenceUseCases.occurrencesForRange(aucklandTask.id, epochDay("2026-10-25"), epochDay("2026-10-26")).getOrThrow(),
        )
    }

    // ---------- 独立完成 ----------

    @Test
    fun 各次出现分别记录完成且互不影响() = runBlocking {
        clock.add(millis("2026-10-25"))
        val task = taskUseCases.create(weeklyInput()).getOrThrow()
        val monday = epochDay("2026-10-26")
        val wednesday = epochDay("2026-10-28")

        clock.add(millis("2026-10-26", "09:00"))
        recurrenceUseCases.completeOccurrence(task.id, monday, true).getOrThrow()
        clock.add(millis("2026-10-28", "09:30"))
        recurrenceUseCases.completeOccurrence(task.id, wednesday, true).getOrThrow()

        val records = occurrenceRepository.listByTask(task.id).sortedBy { it.occurrenceEpochDay }
        assertEquals(2, records.size)
        assertEquals(monday, records[0].occurrenceEpochDay)
        assertTrue(records[0].completed)
        assertEquals(millis("2026-10-26", "09:00"), records[0].completedAt)
        assertEquals(wednesday, records[1].occurrenceEpochDay)
        assertEquals(millis("2026-10-28", "09:30"), records[1].completedAt)
        // 两次时间戳独立
        assertTrue(records[0].completedAt != records[1].completedAt)

        // 撤销周一完成不影响周三
        clock.add(millis("2026-10-29", "08:00"))
        recurrenceUseCases.completeOccurrence(task.id, monday, false).getOrThrow()
        val after = occurrenceRepository.listByTask(task.id).sortedBy { it.occurrenceEpochDay }
        assertEquals(2, after.size)
        assertFalse(after[0].completed)
        assertNull(after[0].completedAt)
        assertTrue(after[1].completed)
    }

    @Test
    fun 本次未完成不阻止下一次出现() = runBlocking {
        clock.add(millis("2026-10-25"))
        val task = taskUseCases.create(weeklyInput()).getOrThrow()
        val monday = epochDay("2026-10-26")
        val wednesday = epochDay("2026-10-28")

        // 周一整个未处理（时钟已到周三），周三的 occurrence 仍然生成且可见
        clock.add(millis("2026-10-28", "08:00"))
        assertEquals(
            listOf(wednesday),
            recurrenceUseCases.occurrencesForRange(task.id, wednesday, wednesday).getOrThrow(),
        )
        val onWednesday = recurrenceUseCases.observeForDay(wednesday).first()
        assertEquals(listOf(task.id), onWednesday.map { it.task.id })
        assertFalse(onWednesday.single().completed)
        // 周一未完成也没有被自动补成完成
        assertEquals(
            emptyList<Long>(),
            occurrenceRepository.listByTask(task.id).filter { it.completed }.map { it.occurrenceEpochDay },
        )
    }

    @Test
    fun 重复完成同一出现保持幂等不产生重复记录() = runBlocking {
        clock.add(millis("2026-10-25"))
        val task = taskUseCases.create(dailyInput()).getOrThrow()
        val day = epochDay("2026-10-26")

        clock.add(millis("2026-10-26", "08:00"))
        recurrenceUseCases.completeOccurrence(task.id, day, true).getOrThrow()
        clock.add(millis("2026-10-26", "09:00"))
        recurrenceUseCases.completeOccurrence(task.id, day, true).getOrThrow()

        val records = occurrenceRepository.listByTask(task.id)
        assertEquals(1, records.size)
        assertEquals(millis("2026-10-26", "09:00"), records.single().completedAt)
    }

    @Test
    fun 非出现日或非重复待办完成被拒绝() = runBlocking {
        clock.add(millis("2026-10-25"))
        val recurring = taskUseCases.create(weeklyInput()).getOrThrow()
        val plain = taskUseCases.create(TaskInput(title = "普通待办", dueZoneId = zone.id)).getOrThrow()

        // 2026-10-27 周二不是一三五
        val notScheduled = recurrenceUseCases.completeOccurrence(recurring.id, epochDay("2026-10-27"), true)
        assertTrue(notScheduled.isFailure)
        assertEquals("该日期不属于重复计划", notScheduled.exceptionOrNull()!!.message)

        val notRecurring = recurrenceUseCases.completeOccurrence(plain.id, epochDay("2026-10-27"), true)
        assertTrue(notRecurring.isFailure)
        assertEquals("该待办不是重复待办", notRecurring.exceptionOrNull()!!.message)

        val missing = recurrenceUseCases.completeOccurrence("task-404", epochDay("2026-10-27"), true)
        assertTrue(missing.isFailure)
        assertEquals("待办不存在或已永久删除", missing.exceptionOrNull()!!.message)
    }

    // ---------- 列表呈现：今天过滤与逾期 ----------

    @Test
    fun 今天未完成的重复待办出现在今天过滤() = runBlocking {
        // 锚点日当天出现（每日规则创建即当天一次），当天未完成 → 今天过滤
        clock.add(millis("2026-10-25", "08:00"))
        val daily = taskUseCases.create(dailyInput()).getOrThrow()
        clock.add(millis("2026-10-25", "12:00"))

        assertEquals(listOf("晨间打卡"), titles(TaskFilter.TODAY))
        assertEquals(emptyList<String>(), titles(TaskFilter.OVERDUE))
        assertEquals(daily.id, recurrenceUseCases.observeForDay(epochDay("2026-10-25")).first().single().task.id)
    }

    @Test
    fun 错过的出现不自动完成也不静默消失_明确逾期() = runBlocking {
        clock.add(millis("2026-10-25"))
        val daily = taskUseCases.create(dailyInput()).getOrThrow()

        // 时钟直接推进两天：10-25 的出现未处理，10-27 又出现。
        // 下一次未完成出现（10-25）已错过：整行按逾期呈现，今天的出现要先把错过补完再追。
        clock.add(millis("2026-10-27", "08:00"))
        assertEquals(listOf("晨间打卡"), titles(TaskFilter.OVERDUE))
        assertEquals(emptyList<String>(), titles(TaskFilter.TODAY))
        // 记录没有被自动补完成
        assertEquals(
            emptyList<Long>(),
            occurrenceRepository.listByTask(daily.id).filter { it.completed }.map { it.occurrenceEpochDay },
        )
        // 下一次未完成出现是错过的最早一天（10-25）
        val overdueDay = epochDay("2026-10-25")
        val state = TaskDue.RecurringDueState(
            nextUnfinishedDay = overdueDay,
            todayEpochDay = epochDay("2026-10-27"),
        )
        assertTrue(TaskDue.matches(taskUseCases.get(daily.id)!!, TaskFilter.OVERDUE, clock.last(), state))
    }

    @Test
    fun 完成今天的出现后历史错过仍显示逾期() = runBlocking {
        clock.add(millis("2026-10-25"))
        val daily = taskUseCases.create(dailyInput()).getOrThrow()
        clock.add(millis("2026-10-26", "08:00"))
        // 10-25 出现已被错过，10-26 是今天：代表次是最早的未完成出现（10-25）
        taskUseCases.complete(taskUseCases.observe(TaskFilter.OVERDUE).first().single().id, completed = true)

        // 完成的是 10-25 那一次，10-26 今天仍未完成
        val records = occurrenceRepository.listByTask(daily.id)
        assertEquals(listOf(epochDay("2026-10-25")), records.filter { it.completed }.map { it.occurrenceEpochDay })
        assertEquals(TaskStatus.ACTIVE, taskUseCases.get(daily.id)!!.status)
        assertEquals(listOf("晨间打卡"), titles(TaskFilter.TODAY))
        assertEquals(emptyList<String>(), titles(TaskFilter.OVERDUE))
    }

    @Test
    fun 每周规则下一次出现在未来时归入即将到期() = runBlocking {
        // 2026-10-25 周日创建，下一次出现是周一 10-26
        clock.add(millis("2026-10-25", "12:00"))
        val weekly = taskUseCases.create(weeklyInput()).getOrThrow()

        assertEquals(emptyList<String>(), titles(TaskFilter.TODAY))
        assertEquals(emptyList<String>(), titles(TaskFilter.OVERDUE))
        assertEquals(listOf("健身"), titles(TaskFilter.UPCOMING))

        // 周一当天出现在今天过滤
        clock.add(millis("2026-10-26", "08:00"))
        assertEquals(listOf("健身"), titles(TaskFilter.TODAY))
        assertEquals(weekly.id, taskUseCases.observe(TaskFilter.TODAY).first().single().id)
    }

    @Test
    fun 列表完成动作作用于下一次未完成出现且整体保持进行中() = runBlocking {
        // 锚点日 2026-10-25 出现一次，当天完成它
        clock.add(millis("2026-10-25", "08:00"))
        val daily = taskUseCases.create(dailyInput()).getOrThrow()
        clock.add(millis("2026-10-25", "09:00"))
        taskUseCases.complete(daily.id, completed = true).getOrThrow()

        // 整体状态保持进行中（GLOSSARY：每次出现分别记录完成）
        assertEquals(TaskStatus.ACTIVE, taskUseCases.get(daily.id)!!.status)
        assertEquals(
            listOf(epochDay("2026-10-25")),
            occurrenceRepository.listByTask(daily.id).filter { it.completed }.map { it.occurrenceEpochDay },
        )
        // 今天的已完成，今天过滤不再出现；明天的新出现仍然出现且未被自动完成
        assertEquals(emptyList<String>(), titles(TaskFilter.TODAY))
        clock.add(millis("2026-10-26", "08:00"))
        assertEquals(listOf("晨间打卡"), titles(TaskFilter.TODAY))
        assertEquals(
            emptyList<Long>(),
            occurrenceRepository.listByTask(daily.id).filter { it.completed }
                .map { it.occurrenceEpochDay }.filter { it == epochDay("2026-10-26") },
        )
    }

    @Test
    fun 撤销完成撤销最近一次已完成出现() = runBlocking {
        clock.add(millis("2026-10-25"))
        val daily = taskUseCases.create(dailyInput()).getOrThrow()

        clock.add(millis("2026-10-25", "11:00"))
        taskUseCases.complete(daily.id, completed = true).getOrThrow()
        clock.add(millis("2026-10-26", "11:00"))
        taskUseCases.complete(daily.id, completed = true).getOrThrow()
        assertEquals(2, occurrenceRepository.listByTask(daily.id).count { it.completed })

        clock.add(millis("2026-10-26", "12:00"))
        taskUseCases.complete(daily.id, completed = false).getOrThrow()
        val records = occurrenceRepository.listByTask(daily.id)
        // 只撤销最近一次（10-26），10-25 保持完成
        assertEquals(listOf(epochDay("2026-10-25")), records.filter { it.completed }.map { it.occurrenceEpochDay })
    }

    // ---------- 规则的创建 / 编辑 / 移除 ----------

    @Test
    fun 可以为既有待办设置修改与移除重复规则() = runBlocking {
        clock.add(millis("2026-10-25"))
        val task = taskUseCases.create(TaskInput(title = "初始无规则", dueZoneId = zone.id)).getOrThrow()

        val withRule = recurrenceUseCases.create(task.id, weeklyInput().recurrenceRule!!).getOrThrow()
        assertEquals(RecurrenceFrequency.WEEKLY, withRule.recurrenceRule!!.frequency)

        // 已有规则再次 create 被拒绝，应使用 update
        val again = recurrenceUseCases.create(task.id, dailyInput().recurrenceRule!!)
        assertTrue(again.isFailure)
        assertEquals("该待办已设置重复规则，请使用编辑修改", again.exceptionOrNull()!!.message)

        clock.add(millis("2026-10-26", "09:00"))
        val updated = recurrenceUseCases.update(task.id, dailyInput(interval = 2).recurrenceRule!!).getOrThrow()
        assertEquals(RecurrenceFrequency.DAILY, updated.recurrenceRule?.frequency)
        assertEquals(2, updated.recurrenceRule?.intervalDays)
        assertTrue(updated.updatedAt > task.updatedAt)

        val removed = recurrenceUseCases.remove(task.id).getOrThrow()
        assertNull(removed.recurrenceRule)
    }

    @Test
    fun 通过创建输入直接携带重复规则保存() = runBlocking {
        clock.add(millis("2026-10-25"))
        val created = taskUseCases.create(dailyInput(interval = 2)).getOrThrow()
        val readBack = taskUseCases.get(created.id)!!
        assertNotNull(readBack.recurrenceRule)
        assertEquals(RecurrenceFrequency.DAILY, readBack.recurrenceRule?.frequency)
        assertEquals(2, readBack.recurrenceRule?.intervalDays)

        // 编辑时更新规则
        clock.add(millis("2026-10-26", "09:00"))
        val updated = taskUseCases.update(created.id, weeklyInput(weekdays = setOf(DayOfWeek.SUNDAY))).getOrThrow()
        assertEquals(RecurrenceFrequency.WEEKLY, updated.recurrenceRule?.frequency)
        assertEquals(setOf(DayOfWeek.SUNDAY), updated.recurrenceRule?.weekdays)
    }

    // ---------- 持久化 ----------

    @Test
    fun 重复规则与各次记录关闭重开后保留() = runBlocking {
        clock.add(millis("2026-10-25"))
        val daily = taskUseCases.create(dailyInput()).getOrThrow()
        clock.add(millis("2026-10-26", "09:00"))
        recurrenceUseCases.completeOccurrence(daily.id, epochDay("2026-10-26"), true).getOrThrow()
        db.close()
        open()

        val readBack = taskUseCases.get(daily.id)
        assertNotNull(readBack!!.recurrenceRule)
        assertEquals(RecurrenceFrequency.DAILY, readBack.recurrenceRule!!.frequency)
        val records = occurrenceRepository.listByTask(daily.id)
        assertEquals(1, records.size)
        assertTrue(records.single().completed)
        assertEquals(epochDay("2026-10-26"), records.single().occurrenceEpochDay)
        assertEquals(millis("2026-10-26", "09:00"), records.single().completedAt)
    }

    // ---------- 与既有回调链共存 ----------

    @Test
    fun 重复待办删除取消编辑仍触发既有回调() = runBlocking {
        clock.add(millis("2026-10-25"))
        val a = taskUseCases.create(weeklyInput(title = "将被取消")).getOrThrow()
        val b = taskUseCases.create(weeklyInput(title = "将被删除")).getOrThrow()

        taskUseCases.update(a.id, weeklyInput(title = "改名")).getOrThrow()
        assertTrue(callbackLog.contains("dueChanged"))
        taskUseCases.cancel(a.id)
        assertTrue(callbackLog.contains("cancelled"))
        taskUseCases.trash(b.id)
        assertTrue(callbackLog.contains("trashed"))

        // 取消（保留业务记录，仍在“全部”）/回收站（不再出现在任何过滤）；
        // occurrence 维度同样按状态排除
        assertEquals(listOf("改名"), titles(TaskFilter.ALL))
        assertEquals(emptyList<String>(), titles(TaskFilter.TODAY))
        assertEquals(
            emptyList<String>(),
            recurrenceUseCases.observeForDay(epochDay("2026-10-26")).first().map { it.task.id },
        )
    }

    @Test
    fun observeForDay返回当天出现与完成状态() = runBlocking {
        clock.add(millis("2026-10-25"))
        val daily = taskUseCases.create(dailyInput()).getOrThrow()
        val weekly = taskUseCases.create(weeklyInput()).getOrThrow()

        clock.add(millis("2026-10-26", "09:00"))
        recurrenceUseCases.completeOccurrence(daily.id, epochDay("2026-10-26"), true).getOrThrow()

        val onMonday = recurrenceUseCases.observeForDay(epochDay("2026-10-26")).first()
        assertEquals(2, onMonday.size)
        val dailyRow = onMonday.single { it.task.id == daily.id }
        val weeklyRow = onMonday.single { it.task.id == weekly.id }
        assertTrue(dailyRow.completed)
        assertFalse(weeklyRow.completed)
        assertEquals(epochDay("2026-10-26"), dailyRow.occurrenceEpochDay)

        // 周二只有每日待办，且未完成
        val onTuesday = recurrenceUseCases.observeForDay(epochDay("2026-10-27")).first()
        assertEquals(listOf(daily.id), onTuesday.map { it.task.id })
        assertFalse(onTuesday.single().completed)
    }

    @Test
    fun 规则摘要为中文() {
        assertEquals("每天", RecurrenceRule(RecurrenceFrequency.DAILY).summary())
        assertEquals("每 3 天", RecurrenceRule(RecurrenceFrequency.DAILY, intervalDays = 3).summary())
        assertEquals(
            "每周 一、三、五",
            RecurrenceRule(RecurrenceFrequency.WEEKLY, weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)).summary(),
        )
        assertEquals("每周 日", RecurrenceRule(RecurrenceFrequency.WEEKLY, weekdays = setOf(DayOfWeek.SUNDAY)).summary())
    }

    // ---------- 每月规则（T13/#14）----------

    @Test
    fun 每月规则校验拒绝无效第几日() = runBlocking {
        assertEquals("每月重复需选择 1-31 日", RecurrenceRule.validate(RecurrenceRule(RecurrenceFrequency.MONTHLY, monthDay = 0)))
        assertEquals("每月重复需选择 1-31 日", RecurrenceRule.validate(RecurrenceRule(RecurrenceFrequency.MONTHLY, monthDay = 32)))
        assertNull(RecurrenceRule.validate(RecurrenceRule(RecurrenceFrequency.MONTHLY, monthDay = 1)))
        assertNull(RecurrenceRule.validate(RecurrenceRule(RecurrenceFrequency.MONTHLY, monthDay = 31)))

        // 用例入口同样拒绝
        clock.add(millis("2026-10-25"))
        val created = taskUseCases.create(dailyInput()).getOrThrow()
        val bad = recurrenceUseCases.create(created.id, RecurrenceRule(RecurrenceFrequency.MONTHLY, monthDay = 0))
        assertTrue(bad.isFailure)
        assertEquals("每月重复需选择 1-31 日", bad.exceptionOrNull()!!.message)

        // 创建输入直接携带也拒绝
        val badInput = taskUseCases.create(
            TaskInput(
                title = "无效月规则",
                dueZoneId = zone.id,
                recurrenceRule = RecurrenceRule(RecurrenceFrequency.MONTHLY, monthDay = 32),
            ),
        )
        assertTrue(badInput.isFailure)
        assertEquals("每月重复需选择 1-31 日", badInput.exceptionOrNull()!!.message)
    }

    @Test
    fun 每月15日常规与跨年出现() = runBlocking {
        clock.add(millis("2026-11-15"))
        val task = taskUseCases.create(monthlyInput(title = "月度账单", monthDay = 15)).getOrThrow()

        val days = recurrenceUseCases.occurrencesForRange(task.id, epochDay("2026-11-01"), epochDay("2027-01-31"))
            .getOrThrow()
        assertEquals(listOf("2026-11-15", "2026-12-15", "2027-01-15").map(::epochDay), days)
        // 锚点之前不出现
        val before = recurrenceUseCases.occurrencesForRange(task.id, epochDay("2026-11-01"), epochDay("2026-11-14"))
            .getOrThrow()
        assertEquals(emptyList<Long>(), before)
    }

    @Test
    fun 每月31日短月落到月末且三月回到31日不漂移() = runBlocking {
        // issue #14 示例：每月 31 日遇二月落月末（2026 非闰年 → 2-28），三月仍回到 31 日，不漂移成每月 28 日
        clock.add(millis("2026-01-31"))
        val task = taskUseCases.create(monthlyInput(title = "月末对账", monthDay = 31)).getOrThrow()

        val days = recurrenceUseCases.occurrencesForRange(task.id, epochDay("2026-01-01"), epochDay("2026-04-30"))
            .getOrThrow()
        assertEquals(
            listOf("2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30").map(::epochDay),
            days,
        )
    }

    @Test
    fun 每月29与30日跨闰年出现() = runBlocking {
        // 锚点 2026-12-29：跨年 12-29 → 1-29；2027-02（非闰）落 28 日，2028-02（闰）回到 29 日
        clock.add(millis("2026-12-29"))
        val task29 = taskUseCases.create(monthlyInput(title = "闰日维护", monthDay = 29)).getOrThrow()

        assertEquals(
            listOf("2026-12-29", "2027-01-29").map(::epochDay),
            recurrenceUseCases.occurrencesForRange(task29.id, epochDay("2026-12-01"), epochDay("2027-01-31")).getOrThrow(),
        )
        assertEquals(
            listOf(epochDay("2027-02-28")),
            recurrenceUseCases.occurrencesForRange(task29.id, epochDay("2027-02-01"), epochDay("2027-02-28")).getOrThrow(),
        )
        assertEquals(
            listOf(epochDay("2028-02-29")),
            recurrenceUseCases.occurrencesForRange(task29.id, epochDay("2028-02-01"), epochDay("2028-02-29")).getOrThrow(),
        )

        // 第 30 日：闰年二月（29 天）落 29 日
        val task30 = taskUseCases.create(monthlyInput(title = "三十号任务", monthDay = 30)).getOrThrow()
        assertEquals(
            listOf(epochDay("2028-02-29")),
            recurrenceUseCases.occurrencesForRange(task30.id, epochDay("2028-02-01"), epochDay("2028-02-29")).getOrThrow(),
        )
    }

    @Test
    fun 每月各次出现分别记录完成且未完成不阻止下一次() = runBlocking {
        clock.add(millis("2026-01-31"))
        val task = taskUseCases.create(monthlyInput(title = "月末复盘", monthDay = 31)).getOrThrow()
        val jan31 = epochDay("2026-01-31")
        val feb28 = epochDay("2026-02-28")

        clock.add(millis("2026-01-31", "18:00"))
        recurrenceUseCases.completeOccurrence(task.id, jan31, true).getOrThrow()

        // 一月的出现已处理，二月的短月出现照常生成且未完成（GLOSSARY：本次未完成不阻止下一次）
        clock.add(millis("2026-02-28", "08:00"))
        val onFeb = recurrenceUseCases.observeForDay(feb28).first()
        assertEquals(listOf(task.id), onFeb.map { it.task.id })
        assertFalse(onFeb.single().completed)
        // 非出现日被拒绝（2-27 不是该规则的出现日）
        val notScheduled = recurrenceUseCases.completeOccurrence(task.id, epochDay("2026-02-27"), true)
        assertTrue(notScheduled.isFailure)
        assertEquals("该日期不属于重复计划", notScheduled.exceptionOrNull()!!.message)

        // 二月独立完成，与一月记录互不影响
        clock.add(millis("2026-02-28", "09:00"))
        recurrenceUseCases.completeOccurrence(task.id, feb28, true).getOrThrow()
        val records = occurrenceRepository.listByTask(task.id).sortedBy { it.occurrenceEpochDay }
        assertEquals(2, records.size)
        assertEquals(jan31, records[0].occurrenceEpochDay)
        assertTrue(records[0].completed)
        assertEquals(feb28, records[1].occurrenceEpochDay)
        assertTrue(records[1].completed)

        // 撤销二月完成不影响一月
        clock.add(millis("2026-02-28", "10:00"))
        recurrenceUseCases.completeOccurrence(task.id, feb28, false).getOrThrow()
        val after = occurrenceRepository.listByTask(task.id).sortedBy { it.occurrenceEpochDay }
        assertEquals(2, after.size)
        assertTrue(after[0].completed)
        assertFalse(after[1].completed)
    }

    @Test
    fun 错过的每月出现按逾期呈现且不自动完成() = runBlocking {
        // 锚点 2026-01-31 出现一次，未处理；时钟直接推进到 2026-03-30：
        // 1-31 与 2-28 两次出现均已错过，整行按逾期呈现
        clock.add(millis("2026-01-31", "08:00"))
        val task = taskUseCases.create(monthlyInput(title = "月度盘点", monthDay = 31)).getOrThrow()

        clock.add(millis("2026-03-30", "09:00"))
        assertEquals(listOf("月度盘点"), titles(TaskFilter.OVERDUE))
        assertEquals(emptyList<String>(), titles(TaskFilter.TODAY))
        assertEquals(
            emptyList<Long>(),
            occurrenceRepository.listByTask(task.id).filter { it.completed }.map { it.occurrenceEpochDay },
        )
        val state = TaskDue.RecurringDueState(
            nextUnfinishedDay = epochDay("2026-01-31"),
            todayEpochDay = epochDay("2026-03-30"),
        )
        assertTrue(TaskDue.matches(taskUseCases.get(task.id)!!, TaskFilter.OVERDUE, clock.last(), state))

        // 通过列表完成动作处理最早一次错过（1-31）后，2-28 仍错过：分别记录、不静默消失
        taskUseCases.complete(task.id, completed = true).getOrThrow()
        assertEquals(
            listOf(epochDay("2026-01-31")),
            occurrenceRepository.listByTask(task.id).filter { it.completed }.map { it.occurrenceEpochDay },
        )
        assertEquals(listOf("月度盘点"), titles(TaskFilter.OVERDUE))
    }

    @Test
    fun 每月规则与各次记录关闭重开后保留() = runBlocking {
        clock.add(millis("2026-01-31"))
        val task = taskUseCases.create(monthlyInput(title = "月度回顾", monthDay = 31)).getOrThrow()
        clock.add(millis("2026-02-28", "09:00"))
        recurrenceUseCases.completeOccurrence(task.id, epochDay("2026-02-28"), true).getOrThrow()
        db.close()
        open()

        val readBack = taskUseCases.get(task.id)
        assertNotNull(readBack!!.recurrenceRule)
        assertEquals(RecurrenceFrequency.MONTHLY, readBack.recurrenceRule!!.frequency)
        assertEquals(31, readBack.recurrenceRule.monthDay)
        val records = occurrenceRepository.listByTask(task.id)
        assertEquals(1, records.size)
        assertTrue(records.single().completed)
        assertEquals(epochDay("2026-02-28"), records.single().occurrenceEpochDay)
        // 重开后三月仍回到 31 日，不因持久化漂移
        assertEquals(
            listOf(epochDay("2026-03-31")),
            recurrenceUseCases.occurrencesForRange(task.id, epochDay("2026-03-01"), epochDay("2026-03-31")).getOrThrow(),
        )
    }

    @Test
    fun 每月锚点跟随创建时区() = runBlocking {
        // 与每日一致：同一 UTC 时刻，奥克兰（UTC+13）锚点日期比上海晚一天（1-31 vs 1-30）
        val shanghai = ZoneId.of("Asia/Shanghai")
        val auckland = ZoneId.of("Pacific/Auckland")
        val utcInstant = LocalDate.parse("2026-01-30").atTime(20, 0).atZone(shanghai).toInstant().toEpochMilli()
        clock.add(utcInstant)
        assertEquals("2026-01-30", LocalDate.ofInstant(java.time.Instant.ofEpochMilli(utcInstant), shanghai).toString())
        assertEquals("2026-01-31", LocalDate.ofInstant(java.time.Instant.ofEpochMilli(utcInstant), auckland).toString())

        val shanghaiTask = taskUseCases.create(
            TaskInput(
                title = "上海月报",
                dueZoneId = shanghai.id,
                recurrenceRule = RecurrenceRule(RecurrenceFrequency.MONTHLY, monthDay = 30),
            ),
        ).getOrThrow()
        val aucklandTask = taskUseCases.create(
            TaskInput(
                title = "奥克兰月报",
                dueZoneId = auckland.id,
                recurrenceRule = RecurrenceRule(RecurrenceFrequency.MONTHLY, monthDay = 30),
            ),
        ).getOrThrow()

        // 上海锚点 1-30：1-30 即出现（含），2 月短月落 28 日，3 月回到 30 日
        assertEquals(
            listOf("2026-01-30", "2026-02-28", "2026-03-30").map(::epochDay),
            recurrenceUseCases.occurrencesForRange(shanghaiTask.id, epochDay("2026-01-30"), epochDay("2026-03-31")).getOrThrow(),
        )
        // 奥克兰锚点 1-31：1-30 早于锚点不出现，首现为 2-28（短月回落）
        assertEquals(
            listOf("2026-02-28", "2026-03-30").map(::epochDay),
            recurrenceUseCases.occurrencesForRange(aucklandTask.id, epochDay("2026-01-30"), epochDay("2026-03-31")).getOrThrow(),
        )
    }

    @Test
    fun 每月规则摘要为中文() {
        assertEquals("每月 15 日", RecurrenceRule(RecurrenceFrequency.MONTHLY, monthDay = 15).summary())
        assertEquals("每月 31 日", RecurrenceRule(RecurrenceFrequency.MONTHLY, monthDay = 31).summary())
    }

    // ---------- 例外日期与单次跳过（T14/#15）----------

    @Test
    fun 例外日的出现不再生成且完成被拒绝() = runBlocking {
        clock.add(millis("2026-10-25"))
        val task = taskUseCases.create(dailyInput()).getOrThrow()
        val exceptionDay = epochDay("2026-10-27")

        // 通过规则编辑加入例外日（例外随规则持久化）
        recurrenceUseCases.update(
            task.id,
            task.recurrenceRule!!.copy(exceptions = setOf(exceptionDay)),
        ).getOrThrow()

        // 被排除的出现日不再出现
        assertEquals(
            listOf("2026-10-25", "2026-10-26", "2026-10-28").map(::epochDay),
            recurrenceUseCases.occurrencesForRange(task.id, epochDay("2026-10-25"), epochDay("2026-10-28"))
                .getOrThrow(),
        )
        // 例外日当天列表不再出现
        assertEquals(
            emptyList<String>(),
            recurrenceUseCases.observeForDay(exceptionDay).first().map { it.task.title },
        )
        // 例外日不再要求完成
        val completed = recurrenceUseCases.completeOccurrence(task.id, exceptionDay, true)
        assertTrue(completed.isFailure)
        assertEquals("该出现日已设为例外，不再要求完成", completed.exceptionOrNull()!!.message)
    }

    @Test
    fun 跳过与完成区分_跳过已完成被拒绝_撤销后可跳过() = runBlocking {
        clock.add(millis("2026-10-25"))
        val task = taskUseCases.create(weeklyInput()).getOrThrow()
        val monday = epochDay("2026-10-26")

        clock.add(millis("2026-10-26", "09:00"))
        recurrenceUseCases.completeOccurrence(task.id, monday, true).getOrThrow()

        // 已完成的出现日不能跳过（跳过与完成互斥、区分）
        val skippedAfterDone = recurrenceUseCases.skipOccurrence(task.id, monday)
        assertTrue(skippedAfterDone.isFailure)
        assertEquals("该次出现已完成，请先撤销完成再跳过", skippedAfterDone.exceptionOrNull()!!.message)

        // 撤销完成之后可以跳过；跳过不是完成——不产生已完成记录
        recurrenceUseCases.completeOccurrence(task.id, monday, false).getOrThrow()
        recurrenceUseCases.skipOccurrence(task.id, monday).getOrThrow()
        val records = occurrenceRepository.listByTask(task.id)
        assertTrue(records.none { it.completed })
        assertEquals(
            emptyList<Long>(),
            recurrenceUseCases.occurrencesForRange(task.id, monday, monday).getOrThrow(),
        )
        // 非出现日不能跳过
        val notScheduled = recurrenceUseCases.skipOccurrence(task.id, epochDay("2026-10-27"))
        assertTrue(notScheduled.isFailure)
        assertEquals("该日期不属于重复计划", notScheduled.exceptionOrNull()!!.message)
    }

    @Test
    fun 跳过错过的出现后不再逾期且今天照常出现() = runBlocking {
        clock.add(millis("2026-10-25", "08:00"))
        val daily = taskUseCases.create(dailyInput()).getOrThrow()

        // 10-25 的出现被错过，10-27 整行按逾期呈现
        clock.add(millis("2026-10-27", "08:00"))
        assertEquals(listOf("晨间打卡"), titles(TaskFilter.OVERDUE))

        // 仅本次跳过错过的 10-25 与 10-26：不再要求完成，今天（10-27）的出现照常
        recurrenceUseCases.skipOccurrence(daily.id, epochDay("2026-10-25")).getOrThrow()
        recurrenceUseCases.skipOccurrence(daily.id, epochDay("2026-10-26")).getOrThrow()
        assertEquals(emptyList<String>(), titles(TaskFilter.OVERDUE))
        assertEquals(listOf("晨间打卡"), titles(TaskFilter.TODAY))
        // 跳过不产生任何完成记录
        assertEquals(
            emptyList<Long>(),
            occurrenceRepository.listByTask(daily.id).filter { it.completed }.map { it.occurrenceEpochDay },
        )
    }

    @Test
    fun 恢复被跳过的出现后该日重新出现且保持未完成() = runBlocking {
        clock.add(millis("2026-10-25"))
        val task = taskUseCases.create(weeklyInput()).getOrThrow()
        val monday = epochDay("2026-10-26")

        recurrenceUseCases.skipOccurrence(task.id, monday).getOrThrow()
        assertEquals(emptyList<Long>(), recurrenceUseCases.occurrencesForRange(task.id, monday, monday).getOrThrow())

        clock.add(millis("2026-10-26", "08:00"))
        recurrenceUseCases.restoreOccurrence(task.id, monday).getOrThrow()
        val onMonday = recurrenceUseCases.observeForDay(monday).first()
        assertEquals(listOf(task.id), onMonday.map { it.task.id })
        assertFalse(onMonday.single().completed)
    }

    @Test
    fun 重复跳过与恢复幂等不重复产生记录() = runBlocking {
        clock.add(millis("2026-10-25"))
        val task = taskUseCases.create(dailyInput()).getOrThrow()
        val day = epochDay("2026-10-26")

        repeat(3) { recurrenceUseCases.skipOccurrence(task.id, day).getOrThrow() }
        val skipped = taskUseCases.get(task.id)!!.recurrenceRule!!
        assertEquals(setOf(day), skipped.exceptions)

        // 恢复未跳过的日期同样幂等成功
        recurrenceUseCases.restoreOccurrence(task.id, epochDay("2026-10-27")).getOrThrow()
        recurrenceUseCases.restoreOccurrence(task.id, day).getOrThrow()
        val restored = taskUseCases.get(task.id)!!.recurrenceRule!!
        assertEquals(emptySet<Long>(), restored.exceptions)
    }

    @Test
    fun 规则调整保留既有完成记录且只影响未来出现() = runBlocking {
        clock.add(millis("2026-10-25"))
        val task = taskUseCases.create(weeklyInput()).getOrThrow()
        val monday = epochDay("2026-10-26")
        clock.add(millis("2026-10-26", "09:00"))
        recurrenceUseCases.completeOccurrence(task.id, monday, true).getOrThrow()
        val before = occurrenceRepository.listByTask(task.id)

        // 调整规则（每周一三五 → 每周二四）：只影响以后的出现，已完成历史不被删除或改写
        clock.add(millis("2026-10-27", "09:00"))
        recurrenceUseCases.update(
            task.id,
            RecurrenceRule(RecurrenceFrequency.WEEKLY, weekdays = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY)),
        ).getOrThrow()

        assertEquals(before, occurrenceRepository.listByTask(task.id))
        // 未来出现按新规则生成，旧出现日不再是出现日
        assertEquals(
            listOf("2026-10-27", "2026-10-29", "2026-11-03", "2026-11-05").map(::epochDay),
            recurrenceUseCases.occurrencesForRange(task.id, epochDay("2026-10-27"), epochDay("2026-11-06"))
                .getOrThrow(),
        )
        // 已完成的 10-26 记录保留可核对（不再属于新规则的出现序列）
        val kept = occurrenceRepository.listByTask(task.id).single()
        assertEquals(monday, kept.occurrenceEpochDay)
        assertTrue(kept.completed)
    }

    @Test
    fun 跨月例外日排除每月出现() = runBlocking {
        clock.add(millis("2026-01-31"))
        val task = taskUseCases.create(monthlyInput(title = "月末对账", monthDay = 31)).getOrThrow()
        val feb28 = epochDay("2026-02-28")

        // 一月的出现已完成后，单次跳过二月的短月出现；三月回到 31 日不受影响
        clock.add(millis("2026-01-31", "18:00"))
        recurrenceUseCases.completeOccurrence(task.id, epochDay("2026-01-31"), true).getOrThrow()
        recurrenceUseCases.skipOccurrence(task.id, feb28).getOrThrow()
        assertEquals(
            listOf("2026-01-31", "2026-03-31", "2026-04-30").map(::epochDay),
            recurrenceUseCases.occurrencesForRange(task.id, epochDay("2026-01-01"), epochDay("2026-04-30"))
                .getOrThrow(),
        )
        // 下一次未完成出现跳过例外日
        clock.add(millis("2026-02-27", "09:00"))
        val completedDays = occurrenceRepository.listByTask(task.id)
            .filter { it.completed }.map { it.occurrenceEpochDay }.toSet()
        assertEquals(
            epochDay("2026-03-31"),
            RecurrenceUseCases.nextUnfinishedDay(
                taskUseCases.get(task.id)!!,
                completedDays,
                TaskDue.todayEpochDay(zone.id, clock.last()),
            ),
        )
    }

    @Test
    fun 例外日关闭重开后保留() = runBlocking {
        clock.add(millis("2026-10-25"))
        val daily = taskUseCases.create(dailyInput()).getOrThrow()
        clock.add(millis("2026-10-26", "09:00"))
        recurrenceUseCases.skipOccurrence(daily.id, epochDay("2026-10-26")).getOrThrow()
        recurrenceUseCases.completeOccurrence(daily.id, epochDay("2026-10-27"), true).getOrThrow()
        db.close()
        open()

        val readBack = taskUseCases.get(daily.id)!!
        assertEquals(setOf(epochDay("2026-10-26")), readBack.recurrenceRule!!.exceptions)
        assertEquals(
            emptyList<Long>(),
            recurrenceUseCases.occurrencesForRange(daily.id, epochDay("2026-10-26"), epochDay("2026-10-26"))
                .getOrThrow(),
        )
        // 完成记录与例外各自保留
        val records = occurrenceRepository.listByTask(daily.id)
        assertEquals(listOf(epochDay("2026-10-27")), records.filter { it.completed }.map { it.occurrenceEpochDay })
        // 通过规则编辑写入的例外同样持久化
        recurrenceUseCases.update(
            daily.id,
            readBack.recurrenceRule.copy(exceptions = setOf(epochDay("2026-10-26"), epochDay("2026-10-28"))),
        ).getOrThrow()
        db.close()
        open()
        assertEquals(
            setOf(epochDay("2026-10-26"), epochDay("2026-10-28")),
            taskUseCases.get(daily.id)!!.recurrenceRule!!.exceptions,
        )
        assertEquals(
            // 例外日 10-26、10-28 被排除；10-27 已完成但仍是出现日，10-29 照常
            listOf(epochDay("2026-10-27"), epochDay("2026-10-29")),
            recurrenceUseCases.occurrencesForRange(daily.id, epochDay("2026-10-26"), epochDay("2026-10-29"))
                .getOrThrow(),
        )
    }

    @Test
    fun 规则摘要显示例外数() {
        assertEquals("每天", RecurrenceRule(RecurrenceFrequency.DAILY).summary())
        assertEquals(
            "每天，已排除 2 天",
            RecurrenceRule(RecurrenceFrequency.DAILY, exceptions = setOf(1L, 5L)).summary(),
        )
        assertEquals(
            "每周 一，已排除 1 天",
            RecurrenceRule(RecurrenceFrequency.WEEKLY, weekdays = setOf(DayOfWeek.MONDAY), exceptions = setOf(9L)).summary(),
        )
    }

    @Test
    fun 例外与跳过拒绝非法输入提示中文() = runBlocking {
        clock.add(millis("2026-10-25"))
        val recurring = taskUseCases.create(dailyInput()).getOrThrow()
        val plain = taskUseCases.create(TaskInput(title = "普通待办", dueZoneId = zone.id)).getOrThrow()

        val missing = recurrenceUseCases.skipOccurrence("task-404", epochDay("2026-10-26"))
        assertTrue(missing.isFailure)
        assertEquals("待办不存在或已永久删除", missing.exceptionOrNull()!!.message)

        val notRecurring = recurrenceUseCases.skipOccurrence(plain.id, epochDay("2026-10-26"))
        assertTrue(notRecurring.isFailure)
        assertEquals("该待办不是重复待办", notRecurring.exceptionOrNull()!!.message)

        // 锚点之前不出现，也不能跳过
        val beforeAnchor = recurrenceUseCases.skipOccurrence(recurring.id, epochDay("2026-10-24"))
        assertTrue(beforeAnchor.isFailure)
        assertEquals("该日期不属于重复计划", beforeAnchor.exceptionOrNull()!!.message)

        // 恢复不存在的例外：幂等成功
        assertTrue(recurrenceUseCases.restoreOccurrence(recurring.id, epochDay("2026-10-26")).isSuccess)
    }

    private suspend fun titles(filter: TaskFilter): List<String> =
        taskUseCases.observe(filter).first().map { it.title }
}
