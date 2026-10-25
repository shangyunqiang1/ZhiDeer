package com.zhideer.app.domain.usecase

import com.zhideer.app.domain.model.Reminder
import com.zhideer.app.domain.model.ReminderDisplay
import com.zhideer.app.domain.model.TodoTask
import com.zhideer.app.domain.model.resolvedAt
import com.zhideer.app.domain.repository.RemindersRepository
import com.zhideer.app.domain.repository.TasksRepository
import com.zhideer.app.platform.AlarmScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** 提醒设置输入；相对偏移与独立时刻两种形态互斥。 */
data class ReminderInput(
    val relativeOffsetMillis: Long? = null,
    val remindAtMillis: Long? = null,
)

/**
 * 统一提醒用例入口（T08/#9）：设置（含去重）、删除、启停、未就绪修复与截止变化重算。
 * - 提醒时间与截止时间分别设置：创建待办不产生任何提醒，显式设置才创建；
 * - 同一待办下相对偏移或绝对时刻相同的提醒只存一条（去重）；
 * - 相对提醒随待办截止变化而移动（rescheduleForDueChange），独立时刻提醒不动；
 * - 精确闹钟能力不足时保留提醒记录并标记未就绪及原因，降级为非精确提醒
 *   必须经 [ReminderInput] 调用方显式确认（allowInexact = true），不悄悄降级；
 * - 已过期提醒不再向系统调度，仅在展示层明确标记。
 * 平台投递（通知/提示音/振动）由 AlarmScheduler/NotificationPoster 适配，真实投递留待实机验收。
 */
