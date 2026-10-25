package com.zhideer.app

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.zhideer.app.data.db.ZhiDeerDatabase
import com.zhideer.app.data.repository.OccurrenceSubTaskRecordsRepositoryImpl
import com.zhideer.app.data.repository.SubTasksRepositoryImpl
import com.zhideer.app.data.repository.TaskOccurrenceRepositoryImpl
import com.zhideer.app.data.repository.TasksRepositoryImpl
import com.zhideer.app.domain.model.OccurrenceSubTaskRecord
import com.zhideer.app.domain.model.RecurrenceFrequency
import com.zhideer.app.domain.model.RecurrenceRule
import com.zhideer.app.domain.model.TaskStatus
import com.zhideer.app.domain.repository.OccurrenceSubTaskRecordsRepository
import com.zhideer.app.domain.repository.SubTasksRepository
import com.zhideer.app.domain.repository.TaskOccurrenceRepository
import com.zhideer.app.domain.repository.TasksRepository
import com.zhideer.app.domain.usecase.OccurrenceSubTaskUseCases
import com.zhideer.app.domain.usecase.RecurrenceUseCases
import com.zhideer.app.domain.usecase.SubTaskUseCases
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
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/**
 * T15/#16 验收测试：重复待办的每一次出现可按父待办的子任务步骤清单执行——
 * 每个出现（occurrence）维度的步骤完成状态独立（完成 3 月 1 日出现的「步骤 A」
 * 不影响 3 月 2 日出现的同一步骤），新出现初始未完成、不沿用上次勾选状态；
 * 父待办增删改子任务模板后未来出现的步骤清单随之变化、已记录的历史出现行级不变；
 * 全部步骤完成不自动完成该次出现、更不自动完成父待办（GLOSSARY：分别确认，互不联动）；
 * 例外日/被延期走的原日不产生步骤记录，跳过恢复后以出现行存在为准；
 * 离线重开读取完整，中文界面提示。
 * 全部走真实 Room 文件库与统一用例入口，固定注入时钟与 id 生成器。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OccurrenceSubTaskUseCasesTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private lateinit var dbFile: File
    private lateinit var db: ZhiDeerDatabase
    private lateinit var tasksRepository: TasksRepository
    private lateinit var subTasksRepository: SubTasksRepository
    private lateinit var occurrenceRepository: TaskOccurrenceRepository
    private lateinit var recordsRepository: OccurrenceSubTaskRecordsRepository
    private lateinit var subTaskUseCases: SubTaskUseCases
    private lateinit var taskUseCases: TaskUseCases
    private lateinit var recurrenceUseCases: RecurrenceUseCases
    private lateinit var occurrenceSubTaskUseCases: OccurrenceSubTaskUseCases
    private val clock = mutableListOf(0L)
    private val idSeq = AtomicInteger(0)

    private fun millis(date: String, time: String = "10:00"): Long =
        LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()

    private fun epochDay(date: String): Long = LocalDate.parse(date).toEpochDay()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dbFile = context.getDatabasePath("t15_test.db")
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
        tasksRepository = TasksRepositoryImpl(db.taskDao(), Dispatchers.IO)
        subTasksRepository = SubTasksRepositoryImpl(db.subTaskDao(), Dispatchers.IO)
        occurrenceRepository = TaskOccurrenceRepositoryImpl(db.taskOccurrenceDao(), Dispatchers.IO)
        recordsRepository = OccurrenceSubTaskRecordsRepositoryImpl(db.occurrenceSubTaskRecordDao(), Dispatchers.IO)
        subTaskUseCases = SubTaskUseCases(
            repository = subTasksRepository,
            tasksRepository = tasksRepository,
            idGen = { "sub-${idSeq.incrementAndGet()}" },
            now = { clock.last() },
        )
        taskUseCases = TaskUseCases(
            repository = tasksRepository,
            idGen = { "task-${idSeq.incrementAndGet()}" },
            now = { clock.last() },
            zoneId = zone.id,
            occurrenceRepository = occurrenceRepository,
        )
        recurrenceUseCases = RecurrenceUseCases(
            tasksRepository = tasksRepository,
            occurrenceRepository = occurrenceRepository,
            now = { clock.last() },
        )
        occurrenceSubTaskUseCases = OccurrenceSubTaskUseCases(
            tasksRepository = tasksRepository,
            occurrenceRepository = occurrenceRepository,
            recordsRepository = recordsRepository,
            subTasksRepository = subTasksRepository,
            now = { clock.last() },
            idGen = { "osr-${idSeq.incrementAndGet()}" },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 每日重复待办：锚点为创建日（2026-03-01），此后每天出现。 */
    private suspend fun createDailyTask(title: String = "晨间保养"): com.zhideer.app.domain.model.TodoTask {
        clock.add(millis("2026-03-01", "09:00"))
        return taskUseCases.create(
            TaskInput(
                title = title,
                dueZoneId = zone.id,
                recurrenceRule = RecurrenceRule(frequency = RecurrenceFrequency.DAILY, intervalDays = 1),
            ),
        ).getOrThrow()
    }

    private suspend fun addSteps(taskId: String, vararg titles: String): List<String> =
        titles.map { subTaskUseCases.create(taskId, it).getOrThrow().id }

    /** 该待办在 [date] 出现行的出现记录（按原始出现日定位行，行不存在时为空清单）。 */
    private suspend fun recordsOf(taskId: String, date: String): List<OccurrenceSubTaskRecord> {
        val row = occurrenceRepository.listByTask(taskId).firstOrNull {
            it.originalEpochDay == epochDay(date)
        } ?: return emptyList()
        return recordsRepository.listByOccurrence(row.id)
    }

    private suspend fun recordMapOf(taskId: String, date: String): Map<String, OccurrenceSubTaskRecord> =
        recordsOf(taskId, date).associateBy { it.subTaskId }

    private suspend fun stepViewsOf(taskId: String, date: String, originalDate: String = date) =
        occurrenceSubTaskUseCases.observeForOccurrence(taskId, epochDay(date), epochDay(originalDate))
            .first().steps.associate { it.subTaskId to it }

    // ---------- 出现维度独立 ----------

    @Test
    fun 每次出现的步骤独立勾选互不影响() = runBlocking {
        val task = createDailyTask()
        val (a, b) = addSteps(task.id, "步骤A", "步骤B").let { it[0] to it[1] }

        // 3 月 1 日出现勾选步骤 A：不影响 3 月 2 日出现的同一步骤
        clock.add(millis("2026-03-01", "10:00"))
        occurrenceSubTaskUseCases.toggleStep(task.id, epochDay("2026-03-01"), a, done = true).getOrThrow()

        val day1 = stepViewsOf(task.id, "2026-03-01")
        val day2 = stepViewsOf(task.id, "2026-03-02")
        assertTrue(day1.getValue(a).done)
        assertFalse(day2.getValue(a).done)
        assertFalse(day1.getValue(b).done)
        assertFalse(day2.getValue(b).done)
        assertEquals(millis("2026-03-01", "10:00"), day1.getValue(a).doneAt)
        assertNull(day2.getValue(a).doneAt)

        // 3 月 2 日勾选步骤 B：3 月 1 日不受影响
        clock.add(millis("2026-03-02", "10:00"))
        occurrenceSubTaskUseCases.toggleStep(task.id, epochDay("2026-03-02"), b, done = true).getOrThrow()

        val day1Again = stepViewsOf(task.id, "2026-03-01")
        val day2Again = stepViewsOf(task.id, "2026-03-02")
        assertTrue(day1Again.getValue(a).done)
        assertFalse(day1Again.getValue(b).done)
        assertTrue(day2Again.getValue(b).done)
        assertFalse(day2Again.getValue(a).done)
        assertEquals(millis("2026-03-02", "10:00"), day2Again.getValue(b).doneAt)
    }

    @Test
    fun 新出现步骤初始未完成不沿用上次勾选状态() = runBlocking {
        val task = createDailyTask()
        val (a, b) = addSteps(task.id, "步骤A", "步骤B").let { it[0] to it[1] }

        // 3 月 1 日全部步骤勾完
        clock.add(millis("2026-03-01", "10:00"))
        occurrenceSubTaskUseCases.toggleStep(task.id, epochDay("2026-03-01"), a, done = true).getOrThrow()
        occurrenceSubTaskUseCases.toggleStep(task.id, epochDay("2026-03-01"), b, done = true).getOrThrow()
        assertTrue(stepViewsOf(task.id, "2026-03-01").values.all { it.done })

        // 3 月 2 日出现重新开始：初始全部未完成，不沿用上次勾选
        val day2 = stepViewsOf(task.id, "2026-03-02")
        assertEquals(2, day2.size)
        assertTrue(day2.values.none { it.done })
        assertTrue(recordsOf(task.id, "2026-03-02").isEmpty())
        // 撤销 3 月 2 日勾选也不回写 3 月 1 日
        occurrenceSubTaskUseCases.toggleStep(task.id, epochDay("2026-03-02"), a, done = true).getOrThrow()
        occurrenceSubTaskUseCases.toggleStep(task.id, epochDay("2026-03-02"), a, done = false).getOrThrow()
        assertTrue(stepViewsOf(task.id, "2026-03-01").getValue(a).done)
        assertFalse(stepViewsOf(task.id, "2026-03-02").getValue(a).done)
    }

    // ---------- 模板变更 ----------

    @Test
    fun 模板变更不改写已记录历史出现且未来出现随之变化() = runBlocking {
        val task = createDailyTask()
        val (a, b) = addSteps(task.id, "步骤A", "步骤B").let { it[0] to it[1] }

        clock.add(millis("2026-03-01", "10:00"))
        occurrenceSubTaskUseCases.toggleStep(task.id, epochDay("2026-03-01"), a, done = true).getOrThrow()
        val before = recordsOf(task.id, "2026-03-01")
        assertEquals(1, before.size)

        // 变更模板：改 A 标题、删除 B、新增 C（未来出现清单随之变化）
        clock.add(millis("2026-03-01", "11:00"))
        subTaskUseCases.update(a, "步骤A改").getOrThrow()
        subTaskUseCases.delete(b).getOrThrow()
        val c = addSteps(task.id, "步骤C").single()

        // 已记录的历史出现：行级完全不变（id、subTaskId、done、doneAt、updatedAt）
        val after = recordsOf(task.id, "2026-03-01")
        assertEquals(before, after)
        assertEquals(before.map { it.id }, after.map { it.id })
        assertTrue(after.all { it.done && it.doneAt == millis("2026-03-01", "10:00") })

        // 历史出现仍按模板新标题展示；被删步骤无记录则不再出现在清单
        val day1Views = stepViewsOf(task.id, "2026-03-01")
        assertTrue(day1Views.getValue(a).done)
        assertFalse(day1Views.containsKey(b))
        assertTrue(day1Views.containsKey(c))

        // 未来出现：新清单 [A改, C]，全部初始未完成
        val day2Views = stepViewsOf(task.id, "2026-03-02")
        assertEquals(setOf(a, c), day2Views.keys)
        assertTrue(day2Views.values.none { it.done })
    }

    // ---------- 零联动 ----------

    @Test
    fun 全部步骤完成不自动完成该次出现也不自动完成父待办() = runBlocking {
        val task = createDailyTask()
        val (a, b) = addSteps(task.id, "步骤A", "步骤B").let { it[0] to it[1] }

        clock.add(millis("2026-03-01", "10:00"))
        occurrenceSubTaskUseCases.toggleStep(task.id, epochDay("2026-03-01"), a, done = true).getOrThrow()
        occurrenceSubTaskUseCases.toggleStep(task.id, epochDay("2026-03-01"), b, done = true).getOrThrow()
        val progress = occurrenceSubTaskUseCases.progressForOccurrence(task.id, epochDay("2026-03-01")).getOrThrow()
        assertEquals(2, progress.total)
        assertEquals(2, progress.done)

        // 该次出现仍未完成（completed 与 completedAt 不动）
        val row = occurrenceRepository.listByTask(task.id).first { it.originalEpochDay == epochDay("2026-03-01") }
        assertFalse(row.completed)
        assertNull(row.completedAt)
        // 父待办也仍未完成（GLOSSARY：分别确认，互不联动）
        val parent = taskUseCases.get(task.id)!!
        assertEquals(TaskStatus.ACTIVE, parent.status)
        assertNull(parent.completedAt)

        // 反向联动同样不存在：完成/撤销该次出现不改写步骤记录
        val recordsBeforeComplete = recordsOf(task.id, "2026-03-01")
        recurrenceUseCases.completeOccurrence(task.id, epochDay("2026-03-01"), completed = true).getOrThrow()
        val recordsAfterComplete = recordsOf(task.id, "2026-03-01")
        assertEquals(2, recordsAfterComplete.size)
        assertTrue(recordsAfterComplete.all { it.done })
        assertEquals(
            recordsBeforeComplete.map { it.id to it.doneAt }.sortedBy { it.first },
            recordsAfterComplete.map { it.id to it.doneAt }.sortedBy { it.first },
        )
        recurrenceUseCases.completeOccurrence(task.id, epochDay("2026-03-01"), completed = false).getOrThrow()
        val recordsAfterUndo = recordsOf(task.id, "2026-03-01").associateBy { it.subTaskId }
        assertTrue(recordsAfterUndo.getValue(a).done)
        assertTrue(recordsAfterUndo.getValue(b).done)
    }

    // ---------- 例外日与延期 ----------

    @Test
    fun 例外日不产生步骤记录且跳过恢复后按出现行延续() = runBlocking {
        val task = createDailyTask()
        val (a, b) = addSteps(task.id, "步骤A", "步骤B").let { it[0] to it[1] }

        // 场景一：先勾步骤（出现行已建立）再跳过——记录保留，跳过期间不可再勾
        clock.add(millis("2026-03-01", "10:00"))
        occurrenceSubTaskUseCases.toggleStep(task.id, epochDay("2026-03-01"), a, done = true).getOrThrow()
        recurrenceUseCases.skipOccurrence(task.id, epochDay("2026-03-01")).getOrThrow()
        val failed = occurrenceSubTaskUseCases.toggleStep(task.id, epochDay("2026-03-01"), b, done = true)
        assertTrue(failed.isFailure)
        assertEquals("该出现日已设为例外，不再出现，无法执行步骤", failed.exceptionOrNull()!!.message)
        val duringSkip = recordMapOf(task.id, "2026-03-01")
        assertEquals(1, duringSkip.size)
        assertTrue(duringSkip.getValue(a).done)

        // 恢复例外：以出现行为准，既有记录延续、可继续执行剩余步骤
        recurrenceUseCases.restoreOccurrence(task.id, epochDay("2026-03-01")).getOrThrow()
        val restoredViews = stepViewsOf(task.id, "2026-03-01")
        assertTrue(restoredViews.getValue(a).done)
        occurrenceSubTaskUseCases.toggleStep(task.id, epochDay("2026-03-01"), b, done = true).getOrThrow()
        assertTrue(stepViewsOf(task.id, "2026-03-01").getValue(b).done)

        // 场景二：从无记录的出现直接跳过再恢复——出现行不存在则步骤从零开始
        recurrenceUseCases.skipOccurrence(task.id, epochDay("2026-03-03")).getOrThrow()
        recurrenceUseCases.restoreOccurrence(task.id, epochDay("2026-03-03")).getOrThrow()
        assertTrue(recordsOf(task.id, "2026-03-03").isEmpty())
        val day3 = stepViewsOf(task.id, "2026-03-03")
        assertTrue(day3.getValue(a).let { !it.done })
        occurrenceSubTaskUseCases.toggleStep(task.id, epochDay("2026-03-03"), a, done = true).getOrThrow()
        assertTrue(stepViewsOf(task.id, "2026-03-03").getValue(a).done)
    }

    @Test
    fun 单次延期后步骤随出现行走且被延期走的原日不可再执行() = runBlocking {
        val task = createDailyTask()
        val (a, b) = addSteps(task.id, "步骤A", "步骤B").let { it[0] to it[1] }

        clock.add(millis("2026-03-01", "10:00"))
        occurrenceSubTaskUseCases.toggleStep(task.id, epochDay("2026-03-01"), a, done = true).getOrThrow()

        // 单次延期 3 月 1 日 → 3 月 3 日（每日重复，3 月 3 日本也是几何出现日）
        recurrenceUseCases.postponeOccurrence(task.id, epochDay("2026-03-01"), epochDay("2026-03-03")).getOrThrow()

        // 被延期走的原日：不可查看与勾选，中文说明延期去向
        val originalDay = occurrenceSubTaskUseCases.toggleStep(task.id, epochDay("2026-03-01"), b, done = true)
        assertTrue(originalDay.isFailure)
        assertEquals("该次出现已单次延期到 2026-03-03", originalDay.exceptionOrNull()!!.message)
        assertTrue(stepViewsOf(task.id, "2026-03-01").isEmpty())

        // 延期入的那次（original=3月1，生效日=3月3）：步骤记录随出现行走
        val movedViews = stepViewsOf(task.id, "2026-03-03", originalDate = "2026-03-01")
        assertTrue(movedViews.getValue(a).done)

        // 3 月 3 日的常规出现（original=3月3）：独立勾选，与延期入的那次互不影响
        occurrenceSubTaskUseCases.toggleStep(task.id, epochDay("2026-03-03"), a, done = true).getOrThrow()
        val geometricViews = stepViewsOf(task.id, "2026-03-03")
        assertTrue(geometricViews.getValue(a).done)
        assertFalse(geometricViews.getValue(b).done)
        val movedMap = recordMapOf(task.id, "2026-03-01")
        assertEquals(1, movedMap.size)
        assertTrue(movedMap.getValue(a).done)
    }

    // ---------- 拒绝与幂等 ----------

    @Test
    fun 非重复待办回收站取消与非出现日拒绝执行步骤() = runBlocking {
        // 非重复待办
        clock.add(millis("2026-03-01", "09:00"))
        val plain = taskUseCases.create(TaskInput(title = "普通待办", dueZoneId = zone.id)).getOrThrow()
        val step = addSteps(plain.id, "步骤A").single()
        val plainResult = occurrenceSubTaskUseCases.toggleStep(plain.id, epochDay("2026-03-01"), step, done = true)
        assertEquals("该待办不是重复待办", plainResult.exceptionOrNull()!!.message)

        // 非出现日（早于锚点 2026-03-01）
        val daily = createDailyTask()
        val dailyStep = addSteps(daily.id, "步骤A").single()
        val offDay = occurrenceSubTaskUseCases.toggleStep(daily.id, epochDay("2026-02-28"), dailyStep, done = true)
        assertEquals("该日期不属于重复计划", offDay.exceptionOrNull()!!.message)

        // 回收站
        taskUseCases.trash(daily.id).getOrThrow()
        val trashed = occurrenceSubTaskUseCases.toggleStep(daily.id, epochDay("2026-03-01"), dailyStep, done = true)
        assertEquals("待办已进入回收站", trashed.exceptionOrNull()!!.message)

        // 取消
        val cancelled = createDailyTask(title = "会被取消的待办")
        val cancelledStep = addSteps(cancelled.id, "步骤A").single()
        taskUseCases.cancel(cancelled.id).getOrThrow()
        val cancelledResult =
            occurrenceSubTaskUseCases.toggleStep(cancelled.id, epochDay("2026-03-01"), cancelledStep, done = true)
        assertEquals("待办已取消，不再记录各次步骤", cancelledResult.exceptionOrNull()!!.message)
    }

    @Test
    fun 重复勾选同一步骤幂等不产生新记录() = runBlocking {
        val task = createDailyTask()
        val a = addSteps(task.id, "步骤A").single()

        clock.add(millis("2026-03-01", "10:00"))
        val first = occurrenceSubTaskUseCases.toggleStep(task.id, epochDay("2026-03-01"), a, done = true).getOrThrow()
        clock.add(millis("2026-03-01", "11:00"))
        val second = occurrenceSubTaskUseCases.toggleStep(task.id, epochDay("2026-03-01"), a, done = true).getOrThrow()
        assertEquals(first.id, second.id)
        assertEquals(1, recordsOf(task.id, "2026-03-01").size)
        assertEquals(millis("2026-03-01", "10:00"), second.doneAt)

        clock.add(millis("2026-03-01", "12:00"))
        val undone = occurrenceSubTaskUseCases.toggleStep(task.id, epochDay("2026-03-01"), a, done = false).getOrThrow()
        assertEquals(first.id, undone.id)
        assertNull(undone.doneAt)
        assertEquals(1, recordsOf(task.id, "2026-03-01").size)
    }

    // ---------- 进度与重开 ----------

    @Test
    fun 进度n除以m可查询且随勾选推进() = runBlocking {
        val task = createDailyTask()
        val (a, b, c) = addSteps(task.id, "步骤A", "步骤B", "步骤C")

        // 尚无记录的出现：进度 0/3
        assertEquals(0 to 3, occurrenceSubTaskUseCases.progressForOccurrence(task.id, epochDay("2026-03-02"))
            .getOrThrow().let { it.done to it.total })

        clock.add(millis("2026-03-01", "10:00"))
        occurrenceSubTaskUseCases.toggleStep(task.id, epochDay("2026-03-01"), a, done = true).getOrThrow()
        occurrenceSubTaskUseCases.toggleStep(task.id, epochDay("2026-03-01"), b, done = true).getOrThrow()
        assertEquals(2 to 3, occurrenceSubTaskUseCases.progressForOccurrence(task.id, epochDay("2026-03-01"))
            .getOrThrow().let { it.done to it.total })

        // 无效出现查询进度返回失败
        assertTrue(occurrenceSubTaskUseCases.progressForOccurrence(task.id, epochDay("2026-02-28")).isFailure)
    }

    @Test
    fun 离线重开后步骤记录与进度完整读回() = runBlocking {
        val task = createDailyTask()
        val (a, b) = addSteps(task.id, "步骤A", "步骤B")

        clock.add(millis("2026-03-01", "10:00"))
        occurrenceSubTaskUseCases.toggleStep(task.id, epochDay("2026-03-01"), a, done = true).getOrThrow()
        clock.add(millis("2026-03-01", "11:00"))
        occurrenceSubTaskUseCases.toggleStep(task.id, epochDay("2026-03-01"), b, done = true).getOrThrow()
        val before = stepViewsOf(task.id, "2026-03-01")

        // 离线重开：关闭后重新打开同一数据库文件
        db.close()
        open()

        val after = stepViewsOf(task.id, "2026-03-01")
        assertEquals(before.keys, after.keys)
        assertEquals(before.getValue(a).done, after.getValue(a).done)
        assertEquals(before.getValue(a).doneAt, after.getValue(a).doneAt)
        assertEquals(before.getValue(b).doneAt, after.getValue(b).doneAt)
        val progress = occurrenceSubTaskUseCases.progressForOccurrence(task.id, epochDay("2026-03-01")).getOrThrow()
        assertEquals(2, progress.done)
        assertEquals(2, progress.total)
    }
}
