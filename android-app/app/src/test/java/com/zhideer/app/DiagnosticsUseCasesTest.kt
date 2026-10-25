package com.zhideer.app

import com.zhideer.app.data.diagnostics.DiagnosticsFailureStore
import com.zhideer.app.domain.model.ClockHandoff
import com.zhideer.app.domain.model.ClockHandoffStatus
import com.zhideer.app.domain.model.DiagnosticsFailureCategory
import com.zhideer.app.domain.model.ReminderEnvironmentStatus
import com.zhideer.app.domain.model.ReminderOwner
import com.zhideer.app.domain.model.ReminderRestoreReport
import com.zhideer.app.domain.model.ReminderRestoreTrigger
import com.zhideer.app.domain.model.ReminderScheduleEntry
import com.zhideer.app.domain.model.ReminderScheduleState
import com.zhideer.app.domain.model.SpeechEngineAvailability
import com.zhideer.app.domain.repository.ClockHandoffRepository
import com.zhideer.app.domain.repository.ReminderScheduleLogRepository
import com.zhideer.app.domain.usecase.AiConfigView
import com.zhideer.app.domain.usecase.DiagnosticsUseCases
import com.zhideer.app.platform.ReminderCapabilityProbe
import com.zhideer.app.platform.VoiceEngineProbe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.ZoneId

/**
 * T35/#36 验收测试：查看权限和故障诊断。
 *
 * 语义边界（规格用户故事 40/48/54/67/89）：
 * - 每次刷新都重新探测真实状态，不保留过期成功；
 * - 系统时钟待核对与知时鹿自有提醒的调度状态分开展示，绝不把「已调度/待核对」当成已验证成功；
 * - 未就绪项给出系统设置修复入口与手动回退说明，拒绝授权不影响手动功能；
 * - 失败记录与导出文本脱敏：不含 API Key、原始录音或完整私人事项内容。
 *
 * 全部走用例入口 + 替身能力探针；真实设备提醒恢复由 DiagnosticsReminderIntegrationTest 覆盖。
 */
