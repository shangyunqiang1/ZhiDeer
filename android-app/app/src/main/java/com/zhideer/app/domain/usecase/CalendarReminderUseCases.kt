package com.zhideer.app.domain.usecase

import com.zhideer.app.domain.model.CalendarEntry
import com.zhideer.app.domain.model.Reminder
import com.zhideer.app.domain.model.ReminderDisplay
import com.zhideer.app.domain.model.ReminderOwner
import com.zhideer.app.domain.model.ReminderRestoreOutcome
import com.zhideer.app.domain.model.resolvedAtForEntry
import com.zhideer.app.domain.model.schedulerKey
import com.zhideer.app.domain.repository.CalendarRemindersRepository
import com.zhideer.app.domain.repository.CalendarRepository
import com.zhideer.app.platform.AlarmScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * 日程提醒用例入口（T09/#10）：为日程设置（含去重）、删除、启停、未就绪修复与改期重算。
 * 复用 T08/#9 待办提醒的成熟模式与平台适配器（AlarmScheduler/NotificationPoster），
 * 差异点：归属为日程（ReminderOwner.CALENDAR），相对提醒以开始时刻为基准随改期移动，
 * 独立时刻提醒不动；调度键经 schedulerKey 加归属前缀，与待办提醒键空间区分。
 * - 提醒时间与日程开始时间分别设置：创建日程不产生任何提醒，显式设置才创建；
 * - 同一日程下相对提前量或绝对时刻相同的提醒只存一条（去重）；
 * - 精确闹钟能力不足时保留提醒记录并标记未就绪及原因，降级为非精确提醒
 *   必须经调用方显式确认（allowInexact = true），不悄悄降级；
 * - 已过期提醒不再向系统调度，仅在展示层明确标记；
 * - 日程结束只表示时间已过，不影响提醒记录（结束≠完成，日程本无完成概念）。
 * 平台投递（通知/提示音/振动）由既有 ReminderAlarmReceiver/NotificationPoster 适配，
 * 真实投递留待统一实机验收。
 */
