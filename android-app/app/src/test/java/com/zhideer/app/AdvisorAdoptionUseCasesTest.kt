package com.zhideer.app

import com.zhideer.app.data.ai.AdvisorRecordStore
import com.zhideer.app.data.ai.AiProposalStore
import com.zhideer.app.data.ai.AiSessionStore
import com.zhideer.app.data.ai.DeepSeekChatClient
import com.zhideer.app.data.ai.InMemoryAiConfigStore
import com.zhideer.app.domain.model.AdvisorAdoptionOutcome
import com.zhideer.app.domain.model.AdvisorFeedbackType
import com.zhideer.app.domain.model.AiProposal
import com.zhideer.app.domain.model.AiProposalStatus
import com.zhideer.app.domain.model.CalendarEntry
import com.zhideer.app.domain.model.Memory
import com.zhideer.app.domain.model.MemorySource
import com.zhideer.app.domain.model.MemoryType
import com.zhideer.app.domain.model.Note
import com.zhideer.app.domain.model.RecurrenceFrequency
import com.zhideer.app.domain.model.RecurrenceRule
import com.zhideer.app.domain.model.TaskOccurrence
import com.zhideer.app.domain.model.TaskStatus
import com.zhideer.app.domain.model.TodoTask
import com.zhideer.app.domain.repository.CalendarRepository
import com.zhideer.app.domain.repository.MemoriesRepository
import com.zhideer.app.domain.repository.NotesRepository
import com.zhideer.app.domain.repository.TaskOccurrenceRepository
import com.zhideer.app.domain.repository.TasksRepository
import com.zhideer.app.domain.usecase.AdvisorUseCases
import com.zhideer.app.domain.usecase.AiChatUseCases
import com.zhideer.app.domain.usecase.AiContextAuthorizer
import com.zhideer.app.domain.usecase.AiContextCategory
import com.zhideer.app.domain.usecase.AiContextSnapshot
import com.zhideer.app.domain.usecase.AiProposalTargetMatcher
import com.zhideer.app.domain.usecase.AiProposalUseCases
import com.zhideer.app.domain.usecase.CalendarReminderUseCases
import com.zhideer.app.domain.usecase.CalendarUseCases
import com.zhideer.app.domain.usecase.MemoryUseCases
import com.zhideer.app.domain.usecase.NoteUseCases
import com.zhideer.app.domain.usecase.TaskInput
import com.zhideer.app.domain.usecase.TaskUseCases
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * T30/#31 验收测试：确认采纳建议并拒绝过期操作。
 *
 * 规格语义（用户故事 79、81–83；GLOSSARY「参谋建议」：建议本身不修改事项，
 * 采纳涉及的写入仍须预览确认）：
 * - 采纳前先展示将创建或修改的事项，所有写入继续显式确认；
 * - 真实执行成功后才标记已采纳；失败、取消或未知结果都不标已采纳；
 * - 依据记录被修改、完成、删除或失效后，旧建议/旧提案不可直接执行，须重新生成并确认；
 * - 重复采纳或响应丢失不产生重复事项，可依据执行记录核对；
 * - 忽略、调整、不再建议反馈可保存追溯，且不写长期记忆。
 */
