package com.zhideer.app.domain.model

import kotlinx.serialization.Serializable

/**
 * 权限与故障诊断（T35/#36，规格用户故事 40、48、54、67、89）。
 *
 * 本文件只承载“可观察事实”的展示模型，不做任何推断：
 * - 权限、语音引擎与模型配置来自各能力探针的实时结果；
 * - 自有提醒来自 #11 的系统调度台账（已调度 ≠ 已投递）；
 * - 系统时钟交接来自 #25 的记录（待核对 ≠ 已保存），与自有提醒分开表达；
 * - 失败记录已在写入前脱敏（不含 API Key、原始录音或完整私人事项内容）。
 */

/** 诊断失败类别：仅保存在本机供排查，绝不上报或自动外发。 */
@Serializable
enum class DiagnosticsFailureCategory(val label: String) {
    MODEL_CONNECTION("模型连接"),
    VOICE_ENGINE("语音引擎"),
    REMINDER("提醒调度"),
    CLOCK_HANDOFF("系统时钟交接"),
}

/**
 * 一条脱敏后的失败记录。[summary] 由 [com.zhideer.app.domain.usecase.DiagnosticsRedaction] 处理：
 * 不含 API Key、Bearer 令牌、URL 凭据，不包含事项标题或原始录音。
 */
@Serializable
data class DiagnosticsFailure(
    val id: String,
    val occurredAtMillis: Long,
    val category: DiagnosticsFailureCategory,
    val summary: String,
)

/** 失败记录存储载荷（DataStore JSON；有意避开 Room，诊断记录不是业务事项）。 */
@Serializable
data class DiagnosticsFailureRecords(
    val records: List<DiagnosticsFailure> = emptyList(),
)

/** 系统提醒能力：只读系统可观察状态，不据此推断真实投递。 */
data class DiagnosticsPermissions(
    val notificationsAllowed: Boolean,
    val exactAlarmsAllowed: Boolean,
    val batteryOptimizationsIgnored: Boolean,
) {
    val notificationsNeedFix: Boolean get() = !notificationsAllowed

    val exactAlarmsNeedFix: Boolean get() = !exactAlarmsAllowed

    val batteryOptimizationNeedsFix: Boolean get() = !batteryOptimizationsIgnored

    /** 拒绝授权后的可用性：手动功能不受影响，提醒保持未就绪（不静默降级）。 */
    val manualFallbackNote: String
        get() = "拒绝授权不会阻止手动新增、查看和编辑事项；相关提醒保持未就绪，" +
            "不会悄悄降级为不精确提醒，也不会伪造已准时。"
}

/** 模型配置当前状态：只显示是否已配置、模型标识与 Base URL，绝不携带 API Key。 */
data class DiagnosticsModel(
    val configured: Boolean,
    val modelName: String? = null,
    val baseUrl: String? = null,
) {
    val needsConfig: Boolean get() = !configured
}

/** 一次模型连接测试的即时结果：只在本次页面会话内展示，刷新后不保留旧的成功状态。 */
data class ModelConnectionTest(
    val testedAtMillis: Long,
    val success: Boolean,
    val message: String,
)

/** 中文播报引擎的探测结果；[UNKNOWN] 只表示未确认，不表示可用。 */
enum class SpeechEngineAvailability {
    AVAILABLE,
    CHINESE_UNAVAILABLE,
    ENGINE_MISSING,
    UNKNOWN,
}

/** 语音引擎状态（#24）：识别与播报分别展示；不可用时给出键盘/文字回退。 */
data class DiagnosticsVoice(
    val recognitionAvailable: Boolean?,
    val speechOutput: SpeechEngineAvailability,
    val message: String? = null,
) {
    val recognitionUnavailable: Boolean get() = recognitionAvailable == false

    val speechOutputUnavailable: Boolean get() = speechOutput != SpeechEngineAvailability.AVAILABLE

    /** 语音不可用时的回退说明：键盘输入与文字回复不受影响，应用不保存原始录音。 */
    val manualFallbackNote: String
        get() = "语音不可用时仍可用键盘输入，文字回复始终保留；应用不保存原始录音，也不静默外发。"
}

/** 自有提醒的调度事实（#11 台账）：展示「已调度」，绝不当作「已投递」。 */
data class DiagnosticsReminders(
    val nextReminderAtMillis: Long?,
    val nextReminderOwner: ReminderOwner?,
    val pendingScheduleCount: Int,
    val notReadyCount: Int,
    val outstandingMissedCount: Int,
    val lastRestoreTriggerLabel: String?,
    val lastRestoreAtMillis: Long?,
) {
    val hasNextReminder: Boolean get() = nextReminderAtMillis != null

    /** 如实说明：调度请求提交成功不代表真实投递。 */
    val schedulingNote: String
        get() = "「已调度」不等于「已投递」：ColorOS 等系统在锁屏、待机或省电模式下仍可能有数分钟投递窗口，" +
            "本页状态不能作为准点保证。"
}

/** 系统时钟交接（#25）：待核对不等于已保存，也与知时鹿自有提醒分开。 */
data class DiagnosticsClockHandoff(
    val pendingCheckCount: Int,
    val failedCount: Int,
    val latestStatusLabel: String?,
    val latestRequestedAtMillis: Long?,
) {
    val needsCheck: Boolean get() = pendingCheckCount > 0

    val hasFailure: Boolean get() = failedCount > 0

    val note: String
        get() = "系统闹钟交接记录只表示已向系统时钟发起请求：待核对不等于已保存，" +
            "也不是知时鹿自有提醒；请到系统时钟界面对照核对。"
}

/** 诊断快照：每次刷新都重新探测真实状态，不缓存过期成功。 */
data class DiagnosticsSnapshot(
    val capturedAtMillis: Long,
    val permissions: DiagnosticsPermissions,
    val model: DiagnosticsModel,
    val voice: DiagnosticsVoice,
    val reminders: DiagnosticsReminders,
    val clockHandoff: DiagnosticsClockHandoff,
    val recentFailures: List<DiagnosticsFailure>,
)
