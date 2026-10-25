package com.zhideer.app.domain.usecase

import com.zhideer.app.data.diagnostics.DiagnosticsFailureStore
import com.zhideer.app.domain.model.ClockHandoff
import com.zhideer.app.domain.model.ClockHandoffStatus
import com.zhideer.app.domain.model.DiagnosticsClockHandoff
import com.zhideer.app.domain.model.DiagnosticsFailure
import com.zhideer.app.domain.model.DiagnosticsFailureCategory
import com.zhideer.app.domain.model.DiagnosticsModel
import com.zhideer.app.domain.model.DiagnosticsPermissions
import com.zhideer.app.domain.model.DiagnosticsReminders
import com.zhideer.app.domain.model.DiagnosticsSnapshot
import com.zhideer.app.domain.model.DiagnosticsVoice
import com.zhideer.app.domain.model.ModelConnectionTest
import com.zhideer.app.domain.model.ReminderEnvironmentStatus
import com.zhideer.app.domain.model.ReminderOwner
import com.zhideer.app.domain.model.ReminderRestoreReport
import com.zhideer.app.domain.model.ReminderScheduleEntry
import com.zhideer.app.domain.model.SpeechEngineAvailability
import com.zhideer.app.domain.repository.ClockHandoffRepository
import com.zhideer.app.domain.repository.ReminderScheduleLogRepository
import com.zhideer.app.platform.ReminderCapabilityProbe
import com.zhideer.app.platform.VoiceEngineProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.CoroutineContext

/**
 * 权限与故障诊断用例入口（T35/#36，规格用户故事 40、48、54、67、89）。
 *
 * 设计边界：
 * - 只读事实：权限探针、语音引擎探针、模型配置（掩码视图）、#11 调度台账、#25 交接记录；
 *   诊断页的「重新核对」复用 #11 的恢复入口，不另建调度路径；
 * - 每次 [refresh] 都重新探测，绝不缓存过期成功；模型连接成功只保留在页面会话内；
 * - 自有提醒（已调度/未就绪/错过）与系统时钟交接（待核对/失败）分成两个区块，
 *   不使用「已保存」「已准时」等超出证据的说法；
 * - 失败记录写入前统一脱敏，导出文本不含 API Key、原始录音或完整事项内容，且不自动外发；
 * - 未就绪项提供修复入口所需的状态（[DiagnosticsPermissions] 的 *NeedFix 等）与手动回退说明。
 *
 * @param refreshReminders 复用 #11 的恢复入口（重建未来调度并返回报告）；诊断只展示其结果。
 * @param capabilityProbe 系统提醒能力探针（#11）。
 * @param loadModelConfig 只读模型配置掩码视图（#18），绝不读取明文 Key。
 * @param testModelConnection 复用 #18 的连接测试（发送一条极短请求）。
 * @param voiceProbe 语音识别/中文播报能力探测（#24）。
 */
