package com.zhideer.app

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.zhideer.app.data.ai.AiProposalStore
import com.zhideer.app.data.ai.AiSessionStore
import com.zhideer.app.data.ai.DeepSeekChatClient
import com.zhideer.app.data.ai.InMemoryAiConfigStore
import com.zhideer.app.data.db.ZhiDeerDatabase
import com.zhideer.app.data.repository.CalendarRemindersRepositoryImpl
import com.zhideer.app.data.repository.CalendarRepositoryImpl
import com.zhideer.app.data.repository.NotesRepositoryImpl
import com.zhideer.app.data.repository.OccurrenceSubTaskRecordsRepositoryImpl
import com.zhideer.app.data.repository.SubTasksRepositoryImpl
import com.zhideer.app.data.repository.TaskOccurrenceRepositoryImpl
import com.zhideer.app.data.repository.TasksRepositoryImpl
import com.zhideer.app.domain.model.AiProposalKind
import com.zhideer.app.domain.model.AiProposalScope
import com.zhideer.app.domain.model.AiProposalSendOutcome
import com.zhideer.app.domain.model.AiProposalStatus
import com.zhideer.app.domain.model.RecurrenceFrequency
import com.zhideer.app.domain.model.RecurrenceRule
import com.zhideer.app.domain.model.TaskStatus
import com.zhideer.app.domain.model.TodoTask
import com.zhideer.app.domain.usecase.AiChatUseCases
import com.zhideer.app.domain.usecase.AiContextAuthorizer
import com.zhideer.app.domain.usecase.AiContextCategory
import com.zhideer.app.domain.usecase.AiContextProvider
import com.zhideer.app.domain.usecase.AiContextSnapshot
import com.zhideer.app.domain.usecase.AiProposalTargetMatcher
import com.zhideer.app.domain.usecase.AiProposalUseCases
import com.zhideer.app.domain.usecase.CalendarReminderUseCases
import com.zhideer.app.domain.usecase.CalendarUseCases
import com.zhideer.app.domain.usecase.NoteUseCases
import com.zhideer.app.domain.usecase.OccurrenceSubTaskUseCases
import com.zhideer.app.domain.usecase.RecurrenceUseCases
import com.zhideer.app.domain.usecase.SubTaskUseCases
import com.zhideer.app.domain.usecase.TaskInput
import com.zhideer.app.domain.usecase.TaskUseCases
import com.zhideer.app.domain.usecase.proposalChangePreviewLines
import com.zhideer.app.domain.usecase.proposalRecurrenceLines
import com.zhideer.app.domain.usecase.proposalScopeNotice
import com.zhideer.app.platform.AlarmScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * T22/#23 验收测试：通过 AI 管理重复及父子待办。
 *
 * 协议（system prompt 与模型约定）：task 提案可携带 recurrence（每日/每周/每月、例外日期）
 * 与 subTasks（子任务步骤模板）；修改/取消重复待办必须携带 scope（this/future，
 * 即「仅本次/本次及以后」），未说明范围时设备端中文追问、不落提案。
 *
 * 全部走真实 Room 文件库 + 统一用例入口（Task/Recurrence/SubTask/OccurrenceSubTask），
 * 模型回复由 MockWebServer 固定注入；真实模型表现待配置 Key 后由 Root 观察。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AiRecurrenceProposalTest {

    private companion object {
        const val KEY = "sk-test-1234567890abcdef"
        const val MODEL = "deepseek-chat"
        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    }

    private lateinit var server: MockWebServer
    private lateinit var configStore: InMemoryAiConfigStore
    private lateinit var sessionStore: AiSessionStore
    private lateinit var proposalStore: AiProposalStore
    private lateinit var client: DeepSeekChatClient
    private lateinit var chat: AiChatUseCases
    private lateinit var proposals: AiProposalUseCases
    private lateinit var dbFile: File
    private lateinit var db: ZhiDeerDatabase
    private lateinit var tasksRepository: TasksRepositoryImpl
    private lateinit var occurrenceRepository: TaskOccurrenceRepositoryImpl
    private lateinit var subTaskUseCases: SubTaskUseCases
    private lateinit var taskUseCases: TaskUseCases
    private lateinit var recurrenceUseCases: RecurrenceUseCases
    private lateinit var occurrenceSubTaskUseCases: OccurrenceSubTaskUseCases
    private lateinit var targetMatcher: AiProposalTargetMatcher
    private lateinit var storeDir: File
    private val idSeq = AtomicInteger(0)
    private var clock: Long = 0L
    private val hookLog = mutableListOf<String>()

    private fun millis(date: String, time: String = "09:00"): Long =
        LocalDate.parse(date).atTime(java.time.LocalTime.parse(time)).atZone(ZONE).toInstant().toEpochMilli()

    private fun epochDay(date: String): Long = LocalDate.parse(date).toEpochDay()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dbFile = context.getDatabasePath("t22_test.db")
        dbFile.parentFile?.mkdirs()
        context.deleteDatabase(dbFile.name)
        db = Room.databaseBuilder<ZhiDeerDatabase>(context = context, name = dbFile.absolutePath)
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        tasksRepository = TasksRepositoryImpl(db.taskDao(), Dispatchers.IO)
        occurrenceRepository = TaskOccurrenceRepositoryImpl(db.taskOccurrenceDao(), Dispatchers.IO)
        val subTasksRepository = SubTasksRepositoryImpl(db.subTaskDao(), Dispatchers.IO)
        val occurrenceRecordsRepository = OccurrenceSubTaskRecordsRepositoryImpl(db.occurrenceSubTaskRecordDao(), Dispatchers.IO)
        val notesRepository = NotesRepositoryImpl(db.noteDao(), Dispatchers.IO)
        val calendarRepository = CalendarRepositoryImpl(db.calendarEntryDao(), Dispatchers.IO)
        val calendarRemindersRepository = CalendarRemindersRepositoryImpl(db.calendarReminderDao(), Dispatchers.IO)
        clock = millis("2026-10-25")
        hookLog.clear()
        subTaskUseCases = SubTaskUseCases(
            repository = subTasksRepository,
            tasksRepository = tasksRepository,
            idGen = { "sub-${idSeq.incrementAndGet()}" },
            now = { clock },
        )
        taskUseCases = TaskUseCases(
            repository = tasksRepository,
            idGen = { "task-${idSeq.incrementAndGet()}" },
            now = { clock },
            zoneId = ZONE.id,
            onTrashed = { hookLog += "trashed:$it" },
            onDueChanged = { hookLog += "dueChanged:$it" },
            onCancelled = { hookLog += "cancelled:$it" },
            occurrenceRepository = occurrenceRepository,
        )
        recurrenceUseCases = RecurrenceUseCases(
            tasksRepository = tasksRepository,
            occurrenceRepository = occurrenceRepository,
            now = { clock },
        )
        occurrenceSubTaskUseCases = OccurrenceSubTaskUseCases(
            tasksRepository = tasksRepository,
            occurrenceRepository = occurrenceRepository,
            recordsRepository = occurrenceRecordsRepository,
            subTasksRepository = subTasksRepository,
            now = { clock },
        )
        targetMatcher = AiProposalTargetMatcher(
            tasksRepository = tasksRepository,
            notesRepository = notesRepository,
            calendarRepository = calendarRepository,
        )
        server = MockWebServer()
        server.start()
        configStore = InMemoryAiConfigStore()
        configStore.save(com.zhideer.app.domain.model.AiConfig(apiKey = KEY, model = MODEL, baseUrl = server.url("/v1").toString()))
        storeDir = File(System.getProperty("java.io.tmpdir"), "t22-test-${System.nanoTime()}")
        check(storeDir.mkdirs() || storeDir.isDirectory) { "无法创建测试目录" }
        sessionStore = AiSessionStore(File(storeDir, "ai_sessions.preferences_pb"))
        proposalStore = AiProposalStore(File(storeDir, "ai_proposals.preferences_pb"))
        client = DeepSeekChatClient(connectTimeoutMs = 15_000, readTimeoutMs = 5_000)
        chat = AiChatUseCases(
            configStore = configStore,
            sessionStore = sessionStore,
            client = client,
            idGen = { "session-${idSeq.incrementAndGet()}" },
            now = { clock },
            contextAuthorizer = AiContextAuthorizer(),
            contextProvider = AiContextProvider { AiContextSnapshot(generatedAtText = "测试快照") },
            zoneId = ZONE,
        )
        proposals = AiProposalUseCases(
            chat = chat,
            store = proposalStore,
            taskUseCases = taskUseCases,
            noteUseCases = NoteUseCases(
                repository = notesRepository,
                idGen = { "note-${idSeq.incrementAndGet()}" },
                now = { clock },
            ),
            calendarUseCases = CalendarUseCases(
                repository = calendarRepository,
                idGen = { "entry-${idSeq.incrementAndGet()}" },
                now = { clock },
                zoneId = ZONE.id,
            ),
            calendarReminderUseCases = CalendarReminderUseCases(
                repository = calendarRemindersRepository,
                calendarRepository = calendarRepository,
                scheduler = FakeAlarmScheduler(),
                idGen = { "calrem-${idSeq.incrementAndGet()}" },
                now = { clock },
            ),
            targetMatcher = targetMatcher,
            // T22/#23：重复规则、子任务与出现维度经既有真实入口执行
            recurrenceUseCases = recurrenceUseCases,
            subTaskUseCases = subTaskUseCases,
            idGen = { "proposal-${idSeq.incrementAndGet()}" },
            now = { clock },
            zoneId = ZONE,
        )
    }

    @After
    fun tearDown() {
        client.close()
        server.close()
        sessionStore.close()
        proposalStore.close()
        db.close()
        storeDir.deleteRecursively()
    }

    // ---------- 工具 ----------

    /** 把模型回复文本拆成多个 SSE 增量块并以 [DONE] 结束（模拟真实流式）。 */
    private fun sseProposal(fullText: String, chunks: Int = 3): MockResponse {
        val size = maxOf(1, chunks)
        val step = (fullText.length + size - 1) / size
        val body = buildString {
            fullText.chunked(step).forEach { delta ->
                append("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"${escapeJson(delta)}\"}}]}\n\n")
            }
            append("data: [DONE]\n\n")
        }
        return MockResponse.Builder()
            .code(200)
            .addHeader("Content-Type", "text/event-stream")
            .body(body)
            .build()
    }

    /** 非流式补全响应（解析失败后的自动重试走非流式）。 */
    private fun completionProposal(fullText: String): MockResponse = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "application/json")
        .body("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"${escapeJson(fullText)}\"}}]}")
        .build()

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private fun proposalBlock(vararg items: String): String =
        "已为你起草操作提案，请确认。\n```json\n{\"proposals\":[${items.joinToString(",")} ]}\n```"

    private fun recurrenceJson(
        frequency: String? = null,
        intervalDays: Int? = null,
        weekdays: List<String>? = null,
        monthDay: Int? = null,
        exceptions: List<String>? = null,
        text: String? = null,
    ): String = buildString {
        append("{")
        val parts = mutableListOf<String>()
        if (frequency != null) parts += "\"frequency\":\"$frequency\""
        if (intervalDays != null) parts += "\"intervalDays\":$intervalDays"
        if (weekdays != null) parts += "\"weekdays\":[${weekdays.joinToString(",") { "\"$it\"" }}]"
        if (monthDay != null) parts += "\"monthDay\":$monthDay"
        if (exceptions != null) parts += "\"exceptions\":[${exceptions.joinToString(",") { "\"$it\"" }}]"
        if (text != null) parts += "\"text\":\"${escapeJson(text)}\""
        append(parts.joinToString(","))
        append("}")
    }

    private fun taskJson(
        title: String = "晨间跑步",
        dueDate: String? = null,
        priority: String? = null,
        recurrence: String? = null,
        subTasks: List<String>? = null,
    ): String = buildString {
        append("{\"kind\":\"task\",\"title\":\"${escapeJson(title)}\"")
        if (dueDate != null) append(",\"dueDate\":\"$dueDate\"")
        if (priority != null) append(",\"priority\":\"$priority\"")
        if (recurrence != null) append(",\"recurrence\":$recurrence")
        if (subTasks != null) append(",\"subTasks\":[${subTasks.joinToString(",") { "\"${escapeJson(it)}\" " }}]")
        append("}")
    }

    private fun modifyJson(
        targetTitle: String,
        changesJson: String,
        scope: String? = null,
        occurrenceDate: String? = null,
    ): String = buildString {
        append("{\"kind\":\"modify\",\"target\":{\"kind\":\"task\",\"title\":\"${escapeJson(targetTitle)}\"},\"changes\":$changesJson")
        if (scope != null) append(",\"scope\":\"$scope\"")
        if (occurrenceDate != null) append(",\"occurrenceDate\":\"$occurrenceDate\"")
        append("}")
    }

    private fun deleteJson(
        targetTitle: String,
        action: String,
        scope: String? = null,
        occurrenceDate: String? = null,
    ): String = buildString {
        append("{\"kind\":\"delete\",\"target\":{\"kind\":\"task\",\"title\":\"${escapeJson(targetTitle)}\"},\"action\":\"$action\"")
        if (scope != null) append(",\"scope\":\"$scope\"")
        if (occurrenceDate != null) append(",\"occurrenceDate\":\"$occurrenceDate\"")
        append("}")
    }

    private suspend fun send(text: String = "帮我建一个待办"): Result<AiProposalSendOutcome> =
        proposals.sendAndIngest(null, text) {}

    private fun authorize(vararg categories: AiContextCategory) {
        chat.authorizeContext(categories.toSet())
    }

    /** 直接经统一用例入口预置一条重复待办（锚点为 [createdDate] 在创建时区下的日期）。 */
    private suspend fun presetRecurringTask(
        title: String,
        rule: RecurrenceRule,
        createdDate: String = "2026-10-20",
    ): TodoTask {
        clock = millis(createdDate)
        val task = taskUseCases.create(TaskInput(title = title, dueZoneId = ZONE.id)).getOrThrow()
        recurrenceUseCases.create(task.id, rule).getOrThrow()
        clock = millis("2026-10-25")
        return taskUseCases.get(task.id)!!
    }

    private suspend fun taskCount(): Int = tasksRepository.observeTasks().first().size

    // ---------- 1. 中文规则解析、提案预览与确认保存 ----------

    @Test
    fun 中文每日规则提案展示重复与子任务_确认后经真实入口保存并可查询出现() = runBlocking {
        server.enqueue(
            sseProposal(
                proposalBlock(
                    taskJson(
                        title = "晨间跑步",
                        recurrence = recurrenceJson(frequency = "daily", intervalDays = 2),
                        subTasks = listOf("拉伸", "喝水"),
                    ),
                ),
            ),
        )

        val outcome = send("每两天早上跑步，步骤是拉伸、喝水").getOrThrow() as AiProposalSendOutcome.ProposalCreated
        val proposal = outcome.proposal
        assertEquals(AiProposalStatus.PENDING, proposal.status)
        val item = proposal.items.single()
        assertEquals(AiProposalKind.TASK, item.kind)
        assertEquals("DAILY", item.recurrence!!.frequency)
        assertEquals(2, item.recurrence!!.intervalDays)
        assertEquals(listOf("拉伸", "喝水"), item.subTasks)
        // 提案预览必须显示重复规则与子任务（验收第 1 条）
        val lines = proposalRecurrenceLines(item)
        assertTrue("预览应含规则: $lines", lines.any { it.contains("每 2 天") })
        assertTrue("预览应含子任务: $lines", lines.any { it.contains("子任务") && it.contains("拉伸") })
        // 确认前绝不写入
        assertEquals(0, taskCount())

        val confirmed = proposals.confirmProposal(proposal.id).getOrThrow()
        assertEquals(AiProposalStatus.EXECUTED, confirmed.status)
        val task = tasksRepository.observeTasks().first().single()
        assertEquals("晨间跑步", task.title)
        assertEquals(RecurrenceFrequency.DAILY, task.recurrenceRule!!.frequency)
        assertEquals(2, task.recurrenceRule!!.intervalDays)
        assertEquals(listOf("拉伸", "喝水"), subTaskUseCases.observeByParent(task.id).first().map { it.title })
        // 实际查询结果：从锚点起每 2 天一次
        val anchor = epochDay("2026-10-25")
        assertEquals(
            listOf(anchor, anchor + 2, anchor + 4),
            recurrenceUseCases.occurrencesForRange(task.id, anchor, anchor + 4).getOrThrow(),
        )
        val receipt = confirmed.resultMessage!!
        assertTrue("回执应说明重复规则: $receipt", receipt.contains("重复"))
        assertTrue("回执应说明子任务: $receipt", receipt.contains("子任务"))
    }


    @Test
    fun 中文每周规则与每月月末例外日期在提案中可见且确认后保存() = runBlocking {
        server.enqueue(
            sseProposal(
                proposalBlock(
                    taskJson(title = "健身", recurrence = recurrenceJson(text = "每周一、三、五")),
                    taskJson(
                        title = "月末对账",
                        recurrence = recurrenceJson(text = "每月最后一天", exceptions = listOf("2026-11-30")),
                    ),
                ),
            ),
        )

        val outcome = send("每周一三五健身；每月最后一天对账，11月30日除外")
            .getOrThrow() as AiProposalSendOutcome.ProposalCreated
        val (weekly, monthly) = outcome.proposal.items
        assertEquals("WEEKLY", weekly.recurrence!!.frequency)
        assertEquals(listOf("MONDAY", "WEDNESDAY", "FRIDAY"), weekly.recurrence!!.weekdays)
        assertTrue(
            "预览应显示中文规则: ${proposalRecurrenceLines(weekly)}",
            proposalRecurrenceLines(weekly).any { it.contains("每周 一、三、五") },
        )
        assertEquals("MONTHLY", monthly.recurrence!!.frequency)
        assertEquals(31, monthly.recurrence!!.monthDay)
        val monthlyLines = proposalRecurrenceLines(monthly)
        assertTrue("预览应说明月末处理: $monthlyLines", monthlyLines.any { it.contains("月末处理") && it.contains("最后一天") })
        assertTrue("预览应显示例外日期: $monthlyLines", monthlyLines.any { it.contains("2026-11-30") })
        assertEquals(0, taskCount())

        val confirmed = proposals.confirmProposal(outcome.proposal.id).getOrThrow()
        assertEquals(AiProposalStatus.EXECUTED, confirmed.status)
        val tasks = tasksRepository.observeTasks().first()
        assertEquals(2, tasks.size)
        val monthEnd = tasks.first { it.title == "月末对账" }
        assertEquals(RecurrenceFrequency.MONTHLY, monthEnd.recurrenceRule!!.frequency)
        assertEquals(31, monthEnd.recurrenceRule!!.monthDay)
        assertEquals(setOf(epochDay("2026-11-30")), monthEnd.recurrenceRule!!.exceptions)
        // 实际查询：2026 年 11 月只有 30 天，31 日规则落在 11/30 且被例外排除；12 月回到 31 日
        val days = recurrenceUseCases.occurrencesForRange(
            monthEnd.id,
            epochDay("2026-11-01"),
            epochDay("2026-12-31"),
        ).getOrThrow()
        assertFalse("例外日期不出现", days.contains(epochDay("2026-11-30")))
        assertTrue("12 月回到 31 日", days.contains(epochDay("2026-12-31")))
        val weeklyTask = tasks.first { it.title == "健身" }
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), weeklyTask.recurrenceRule!!.weekdays)
    }

    @Test
    fun 修改重复待办未说明范围时中文追问且不落提案() = runBlocking {
        val task = presetRecurringTask("晨跑", RecurrenceRule(frequency = RecurrenceFrequency.DAILY))
        authorize(AiContextCategory.TASKS)
        server.enqueue(sseProposal(proposalBlock(modifyJson("晨跑", "{\"title\":\"晨间跑步\"}"))))

        val result = send("把晨跑改个名字")

        assertTrue("未说明范围应被拒绝", result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue("应中文追问范围: $message", message.contains("重复") && message.contains("仅本次") && message.contains("本次及以后"))
        assertTrue("未说明范围不落提案", proposalStore.load().isEmpty())
        assertEquals("未说明范围前不改动记录", "晨跑", tasksRepository.getTask(task.id)!!.title)
    }

    @Test
    fun 本次及以后修改规则与子任务经真实入口生效_过去完成记录保留() = runBlocking {
        val task = presetRecurringTask(
            "周会",
            RecurrenceRule(frequency = RecurrenceFrequency.WEEKLY, weekdays = setOf(DayOfWeek.MONDAY)),
            createdDate = "2026-10-19",
        )
        recurrenceUseCases.completeOccurrence(task.id, epochDay("2026-10-19"), true).getOrThrow()
        authorize(AiContextCategory.TASKS)
        server.enqueue(
            sseProposal(
                proposalBlock(
                    modifyJson(
                        targetTitle = "周会",
                        changesJson = "{\"title\":\"项目周会\",\"recurrence\":${recurrenceJson(frequency = "weekly", weekdays = listOf("tuesday", "thursday"))}," +
                            "\"subTasks\":[\"准备议程\",\"记录结论\"]}",
                        scope = "future",
                    ),
                ),
            ),
        )

        val outcome = send("以后周会改成每周二四，改叫项目周会，加两个步骤")
            .getOrThrow() as AiProposalSendOutcome.ProposalCreated
        val item = outcome.proposal.items.single()
        assertEquals(AiProposalScope.THIS_AND_FUTURE, item.scope)
        val preview = proposalChangePreviewLines(item, ZONE)
        assertTrue("预览应含旧→新规则: $preview", preview.any { it.contains("每周 一") && it.contains("每周 二、四") })
        assertTrue("预览应含新增子任务: $preview", preview.any { it.contains("子任务") && it.contains("准备议程") })

        val confirmed = proposals.confirmProposal(outcome.proposal.id).getOrThrow()
        val updated = tasksRepository.getTask(task.id)!!
        assertEquals("项目周会", updated.title)
        assertEquals(setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), updated.recurrenceRule!!.weekdays)
        assertEquals(
            listOf("准备议程", "记录结论"),
            subTaskUseCases.observeByParent(task.id).first().map { it.title },
        )
        // 过去记录保持不变（本次及以后只改模板与规则，不删除/改写既有出现记录）
        assertTrue(
            "过去完成记录保留",
            occurrenceRepository.listByTask(task.id).any { it.originalEpochDay == epochDay("2026-10-19") && it.completed },
        )
        assertTrue("回执说明规则更新: ${confirmed.resultMessage}", confirmed.resultMessage!!.contains("重复规则已更新"))
    }

    @Test
    fun 仅本次延期不挪动后续日期_同日两次明确提示() = runBlocking {
        val task = presetRecurringTask("晨跑", RecurrenceRule(frequency = RecurrenceFrequency.DAILY), createdDate = "2026-10-20")
        recurrenceUseCases.completeOccurrence(task.id, epochDay("2026-10-21"), true).getOrThrow()
        authorize(AiContextCategory.TASKS)
        server.enqueue(
            sseProposal(
                proposalBlock(
                    modifyJson(
                        targetTitle = "晨跑",
                        changesJson = "{\"dueDate\":\"2026-10-27\"}",
                        scope = "this",
                        occurrenceDate = "2026-10-26",
                    ),
                ),
            ),
        )

        val outcome = send("把10月26日那次晨跑延到10月27日")
            .getOrThrow() as AiProposalSendOutcome.ProposalCreated
        val item = outcome.proposal.items.single()
        assertEquals(AiProposalScope.THIS_OCCURRENCE, item.scope)
        assertEquals(epochDay("2026-10-26"), item.occurrenceEpochDay)
        assertTrue("同日两次应明确提示: ${proposalScopeNotice(item)}", proposalScopeNotice(item)!!.contains("两次"))
        assertTrue("确认前不写出现记录", occurrenceRepository.listByTask(task.id).none { it.postponed })

        val confirmed = proposals.confirmProposal(outcome.proposal.id).getOrThrow()
        val rows = occurrenceRepository.listByTask(task.id)
        val moved = rows.single { it.originalEpochDay == epochDay("2026-10-26") }
        assertEquals("这一次移到目标日", epochDay("2026-10-27"), moved.effectiveEpochDay)
        // 后续日期不变（规则几何未被改写）
        assertEquals(
            listOf(epochDay("2026-10-28"), epochDay("2026-10-29"), epochDay("2026-10-30")),
            recurrenceUseCases.occurrencesForRange(
                task.id,
                epochDay("2026-10-28"),
                epochDay("2026-10-30"),
            ).getOrThrow(),
        )
        // 同日两次：目标日原本的常规出现 + 延期而来，分别记录完成
        assertEquals(2, recurrenceUseCases.observeForDay(epochDay("2026-10-27")).first().size)
        // 过去记录保持不变
        assertTrue(
            "过去完成记录保留",
            rows.any { it.originalEpochDay == epochDay("2026-10-21") && it.completed },
        )
        assertTrue("回执说明后续日期不变: ${confirmed.resultMessage}", confirmed.resultMessage!!.contains("后续日期不变"))
    }

    @Test
    fun 取消仅本次与本次及以后_过去记录保留且提醒联动复用真实入口() = runBlocking {
        val task = presetRecurringTask("吃药", RecurrenceRule(frequency = RecurrenceFrequency.DAILY), createdDate = "2026-10-20")
        recurrenceUseCases.completeOccurrence(task.id, epochDay("2026-10-21"), true).getOrThrow()
        authorize(AiContextCategory.TASKS)

        // 仅本次：跳过 10/26 这一次（例外日），其余日期与既有记录不变
        server.enqueue(
            sseProposal(
                proposalBlock(deleteJson("吃药", action = "cancel", scope = "this", occurrenceDate = "2026-10-26")),
            ),
        )
        val first = send("10月26日那次不吃了").getOrThrow() as AiProposalSendOutcome.ProposalCreated
        assertTrue(
            "取消本次说明: ${proposalScopeNotice(first.proposal.items.single())}",
            proposalScopeNotice(first.proposal.items.single())!!.contains("不再出现"),
        )
        proposals.confirmProposal(first.proposal.id).getOrThrow()
        val afterSkip = tasksRepository.getTask(task.id)!!
        assertEquals(TaskStatus.ACTIVE, afterSkip.status)
        assertTrue("该次成为例外日", afterSkip.recurrenceRule!!.exceptions.contains(epochDay("2026-10-26")))

        // 本次及以后：整组取消，取消钩子（提醒停止）经 TaskUseCases 真实入口触发
        server.enqueue(sseProposal(proposalBlock(deleteJson("吃药", action = "cancel", scope = "future"))))
        val second = send("以后都不吃了").getOrThrow() as AiProposalSendOutcome.ProposalCreated
        val confirmed = proposals.confirmProposal(second.proposal.id).getOrThrow()
        assertEquals(TaskStatus.CANCELLED, tasksRepository.getTask(task.id)!!.status)
        assertTrue("提醒停止联动经真实入口触发", hookLog.contains("cancelled:${task.id}"))
        assertTrue("回执说明过去记录保留", confirmed.resultMessage!!.contains("过去各次记录保留"))
        assertTrue(
            "过去完成记录保留",
            occurrenceRepository.listByTask(task.id).any { it.originalEpochDay == epochDay("2026-10-21") && it.completed },
        )
    }

    @Test
    fun 重复子任务各次独立_提前完成父待办不自动勾选子任务() = runBlocking {
        val task = presetRecurringTask("服药流程", RecurrenceRule(frequency = RecurrenceFrequency.DAILY), createdDate = "2026-10-20")
        subTaskUseCases.create(task.id, "倒水").getOrThrow()
        subTaskUseCases.create(task.id, "服药").getOrThrow()
        val firstStep = subTaskUseCases.observeByParent(task.id).first().first().id

        // 10/26 的「倒水」勾选不影响 10/27 的同一步骤（每一次出现独立）
        occurrenceSubTaskUseCases.toggleStep(task.id, epochDay("2026-10-26"), firstStep, true).getOrThrow()
        assertEquals(
            "新出现不沿用上一次勾选",
            0,
            occurrenceSubTaskUseCases.progressForOccurrence(task.id, epochDay("2026-10-27")).getOrThrow().done,
        )
        // 提前完成父待办（下一次未完成出现）不自动勾选子任务
        taskUseCases.complete(task.id, true).getOrThrow()
        assertEquals("父待办完成不自动勾选子任务", 0, subTaskUseCases.progress(task.id).done)
        assertEquals(2, subTaskUseCases.progress(task.id).total)
        assertEquals(
            "该次出现的步骤保持未勾选",
            0,
            occurrenceSubTaskUseCases.progressForOccurrence(task.id, epochDay("2026-10-20")).getOrThrow().done,
        )
        // 重复待办整体状态保持进行中（每一次分别记录）
        assertEquals(TaskStatus.ACTIVE, tasksRepository.getTask(task.id)!!.status)
    }

    @Test
    fun 非法重复规则整批拒绝且无任何写入() = runBlocking {
        // 每周规则缺少星期：整批拒绝（含同批合法条目也不保存）
        val badBlock = proposalBlock(
            taskJson(title = "正常项"),
            taskJson(title = "坏规则", recurrence = recurrenceJson(frequency = "weekly")),
        )
        server.enqueue(sseProposal(badBlock))
        server.enqueue(completionProposal(badBlock))

        val result = send("每周")

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue("应中文提示缺少星期: $message", message.contains("星期") || message.contains("weekdays"))
        assertTrue(proposalStore.load().isEmpty())
        assertEquals(0, taskCount())
    }

    @Test
    fun 重复与子任务提案重复确认幂等不重复创建() = runBlocking {
        server.enqueue(
            sseProposal(
                proposalBlock(
                    taskJson(
                        title = "晨跑",
                        recurrence = recurrenceJson(frequency = "daily"),
                        subTasks = listOf("拉伸"),
                    ),
                ),
            ),
        )
        val outcome = send().getOrThrow() as AiProposalSendOutcome.ProposalCreated

        val first = proposals.confirmProposal(outcome.proposal.id).getOrThrow()
        val second = proposals.confirmProposal(outcome.proposal.id).getOrThrow()

        assertEquals(first.resultMessage, second.resultMessage)
        assertEquals("重复确认不重复创建", 1, taskCount())
        val task = tasksRepository.observeTasks().first().single()
        assertEquals("重复确认不重复创建子任务", 1, subTaskUseCases.observeByParent(task.id).first().size)
    }

    @Test
    fun 卡片范围选择_仅本次与本次及以后可切换且仅本次不支持改标题() = runBlocking {
        val task = presetRecurringTask("晨跑", RecurrenceRule(frequency = RecurrenceFrequency.DAILY), createdDate = "2026-10-20")
        authorize(AiContextCategory.TASKS)
        server.enqueue(
            sseProposal(
                proposalBlock(modifyJson("晨跑", "{\"title\":\"晨间跑步\"}", scope = "future")),
            ),
        )

        val outcome = send("把晨跑改名叫晨间跑步")
            .getOrThrow() as AiProposalSendOutcome.ProposalCreated
        val item = outcome.proposal.items.single()

        // 切到「仅本次」：改标题不被支持，明确拒绝（绝不静默改成整组）
        val invalid = proposals.selectProposalScope(outcome.proposal.id, item.id, AiProposalScope.THIS_OCCURRENCE)
        assertTrue(invalid.isFailure)
        assertTrue(invalid.exceptionOrNull()!!.message!!.contains("仅本次"))

        // 保持「本次及以后」：确认后模板改名，过去记录不被改写
        val selected = proposals.selectProposalScope(outcome.proposal.id, item.id, AiProposalScope.THIS_AND_FUTURE).getOrThrow()
        assertEquals(AiProposalScope.THIS_AND_FUTURE, selected.items.single().scope)
        proposals.confirmProposal(outcome.proposal.id).getOrThrow()
        assertEquals("晨间跑步", tasksRepository.getTask(task.id)!!.title)
    }

    // ---------- 替身 ----------

    private class FakeAlarmScheduler : AlarmScheduler {
        override fun canScheduleExact(): Boolean = true
        override suspend fun scheduleExact(reminderId: String, triggerAtMillis: Long, taskId: String, title: String): Boolean = true
        override suspend fun scheduleInexact(reminderId: String, triggerAtMillis: Long, taskId: String, title: String): Boolean = true
        override suspend fun cancel(reminderId: String) = Unit
    }
}
