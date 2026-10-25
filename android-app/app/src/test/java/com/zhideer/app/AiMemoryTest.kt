package com.zhideer.app

import com.zhideer.app.data.ai.AiProposalStore
import com.zhideer.app.data.ai.AiSessionStore
import com.zhideer.app.data.ai.DeepSeekChatClient
import com.zhideer.app.data.ai.InMemoryAiConfigStore
import com.zhideer.app.data.ai.RepositoryAiContextProvider
import com.zhideer.app.domain.model.AiProposalKind
import com.zhideer.app.domain.model.AiProposalSendOutcome
import com.zhideer.app.domain.model.AiProposalStatus
import com.zhideer.app.domain.model.AiConfig
import com.zhideer.app.domain.model.CalendarEntry
import com.zhideer.app.domain.model.Memory
import com.zhideer.app.domain.model.MemoryRefType
import com.zhideer.app.domain.model.MemorySource
import com.zhideer.app.domain.model.MemoryType
import com.zhideer.app.domain.model.Note
import com.zhideer.app.domain.model.Reminder
import com.zhideer.app.domain.model.TodoTask
import com.zhideer.app.domain.repository.CalendarRemindersRepository
import com.zhideer.app.domain.repository.CalendarRepository
import com.zhideer.app.domain.repository.MemoriesRepository
import com.zhideer.app.domain.repository.NotesRepository
import com.zhideer.app.domain.repository.TasksRepository
import com.zhideer.app.domain.usecase.AiChatUseCases
import com.zhideer.app.domain.usecase.AiContextAuthorizer
import com.zhideer.app.domain.usecase.AiContextCategory
import com.zhideer.app.domain.usecase.AiContextProvider
import com.zhideer.app.domain.usecase.AiContextSnapshot
import com.zhideer.app.domain.usecase.AiProposalTargetMatcher
import com.zhideer.app.domain.usecase.AiProposalUseCases
import com.zhideer.app.domain.usecase.CalendarReminderUseCases
import com.zhideer.app.domain.usecase.CalendarUseCases
import com.zhideer.app.domain.usecase.MemoryInput
import com.zhideer.app.domain.usecase.MemoryUseCases
import com.zhideer.app.domain.usecase.NoteUseCases
import com.zhideer.app.domain.usecase.TaskUseCases
import com.zhideer.app.platform.AlarmScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/**
 * T28/#29 验收测试：在聊天中记住和检索偏好（长期记忆）。
 * - 用户明确要求记住 → AI 起草可编辑记忆提案（GLOSSARY：操作提案）：确认前绝不写入记忆表；
 * - 确认后经 [MemoryUseCases] 真实入口保存：来源 CHAT（聊天确认）、写入即已确认、原文不篡改、重复确认幂等；
 * - 记忆写入提案只写不读：不需要读取授权（与新增待办/便签提案一致）；
 * - 聊天检索：读取需记忆授权（[AiContextCategory.MEMORY]）——未授权时请求体绝不含记忆内容；
 *   授权后仅注入活动记忆（已确认、未停用、未过期）原文，上限 10 条、最新优先；
 * - 聊天总结不自动升级为长期记忆（GLOSSARY：聊天历史本身不等于长期记忆）。
 * 全部走 MockWebServer 固定响应 + 记忆替身仓库 + 真实用例入口；真实模型表现待用户配置 Key 后由 Root 观察。
 */
class AiMemoryTest {

    private companion object {
        const val KEY = "sk-test-1234567890abcdef"
        const val MODEL = "deepseek-chat"
        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
        val NOW: Long = LocalDateTime.parse("2026-10-25T09:00").atZone(ZONE).toInstant().toEpochMilli()
    }