class DiagnosticsUseCasesTest {

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
        const val HOUR: Long = 3_600_000L
    }

    private lateinit var tempDir: File
    private lateinit var failureStore: DiagnosticsFailureStore
    private lateinit var probe: FakeReminderProbe
    private lateinit var voice: FakeVoiceProbe
    private lateinit var scheduleLog: FakeScheduleLogRepository
    private lateinit var clockRepo: FakeClockHandoffRepository
    private lateinit var useCases: DiagnosticsUseCases
    private var clock: Long = 0L
    private var idSeq = 0
    private var config: AiConfigView? =
        AiConfigView(model = "deepseek-chat", baseUrl = "https://api.deepseek.com", hasApiKey = true, maskedApiKey = "sk-***abcd")
    private var connection: Result<String> = Result.success("连接成功：模型 deepseek-chat 可用")
    private var reportOverride: (suspend () -> ReminderRestoreReport)? = null

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "t35-diagnostics-${System.nanoTime()}")
        check(tempDir.mkdirs() || tempDir.isDirectory) { "无法创建测试目录" }
        failureStore = DiagnosticsFailureStore(File(tempDir, "diagnostics_failures.preferences_pb"))
        probe = FakeReminderProbe()
        voice = FakeVoiceProbe()
        scheduleLog = FakeScheduleLogRepository()
        clockRepo = FakeClockHandoffRepository()
        clock = 1_000L
        useCases = DiagnosticsUseCases(
            capabilityProbe = probe,
            refreshReminders = { reportOverride?.invoke() ?: report() },
            scheduleLog = scheduleLog,
            clockHandoffRepository = clockRepo,
            loadModelConfig = { config },
            testModelConnection = { connection },
            voiceProbe = voice,
            failureStore = failureStore,
            idGen = { "failure-${idSeq++}" },
            now = { clock },
            zoneId = ZONE,
            appVersion = "0.1.0",
        )
    }

    @After
    fun tearDown() {
        failureStore.close()
        tempDir.deleteRecursively()
    }

    // ---------- 夹具 ----------

    private fun entry(
        key: String,
        title: String,
        at: Long,
        state: ReminderScheduleState = ReminderScheduleState.PENDING,
    ) = ReminderScheduleEntry(
        reminderKey = key,
        ownerId = "owner-$key",
        ownerTitle = title,
        scheduledAtMillis = at,
        state = state,
        recordedAtMillis = clock,
    )

    private fun report(
        next: ReminderScheduleEntry? = null,
        missed: List<ReminderScheduleEntry> = emptyList(),
        notReady: Int = 0,
        trigger: ReminderRestoreTrigger = ReminderRestoreTrigger.MANUAL,
        restoredAt: Long = clock,
        environment: ReminderEnvironmentStatus = ReminderEnvironmentStatus(true, true, true),
    ) = ReminderRestoreReport(
        trigger = trigger,
        restoredAtMillis = restoredAt,
        scannedCount = 1,
        futureScheduledCount = if (next != null) 1 else 0,
        notReadyCount = notReady,
        skippedCount = 0,
        newlyMissedCount = 0,
        missed = missed,
        environment = environment,
        zoneAnchors = emptyList(),
        nextReminder = next,
        messages = emptyList(),
    )

    private fun handoff(
        id: String,
        status: ClockHandoffStatus,
        requestedAt: Long,
    ) = ClockHandoff(
        id = id,
        hour = 7,
        minute = 30,
        label = "起床",
        requestedAtMillis = requestedAt,
        status = status,
    )

    private class FakeReminderProbe : ReminderCapabilityProbe {
        var notificationsAllowed = true
        var exactAlarmsAllowed = true
        var batteryOptimizationsIgnored = true
        var calls = 0

        override fun snapshot(): ReminderEnvironmentStatus {
            calls++
            return ReminderEnvironmentStatus(
                notificationsAllowed = notificationsAllowed,
                exactAlarmsAllowed = exactAlarmsAllowed,
                batteryOptimizationsIgnored = batteryOptimizationsIgnored,
            )
        }
    }

    private class FakeVoiceProbe : VoiceEngineProbe {
        var recognitionAvailable = true
        var textToSpeechStatus = SpeechEngineAvailability.AVAILABLE
        var failProbe = false
        var textToSpeechCalls = 0

        override fun recognitionAvailable(): Boolean {
            if (failProbe) throw IllegalStateException("识别能力探测崩溃")
            return recognitionAvailable
        }

        override suspend fun textToSpeechStatus(): SpeechEngineAvailability {
            textToSpeechCalls++
            if (failProbe) throw IllegalStateException("播报引擎探测崩溃")
            return textToSpeechStatus
        }
    }

    private class FakeScheduleLogRepository : ReminderScheduleLogRepository {
        val pending = mutableListOf<ReminderScheduleEntry>()
        val missed = mutableListOf<ReminderScheduleEntry>()

        override suspend fun recordPending(
            reminderKey: String,
            ownerId: String,
            ownerTitle: String,
            scheduledAtMillis: Long,
            recordedAtMillis: Long,
        ) = Unit

        override suspend fun removePending(reminderKey: String) = Unit

        override suspend fun markDelivered(
            reminderKey: String,
            ownerId: String,
            ownerTitle: String,
            scheduledAtMillis: Long,
            deliveredAtMillis: Long,
        ) = Unit

        override suspend fun markMissedBefore(cutoffMillis: Long, detectedAtMillis: Long): List<ReminderScheduleEntry> =
            emptyList()

        override suspend fun listPending(): List<ReminderScheduleEntry> = pending.toList()

        override suspend fun listTerminalInstants(): Set<String> = emptySet()

        override suspend fun listOutstandingMissed(): List<ReminderScheduleEntry> = missed.toList()

        override fun observeOutstandingMissed(): Flow<List<ReminderScheduleEntry>> = flowOf(missed.toList())

        override suspend fun acknowledgeMissed(acknowledgedAtMillis: Long): Int = missed.size
    }

    private class FakeClockHandoffRepository : ClockHandoffRepository {
        val handoffs = mutableListOf<ClockHandoff>()

        override fun observeAll(): Flow<List<ClockHandoff>> = flowOf(handoffs.toList())

        override suspend fun upsert(handoff: ClockHandoff) {
            handoffs.removeAll { it.id == handoff.id }
            handoffs += handoff
        }

        override suspend fun delete(id: String) {
            handoffs.removeAll { it.id == id }
        }
    }

    // ---------- 验收 ----------

    @Test
    fun 刷新展示权限模型语音下次自有提醒与最近失败() = runBlocking {
        val nextAt = clock + 2 * HOUR
        val next = entry("rem-1", "装修新房", nextAt)
        scheduleLog.pending += next
        scheduleLog.missed += entry("rem-2", "交水电费", clock - HOUR, ReminderScheduleState.MISSED)
        reportOverride = { report(next = next, missed = scheduleLog.missed.toList(), notReady = 1) }

        val snapshot = useCases.refresh()

        assertTrue(snapshot.permissions.notificationsAllowed)
        assertTrue(snapshot.permissions.exactAlarmsAllowed)
        assertFalse(snapshot.permissions.notificationsNeedFix)
        assertFalse(snapshot.permissions.exactAlarmsNeedFix)

        assertTrue(snapshot.model.configured)
        assertEquals("deepseek-chat", snapshot.model.modelName)
        assertFalse(snapshot.model.needsConfig)

        assertEquals(true, snapshot.voice.recognitionAvailable)
        assertEquals(SpeechEngineAvailability.AVAILABLE, snapshot.voice.speechOutput)
        assertFalse(snapshot.voice.speechOutputUnavailable)

        assertEquals(nextAt, snapshot.reminders.nextReminderAtMillis)
        assertEquals(ReminderOwner.TASK, snapshot.reminders.nextReminderOwner)
        assertEquals(1, snapshot.reminders.pendingScheduleCount)
        assertEquals(1, snapshot.reminders.notReadyCount)
        assertEquals(1, snapshot.reminders.outstandingMissedCount)
        assertEquals("手动核对", snapshot.reminders.lastRestoreTriggerLabel)
        assertEquals(clock, snapshot.reminders.lastRestoreAtMillis)

        assertEquals(1, probe.calls)
        assertEquals(1, voice.textToSpeechCalls)
        assertTrue(snapshot.recentFailures.isEmpty())
    }

    @Test
    fun 权限撤销后刷新立即反映真实状态并提供修复与手动回退() = runBlocking {
        val nextAt = clock + 2 * HOUR
        val next = entry("rem-1", "装修新房", nextAt)
        scheduleLog.pending += next
        useCases.refresh()

        // 用户去系统设置撤销通知与精确提醒权限（模拟为探针返回值变化）
        probe.notificationsAllowed = false
        probe.exactAlarmsAllowed = false
        probe.batteryOptimizationsIgnored = false
        reportOverride = { report(next = next, notReady = 1) }

        val snapshot = useCases.refresh()

        assertFalse(snapshot.permissions.notificationsAllowed)
        assertFalse(snapshot.permissions.exactAlarmsAllowed)
        assertFalse(snapshot.permissions.batteryOptimizationsIgnored)
        assertTrue(snapshot.permissions.notificationsNeedFix)
        assertTrue(snapshot.permissions.exactAlarmsNeedFix)
        assertTrue(snapshot.permissions.batteryOptimizationNeedsFix)
        assertTrue(snapshot.permissions.manualFallbackNote.contains("手动"))
        assertEquals(1, snapshot.reminders.notReadyCount)
        assertEquals(2, probe.calls)
        // 自有提醒记录仍在：不会因为撤销权限而删除事项或静默降级
        assertEquals(nextAt, snapshot.reminders.nextReminderAtMillis)
    }


    @Test
    fun 系统时钟待核对与自有提醒调度状态分开且不宣称已保存() = runBlocking {
        val nextAt = clock + 2 * HOUR
        val next = entry("rem-1", "装修新房", nextAt)
        scheduleLog.pending += next
        clockRepo.handoffs += handoff("h-1", ClockHandoffStatus.PENDING_CHECK, clock - 60_000L)
        clockRepo.handoffs += handoff("h-2", ClockHandoffStatus.FAILED, clock - 120_000L)
        reportOverride = { report(next = next) }

        val snapshot = useCases.refresh()

        assertEquals(1, snapshot.clockHandoff.pendingCheckCount)
        assertEquals(1, snapshot.clockHandoff.failedCount)
        assertEquals("待核对", snapshot.clockHandoff.latestStatusLabel)
        assertTrue(snapshot.clockHandoff.needsCheck)
        assertTrue(snapshot.clockHandoff.hasFailure)
        assertTrue(snapshot.clockHandoff.note.contains("不等于已保存"))
        assertEquals(nextAt, snapshot.reminders.nextReminderAtMillis)
        assertTrue(snapshot.reminders.schedulingNote.contains("不等于"))
        assertTrue(
            "交接失败是独立的失败事实",
            snapshot.recentFailures.any { it.category == DiagnosticsFailureCategory.CLOCK_HANDOFF },
        )
        assertFalse(
            "自有提醒不应混入交接失败",
            snapshot.recentFailures.any { it.category == DiagnosticsFailureCategory.REMINDER },
        )

        val text = useCases.exportText(snapshot)
        assertTrue(text.contains("待核对"))
        assertTrue(text.contains("不等于已保存"))
        assertTrue(text.contains("已调度"))
        assertFalse(text.contains("系统闹钟已保存"))
        assertFalse("交接标签属于私人内容，不进入导出", text.contains("起床"))
    }

    @Test
    fun 模型连接失败写入脱敏失败记录且成功不写验证状态() = runBlocking {
        connection = Result.failure(
            IllegalStateException("鉴权失败：API Key sk-abcdef123456789 无效；Bearer abc.def.ghi"),
        )

        val failed = useCases.probeModelConnection()

        assertFalse(failed.success)
        assertFalse("测试结果也不能回显完整 Key", failed.message.contains("abcdef123456789"))
        assertTrue(failed.message.contains("sk-***"))
        val snapshot = useCases.refresh()
        assertEquals(1, snapshot.recentFailures.size)
        val failure = snapshot.recentFailures.first()
        assertEquals(DiagnosticsFailureCategory.MODEL_CONNECTION, failure.category)
        assertFalse(failure.summary.contains("abcdef123456789"))
        assertEquals(listOf(failure), failureStore.load())

        connection = Result.success("连接成功：模型 deepseek-chat 可用")
        val ok = useCases.probeModelConnection()
        assertTrue(ok.success)
        // 成功不是“已验证”持久状态：不新增记录，也不删除历史失败
        assertEquals(1, failureStore.load().size)
        assertFalse(useCases.exportText(snapshot).contains("连接成功"))
    }

    @Test
    fun 导出诊断文本脱敏且不含完整事项内容并写入指定目录() = runBlocking {
        val nextAt = clock + 2 * HOUR
        val next = entry("rem-1", "私人牙科预约", nextAt)
        scheduleLog.pending += next
        clockRepo.handoffs += handoff("h-1", ClockHandoffStatus.PENDING_CHECK, clock - 60_000L)
        reportOverride = { report(next = next) }
        config = AiConfigView(
            model = "deepseek-chat",
            baseUrl = "https://user:secretpass@api.deepseek.com/v1?token=abc#frag",
            hasApiKey = true,
            maskedApiKey = "sk-***abcd",
        )
        connection = Result.failure(IllegalStateException("鉴权失败：API Key sk-secretkey987654"))
        useCases.probeModelConnection()

        val snapshot = useCases.refresh()
        val text = useCases.exportText(snapshot)

        assertTrue(text.contains("知时鹿诊断报告"))
        assertTrue(text.contains("系统权限"))
        assertTrue(text.contains("语音引擎"))
        assertTrue(text.contains("自有提醒"))
        assertTrue(text.contains("系统时钟交接"))
        assertTrue(text.contains("最近失败"))
        assertFalse("事项标题不进入导出", text.contains("私人牙科预约"))
        assertFalse("交接标签不进入导出", text.contains("起床"))
        assertFalse("API Key 不进入导出", text.contains("secretkey987654"))
        assertFalse("URL 凭据不进入导出", text.contains("secretpass"))
        assertFalse("URL 查询参数不进入导出", text.contains("token=abc"))
        assertTrue("导出保留脱敏痕迹", text.contains("sk-***"))
        assertTrue(text.contains("https://api.deepseek.com/v1"))

        val directory = File(tempDir, "export")
        val file = useCases.export(snapshot, directory).getOrThrow()
        assertTrue(file.isFile)
        assertTrue(file.name.startsWith("zhideer-diagnostics-"))
        assertEquals(text, file.readText())
    }


    @Test
    fun 缺少语音引擎时如实显示不可用并给出键盘与文字回退() = runBlocking {
        voice.recognitionAvailable = false
        voice.textToSpeechStatus = SpeechEngineAvailability.ENGINE_MISSING

        val snapshot = useCases.refresh()

        assertEquals(false, snapshot.voice.recognitionAvailable)
        assertTrue(snapshot.voice.recognitionUnavailable)
        assertEquals(SpeechEngineAvailability.ENGINE_MISSING, snapshot.voice.speechOutput)
        assertTrue(snapshot.voice.speechOutputUnavailable)
        assertTrue(snapshot.voice.manualFallbackNote.contains("键盘"))
        assertTrue(snapshot.voice.message!!.contains("键盘"))
        assertTrue(snapshot.recentFailures.isEmpty())
    }

    @Test
    fun 语音探测崩溃写入失败记录且不假装可用() = runBlocking {
        voice.failProbe = true

        val snapshot = useCases.refresh()

        assertNull(snapshot.voice.recognitionAvailable)
        assertEquals(SpeechEngineAvailability.UNKNOWN, snapshot.voice.speechOutput)
        assertTrue(snapshot.voice.speechOutputUnavailable)
        assertNotNull(snapshot.voice.message)
        assertEquals(1, snapshot.recentFailures.count { it.category == DiagnosticsFailureCategory.VOICE_ENGINE })
    }

    @Test
    fun 刷新后不保留过期模型成功状态() = runBlocking {
        connection = Result.success("连接成功：模型 deepseek-chat 可用")
        val tested = useCases.probeModelConnection()
        assertTrue(tested.success)

        val first = useCases.refresh()
        assertTrue(first.model.configured)
        val firstExport = useCases.exportText(first)
        assertFalse("成功不是持久验证状态", firstExport.contains("连接成功"))
        assertTrue(firstExport.contains("刷新后不保留旧的成功状态"))

        config = null
        val second = useCases.refresh()
        assertFalse(second.model.configured)
        assertTrue(second.model.needsConfig)
        assertNull(second.model.modelName)
        assertTrue(useCases.exportText(second).contains("未配置"))
    }
}
