package com.zhideer.app

import com.zhideer.app.data.ai.AiSessionStore
import com.zhideer.app.data.ai.DeepSeekChatClient
import com.zhideer.app.data.ai.InMemoryAiConfigStore
import com.zhideer.app.domain.model.AiChatRole
import com.zhideer.app.domain.model.AiConfig
import com.zhideer.app.domain.usecase.AiChatUseCases
import com.zhideer.app.domain.usecase.AiConfigInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * T17/#18 验收测试：DeepSeek 中文对话。
 * - 本地 mock server 验证流式多块拼接、中文内容、鉴权/网络/超时/流中断的中文可诊断提示；
 * - 请求体只包含会话内消息（不携带本地事项、记忆等未授权上下文），Key 不进入任何错误文本；
 * - 取消生成后不再有增量回调、不保存部分回复、已发送的用户消息保留，且可再次发送；
 * - 配置掩码显示；多会话支持重开与删除。
 * 真实端到端连接验证需用户提供 API Key 后另行执行，不在本测试范围。
 */
class AiChatUseCasesTest {

    private companion object {
        const val KEY = "sk-test-1234567890abcdef"
        const val MODEL = "deepseek-chat"
        const val TITLE_N = 12
    }

    private lateinit var server: MockWebServer
    private lateinit var configStore: InMemoryAiConfigStore
    private lateinit var sessionStore: AiSessionStore
    private lateinit var client: DeepSeekChatClient
    private lateinit var useCases: AiChatUseCases
    private lateinit var storeDir: File
    private val idSeq = AtomicInteger(0)
    private var clock: Long = 1_000L

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        configStore = InMemoryAiConfigStore()
        storeDir = File(System.getProperty("java.io.tmpdir"), "t18-test-${System.nanoTime()}")
        check(storeDir.mkdirs() || storeDir.isDirectory) { "无法创建测试目录" }
        sessionStore = AiSessionStore(File(storeDir, "ai_sessions.preferences_pb"))
        client = DeepSeekChatClient(connectTimeoutMs = 2_000, readTimeoutMs = 800)
        useCases = AiChatUseCases(
            configStore = configStore,
            sessionStore = sessionStore,
            client = client,
            idGen = { "session-${idSeq.incrementAndGet()}" },
            now = { clock },
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

    /** 用配置指向本地 mock server。 */
    private fun givenConfig() {
        configStore.save(AiConfig(apiKey = KEY, model = MODEL, baseUrl = server.url("/v1").toString()))
    }

    /** 标准 SSE 流式响应：多块中文增量 + [DONE] 结束。 */
    private fun sseResponse(vararg deltas: String): MockResponse =
        MockResponse.Builder()
            .code(200)
            .addHeader("Content-Type", "text/event-stream")
            .body(sseBody(deltas.toList(), withDone = true))
            .build()

    private fun sseBody(deltas: List<String>, withDone: Boolean): String = buildString {
        deltas.forEach { delta -> append(chunkLine(delta)) }
        if (withDone) append("data: [DONE]\n\n")
    }

    private fun chunkLine(content: String): String =
        "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"model\":\"$MODEL\"," +
            "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"$content\"},\"finish_reason\":null}]}\n\n"

    /** 非流式补全响应（连接测试用）。 */
    private fun completionResponse(content: String = "pong"): MockResponse =
        MockResponse.Builder()
            .code(200)
            .addHeader("Content-Type", "application/json")
            .body(
                "{\"id\":\"c2\",\"object\":\"chat.completion\",\"model\":\"$MODEL\"," +
                    "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"$content\"}," +
                    "\"finish_reason\":\"stop\"}]}",
            )
            .build()

    private fun errorResponse(code: Int, body: String): MockResponse =
        MockResponse.Builder()
            .code(code)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build()

    private fun assertNoKeyLeak(t: Throwable) {
        val text = (t.message.orEmpty()) + "\n" + t.stackTraceToString()
        assertFalse("错误信息不得包含 API Key", text.contains(KEY))
    }

    // ---------- 1. 流式与保存 ----------

    @Test
    fun 流式回复多块拼接为完整中文回复并保存用户与助手消息() = runBlocking {
        givenConfig()
        server.enqueue(sseResponse("你好", "，", "很高兴认识你", "！"))

        val collected = StringBuilder()
        val result = useCases.sendStreaming(sessionId = null, userText = "你好，请做一个完整的自我介绍") { delta ->
            collected.append(delta)
        }

        val session = result.getOrThrow()
        assertEquals("你好，很高兴认识你！", collected.toString())
        assertEquals(2, session.messages.size)
        assertEquals(AiChatRole.USER, session.messages[0].role)
        assertEquals("你好，请做一个完整的自我介绍", session.messages[0].content)
        assertEquals(AiChatRole.ASSISTANT, session.messages[1].role)
        assertEquals("你好，很高兴认识你！", session.messages[1].content)
        // 标题取首条用户消息前 N 字，超长加省略号
        assertEquals("你好，请做一个完整的自我介绍".take(TITLE_N) + "…", session.title)
        // 会话已持久化，重开一致
        val reopened = useCases.openSession(session.id)
        assertNotNull(reopened)
        assertEquals(session.messages, reopened!!.messages)
    }

    // ---------- 2. 请求体只含会话内消息 ----------

    @Test
    fun 请求体只含会话内消息且携带Bearer鉴权与正确路径() = runBlocking {
        givenConfig()
        server.enqueue(sseResponse("答案"))
        val first = useCases.sendStreaming(null, "第一问") {}.getOrThrow()
        server.enqueue(sseResponse("答案二"))
        useCases.sendStreaming(first.id, "第二问") {}.getOrThrow()

        val r1 = server.takeRequest()
        assertEquals("/v1/chat/completions", r1.target)
        assertEquals("Bearer $KEY", r1.headers["Authorization"])
        val body1 = r1.body!!.utf8()
        val root1 = Json.parseToJsonElement(body1).jsonObject
        assertEquals(MODEL, root1["model"]!!.jsonPrimitive.content)
        assertEquals(true, root1["stream"]!!.jsonPrimitive.boolean)
        val messages1 = root1["messages"]!!.jsonArray
        assertEquals(1, messages1.size)
        assertEquals("user", messages1[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("第一问", messages1[0].jsonObject["content"]!!.jsonPrimitive.content)
        // 未授权上下文不得出现在请求体：本地事项、长期记忆或其他历史
        assertFalse(body1.contains("待办"))
        assertFalse(body1.contains("日程"))
        assertFalse(body1.contains("便签"))
        assertFalse(body1.contains("记忆"))
        assertFalse("请求体不得包含 API Key", body1.contains(KEY))

        // 多轮：第二条请求按顺序包含同一会话内此前的用户与助手消息（不含其他会话内容）
        val r2 = server.takeRequest()
        val messages2 = Json.parseToJsonElement(r2.body!!.utf8()).jsonObject["messages"]!!.jsonArray
        assertEquals(3, messages2.size)
        assertEquals("user", messages2[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("第一问", messages2[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("assistant", messages2[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("答案", messages2[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("user", messages2[2].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("第二问", messages2[2].jsonObject["content"]!!.jsonPrimitive.content)
    }

    // ---------- 3. 未配置 ----------

    @Test
    fun 未配置时发送与测试连接返回中文提示() = runBlocking {
        val send = useCases.sendStreaming(null, "你好") {}
        assertTrue(send.isFailure)
        assertTrue(send.exceptionOrNull()!!.message!!.contains("配置"))
        assertNoKeyLeak(send.exceptionOrNull()!!)

        val test = useCases.testConnection()
        assertTrue(test.isFailure)
        assertTrue(test.exceptionOrNull()!!.message!!.contains("配置"))

        val blank = useCases.sendStreaming(null, "   ") {}
        assertTrue(blank.isFailure)
        assertEquals("消息内容不能为空", blank.exceptionOrNull()!!.message)
    }

    // ---------- 4. 鉴权失败 ----------

    @Test
    fun 鉴权失败401返回中文提示且不泄露Key() = runBlocking {
        givenConfig()
        server.enqueue(
            errorResponse(401, "{\"error\":{\"message\":\"Incorrect API key provided\",\"type\":\"invalid_request_error\"}}"),
        )
        val result = useCases.sendStreaming(null, "你好") {}
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue("应包含 401: $message", message.contains("401"))
        assertTrue("应为中文鉴权提示: $message", message.contains("鉴权") || message.contains("API Key"))
        assertNoKeyLeak(result.exceptionOrNull()!!)
        // 用户消息已确认发送，保留在会话中
        val sessions = useCases.listSessions().first()
        assertEquals(1, sessions.size)
        assertEquals(listOf(AiChatRole.USER), sessions[0].messages.map { it.role })
    }

    // ---------- 5. 4xx / 5xx ----------

    @Test
    fun 服务端4xx与5xx错误返回中文提示() = runBlocking {
        givenConfig()
        server.enqueue(errorResponse(400, "{\"error\":{\"message\":\"bad request\"}}"))
        val r400 = useCases.sendStreaming(null, "你好") {}
        assertTrue(r400.isFailure)
        val m400 = r400.exceptionOrNull()!!.message!!
        assertTrue("应包含 400: $m400", m400.contains("400"))
        assertTrue(m400.contains("请求"))
        assertNoKeyLeak(r400.exceptionOrNull()!!)

        server.enqueue(errorResponse(500, "{\"error\":{\"message\":\"server exploded\"}}"))
        val r500 = useCases.sendStreaming(null, "你好") {}
        assertTrue(r500.isFailure)
        val m500 = r500.exceptionOrNull()!!.message!!
        assertTrue("应包含 500: $m500", m500.contains("500"))
        assertTrue(m500.contains("服务"))
        assertNoKeyLeak(r500.exceptionOrNull()!!)
    }

    // ---------- 6. 无法连接 ----------

    @Test
    fun 无法连接时返回中文可诊断提示() = runBlocking {
        givenConfig()
        val port = server.url("/").port
        server.close()
        configStore.save(AiConfig(apiKey = KEY, model = MODEL, baseUrl = "http://127.0.0.1:$port"))

        val result = useCases.sendStreaming(null, "你好") {}
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue("应为中文连接提示: $message", message.contains("无法连接") || message.contains("网络"))
        assertNoKeyLeak(result.exceptionOrNull()!!)
    }

    // ---------- 7. 超时 ----------

    @Test
    fun 等待响应超时返回中文提示() = runBlocking {
        givenConfig()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body(sseBody(listOf("迟到的回复"), withDone = true))
                .bodyDelay(5, TimeUnit.SECONDS)
                .build(),
        )
        val result = useCases.sendStreaming(null, "你好") {}
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue("应为中文超时提示: $message", message.contains("超时"))
        assertNoKeyLeak(result.exceptionOrNull()!!)
    }

    // ---------- 8. 流中断 ----------

    @Test
    fun 流提前结束返回中文提示且不保存助手消息() = runBlocking {
        givenConfig()
        // 只发一块增量且不带 [DONE]，随后连接正常结束 → 流中断
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body(sseBody(listOf("半截"), withDone = false))
                .build(),
        )
        val result = useCases.sendStreaming(null, "给我讲个长故事") {}
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue("应为中文流中断提示: $message", message.contains("中断"))
        assertNoKeyLeak(result.exceptionOrNull()!!)
        // 部分回复不保存
        val session = useCases.listSessions().first().single()
        assertEquals(listOf(AiChatRole.USER), session.messages.map { it.role })
    }

    // ---------- 9. 取消生成 ----------

    @Test
    fun 取消生成后不再有增量回调且不保存部分回复() = runBlocking {
        givenConfig()
        val trickleBody = sseBody((1..12).map { "第${it}句。" }, withDone = false)
        val respondNormal = AtomicBoolean(false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (respondNormal.get()) {
                    sseResponse("新的回复")
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
            useCases.sendStreaming(null, "请写一篇很长的文章") { _ -> deltas.incrementAndGet() }
        }
        withTimeout(15_000) {
            while (deltas.get() < 1) delay(20)
        }
        useCases.cancelGeneration()
        job.join()
        val countAtCancel = deltas.get()
        assertTrue("取消前应已收到至少一个增量", countAtCancel >= 1)
        // 服务器仍在滴流，若取消未生效则计数会继续增长
        delay(1_500)
        assertEquals("取消后不得再有增量回调", countAtCancel, deltas.get())

        // 部分回复不保存，用户消息保留
        val session = useCases.listSessions().first().single()
        assertEquals(listOf(AiChatRole.USER), session.messages.map { it.role })
        assertEquals("请写一篇很长的文章", session.messages.single().content)

        // 取消后可再次发送，且请求只含会话内消息
        respondNormal.set(true)
        val again = useCases.sendStreaming(session.id, "换个话题") {}
        assertTrue("取消后应可再次发送: ${again.exceptionOrNull()}", again.isSuccess)
        val messages = again.getOrThrow().messages
        assertEquals(3, messages.size)
        assertEquals(AiChatRole.USER, messages[0].role)
        assertEquals(AiChatRole.USER, messages[1].role)
        assertEquals(AiChatRole.ASSISTANT, messages[2].role)
        assertEquals("新的回复", messages[2].content)
    }

    // ---------- 10. 配置掩码 ----------

    @Test
    fun 配置保存后掩码显示且不含明文Key空Key更新保留原值() = runBlocking {
        val view = useCases.saveConfig(
            AiConfigInput(apiKey = KEY, model = MODEL, baseUrl = server.url("/v1").toString()),
        ).getOrThrow()
        assertTrue(view.hasApiKey)
        val masked = view.maskedApiKey
        assertNotNull(masked)
        assertTrue("掩码应包含遮蔽符号: $masked", masked!!.contains("****"))
        assertFalse("掩码不得等于明文", masked == KEY)
        assertFalse("掩码不得包含明文长段", masked.contains("1234567890abcdef"))
        // 视图对象字符串化也不泄露明文
        assertFalse(view.toString().contains(KEY))
        // 明文仅保存在配置存储中，用于实际请求
        assertEquals(MODEL, view.model)
        assertEquals(KEY, configStore.load()!!.apiKey)

        // 空 Key 更新保留原 Key（便于只改模型或 Base URL）
        val updated = useCases.saveConfig(
            AiConfigInput(apiKey = "", model = "deepseek-reasoner", baseUrl = server.url("/v1").toString()),
        ).getOrThrow()
        assertTrue(updated.hasApiKey)
        assertEquals("deepseek-reasoner", updated.model)
        assertEquals(KEY, configStore.load()!!.apiKey)

        // 模型或 Base URL 为空被拒绝，提示中文
        val noModel = useCases.saveConfig(AiConfigInput(apiKey = "x", model = " ", baseUrl = "http://x"))
        assertTrue(noModel.isFailure)
        assertTrue(noModel.exceptionOrNull()!!.message!!.contains("模型"))
        val noUrl = useCases.saveConfig(AiConfigInput(apiKey = "x", model = "m", baseUrl = " "))
        assertTrue(noUrl.isFailure)
        assertTrue(noUrl.exceptionOrNull()!!.message!!.contains("Base URL"))
        val badUrl = useCases.saveConfig(AiConfigInput(apiKey = "x", model = "m", baseUrl = "ftp://x"))
        assertTrue(badUrl.isFailure)
        assertTrue(badUrl.exceptionOrNull()!!.message!!.contains("Base URL"))
    }

    // ---------- 11. 多会话 ----------

    @Test
    fun 多会话列表排序重开与删除() = runBlocking {
        givenConfig()
        server.enqueue(sseResponse("第一个回答"))
        val s1 = useCases.sendStreaming(null, "会话甲的第一个问题") {}.getOrThrow()
        clock += 60_000
        server.enqueue(sseResponse("第二个回答"))
        val s2 = useCases.sendStreaming(null, "会话乙的第一个问题") {}.getOrThrow()

        val listed = useCases.listSessions().first()
        assertEquals(listOf(s2.id, s1.id), listed.map { it.id })

        val opened = useCases.openSession(s1.id)
        assertEquals("会话甲的第一个问题".take(TITLE_N), opened!!.title)
        assertEquals("第一个回答", opened.messages.last().content)

        assertTrue(useCases.deleteSession(s1.id).isSuccess)
        assertNull(useCases.openSession(s1.id))
        assertEquals(listOf(s2.id), useCases.listSessions().first().map { it.id })

        val missing = useCases.deleteSession("no-such-session")
        assertTrue(missing.isFailure)
        assertTrue(missing.exceptionOrNull()!!.message!!.contains("会话不存在"))
    }

    // ---------- 12. 连接测试 ----------

    @Test
    fun 测试连接成功与失败路径() = runBlocking {
        givenConfig()
        server.enqueue(completionResponse("pong"))
        val ok = useCases.testConnection()
        assertTrue(ok.isSuccess)
        assertTrue(ok.getOrThrow().contains("连接成功"))

        server.enqueue(errorResponse(401, "{\"error\":{\"message\":\"Invalid API key\"}}"))
        val bad = useCases.testConnection()
        assertTrue(bad.isFailure)
        assertTrue(bad.exceptionOrNull()!!.message!!.contains("401"))
        assertNoKeyLeak(bad.exceptionOrNull()!!)
    }
}