class DiagnosticsUseCases(
    private val capabilityProbe: ReminderCapabilityProbe,
    private val refreshReminders: suspend () -> ReminderRestoreReport,
    private val scheduleLog: ReminderScheduleLogRepository,
    private val clockHandoffRepository: ClockHandoffRepository,
    private val loadModelConfig: suspend () -> AiConfigView?,
    private val testModelConnection: suspend () -> Result<String>,
    private val voiceProbe: VoiceEngineProbe,
    private val failureStore: DiagnosticsFailureStore,
    private val idGen: () -> String,
    private val now: () -> Long,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val appVersion: String = "0.1.0",
    private val ioDispatcher: CoroutineContext = Dispatchers.IO,
) {

    private val mutex = Mutex()
    private val _snapshot = MutableStateFlow<DiagnosticsSnapshot?>(null)

    /** 最近一次诊断快照（界面观察；未刷新过为 null）。 */
    val snapshot: StateFlow<DiagnosticsSnapshot?> = _snapshot.asStateFlow()

    /**
     * 重新探测并核对：提醒恢复（#11）→ 权限/语音/模型/交接事实 → 最近失败。
     * 串行执行，避免页面进入与回到前台并发刷新互相覆盖。
     */
    suspend fun refresh(): DiagnosticsSnapshot = mutex.withLock {
        val capturedAt = now()

        val permissions = probePermissions()
        val report = runReminderRefresh()
        val pending = runCatching { scheduleLog.listPending() }.getOrDefault(emptyList())
        val missed = runCatching { scheduleLog.listOutstandingMissed() }.getOrDefault(emptyList())
        val next = report?.nextReminder ?: pending.minByOrNull { it.scheduledAtMillis }
        val reminders = DiagnosticsReminders(
            nextReminderAtMillis = next?.scheduledAtMillis,
            nextReminderOwner = next?.let { ownerOf(it) },
            pendingScheduleCount = pending.size,
            notReadyCount = report?.notReadyCount ?: 0,
            outstandingMissedCount = report?.missed?.size ?: missed.size,
            lastRestoreTriggerLabel = report?.trigger?.label,
            lastRestoreAtMillis = report?.restoredAtMillis,
        )

        val model = loadModel()
        val voice = probeVoice()
        val handoffs = runCatching { clockHandoffRepository.observeAll().first() }.getOrDefault(emptyList())
        val clockHandoff = clockHandoffOf(handoffs)

        val snapshot = DiagnosticsSnapshot(
            capturedAtMillis = capturedAt,
            permissions = permissions,
            model = model,
            voice = voice,
            reminders = reminders,
            clockHandoff = clockHandoff,
            recentFailures = recentFailures(handoffs),
        )
        _snapshot.value = snapshot
        snapshot
    }

    /**
     * 测试模型连接：成功只作为本次页面会话内的结果返回（不写入任何“已验证”状态）；
     * 失败写入一条脱敏的最近失败记录，供稍后排查。未配置时给出配置指引而非失败记录。
     */
    suspend fun probeModelConnection(): ModelConnectionTest {
        val view = loadModelSafely()
        if (view == null) {
            return ModelConnectionTest(
                testedAtMillis = now(),
                success = false,
                message = "尚未配置模型服务：请在「小鹿 → 设置」中填写 API Key、模型与 Base URL；" +
                    "本地手动新增、查看和编辑功能仍可用。",
            )
        }
        val result = try {
            testModelConnection()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.failure(t)
        }
        val testedAt = now()
        return result.fold(
            onSuccess = { message ->
                ModelConnectionTest(testedAt, true, DiagnosticsRedaction.redact(message))
            },
            onFailure = { error ->
                val summary = DiagnosticsRedaction.redact(
                    "模型连接失败：${error.message ?: error.javaClass.simpleName}",
                )
                recordFailure(DiagnosticsFailureCategory.MODEL_CONNECTION, summary)
                ModelConnectionTest(testedAt, false, summary)
            },
        )
    }

    /** 清空用户可见的失败记录；交接失败是台账事实，会按记录重新列出。 */
    suspend fun clearFailures() {
        failureStore.clear()
        val handoffs = runCatching { clockHandoffRepository.observeAll().first() }.getOrDefault(emptyList())
        _snapshot.value = _snapshot.value?.copy(recentFailures = derivedHandoffFailures(handoffs))
    }

    /** 导出脱敏诊断文本到指定目录（纯文本，不自动分享/发送）；文件名含时间戳。 */
    suspend fun export(snapshot: DiagnosticsSnapshot, directory: File): Result<File> = runCatching {
        withContext(ioDispatcher) {
            if (!directory.isDirectory) directory.mkdirs()
            val stamp = Instant.ofEpochMilli(now()).atZone(zoneId)
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val file = File(directory, "zhideer-diagnostics-$stamp.txt")
            file.writeText(exportText(snapshot))
            file
        }
    }

    /** 组装脱敏诊断文本：只含状态、数量与时间，不含事项标题、闹钟标签、API Key 或录音。 */
    fun exportText(snapshot: DiagnosticsSnapshot): String = buildString {
        val permissions = snapshot.permissions
        val reminders = snapshot.reminders
        val clock = snapshot.clockHandoff
        appendLine("知时鹿诊断报告（脱敏）")
        appendLine("生成时间：${formatAt(snapshot.capturedAtMillis)}（时区 ${zoneId.id}）")
        appendLine("应用版本：$appVersion")
        appendLine()
        appendLine("—— 系统权限 ——")
        appendLine("通知：${if (permissions.notificationsAllowed) "允许" else "不可用（提醒可能不可见）"}")
        appendLine("精确提醒：${if (permissions.exactAlarmsAllowed) "允许" else "不可用（提醒可能无法准时）"}")
        appendLine("电池优化：${if (permissions.batteryOptimizationsIgnored) "不受限制" else "受限（可能在后台被延迟）"}")
        appendLine(permissions.manualFallbackNote)
        appendLine()
        appendLine("—— 模型连接 ——")
        if (snapshot.model.configured) {
            appendLine("配置：已配置（模型 ${sanitize(snapshot.model.modelName)}）")
            appendLine("Base URL：${sanitizeUrl(snapshot.model.baseUrl)}")
        } else {
            appendLine("配置：未配置（AI 功能不可用，本地手动功能仍可用）")
        }
        appendLine("说明：连接测试结果只在当前页面会话内有效，刷新后不保留旧的成功状态。")
        appendLine()
        appendLine("—— 语音引擎 ——")
        appendLine("语音识别：${recognitionText(snapshot.voice.recognitionAvailable)}")
        appendLine("中文播报：${speechOutputText(snapshot.voice.speechOutput)}")
        appendLine(snapshot.voice.manualFallbackNote)
        snapshot.voice.message?.let { appendLine("提示：${DiagnosticsRedaction.redact(it)}") }
        appendLine()
        appendLine("—— 知时鹿自有提醒（已调度 ≠ 已投递）——")
        if (reminders.nextReminderAtMillis != null) {
            appendLine(
                "下次提醒：${formatAt(reminders.nextReminderAtMillis)}" +
                    "（${ownerText(reminders.nextReminderOwner)}）",
            )
        } else {
            appendLine("下次提醒：无")
        }
        appendLine(
            "待投递调度：${reminders.pendingScheduleCount} 条；" +
                "未就绪：${reminders.notReadyCount} 条；未确认错过：${reminders.outstandingMissedCount} 条",
        )
        appendLine(
            "最近核对：${reminders.lastRestoreTriggerLabel ?: "尚未核对"}" +
                (reminders.lastRestoreAtMillis?.let { "（${formatAt(it)}）" } ?: ""),
        )
        appendLine(reminders.schedulingNote)
        appendLine()
        appendLine("—— 系统时钟交接（与自有提醒分开）——")
        appendLine("待核对：${clock.pendingCheckCount} 条；失败/不可用：${clock.failedCount} 条")
        appendLine("最近状态：${clock.latestStatusLabel ?: "无记录"}")
        appendLine(clock.note)
        appendLine()
        appendLine("—— 最近失败 ——")
        if (snapshot.recentFailures.isEmpty()) {
            appendLine("无记录。")
        } else {
            snapshot.recentFailures.forEach { failure ->
                appendLine(
                    "[${failure.category.label}] ${formatAt(failure.occurredAtMillis)} " +
                        DiagnosticsRedaction.redact(failure.summary),
                )
            }
        }
        appendLine()
        appendLine(
            "说明：本报告已脱敏，不含 API Key、原始录音或完整待办/日程内容；" +
                "导出不会自动发送，请自行决定是否分享。",
        )
    }

    // ---------- 内部 ----------

    private suspend fun probePermissions(): DiagnosticsPermissions {
        val environment = try {
            capabilityProbe.snapshot()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            recordFailure(
                DiagnosticsFailureCategory.REMINDER,
                "提醒能力探测失败：${t.message ?: t.javaClass.simpleName}",
            )
            ReminderEnvironmentStatus(
                notificationsAllowed = false,
                exactAlarmsAllowed = false,
                batteryOptimizationsIgnored = false,
            )
        }
        return DiagnosticsPermissions(
            notificationsAllowed = environment.notificationsAllowed,
            exactAlarmsAllowed = environment.exactAlarmsAllowed,
            batteryOptimizationsIgnored = environment.batteryOptimizationsIgnored,
        )
    }

    private suspend fun runReminderRefresh(): ReminderRestoreReport? = try {
        refreshReminders()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        recordFailure(
            DiagnosticsFailureCategory.REMINDER,
            "提醒核对失败：${t.message ?: t.javaClass.simpleName}",
        )
        null
    }

    private suspend fun loadModel(): DiagnosticsModel {
        val view = loadModelSafely() ?: return DiagnosticsModel(configured = false)
        return DiagnosticsModel(
            configured = true,
            modelName = view.model,
            baseUrl = view.baseUrl,
        )
    }

    private suspend fun loadModelSafely(): AiConfigView? = try {
        loadModelConfig()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        recordFailure(
            DiagnosticsFailureCategory.MODEL_CONNECTION,
            "模型配置读取失败：${t.message ?: t.javaClass.simpleName}",
        )
        null
    }

    private suspend fun probeVoice(): DiagnosticsVoice {
        var recognition: Boolean? = null
        var speechOutput = SpeechEngineAvailability.UNKNOWN
        var problem: String? = null
        try {
            recognition = voiceProbe.recognitionAvailable()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            problem = "语音识别能力探测失败：${t.message ?: t.javaClass.simpleName}"
        }
        try {
            speechOutput = voiceProbe.textToSpeechStatus()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            val failure = "中文播报能力探测失败：${t.message ?: t.javaClass.simpleName}"
            problem = problem?.let { "$it；$failure" } ?: failure
        }
        if (problem != null) {
            recordFailure(DiagnosticsFailureCategory.VOICE_ENGINE, problem)
        }
        val message = when {
            problem != null -> "语音能力探测失败：${DiagnosticsRedaction.redact(problem)}。键盘输入与文字回复仍可用。"
            speechOutput == SpeechEngineAvailability.UNKNOWN ->
                "中文播报引擎检测超时，未确认是否可用；文字回复仍保留。"

            recognition == false && speechOutput != SpeechEngineAvailability.AVAILABLE ->
                "本机暂缺可用的语音能力：可用键盘输入与文字查看，不会静默外发。"

            else -> null
        }
        return DiagnosticsVoice(recognition, speechOutput, message)
    }

    private fun clockHandoffOf(handoffs: List<ClockHandoff>): DiagnosticsClockHandoff {
        val pending = handoffs.count { it.status == ClockHandoffStatus.PENDING_CHECK }
        val failed = handoffs.count {
            it.status == ClockHandoffStatus.FAILED || it.status == ClockHandoffStatus.UNAVAILABLE
        }
        val latest = handoffs.maxByOrNull { it.requestedAtMillis }
        return DiagnosticsClockHandoff(
            pendingCheckCount = pending,
            failedCount = failed,
            latestStatusLabel = latest?.status?.label,
            latestRequestedAtMillis = latest?.requestedAtMillis,
        )
    }

    private suspend fun recentFailures(handoffs: List<ClockHandoff>): List<DiagnosticsFailure> {
        val stored = runCatching { failureStore.load() }.getOrDefault(emptyList())
        return (stored + derivedHandoffFailures(handoffs))
            .sortedByDescending { it.occurredAtMillis }
            .take(MAX_RECENT_FAILURES)
    }

    private fun derivedHandoffFailures(handoffs: List<ClockHandoff>): List<DiagnosticsFailure> =
        handoffs
            .filter {
                it.status == ClockHandoffStatus.FAILED || it.status == ClockHandoffStatus.UNAVAILABLE
            }
            .map { handoff ->
                DiagnosticsFailure(
                    id = "handoff:${handoff.id}",
                    occurredAtMillis = handoff.requestedAtMillis,
                    category = DiagnosticsFailureCategory.CLOCK_HANDOFF,
                    summary = DiagnosticsRedaction.redact(
                        "系统时钟交接${handoff.status.label}：${handoff.note}",
                    ),
                )
            }
            .sortedByDescending { it.occurredAtMillis }
            .take(MAX_RECENT_FAILURES)

    private suspend fun recordFailure(category: DiagnosticsFailureCategory, rawSummary: String) {
        runCatching {
            failureStore.append(
                DiagnosticsFailure(
                    id = idGen(),
                    occurredAtMillis = now(),
                    category = category,
                    summary = DiagnosticsRedaction.redact(rawSummary),
                ),
            )
        }
    }

    private fun ownerOf(entry: ReminderScheduleEntry): ReminderOwner =
        // 与 Reminder.schedulerKey() 一致：日程提醒加 "cal:" 前缀，其余为待办提醒
        if (entry.reminderKey.startsWith(CALENDAR_KEY_PREFIX)) {
            ReminderOwner.CALENDAR
        } else {
            ReminderOwner.TASK
        }

    private fun sanitize(value: String?): String =
        if (value.isNullOrBlank()) "未提供" else DiagnosticsRedaction.redact(value, 80)

    private fun sanitizeUrl(raw: String?): String {
        if (raw.isNullOrBlank()) return "未提供"
        return runCatching {
            val uri = java.net.URI(raw)
            java.net.URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()
        }.getOrElse { DiagnosticsRedaction.redact(raw, 120) }
    }

    private fun formatAt(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(zoneId).format(TIME_FORMAT)

    private fun recognitionText(available: Boolean?): String = when (available) {
        true -> "可用"
        false -> "不可用（可改用键盘输入）"
        null -> "未确认（探测失败）"
    }

    private fun speechOutputText(availability: SpeechEngineAvailability): String = when (availability) {
        SpeechEngineAvailability.AVAILABLE -> "可用"
        SpeechEngineAvailability.CHINESE_UNAVAILABLE -> "不支持中文（文字回复仍保留）"
        SpeechEngineAvailability.ENGINE_MISSING -> "缺少可用引擎（文字回复仍保留）"
        SpeechEngineAvailability.UNKNOWN -> "未确认（探测超时或失败）"
    }

    private fun ownerText(owner: ReminderOwner?): String = when (owner) {
        ReminderOwner.TASK -> "待办提醒"
        ReminderOwner.CALENDAR -> "日程提醒"
        null -> "未知归属"
    }

    private companion object {
        const val MAX_RECENT_FAILURES = 10
        const val CALENDAR_KEY_PREFIX = "cal:"
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
