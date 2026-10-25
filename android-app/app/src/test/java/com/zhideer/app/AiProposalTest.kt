package com.zhideer.app

import com.zhideer.app.data.ai.AiProposalStore
import com.zhideer.app.data.ai.AiSessionStore
import com.zhideer.app.data.ai.DeepSeekChatClient
import com.zhideer.app.data.ai.InMemoryAiConfigStore
import com.zhideer.app.domain.model.AiChatRole
import com.zhideer.app.domain.model.AiConfig
import com.zhideer.app.domain.model.AiProposalDeleteAction
import com.zhideer.app.domain.model.AiProposalKind
import com.zhideer.app.domain.model.AiProposalOp
import com.zhideer.app.domain.model.AiProposalSendOutcome
import com.zhideer.app.domain.model.AiProposalStatus
import com.zhideer.app.domain.model.CalendarEntry
import com.zhideer.app.domain.model.Note
import com.zhideer.app.domain.model.Reminder
import com.zhideer.app.domain.model.ReminderOwner
import com.zhideer.app.domain.model.TaskPriority
import com.zhideer.app.domain.model.TaskStatus
import com.zhideer.app.domain.model.TodoTask
import com.zhideer.app.domain.repository.CalendarRemindersRepository
import com.zhideer.app.domain.repository.CalendarRepository
import com.zhideer.app.domain.repository.NotesRepository
import com.zhideer.app.domain.repository.TasksRepository
import com.zhideer.app.domain.usecase.AiChatUseCases
import com.zhideer.app.domain.usecase.AiContextAuthorizer
import com.zhideer.app.domain.usecase.AiContextCategory
import com.zhideer.app.domain.usecase.AiContextProvider
import com.zhideer.app.domain.usecase.AiContextSnapshot
import com.zhideer.app.domain.usecase.AiProposalTargetMatcher
import com.zhideer.app.domain.usecase.AiProposalUseCases
import com.zhideer.app.domain.usecase.proposalChangePreviewLines
import com.zhideer.app.domain.usecase.proposalDeleteActionText
import com.zhideer.app.domain.usecase.CalendarReminderUseCases
import com.zhideer.app.domain.usecase.CalendarUseCases
import com.zhideer.app.domain.usecase.NoteUseCases
import com.zhideer.app.domain.usecase.TaskUseCases
import com.zhideer.app.platform.AlarmScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * T19/#20 验收测试：通过 AI 确认创建便签和待办（操作提案）。
 *
 * 协议（system prompt 与模型约定）：模型在回复末尾输出 ```json 代码块，结构为
 * {"proposals":[{"kind":"task"|"note","title":...,"dueDate":"yyyy-MM-dd"|"dueAt":"yyyy-MM-dd'T'HH:mm",...}]}；
 * dueDate（日期截止，不补时刻）与 dueAt（时刻截止）互斥；无创建意图时不输出 JSON 块（普通回复）。
 *
 * 状态机：PENDING（待确认）→ CONFIRMED（已确认执行中；进程中断停留此态=结果待核实）
 * → EXECUTED（结果已回写）；PENDING → EXPIRED（忽略或条目清空）。
 *
 * 本测试全部经 MockWebServer 固定响应验证；真实模型表现待用户配置 Key 后由 Root 观察。
 */
class AiProposalTest {

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
    private lateinit var fakeTasks: FakeTasksRepository
    private lateinit var fakeNotes: FakeNotesRepository
    private lateinit var fakeCalendar: FakeCalendarRepository
    private lateinit var fakeCalendarReminders: FakeCalendarRemindersRepository
    private lateinit var aiScheduler: AiFakeAlarmScheduler
    private lateinit var storeDir: File
    private val idSeq = AtomicInteger(0)
    private val taskSeq = AtomicInteger(0)
    private val noteSeq = AtomicInteger(0)
    private val entrySeq = AtomicInteger(0)
    private val calRemSeq = AtomicInteger(0)
    private var clock: Long = 0L

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        configStore = InMemoryAiConfigStore()
        configStore.save(AiConfig(apiKey = KEY, model = MODEL, baseUrl = server.url("/v1").toString()))
        storeDir = File(System.getProperty("java.io.tmpdir"), "t20-test-${System.nanoTime()}")
        check(storeDir.mkdirs() || storeDir.isDirectory) { "无法创建测试目录" }
        sessionStore = AiSessionStore(File(storeDir, "ai_sessions.preferences_pb"))
        proposalStore = AiProposalStore(File(storeDir, "ai_proposals.preferences_pb"))
        client = DeepSeekChatClient(connectTimeoutMs = 2_000, readTimeoutMs = 800)
        clock = LocalDateTime.parse("2026-10-25T09:00").atZone(ZONE).toInstant().toEpochMilli()
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
        fakeTasks = FakeTasksRepository()
        fakeNotes = FakeNotesRepository()
        fakeCalendar = FakeCalendarRepository()
        fakeCalendarReminders = FakeCalendarRemindersRepository()
        aiScheduler = AiFakeAlarmScheduler()
        proposals = AiProposalUseCases(
            chat = chat,
            store = proposalStore,
            taskUseCases = TaskUseCases(
                repository = fakeTasks,
                idGen = { "task-${taskSeq.incrementAndGet()}" },
                now = { clock },
                zoneId = ZONE.id,
            ),
            noteUseCases = NoteUseCases(
                repository = fakeNotes,
                idGen = { "note-${noteSeq.incrementAndGet()}" },
                now = { clock },
            ),
            // T20/#21：确认执行经日程与日程提醒真实入口（与 AppContainer 相同组装方式）
            calendarUseCases = CalendarUseCases(
                repository = fakeCalendar,
                idGen = { "entry-${entrySeq.incrementAndGet()}" },
                now = { clock },
                zoneId = ZONE.id,
            ),
            calendarReminderUseCases = CalendarReminderUseCases(
                repository = fakeCalendarReminders,
                calendarRepository = fakeCalendar,
                scheduler = aiScheduler,
                idGen = { "calrem-${calRemSeq.incrementAndGet()}" },
                now = { clock },
            ),
            idGen = { "proposal-${idSeq.incrementAndGet()}" },
            now = { clock },
            zoneId = ZONE,
            // T21/#22 纯新增：修改/删除提案的目标定位（授权门在用例层，匹配器只读仓库）
            targetMatcher = AiProposalTargetMatcher(
                tasksRepository = fakeTasks,
                notesRepository = fakeNotes,
                calendarRepository = fakeCalendar,
            ),
        )
    }

    @After
    fun tearDown() {
        client.close()
        server.close()
        sessionStore.close()
        proposalStore.close()
        storeDir.deleteRecursively()
    }

    // ---------- 工具 ----------

    /** 把模型回复文本拆成多个 SSE 增量块并以 [DONE] 结束（模拟真实流式）。 */
    private fun sseProposal(fullText: String, chunks: Int = 3): MockResponse {
        val size = maxOf(1, chunks)
        val step = (fullText.length + size - 1) / size
        val deltas = fullText.chunked(step)
        val body = buildString {
            deltas.forEach { delta ->
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
        .body(
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"${escapeJson(fullText)}\"}}]}",
        )
        .build()

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    /** 模型按协议输出的提案 JSON 代码块（包裹在 ```json …``` 中）。 */
    private fun proposalBlock(vararg items: String): String =
        "已为你起草操作提案，请确认。\n```json\n{\"proposals\":[${items.joinToString(",")} ]}\n```"

    private fun taskJson(
        title: String = "买牛奶",
        dueDate: String? = null,
        dueAt: String? = null,
        priority: String? = null,
        category: String? = null,
        note: String? = null,
    ): String = buildString {
        append("{\"kind\":\"task\",\"title\":\"${escapeJson(title)}\"")
        if (dueDate != null) append(",\"dueDate\":\"$dueDate\"")
        if (dueAt != null) append(",\"dueAt\":\"$dueAt\"")
        if (priority != null) append(",\"priority\":\"$priority\"")
        if (category != null) append(",\"category\":\"$category\"")
        if (note != null) append(",\"note\":\"${escapeJson(note)}\"")
        append("}")
    }

    private fun noteJson(
        title: String = "会议要点",
        content: String? = null,
        tags: List<String>? = null,
        items: List<String>? = null,
    ): String = buildString {
        append("{\"kind\":\"note\",\"title\":\"${escapeJson(title)}\"")
        if (content != null) append(",\"content\":\"${escapeJson(content)}\"")
        if (tags != null) append(",\"tags\":${tags.joinToString(",", "[", "]") { "\"$it\"" }}")
        if (items != null) append(",\"items\":${items.joinToString(",", "[", "]") { "\"${escapeJson(it)}\"" }}")
        append("}")
    }

    private suspend fun send(text: String = "帮我建一个待办") = proposals.sendAndIngest(null, text) {}

    private fun assertNoBusinessWrite() {
        assertTrue("未确认前待办仓库必须为空", fakeTasks.tasks.isEmpty())
        assertTrue("未确认前便签仓库必须为空", fakeNotes.notes.isEmpty())
        assertTrue("未确认前日程仓库必须为空", fakeCalendar.entries.isEmpty())
        assertTrue("未确认前日程提醒仓库必须为空", fakeCalendarReminders.reminders.isEmpty())
    }

    /** 模型按协议输出的日程提案 JSON（start/end 为本地时间字符串，由解析层按创建时区换算）。 */
    private fun scheduleJson(
        title: String = "项目周会",
        start: String? = "2026-10-26T14:00",
        end: String? = "2026-10-26T15:30",
        location: String? = null,
        category: String? = null,
        note: String? = null,
        reminder: String? = null,
        reminders: List<String>? = null,
    ): String = buildString {
        append("{\"kind\":\"schedule\",\"title\":\"${escapeJson(title)}\"")
        if (start != null) append(",\"start\":\"$start\"")
        if (end != null) append(",\"end\":\"$end\"")
        if (location != null) append(",\"location\":\"${escapeJson(location)}\"")
        if (category != null) append(",\"category\":\"$category\"")
        if (note != null) append(",\"note\":\"${escapeJson(note)}\"")
        if (reminder != null) append(",\"reminder\":$reminder")
        if (reminders != null) append(",\"reminders\":[${reminders.joinToString(",")}]")
        append("}")
    }

    private fun relativeReminderJson(offsetMillis: Long = 1_800_000L): String =
        "{\"type\":\"relative\",\"offsetMillis\":$offsetMillis}"

    private fun absoluteReminderJson(remindAt: String): String =
        "{\"type\":\"absolute\",\"remindAt\":\"$remindAt\"}"

    /** 双响应：第一次流式 + 自动重试（非流式），两次均返回同一非法提案。 */
    private fun enqueueInvalidTwice(badProposalText: String) {
        server.enqueue(sseProposal(badProposalText))
        server.enqueue(completionProposal(badProposalText))
    }

    /** 断言解析失败且整批未保存：中文提示包含全部关键词，提案库与业务仓库均无写入。 */
    private suspend fun assertParseRejected(text: String, vararg messageParts: String) {
        val result = send(text)
        assertTrue("非法提案应解析失败", result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        messageParts.forEach { part ->
            assertTrue("中文提示应包含「$part」: $message", message.contains(part))
        }
        assertTrue(proposalStore.load().isEmpty())
        assertNoBusinessWrite()
    }

    // ---------- 1. 提案解析：合法 ----------

    @Test
    fun 创建待办请求产生待确认提案且不经确认绝不写入() = runBlocking {
        server.enqueue(sseProposal(proposalBlock(taskJson(dueDate = "2026-10-26", priority = "HIGH", category = "生活"))))

        val outcome = send().getOrThrow()

        assertTrue(outcome is AiProposalSendOutcome.ProposalCreated)
        val created = outcome as AiProposalSendOutcome.ProposalCreated
        val proposal = created.proposal
        assertEquals(AiProposalStatus.PENDING, proposal.status)
        assertEquals(1, proposal.items.size)
        val item = proposal.items.single()
        assertEquals(AiProposalKind.TASK, item.kind)
        assertEquals("买牛奶", item.title)
        assertEquals("HIGH", item.priority)
        assertEquals("生活", item.category)
        assertEquals(LocalDate.parse("2026-10-26").toEpochDay(), item.dueEpochDay)
        assertNullBut(item.dueAtMillis, null)
        // 验收核心：提案产生后业务仓库无新记录
        assertNoBusinessWrite()
        // 会话中的助手消息为人类可读摘要，不把原始 JSON 抛给用户
        val last = created.session.messages.last()
        assertEquals(AiChatRole.ASSISTANT, last.role)
        assertFalse("摘要不得包含 JSON 代码块标记", last.content.contains("```"))
        assertTrue(last.content.contains("买牛奶"))
        // 提案已持久化
        val stored = proposalStore.load().single()
        assertEquals(proposal.id, stored.id)
        assertEquals(AiProposalStatus.PENDING, stored.status)
    }

    @Test
    fun 创建便签请求产生提案并保留原话与清单() = runBlocking {
        server.enqueue(
            sseProposal(
                proposalBlock(
                    noteJson(
                        title = "周会要点",
                        content = "记住：周三前把版本说明发给小林，原文照抄不改写。",
                        tags = listOf("工作", "周会"),
                        items = listOf("发版本说明", "订会议室"),
                    ),
                ),
            ),
        )

        val outcome = send("记个便签：周三前把版本说明发给小林").getOrThrow() as AiProposalSendOutcome.ProposalCreated
        val item = outcome.proposal.items.single()
        assertEquals(AiProposalKind.NOTE, item.kind)
        assertEquals("周会要点", item.title)
        // 便签保留原话：正文与用户原话一致，解析层不改写
        assertEquals("记住：周三前把版本说明发给小林，原文照抄不改写。", item.content)
        assertEquals(listOf("工作", "周会"), item.tags)
        assertEquals(listOf("发版本说明", "订会议室"), item.items)
        assertNoBusinessWrite()
    }

    @Test
    fun 时刻截止按创建时区换算_日期截止不补具体时刻() = runBlocking {
        server.enqueue(sseProposal(proposalBlock(taskJson(dueAt = "2026-10-27T14:30"), taskJson(dueDate = "2026-10-28"))))
        val outcome = send().getOrThrow() as AiProposalSendOutcome.ProposalCreated
        val (withTime, dayOnly) = outcome.proposal.items
        val expectedMillis = LocalDateTime.parse("2026-10-27T14:30").atZone(ZONE).toInstant().toEpochMilli()
        assertEquals(expectedMillis, withTime.dueAtMillis)
        assertEquals(LocalDate.parse("2026-10-28").toEpochDay(), dayOnly.dueEpochDay)
    }

    // ---------- 2. 提案解析：非法与截断 ----------

    @Test
    fun 空标题提案解析失败给出中文提示且不创建半成品() = runBlocking {
        // 第一次与重试一次均为空标题 → 报错
        server.enqueue(sseProposal(proposalBlock(taskJson(title = "  "))))
        server.enqueue(completionProposal(proposalBlock(taskJson(title = "  "))))

        val result = send()
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue("应包含标题相关中文提示: $message", message.contains("标题"))
        assertTrue(proposalStore.load().isEmpty())
        assertNoBusinessWrite()
    }

    @Test
    fun 双截止字段非法整批不保存() = runBlocking {
        // dueDate 与 dueAt 同时出现 → 协议互斥，非法；批量中任一项校验失败整批不保存
        val bad = "{\"kind\":\"task\",\"title\":\"混合\",\"dueDate\":\"2026-10-26\",\"dueAt\":\"2026-10-26T18:00\"}"
        server.enqueue(sseProposal(proposalBlock(taskJson(title = "正常项"), bad)))
        server.enqueue(completionProposal(proposalBlock(taskJson(title = "正常项"), bad)))

        val result = send()
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue("应为中文双截止提示: $message", message.contains("截止"))
        assertTrue(proposalStore.load().isEmpty())
        assertNoBusinessWrite()
    }

    @Test
    fun 截断的提案JSON自动重试一次后成功() = runBlocking {
        // 第一次流式输出被截断（JSON 不完整但流正常结束）→ 自动重试一次（非流式）→ 合法
        val truncated = "好的，提案如下：\n```json\n{\"proposals\":[{\"kind\":\"task\",\"title\":\"买牛"
        server.enqueue(sseProposal(truncated))
        server.enqueue(completionProposal(proposalBlock(taskJson(dueDate = "2026-10-26"))))

        val outcome = send().getOrThrow() as AiProposalSendOutcome.ProposalCreated
        assertEquals("买牛奶", outcome.proposal.items.single().title)
        assertNoBusinessWrite()
    }

    @Test
    fun 两次均无法解析返回中文可诊断提示() = runBlocking {
        server.enqueue(sseProposal("```json\n{\"proposals\":[{\"kind\":\"task\"}")) // 截断
        server.enqueue(completionProposal("```json\n{\"proposals\":[{\"kind\":\"task\"}")) // 重试仍截断

        val result = send()
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue("应为中文可诊断提示: $message", message.contains("提案"))
        assertTrue(proposalStore.load().isEmpty())
        assertNoBusinessWrite()
        // 第一轮的坏回复不进入会话：会话只有用户消息
        val session = chat.listSessions().first().single()
        assertEquals(listOf(AiChatRole.USER), session.messages.map { it.role })
    }

    @Test
    fun 无创建意图时按普通回复处理不产生提案() = runBlocking {
        server.enqueue(sseProposal("今天天气不错，适合散步。需要我帮你安排什么吗？"))

        val outcome = send("今天天气怎么样").getOrThrow()
        assertTrue(outcome is AiProposalSendOutcome.PlainReply)
        val plain = outcome as AiProposalSendOutcome.PlainReply
        assertEquals("今天天气不错，适合散步。需要我帮你安排什么吗？", plain.session.messages.last().content)
        assertTrue(proposalStore.load().isEmpty())
        assertNoBusinessWrite()
    }

    // ---------- 3. 确认：经真实用例入口 + 幂等 ----------

    @Test
    fun 确认创建经真实用例入口写入并回执含记录标识() = runBlocking {
        server.enqueue(sseProposal(proposalBlock(taskJson(dueDate = "2026-10-26"))))
        val outcome = send().getOrThrow() as AiProposalSendOutcome.ProposalCreated
        val proposalId = outcome.proposal.id
        val sessionId = outcome.session.id

        val confirmed = proposals.confirmProposal(proposalId).getOrThrow()

        assertEquals(AiProposalStatus.EXECUTED, confirmed.status)
        val task = fakeTasks.tasks.values.single()
        assertEquals("买牛奶", task.title)
        assertEquals(task.id, confirmed.createdTaskIds.single())
        assertNotNull(confirmed.resultMessage)
        assertTrue("回执应包含已创建记录标识: ${confirmed.resultMessage}", confirmed.resultMessage!!.contains(task.id))
        // 结果回写到对话：会话末尾出现含记录标识的助手回执消息
        val session = chat.openSession(sessionId)!!
        val receipt = session.messages.last()
        assertEquals(AiChatRole.ASSISTANT, receipt.role)
        assertTrue(receipt.content.contains(task.id))
    }

    @Test
    fun 重复确认幂等不产生第二条记录() = runBlocking {
        server.enqueue(sseProposal(proposalBlock(taskJson(dueDate = "2026-10-26"))))
        val outcome = send().getOrThrow() as AiProposalSendOutcome.ProposalCreated
        val proposalId = outcome.proposal.id

        val first = proposals.confirmProposal(proposalId).getOrThrow()
        val second = proposals.confirmProposal(proposalId).getOrThrow()

        // 第二次确认不产生第二条记录：仍是同一条，仓库仍只有一条
        assertEquals(first.id, second.id)
        assertEquals(AiProposalStatus.EXECUTED, second.status)
        assertEquals(1, fakeTasks.tasks.size)
        assertEquals(first.createdTaskIds, second.createdTaskIds)
        assertEquals(first.resultMessage, second.resultMessage)
    }

    @Test
    fun 确认便签提案经NoteUseCases创建() = runBlocking {
        server.enqueue(
            sseProposal(
                proposalBlock(
                    noteJson(content = "正文", items = listOf("清单项一")),
                ),
            ),
        )
        val outcome = send().getOrThrow() as AiProposalSendOutcome.ProposalCreated
        val confirmed = proposals.confirmProposal(outcome.proposal.id).getOrThrow()

        val note = fakeNotes.notes.values.single()
        assertEquals("会议要点", note.title)
        assertEquals("正文", note.content)
        assertEquals(1, note.items.size)
        assertEquals("清单项一", note.items.single().text)
        assertEquals(note.id, confirmed.createdNoteIds.single())
        assertEquals(AiProposalStatus.EXECUTED, confirmed.status)
    }

    // ---------- 4. 忽略语义 ----------

    @Test
    fun 忽略的提案不执行且不可再确认() = runBlocking {
        server.enqueue(sseProposal(proposalBlock(taskJson(dueDate = "2026-10-26"))))
        val outcome = send().getOrThrow() as AiProposalSendOutcome.ProposalCreated
        val proposalId = outcome.proposal.id

        assertTrue(proposals.dismissProposal(proposalId).isSuccess)
        assertEquals(AiProposalStatus.EXPIRED, proposalStore.load().single().status)
        assertNoBusinessWrite()

        val confirmAgain = proposals.confirmProposal(proposalId)
        assertTrue("已忽略的提案不可确认", confirmAgain.isFailure)
        assertTrue(confirmAgain.exceptionOrNull()!!.message!!.contains("忽略"))
        assertNoBusinessWrite()

        // 重复忽略幂等
        assertTrue(proposals.dismissProposal(proposalId).isSuccess)
    }

    // ---------- 5. 逐项移除 ----------

    @Test
    fun 逐项移除后确认只创建剩余条目() = runBlocking {
        server.enqueue(
            sseProposal(proposalBlock(taskJson(dueDate = "2026-10-26"), noteJson(title = "不要的便签"))),
        )
        val outcome = send().getOrThrow() as AiProposalSendOutcome.ProposalCreated
        val noteItem = outcome.proposal.items.single { it.kind == AiProposalKind.NOTE }

        val updated = proposals.removeProposalItem(outcome.proposal.id, noteItem.id).getOrThrow()
        assertEquals(1, updated.items.size)
        assertEquals(AiProposalKind.TASK, updated.items.single().kind)

        val confirmed = proposals.confirmProposal(outcome.proposal.id).getOrThrow()
        assertEquals(1, fakeTasks.tasks.size)
        assertEquals(0, fakeNotes.notes.size)
        assertEquals(1, confirmed.items.size)
    }

    @Test
    fun 移除全部条目后提案失效() = runBlocking {
        server.enqueue(sseProposal(proposalBlock(taskJson(dueDate = "2026-10-26"))))
        val outcome = send().getOrThrow() as AiProposalSendOutcome.ProposalCreated
        val itemId = outcome.proposal.items.single().id

        val updated = proposals.removeProposalItem(outcome.proposal.id, itemId).getOrThrow()
        assertEquals(AiProposalStatus.EXPIRED, updated.status)
        val confirmAfterEmpty = proposals.confirmProposal(outcome.proposal.id)
        assertTrue(confirmAfterEmpty.isFailure)
        assertNoBusinessWrite()
    }

    // ---------- 6. 取消生成 ----------

    @Test
    fun 取消生成时未确认提案不产生任何写入() = runBlocking {
        val trickleBody = buildString {
            repeat(12) { i -> append("data: {\"choices\":[{\"delta\":{\"content\":\"第${i}句\"}}]}\n\n") }
        }
        val respondNormal = AtomicBoolean(false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (respondNormal.get()) {
                    sseProposal(proposalBlock(taskJson(dueDate = "2026-10-26")))
                } else {
                    MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "text/event-stream")
                        .body(trickleBody)
                        .throttleBody(20, 100, TimeUnit.MILLISECONDS)
                        .build()
                }
        }

        val deltas = AtomicInteger(0)
        val job = launch(Dispatchers.IO) {
            proposals.sendAndIngest(null, "帮我建个待办") { _ -> deltas.incrementAndGet() }
        }
        withTimeout(15_000) {
            while (deltas.get() < 1) delay(20)
        }
        chat.cancelGeneration()
        job.join()
        delay(300)

        assertTrue(proposalStore.load().isEmpty())
        assertNoBusinessWrite()
        // 沿用既有取消语义：用户消息保留，无部分回复
        val session = chat.listSessions().first().single()
        assertEquals(listOf(AiChatRole.USER), session.messages.map { it.role })
    }

    // ---------- 7. 失败原子性 ----------

    @Test
    fun 本地保存失败如实报告部分结果且重复确认不重建() = runBlocking {
        server.enqueue(
            sseProposal(proposalBlock(taskJson(title = "任务一"), taskJson(title = "任务二"))),
        )
        val outcome = send().getOrThrow() as AiProposalSendOutcome.ProposalCreated
        fakeTasks.failNextUpsert = "磁盘空间不足"

        val result = proposals.confirmProposal(outcome.proposal.id)
        assertTrue("部分失败时确认操作本身完成并回执", result.isSuccess)
        val confirmed = result.getOrThrow()
        assertEquals(AiProposalStatus.EXECUTED, confirmed.status)
        assertEquals(1, fakeTasks.tasks.size)
        val message = confirmed.resultMessage!!
        assertTrue("回执应说明已创建项: $message", message.contains("任务一") && message.contains("已创建"))
        assertTrue("回执应如实说明保存失败: $message", message.contains("任务二") && message.contains("失败") && message.contains("磁盘空间不足"))

        // 重复确认幂等：不产生第二条记录
        val again = proposals.confirmProposal(outcome.proposal.id).getOrThrow()
        assertEquals(1, fakeTasks.tasks.size)
        assertEquals(confirmed.resultMessage, again.resultMessage)
    }

    // ---------- 8. 授权边界 ----------

    @Test
    fun 创建提案不需要读取本地数据授权() = runBlocking {
        // 未授权任何上下文类别（grant == null）：提案路径只写不读，必须可用
        assertNull(chat.currentContextGrant())
        server.enqueue(sseProposal(proposalBlock(taskJson(dueDate = "2026-10-26"))))
        val outcome = send().getOrThrow() as AiProposalSendOutcome.ProposalCreated
        assertEquals(AiProposalKind.TASK, outcome.proposal.items.single().kind)
        assertNoBusinessWrite()
    }

    // ---------- 9. 请求体协议 ----------

    @Test
    fun 提案请求携带协议提示且普通发送路径不受影响() = runBlocking {
        server.enqueue(sseProposal(proposalBlock(taskJson(dueDate = "2026-10-26"))))
        proposals.sendAndIngest(null, "建个待办") {}.getOrThrow()

        val r = server.takeRequest()
        val body = r.body!!.utf8()
        val messages = Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray
        // 第一条为提案协议 system 消息
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        val systemText = messages[0].jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(systemText.contains("proposals"))
        assertTrue(systemText.contains("2026-10-25")) // 注入当前日期，供模型推算相对时间
        // 请求仍以 user 消息收尾
        assertEquals("user", messages.last().jsonObject["role"]!!.jsonPrimitive.content)

        // 普通发送路径请求体不变：不含协议提示
        server.enqueue(sseProposal("普通回复", chunks = 1))
        chat.sendStreaming(null, "普通问题") {}.getOrThrow()
        val r2 = server.takeRequest()
        val messages2 = Json.parseToJsonElement(r2.body!!.utf8()).jsonObject["messages"]!!.jsonArray
        assertEquals(1, messages2.size)
        assertEquals("user", messages2[0].jsonObject["role"]!!.jsonPrimitive.content)
    }

    // ---------- 10. 日程提案（T20/#21）：解析与时区换算 ----------

    @Test
    fun 日程提案解析出时间地点分类备注与相对提醒() = runBlocking {
        server.enqueue(
            sseProposal(
                proposalBlock(
                    scheduleJson(
                        location = "3号会议室",
                        category = "工作",
                        note = "带笔记本",
                        reminder = relativeReminderJson(1_800_000L),
                    ),
                ),
            ),
        )

        val outcome = send("帮我安排明天下午的项目周会").getOrThrow() as AiProposalSendOutcome.ProposalCreated
        val proposal = outcome.proposal
        assertEquals(AiProposalStatus.PENDING, proposal.status)
        val item = proposal.items.single()
        assertEquals(AiProposalKind.SCHEDULE, item.kind)
        assertEquals("项目周会", item.title)
        val expectedStart = LocalDateTime.parse("2026-10-26T14:00").atZone(ZONE).toInstant().toEpochMilli()
        val expectedEnd = LocalDateTime.parse("2026-10-26T15:30").atZone(ZONE).toInstant().toEpochMilli()
        assertEquals(expectedStart, item.startMillis)
        assertEquals(expectedEnd, item.endMillis)
        assertEquals("3号会议室", item.location)
        assertEquals("工作", item.category)
        assertEquals("带笔记本", item.note)
        val reminder = item.reminders.single()
        assertEquals(1_800_000L, reminder.offsetMillis)
        assertNull(reminder.remindAtMillis)
        // 验收核心：提案产生后业务仓库无新记录
        assertNoBusinessWrite()
        // 会话中的助手消息为人类可读摘要
        val summary = outcome.session.messages.last().content
        assertFalse("摘要不得包含 JSON 代码块标记", summary.contains("```"))
        assertTrue(summary.contains("项目周会"))
    }

    @Test
    fun 日程时间按创建时区换算且摘要跨日明确显示() = runBlocking {
        server.enqueue(sseProposal(proposalBlock(scheduleJson(start = "2026-10-26T23:00", end = "2026-10-27T01:00"))))
        val outcome = send().getOrThrow() as AiProposalSendOutcome.ProposalCreated
        val item = outcome.proposal.items.single()
        assertEquals(
            LocalDateTime.parse("2026-10-26T23:00").atZone(ZONE).toInstant().toEpochMilli(),
            item.startMillis,
        )
        assertEquals(
            LocalDateTime.parse("2026-10-27T01:00").atZone(ZONE).toInstant().toEpochMilli(),
            item.endMillis,
        )
        // 跨日：摘要明确写出结束日期（按创建时区换算）
        val crossDaySummary = outcome.session.messages.last().content
        assertTrue("跨日应显示完整日期: $crossDaySummary", crossDaySummary.contains("2026-10-26 23:00 ～ 2026-10-27 01:00"))

        // 同日：结束只显示时刻
        server.enqueue(sseProposal(proposalBlock(scheduleJson())))
        val sameDay = send().getOrThrow() as AiProposalSendOutcome.ProposalCreated
        val sameDaySummary = sameDay.session.messages.last().content
        assertTrue("同日应显示开始日期与结束时刻: $sameDaySummary", sameDaySummary.contains("2026-10-26 14:00 ～ 15:30"))
    }

    @Test
    fun 提醒数组重复项去重只保留一条() = runBlocking {
        server.enqueue(
            sseProposal(
                proposalBlock(
                    scheduleJson(
                        reminders = listOf(relativeReminderJson(1_800_000L), relativeReminderJson(1_800_000L)),
                    ),
                ),
            ),
        )
        val outcome = send().getOrThrow() as AiProposalSendOutcome.ProposalCreated
        assertEquals(1, outcome.proposal.items.single().reminders.size)
        assertNoBusinessWrite()
    }

    // ---------- 11. 日程提案：非法时段与非法提醒整批拒绝 ----------

    @Test
    fun 日程结束早于开始解析拒绝整批不保存() = runBlocking {
        enqueueInvalidTwice(proposalBlock(scheduleJson(start = "2026-10-26T15:00", end = "2026-10-26T14:00")))
        assertParseRejected("时间写反了", "结束时间", "早于开始时间")
    }

    @Test
    fun 日程缺少结束时间不擅自推断时长解析拒绝() = runBlocking {
        enqueueInvalidTwice(proposalBlock(scheduleJson(end = null)))
        assertParseRejected("下午三点开个会", "结束时间")
    }

    @Test
    fun 独立时刻提醒早于日程开始整批拒绝() = runBlocking {
        enqueueInvalidTwice(
            proposalBlock(scheduleJson(reminder = absoluteReminderJson("2026-10-26T13:00"))),
        )
        assertParseRejected("提前提醒我", "提醒", "早于日程开始时间")
    }

    @Test
    fun 相对提醒提前量非正整批拒绝() = runBlocking {
        enqueueInvalidTwice(proposalBlock(scheduleJson(reminder = relativeReminderJson(0L))))
        assertParseRejected("开始时提醒我", "提前量")
    }

    @Test
    fun 相对提前量与独立时刻同时给出整批拒绝() = runBlocking {
        val both = "{\"type\":\"relative\",\"offsetMillis\":60000,\"remindAt\":\"2026-10-26T14:00\"}"
        enqueueInvalidTwice(proposalBlock(scheduleJson(reminder = both)))
        assertParseRejected("提醒写复杂了", "只能选择其一")
    }

    @Test
    fun 提醒类型无法识别整批拒绝() = runBlocking {
        val bad = "{\"type\":\"weekly\",\"offsetMillis\":60000}"
        enqueueInvalidTwice(proposalBlock(scheduleJson(reminder = bad)))
        assertParseRejected("每周提醒我", "提醒类型")
    }

    @Test
    fun 待办或便签提案携带日程字段整批拒绝() = runBlocking {
        val taskWithSchedule =
            "{\"kind\":\"task\",\"title\":\"混淆\",\"start\":\"2026-10-26T14:00\",\"end\":\"2026-10-26T15:00\"}"
        enqueueInvalidTwice(proposalBlock(taskWithSchedule))
        assertParseRejected("建个待办", "日程时间或提醒字段")

        val noteWithReminder =
            "{\"kind\":\"note\",\"title\":\"混淆便签\",\"reminder\":{\"type\":\"relative\",\"offsetMillis\":60000}}"
        enqueueInvalidTwice(proposalBlock(noteWithReminder))
        assertParseRejected("记个便签", "日程时间或提醒字段")

        val scheduleWithDue =
            "{\"kind\":\"schedule\",\"title\":\"混淆日程\",\"start\":\"2026-10-26T14:00\"," +
                "\"end\":\"2026-10-26T15:00\",\"dueDate\":\"2026-10-26\"}"
        enqueueInvalidTwice(proposalBlock(scheduleWithDue))
        assertParseRejected("建个日程", "截止")
    }

    // ---------- 12. 日程提案：确认经真实入口创建日程并设置提醒 ----------

    @Test
    fun 确认日程提案经真实入口创建日程并设置相对提醒() = runBlocking {
        server.enqueue(
            sseProposal(
                proposalBlock(
                    scheduleJson(
                        location = "3号会议室",
                        category = "工作",
                        note = "带笔记本",
                        reminder = relativeReminderJson(1_800_000L),
                    ),
                ),
            ),
        )
        val outcome = send().getOrThrow() as AiProposalSendOutcome.ProposalCreated
        val expectedStart = LocalDateTime.parse("2026-10-26T14:00").atZone(ZONE).toInstant().toEpochMilli()

        val confirmed = proposals.confirmProposal(outcome.proposal.id).getOrThrow()

        assertEquals(AiProposalStatus.EXECUTED, confirmed.status)
        // 日程经 CalendarUseCases.create 真实入口创建，字段与时区锚定一致
        val entry = fakeCalendar.entries.values.single()
        assertEquals("项目周会", entry.title)
        assertEquals(expectedStart, entry.startMillis)
        assertEquals(expectedStart + 90 * 60_000L, entry.endMillis)
        assertEquals(ZONE.id, entry.timezoneId)
        assertEquals("3号会议室", entry.location)
        assertEquals("工作", entry.category)
        assertEquals("带笔记本", entry.note)
        assertEquals(entry.id, confirmed.createdScheduleIds.single())
        // 提醒经 CalendarReminderUseCases 真实入口设置：日程归属、相对开始提前量
        val reminder = fakeCalendarReminders.reminders.values.single()
        assertEquals(ReminderOwner.CALENDAR, reminder.ownerType)
        assertEquals(entry.id, reminder.taskId)
        assertEquals(1_800_000L, reminder.relativeOffsetMillis)
        assertNull(reminder.remindAtMillis)
        // 调度请求已按 开始-提前量 提交（提交请求不等于真实触发，回执如实区分）
        val expectedTrigger = expectedStart - 1_800_000L
        assertTrue(
            "应按开始-提前量提交精确闹钟调度请求",
            aiScheduler.exactCalls.any { it.first == "cal:${reminder.id}" && it.second == expectedTrigger },
        )
        // 回执含记录标识并区分提醒结果；结果回写到对话流
        val receipt = confirmed.resultMessage!!
        assertTrue("回执应包含日程记录标识: $receipt", receipt.contains(entry.id))
        assertTrue("回执应说明提醒已设置: $receipt", receipt.contains("提醒已设置（1 条）"))
        val session = chat.openSession(outcome.session.id)!!
        assertTrue(session.messages.last().content.contains(entry.id))
    }

    @Test
    fun 确认带独立时刻提醒的日程提案() = runBlocking {
        server.enqueue(
            sseProposal(proposalBlock(scheduleJson(reminder = absoluteReminderJson("2026-10-26T14:00")))),
        )
        val outcome = send().getOrThrow() as AiProposalSendOutcome.ProposalCreated
        val expectedStart = LocalDateTime.parse("2026-10-26T14:00").atZone(ZONE).toInstant().toEpochMilli()

        proposals.confirmProposal(outcome.proposal.id).getOrThrow()

        val reminder = fakeCalendarReminders.reminders.values.single()
        assertEquals(expectedStart, reminder.remindAtMillis)
        assertNull(reminder.relativeOffsetMillis)
    }

    @Test
    fun 确认多个提醒的日程提案全部设置() = runBlocking {
        server.enqueue(
            sseProposal(
                proposalBlock(
                    scheduleJson(
                        reminders = listOf(relativeReminderJson(1_800_000L), absoluteReminderJson("2026-10-26T16:00")),
                    ),
                ),
            ),
        )
        val outcome = send().getOrThrow() as AiProposalSendOutcome.ProposalCreated

        val confirmed = proposals.confirmProposal(outcome.proposal.id).getOrThrow()

        assertEquals(2, fakeCalendarReminders.reminders.size)
        val expectedAbs = LocalDateTime.parse("2026-10-26T16:00").atZone(ZONE).toInstant().toEpochMilli()
        val byRelative = fakeCalendarReminders.reminders.values.single { it.relativeOffsetMillis != null }
        val byAbsolute = fakeCalendarReminders.reminders.values.single { it.remindAtMillis != null }
        assertEquals(1_800_000L, byRelative.relativeOffsetMillis)
        assertEquals(expectedAbs, byAbsolute.remindAtMillis)
        assertTrue(confirmed.resultMessage!!.contains("提醒已设置（2 条）"))
    }

    @Test
    fun 确认无提醒日程提案不产生提醒记录且回执说明无提醒() = runBlocking {
        server.enqueue(sseProposal(proposalBlock(scheduleJson())))
        val outcome = send().getOrThrow() as AiProposalSendOutcome.ProposalCreated

        val confirmed = proposals.confirmProposal(outcome.proposal.id).getOrThrow()

        assertEquals(1, fakeCalendar.entries.size)
        assertTrue(fakeCalendarReminders.reminders.isEmpty())
        assertTrue(aiScheduler.exactCalls.isEmpty())
        val receipt = confirmed.resultMessage!!
        assertTrue("回执应明确无提醒状态: $receipt", receipt.contains("未设置提醒"))
        assertTrue(receipt.contains(confirmed.createdScheduleIds.single()))
    }

    @Test
    fun 重复确认日程提案幂等不重建日程与提醒() = runBlocking {
        server.enqueue(sseProposal(proposalBlock(scheduleJson(reminder = relativeReminderJson()))))
        val outcome = send().getOrThrow() as AiProposalSendOutcome.ProposalCreated

        val first = proposals.confirmProposal(outcome.proposal.id).getOrThrow()
        val second = proposals.confirmProposal(outcome.proposal.id).getOrThrow()

        assertEquals(first.id, second.id)
        assertEquals(1, fakeCalendar.entries.size)
        assertEquals(1, fakeCalendarReminders.reminders.size)
        assertEquals(first.createdScheduleIds, second.createdScheduleIds)
        assertEquals(first.resultMessage, second.resultMessage)
    }

    @Test
    fun 日程保存失败如实回执且重复确认不重建() = runBlocking {
        server.enqueue(sseProposal(proposalBlock(scheduleJson())))
        val outcome = send().getOrThrow() as AiProposalSendOutcome.ProposalCreated
        fakeCalendar.failNextUpsert = "磁盘空间不足"

        val result = proposals.confirmProposal(outcome.proposal.id)
        assertTrue("部分失败时确认操作本身完成并回执", result.isSuccess)
        val confirmed = result.getOrThrow()
        assertEquals(AiProposalStatus.EXECUTED, confirmed.status)
        assertEquals(0, fakeCalendar.entries.size)
        val message = confirmed.resultMessage!!
        assertTrue("回执应如实说明保存失败: $message", message.contains("失败") && message.contains("磁盘空间不足"))

        // 重复确认幂等：不重建
        val again = proposals.confirmProposal(outcome.proposal.id).getOrThrow()
        assertEquals(0, fakeCalendar.entries.size)
        assertEquals(confirmed.resultMessage, again.resultMessage)
    }

    @Test
    fun 提醒设置失败时日程保留且回执如实说明() = runBlocking {
        server.enqueue(sseProposal(proposalBlock(scheduleJson(reminder = relativeReminderJson()))))
        val outcome = send().getOrThrow() as AiProposalSendOutcome.ProposalCreated
        fakeCalendarReminders.failNextUpsert = "提醒存储不可用"

        val confirmed = proposals.confirmProposal(outcome.proposal.id).getOrThrow()

        // 日程已真实创建（部分成功语义与 T19 一致），提醒失败如实回执
        assertEquals(1, fakeCalendar.entries.size)
        assertEquals(1, confirmed.createdScheduleIds.size)
        assertEquals(0, fakeCalendarReminders.reminders.size)
        val message = confirmed.resultMessage!!
        assertTrue("回执应包含日程记录标识: $message", message.contains(confirmed.createdScheduleIds.single()))
        assertTrue("回执应如实说明提醒失败: $message", message.contains("提醒设置失败") && message.contains("提醒存储不可用"))
    }

    @Test
    fun 精确闹钟权限不足时提醒保留并回执说明未就绪() = runBlocking {
        aiScheduler.exactAllowed = false
        server.enqueue(sseProposal(proposalBlock(scheduleJson(reminder = relativeReminderJson()))))
        val outcome = send().getOrThrow() as AiProposalSendOutcome.ProposalCreated

        val confirmed = proposals.confirmProposal(outcome.proposal.id).getOrThrow()

        // 权限不足：提醒记录保留并标记未就绪，不悄悄降级
        val reminder = fakeCalendarReminders.reminders.values.single()
        assertEquals(CalendarReminderUseCases.NOT_READY_EXACT_ALARM, reminder.notReadyReason)
        assertTrue(confirmed.resultMessage!!.contains("未就绪"))
    }

    @Test
    fun 调度请求被系统拒绝时提醒保留未就绪回执如实() = runBlocking {
        aiScheduler.scheduleResult = false
        server.enqueue(sseProposal(proposalBlock(scheduleJson(reminder = relativeReminderJson()))))
        val outcome = send().getOrThrow() as AiProposalSendOutcome.ProposalCreated

        val confirmed = proposals.confirmProposal(outcome.proposal.id).getOrThrow()

        val reminder = fakeCalendarReminders.reminders.values.single()
        assertEquals(CalendarReminderUseCases.NOT_READY_SCHEDULE_FAILED, reminder.notReadyReason)
        assertTrue(confirmed.resultMessage!!.contains("未就绪"))
    }

    @Test
    fun 混合提案确认后分别经各真实入口创建() = runBlocking {
        server.enqueue(
            sseProposal(
                proposalBlock(taskJson(dueDate = "2026-10-26"), scheduleJson(), noteJson(content = "正文")),
            ),
        )
        val outcome = send().getOrThrow() as AiProposalSendOutcome.ProposalCreated

        val confirmed = proposals.confirmProposal(outcome.proposal.id).getOrThrow()

        assertEquals(1, fakeTasks.tasks.size)
        assertEquals(1, fakeNotes.notes.size)
        assertEquals(1, fakeCalendar.entries.size)
        assertEquals(1, confirmed.createdTaskIds.size)
        assertEquals(1, confirmed.createdNoteIds.size)
        assertEquals(1, confirmed.createdScheduleIds.size)
        assertEquals(3, confirmed.items.size)
    }

    // ---------- 13. 日程提案：取消生成 ----------

    @Test
    fun 取消生成时日程提案不产生任何写入() = runBlocking {
        val trickleBody = buildString {
            repeat(12) { i -> append("data: {\"choices\":[{\"delta\":{\"content\":\"第${i}句\"}}]}\n\n") }
        }
        val respondNormal = AtomicBoolean(false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (respondNormal.get()) {
                    sseProposal(proposalBlock(scheduleJson(reminder = relativeReminderJson())))
                } else {
                    MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "text/event-stream")
                        .body(trickleBody)
                        .throttleBody(20, 100, TimeUnit.MILLISECONDS)
                        .build()
                }
        }

        val deltas = AtomicInteger(0)
        val job = launch(Dispatchers.IO) {
            proposals.sendAndIngest(null, "帮我安排明天的会") { _ -> deltas.incrementAndGet() }
        }
        withTimeout(15_000) {
            while (deltas.get() < 1) delay(20)
        }
        chat.cancelGeneration()
        job.join()
        delay(300)

        assertTrue(proposalStore.load().isEmpty())
        assertNoBusinessWrite()
        // 沿用既有取消语义：用户消息保留，无部分回复
        val session = chat.listSessions().first().single()
        assertEquals(listOf(AiChatRole.USER), session.messages.map { it.role })
    }

    // ---------- 14. 修改与删除提案（T21/#22）：授权门与目标定位 ----------

    private fun authorize(vararg categories: AiContextCategory) {
        chat.authorizeContext(categories.toSet())
    }

    private fun targetJson(kind: String, title: String): String =
        "{\"kind\":\"$kind\",\"title\":\"${escapeJson(title)}\"}"

    /** 模型按协议输出的修改提案 JSON：target 定位线索 + changes 变更集（仅用户要改的字段）。 */
    private fun modifyJson(targetKind: String, targetTitle: String, changesJson: String): String =
        "{\"kind\":\"modify\",\"target\":${targetJson(targetKind, targetTitle)},\"changes\":$changesJson}"

    /** 模型按协议输出的删除提案 JSON：action 为 cancel（仅待办）或 trash（缺省 = trash）。 */
    private fun deleteJson(targetKind: String, targetTitle: String, action: String? = null): String = buildString {
        append("{\"kind\":\"delete\",\"target\":${targetJson(targetKind, targetTitle)}")
        if (action != null) append(",\"action\":\"$action\"")
        append("}")
    }

    private fun presetTask(
        id: String,
        title: String,
        dueEpochDay: Long? = null,
        priority: TaskPriority = TaskPriority.NORMAL,
        note: String = "",
    ): TodoTask = TodoTask(
        id = id,
        title = title,
        note = note,
        priority = priority,
        dueEpochDay = dueEpochDay,
        dueZoneId = ZONE.id,
        createdAt = clock,
        updatedAt = clock,
    )

    private fun presetNote(id: String, title: String, content: String = ""): Note = Note(
        id = id,
        title = title,
        content = content,
        createdAt = clock,
        updatedAt = clock,
    )

    private fun presetEntry(id: String, title: String, start: String, end: String): CalendarEntry = CalendarEntry(
        id = id,
        title = title,
        startMillis = LocalDateTime.parse(start).atZone(ZONE).toInstant().toEpochMilli(),
        endMillis = LocalDateTime.parse(end).atZone(ZONE).toInstant().toEpochMilli(),
        timezoneId = ZONE.id,
        createdAt = clock,
        updatedAt = clock,
    )

    @Test
    fun 未授权时修改提案被拒绝不读取本地数据且留痕说明() = runBlocking {
        assertNull(chat.currentContextGrant())
        fakeTasks.tasks["t1"] = presetTask("t1", "买牛奶", dueEpochDay = LocalDate.parse("2026-10-26").toEpochDay())
        server.enqueue(sseProposal(proposalBlock(modifyJson("task", "买牛奶", "{\"dueDate\":\"2026-10-27\"}"))))

        val result = send("把买牛奶的截止改到明天")

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue("应提示先授权: $message", message.contains("授权") && message.contains("待办"))
        assertEquals("未授权时绝不读取本地数据", 0, fakeTasks.queryCount)
        assertTrue(proposalStore.load().isEmpty())
        // 会话中留有中文说明（先授权再重试），记录未被修改
        val session = chat.listSessions().first().single()
        assertEquals(AiChatRole.ASSISTANT, session.messages.last().role)
        assertTrue(session.messages.last().content.contains("授权"))
        assertEquals(LocalDate.parse("2026-10-26").toEpochDay(), fakeTasks.tasks["t1"]!!.dueEpochDay)
        assertEquals(TaskPriority.NORMAL, fakeTasks.tasks["t1"]!!.priority)
    }

    @Test
    fun 类别未授权时修改便签提案被拒且不读取便签数据() = runBlocking {
        authorize(AiContextCategory.TASKS) // 只授权了待办
        fakeNotes.notes["n1"] = presetNote("n1", "会议要点")
        server.enqueue(sseProposal(proposalBlock(modifyJson("note", "会议要点", "{\"content\":\"新正文\"}"))))

        val result = send("把会议要点便签正文改掉")

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue("应提示便签类别授权: $message", message.contains("便签") && message.contains("授权"))
        assertEquals("未授权便签类别时绝不读取便签", 0, fakeNotes.queryCount)
        assertTrue(proposalStore.load().isEmpty())
    }

    @Test
    fun 修改待办提案单候选直连绑定目标_确认后经真实入口生效且回执含旧值新值() = runBlocking {
        authorize(AiContextCategory.TASKS)
        val oldDay = LocalDate.parse("2026-10-26").toEpochDay()
        val newDay = LocalDate.parse("2026-10-27").toEpochDay()
        fakeTasks.tasks["t1"] = presetTask("t1", "买牛奶", dueEpochDay = oldDay, note = "全脂")
        server.enqueue(
            sseProposal(
                proposalBlock(modifyJson("task", "买牛奶", "{\"dueDate\":\"2026-10-27\",\"priority\":\"HIGH\"}")),
            ),
        )

        val outcome = send("把买牛奶的截止改到明天，优先级调高").getOrThrow() as AiProposalSendOutcome.ProposalCreated
        val item = outcome.proposal.items.single()
        assertEquals(AiProposalOp.MODIFY, item.op)
        assertEquals(AiProposalKind.TASK, item.kind)
        assertEquals("买牛奶", item.targetTitle)
        // 单候选直连：目标自动绑定且快照保留旧值（用户可核对）
        assertEquals("t1", item.targetRecordId)
        assertEquals(1, item.candidates.size)
        val boundRecord = requireNotNull(item.targetRecord) { "单候选直连必须绑定目标快照" }
        assertEquals(oldDay, boundRecord.dueEpochDay)
        assertEquals("NORMAL", boundRecord.priority)
        assertTrue("匹配应读取已授权类别的本地数据", fakeTasks.queryCount >= 1)
        // 确认前绝不写入：目标记录仍是旧值
        assertEquals(oldDay, fakeTasks.tasks["t1"]!!.dueEpochDay)
        // 字段预览清晰展示 旧值 → 新值
        val preview = proposalChangePreviewLines(item, ZONE)
        assertTrue("预览应含截止旧新值: $preview", preview.any { it.contains("2026-10-26") && it.contains("2026-10-27") })
        assertTrue("预览应含优先级旧新值: $preview", preview.any { it.contains("普通") && it.contains("高") })

        val confirmed = proposals.confirmProposal(outcome.proposal.id).getOrThrow()
        assertEquals(AiProposalStatus.EXECUTED, confirmed.status)
        // 经 TaskUseCases.update 真实入口：同一条记录被修改，未新建
        val updated = fakeTasks.tasks.values.single()
        assertEquals("t1", updated.id)
        assertEquals(newDay, updated.dueEpochDay)
        assertEquals(TaskPriority.HIGH, updated.priority)
        assertEquals("未提及的字段保持原值", "全脂", updated.note)
        assertTrue("修改不产生新建记录", confirmed.createdTaskIds.isEmpty())
        val receipt = confirmed.resultMessage!!
        assertTrue("回执应含旧→新截止: $receipt", receipt.contains("2026-10-26") && receipt.contains("2026-10-27"))
        assertTrue("回执应含记录标识: $receipt", receipt.contains("t1"))
    }

    @Test
    fun 修改提案多候选时先点选目标再确认_点选哪条就改哪条() = runBlocking {
        authorize(AiContextCategory.TASKS)
        fakeTasks.tasks["t1"] = presetTask("t1", "买牛奶")
        fakeTasks.tasks["t2"] = presetTask("t2", "买牛奶和鸡蛋")
        server.enqueue(sseProposal(proposalBlock(modifyJson("task", "买牛奶", "{\"priority\":\"HIGH\"}"))))

        val outcome = send().getOrThrow() as AiProposalSendOutcome.ProposalCreated
        val item = outcome.proposal.items.single()
        assertNull("多候选时绝不自动猜测目标", item.targetRecordId)
        assertEquals(2, item.candidates.size)

        // 未点选就确认 → 拒绝，绝不静默写入
        val early = proposals.confirmProposal(outcome.proposal.id)
        assertTrue("未选择目标不可确认", early.isFailure)
        assertTrue("应提示先选择目标: ${early.exceptionOrNull()!!.message}", early.exceptionOrNull()!!.message!!.contains("目标"))
        assertTrue("未确认绝不写入", fakeTasks.tasks.values.none { it.priority == TaskPriority.HIGH })

        // 用户点选第二条：点选哪条就是哪条
        val selected = proposals.selectProposalTarget(outcome.proposal.id, item.id, "t2").getOrThrow()
        assertEquals("t2", selected.items.single().targetRecordId)
        assertEquals("买牛奶和鸡蛋", selected.items.single().targetRecord?.title)

        val confirmed = proposals.confirmProposal(outcome.proposal.id).getOrThrow()
        assertEquals(TaskPriority.HIGH, fakeTasks.tasks["t2"]!!.priority)
        assertEquals("未被点选的记录保持不变", TaskPriority.NORMAL, fakeTasks.tasks["t1"]!!.priority)
        assertTrue(confirmed.resultMessage!!.contains("t2"))
    }

    @Test
    fun 点选目标限定在候选之内_不信任候选外的记录标识() = runBlocking {
        authorize(AiContextCategory.TASKS)
        fakeTasks.tasks["t1"] = presetTask("t1", "买牛奶")
        fakeTasks.tasks["t2"] = presetTask("t2", "买牛奶和鸡蛋")
        server.enqueue(sseProposal(proposalBlock(modifyJson("task", "买牛奶", "{\"priority\":\"HIGH\"}"))))
        val outcome = send().getOrThrow() as AiProposalSendOutcome.ProposalCreated
        val item = outcome.proposal.items.single()

        val result = proposals.selectProposalTarget(outcome.proposal.id, item.id, "t9-不存在")
        assertTrue("候选外的记录不可选", result.isFailure)
        assertTrue(proposals.confirmProposal(outcome.proposal.id).isFailure)
        assertTrue(fakeTasks.tasks.values.none { it.priority == TaskPriority.HIGH })
    }

    @Test
    fun 修改提案匹配不到目标时中文追问且不落任何提案() = runBlocking {
        authorize(AiContextCategory.TASKS)
        fakeTasks.tasks["t1"] = presetTask("t1", "打扫房间")
        server.enqueue(sseProposal(proposalBlock(modifyJson("task", "不存在的任务", "{\"priority\":\"HIGH\"}"))))

        val result = send()
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue("应中文提示未找到并追问: $message", message.contains("没有找到") && message.contains("不存在的任务"))
        assertTrue("匹配失败不落提案", proposalStore.load().isEmpty())
        // 会话中留有追问说明，不猜不编
        val session = chat.listSessions().first().single()
        assertTrue(session.messages.last().content.contains("没有找到"))
        assertEquals(TaskPriority.NORMAL, fakeTasks.tasks["t1"]!!.priority)
    }

    @Test
    fun 候选超过上限时追问更具体的名称且不落提案() = runBlocking {
        authorize(AiContextCategory.TASKS)
        repeat(6) { i -> fakeTasks.tasks["t$i"] = presetTask("t$i", "周会行动项$i") }
        server.enqueue(sseProposal(proposalBlock(modifyJson("task", "周会行动项", "{\"priority\":\"HIGH\"}"))))

        val result = send()
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue("应提示候选过多并追问: $message", message.contains("6") && message.contains("周会行动项"))
        assertTrue(proposalStore.load().isEmpty())
        assertTrue(fakeTasks.tasks.values.none { it.priority == TaskPriority.HIGH })
    }

    @Test
    fun 删除待办提案确认后进入回收站且回执说明可恢复() = runBlocking {
        authorize(AiContextCategory.TASKS)
        fakeTasks.tasks["t1"] = presetTask("t1", "过期计划")
        server.enqueue(sseProposal(proposalBlock(deleteJson("task", "过期计划", action = "trash"))))

        val outcome = send("删掉过期计划这个待办").getOrThrow() as AiProposalSendOutcome.ProposalCreated
        val item = outcome.proposal.items.single()
        assertEquals(AiProposalOp.DELETE, item.op)
        assertEquals(AiProposalDeleteAction.TRASH, item.deleteAction)
        assertEquals("单候选直连", "t1", item.targetRecordId)
        assertNull("确认前未删除", fakeTasks.tasks["t1"]!!.trashedAt)

        val confirmed = proposals.confirmProposal(outcome.proposal.id).getOrThrow()
        assertNotNull("删除走回收站语义", fakeTasks.tasks["t1"]!!.trashedAt)
        val receipt = confirmed.resultMessage!!
        assertTrue("回执应说明移入回收站: $receipt", receipt.contains("回收站"))
        assertTrue(receipt.contains("t1"))
    }

    @Test
    fun 取消待办提案明确标注取消语义_确认后保留记录且不进回收站() = runBlocking {
        authorize(AiContextCategory.TASKS)
        fakeTasks.tasks["t1"] = presetTask("t1", "不再做的计划")
        server.enqueue(sseProposal(proposalBlock(deleteJson("task", "不再做的计划", action = "cancel"))))

        val outcome = send("取消不再做的计划").getOrThrow() as AiProposalSendOutcome.ProposalCreated
        val item = outcome.proposal.items.single()
        assertEquals(AiProposalDeleteAction.CANCEL, item.deleteAction)
        // GLOSSARY：取消待办 ≠ 删除待办 —— 动作文案必须标注"取消"而非删除
        val actionText = proposalDeleteActionText(item)
        assertTrue("动作文案应标注取消: $actionText", actionText.contains("取消"))
        assertFalse("取消不是删除", actionText.contains("回收站"))

        val confirmed = proposals.confirmProposal(outcome.proposal.id).getOrThrow()
        val task = fakeTasks.tasks["t1"]!!
        assertEquals(TaskStatus.CANCELLED, task.status)
        assertNull("取消保留业务记录，不进回收站", task.trashedAt)
        assertNotNull(task.cancelledAt)
        val receipt = confirmed.resultMessage!!
        assertTrue(receipt.contains("已取消"))
        assertFalse("取消回执不得声称移入回收站", receipt.contains("回收站"))
    }

    @Test
    fun 删除便签与日程提案各自走回收站语义() = runBlocking {
        authorize(AiContextCategory.NOTES, AiContextCategory.SCHEDULE)
        fakeNotes.notes["n1"] = presetNote("n1", "旧便签")
        fakeCalendar.entries["e1"] = presetEntry("e1", "旧安排", "2026-10-26T10:00", "2026-10-26T11:00")
        server.enqueue(
            sseProposal(
                proposalBlock(deleteJson("note", "旧便签"), deleteJson("schedule", "旧安排")),
            ),
        )

        val outcome = send().getOrThrow() as AiProposalSendOutcome.ProposalCreated
        assertEquals(2, outcome.proposal.items.size)
        val confirmed = proposals.confirmProposal(outcome.proposal.id).getOrThrow()
        assertNotNull(fakeNotes.notes["n1"]!!.trashedAt)
        assertNotNull(fakeCalendar.entries["e1"]!!.trashedAt)
        assertTrue(confirmed.resultMessage!!.contains("回收站"))
    }

    @Test
    fun 修改提案重复确认幂等不重复执行() = runBlocking {
        authorize(AiContextCategory.TASKS)
        fakeTasks.tasks["t1"] = presetTask("t1", "买牛奶")
        server.enqueue(sseProposal(proposalBlock(modifyJson("task", "买牛奶", "{\"priority\":\"HIGH\"}"))))
        val outcome = send().getOrThrow() as AiProposalSendOutcome.ProposalCreated

        val first = proposals.confirmProposal(outcome.proposal.id).getOrThrow()
        val updatedAtAfterFirst = fakeTasks.tasks["t1"]!!.updatedAt
        val second = proposals.confirmProposal(outcome.proposal.id).getOrThrow()

        assertEquals(first.resultMessage, second.resultMessage)
        assertEquals("重复确认不重放修改", updatedAtAfterFirst, fakeTasks.tasks["t1"]!!.updatedAt)
        assertEquals(1, fakeTasks.tasks.size)
    }

    @Test
    fun 确认前目标记录已被手动修改时拒绝覆盖并如实回执() = runBlocking {
        authorize(AiContextCategory.TASKS)
        fakeTasks.tasks["t1"] = presetTask("t1", "买牛奶")
        server.enqueue(sseProposal(proposalBlock(modifyJson("task", "买牛奶", "{\"priority\":\"HIGH\"}"))))
        val outcome = send().getOrThrow() as AiProposalSendOutcome.ProposalCreated

        // 确认前用户手动改了这条记录（updatedAt 变化）
        fakeTasks.tasks["t1"] = fakeTasks.tasks["t1"]!!.copy(title = "买牛奶（两箱）", updatedAt = clock + 999)

        val result = proposals.confirmProposal(outcome.proposal.id)
        assertTrue("提案执行完成（含失败项）", result.isSuccess)
        val confirmed = result.getOrThrow()
        val task = fakeTasks.tasks["t1"]!!
        assertEquals("拒绝覆盖用户手动改动", "买牛奶（两箱）", task.title)
        assertEquals("未应用提案变更", TaskPriority.NORMAL, task.priority)
        val receipt = confirmed.resultMessage!!
        assertTrue("回执应如实说明未执行原因: $receipt", receipt.contains("已被修改"))
    }

    @Test
    fun 批量修改多个目标一次确认全部生效() = runBlocking {
        authorize(AiContextCategory.TASKS)
        fakeTasks.tasks["t1"] = presetTask("t1", "买牛奶", dueEpochDay = 1L)
        fakeTasks.tasks["t2"] = presetTask("t2", "买鸡蛋")
        server.enqueue(
            sseProposal(
                proposalBlock(
                    modifyJson("task", "买牛奶", "{\"dueDate\":\"2026-10-27\"}"),
                    modifyJson("task", "买鸡蛋", "{\"priority\":\"LOW\"}"),
                ),
            ),
        )
        val outcome = send().getOrThrow() as AiProposalSendOutcome.ProposalCreated

        val confirmed = proposals.confirmProposal(outcome.proposal.id).getOrThrow()

        assertEquals(LocalDate.parse("2026-10-27").toEpochDay(), fakeTasks.tasks["t1"]!!.dueEpochDay)
        assertEquals(TaskPriority.LOW, fakeTasks.tasks["t2"]!!.priority)
        assertTrue(confirmed.resultMessage!!.contains("已执行 2 项"))
    }

    @Test
    fun 修改便签与日程提案确认经各自真实入口生效() = runBlocking {
        authorize(AiContextCategory.NOTES, AiContextCategory.SCHEDULE)
        fakeNotes.notes["n1"] = presetNote("n1", "会议要点", content = "旧正文")
        fakeCalendar.entries["e1"] = presetEntry("e1", "项目周会", "2026-10-26T14:00", "2026-10-26T15:30")
        server.enqueue(
            sseProposal(
                proposalBlock(
                    modifyJson("note", "会议要点", "{\"content\":\"新正文\",\"tags\":[\"工作\"]}"),
                    modifyJson("schedule", "项目周会", "{\"start\":\"2026-10-27T09:00\",\"end\":\"2026-10-27T10:00\"}"),
                ),
            ),
        )
        val outcome = send().getOrThrow() as AiProposalSendOutcome.ProposalCreated

        val confirmed = proposals.confirmProposal(outcome.proposal.id).getOrThrow()

        // 便签经 NoteUseCases.update：标题保持、正文与标签更新
        val note = fakeNotes.notes["n1"]!!
        assertEquals("新正文", note.content)
        assertEquals(listOf("工作"), note.tags)
        assertEquals("会议要点", note.title)
        // 日程经 CalendarUseCases.update：时间更新、时区锚定保持
        val entry = fakeCalendar.entries["e1"]!!
        assertEquals(LocalDateTime.parse("2026-10-27T09:00").atZone(ZONE).toInstant().toEpochMilli(), entry.startMillis)
        assertEquals(ZONE.id, entry.timezoneId)
        assertTrue(confirmed.resultMessage!!.contains("已执行 2 项"))
    }

    @Test
    fun 修改提案没有给出任何变更时整批拒绝() = runBlocking {
        enqueueInvalidTwice(proposalBlock(modifyJson("task", "买牛奶", "{}")))
        assertParseRejected("改一下", "变更")
    }

    @Test
    fun 修改待办携带便签专属字段时整批拒绝() = runBlocking {
        enqueueInvalidTwice(proposalBlock(modifyJson("task", "买牛奶", "{\"content\":\"不该有\"}")))
        assertParseRejected("改一下", "待办")
    }

    @Test
    fun 修改日程只给开始不给结束整批拒绝() = runBlocking {
        enqueueInvalidTwice(proposalBlock(modifyJson("schedule", "项目周会", "{\"start\":\"2026-10-27T09:00\"}")))
        assertParseRejected("改时间", "结束")
    }

    @Test
    fun 删除待办缺少动作字段整批拒绝() = runBlocking {
        enqueueInvalidTwice(proposalBlock(deleteJson("task", "买牛奶")))
        assertParseRejected("处理掉买牛奶", "取消", "删除")
    }

    @Test
    fun 便签删除不允许取消动作整批拒绝() = runBlocking {
        enqueueInvalidTwice(proposalBlock(deleteJson("note", "旧便签", action = "cancel")))
        assertParseRejected("取消便签", "便签")
    }

    @Test
    fun 创建条目携带目标或变更字段整批拒绝() = runBlocking {
        val bad = "{\"kind\":\"task\",\"title\":\"混淆\",\"target\":{\"kind\":\"task\",\"title\":\"x\"}}"
        enqueueInvalidTwice(proposalBlock(bad))
        assertParseRejected("建个待办", "target")
    }

    @Test
    fun 取消生成时修改提案不产生任何写入() = runBlocking {
        authorize(AiContextCategory.TASKS)
        fakeTasks.tasks["t1"] = presetTask("t1", "买牛奶")
        val trickleBody = buildString {
            repeat(12) { i -> append("data: {\"choices\":[{\"delta\":{\"content\":\"第${i}句\"}}]}\n\n") }
        }
        val respondNormal = AtomicBoolean(false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (respondNormal.get()) {
                    sseProposal(proposalBlock(modifyJson("task", "买牛奶", "{\"priority\":\"HIGH\"}")))
                } else {
                    MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "text/event-stream")
                        .body(trickleBody)
                        .throttleBody(20, 100, TimeUnit.MILLISECONDS)
                        .build()
                }
        }

        val deltas = AtomicInteger(0)
        val job = launch(Dispatchers.IO) {
            proposals.sendAndIngest(null, "把买牛奶优先级调高") { _ -> deltas.incrementAndGet() }
        }
        withTimeout(15_000) {
            while (deltas.get() < 1) delay(20)
        }
        chat.cancelGeneration()
        job.join()
        delay(300)

        assertTrue(proposalStore.load().isEmpty())
        assertEquals("取消后无任何写入", TaskPriority.NORMAL, fakeTasks.tasks["t1"]!!.priority)
        val session = chat.listSessions().first().single()
        assertEquals(listOf(AiChatRole.USER), session.messages.map { it.role })
    }

    // ---------- 替身 ----------

    private fun assertNullBut(actual: Any?, expected: Any?) = assertEquals(expected, actual)

    private class FakeTasksRepository : TasksRepository {
        val tasks = LinkedHashMap<String, TodoTask>()
        var failNextUpsert: String? = null
        // T21/#22：记录 observeTasks 查询次数，供「未授权不读取本地数据」断言
        var queryCount: Int = 0

        override fun observeTasks(): Flow<List<TodoTask>> {
            queryCount++
            return flowOf(tasks.values.toList())
        }
        override suspend fun getTask(id: String): TodoTask? = tasks[id]
        override suspend fun upsertTask(task: TodoTask) {
            failNextUpsert?.let { msg ->
                failNextUpsert = null
                throw IllegalStateException(msg)
            }
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

    private class FakeNotesRepository : NotesRepository {
        val notes = LinkedHashMap<String, Note>()
        // T21/#22：记录 observeNotes 查询次数，供「未授权不读取本地数据」断言
        var queryCount: Int = 0

        override fun observeNotes(): Flow<List<Note>> {
            queryCount++
            return flowOf(notes.values.toList())
        }
        override suspend fun getNote(id: String): Note? = notes[id]
        override suspend fun upsertNote(note: Note) {
            notes[note.id] = note
        }

        override suspend fun trashNote(id: String, trashedAtMillis: Long) {
            notes[id]?.let { notes[id] = it.copy(trashedAt = trashedAtMillis) }
        }

        override fun observeTrashedNotes(): Flow<List<Note>> =
            flowOf(notes.values.filter { it.trashedAt != null })

        override suspend fun listTrashedNotes(): List<Note> = notes.values.filter { it.trashedAt != null }
        override suspend fun purgeNote(id: String) {
            notes.remove(id)
        }
    }

    /** 日程仓库替身：记录创建的日程，可注入保存失败。 */
    private class FakeCalendarRepository : CalendarRepository {
        val entries = LinkedHashMap<String, CalendarEntry>()
        var failNextUpsert: String? = null
        // T21/#22：记录 observeEntries 查询次数，供「未授权不读取本地数据」断言
        var queryCount: Int = 0

        override fun observeEntries(): Flow<List<CalendarEntry>> {
            queryCount++
            return flowOf(entries.values.filter { it.trashedAt == null })
        }

        override suspend fun getEntry(id: String): CalendarEntry? = entries[id]
        override suspend fun getEntriesLinkedToTask(taskId: String): List<CalendarEntry> =
            entries.values.filter { it.taskId == taskId }

        override suspend fun upsertEntry(entry: CalendarEntry) {
            failNextUpsert?.let { msg ->
                failNextUpsert = null
                throw IllegalStateException(msg)
            }
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

    /** 日程提醒仓库替身：记录设置的提醒，可注入保存失败。 */
    private class FakeCalendarRemindersRepository : CalendarRemindersRepository {
        val reminders = LinkedHashMap<String, Reminder>()
        var failNextUpsert: String? = null

        override fun observeByEntry(entryId: String): Flow<List<Reminder>> =
            flowOf(reminders.values.filter { it.taskId == entryId })

        override suspend fun listByEntry(entryId: String): List<Reminder> =
            reminders.values.filter { it.taskId == entryId }

        override suspend fun listAll(): List<Reminder> = reminders.values.toList()

        override suspend fun get(id: String): Reminder? = reminders[id]
        override suspend fun upsert(reminder: Reminder) {
            failNextUpsert?.let { msg ->
                failNextUpsert = null
                throw IllegalStateException(msg)
            }
            reminders[reminder.id] = reminder
        }

        override suspend fun delete(id: String) {
            reminders.remove(id)
        }

        override suspend fun deleteByEntry(entryId: String) {
            reminders.keys.removeAll { key -> reminders[key]?.taskId == entryId }
        }
    }

    /** 记录调用的调度器替身（模式同 CalendarReminderUseCasesTest），可切换精确闹钟能力与调度结果。 */
    private class AiFakeAlarmScheduler : AlarmScheduler {
        var exactAllowed: Boolean = true
        var scheduleResult: Boolean = true

        val exactCalls = mutableListOf<Pair<String, Long>>()
        val inexactCalls = mutableListOf<Pair<String, Long>>()
        val cancelled = mutableListOf<String>()

        override fun canScheduleExact(): Boolean = exactAllowed

        override suspend fun scheduleExact(reminderId: String, triggerAtMillis: Long, taskId: String, title: String): Boolean {
            exactCalls += reminderId to triggerAtMillis
            return scheduleResult
        }

        override suspend fun scheduleInexact(reminderId: String, triggerAtMillis: Long, taskId: String, title: String): Boolean {
            inexactCalls += reminderId to triggerAtMillis
            return scheduleResult
        }

        override suspend fun cancel(reminderId: String) {
            cancelled += reminderId
        }
    }
}