    private lateinit var server: MockWebServer
    private lateinit var configStore: InMemoryAiConfigStore
    private lateinit var sessionStore: AiSessionStore
    private lateinit var proposalStore: AiProposalStore
    private lateinit var client: DeepSeekChatClient
    private lateinit var chat: AiChatUseCases
    private lateinit var proposals: AiProposalUseCases
    private lateinit var memories: MemoryUseCases
    private lateinit var fakeMemories: FakeMemoriesRepository
    private lateinit var authorizer: AiContextAuthorizer
    private lateinit var provider: RepositoryAiContextProvider
    private lateinit var storeDir: File
    private val idSeq = AtomicInteger(0)
    private val memorySeq = AtomicInteger(0)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        configStore = InMemoryAiConfigStore()
        configStore.save(AiConfig(apiKey = KEY, model = MODEL, baseUrl = server.url("/v1").toString()))
        storeDir = File(System.getProperty("java.io.tmpdir"), "t28-test-${System.nanoTime()}")
        check(storeDir.mkdirs() || storeDir.isDirectory) { "无法创建测试目录" }
        sessionStore = AiSessionStore(File(storeDir, "ai_sessions.preferences_pb"))
        proposalStore = AiProposalStore(File(storeDir, "ai_proposals.preferences_pb"))
        client = DeepSeekChatClient(connectTimeoutMs = 2_000, readTimeoutMs = 800)
        authorizer = AiContextAuthorizer()
        fakeMemories = FakeMemoriesRepository()
        memories = MemoryUseCases(
            repository = fakeMemories,
            idGen = { "memory-${memorySeq.incrementAndGet()}" },
            now = { NOW },
        )
        // 生产快照提供者（真实实现）：只读授权类别，记忆段同样经真实 MemoryUseCases 仓库入口
        provider = RepositoryAiContextProvider(
            tasksRepository = NoopTasksRepository(),
            calendarRepository = NoopCalendarRepository(),
            notesRepository = NoopNotesRepository(),
            zoneId = ZONE,
            now = { NOW },
            memoriesRepository = fakeMemories,
        )
        chat = AiChatUseCases(
            configStore = configStore,
            sessionStore = sessionStore,
            client = client,
            idGen = { "session-${idSeq.incrementAndGet()}" },
            now = { NOW },
            contextAuthorizer = authorizer,
            contextProvider = AiContextProvider { provider.loadSnapshot(it) },
            zoneId = ZONE,
        )
        proposals = AiProposalUseCases(
            chat = chat,
            store = proposalStore,
            taskUseCases = TaskUseCases(
                repository = NoopTasksRepository(),
                idGen = { "task-${idSeq.incrementAndGet()}" },
                now = { NOW },
                zoneId = ZONE.id,
            ),
            noteUseCases = NoteUseCases(
                repository = NoopNotesRepository(),
                idGen = { "note-${idSeq.incrementAndGet()}" },
                now = { NOW },
            ),
            calendarUseCases = CalendarUseCases(
                repository = NoopCalendarRepository(),
                idGen = { "entry-${idSeq.incrementAndGet()}" },
                now = { NOW },
                zoneId = ZONE.id,
            ),
            calendarReminderUseCases = CalendarReminderUseCases(
                repository = NoopCalendarRemindersRepository(),
                calendarRepository = NoopCalendarRepository(),
                scheduler = NoopAlarmScheduler(),
                idGen = { "calrem-${idSeq.incrementAndGet()}" },
                now = { NOW },
            ),
            // T28/#29 纯新增：记忆提案确认后经 MemoryUseCases 真实入口保存（来源 CHAT）
            memoryUseCases = memories,
            targetMatcher = AiProposalTargetMatcher(
                tasksRepository = NoopTasksRepository(),
                notesRepository = NoopNotesRepository(),
                calendarRepository = NoopCalendarRepository(),
            ),
            idGen = { "proposal-${idSeq.incrementAndGet()}" },
            now = { NOW },
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

    private fun sse(fullText: String): MockResponse {
        val body = buildString {
            fullText.chunked(8).forEach { delta ->
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

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    /** 模型按协议输出的记忆提案 JSON：content 为用户原话（不改写），memoryType 为偏好或事实。 */
    private fun memoryJson(content: String, memoryType: String? = "preference"): String = buildString {
        append("{\"kind\":\"memory\",\"content\":\"${escapeJson(content)}\"")
        if (memoryType != null) append(",\"memoryType\":\"$memoryType\"")
        append("}")
    }

    private fun proposalReply(vararg items: String): String =
        "好的，我记下了，请确认。\n```json\n{\"proposals\":[${items.joinToString(",")}]}\n```"

    private suspend fun send(text: String = "记住：我喜欢在清晨跑步") =
        proposals.sendAndIngest(null, text) {}

    private fun parseMessages(requestBody: String) =
        Json.parseToJsonElement(requestBody).jsonObject["messages"]!!.jsonArray

    // ---------- 1. 记忆提案：确认前绝不写入 ----------

    @Test
    fun 聊天要求记住生成待确认记忆提案且确认前绝不写入记忆表() = runBlocking {
        server.enqueue(sse(proposalReply(memoryJson("我喜欢在清晨跑步"))))

        val outcome = send().getOrThrow()

        assertTrue(outcome is AiProposalSendOutcome.ProposalCreated)
        val proposal = (outcome as AiProposalSendOutcome.ProposalCreated).proposal
        assertEquals(AiProposalStatus.PENDING, proposal.status)
        val item = proposal.items.single()
        assertEquals(AiProposalKind.MEMORY, item.kind)
        assertEquals("我喜欢在清晨跑步", item.content)
        // 验收核心：操作提案确认前记忆表绝无写入（GLOSSARY：操作提案确认后才执行）
        assertTrue("确认前记忆表必须为空", fakeMemories.memories.isEmpty())
        // 会话助手消息是人类可读摘要，不含原始 JSON
        val session = outcome.session
        val assistant = session.messages.last()
        assertFalse("摘要不得包含原始 JSON", assistant.content.contains("```"))
        assertTrue("摘要应说明记忆条目", assistant.content.contains("记忆"))
    }

    @Test
    fun 确认后经真实入口保存来源聊天确认原文不篡改且重复确认幂等() = runBlocking {
        server.enqueue(sse(proposalReply(memoryJson("我对芒果过敏，推荐食谱时请避开", memoryType = "fact"))))

        val created = (send("记住我对芒果过敏").getOrThrow() as AiProposalSendOutcome.ProposalCreated).proposal
        assertTrue(fakeMemories.memories.isEmpty())

        val confirmed = proposals.confirmProposal(created.id).getOrThrow()

        // 经 MemoryUseCases 真实入口保存：原文不篡改、类型为事实、来源为聊天确认、写入即已确认
        assertEquals(1, fakeMemories.memories.size)
        val memory = fakeMemories.memories.values.single()
        assertEquals("我对芒果过敏，推荐食谱时请避开", memory.content)
        assertEquals(MemoryType.FACT, memory.type)
        assertEquals(MemorySource.CHAT, memory.source)
        assertTrue("聊天确认保存即已确认", memory.confirmed)
        assertTrue(memory.enabled)
        // 回执含真实记录标识与中文说明；提案记录创建的记忆 id
        val receipt = confirmed.resultMessage!!
        assertTrue("回执应含记录标识: $receipt", receipt.contains(memory.id))
        assertTrue("回执应为中文已创建说明: $receipt", receipt.contains("已创建"))
        assertEquals(listOf(memory.id), confirmed.createdMemoryIds)
        // 幂等：重复确认不重复写入（GLOSSARY：操作提案再次确认幂等）
        val again = proposals.confirmProposal(created.id).getOrThrow()
        assertEquals(1, fakeMemories.memories.size)
        assertEquals(confirmed.resultMessage, again.resultMessage)
    }

    @Test
    fun 记忆写入提案不需要读取授权() = runBlocking {
        // 未授权任何类别（grant == null）：记忆提案是只写不读的创建提案，必须可正常起草
        assertEquals(null, authorizer.currentGrant())
        server.enqueue(sse(proposalReply(memoryJson("偏好通过邮件接收周报"))))

        val outcome = send().getOrThrow()

        assertTrue(outcome is AiProposalSendOutcome.ProposalCreated)
        assertEquals(AiProposalKind.MEMORY, (outcome as AiProposalSendOutcome.ProposalCreated).proposal.items.single().kind)
        assertTrue("未授权时记忆提案仍应落库待确认", proposalStore.load().size == 1)
        // 只写不读：未授权绝不触发任何记忆读取
        assertEquals("未授权时不得读取记忆", 0, fakeMemories.observeCalls)
    }

    @Test
    fun 普通聊天回复不自动写入长期记忆() = runBlocking {
        // 无提案 JSON 块的普通回复：聊天总结/闲聊绝不自动升级为长期记忆（GLOSSARY）
        server.enqueue(sse("好的，今天我们一起安排了三件事，稍后我会提醒你。"))

        val outcome = send("总结一下今天的安排").getOrThrow()

        assertTrue(outcome is AiProposalSendOutcome.PlainReply)
        assertTrue("普通回复不得写入记忆表", fakeMemories.memories.isEmpty())
        assertTrue(proposalStore.load().isEmpty())
    }

    // ---------- 2. 聊天检索：记忆授权门 ----------

    @Test
    fun 未授权记忆类别时请求体不含记忆内容() = runBlocking {
        // 先经聊天确认保存一条真实记忆
        server.enqueue(sse(proposalReply(memoryJson("我对芒果过敏，推荐食谱时请避开", memoryType = "fact"))))
        val created = (send("记住我对芒果过敏").getOrThrow() as AiProposalSendOutcome.ProposalCreated).proposal
        proposals.confirmProposal(created.id).getOrThrow()
        assertEquals(1, fakeMemories.memories.size)
        server.takeRequest() // 消费掉提案路径的第一次请求

        // 授权了待办但未授权记忆：请求体绝不含记忆原文
        authorizer.grant(setOf(AiContextCategory.TASKS))
        server.enqueue(sse("好的"))
        chat.sendWithAuthorizedContext(null, "推荐一个食谱") {}.getOrThrow()

        val body = server.takeRequest().body!!.utf8()
        val systemContent = parseMessages(body)[0].jsonObject["content"]!!.jsonPrimitive.content
        assertFalse("未授权记忆类别不得外发记忆原文: $systemContent", systemContent.contains("芒果过敏"))
        assertTrue("应说明记忆未授权: $systemContent", systemContent.contains("记忆") && systemContent.contains("未授权"))
    }

    @Test
    fun 授权后请求体注入活动记忆原文上限十条最新优先() = runBlocking {
        (1..12).forEach { i ->
            memories.create(
                MemoryInput(content = "记忆内容第${i}条：偏好${i}号方案", type = MemoryType.PREFERENCE),
                source = MemorySource.CHAT,
            ).getOrThrow().let { saved ->
                // 递增 updatedAt 保证排序确定（最新在后写入）
                fakeMemories.memories[saved.id] = saved.copy(updatedAt = NOW + i)
            }
        }

        authorizer.grant(setOf(AiContextCategory.MEMORY))
        server.enqueue(sse("好的，我记得你的偏好。"))
        chat.sendWithAuthorizedContext(null, "推荐一个方案") {}.getOrThrow()

        val body = server.takeRequest().body!!.utf8()
        val systemContent = parseMessages(body)[0].jsonObject["content"]!!.jsonPrimitive.content
        // 只注入最新 10 条（第 3～12 条），最早的 2 条不发送（少量相关内容）
        (3..12).forEach { i ->
            assertTrue("应包含第${i}条记忆原文: $systemContent", systemContent.contains("记忆内容第${i}条"))
        }
        assertFalse("最早第1条超出上限不得发送", systemContent.contains("记忆内容第1条"))
        assertFalse("最早第2条超出上限不得发送", systemContent.contains("记忆内容第2条"))
        // 原文不篡改：逐字保留
        assertTrue(systemContent.contains("记忆内容第12条：偏好12号方案"))
    }

    @Test
    fun 停用过期与未确认记忆不进入聊天上下文() = runBlocking {
        val active = memories.create(MemoryInput(content = "偏好不吃香菜", type = MemoryType.PREFERENCE))
            .getOrThrow()
        // 直接构造非活动记忆：停用、过期、未确认（活动集合只认 confirmed+enabled+未过期）
        fakeMemories.memories["disabled"] = Memory(
            id = "disabled",
            content = "已停用的记忆：临时偏好低碳饮食",
            type = MemoryType.PREFERENCE,
            source = MemorySource.CHAT,
            confirmed = true,
            enabled = false,
            createdAt = NOW,
            updatedAt = NOW,
            verifiedAt = NOW,
        )
        fakeMemories.memories["expired"] = Memory(
            id = "expired",
            content = "已过期的记忆：本周尝试早起",
            type = MemoryType.PREFERENCE,
            source = MemorySource.CHAT,
            confirmed = true,
            enabled = true,
            createdAt = NOW,
            updatedAt = NOW,
            verifiedAt = NOW,
            expiresAt = NOW - 1,
        )
        fakeMemories.memories["unconfirmed"] = Memory(
            id = "unconfirmed",
            content = "未确认的记忆：猜测的偏好",
            type = MemoryType.PREFERENCE,
            source = MemorySource.CHAT,
            confirmed = false,
            enabled = true,
            createdAt = NOW,
            updatedAt = NOW,
            verifiedAt = NOW,
        )

        authorizer.grant(setOf(AiContextCategory.MEMORY))
        server.enqueue(sse("好的"))
        chat.sendWithAuthorizedContext(null, "今晚吃什么好") {}.getOrThrow()

        val body = server.takeRequest().body!!.utf8()
        val systemContent = parseMessages(body)[0].jsonObject["content"]!!.jsonPrimitive.content
        assertTrue("活动记忆应注入: $systemContent", systemContent.contains("偏好不吃香菜"))
        assertFalse("停用记忆不得进入上下文: $systemContent", systemContent.contains("低碳饮食"))
        assertFalse("过期记忆不得进入上下文: $systemContent", systemContent.contains("本周尝试早起"))
        assertFalse("未确认记忆不得进入上下文: $systemContent", systemContent.contains("猜测的偏好"))
        assertEquals("活动记忆 id 校验", active.id, fakeMemories.memories[active.id]!!.id)
    }

    // ---------- 替身 ----------

    /** 记忆仓库替身：记录读取次数（供「未授权不读取」断言）与写入内容（供真实入口断言）。 */
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

        // 来源删除边界联动（#28）：本替身无持久化来源引用条目，联动为无操作返回 0
        override suspend fun disableAndMarkSourceMissing(
            sourceType: MemoryRefType,
            sourceId: String,
            updatedAt: Long,
        ): Int = 0

        override suspend fun clearSourceMissing(
            sourceType: MemoryRefType,
            sourceId: String,
            updatedAt: Long,
        ): Int = 0

        override suspend fun deleteBySourceRef(sourceType: MemoryRefType, sourceId: String): Int = 0
    }

    /** 空实现替身：本测试只覆盖记忆提案与记忆检索，任何待办读写都是错误。 */
    private class NoopTasksRepository : TasksRepository {
        override fun observeTasks(): Flow<List<TodoTask>> = flowOf(emptyList())
        override suspend fun getTask(id: String): TodoTask? = null
        override suspend fun upsertTask(task: TodoTask) = throw UnsupportedOperationException("本测试不应写入待办")
        override suspend fun trashTask(id: String, trashedAtMillis: Long) =
            throw UnsupportedOperationException("本测试不应改动待办")

        override fun observeTrashedTasks(): Flow<List<TodoTask>> = flowOf(emptyList())
        override suspend fun listTrashedTasks(): List<TodoTask> = emptyList()
        override suspend fun purgeTask(id: String) = throw UnsupportedOperationException("本测试不应删除待办")
    }

    private class NoopNotesRepository : NotesRepository {
        override fun observeNotes(): Flow<List<Note>> = flowOf(emptyList())
        override suspend fun getNote(id: String): Note? = null
        override suspend fun upsertNote(note: Note) = throw UnsupportedOperationException("本测试不应写入便签")
        override suspend fun trashNote(id: String, trashedAtMillis: Long) =
            throw UnsupportedOperationException("本测试不应改动便签")

        override fun observeTrashedNotes(): Flow<List<Note>> = flowOf(emptyList())
        override suspend fun listTrashedNotes(): List<Note> = emptyList()
        override suspend fun purgeNote(id: String) = throw UnsupportedOperationException("本测试不应删除便签")
    }

    private class NoopCalendarRepository : CalendarRepository {
        override fun observeEntries(): Flow<List<CalendarEntry>> = flowOf(emptyList())
        override suspend fun getEntry(id: String): CalendarEntry? = null
        override suspend fun getEntriesLinkedToTask(taskId: String): List<CalendarEntry> = emptyList()
        override suspend fun upsertEntry(entry: CalendarEntry) =
            throw UnsupportedOperationException("本测试不应写入日程")

        override suspend fun trashEntry(id: String, trashedAtMillis: Long) =
            throw UnsupportedOperationException("本测试不应改动日程")

        override fun observeTrashedEntries(): Flow<List<CalendarEntry>> = flowOf(emptyList())
        override suspend fun listTrashedEntries(): List<CalendarEntry> = emptyList()
        override suspend fun purgeEntry(id: String) = throw UnsupportedOperationException("本测试不应删除日程")
    }

    private class NoopCalendarRemindersRepository : CalendarRemindersRepository {
        override fun observeByEntry(entryId: String): Flow<List<Reminder>> = flowOf(emptyList())
        override suspend fun listByEntry(entryId: String): List<Reminder> = emptyList()
        override suspend fun listAll(): List<Reminder> = emptyList()
        override suspend fun get(id: String): Reminder? = null
        override suspend fun upsert(reminder: Reminder) =
            throw UnsupportedOperationException("本测试不应写入提醒")

        override suspend fun delete(id: String) = throw UnsupportedOperationException("本测试不应删除提醒")
        override suspend fun deleteByEntry(entryId: String) =
            throw UnsupportedOperationException("本测试不应删除提醒")
    }

    private class NoopAlarmScheduler : AlarmScheduler {
        override fun canScheduleExact(): Boolean = false
        override suspend fun scheduleExact(reminderId: String, triggerAtMillis: Long, taskId: String, title: String): Boolean =
            throw UnsupportedOperationException("本测试不应调度提醒")

        override suspend fun scheduleInexact(reminderId: String, triggerAtMillis: Long, taskId: String, title: String): Boolean =
            throw UnsupportedOperationException("本测试不应调度提醒")

        override suspend fun cancel(reminderId: String) = throw UnsupportedOperationException("本测试不应取消提醒")
    }
}
