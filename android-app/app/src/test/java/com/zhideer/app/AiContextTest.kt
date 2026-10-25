package com.zhideer.app

import com.zhideer.app.data.ai.AiSessionStore
import com.zhideer.app.data.ai.DeepSeekChatClient
import com.zhideer.app.data.ai.InMemoryAiConfigStore
import com.zhideer.app.data.ai.RepositoryAiContextProvider
import com.zhideer.app.domain.model.AiChatRole
import com.zhideer.app.domain.model.AiConfig
import com.zhideer.app.domain.model.CalendarEntry
import com.zhideer.app.domain.model.Note
import com.zhideer.app.domain.model.TaskPriority
import com.zhideer.app.domain.model.TaskStatus
import com.zhideer.app.domain.model.TodoTask
import com.zhideer.app.domain.repository.CalendarRepository
import com.zhideer.app.domain.repository.NotesRepository
import com.zhideer.app.domain.repository.TasksRepository
import com.zhideer.app.domain.usecase.AiChatUseCases
import com.zhideer.app.domain.usecase.AiContextAuthorizer
import com.zhideer.app.domain.usecase.AiContextCategory
import com.zhideer.app.domain.usecase.AiContextGrant
import com.zhideer.app.domain.usecase.AiContextProvider
import com.zhideer.app.domain.usecase.AiContextSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
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
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/**
 * T18/#19 验收测试：授权上下文并查询真实事项。
 * - 显式授权门：未授权时授权发送失败，且绝不发出包含本地事项内容的请求；
 * - 授权后把真实记录快照（结构化文本）注入 system 消息，请求基于真实记录、不编造；
 * - 各类别（会话历史/待办/日程/便签）可独立关闭，关闭类别不外发其数据；
 * - 授权可随时撤销，撤销后请求体恢复不含本地数据；
 * - 生产快照提供者只读授权类别：支持今日/未来七天窗口，排除回收站与完成/取消记录；
 * - 错误路径全部为中文可诊断提示。
 * 真实模型调用不在本任务范围（MockWebServer 验证请求体与流程）。
 */
class AiContextTest {

    private companion object {
        const val KEY = "sk-test-1234567890abcdef"
        const val MODEL = "deepseek-chat"
        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
        // 2026-10-25 14:00（上海）：快照的“今天”固定为 2026-10-25
        val NOW_MILLIS: Long = LocalDate.of(2026, 10, 25).atStartOfDay(ZONE).toInstant().toEpochMilli() + 14 * 3600_000
        val TODAY: Long = LocalDate.of(2026, 10, 25).toEpochDay()
    }

