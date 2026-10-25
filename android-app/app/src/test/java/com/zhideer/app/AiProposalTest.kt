package com.zhideer.app

import com.zhideer.app.data.ai.AiProposalStore
import com.zhideer.app.data.ai.AiSessionStore
import com.zhideer.app.data.ai.DeepSeekChatClient
import com.zhideer.app.data.ai.InMemoryAiConfigStore
import com.zhideer.app.domain.model.AiChatRole
import com.zhideer.app.domain.model.AiConfig
import com.zhideer.app.domain.model.AiProposalKind
import com.zhideer.app.domain.model.AiProposalSendOutcome
import com.zhideer.app.domain.model.AiProposalStatus
import com.zhideer.app.domain.model.Note
import com.zhideer.app.domain.model.TodoTask
import com.zhideer.app.domain.repository.NotesRepository
import com.zhideer.app.domain.repository.TasksRepository
import com.zhideer.app.domain.usecase.AiChatUseCases
import com.zhideer.app.domain.usecase.AiContextAuthorizer
import com.zhideer.app.domain.usecase.AiContextProvider
import com.zhideer.app.domain.usecase.AiContextSnapshot
import com.zhideer.app.domain.usecase.AiProposalUseCases
import com.zhideer.app.domain.usecase.NoteUseCases
import com.zhideer.app.domain.usecase.TaskUseCases
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
    private lateinit var storeDir: File
    private val idSeq = AtomicInteger(0)
    private val taskSeq = AtomicInteger(0)
    private val noteSeq = AtomicInteger(0)
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

    // ---------- 替身 ----------

    private fun assertNullBut(actual: Any?, expected: Any?) = assertEquals(expected, actual)

    private class FakeTasksRepository : TasksRepository {
        val tasks = LinkedHashMap<String, TodoTask>()
        var failNextUpsert: String? = null

        override fun observeTasks(): Flow<List<TodoTask>> = flowOf(tasks.values.toList())
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

        override fun observeNotes(): Flow<List<Note>> = flowOf(notes.values.toList())
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
}
