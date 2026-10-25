package com.zhideer.app.platform

import com.zhideer.app.domain.repository.ReminderScheduleLogRepository

/**
 * 调度装饰器（T10/#11）：所有真实调度在提交系统闹钟成功后写入“已调度”台账，
 * 取消时移除待投递台账。恢复时据此区分「已调度」与「已投递」：
 * 台账中仍为待投递且已过点的调度即视为错过，绝不逐条补响。
 *
 * 调度失败时移除该提醒键的待投递台账——系统未接受请求时不得声称已调度。
 * 台账写入失败不得影响提醒业务本身（调用方只看调度返回值）。
 */
class ScheduleLedgerAlarmScheduler(
    private val delegate: AlarmScheduler,
    private val scheduleLogRepository: ReminderScheduleLogRepository,
    private val now: () -> Long,
) : AlarmScheduler {

    override fun canScheduleExact(): Boolean = delegate.canScheduleExact()

    override suspend fun scheduleExact(
        reminderId: String,
        triggerAtMillis: Long,
        taskId: String,
        title: String,
    ): Boolean = record(delegate.scheduleExact(reminderId, triggerAtMillis, taskId, title), reminderId, triggerAtMillis, taskId, title)

    override suspend fun scheduleInexact(
        reminderId: String,
        triggerAtMillis: Long,
        taskId: String,
        title: String,
    ): Boolean = record(delegate.scheduleInexact(reminderId, triggerAtMillis, taskId, title), reminderId, triggerAtMillis, taskId, title)

    override suspend fun cancel(reminderId: String) {
        delegate.cancel(reminderId)
        runCatching { scheduleLogRepository.removePending(reminderId) }
    }

    private suspend fun record(
        scheduled: Boolean,
        reminderId: String,
        triggerAtMillis: Long,
        taskId: String,
        title: String,
    ): Boolean {
        runCatching {
            if (scheduled) {
                // 同一提醒重新调度会覆盖系统闹钟：先移除其旧待投递台账，避免旧时刻被误判为错过
                scheduleLogRepository.removePending(reminderId)
                scheduleLogRepository.recordPending(reminderId, taskId, title, triggerAtMillis, now())
            } else {
                scheduleLogRepository.removePending(reminderId)
            }
        }
        return scheduled
    }
}
