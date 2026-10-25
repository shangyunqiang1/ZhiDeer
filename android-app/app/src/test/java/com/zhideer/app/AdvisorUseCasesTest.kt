package com.zhideer.app

import com.zhideer.app.data.ai.AdvisorRecordStore
import com.zhideer.app.data.ai.AiProposalStore
import com.zhideer.app.domain.model.AiProposalKind
import com.zhideer.app.domain.model.AiProposalOp
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
import com.zhideer.app.domain.usecase.AdvisorSuggestionKind
import com.zhideer.app.domain.usecase.AdvisorUseCases
import com.zhideer.app.domain.usecase.AiContextAuthorizer
import com.zhideer.app.domain.usecase.AiContextCategory
import com.zhideer.app.domain.usecase.AiProposalTargetMatcher
import com.zhideer.app.domain.usecase.CalendarUseCases
import com.zhideer.app.domain.usecase.MemoryUseCases
import com.zhideer.app.domain.usecase.TaskUseCases
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * T29/#30 验收测试：主动生成有依据的参谋建议。
 *
 * 核心语义（GLOSSARY「参谋建议」：依据真实事项和已授权记忆提出的行动建议；
 * 建议本身不修改事项，采纳涉及的写入仍须预览确认）：
 * - 每条建议必须引用其依据的真实记录（evidence 可核对），绝不无中生有：
 *   无过期待办 / 无明日日程冲突 / 无久未核对记忆时不出产生对应建议；
 * - 依据已失效（记录已完成、取消或删除）的建议自动失效不展示；
 * - 采纳 = 转为操作提案并走既有提案状态机确认执行，全程零直接写入：
 *   生成建议绝不写业务仓库与提案库；转提案落 PENDING 后经 confirmProposal 真实生效；
 * - 记忆是独立隐私数据：未授权 MEMORY 类别时绝不读取记忆仓库、不产生记忆类建议；
 * - 首版仅主动触发：生成只由显式调用发起（UI 点击），无后台自动生成与通知。
 * 全部走替身仓库 + 真实用例入口；AI 增强路径本票未实现（真实模型待 Key，见任务报告）。
 */
class AdvisorUseCasesTest {

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
    private lateinit var advisor: AdvisorUseCases

