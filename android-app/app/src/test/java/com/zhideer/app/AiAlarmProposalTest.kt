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
import com.zhideer.app.data.repository.ClockHandoffRepositoryImpl
import com.zhideer.app.data.repository.NotesRepositoryImpl
import com.zhideer.app.data.repository.TasksRepositoryImpl
import com.zhideer.app.domain.model.AiChatRole
import com.zhideer.app.domain.model.AiConfig
import com.zhideer.app.domain.model.AiProposalKind
import com.zhideer.app.domain.model.AiProposalSendOutcome
import com.zhideer.app.domain.model.AiProposalStatus
import com.zhideer.app.domain.model.ClockCapability
import com.zhideer.app.domain.model.ClockHandoffStatus
import com.zhideer.app.domain.model.ClockLaunchResult
import com.zhideer.app.domain.usecase.AiChatUseCases
import com.zhideer.app.domain.usecase.AiContextAuthorizer
import com.zhideer.app.domain.usecase.AiContextProvider
import com.zhideer.app.domain.usecase.AiContextSnapshot
import com.zhideer.app.domain.usecase.AiProposalTargetMatcher
import com.zhideer.app.domain.usecase.AiProposalUseCases
import com.zhideer.app.domain.usecase.CalendarReminderUseCases
import com.zhideer.app.domain.usecase.CalendarUseCases
import com.zhideer.app.domain.usecase.ClockHandoffUseCases
import com.zhideer.app.domain.usecase.NoteUseCases
import com.zhideer.app.domain.usecase.TaskUseCases
import com.zhideer.app.domain.usecase.VoiceInputController
import com.zhideer.app.domain.usecase.VoiceInputState
import com.zhideer.app.domain.usecase.proposalAlarmDetailLines
import com.zhideer.app.domain.usecase.proposalAlarmRepeatText
import com.zhideer.app.domain.usecase.proposalAlarmTimeText
import com.zhideer.app.platform.AlarmScheduler
import com.zhideer.app.platform.ClockLauncher
import com.zhideer.app.platform.SpeechInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/**
 * T25/#26 验收测试：通过 AI 提出系统闹钟操作。
 *
 * 协议：模型回复末尾的提案 JSON 新增 kind "alarm"：
 * {"kind":"alarm","title":"起床","time":"07:30","date":"2026-11-05","weekdays":["monday","wednesday"]}。
 * 设备侧事实（docs/validation/t02-oppo-device.md D09/D10）：
 * - 系统时钟 SET_ALARM 只接受时/分与星期，不接受日历日期；ColorOS 15 只打开闹钟列表、不预填；
 * - 知时鹿无法读取或核实系统闹钟是否保存，交接记录只能保持「待核对」；
 * - 无 handler 时必须禁用发起并给出回退，不得假装成功。
 *
 * 全部经真实 Room 文件库 + 统一用例入口（ClockHandoffUseCases 真实记录表）验证；
 * 模型回复由 MockWebServer 固定注入，真实模型表现待配置 Key 后由 Root 观察。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AiAlarmProposalTest {

    private companion object {
        const val KEY = "sk-test-1234567890abcdef"
        const val MODEL = "deepseek-chat"
        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    }

    /** 记录平台收到的创建请求；不模拟系统时钟实际保存（无法读取）。 */
    private class FakeClockLauncher(
        var capability: ClockCapability = ClockCapability(canCreateAlarm = true, canOpenAlarmList = true),
        var createResult: ClockLaunchResult = ClockLaunchResult.Launched,
        var listResult: ClockLaunchResult = ClockLaunchResult.Launched,
    ) : ClockLauncher {
        data class CreateCall(val hour: Int, val minute: Int, val label: String, val weekdays: Set<Int>)

        val created = mutableListOf<CreateCall>()
        var createCalls = 0
        var listCalls = 0

        override fun capability(): ClockCapability = capability

        override fun createAlarm(hour: Int, minute: Int, label: String, weekdays: Set<Int>): ClockLaunchResult {
            createCalls++
            created += CreateCall(hour, minute, label, weekdays)
            return createResult
        }

        override fun openAlarmList(): ClockLaunchResult {
            listCalls++
            return listResult
        }
    }

    private class NoopAlarmScheduler : AlarmScheduler {
        override fun canScheduleExact(): Boolean = true
        override suspend fun scheduleExact(reminderId: String, triggerAtMillis: Long, taskId: String, title: String): Boolean = true
        override suspend fun scheduleInexact(reminderId: String, triggerAtMillis: Long, taskId: String, title: String): Boolean = true
        override suspend fun cancel(reminderId: String) = Unit
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
    private lateinit var clockLauncher: FakeClockLauncher
    private lateinit var clockHandoffUseCases: ClockHandoffUseCases
    private lateinit var storeDir: File
    private val idSeq = AtomicInteger(0)
    private var clock: Long = 0L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dbFile = context.getDatabasePath("t25_alarm_test.db")
        dbFile.parentFile?.mkdirs()
        context.deleteDatabase(dbFile.name)
        db = Room.databaseBuilder<ZhiDeerDatabase>(context = context, name = dbFile.absolutePath)
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        clock = LocalDateTime.parse("2026-10-25T09:00").atZone(ZONE).toInstant().toEpochMilli()
        val tasksRepository = TasksRepositoryImpl(db.taskDao(), Dispatchers.IO)
        val notesRepository = NotesRepositoryImpl(db.noteDao(), Dispatchers.IO)
        val calendarRepository = CalendarRepositoryImpl(db.calendarEntryDao(), Dispatchers.IO)
        val calendarRemindersRepository = CalendarRemindersRepositoryImpl(db.calendarReminderDao(), Dispatchers.IO)
        clockLauncher = FakeClockLauncher()
        clockHandoffUseCases = ClockHandoffUseCases(
            repository = ClockHandoffRepositoryImpl(db.clockHandoffDao(), Dispatchers.IO),
            launcher = clockLauncher,
            idGen = { "handoff-" + idSeq.incrementAndGet() },
            now = { clock },
        )
        server = MockWebServer()
        server.start()
        configStore = InMemoryAiConfigStore()
        configStore.save(AiConfig(apiKey = KEY, model = MODEL, baseUrl = server.url("/v1").toString()))
        storeDir = File(System.getProperty("java.io.tmpdir"), "t25-test-" + System.nanoTime())
        check(storeDir.mkdirs() || storeDir.isDirectory) { "无法创建测试目录" }
        sessionStore = AiSessionStore(File(storeDir, "ai_sessions.preferences_pb"))
        proposalStore = AiProposalStore(File(storeDir, "ai_proposals.preferences_pb"))
        client = DeepSeekChatClient(connectTimeoutMs = 15_000, readTimeoutMs = 5_000)
        chat = AiChatUseCases(
            configStore = configStore,
            sessionStore = sessionStore,
            client = client,
            idGen = { "session-" + idSeq.incrementAndGet() },
            now = { clock },
            contextAuthorizer = AiContextAuthorizer(),
            contextProvider = AiContextProvider { AiContextSnapshot(generatedAtText = "测试快照") },
            zoneId = ZONE,
        )
        proposals = AiProposalUseCases(
            chat = chat,
            store = proposalStore,
            taskUseCases = TaskUseCases(
                repository = tasksRepository,
                idGen = { "task-" + idSeq.incrementAndGet() },
                now = { clock },
                zoneId = ZONE.id,
            ),
            noteUseCases = NoteUseCases(
                repository = notesRepository,
                idGen = { "note-" + idSeq.incrementAndGet() },
                now = { clock },
            ),
            calendarUseCases = CalendarUseCases(
                repository = calendarRepository,
                idGen = { "entry-" + idSeq.incrementAndGet() },
                now = { clock },
                zoneId = ZONE.id,
            ),
            calendarReminderUseCases = CalendarReminderUseCases(
                repository = calendarRemindersRepository,
                calendarRepository = calendarRepository,
                scheduler = NoopAlarmScheduler(),
                idGen = { "calrem-" + idSeq.incrementAndGet() },
                now = { clock },
            ),
            targetMatcher = AiProposalTargetMatcher(
                tasksRepository = tasksRepository,
                notesRepository = notesRepository,
                calendarRepository = calendarRepository,
            ),
            // T25/#26：确认系统闹钟提案经既有 ClockHandoffUseCases 真实入口交接
            clockHandoffUseCases = clockHandoffUseCases,
            idGen = { "proposal-" + idSeq.incrementAndGet() },
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
        .body(
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"${escapeJson(fullText)}\"}}]}",
        )
        .build()

    /** 双响应：第一次流式 + 自动重试（非流式），两次均返回同一非法提案。 */
    private fun enqueueInvalidTwice(badProposalText: String) {
        server.enqueue(sseProposal(badProposalText))
        server.enqueue(completionProposal(badProposalText))
    }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private fun proposalBlock(vararg items: String): String =
        "已为你起草操作提案，请确认。\n\u0060\u0060\u0060json\n{\"proposals\":[${items.joinToString(",")} ]}\n\u0060\u0060\u0060"

    /** 模型按协议输出的系统闹钟提案条目。 */
    private fun alarmJson(
        title: String? = "起床",
        time: String? = "07:30",
        date: String? = null,
        weekdays: List<String>? = null,
        extra: String? = null,
    ): String = buildString {
        append("{\"kind\":\"alarm\"")
        if (title != null) append(",\"title\":\"${escapeJson(title)}\"")
        if (time != null) append(",\"time\":\"$time\"")
        if (date != null) append(",\"date\":\"$date\"")
        if (weekdays != null) append(",\"weekdays\":[${weekdays.joinToString(",") { "\"$it\"" }}]")
        if (extra != null) append(",$extra")
        append("}")
    }

    private suspend fun send(text: String = "明天早上七点半叫我起床"): AiProposalSendOutcome =
        proposals.sendAndIngest(null, text) {}.getOrThrow()

    private suspend fun sendAlarmProposal(): com.zhideer.app.domain.model.AiProposal =
        (send() as AiProposalSendOutcome.ProposalCreated).proposal

    private suspend fun clockHandoffs() = db.clockHandoffDao().observeAll().first()

    private suspend fun assertNoSelfReminder() {
        assertTrue("不得自动创建待办提醒", db.reminderDao().listAll().isEmpty())
        assertTrue("不得自动创建日程提醒", db.calendarReminderDao().listAll().isEmpty())
    }

    // ---------- 提案预览与确认前不跳转 ----------

    @Test
    fun 文字指令生成系统闹钟提案并明确区分系统时钟与知时鹿提醒() = runBlocking {
        server.enqueue(sseProposal(proposalBlock(alarmJson(time = "07:30", title = "起床"))))

        val proposal = sendAlarmProposal()
        val item = proposal.items.single()
        assertEquals(AiProposalKind.ALARM, item.kind)
        assertEquals(7, item.alarmHour)
        assertEquals(30, item.alarmMinute)
        assertNull(item.alarmEpochDay)
        assertTrue(item.alarmWeekdays.isEmpty())
        assertEquals("起床", item.title)
        // 确定时间与重复规则在预览中可见
        assertEquals("07:30", proposalAlarmTimeText(item))
        assertEquals("仅响一次", proposalAlarmRepeatText(item))
        val lines = proposalAlarmDetailLines(item)
        assertTrue("预览应标明来源是系统时钟：" + lines, lines.any { it.contains("系统时钟") })
        assertTrue("预览应说明无法核实是否保存：" + lines, lines.any { it.contains("无法核实") })
        assertTrue("预览应与知时鹿提醒对比：" + lines, lines.any { it.contains("知时鹿提醒") })
        assertTrue("预览应说明确认前不跳转：" + lines, lines.any { it.contains("确认前") && it.contains("不会跳转") })
        // 状态机：仅落待确认提案；确认前绝不跳转、不写交接记录、不创建任何提醒
        assertEquals(AiProposalStatus.PENDING, proposal.status)
        assertEquals(0, clockLauncher.createCalls)
        assertEquals(0, clockLauncher.listCalls)
        assertTrue(clockHandoffs().isEmpty())
        assertNoSelfReminder()
        assertTrue(proposal.createdTaskIds.isEmpty())
        assertTrue(proposal.createdScheduleIds.isEmpty())
        // 会话摘要同样如实说明来源与「待核对」边界，不出现已保存式肯定
        val session = chat.openSession(proposal.sessionId!!)!!
        val summary = session.messages.last { it.role == AiChatRole.ASSISTANT }.content
        assertTrue("摘要应标明系统闹钟：" + summary, summary.contains("系统闹钟"))
        assertTrue("摘要应说明来源与核对边界：" + summary, summary.contains("系统时钟"))
        assertFalse("摘要不得宣称已保存：" + summary, summary.contains("已保存"))
    }

    @Test
    fun 确认后发起系统时钟交接并记录待核对而不宣称保存成功() = runBlocking {
        server.enqueue(sseProposal(proposalBlock(alarmJson(time = "06:45", title = "晨跑起床"))))
        val proposal = sendAlarmProposal()

        val confirmed = proposals.confirmProposal(proposal.id).getOrThrow()
        assertEquals(AiProposalStatus.EXECUTED, confirmed.status)
        assertEquals(true, confirmed.executionSucceeded)
        // 平台收到时分与标签；平台只接受时分与星期（不承诺预填/保存）
        val call = clockLauncher.created.single()
        assertEquals(6, call.hour)
        assertEquals(45, call.minute)
        assertEquals("晨跑起床", call.label)
        assertTrue(call.weekdays.isEmpty())
        // 交接记录保持「待核对」，回执如实说明无法核实
        val handoff = clockHandoffs().single()
        assertEquals(ClockHandoffStatus.PENDING_CHECK.name, handoff.status)
        assertEquals("待核对", ClockHandoffStatus.PENDING_CHECK.label)
        assertEquals(listOf(handoff.id), confirmed.createdAlarmHandoffIds)
        val receipt = confirmed.resultMessage!!
        assertTrue("回执应显示待核对：" + receipt, receipt.contains("待核对"))
        assertTrue("回执应说明无法核实：" + receipt, receipt.contains("无法核实"))
        assertTrue("回执应说明只是发起：" + receipt, receipt.contains("已发起"))
        assertFalse("回执不得宣称已保存：" + receipt, receipt.contains("已保存"))
        // 系统闹钟交接绝不自动创建知时鹿提醒（避免双响）
        assertNoSelfReminder()
        assertTrue(confirmed.createdTaskIds.isEmpty())
        assertTrue(confirmed.createdScheduleIds.isEmpty())
        assertTrue(confirmed.createdNoteIds.isEmpty())
    }

    @Test
    fun 重复确认幂等不重复发起系统时钟也不重复记录() = runBlocking {
        server.enqueue(sseProposal(proposalBlock(alarmJson())))
        val proposal = sendAlarmProposal()

        val first = proposals.confirmProposal(proposal.id).getOrThrow()
        val second = proposals.confirmProposal(proposal.id).getOrThrow()

        assertEquals(AiProposalStatus.EXECUTED, second.status)
        assertEquals(first.resultMessage, second.resultMessage)
        assertEquals("重复确认不得重复发起系统时钟", 1, clockLauncher.createCalls)
        assertEquals("重复确认不得重复写交接记录", 1, clockHandoffs().size)
        assertEquals("提案不应被重复创建", 1, proposalStore.load().size)
    }
    // ---------- 澄清、取消与回退 ----------

    @Test
    fun 模糊时间先追问不落提案也不跳转() = runBlocking {
        // 模型按协议规则先追问（没有 JSON 块）：普通回复，不产生提案
        server.enqueue(sseProposal("好的，你想几点起床？请告诉我具体时间，比如 07:30。", chunks = 1))

        val outcome = send("设个闹钟叫我起床")

        assertTrue("模糊时间应先追问而不是生成提案", outcome is AiProposalSendOutcome.PlainReply)
        assertTrue("未确认不得产生提案", proposalStore.load().isEmpty())
        assertEquals(0, clockLauncher.createCalls)
        assertEquals(0, clockLauncher.listCalls)
        assertTrue(clockHandoffs().isEmpty())
        assertNoSelfReminder()
    }

    @Test
    fun 模型给出缺失时间的系统闹钟提案被拒绝且中文提示先确认时间() = runBlocking {
        // 模型违规给出没有 time 的 alarm 条目：解析层整批拒绝（自动重试一次仍失败）
        val bad = proposalBlock(alarmJson(time = null))
        server.enqueue(sseProposal(bad))
        server.enqueue(completionProposal(bad))

        val result = proposals.sendAndIngest(null, "设个闹钟", {})

        assertTrue("缺失响铃时间的提案必须被拒绝", result.isFailure)
        val message = result.exceptionOrNull()!!.message.orEmpty()
        assertTrue("应中文提示先确认具体时间：$message", message.contains("响铃时间") || message.contains("具体几点"))
        assertTrue("不得落提案", proposalStore.load().isEmpty())
        assertEquals(0, clockLauncher.createCalls)
        assertTrue(clockHandoffs().isEmpty())
        assertNoSelfReminder()
    }

    @Test
    fun 忽略系统闹钟提案不发起跳转也不产生交接记录() = runBlocking {
        server.enqueue(sseProposal(proposalBlock(alarmJson())))
        val proposal = sendAlarmProposal()

        val dismissed = proposals.dismissProposal(proposal.id)
        assertTrue(dismissed.isSuccess)
        val expired = proposalStore.load().single()
        assertEquals(AiProposalStatus.EXPIRED, expired.status)
        assertEquals(0, clockLauncher.createCalls)
        assertEquals(0, clockLauncher.listCalls)
        assertTrue(clockHandoffs().isEmpty())
        assertNoSelfReminder()
    }

    @Test
    fun 指定日历日期保留日期且不发起并给出两条回退() = runBlocking {
        server.enqueue(sseProposal(proposalBlock(alarmJson(time = "07:30", date = "2026-11-05", title = "下月早起"))))
        val proposal = sendAlarmProposal()
        val item = proposal.items.single()
        assertEquals(LocalDate.parse("2026-11-05").toEpochDay(), item.alarmEpochDay)
        assertEquals("2026-11-05 07:30", proposalAlarmTimeText(item))
        val lines = proposalAlarmDetailLines(item)
        assertTrue("预览应保留日期：$lines", lines.any { it.contains("2026-11-05") })
        assertTrue("预览应说明平台无法传达日期：$lines", lines.any { it.contains("无法传达日期") })
        assertTrue("预览应给出知时鹿提醒回退：$lines", lines.any { it.contains("知时鹿通知提醒") })
        assertTrue("预览应给出打开系统时钟回退：$lines", lines.any { it.contains("打开系统时钟") })

        // 确认时不得把日期丢掉只传时分：不发起交接、不写交接记录、不创建任何提醒
        val confirmed = proposals.confirmProposal(proposal.id).getOrThrow()
        assertEquals(AiProposalStatus.EXECUTED, confirmed.status)
        assertEquals("全部失败也是真实结果，不标成功", false, confirmed.executionSucceeded)
        assertTrue("日期必须原样保留在提案记录中", confirmed.items.single().alarmEpochDay == item.alarmEpochDay)
        assertEquals("指定日期不得发起系统时钟创建", 0, clockLauncher.createCalls)
        assertTrue("指定日期不得产生待核对交接记录", clockHandoffs().isEmpty())
        assertTrue(confirmed.createdAlarmHandoffIds.isEmpty())
        assertNoSelfReminder()
        val receipt = confirmed.resultMessage.orEmpty()
        assertTrue("回执应说明限制并保留日期：$receipt", receipt.contains("2026-11-05") && receipt.contains("无法传达"))
        assertTrue("回执应给出两条回退：$receipt",
            receipt.contains("知时鹿通知提醒") && receipt.contains("打开系统时钟"))
        assertFalse("回执不得宣称已保存：$receipt", receipt.contains("已保存"))
    }

    @Test
    fun 无系统时钟处理器时禁用发起并给中文回退且不写记录() = runBlocking {
        server.enqueue(sseProposal(proposalBlock(alarmJson())))
        val proposal = sendAlarmProposal()
        // 独立环境（模拟器 D10）：系统无 SET_ALARM/SHOW_ALARMS 处理器
        clockLauncher.capability = ClockCapability(canCreateAlarm = false, canOpenAlarmList = false)
        clockLauncher.listResult = ClockLaunchResult.NoHandler
        assertEquals(false, proposals.clockCapability()?.canCreateAlarm)

        val confirmed = proposals.confirmProposal(proposal.id).getOrThrow()
        assertEquals(AiProposalStatus.EXECUTED, confirmed.status)
        assertEquals(false, confirmed.executionSucceeded)
        assertEquals("无处理器不得发起系统时钟", 0, clockLauncher.createCalls)
        assertTrue("无处理器不得写待核对记录", clockHandoffs().isEmpty())
        val receipt = confirmed.resultMessage.orEmpty()
        assertTrue("应中文说明没有可用时钟应用：$receipt", receipt.contains("没有可用的时钟应用"))
        assertTrue("应给出回退：$receipt", receipt.contains("回退"))
        // 回退选项②：打开系统时钟也失败时给中文提示，不崩溃
        val listResult = proposals.openSystemClock()
        assertTrue(listResult.isFailure)
        assertTrue(
            "打开时钟失败应中文提示手动打开：${listResult.exceptionOrNull()?.message}",
            listResult.exceptionOrNull()?.message.orEmpty().contains("手动打开系统时钟"),
        )
        assertNoSelfReminder()
    }

    @Test
    fun 重复规则系统闹钟解析星期并交给平台且在预览可见() = runBlocking {
        server.enqueue(
            sseProposal(proposalBlock(alarmJson(time = "06:30", weekdays = listOf("monday", "wednesday")))),
        )
        val proposal = sendAlarmProposal()
        val item = proposal.items.single()
        assertEquals(listOf(1, 3), item.alarmWeekdays)
        assertEquals("每周 一、三", proposalAlarmRepeatText(item))
        assertTrue(proposalAlarmDetailLines(item).any { it.contains("每周 一、三") })
        assertEquals(0, clockLauncher.createCalls)

        val confirmed = proposals.confirmProposal(proposal.id).getOrThrow()
        assertEquals(true, confirmed.executionSucceeded)
        val call = clockLauncher.created.single()
        assertEquals(setOf(1, 3), call.weekdays)
        assertTrue(confirmed.resultMessage.orEmpty().contains("每周 一、三"))
        assertTrue(clockHandoffs().single().weekdays == "1,3")
    }

    @Test
    fun 回退打开系统时钟不写记录不创建提醒并提示双响() = runBlocking {
        server.enqueue(sseProposal(proposalBlock(alarmJson(date = "2026-11-05"))))
        val proposal = sendAlarmProposal()

        // 回退选项②：打开系统时钟供手动选择日期；不写交接记录、不创建任何提醒
        val opened = proposals.openSystemClock()
        assertTrue(opened.isSuccess)
        assertEquals(1, clockLauncher.listCalls)
        assertTrue(clockHandoffs().isEmpty())
        assertNoSelfReminder()
        val hint = proposals.clockFallbackHint()
        assertTrue("回退说明应含系统时钟：$hint", hint.contains("系统时钟"))
        assertTrue("回退说明应含知时鹿提醒：$hint", hint.contains("提醒"))
        assertTrue("回退说明应警告双响：$hint", hint.contains("重复响铃"))

        proposals.dismissProposal(proposal.id)
        assertTrue(clockHandoffs().isEmpty())
        assertNoSelfReminder()
    }

    // ---------- 语音与文字走同一提案入口 ----------

    private class FakeSpeechInput : SpeechInput {
        val listeners = mutableListOf<SpeechInput.Listener>()
        override fun isAvailable(): Boolean = true
        override fun start(listener: SpeechInput.Listener) {
            listeners += listener
        }
        override fun stop() = Unit
        override fun cancel() = Unit
        override fun release() = Unit
        fun latest(): SpeechInput.Listener = listeners.last()
    }

    @Test
    fun 语音转写先成可编辑草稿点击提交后与文字走同一系统闹钟提案入口() = runBlocking {
        val speech = FakeSpeechInput()
        val voice = VoiceInputController(speech)
        assertTrue(voice.startRecording())
        voice.releaseAndTranscribe()
        speech.latest().onResult("明早七点叫我起床")
        val transcribed = voice.state.value
        assertTrue("松手识别后应为已转写草稿：$transcribed", transcribed is VoiceInputState.Transcribed)
        // 转写只是草稿：未点击发送前绝不生成提案或跳转
        assertTrue(proposalStore.load().isEmpty())
        assertEquals(0, clockLauncher.createCalls)
        assertTrue(clockHandoffs().isEmpty())

        // 点击发送：与键盘文字完全相同的一条 sendAndIngest 提案路径
        server.enqueue(sseProposal(proposalBlock(alarmJson(time = "07:00"))))
        val text = (transcribed as VoiceInputState.Transcribed).text
        voice.onDraftSent()
        val proposal = (proposals.sendAndIngest(null, text) {}).getOrThrow() as AiProposalSendOutcome.ProposalCreated
        assertEquals(AiProposalKind.ALARM, proposal.proposal.items.single().kind)
        assertEquals(7, proposal.proposal.items.single().alarmHour)
        assertTrue(voice.state.value is VoiceInputState.Idle)
        // 提交前仍不跳转：需再次确认
        assertEquals(0, clockLauncher.createCalls)
    }
    // ---------- 协议边界：系统闹钟不是知时鹿提醒 ----------

    @Test
    fun 系统闹钟条目携带提醒或日程字段时整批拒绝() = runBlocking {
        val bad = proposalBlock(
            alarmJson(
                extra = "\"reminder\":{\"type\":\"relative\",\"offsetMillis\":900000}," +
                    "\"start\":\"2026-11-05T07:00\"",
            ),
        )
        enqueueInvalidTwice(bad)

        val result = proposals.sendAndIngest(null, "七点闹钟，并提前提醒我", {})

        assertTrue("系统闹钟混入提醒字段必须整批拒绝", result.isFailure)
        val message = result.exceptionOrNull()!!.message.orEmpty()
        assertTrue("应中文说明系统闹钟不是知时鹿提醒：$message", message.contains("系统闹钟"))
        assertTrue("不得落提案", proposalStore.load().isEmpty())
        assertEquals(0, clockLauncher.createCalls)
        assertTrue(clockHandoffs().isEmpty())
        assertNoSelfReminder()
    }

    @Test
    fun 系统闹钟同时指定日期与星期时整批拒绝() = runBlocking {
        val bad = proposalBlock(alarmJson(date = "2026-11-05", weekdays = listOf("monday")))
        enqueueInvalidTwice(bad)

        val result = proposals.sendAndIngest(null, "下个月 5 号每周一早上七点叫我", {})

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message.orEmpty()
        assertTrue("应说明日期与星期不能同时给出：$message", message.contains("日期") && message.contains("星期"))
        assertTrue(proposalStore.load().isEmpty())
        assertEquals(0, clockLauncher.createCalls)
        assertTrue(clockHandoffs().isEmpty())
    }

    @Test
    fun 模型把日期并进时间字段时仍保留日期且确认不发起() = runBlocking {
        // 容忍模型输出 yyyy-MM-ddTHH:mm：日期不能被静默丢弃
        server.enqueue(sseProposal(proposalBlock(alarmJson(time = "2026-11-05T07:30"))))
        val proposal = sendAlarmProposal()
        val item = proposal.items.single()
        assertEquals(LocalDate.parse("2026-11-05").toEpochDay(), item.alarmEpochDay)
        assertEquals("2026-11-05 07:30", proposalAlarmTimeText(item))

        val confirmed = proposals.confirmProposal(proposal.id).getOrThrow()
        assertEquals(0, clockLauncher.createCalls)
        assertTrue(clockHandoffs().isEmpty())
        assertTrue(confirmed.resultMessage.orEmpty().contains("2026-11-05"))
    }
}