class CalendarReminderUseCases(
    private val repository: CalendarRemindersRepository,
    private val calendarRepository: CalendarRepository,
    private val scheduler: AlarmScheduler,
    private val idGen: () -> String,
    private val now: () -> Long,
) {

    /**
     * 以给定列表整体设置日程的提醒：列表外的既有提醒被删除，键相同的复用原记录。
     * 相同提醒（相对提前量相同，或绝对时刻相同）只存一条。
     * [allowInexact] 仅在用户对“降级为非精确提醒”显式确认后传 true。
     */
    suspend fun setReminders(
        entryId: String,
        inputs: List<ReminderInput>,
        allowInexact: Boolean = false,
    ): Result<List<Reminder>> {
        val entry = calendarRepository.getEntry(entryId)
            ?: return Result.failure(IllegalStateException("日程不存在或已进入回收站"))
        if (entry.trashedAt != null) {
            return Result.failure(IllegalStateException("日程不存在或已进入回收站"))
        }
        inputs.forEach { input ->
            val error = validateInput(input) ?: return@forEach
            return Result.failure(IllegalArgumentException(error))
        }

        val t = now()
        val existing = repository.listByEntry(entryId)
        val existingByKey = existing.associateBy { keyOf(it) }
        val keptIds = mutableSetOf<String>()
        // 去重：按（相对提前量 | 绝对时刻）键保留首个出现
        inputs.distinctBy { keyOfInput(it) }.forEach { input ->
            val key = keyOfInput(input)
            val matched = existingByKey[key]
            val row = if (matched != null) {
                keptIds += matched.id
                matched.copy(
                    inexactConfirmed = matched.inexactConfirmed || allowInexact,
                    updatedAt = t,
                )
            } else {
                Reminder(
                    id = idGen(),
                    ownerType = ReminderOwner.CALENDAR,
                    taskId = entryId,
                    relativeOffsetMillis = input.relativeOffsetMillis,
                    remindAtMillis = input.remindAtMillis,
                    enabled = true,
                    inexactConfirmed = allowInexact,
                    notReadyReason = null,
                    createdAt = t,
                    updatedAt = t,
                )
            }
            repository.upsert(row)
        }
        // 列表外的既有提醒：先停闹钟再删记录
        existing.filter { it.id !in keptIds }.forEach { stale ->
            scheduler.cancel(stale.schedulerKey())
            repository.delete(stale.id)
        }
        // 逐条按当前能力调度/标记
        repository.listByEntry(entryId).forEach { row ->
            applySchedule(row, entry)
        }
        return Result.success(repository.listByEntry(entryId))
    }

    /** 删除单条提醒：先取消系统闹钟再移除记录。 */
    suspend fun deleteReminder(reminderId: String): Result<Unit> {
        val current = repository.get(reminderId)
            ?: return Result.failure(IllegalStateException("提醒不存在"))
        scheduler.cancel(current.schedulerKey())
        repository.delete(current.id)
        return Result.success(Unit)
    }

    /** 启停提醒：停用即取消闹钟并保留记录；启用按当前能力重新调度。 */
    suspend fun setEnabled(reminderId: String, enabled: Boolean): Result<Reminder> {
        val current = repository.get(reminderId)
            ?: return Result.failure(IllegalStateException("提醒不存在"))
        val entry = calendarRepository.getEntry(current.taskId)
            ?: return Result.failure(IllegalStateException("日程不存在或已进入回收站"))
        val updated = current.copy(enabled = enabled, updatedAt = now())
        repository.upsert(updated)
        if (enabled) {
            applySchedule(updated, entry)
        } else {
            scheduler.cancel(updated.schedulerKey())
        }
        return Result.success(updated)
    }

    /** 删除日程时停止其全部提醒：取消闹钟并清除记录。不影响关联待办自身的提醒（分表按日程归属）。幂等。 */
    suspend fun cancelReminders(entryId: String): Result<Unit> {
        repository.listByEntry(entryId).forEach { scheduler.cancel(it.schedulerKey()) }
        repository.deleteByEntry(entryId)
        return Result.success(Unit)
    }

    /**
     * 设备变化后的整体恢复（T10/#11）：与待办提醒恢复同一套 [applySchedule] 规则，
     * 调度键使用 [schedulerKey]（日程提醒带 "cal:" 前缀）区分归属。
     * 日程已删除/提醒已停用或为终态调度（已投递/已错过）时取消并跳过，不补响。
     */
    suspend fun restoreAll(suppressedInstants: Set<String> = emptySet()): ReminderRestoreOutcome {
        var scanned = 0
        var rescheduled = 0
        var notReady = 0
        var skipped = 0
        repository.listAll().forEach { reminder ->
            scanned++
            val key = reminder.schedulerKey()
            val entry = calendarRepository.getEntry(reminder.taskId)
            val resolved = reminder.resolvedAtForEntry(entry)
            val suppressed = resolved != null && "$key@$resolved" in suppressedInstants
            if (entry == null || entry.trashedAt != null || suppressed) {
                scheduler.cancel(key)
                skipped++
                return@forEach
            }
            applySchedule(reminder, entry)
            val after = repository.get(reminder.id)
            when {
                after == null -> skipped++
                after.notReadyReason != null -> notReady++
                after.enabled && resolved != null && resolved > now() -> rescheduled++
                else -> skipped++
            }
        }
        return ReminderRestoreOutcome(
            scannedCount = scanned,
            rescheduledCount = rescheduled,
            notReadyCount = notReady,
            skippedCount = skipped,
        )
    }

    /** 修复入口：按当前能力重新评估该日程全部提醒，可调度则清除未就绪标记并恢复闹钟。幂等。 */
    suspend fun repairNotReady(entryId: String): Result<List<Reminder>> {
        val entry = calendarRepository.getEntry(entryId)
            ?: return Result.failure(IllegalStateException("日程不存在或已进入回收站"))
        if (entry.trashedAt != null) {
            return Result.failure(IllegalStateException("日程不存在或已进入回收站"))
        }
        repository.listByEntry(entryId).forEach { applySchedule(it, entry) }
        return Result.success(repository.listByEntry(entryId))
    }

    /**
     * 日程改期回调（CalendarUseCases.update/reinterpretInZone 成功后调用）：
     * 仅重算相对提醒（随开始时刻移动）；独立时刻提醒不依赖开始时刻，保持不动。
     */
    suspend fun rescheduleForStartChange(entryId: String): Result<Unit> {
        val entry = calendarRepository.getEntry(entryId) ?: return Result.success(Unit)
        repository.listByEntry(entryId)
            .filter { it.relativeOffsetMillis != null }
            .forEach { applySchedule(it, entry) }
        return Result.success(Unit)
    }

    /** 观察某日程的提醒（原始记录，含未就绪标记）。 */
    fun observeByEntry(entryId: String): Flow<List<Reminder>> = repository.observeByEntry(entryId)

    /** 观察某日程的提醒展示快照：结合日程换算具体时间并标记过期。 */
    fun observeDisplaysByEntry(entryId: String): Flow<List<ReminderDisplay>> =
        combine(repository.observeByEntry(entryId), calendarRepository.observeEntries()) { reminders, entries ->
            val entry = entries.firstOrNull { it.id == entryId }
            reminders.map { display(it, entry) }
        }

    private fun display(reminder: Reminder, entry: CalendarEntry?): ReminderDisplay {
        val resolved = reminder.resolvedAtForEntry(entry)
        return ReminderDisplay(
            reminder = reminder,
            resolvedAtMillis = resolved,
            isOverdue = resolved != null && resolved < now(),
        )
    }

    /**
     * 按当前能力调度单条提醒（模式同 ReminderUseCases.applySchedule）：
     * - 停用或已过期：不设置闹钟（过期在展示层标记）；
     * - 相对提醒无法换算时刻：标记未就绪（防御分支，当前日程模型必有开始时刻）；
     * - 可精确调度：提交精确闹钟并清除未就绪标记（曾确认降级的升级回精确）；
     * - 不可精确且已显式确认降级：按非精确调度；
     * - 不可精确且未确认：保留记录并标记未就绪及原因，不悄悄降级。
     * 调度键一律走 schedulerKey（日程提醒带 "cal:" 前缀），与待办提醒键空间区分。
     */
    private suspend fun applySchedule(reminder: Reminder, entry: CalendarEntry) {
        val key = reminder.schedulerKey()
        if (!reminder.enabled) {
            scheduler.cancel(key)
            return
        }
        val resolved = reminder.resolvedAtForEntry(entry)
        if (resolved == null) {
            scheduler.cancel(key)
            repository.upsert(
                reminder.copy(notReadyReason = NOT_READY_NO_START_INSTANT, updatedAt = now()),
            )
            return
        }
        if (resolved <= now()) {
            scheduler.cancel(key)
            return
        }
        val t = now()
        when {
            scheduler.canScheduleExact() -> {
                val ok = scheduler.scheduleExact(key, resolved, entry.id, entry.title)
                if (ok) {
                    repository.upsert(
                        reminder.copy(notReadyReason = null, inexactConfirmed = false, updatedAt = t),
                    )
                } else {
                    repository.upsert(
                        reminder.copy(notReadyReason = NOT_READY_SCHEDULE_FAILED, updatedAt = t),
                    )
                }
            }
            reminder.inexactConfirmed -> {
                val ok = scheduler.scheduleInexact(key, resolved, entry.id, entry.title)
                if (ok) {
                    repository.upsert(reminder.copy(notReadyReason = null, updatedAt = t))
                } else {
                    repository.upsert(
                        reminder.copy(notReadyReason = NOT_READY_SCHEDULE_FAILED, updatedAt = t),
                    )
                }
            }
            else -> {
                scheduler.cancel(key)
                repository.upsert(
                    reminder.copy(notReadyReason = NOT_READY_EXACT_ALARM, updatedAt = t),
                )
            }
        }
    }

    private fun validateInput(input: ReminderInput): String? = when {
        input.relativeOffsetMillis == null && input.remindAtMillis == null -> "提醒时间不能为空"
        input.relativeOffsetMillis != null && input.remindAtMillis != null -> "相对提醒与独立时刻提醒只能选择其一"
        input.relativeOffsetMillis != null && input.relativeOffsetMillis <= 0 -> "相对提醒需提前于开始时刻"
        else -> null
    }

    private fun keyOfInput(input: ReminderInput): String = when {
        input.relativeOffsetMillis != null -> "R:${input.relativeOffsetMillis}"
        else -> "A:${input.remindAtMillis}"
    }

    private fun keyOf(reminder: Reminder): String = when {
        reminder.relativeOffsetMillis != null -> "R:${reminder.relativeOffsetMillis}"
        else -> "A:${reminder.remindAtMillis}"
    }

    companion object {
        /** 未授予精确闹钟权限：提醒记录保留，界面提供去授权/降级确认入口。 */
        const val NOT_READY_EXACT_ALARM = "未授予精确闹钟权限，提醒暂无法准时触发"

        /** 相对提醒暂无法换算时刻（防御分支：日程模型必有开始时刻）。 */
        const val NOT_READY_NO_START_INSTANT = "日程缺少开始时刻，相对提醒暂无法换算时刻"

        /** 系统拒绝调度请求（如 SecurityException）。 */
        const val NOT_READY_SCHEDULE_FAILED = "系统未能接受提醒调度请求，请稍后重试"
    }
}
