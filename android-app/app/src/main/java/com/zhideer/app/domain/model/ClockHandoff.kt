package com.zhideer.app.domain.model

/** 系统闹钟交接状态：跳转成功不等于已保存，一律待核对。 */
enum class ClockHandoffStatus(val label: String) {
    PENDING_CHECK("待核对"),
    UNAVAILABLE("平台不支持"),
    FAILED("发起失败"),
}

/**
 * 系统闹钟交接记录（T24/#25）。
 *
 * 语义边界（GLOSSARY「系统闹钟交接记录」）：
 * - 本记录只表示"知时鹿向系统时钟发起了创建请求"，**不证明系统闹钟已保存**；
 * - 未核对前保持 [ClockHandoffStatus.PENDING_CHECK]，界面不得显示"已保存"；
 * - 应用无法读取系统闹钟全部内容，核对必须由用户在系统时钟界面完成。
 *
 * [targetEpochDay] 非空表示用户指定了日历日期。平台 SET_ALARM 公开接口只接受
 * 小时/分钟与星期规则，**不接受具体日期**；因此指定日期时不得静默丢弃日期只传时分，
 * 只能标记为需人工在系统时钟中选择日期（见 [ClockHandoff.appDateNotConveyable]）。
 */
data class ClockHandoff(
    val id: String,
    val hour: Int,
    val minute: Int,
    val label: String,
    val targetEpochDay: Long? = null,
    val weekdays: Set<Int> = emptySet(),
    val requestedAtMillis: Long,
    val status: ClockHandoffStatus,
    val note: String = "",
) {
    /** 指定了日历日期：平台接口无法传达日期，必须提示用户手动选择，不得静默丢弃。 */
    val appDateNotConveyable: Boolean get() = targetEpochDay != null

    val timeText: String get() = "%02d:%02d".format(hour, minute)

    val weekdayText: String
        get() = when {
            weekdays.isEmpty() -> "仅响一次"
            weekdays.size == 7 -> "每天"
            else -> "每周 " + weekdays.sorted().joinToString("、") { WEEKDAY_NAMES[it] ?: "?" }
        }

    companion object {
        /** 1=周一 … 7=周日（与 java.time.DayOfWeek 一致）。 */
        val WEEKDAY_NAMES = mapOf(
            1 to "一", 2 to "二", 3 to "三", 4 to "四", 5 to "五", 6 to "六", 7 to "日",
        )
    }
}
