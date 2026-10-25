package com.zhideer.app.domain.model

/** 平台层系统时钟交互结果：只反映"发起"的事实，不推断"已保存"。 */
sealed interface ClockLaunchResult {
    /** Intent 已被系统接收（有处理器）。仍不代表闹钟已保存。 */
    data object Launched : ClockLaunchResult

    /** 平台无对应处理器。 */
    data object NoHandler : ClockLaunchResult

    /** 发起时被系统拒绝（例如缺少 SET_ALARM 权限或厂商限制）。 */
    data class Rejected(val reason: String) : ClockLaunchResult
}

/** 系统时钟能力探测：仅探测"是否有处理器"，不读取任何系统闹钟内容。 */
data class ClockCapability(
    val canCreateAlarm: Boolean,
    val canOpenAlarmList: Boolean,
) {
    val anyAvailable: Boolean get() = canCreateAlarm || canOpenAlarmList
}