    @Before
    fun setUp() {
        storeDir = File(System.getProperty("java.io.tmpdir"), "t29-test-${System.nanoTime()}")
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
        val calendar = CalendarUseCases(
            repository = fakeCalendar,
            idGen = { "entry-gen" },
            now = { NOW },
            zoneId = ZONE.id,
        )
        val memories = MemoryUseCases(
            repository = fakeMemories,
            idGen = { "memory-gen" },
            now = { NOW },
        )
        advisor = AdvisorUseCases(
            taskUseCases = tasks,
            calendarUseCases = calendar,
            memoryUseCases = memories,
            authorizer = authorizer,
            proposalStore = proposalStore,
            recordStore = recordStore,
            idGen = { "proposal-advisor" },
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

    // ---------- 工具 ----------

    private fun presetTask(
        id: String,
        title: String,
        dueEpochDay: Long? = null,
        dueAtMillis: Long? = null,
        recurrenceRule: RecurrenceRule? = null,
        status: TaskStatus = TaskStatus.ACTIVE,
    ): TodoTask = TodoTask(
        id = id,
        title = title,
        dueEpochDay = dueEpochDay,
        dueAtMillis = dueAtMillis,
        dueZoneId = ZONE.id,
        status = status,
        createdAt = NOW - 5 * DAY_MILLIS, // 锚点 2026-10-20，供重复待办派生错过出现
        updatedAt = NOW - DAY_MILLIS,
        recurrenceRule = recurrenceRule,
    )

    private fun presetEntry(id: String, title: String, start: String, end: String): CalendarEntry {
        val startMillis = LocalDateTime.parse(start).atZone(ZONE).toInstant().toEpochMilli()
        val endMillis = LocalDateTime.parse(end).atZone(ZONE).toInstant().toEpochMilli()
        return CalendarEntry(
            id = id,
            title = title,
            startMillis = startMillis,
            endMillis = endMillis,
            timezoneId = ZONE.id,
            createdAt = NOW - DAY_MILLIS,
            updatedAt = NOW - DAY_MILLIS,
        )
    }

    private fun staleMemory(id: String, content: String, verifiedDaysAgo: Long): Memory = Memory(
        id = id,
        content = content,
        type = MemoryType.PREFERENCE,
        source = MemorySource.MANUAL,
        createdAt = NOW - (verifiedDaysAgo + 5) * DAY_MILLIS,
        updatedAt = NOW - verifiedDaysAgo * DAY_MILLIS,
        verifiedAt = NOW - verifiedDaysAgo * DAY_MILLIS,
    )

    private fun snapshotOfTasks(): Map<String, TodoTask> =
        fakeTasks.tasks.mapValues { it.value.copy() }

    private suspend fun generate() = advisor.generateSuggestions()

    // ---------- 1. 无依据不生成（绝不无中生有） ----------

    @Test
    fun 没有任何真实依据时不产生任何建议也不落提案库() = runBlocking {
        // 空仓库、未授权记忆：无过期待办、无明日日程、无记忆依据
        val suggestions = generate()

        assertTrue("无依据时不得产生任何建议: $suggestions", suggestions.isEmpty())
        assertTrue("生成建议绝不写提案库", proposalStore.load().isEmpty())
        assertTrue("生成建议绝不写业务仓库", fakeTasks.tasks.isEmpty() && fakeCalendar.entries.isEmpty())
    }

    @Test
    fun 明日日程不重叠或冲突在今天时不生成冲突建议() = runBlocking {
        // 明天两条首尾相接（不重叠）：11:00 结束 == 11:00 开始，不算冲突
        fakeCalendar.entries["e1"] = presetEntry("e1", "晨会", "2026-10-26T09:00", "2026-10-26T10:00")
        fakeCalendar.entries["e2"] = presetEntry("e2", "评审", "2026-10-26T10:00", "2026-10-26T11:00")
        // 今天两条重叠：不在明日窗口内，不得建议
        fakeCalendar.entries["e3"] = presetEntry("e3", "今天A", "2026-10-25T10:00", "2026-10-25T11:00")
        fakeCalendar.entries["e4"] = presetEntry("e4", "今天B", "2026-10-25T10:30", "2026-10-25T11:30")

        val suggestions = generate()

        assertTrue(
            "不重叠或非明日窗口不得产生冲突建议: $suggestions",
            suggestions.none { it.kind == AdvisorSuggestionKind.SCHEDULE_CONFLICT },
        )
    }

    @Test
    fun 未授权记忆类别时不读取记忆仓库也不产生记忆建议() = runBlocking {
        fakeMemories.memories["m1"] = staleMemory("m1", "偏好清晨跑步", verifiedDaysAgo = 40)
        assertNull("未授权时授权状态为空", authorizer.currentGrant())

        val suggestions = generate()

        assertTrue(
            "未授权不得产生记忆类建议: $suggestions",
            suggestions.none { it.kind == AdvisorSuggestionKind.MEMORY_REVIEW },
        )
        assertEquals("未授权时绝不读取记忆仓库", 0, fakeMemories.observeCalls)
    }

    @Test
    fun 记忆都已近期核对时不生成记忆核对建议() = runBlocking {
        authorizer.grant(setOf(AiContextCategory.MEMORY))
        fakeMemories.memories["m1"] = staleMemory("m1", "偏好清晨跑步", verifiedDaysAgo = 1)

        val suggestions = generate()

        assertTrue(
            "近期核对过的记忆不产生核对建议: $suggestions",
            suggestions.none { it.kind == AdvisorSuggestionKind.MEMORY_REVIEW },
        )
    }

    // ---------- 2. 有依据生成：依据可核对 ----------

    @Test
    fun 过期待办生成有依据建议且生成过程零直接写入() = runBlocking {
        fakeTasks.tasks["t1"] = presetTask("t1", "买牛奶", dueEpochDay = TODAY - 1)
        val before = snapshotOfTasks()

        val suggestions = generate()

        assertEquals(1, suggestions.size)
        val suggestion = suggestions.single()
        assertEquals(AdvisorSuggestionKind.OVERDUE_TASK, suggestion.kind)
        assertTrue("标题应含待办标题供核对: ${suggestion.title}", suggestion.title.contains("买牛奶"))
        // 依据引用真实记录：记录 id、标题与过期事实可在待办页核对
        val evidence = suggestion.evidence.single()
        assertEquals(AiProposalKind.TASK, evidence.recordKind)
        assertEquals("t1", evidence.recordId)
        assertEquals("买牛奶", evidence.title)
        assertTrue("依据应说明过期: ${evidence.detail}", evidence.detail.contains("过期"))
        assertTrue("依据应含截止日期: ${evidence.detail}", evidence.detail.contains("2026-10-24"))
        // 建议草稿：修改类条目，把截止改到今天；绑定目标快照供卡片预览
        val draft = suggestion.proposalDraft.single()
        assertEquals(AiProposalOp.MODIFY, draft.op)
        assertEquals(AiProposalKind.TASK, draft.kind)
        assertEquals("t1", draft.targetRecordId)
        assertEquals("买牛奶", draft.targetTitle)
        assertEquals(TODAY, draft.changes!!.dueEpochDay)
        assertTrue("修改条目应带目标快照", draft.targetRecord != null)
        // 零直接写入：业务仓库与提案库均无变化
        assertEquals("生成建议不得修改待办", before, fakeTasks.tasks.toMap())
        assertTrue("生成建议不得写提案库", proposalStore.load().isEmpty())
    }

    @Test
    fun 时刻截止过期待办建议改期到今天同一时刻() = runBlocking {
        fakeTasks.tasks["t1"] = presetTask(
            "t1",
            "交周报",
            dueAtMillis = LocalDateTime.parse("2026-10-24T14:00").atZone(ZONE).toInstant().toEpochMilli(),
        )

        val suggestion = generate().single()

        val draft = suggestion.proposalDraft.single()
        val expected = LocalDateTime.parse("2026-10-25T14:00").atZone(ZONE).toInstant().toEpochMilli()
        assertEquals("时刻截止改期保留用户原时刻、只移动日期", expected, draft.changes!!.dueAtMillis)
        assertNull("日期截止与时刻截止互斥", draft.changes.dueEpochDay)
    }

    @Test
    fun 明日日程重叠生成提醒式冲突建议且依据两条真实日程() = runBlocking {
        fakeCalendar.entries["e1"] = presetEntry("e1", "项目周会", "2026-10-26T10:00", "2026-10-26T11:00")
        fakeCalendar.entries["e2"] = presetEntry("e2", "客户拜访", "2026-10-26T10:30", "2026-10-26T11:30")

        val suggestions = generate()

        val conflict = suggestions.filter { it.kind == AdvisorSuggestionKind.SCHEDULE_CONFLICT }
        assertEquals(1, conflict.size)
        val suggestion = conflict.single()
        assertTrue("标题应含两条日程标题: ${suggestion.title}", suggestion.title.contains("项目周会") && suggestion.title.contains("客户拜访"))
        // 依据 = 冲突的两条真实日程，均可在日程页核对
        assertEquals(2, suggestion.evidence.size)
        assertEquals(setOf("e1", "e2"), suggestion.evidence.map { it.recordId }.toSet())
        assertTrue(suggestion.evidence.all { it.recordKind == AiProposalKind.SCHEDULE })
        // 提醒式建议：不编造新的时间，不携带操作提案草稿
        assertTrue("冲突建议不编造调整方案，无提案草稿", suggestion.proposalDraft.isEmpty())
        assertTrue("生成建议不得写提案库", proposalStore.load().isEmpty())
    }

    @Test
    fun 重复待办错过生成补做建议() = runBlocking {
        fakeTasks.tasks["t1"] = presetTask(
            "t1",
            "每日站会准备",
            recurrenceRule = RecurrenceRule(frequency = RecurrenceFrequency.DAILY, intervalDays = 1),
        )

        val suggestions = generate()

        assertEquals(1, suggestions.size)
        val suggestion = suggestions.single()
        assertEquals(AdvisorSuggestionKind.MISSED_RECURRENCE, suggestion.kind)
        assertEquals("t1", suggestion.evidence.single().recordId)
        // 补做 = 修改类提案把完成状态置真（确认后经 TaskUseCases.complete 走出现维度）
        val draft = suggestion.proposalDraft.single()
        assertEquals(AiProposalOp.MODIFY, draft.op)
        assertEquals("t1", draft.targetRecordId)
        assertEquals(true, draft.changes!!.completed)
        assertTrue("生成建议不得写出现记录仓库", fakeOccurrences.rows.isEmpty())
    }

    @Test
    fun 授权后久未核对的记忆生成核对建议且不写记忆() = runBlocking {
        authorizer.grant(setOf(AiContextCategory.MEMORY))
        fakeMemories.memories["m1"] = staleMemory("m1", "偏好通过邮件接收周报", verifiedDaysAgo = 40)
        val before = fakeMemories.memories.toMap()

        val suggestions = generate()

        val review = suggestions.filter { it.kind == AdvisorSuggestionKind.MEMORY_REVIEW }
        assertEquals(1, review.size)
        val evidence = review.single().evidence.single()
        assertEquals(AiProposalKind.MEMORY, evidence.recordKind)
        assertEquals("m1", evidence.recordId)
        assertEquals("偏好通过邮件接收周报", evidence.title)
        assertTrue("记忆核对是提醒类建议", review.single().proposalDraft.isEmpty())
        assertEquals("生成建议不得修改记忆", before, fakeMemories.memories.toMap())
    }

    // ---------- 3. 依据失效自动失效不展示 ----------

    @Test
    fun 过期待办完成或删除后建议自动失效不再生成() = runBlocking {
        fakeTasks.tasks["t1"] = presetTask("t1", "买牛奶", dueEpochDay = TODAY - 1)
        assertEquals(1, generate().size)

        // 完成后：依据失效，不再展示
        tasks.complete("t1", true).getOrThrow()
        assertTrue(
            "已完成待办的建议不得再生成",
            generate().none { it.evidence.any { e -> e.recordId == "t1" } },
        )

        // 换一条新过期待办删除后同理
        fakeTasks.tasks["t2"] = presetTask("t2", "取快递", dueEpochDay = TODAY - 2)
        assertEquals(1, generate().size)
        tasks.trash("t2").getOrThrow()
        assertTrue(
            "已删除（回收站）待办的建议不得再生成",
            generate().none { it.evidence.any { e -> e.recordId == "t2" } },
        )
    }

    // ---------- 4. 采纳 = 转为操作提案，走既有状态机 ----------

    @Test
    fun 转为提案落库待确认且业务仓库零写入() = runBlocking {
        fakeTasks.tasks["t1"] = presetTask("t1", "买牛奶", dueEpochDay = TODAY - 1)
        val before = snapshotOfTasks()

        val suggestion = generate().single()
        val proposal = advisor.suggestionToProposal(suggestion).getOrThrow()

        assertEquals(AiProposalStatus.PENDING, proposal.status)
        assertEquals(1, proposal.items.size)
        val item = proposal.items.single()
        assertEquals(AiProposalOp.MODIFY, item.op)
        assertEquals("t1", item.targetRecordId)
        assertEquals(TODAY, item.changes!!.dueEpochDay)
        assertTrue("落库提案应带目标快照", item.targetRecord != null)
        // 提案已持久化；业务仓库零写入（GLOSSARY：操作提案确认后才执行）
        assertEquals(1, proposalStore.load().size)
        assertEquals(AiProposalStatus.PENDING, proposalStore.load().single().status)
        assertEquals("转提案绝不直接写业务仓库", before, fakeTasks.tasks.toMap())
    }

    @Test
    fun 重复转为提案幂等返回既有提案不重复落库() = runBlocking {
        fakeTasks.tasks["t1"] = presetTask("t1", "买牛奶", dueEpochDay = TODAY - 1)
        val suggestion = generate().single()

        val first = advisor.suggestionToProposal(suggestion).getOrThrow()
        val second = advisor.suggestionToProposal(suggestion).getOrThrow()

        assertEquals(first.id, second.id)
        assertEquals(1, proposalStore.load().size)
    }

    @Test
    fun 采纳过期待办建议后经既有状态机确认执行真实改期() = runBlocking {
        fakeTasks.tasks["t1"] = presetTask("t1", "买牛奶", dueEpochDay = TODAY - 1)

        val proposal = advisor.suggestionToProposal(generate().single()).getOrThrow()
        val confirmed = advisorConfirm(proposal.id).getOrThrow()

        assertEquals(AiProposalStatus.EXECUTED, confirmed.status)
        assertEquals("确认后经 TaskUseCases 真实入口改期到今天", TODAY, fakeTasks.tasks["t1"]!!.dueEpochDay)
        assertTrue("回执应为中文已执行说明: ${confirmed.resultMessage}", confirmed.resultMessage!!.contains("已执行"))
        // 依据已消除：改期后不再生成该建议
        assertTrue("改期后建议不再生成", generate().none { it.evidence.any { e -> e.recordId == "t1" } })
    }

    @Test
    fun 采纳重复补做建议后确认执行完成错过的出现() = runBlocking {
        fakeTasks.tasks["t1"] = presetTask(
            "t1",
            "每日站会准备",
            recurrenceRule = RecurrenceRule(frequency = RecurrenceFrequency.DAILY, intervalDays = 1),
        )

        val proposal = advisor.suggestionToProposal(generate().single()).getOrThrow()
        val confirmed = advisorConfirm(proposal.id).getOrThrow()

        assertEquals(AiProposalStatus.EXECUTED, confirmed.status)
        // 经 TaskUseCases.complete 走出现维度：最早错过的一次出现被完成，本体保持进行中
        val completedRows = fakeOccurrences.rows.values.filter { it.completed }
        assertEquals(1, completedRows.size)
        assertEquals(LocalDate.parse("2026-10-20").toEpochDay(), completedRows.single().effectiveEpochDay)
        assertEquals("重复待办本体保持进行中", TaskStatus.ACTIVE, fakeTasks.tasks["t1"]!!.status)
    }

    @Test
    fun 依据已失效时转为提案被拒绝且不落库() = runBlocking {
        fakeTasks.tasks["t1"] = presetTask("t1", "买牛奶", dueEpochDay = TODAY - 1)
        val suggestion = generate().single()
        // 生成建议后待办被完成：依据失效
        tasks.complete("t1", true).getOrThrow()

        val result = advisor.suggestionToProposal(suggestion)

        assertTrue("依据失效必须拒绝转提案", result.isFailure)
        assertTrue(
            "拒绝理由应为中文说明: ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()!!.message!!.contains("失效"),
        )
        assertTrue("拒绝后提案库不得有残留", proposalStore.load().isEmpty())
    }

    @Test
    fun 提醒类建议转为提案被拒绝() = runBlocking {
        fakeCalendar.entries["e1"] = presetEntry("e1", "项目周会", "2026-10-26T10:00", "2026-10-26T11:00")
        fakeCalendar.entries["e2"] = presetEntry("e2", "客户拜访", "2026-10-26T10:30", "2026-10-26T11:30")

        val conflict = generate().first { it.kind == AdvisorSuggestionKind.SCHEDULE_CONFLICT }
        val result = advisor.suggestionToProposal(conflict)

        assertTrue("提醒类建议没有提案草稿，必须拒绝", result.isFailure)
        assertTrue(
            "拒绝理由应为中文说明: ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()!!.message!!.contains("提醒"),
        )
        assertTrue(proposalStore.load().isEmpty())
    }

    /** 确认参谋提案：复用 AiProposalUseCases 既有状态机（与生产 UI 同一入口语义）。 */
    private suspend fun advisorConfirm(proposalId: String): Result<com.zhideer.app.domain.model.AiProposal> {
        val chat = com.zhideer.app.domain.usecase.AiChatUseCases(
            configStore = com.zhideer.app.data.ai.InMemoryAiConfigStore(),
            sessionStore = com.zhideer.app.data.ai.AiSessionStore(File(storeDir, "ai_sessions.preferences_pb")),
            client = com.zhideer.app.data.ai.DeepSeekChatClient(connectTimeoutMs = 15_000, readTimeoutMs = 5_000),
            idGen = { "session-gen" },
            now = { NOW },
            contextAuthorizer = AiContextAuthorizer(),
            contextProvider = { com.zhideer.app.domain.usecase.AiContextSnapshot(generatedAtText = "测试快照") },
            zoneId = ZONE,
        )
        val proposals = com.zhideer.app.domain.usecase.AiProposalUseCases(
            chat = chat,
            store = proposalStore,
            taskUseCases = tasks,
            noteUseCases = com.zhideer.app.domain.usecase.NoteUseCases(
                repository = NoopNotesRepository(),
                idGen = { "note-gen" },
                now = { NOW },
            ),
            calendarUseCases = com.zhideer.app.domain.usecase.CalendarUseCases(
                repository = fakeCalendar,
                idGen = { "entry-gen" },
                now = { NOW },
                zoneId = ZONE.id,
            ),
            calendarReminderUseCases = com.zhideer.app.domain.usecase.CalendarReminderUseCases(
                repository = NoopCalendarRemindersRepository(),
                calendarRepository = fakeCalendar,
                scheduler = NoopAlarmScheduler(),
                idGen = { "calrem-gen" },
                now = { NOW },
            ),
            targetMatcher = AiProposalTargetMatcher(fakeTasks, NoopNotesRepository(), fakeCalendar),
            idGen = { "proposal-confirm" },
            now = { NOW },
            zoneId = ZONE,
        )
        return proposals.confirmProposal(proposalId)
    }

    // ---------- 替身 ----------

    private class FakeTasksRepository : TasksRepository {
        val tasks = LinkedHashMap<String, TodoTask>()

        override fun observeTasks(): Flow<List<TodoTask>> = flowOf(tasks.values.toList())
        override suspend fun getTask(id: String): TodoTask? = tasks[id]
        override suspend fun upsertTask(task: TodoTask) {
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

    /** 记忆仓库替身：记录读取次数（供「未授权不读取」断言）与写入内容。 */
    private class FakeMemoriesRepository : MemoriesRepository {
        val memories = LinkedHashMap<String, Memory>()
        var observeCalls = 0

        override fun observeMemories(): Flow<List<Memory>> {
            observeCalls++
            return flowOf(memories.values.toList())
        }

        override suspend fun getMemory(id: String): Memory? = memories[id]
        override suspend fun upsertMemory(memory: Memory) {
            memories[memory.id] = memory
        }

        override suspend fun deleteMemory(id: String) {
            memories.remove(id)
        }

        // 来源删除边界（T27/#28）：参谋建议不触碰来源联动，此处空实现仅满足接口
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

    /** 空实现替身：参谋建议绝不读取或写入便签。 */
    private class NoopNotesRepository : NotesRepository {
        override fun observeNotes(): Flow<List<Note>> = flowOf(emptyList())
        override suspend fun getNote(id: String): Note? = null
        override suspend fun upsertNote(note: Note) = throw UnsupportedOperationException("参谋建议不应写便签")
        override suspend fun trashNote(id: String, trashedAtMillis: Long) =
            throw UnsupportedOperationException("参谋建议不应改便签")

        override fun observeTrashedNotes(): Flow<List<Note>> = flowOf(emptyList())
        override suspend fun listTrashedNotes(): List<Note> = emptyList()
        override suspend fun purgeNote(id: String) = throw UnsupportedOperationException("参谋建议不应删便签")
    }

    private class NoopCalendarRemindersRepository : com.zhideer.app.domain.repository.CalendarRemindersRepository {
        override fun observeByEntry(entryId: String): Flow<List<com.zhideer.app.domain.model.Reminder>> =
            flowOf(emptyList())

        override suspend fun listByEntry(entryId: String): List<com.zhideer.app.domain.model.Reminder> = emptyList()
        override suspend fun listAll(): List<com.zhideer.app.domain.model.Reminder> = emptyList()
        override suspend fun get(id: String): com.zhideer.app.domain.model.Reminder? = null
        override suspend fun upsert(reminder: com.zhideer.app.domain.model.Reminder) =
            throw UnsupportedOperationException("本测试不应写提醒")

        override suspend fun delete(id: String) = throw UnsupportedOperationException("本测试不应删提醒")
        override suspend fun deleteByEntry(entryId: String) = throw UnsupportedOperationException("本测试不应删提醒")
    }

    private class NoopAlarmScheduler : com.zhideer.app.platform.AlarmScheduler {
        override fun canScheduleExact(): Boolean = false
        override suspend fun scheduleExact(reminderId: String, triggerAtMillis: Long, taskId: String, title: String): Boolean =
            throw UnsupportedOperationException("本测试不应调度提醒")

        override suspend fun scheduleInexact(reminderId: String, triggerAtMillis: Long, taskId: String, title: String): Boolean =
            throw UnsupportedOperationException("本测试不应调度提醒")

        override suspend fun cancel(reminderId: String) = throw UnsupportedOperationException("本测试不应取消提醒")
    }
}