class AdvisorAdoptionUseCasesTest {

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
        val NOW: Long = LocalDateTime.parse("2026-10-25T09:00").atZone(ZONE).toInstant().toEpochMilli()
        val TODAY: Long = LocalDate.parse("2026-10-25").toEpochDay()
        const val DAY_MILLIS: Long = 24 * 3_600_000L
    }

    private lateinit var storeDir: File
    private lateinit var proposalStore: AiProposalStore
    private lateinit var recordStore: AdvisorRecordStore
    private lateinit var fakeTasks: FakeTasksRepository
    private lateinit var fakeOccurrences: FakeTaskOccurrenceRepository
    private lateinit var fakeCalendar: FakeCalendarRepository
    private lateinit var fakeMemories: FakeMemoriesRepository
    private lateinit var authorizer: AiContextAuthorizer
    private lateinit var tasks: TaskUseCases
    private lateinit var calendar: CalendarUseCases
    private lateinit var memories: MemoryUseCases
    private lateinit var proposals: AiProposalUseCases
    private lateinit var advisor: AdvisorUseCases
    private var advisorIds = 0
    private var sessionIds = 0

    @Before
    fun setUp() {
        storeDir = File(System.getProperty("java.io.tmpdir"), "t30-test-${System.nanoTime()}")
        check(storeDir.mkdirs() || storeDir.isDirectory) { "无法创建测试目录" }
        proposalStore = AiProposalStore(File(storeDir, "ai_proposals.preferences_pb"))
        recordStore = AdvisorRecordStore(File(storeDir, "advisor_records.preferences_pb"))
        fakeTasks = FakeTasksRepository()
        fakeOccurrences = FakeTaskOccurrenceRepository()
        fakeCalendar = FakeCalendarRepository()
        fakeMemories = FakeMemoriesRepository()
        authorizer = AiContextAuthorizer()
        tasks = TaskUseCases(
            repository = fakeTasks,
            idGen = { "task-gen" },
            now = { NOW },
            zoneId = ZONE.id,
            occurrenceRepository = fakeOccurrences,
        )
        calendar = CalendarUseCases(
            repository = fakeCalendar,
            idGen = { "entry-gen" },
            now = { NOW },
            zoneId = ZONE.id,
        )
        memories = MemoryUseCases(
            repository = fakeMemories,
            idGen = { "memory-gen" },
            now = { NOW },
        )
        val chat = AiChatUseCases(
            configStore = InMemoryAiConfigStore(),
            sessionStore = AiSessionStore(File(storeDir, "ai_sessions.preferences_pb")),
            client = DeepSeekChatClient(connectTimeoutMs = 15_000, readTimeoutMs = 5_000),
            idGen = { "session-${sessionIds++}" },
            now = { NOW },
            contextAuthorizer = AiContextAuthorizer(),
            contextProvider = { AiContextSnapshot(generatedAtText = "测试快照") },
            zoneId = ZONE,
        )
        proposals = AiProposalUseCases(
            chat = chat,
            store = proposalStore,
            taskUseCases = tasks,
            noteUseCases = NoteUseCases(
                repository = NoopNotesRepository(),
                idGen = { "note-gen" },
                now = { NOW },
            ),
            calendarUseCases = calendar,
            calendarReminderUseCases = CalendarReminderUseCases(
                repository = NoopCalendarRemindersRepository(),
                calendarRepository = fakeCalendar,
                scheduler = NoopAlarmScheduler(),
                idGen = { "calrem-gen" },
                now = { NOW },
            ),
            targetMatcher = AiProposalTargetMatcher(fakeTasks, NoopNotesRepository(), fakeCalendar),
            memoryUseCases = memories,
            idGen = { "proposal-confirm" },
            now = { NOW },
            zoneId = ZONE,
        )
        advisor = AdvisorUseCases(
            taskUseCases = tasks,
            calendarUseCases = calendar,
            memoryUseCases = memories,
            authorizer = authorizer,
            proposalStore = proposalStore,
            recordStore = recordStore,
            proposalUseCases = proposals,
            idGen = { "proposal-advisor-${advisorIds++}" },
            now = { NOW },
            zoneId = ZONE,
        )
    }

    @After
    fun tearDown() {
        proposalStore.close()
        recordStore.close()
        storeDir.deleteRecursively()
    }

    private fun presetTask(
        id: String,
        title: String,
        dueEpochDay: Long? = null,
        dueAtMillis: Long? = null,
        recurrenceRule: RecurrenceRule? = null,
    ): TodoTask = TodoTask(
        id = id,
        title = title,
        dueEpochDay = dueEpochDay,
        dueAtMillis = dueAtMillis,
        dueZoneId = ZONE.id,
        status = TaskStatus.ACTIVE,
        createdAt = NOW - 5 * DAY_MILLIS,
        updatedAt = NOW - DAY_MILLIS,
        recurrenceRule = recurrenceRule,
    )

    private fun presetEntry(id: String, title: String, start: String, end: String): CalendarEntry = CalendarEntry(
        id = id,
        title = title,
        startMillis = LocalDateTime.parse(start).atZone(ZONE).toInstant().toEpochMilli(),
        endMillis = LocalDateTime.parse(end).atZone(ZONE).toInstant().toEpochMilli(),
        timezoneId = ZONE.id,
        createdAt = NOW - DAY_MILLIS,
        updatedAt = NOW - DAY_MILLIS,
    )

    private suspend fun generate() = advisor.generateSuggestions()

    // ---------- 1. 采纳前预览，写入仍显式确认 ----------

    @Test
    fun 采纳前预览将修改的事项且不写入任何仓库() = runBlocking {
        fakeTasks.tasks["t1"] = presetTask("t1", "买牛奶", dueEpochDay = TODAY - 1)

        val suggestion = generate().single()
        val lines = advisor.adoptionPreview(suggestion)

        assertTrue("预览必须说明将修改的事项: $lines", lines.isNotEmpty())
        val text = lines.joinToString("\n")
        assertTrue("预览应含目标标题: $text", text.contains("买牛奶"))
        assertTrue("预览应说明将修改: $text", text.contains("修改"))
        assertTrue("预览应含新旧截止: $text", text.contains("截止") && text.contains("2026-10-25"))
        // 仅预览：提案库与业务仓库零写入
        assertTrue("预览不得落提案", proposalStore.load().isEmpty())
        assertTrue("预览不得写业务仓库", fakeTasks.upsertCalls == 0)
        assertEquals("截止未被预览修改", TODAY - 1, fakeTasks.tasks["t1"]!!.dueEpochDay)
    }

    // ---------- 2. 忽略 / 调整 / 不再建议反馈可追溯，不写长期记忆 ----------

    @Test
    fun 忽略反馈可保存追溯且不写长期记忆与业务仓库() = runBlocking {
        fakeTasks.tasks["t1"] = presetTask("t1", "买牛奶", dueEpochDay = TODAY - 1)
        val suggestion = generate().single()

        val feedback = advisor.recordFeedback(suggestion, AdvisorFeedbackType.IGNORE).getOrThrow()
        val saved = advisor.observeFeedback().first()

        assertEquals(1, saved.size)
        assertEquals(suggestion.id, saved.single().suggestionId)
        assertEquals(AdvisorFeedbackType.IGNORE, saved.single().type)
        assertEquals("t1", saved.single().evidence.single().recordId)
        assertEquals(feedback.id, saved.single().id)
        // 反馈不写业务仓库、不写提案库、不写长期记忆
        assertTrue("反馈不得写业务仓库", fakeTasks.upsertCalls == 0)
        assertTrue("反馈不得落提案", proposalStore.load().isEmpty())
        assertEquals("反馈不得写长期记忆", 0, fakeMemories.writeCalls)
        assertTrue("反馈不得写长期记忆", fakeMemories.memories.isEmpty())
    }

    @Test
    fun 调整反馈需要说明内容并保存可追溯() = runBlocking {
        fakeTasks.tasks["t1"] = presetTask("t1", "买牛奶", dueEpochDay = TODAY - 1)
        val suggestion = generate().single()

        val blank = advisor.recordFeedback(suggestion, AdvisorFeedbackType.ADJUST, "  ")
        assertTrue("调整反馈必须说明调整内容", blank.isFailure)
        assertTrue(
            "应给出中文说明: ${blank.exceptionOrNull()?.message}",
            blank.exceptionOrNull()!!.message!!.contains("调整"),
        )
        assertTrue("校验失败不得落反馈", advisor.observeFeedback().first().isEmpty())

        val saved = advisor.recordFeedback(suggestion, AdvisorFeedbackType.ADJUST, "改成明天上午").getOrThrow()
        assertEquals("改成明天上午", saved.adjustmentNote)
        assertEquals(AdvisorFeedbackType.ADJUST, advisor.observeFeedback().first().single().type)
        assertTrue("调整反馈不得写业务仓库", fakeTasks.upsertCalls == 0)
        assertEquals("调整反馈不得写长期记忆", 0, fakeMemories.writeCalls)
    }

    @Test
    fun 不再建议反馈后同一建议不再生成而其他建议仍生成() = runBlocking {
        fakeTasks.tasks["t1"] = presetTask("t1", "买牛奶", dueEpochDay = TODAY - 1)
        fakeTasks.tasks["t2"] = presetTask("t2", "取快递", dueEpochDay = TODAY - 2)
        val first = generate()
        assertEquals(2, first.size)
        val milk = first.first { it.evidence.any { e -> e.recordId == "t1" } }

        advisor.recordFeedback(milk, AdvisorFeedbackType.NEVER_SUGGEST).getOrThrow()
        val after = generate()

        assertEquals("不再建议只影响该建议", 1, after.size)
        assertEquals("t2", after.single().evidence.single().recordId)
        assertEquals(AdvisorFeedbackType.NEVER_SUGGEST, advisor.observeFeedback().first().single().type)
    }

    // ---------- 3. 真实执行成功后才标记采纳 ----------

    @Test
    fun 真实执行成功后才标记已采纳() = runBlocking {
        fakeTasks.tasks["t1"] = presetTask("t1", "买牛奶", dueEpochDay = TODAY - 1)
        val suggestion = generate().single()

        val proposal = advisor.suggestionToProposal(suggestion).getOrThrow()
        assertNull("尚未确认执行时不得标记已采纳", advisor.adoptionFor(proposal.id))
        assertEquals("确认前业务仓库零写入", TODAY - 1, fakeTasks.tasks["t1"]!!.dueEpochDay)

        val executed = advisor.confirmAdoptedProposal(proposal.id).getOrThrow()

        assertEquals(AiProposalStatus.EXECUTED, executed.status)
        assertTrue("真实执行成功", executed.executionSucceeded!!)
        assertEquals(TODAY, fakeTasks.tasks["t1"]!!.dueEpochDay)
        val adoption = advisor.adoptionFor(proposal.id)!!
        assertEquals(AdvisorAdoptionOutcome.ADOPTED, adoption.outcome)
        assertEquals(suggestion.id, adoption.suggestionId)
        assertTrue("执行记录应可核对回执: ${adoption.detail}", adoption.detail.contains("已执行"))
    }

    @Test
    fun 执行失败不标记已采纳且执行记录如实() = runBlocking {
        fakeTasks.tasks["t1"] = presetTask("t1", "买牛奶", dueEpochDay = TODAY - 1)
        val suggestion = generate().single()
        val proposal = advisor.suggestionToProposal(suggestion).getOrThrow()
        fakeTasks.failUpserts = true

        val executed = advisor.confirmAdoptedProposal(proposal.id).getOrThrow()

        assertEquals(AiProposalStatus.EXECUTED, executed.status)
        assertFalse("执行失败不得标记成功", executed.executionSucceeded!!)
        assertTrue("回执应如实说明失败: ${executed.resultMessage}", executed.resultMessage!!.contains("失败"))
        assertEquals(AdvisorAdoptionOutcome.FAILED, advisor.adoptionFor(proposal.id)!!.outcome)
        assertEquals("失败未改业务记录", TODAY - 1, fakeTasks.tasks["t1"]!!.dueEpochDay)
    }

    @Test
    fun 取消采纳不标记已采纳且不写入业务记录() = runBlocking {
        fakeTasks.tasks["t1"] = presetTask("t1", "买牛奶", dueEpochDay = TODAY - 1)
        val suggestion = generate().single()
        val proposal = advisor.suggestionToProposal(suggestion).getOrThrow()

        advisor.cancelAdoptedProposal(proposal.id).getOrThrow()

        assertEquals(AiProposalStatus.EXPIRED, proposalStore.load().single().status)
        assertEquals(AdvisorAdoptionOutcome.CANCELLED, advisor.adoptionFor(proposal.id)!!.outcome)
        assertEquals("取消不写入业务记录", TODAY - 1, fakeTasks.tasks["t1"]!!.dueEpochDay)
        assertTrue("取消不产生执行写入", fakeTasks.upsertCalls == 0)
    }

    @Test
    fun 重复采纳与重复确认不产生重复提案或重复执行() = runBlocking {
        fakeTasks.tasks["t1"] = presetTask("t1", "买牛奶", dueEpochDay = TODAY - 1)
        val suggestion = generate().single()

        val first = advisor.suggestionToProposal(suggestion).getOrThrow()
        val second = advisor.suggestionToProposal(suggestion).getOrThrow()
        assertEquals("重复采纳返回同一提案", first.id, second.id)
        assertEquals("重复采纳不重复落提案", 1, proposalStore.load().size)

        val executedOnce = advisor.confirmAdoptedProposal(first.id).getOrThrow()
        val upsertAfterFirst = fakeTasks.upsertCalls
        val executedTwice = advisor.confirmAdoptedProposal(first.id).getOrThrow()

        assertEquals(executedOnce.resultMessage, executedTwice.resultMessage)
        assertEquals("重复确认不重放修改", upsertAfterFirst, fakeTasks.upsertCalls)
        assertEquals("执行记录只有一条", 1, advisor.observeAdoptions().first().size)
        assertEquals(AdvisorAdoptionOutcome.ADOPTED, advisor.adoptionFor(first.id)!!.outcome)
    }

    @Test
    fun 忽略建议会保存反馈并让已落库提案失效不可执行() = runBlocking {
        fakeTasks.tasks["t1"] = presetTask("t1", "买牛奶", dueEpochDay = TODAY - 1)
        val suggestion = generate().single()
        val proposal = advisor.suggestionToProposal(suggestion).getOrThrow()

        advisor.ignoreSuggestion(suggestion).getOrThrow()

        val feedback = advisor.observeFeedback().first().single()
        assertEquals(AdvisorFeedbackType.IGNORE, feedback.type)
        assertEquals(AdvisorFeedbackType.IGNORE, advisor.feedbackFor(suggestion.id).single().type)
        assertEquals("忽略后旧提案失效", AiProposalStatus.EXPIRED, proposalStore.load().single().status)
        assertEquals(AdvisorAdoptionOutcome.CANCELLED, advisor.adoptionFor(proposal.id)!!.outcome)
        val confirm = advisor.confirmAdoptedProposal(proposal.id)
        assertTrue("已忽略的提案不可再执行", confirm.isFailure)
        assertEquals("未执行任何写入", TODAY - 1, fakeTasks.tasks["t1"]!!.dueEpochDay)
        assertEquals("未执行任何写入", 0, fakeTasks.upsertCalls)
    }

    // ---------- 4. 依据变化后旧操作不可直接执行，须重新生成并确认 ----------

    @Test
    fun 依据被修改后旧建议不能直接采纳而重新生成后可采纳() = runBlocking {
        fakeTasks.tasks["t1"] = presetTask("t1", "买牛奶", dueEpochDay = TODAY - 1)
        val old = generate().single()
        // 生成后真实记录被修改（仍过期）：旧建议依据已变化
        tasks.update(
            "t1",
            TaskInput(title = "买牛奶（两箱）", dueEpochDay = TODAY - 1, dueZoneId = ZONE.id),
        ).getOrThrow()

        val rejected = advisor.suggestionToProposal(old)

        assertTrue("依据被修改后旧建议必须拒绝", rejected.isFailure)
        assertTrue(
            "应说明依据已变化并要求重新生成: ${rejected.exceptionOrNull()?.message}",
            rejected.exceptionOrNull()!!.message!!.contains("重新生成"),
        )
        assertTrue("拒绝后不得落提案", proposalStore.load().isEmpty())
        assertEquals("拒绝后不得写业务仓库", "买牛奶（两箱）", fakeTasks.tasks["t1"]!!.title)

        // 重新生成后按最新依据采纳、确认执行
        val fresh = generate().single()
        assertEquals("新建议应基于最新记录", "买牛奶（两箱）", fresh.evidence.single().title)
        val proposal = advisor.suggestionToProposal(fresh).getOrThrow()
        val executed = advisor.confirmAdoptedProposal(proposal.id).getOrThrow()

        assertEquals(AiProposalStatus.EXECUTED, executed.status)
        assertEquals(TODAY, fakeTasks.tasks["t1"]!!.dueEpochDay)
        assertEquals(AdvisorAdoptionOutcome.ADOPTED, advisor.adoptionFor(proposal.id)!!.outcome)
    }

    @Test
    fun 依据完成删除或失效后旧建议不能直接采纳() = runBlocking {
        // 完成
        fakeTasks.tasks["t1"] = presetTask("t1", "买牛奶", dueEpochDay = TODAY - 1)
        val completed = generate().single()
        tasks.complete("t1", true).getOrThrow()
        assertTrue("已完成记录的旧建议必须拒绝", advisor.suggestionToProposal(completed).isFailure)

        // 移入回收站
        fakeTasks.tasks["t2"] = presetTask("t2", "取快递", dueEpochDay = TODAY - 2)
        val trashed = generate().single { it.evidence.any { e -> e.recordId == "t2" } }
        tasks.trash("t2").getOrThrow()
        assertTrue("已删除记录的旧建议必须拒绝", advisor.suggestionToProposal(trashed).isFailure)

        // 失效：改期后不再过期
        fakeTasks.tasks["t3"] = presetTask("t3", "交周报", dueEpochDay = TODAY - 1)
        val invalid = generate().single { it.evidence.any { e -> e.recordId == "t3" } }
        tasks.update(
            "t3",
            TaskInput(title = "交周报", dueEpochDay = TODAY + 1, dueZoneId = ZONE.id),
        ).getOrThrow()
        val rejected = advisor.suggestionToProposal(invalid)
        assertTrue("不再过期的旧建议必须拒绝", rejected.isFailure)
        assertTrue(
            "失效也应要求重新生成: ${rejected.exceptionOrNull()?.message}",
            rejected.exceptionOrNull()!!.message!!.contains("重新生成"),
        )
        assertTrue("以上拒绝都不得落提案", proposalStore.load().isEmpty())
    }

    @Test
    fun 确认前依据被修改时旧提案失效且不写入须重新生成() = runBlocking {
        fakeTasks.tasks["t1"] = presetTask("t1", "买牛奶", dueEpochDay = TODAY - 1)
        val proposal = advisor.suggestionToProposal(generate().single()).getOrThrow()
        // 采纳后、确认前真实记录被修改
        tasks.update(
            "t1",
            TaskInput(title = "买牛奶（两箱）", dueEpochDay = TODAY - 1, dueZoneId = ZONE.id),
        ).getOrThrow()

        val failed = advisor.confirmAdoptedProposal(proposal.id)

        assertTrue("依据变化后旧提案不可执行", failed.isFailure)
        assertTrue(
            "应说明依据已变化并要求重新生成: ${failed.exceptionOrNull()?.message}",
            failed.exceptionOrNull()!!.message!!.contains("重新生成"),
        )
        assertEquals("旧提案置为失效", AiProposalStatus.EXPIRED, proposalStore.load().single().status)
        assertEquals("未执行任何写入", TODAY - 1, fakeTasks.tasks["t1"]!!.dueEpochDay)
        assertEquals(AdvisorAdoptionOutcome.INVALIDATED, advisor.adoptionFor(proposal.id)!!.outcome)

        // 重新生成并确认后才可执行
        val fresh = generate().single()
        val second = advisor.suggestionToProposal(fresh).getOrThrow()
        assertNotEquals("须生成新的提案", proposal.id, second.id)
        advisor.confirmAdoptedProposal(second.id).getOrThrow()
        assertEquals(TODAY, fakeTasks.tasks["t1"]!!.dueEpochDay)
        assertEquals(AiProposalStatus.EXECUTED, proposalStore.load().first { it.id == second.id }.status)
    }

    // ---------- 5. 响应丢失与确认竞态 ----------

    @Test
    fun 响应丢失结果未知时不标记已采纳也不重复执行() = runBlocking {
        fakeTasks.tasks["t1"] = presetTask("t1", "买牛奶", dueEpochDay = TODAY - 1)
        val suggestion = generate().single()
        val proposal = advisor.suggestionToProposal(suggestion).getOrThrow()
        // 模拟进程在 CONFIRMED 期间中断：提案停在已确认、业务未执行
        proposalStore.transition(proposal.id, setOf(AiProposalStatus.PENDING)) {
            it.copy(status = AiProposalStatus.CONFIRMED)
        } ?: error("模拟中断状态失败")

        val unknown = advisor.confirmAdoptedProposal(proposal.id)

        assertTrue("结果未知时确认应失败", unknown.isFailure)
        assertTrue(
            "应提示结果未知: ${unknown.exceptionOrNull()?.message}",
            unknown.exceptionOrNull()!!.message!!.contains("未知"),
        )
        assertEquals(AdvisorAdoptionOutcome.UNKNOWN, advisor.adoptionFor(proposal.id)!!.outcome)
        assertEquals("未知结果不得写入业务记录", TODAY - 1, fakeTasks.tasks["t1"]!!.dueEpochDay)
        assertEquals("未知结果不得执行写入", 0, fakeTasks.upsertCalls)

        // 重新生成后再次采纳同样拒绝，不产生重复提案
        val again = advisor.suggestionToProposal(generate().single())
        assertTrue("结果未知时不得重复采纳", again.isFailure)
        assertEquals("不得产生重复提案", 1, proposalStore.load().size)
    }

    @Test
    fun 并发确认只执行一次且只记录一次已采纳() = runBlocking {
        fakeTasks.tasks["t1"] = presetTask("t1", "买牛奶", dueEpochDay = TODAY - 1)
        val proposal = advisor.suggestionToProposal(generate().single()).getOrThrow()

        val results = listOf(
            async { advisor.confirmAdoptedProposal(proposal.id) },
            async { advisor.confirmAdoptedProposal(proposal.id) },
        ).awaitAll()

        assertEquals("并发确认只执行一次修改", 1, fakeTasks.upsertCalls)
        assertEquals(TODAY, fakeTasks.tasks["t1"]!!.dueEpochDay)
        assertEquals(AdvisorAdoptionOutcome.ADOPTED, advisor.adoptionFor(proposal.id)!!.outcome)
        assertEquals("执行记录按提案唯一", 1, advisor.observeAdoptions().first().size)
        assertTrue("至少一次确认返回执行结果", results.any { it.isSuccess })
    }

    @Test
    fun 已采纳后再次采纳返回既有提案不产生重复事项() = runBlocking {
        // 重复待办：完成一次错过出现不改变待办本体，重新生成仍是同一依据、同一建议
        fakeTasks.tasks["t1"] = presetTask(
            "t1",
            "每日站会准备",
            recurrenceRule = RecurrenceRule(frequency = RecurrenceFrequency.DAILY, intervalDays = 1),
        )
        val suggestion = generate().single()
        val proposal = advisor.suggestionToProposal(suggestion).getOrThrow()
        advisor.confirmAdoptedProposal(proposal.id).getOrThrow()
        assertEquals("首次采纳已完成一次错过出现", 1, fakeOccurrences.rows.size)
        val upsertsAfterExecution = fakeTasks.upsertCalls

        val again = advisor.suggestionToProposal(generate().single())

        assertEquals("已采纳后再次采纳返回既有提案", proposal.id, again.getOrThrow().id)
        assertEquals("不产生第二条提案", 1, proposalStore.load().size)
        assertEquals("不重放修改", upsertsAfterExecution, fakeTasks.upsertCalls)
        assertEquals("不重复完成出现", 1, fakeOccurrences.rows.size)
        assertEquals(AdvisorAdoptionOutcome.ADOPTED, advisor.adoptionFor(proposal.id)!!.outcome)
    }

    // ---------- 替身 ----------

    private class FakeTasksRepository : TasksRepository {
        val tasks = LinkedHashMap<String, TodoTask>()
        var upsertCalls = 0
        var failUpserts = false

        override fun observeTasks(): Flow<List<TodoTask>> = flowOf(tasks.values.toList())
        override suspend fun getTask(id: String): TodoTask? = tasks[id]

        override suspend fun upsertTask(task: TodoTask) {
            upsertCalls++
            if (failUpserts) throw IllegalStateException("模拟本地保存失败")
            tasks[task.id] = task
        }

        override suspend fun trashTask(id: String, trashedAtMillis: Long) {
            tasks[id]?.let { tasks[id] = it.copy(trashedAt = trashedAtMillis) }
        }

        override fun observeTrashedTasks(): Flow<List<TodoTask>> =
            flowOf(tasks.values.filter { it.trashedAt != null })

        override suspend fun listTrashedTasks(): List<TodoTask> = tasks.values.filter { it.trashedAt != null }
        override suspend fun purgeTask(id: String) {
            tasks.remove(id)
        }
    }

    private class FakeTaskOccurrenceRepository : TaskOccurrenceRepository {
        val rows = LinkedHashMap<String, TaskOccurrence>()

        override fun observeAll(): Flow<List<TaskOccurrence>> = flowOf(rows.values.toList())
        override suspend fun listByTask(taskId: String): List<TaskOccurrence> =
            rows.values.filter { it.taskId == taskId }

        override suspend fun upsert(occurrence: TaskOccurrence) {
            rows[occurrence.id] = occurrence
        }

        override suspend fun purgeByTask(taskId: String) {
            rows.keys.removeAll { key -> rows[key]!!.taskId == taskId }
        }
    }

    private class FakeCalendarRepository : CalendarRepository {
        val entries = LinkedHashMap<String, CalendarEntry>()

        override fun observeEntries(): Flow<List<CalendarEntry>> =
            flowOf(entries.values.filter { it.trashedAt == null })

        override suspend fun getEntry(id: String): CalendarEntry? = entries[id]
        override suspend fun getEntriesLinkedToTask(taskId: String): List<CalendarEntry> =
            entries.values.filter { it.taskId == taskId }

        override suspend fun upsertEntry(entry: CalendarEntry) {
            entries[entry.id] = entry
        }

        override suspend fun trashEntry(id: String, trashedAtMillis: Long) {
            entries[id]?.let { entries[id] = it.copy(trashedAt = trashedAtMillis) }
        }

        override fun observeTrashedEntries(): Flow<List<CalendarEntry>> =
            flowOf(entries.values.filter { it.trashedAt != null })

        override suspend fun listTrashedEntries(): List<CalendarEntry> = entries.values.filter { it.trashedAt != null }
        override suspend fun purgeEntry(id: String) {
            entries.remove(id)
        }
    }

    private class FakeMemoriesRepository : MemoriesRepository {
        val memories = LinkedHashMap<String, Memory>()
        var observeCalls = 0
        var writeCalls = 0

        override fun observeMemories(): Flow<List<Memory>> {
            observeCalls++
            return flowOf(memories.values.toList())
        }

        override suspend fun getMemory(id: String): Memory? = memories[id]

        override suspend fun upsertMemory(memory: Memory) {
            writeCalls++
            memories[memory.id] = memory
        }

        override suspend fun deleteMemory(id: String) {
            writeCalls++
            memories.remove(id)
        }

        override suspend fun disableAndMarkSourceMissing(
            sourceType: com.zhideer.app.domain.model.MemoryRefType,
            sourceId: String,
            updatedAt: Long,
        ): Int = 0

        override suspend fun clearSourceMissing(
            sourceType: com.zhideer.app.domain.model.MemoryRefType,
            sourceId: String,
            updatedAt: Long,
        ): Int = 0

        override suspend fun deleteBySourceRef(
            sourceType: com.zhideer.app.domain.model.MemoryRefType,
            sourceId: String,
        ): Int = 0
    }

    private class NoopNotesRepository : NotesRepository {
        override fun observeNotes(): Flow<List<Note>> = flowOf(emptyList())
        override suspend fun getNote(id: String): Note? = null
        override suspend fun upsertNote(note: Note) = throw UnsupportedOperationException("本测试不应写便签")
        override suspend fun trashNote(id: String, trashedAtMillis: Long) =
            throw UnsupportedOperationException("本测试不应改便签")

        override fun observeTrashedNotes(): Flow<List<Note>> = flowOf(emptyList())
        override suspend fun listTrashedNotes(): List<Note> = emptyList()
        override suspend fun purgeNote(id: String) = throw UnsupportedOperationException("本测试不应删便签")
    }

    private class NoopCalendarRemindersRepository : com.zhideer.app.domain.repository.CalendarRemindersRepository {
        override fun observeByEntry(entryId: String): Flow<List<com.zhideer.app.domain.model.Reminder>> =
            flowOf(emptyList())

        override suspend fun listByEntry(entryId: String): List<com.zhideer.app.domain.model.Reminder> = emptyList()
        override suspend fun get(id: String): com.zhideer.app.domain.model.Reminder? = null
        override suspend fun upsert(reminder: com.zhideer.app.domain.model.Reminder) =
            throw UnsupportedOperationException("本测试不应写提醒")

        override suspend fun delete(id: String) = throw UnsupportedOperationException("本测试不应删提醒")
        override suspend fun deleteByEntry(entryId: String) = throw UnsupportedOperationException("本测试不应删提醒")
    }

    private class NoopAlarmScheduler : com.zhideer.app.platform.AlarmScheduler {
        override fun canScheduleExact(): Boolean = false
        override fun scheduleExact(reminderId: String, triggerAtMillis: Long, taskId: String, title: String): Boolean =
            throw UnsupportedOperationException("本测试不应调度提醒")

        override fun scheduleInexact(reminderId: String, triggerAtMillis: Long, taskId: String, title: String): Boolean =
            throw UnsupportedOperationException("本测试不应调度提醒")

        override fun cancel(reminderId: String) = throw UnsupportedOperationException("本测试不应取消提醒")
    }
}
