package com.zhideer.app.platform

import com.zhideer.app.domain.model.ClockCapability
import com.zhideer.app.domain.model.ClockLaunchResult

/**
 * 系统时钟平台适配器（T24/#25）。实现只能发起跳转与探测处理器，
 * **不得尝试读取系统闹钟列表内容**——公开接口不提供该能力。
 */
interface ClockLauncher {
    fun capability(): ClockCapability

    /** 发起创建闹钟：只传小时/分钟与星期规则（平台公开接口的能力边界）。 */
    fun createAlarm(hour: Int, minute: Int, label: String, weekdays: Set<Int>): ClockLaunchResult

    fun openAlarmList(): ClockLaunchResult
}
