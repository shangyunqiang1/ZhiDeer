package com.zhideer.app.domain.usecase

import com.zhideer.app.domain.model.ClockCapability
import com.zhideer.app.domain.model.ClockHandoff
import com.zhideer.app.domain.model.ClockHandoffStatus
import com.zhideer.app.domain.model.ClockLaunchResult
import com.zhideer.app.domain.repository.ClockHandoffRepository
import com.zhideer.app.platform.ClockLauncher
import kotlinx.coroutines.flow.Flow

/** 交接预览：发起前供用户核对时间与星期规则。 */
data class ClockHandoffPreview(
    val hour: Int,
    val minute: Int,
    val label: String,
    val targetEpochDay: Long?,
    val weekdays: Set<Int>,
    val capability: ClockCapability,
) {
    val timeText: String get() = "%02d:%02d".format(hour, minute)

    val weekdayText: String
        get() = when {
            weekdays.isEmpty() -> "仅响一次"
            weekdays.size == 7 -> "每天"
            else -> "每周 " + weekdays.sorted().joinToString("、") {
                ClockHandoff.WEEKDAY_NAMES[it] ?: "?"
            }
        }

    /** 指定了日历日期：平台接口只接受时分与星期，日期无法传达。 */
    val dateNotConveyable: Boolean get() = targetEpochDay != null

    val canHandoff: Boolean get() = capability.canCreateAlarm && targetEpochDay == null
}

/**
 * 系统闹钟交接用例（T24/#25）。
 *
 * 硬约束（实机实测 + 平台公开接口）：
 * - 跳转成功只记为 [ClockHandoffStatus.PENDING_CHECK]，**绝不标记为已保存**；
 * - 平台 `SET_ALARM` 不接受具体日期，指定日期时**不静默丢弃日期**，直接拒绝交接并给出回退；
 * - 平台不支持/发起失败时不崩溃，返回失败结果并保留回退入口（手动系统时钟 / 知时鹿自有提醒）；
 * - 本用例不创建任何自有提醒（避免双响），仅在回退提示中说明可由用户自行设置。
 */
class ClockHandoffUseCases(
    private val repository: ClockHandoffRepository,
    private val launcher: ClockLauncher,
    private val idGen: () -> String,
    private val now: () -> Long,
) {

    fun capability(): ClockCapability = launcher.capability()

    fun observeHandoffs(): Flow<List<ClockHandoff>> = repository.observeAll()

    fun preview(
        hour: Int,
        minute: Int,
        label: String,
        targetEpochDay: Long?,
        weekdays: Set<Int>,
    ): Result<ClockHandoffPreview> {
        if (hour !in 0..23) return Result.failure(IllegalArgumentException("小时需在 0–23 之间"))
        if (minute !in 0..59) return Result.failure(IllegalArgumentException("分钟需在 0–59 之间"))
        if (weekdays.any { it !in 1..7 }) {
            return Result.failure(IllegalArgumentException("星期规则需在周一至周日范围内"))
        }
        return Result.success(
            ClockHandoffPreview(
                hour = hour,
                minute = minute,
                label = label.trim(),
                targetEpochDay = targetEpochDay,
                weekdays = weekdays,
                capability = launcher.capability(),
            ),
        )
    }

    /**
     * 发起系统闹钟创建。返回 [ClockHandoffStatus.PENDING_CHECK] 只表示请求已发出，
     * 用户仍需在系统时钟界面核对确认。
     */
    suspend fun handoff(preview: ClockHandoffPreview): Result<ClockHandoff> {
        if (preview.targetEpochDay != null) {
            return Result.failure(
                IllegalStateException(
                    "系统时钟接口只接受小时与分钟，无法传达具体日期；" +
                        "请改为仅指定时间并在系统时钟中选择日期，或改用知时鹿提醒。",
                ),
            )
        }
        if (!preview.capability.canCreateAlarm) {
            return Result.failure(
                IllegalStateException("当前系统没有可用的时钟应用，无法发起创建；可手动打开系统时钟或改用知时鹿提醒。"),
            )
        }
        val result = launcher.createAlarm(
            hour = preview.hour,
            minute = preview.minute,
            label = preview.label,
            weekdays = preview.weekdays,
        )
        val status: ClockHandoffStatus
        val note: String
        when (result) {
            ClockLaunchResult.Launched -> {
                status = ClockHandoffStatus.PENDING_CHECK
                note = "已发起系统时钟创建请求。请到系统时钟核对是否保存；本记录不代表已保存。"
            }
            ClockLaunchResult.NoHandler -> {
                status = ClockHandoffStatus.UNAVAILABLE
                note = "系统没有处理该请求的时钟应用。可手动打开系统时钟，或改用知时鹿提醒。"
            }
            is ClockLaunchResult.Rejected -> {
                status = ClockHandoffStatus.FAILED
                note = "发起失败：${result.reason}。可手动打开系统时钟，或改用知时鹿提醒。"
            }
        }
        val handoff = ClockHandoff(
            id = idGen(),
            hour = preview.hour,
            minute = preview.minute,
            label = preview.label,
            targetEpochDay = null,
            weekdays = preview.weekdays,
            requestedAtMillis = now(),
            status = status,
            note = note,
        )
        repository.upsert(handoff)
        return if (status == ClockHandoffStatus.PENDING_CHECK) {
            Result.success(handoff)
        } else {
            Result.failure(ClockHandoffFailure(handoff, note))
        }
    }

    suspend fun openAlarmList(): Result<Unit> = when (launcher.openAlarmList()) {
        ClockLaunchResult.Launched -> Result.success(Unit)
        ClockLaunchResult.NoHandler ->
            Result.failure(IllegalStateException("当前系统没有可用的时钟应用，请手动打开系统时钟。"))
        is ClockLaunchResult.Rejected ->
            Result.failure(IllegalStateException("无法打开系统时钟：${launcher.openAlarmList()}"))
    }

    suspend fun delete(handoffId: String) = repository.delete(handoffId)

    /** 回退提示文案：切换为知时鹿自有提醒时，提醒用户检查系统闹钟以避免双响。 */
    fun fallbackHint(): String =
        "回退选择：① 手动打开系统时钟自行设置；② 在知时鹿内为事项设置提醒。" +
            "若两者同时存在，可能重复响铃，请到系统时钟检查并关闭多余的闹钟。"
}

/** 已写入交接记录但平台不支持/发起失败：携带记录供界面展示回退。 */
class ClockHandoffFailure(val handoff: ClockHandoff, message: String) : IllegalStateException(message)