    private lateinit var server: MockWebServer
    private lateinit var configStore: InMemoryAiConfigStore
    private lateinit var sessionStore: AiSessionStore
    private lateinit var client: DeepSeekChatClient
    private lateinit var authorizer: AiContextAuthorizer
    private lateinit var provider: FakeContextProvider
    private lateinit var useCases: AiChatUseCases
    private lateinit var storeDir: File
    private val idSeq = AtomicInteger(0)
    private var clock: Long = NOW_MILLIS

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        configStore = InMemoryAiConfigStore()
        storeDir = File(System.getProperty("java.io.tmpdir"), "t19-test-${System.nanoTime()}")
        check(storeDir.mkdirs() || storeDir.isDirectory) { "无法创建测试目录" }
        sessionStore = AiSessionStore(File(storeDir, "ai_sessions.preferences_pb"))
        client = DeepSeekChatClient(connectTimeoutMs = 2_000, readTimeoutMs = 800)
        authorizer = AiContextAuthorizer()
        provider = FakeContextProvider()
        useCases = AiChatUseCases(
            configStore = configStore,
            sessionStore = sessionStore,
            client = client,
            idGen = { "session-${idSeq.incrementAndGet()}" },
            now = { clock },
            contextAuthorizer = authorizer,
            contextProvider = provider,
        )
    }

    @After
    fun tearDown() {
        client.close()
        server.close()
        sessionStore.close()
        storeDir.deleteRecursively()
    }

    // ---------- 工具 ----------

    private fun givenConfig() {
        configStore.save(AiConfig(apiKey = KEY, model = MODEL, baseUrl = server.url("/v1").toString()))
    }

    private fun sseResponse(vararg deltas: String): MockResponse {
        val body = buildString {
            deltas.forEach { delta ->
                append("data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"model\":\"$MODEL\"," +
                    "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"$delta\"},\"finish_reason\":null}]}\n\n")
            }
            append("data: [DONE]\n\n")
        }
        return MockResponse.Builder()
            .code(200)
            .addHeader("Content-Type", "text/event-stream")
            .body(body)
            .build()
    }

    private fun grantAll() {
        authorizer.grant(AiContextCategory.entries.toSet())
    }

    private fun parseMessages(requestBody: String) =
        Json.parseToJsonElement(requestBody).jsonObject["messages"]!!.jsonArray

    // ---------- 1. 显式授权门 ----------

    @Test
    fun 未授权时授权发送失败且不发出任何请求() = runBlocking {
        givenConfig()
        val result = useCases.sendWithAuthorizedContext(null, "我今天有什么待办？") {}
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue("应提示需要授权: $message", message.contains("授权"))
        assertEquals("未授权时不得发出网络请求", 0, server.requestCount)
        assertNull("未授权不得记录任何授权", authorizer.currentGrant())
    }

    @Test
    fun 授权后发送请求体含真实快照且不编造指令() = runBlocking {
        givenConfig()
        grantAll()
        server.enqueue(sseResponse("你今天有 1 项今日到期待办：交房租。"))

        val collected = StringBuilder()
        val result = useCases.sendWithAuthorizedContext(null, "我今天有什么待办？") { delta -> collected.append(delta) }
        assertTrue("授权后应可发送: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("你今天有 1 项今日到期待办：交房租。", collected.toString())

        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.target)
        val body = request.body!!.utf8()
        assertEquals(true, Json.parseToJsonElement(body).jsonObject["stream"]!!.jsonPrimitive.boolean)
        val messages = parseMessages(body)
        // 第一条为 system 上下文消息，注入真实快照
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        val systemContent = messages[0].jsonObject["content"]!!.jsonPrimitive.content
        assertTrue("应包含待办快照标题: $systemContent", systemContent.contains("交房租"))
        assertTrue("应包含过期待办标题: $systemContent", systemContent.contains("缴水电费"))
        assertTrue("应包含日程快照标题: $systemContent", systemContent.contains("团队例会"))
        assertTrue("应包含便签快照标题: $systemContent", systemContent.contains("购物清单"))
        assertTrue("应声明快照生成时间: $systemContent", systemContent.contains("2026-10-25"))
        assertTrue("应包含不编造规则: $systemContent", systemContent.contains("编造"))
        assertTrue("应说明空记录如实回答: $systemContent", systemContent.contains("如实"))
        // 用户消息紧随其后
        assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("我今天有什么待办？", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
        // 请求体不得包含 API Key
        assertFalse("请求体不得包含 API Key", body.contains(KEY))
        // 快照只注入请求，不写入会话历史：保存的消息仍只有 user 与 assistant
        val session = result.getOrThrow()
        assertEquals(listOf(AiChatRole.USER, AiChatRole.ASSISTANT), session.messages.map { it.role })
    }

    // ---------- 2. 类别粒度 ----------

    @Test
    fun 关闭日程与便签类别后请求体不含其数据() = runBlocking {
        givenConfig()
        authorizer.grant(setOf(AiContextCategory.TASKS))
        server.enqueue(sseResponse("好的"))

        val result = useCases.sendWithAuthorizedContext(null, "今天要做什么？") {}
        assertTrue(result.isSuccess)
        val systemContent = parseMessages(server.takeRequest().body!!.utf8())[0].jsonObject["content"]!!.jsonPrimitive.content
        assertTrue("应包含已授权待办数据: $systemContent", systemContent.contains("交房租"))
        assertFalse("未授权日程类别不得外发其数据: $systemContent", systemContent.contains("团队例会"))
        assertFalse("未授权便签类别不得外发其数据: $systemContent", systemContent.contains("购物清单"))
        assertTrue("应说明日程未提供: $systemContent", systemContent.contains("日程") && systemContent.contains("未授权"))
        assertTrue("应说明便签未提供: $systemContent", systemContent.contains("便签") && systemContent.contains("未授权"))
    }

    @Test
    fun 关闭会话历史后请求体只含上下文与当前消息() = runBlocking {
        givenConfig()
        grantAll()
        server.enqueue(sseResponse("第一答"))
        val first = useCases.sendWithAuthorizedContext(null, "第一问") {}.getOrThrow()

        server.enqueue(sseResponse("第二答"))
        useCases.sendWithAuthorizedContext(first.id, "第二问") {}.getOrThrow()

        val r1 = parseMessages(server.takeRequest().body!!.utf8())
        assertEquals("新会话首轮：system + 当前用户消息", 2, r1.size)
        assertEquals("system", r1[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("第一问", r1[1].jsonObject["content"]!!.jsonPrimitive.content)

        val r2 = parseMessages(server.takeRequest().body!!.utf8())
        assertEquals("历史已授权时：system + 会话历史 + 当前消息", 4, r2.size)
        assertEquals("system", r2[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("第一问", r2[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("第一答", r2[2].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("第二问", r2[3].jsonObject["content"]!!.jsonPrimitive.content)

        // 各类别独立关闭：保留待办与便签，关闭会话历史
        authorizer.grant(setOf(AiContextCategory.TASKS, AiContextCategory.NOTES))
        server.enqueue(sseResponse("第三答"))
        useCases.sendWithAuthorizedContext(first.id, "第三问") {}.getOrThrow()

        val r3 = parseMessages(server.takeRequest().body!!.utf8())
        assertEquals("关闭会话历史后只含 system 与当前消息", 2, r3.size)
        assertEquals("system", r3[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("user", r3[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("第三问", r3[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertFalse("关闭会话历史后不得外发此前消息", r3.toString().contains("第一问"))
        assertFalse("关闭会话历史后不得外发此前回复", r3.toString().contains("第一答"))
    }

    @Test
    fun 已授权但无记录时如实说明暂无记录() = runBlocking {
        givenConfig()
        grantAll()
        provider.snapshot = AiContextSnapshot(generatedAtText = "2026-10-25 14:00（Asia/Shanghai）")
        server.enqueue(sseResponse("当前没有相关记录"))

        useCases.sendWithAuthorizedContext(null, "今天有什么安排？") {}.getOrThrow()
        val systemContent = parseMessages(server.takeRequest().body!!.utf8())[0].jsonObject["content"]!!.jsonPrimitive.content
        assertTrue("待办节应注明暂无记录: $systemContent", systemContent.contains("暂无待办记录"))
        assertTrue("日程节应注明暂无记录: $systemContent", systemContent.contains("暂无日程记录"))
        assertTrue("便签节应注明暂无记录: $systemContent", systemContent.contains("暂无便签记录"))
    }

    // ---------- 3. 撤销 ----------

    @Test
    fun 撤销授权后发送失败_普通发送请求体恢复不含本地数据() = runBlocking {
        givenConfig()
        grantAll()
        server.enqueue(sseResponse("答一"))
        useCases.sendWithAuthorizedContext(null, "查一下待办") {}.getOrThrow()
        server.takeRequest()

        authorizer.revoke()
        assertNull(authorizer.currentGrant())

        // 撤销后授权发送直接失败，不再发出任何请求
        val denied = useCases.sendWithAuthorizedContext(null, "再查一次") {}
        assertTrue(denied.isFailure)
        assertTrue(denied.exceptionOrNull()!!.message!!.contains("授权"))
        assertEquals("撤销后不得再发出授权请求", 1, server.requestCount)

        // 普通发送仍可用，且请求体恢复为只含会话内消息、不含任何本地数据
        server.enqueue(sseResponse("答二"))
        val plain = useCases.sendStreaming(null, "不查数据随便聊聊") {}
        assertTrue(plain.isSuccess)
        val body = server.takeRequest().body!!.utf8()
        val messages = parseMessages(body)
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertFalse("撤销后请求体不得包含待办数据", body.contains("交房租"))
        assertFalse("撤销后请求体不得包含日程数据", body.contains("团队例会"))
        assertFalse("撤销后请求体不得包含便签数据", body.contains("购物清单"))
    }

    // ---------- 4. 错误路径 ----------

    @Test
    fun 快照读取失败返回中文提示且不发出请求() = runBlocking {
        givenConfig()
        grantAll()
        provider.failWith = RuntimeException("disk error")
        val result = useCases.sendWithAuthorizedContext(null, "查待办") {}
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue("应为中文读取失败提示: $message", message.contains("本地记录") || message.contains("无法读取"))
        assertEquals("快照失败时不得发出请求", 0, server.requestCount)

        // 恢复后可正常发送
        provider.failWith = null
        server.enqueue(sseResponse("好了"))
        assertTrue(useCases.sendWithAuthorizedContext(null, "查待办") {}.isSuccess)
    }

    @Test
    fun 授权但未配置服务时沿用中文配置提示() = runBlocking {
        grantAll()
        val result = useCases.sendWithAuthorizedContext(null, "查待办") {}
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("配置"))
        assertEquals(0, server.requestCount)
    }

    // ---------- 5. 生产快照提供者（真实仓库只读） ----------

    private fun newProvider(
        tasks: FakeTasksRepository = FakeTasksRepository(),
        calendar: FakeCalendarRepository = FakeCalendarRepository(),
        notes: FakeNotesRepository = FakeNotesRepository(),
    ): RepositoryAiContextProvider =
        RepositoryAiContextProvider(tasks, calendar, notes, zoneId = ZONE, now = { NOW_MILLIS })

    private fun task(
        id: String,
        title: String,
        dueEpochDay: Long? = null,
        dueAtMillis: Long? = null,
        status: TaskStatus = TaskStatus.ACTIVE,
        trashedAt: Long? = null,
        priority: TaskPriority = TaskPriority.NORMAL,
    ) = TodoTask(
        id = id,
        title = title,
        priority = priority,
        dueEpochDay = dueEpochDay,
        dueAtMillis = dueAtMillis,
        dueZoneId = ZONE.id,
        status = status,
        trashedAt = trashedAt,
        createdAt = NOW_MILLIS,
        updatedAt = NOW_MILLIS,
    )

    @Test
    fun 生产提供者_待办按今日过期未来七天过滤_排除完成取消回收站与窗口外() = runBlocking {
        val repo = FakeTasksRepository()
        val past = LocalDate.of(2026, 10, 24).toEpochDay()
        val day8 = LocalDate.of(2026, 11, 2).toEpochDay()
        val tomorrowAtMillis = LocalDate.of(2026, 10, 26).atTime(9, 0).atZone(ZONE).toInstant().toEpochMilli()
        repo.tasks += task("t1", "过期待办", dueEpochDay = past)
        repo.tasks += task("t2", "今日到期待办", dueEpochDay = TODAY, priority = TaskPriority.HIGH)
        repo.tasks += task("t3", "时刻截止待办", dueAtMillis = tomorrowAtMillis)
        repo.tasks += task("t4", "第八天待办", dueEpochDay = day8) // 窗口外
        repo.tasks += task("t5", "无截止待办") // 无日期不参与今日/未来七天查询
        repo.tasks += task("t6", "已完成待办", dueEpochDay = TODAY, status = TaskStatus.COMPLETED)
        repo.tasks += task("t7", "已取消待办", dueEpochDay = TODAY, status = TaskStatus.CANCELLED)
        repo.tasks += task("t8", "回收站待办", dueEpochDay = TODAY, trashedAt = NOW_MILLIS)

        val snapshot = newProvider(tasks = repo).loadSnapshot(AiContextGrant(setOf(AiContextCategory.TASKS)))
        val titles = snapshot.tasks.map { it.title }
        assertEquals(listOf("过期待办", "今日到期待办", "时刻截止待办"), titles)
        assertEquals("已过期", snapshot.tasks[0].stateLabel)
        assertEquals("今日到期", snapshot.tasks[1].stateLabel)
        assertEquals("未来七天", snapshot.tasks[2].stateLabel)
        assertEquals("高", snapshot.tasks[1].priorityLabel)
    }

    @Test
    fun 生产提供者_日程取今天起未来七天_排除回收站与窗口外() = runBlocking {
        val repo = FakeCalendarRepository()
        fun entry(id: String, title: String, start: String, end: String, trashedAt: Long? = null): CalendarEntry {
            val startMillis = LocalDate.parse(start.substringBefore(" ")).atTime(
                start.substringAfter(" ").substringBefore(":").toInt(),
                start.substringAfter(":").toInt(),
            ).atZone(ZONE).toInstant().toEpochMilli()
            val endMillis = LocalDate.parse(end.substringBefore(" ")).atTime(
                end.substringAfter(" ").substringBefore(":").toInt(),
                end.substringAfter(":").toInt(),
            ).atZone(ZONE).toInstant().toEpochMilli()
            return CalendarEntry(
                id = id,
                title = title,
                startMillis = startMillis,
                endMillis = endMillis,
                timezoneId = ZONE.id,
                location = "",
                trashedAt = trashedAt,
                createdAt = NOW_MILLIS,
                updatedAt = NOW_MILLIS,
            )
        }
        repo.entries += entry("c1", "今天日程", "2026-10-25 09:00", "2026-10-25 10:00")
        repo.entries += entry("c2", "第七天日程", "2026-11-01 10:00", "2026-11-01 11:00")
        repo.entries += entry("c3", "第八天日程", "2026-11-02 10:00", "2026-11-02 11:00") // 窗口外
        repo.entries += entry("c4", "昨天开始今天结束", "2026-10-24 22:00", "2026-10-25 01:00") // 跨入今天仍在窗口
        repo.entries += entry("c5", "回收站日程", "2026-10-25 09:00", "2026-10-25 10:00", trashedAt = NOW_MILLIS)

        val snapshot = newProvider(calendar = repo).loadSnapshot(AiContextGrant(setOf(AiContextCategory.SCHEDULE)))
        val titles = snapshot.schedule.map { it.title }
        assertEquals(listOf("昨天开始今天结束", "今天日程", "第七天日程"), titles)
    }

    @Test
    fun 生产提供者_便签取最近十条_排除回收站() = runBlocking {
        val repo = FakeNotesRepository()
        (1..12).forEach { i ->
            repo.notes += Note(
                id = "n$i",
                title = "便签$i",
                content = "内容$i",
                trashedAt = if (i == 11) NOW_MILLIS else null,
                createdAt = NOW_MILLIS,
                updatedAt = NOW_MILLIS + i * 1000,
            )
        }
        val snapshot = newProvider(notes = repo).loadSnapshot(AiContextGrant(setOf(AiContextCategory.NOTES)))
        val titles = snapshot.notes.map { it.title }
        assertEquals(10, titles.size)
        assertFalse("回收站便签不得进入快照", titles.contains("便签11"))
        assertEquals("按最近更新倒序，最新在前", "便签12", titles.first())
        assertEquals("便签2", titles.last())
    }

    @Test
    fun 生产提供者_只读取已授权类别的仓库() = runBlocking {
        val tasks = FakeTasksRepository()
        val calendar = FakeCalendarRepository()
        val notes = FakeNotesRepository()
        newProvider(tasks, calendar, notes).loadSnapshot(AiContextGrant(setOf(AiContextCategory.TASKS)))
        assertEquals(1, tasks.observeCalls)
        assertEquals(0, calendar.observeCalls)
        assertEquals(0, notes.observeCalls)

        newProvider(tasks, calendar, notes)
            .loadSnapshot(AiContextGrant(setOf(AiContextCategory.SCHEDULE, AiContextCategory.NOTES)))
        assertEquals(1, tasks.observeCalls)
        assertEquals(1, calendar.observeCalls)
        assertEquals(1, notes.observeCalls)
    }
}

// ---------- 测试替身 ----------

/** 固定快照替身：返回三类真实感记录，供请求体组装断言。 */
private class FakeContextProvider : AiContextProvider {
    var snapshot: AiContextSnapshot = AiContextSnapshot(
        generatedAtText = "2026-10-25 14:00（Asia/Shanghai）",
        tasks = listOf(
            com.zhideer.app.domain.usecase.AiContextTaskRecord(
                title = "缴水电费",
                dueText = "2026-10-24",
                stateLabel = "已过期",
                priorityLabel = "普通",
                recurring = false,
            ),
            com.zhideer.app.domain.usecase.AiContextTaskRecord(
                title = "交房租",
                dueText = "2026-10-25",
                stateLabel = "今日到期",
                priorityLabel = "高",
                recurring = false,
            ),
            com.zhideer.app.domain.usecase.AiContextTaskRecord(
                title = "写周报",
                dueText = "2026-10-28",
                stateLabel = "未来七天",
                priorityLabel = "普通",
                recurring = false,
            ),
        ),
        schedule = listOf(
            com.zhideer.app.domain.usecase.AiContextScheduleRecord(
                title = "团队例会",
                timeText = "10-25 14:00～15:00",
                location = "会议室",
                note = "",
            ),
        ),
        notes = listOf(
            com.zhideer.app.domain.usecase.AiContextNoteRecord(
                title = "购物清单",
                updatedText = "2026-10-24 20:00",
                content = "买牛奶和鸡蛋",
                itemsText = "[ ] 牛奶、[x] 鸡蛋",
            ),
        ),
    )
    var failWith: RuntimeException? = null

    override suspend fun loadSnapshot(grant: AiContextGrant): AiContextSnapshot {
        failWith?.let { throw it }
        return snapshot
    }
}

private class FakeTasksRepository : TasksRepository {
    val tasks = mutableListOf<TodoTask>()
    var observeCalls = 0

    override fun observeTasks(): Flow<List<TodoTask>> = flow {
        observeCalls++
        emit(tasks.filter { it.trashedAt == null })
    }

    override suspend fun getTask(id: String): TodoTask? = tasks.find { it.id == id }

    override suspend fun upsertTask(task: TodoTask) {
        tasks.removeAll { it.id == task.id }
        tasks.add(task)
    }

    override suspend fun trashTask(id: String, trashedAtMillis: Long) {
        val index = tasks.indexOfFirst { it.id == id }
        if (index >= 0) tasks[index] = tasks[index].copy(trashedAt = trashedAtMillis)
    }

    override fun observeTrashedTasks(): Flow<List<TodoTask>> = flow { emit(tasks.filter { it.trashedAt != null }) }

    override suspend fun listTrashedTasks(): List<TodoTask> = tasks.filter { it.trashedAt != null }

    override suspend fun purgeTask(id: String) {
        tasks.removeAll { it.id == id }
    }
}

private class FakeCalendarRepository : CalendarRepository {
    val entries = mutableListOf<CalendarEntry>()
    var observeCalls = 0

    override fun observeEntries(): Flow<List<CalendarEntry>> = flow {
        observeCalls++
        emit(entries.filter { it.trashedAt == null })
    }

    override suspend fun getEntry(id: String): CalendarEntry? = entries.find { it.id == id }

    override suspend fun getEntriesLinkedToTask(taskId: String): List<CalendarEntry> =
        entries.filter { it.taskId == taskId }

    override suspend fun upsertEntry(entry: CalendarEntry) {
        entries.removeAll { it.id == entry.id }
        entries.add(entry)
    }

    override suspend fun trashEntry(id: String, trashedAtMillis: Long) {
        val index = entries.indexOfFirst { it.id == id }
        if (index >= 0) entries[index] = entries[index].copy(trashedAt = trashedAtMillis)
    }

    override fun observeTrashedEntries(): Flow<List<CalendarEntry>> = flow { emit(entries.filter { it.trashedAt != null }) }

    override suspend fun listTrashedEntries(): List<CalendarEntry> = entries.filter { it.trashedAt != null }

    override suspend fun purgeEntry(id: String) {
        entries.removeAll { it.id == id }
    }
}

private class FakeNotesRepository : NotesRepository {
    val notes = mutableListOf<Note>()
    var observeCalls = 0

    override fun observeNotes(): Flow<List<Note>> = flow {
        observeCalls++
        emit(notes.filter { it.trashedAt == null })
    }

    override suspend fun getNote(id: String): Note? = notes.find { it.id == id }

    override suspend fun upsertNote(note: Note) {
        notes.removeAll { it.id == note.id }
        notes.add(note)
    }

    override suspend fun trashNote(id: String, trashedAtMillis: Long) {
        val index = notes.indexOfFirst { it.id == id }
        if (index >= 0) notes[index] = notes[index].copy(trashedAt = trashedAtMillis)
    }

    override fun observeTrashedNotes(): Flow<List<Note>> = flow { emit(notes.filter { it.trashedAt != null }) }

    override suspend fun listTrashedNotes(): List<Note> = notes.filter { it.trashedAt != null }

    override suspend fun purgeNote(id: String) {
        notes.removeAll { it.id == id }
    }
}
