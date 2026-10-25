package com.zhideer.app.domain.repository

import com.zhideer.app.domain.model.ReminderScheduleEntry
import kotlinx.coroutines.flow.Flow

/**
 * 系统调度台账（T10/#11）：记录“已提交调度、未投递”的提醒，供设备重启/时间变化后
 * 区分已投递与错过。它是平台事实的本地记录，不是业务提醒配置（提醒配置仍在
 * reminders / calendar_reminders 表）；台账写入失败不得影响提醒业务本身。
 */
interface ReminderScheduleLogRepository {

    /** 记录一条已提交的系统调度（同键同刻幂等：已存在终态记录时保持历史，不覆盖）。 */
    suspend fun recordPending(
        reminderKey: String,
        ownerId: String,
        ownerTitle: String,
        scheduledAtMillis: Long,
        recordedAtMillis: Long,
    )

    /** 取消某提醒键的全部待投递台账（提醒删除/停用/重排后旧调度不再有效）。 */
    suspend fun removePending(reminderKey: String)

    /** 标记已投递；无台账时补记一条已投递记录（用于终态抑制与追溯）。 */
    suspend fun markDelivered(
        reminderKey: String,
        ownerId: String,
        ownerTitle: String,
        scheduledAtMillis: Long,
        deliveredAtMillis: Long,
    )

    /**
     * 将过去时刻仍待投递的调度标记为错过，返回本次新标记的记录。
     * 幂等：同一记录只会在 PENDING 状态转换一次。
     */
    suspend fun markMissedBefore(cutoffMillis: Long, detectedAtMillis: Long): List<ReminderScheduleEntry>

    suspend fun listPending(): List<ReminderScheduleEntry>

    /** 终态调度键集合（"键@时刻"）：已投递或已错过，恢复时不得再次调度（不补响）。 */
    suspend fun listTerminalInstants(): Set<String>

    /** 尚未确认知悉的错过提醒，按提醒时刻倒序（最近的在前）。 */
    suspend fun listOutstandingMissed(): List<ReminderScheduleEntry>

    fun observeOutstandingMissed(): Flow<List<ReminderScheduleEntry>>

    /** 用户确认知悉全部错过提示；返回受影响条数。 */
    suspend fun acknowledgeMissed(acknowledgedAtMillis: Long): Int
}