class ReminderUseCases(
    private val repository: RemindersRepository,
    private val tasksRepository: TasksRepository,
    private val scheduler: AlarmScheduler,
    private val idGen: () -> String,
    private val now: () -> Long,
) {

    /**
     * 以给定列表整体设置待办的提醒：列表外的既有提醒被删除，键相同的复用原记录。
     * 相同提醒（相对偏移相同，或绝对时刻相同）只存一条。
     * [allowInexact] 仅在用户对“降级为非精确提醒”显式确认后传 true。
     */
    suspend fun setReminders(
        taskId: String,
        inputs: List<ReminderInput>,
        allowInexact: Boolean = false,
    ): Result<List<Reminder>> {
        val task = tasksRepository.getTask(taskId)
            ?: return Result.failure(IllegalStateException("待办不存在或已进入回收站"))
        if (task.trashedAt != null) {
            return Result.failure(IllegalStateException("待办不存在或已进入回收站"))
        }
        inputs.forEach { input ->
            val error = validateInput(input, task) ?: return@forEach
            return Result.failure(IllegalArgumentException(error))
        }

        val t = now()
        val existing = repository.listByTask(taskId)
        val existingByKey = existing.associateBy { keyOf(it) }
        val keptIds = mutableSetOf<String>()
        // 去重：按（相对偏移 | 绝对时刻）键保留首个出现
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
                    taskId = taskId,
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
            scheduler.cancel(stale.id)
            repository.delete(stale.id)
        }
        // 逐条按当前能力调度/标记
        repository.listByTask(taskId).forEach { row ->
            applySchedule(row, task)
        }
        return Result.success(repository.listByTask(taskId))
    }

    /** 删除单条提醒：先取消系统闹钟再移除记录。 */
    suspend fun deleteReminder(reminderId: String): Result<Unit> {
        val current = repository.get(reminderId)
            ?: return Result.failure(IllegalStateException("提醒不存在"))
        scheduler.cancel(current.id)
        repository.delete(current.id)
        return Result.success(Unit)
    }

    /** 启停提醒：停用即取消闹钟并保留记录；启用按当前能力重新调度。 */
    suspend fun setEnabled(reminderId: String, enabled: Boolean): Result<Reminder> {
        val current = repository.get(reminderId)
            ?: return Result.failure(IllegalStateException("提醒不存在"))
        val task = tasksRepository.getTask(current.taskId)
            ?: return Result.failure(IllegalStateException("待办不存在或已进入回收站"))
        val updated = current.copy(enabled = enabled, updatedAt = now())
        repository.upsert(updated)
        if (enabled) {
            applySchedule(updated, task)
        } else {
            scheduler.cancel(updated.id)
        }
        return Result.success(updated)
    }

    /** 取消/删除待办时停止其全部提醒：取消闹钟并清除记录（GLOSSARY：取消待办停止提醒）。幂等。 */
    suspend fun cancelReminders(taskId: String): Result<Unit> {
        repository.listByTask(taskId).forEach { scheduler.cancel(it.id) }
        repository.deleteByTask(taskId)
        return Result.success(Unit)
    }

    /** 修复入口：按当前能力重新评估该待办全部提醒，可调度则清除未就绪标记并恢复闹钟。幂等。 */
    suspend fun repairNotReady(taskId: String): Result<List<Reminder>> {
        val task = tasksRepository.getTask(taskId)
            ?: return Result.failure(IllegalStateException("待办不存在或已进入回收站"))
        if (task.trashedAt != null) {
            return Result.failure(IllegalStateException("待办不存在或已进入回收站"))
        }
        repository.listByTask(taskId).forEach { applySchedule(it, task) }
        return Result.success(repository.listByTask(taskId))
    }

    /**
     * 待办截止变化回调（TaskUseCases.update/postpone 成功后调用）：
     * 仅重算相对提醒（随截止移动）；独立时刻提醒不依赖截止，保持不动。
     * 截止改为日期截止（无具体时刻）时，相对提醒标记未就绪并停止闹钟。
     */
    suspend fun rescheduleForDueChange(taskId: String): Result<Unit> {
        val task = tasksRepository.getTask(taskId) ?: return Result.success(Unit)
        repository.listByTask(taskId)
            .filter { it.relativeOffsetMillis != null }
            .forEach { applySchedule(it, task) }
        return Result.success(Unit)
    }

    /** 观察某待办的提醒（原始记录，含未就绪标记）。 */
    fun observeByTask(taskId: String): Flow<List<Reminder>> = repository.observeByTask(taskId)

    /** 观察某待办的提醒展示快照：结合待办换算具体时间并标记过期。 */
    fun observeDisplaysByTask(taskId: String): Flow<List<ReminderDisplay>> =
        combine(repository.observeByTask(taskId), tasksRepository.observeTasks()) { reminders, tasks ->
            val task = tasks.firstOrNull { it.id == taskId }
            reminders.map { display(it, task) }
        }

    private fun display(reminder: Reminder, task: TodoTask?): ReminderDisplay {
        val resolved = reminder.resolvedAt(task)
        return ReminderDisplay(
            reminder = reminder,
            resolvedAtMillis = resolved,
            isOverdue = resolved != null && resolved < now(),
        )
    }

    /**
     * 按当前能力调度单条提醒：
     * - 停用或已过期：不设置闹钟（过期在展示层标记）；
     * - 相对提醒无具体时刻截止可换算：标记未就绪（日期截止原因）；
     * - 可精确调度：提交精确闹钟并清除未就绪标记（曾确认降级的升级回精确）；
     * - 不可精确且已显式确认降级：按非精确调度；
     * - 不可精确且未确认：保留记录并标记未就绪及原因，不悄悄降级。
     */
    private suspend fun applySchedule(reminder: Reminder, task: TodoTask) {
        if (!reminder.enabled) {
            scheduler.cancel(reminder.id)
            return
        }
        val resolved = reminder.resolvedAt(task)
        if (resolved == null) {
            scheduler.cancel(reminder.id)
            repository.upsert(
                reminder.copy(notReadyReason = NOT_READY_NO_DUE_INSTANT, updatedAt = now()),
            )
            return
        }
        if (resolved <= now()) {
            scheduler.cancel(reminder.id)
            return
        }
        val t = now()
        when {
            scheduler.canScheduleExact() -> {
                val ok = scheduler.scheduleExact(reminder.id, resolved, task.id, task.title)
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
                val ok = scheduler.scheduleInexact(reminder.id, resolved, task.id, task.title)
                if (ok) {
                    repository.upsert(reminder.copy(notReadyReason = null, updatedAt = t))
                } else {
                    repository.upsert(
                        reminder.copy(notReadyReason = NOT_READY_SCHEDULE_FAILED, updatedAt = t),
                    )
                }
            }
            else -> {
                scheduler.cancel(reminder.id)
                repository.upsert(
                    reminder.copy(notReadyReason = NOT_READY_EXACT_ALARM, updatedAt = t),
                )
            }
        }
    }

    private fun validateInput(input: ReminderInput, task: TodoTask): String? = when {
        input.relativeOffsetMillis == null && input.remindAtMillis == null -> "提醒时间不能为空"
        input.relativeOffsetMillis != null && input.remindAtMillis != null -> "相对提醒与独立时刻提醒只能选择其一"
        input.relativeOffsetMillis != null && input.relativeOffsetMillis <= 0 -> "相对提醒需提前于截止时刻"
        input.relativeOffsetMillis != null && task.dueAtMillis == null -> "日期截止不含具体时刻，无法设置相对提醒"
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

        /** 待办为日期截止（不含具体时刻），相对提醒暂无法换算时刻。 */
        const val NOT_READY_NO_DUE_INSTANT = "待办当前为日期截止，相对提醒暂无法换算时刻"

        /** 系统拒绝调度请求（如 SecurityException）。 */
        const val NOT_READY_SCHEDULE_FAILED = "系统未能接受提醒调度请求，请稍后重试"
    }
}
