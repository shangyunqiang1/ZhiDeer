package com.zhideer.app.domain.usecase

import com.zhideer.app.domain.model.ReminderEnvironmentStatus
import com.zhideer.app.domain.model.ReminderOwner
import com.zhideer.app.domain.model.ReminderRestoreReport
import com.zhideer.app.domain.model.ReminderRestoreTrigger
import com.zhideer.app.domain.model.ReminderScheduleEntry
import com.zhideer.app.domain.model.ReminderZoneAnchor
import com.zhideer.app.domain.model.TaskStatus
import com.zhideer.app.domain.model.resolvedAt
import com.zhideer.app.domain.model.resolvedAtForEntry
import com.zhideer.app.domain.repository.CalendarRemindersRepository
import com.zhideer.app.domain.repository.CalendarRepository
import com.zhideer.app.domain.repository.ReminderScheduleLogRepository
import com.zhideer.app.domain.repository.RemindersRepository
import com.zhideer.app.domain.repository.TasksRepository
import com.zhideer.app.platform.ReminderCapabilityProbe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId

/**
 * 设备变化后的提醒恢复统一入口（T10/#11，规格用户故事 40–43）。
 *
 * 触发来源不同（重启、时间调整、时区变化、权限变化、应用启动/手动），恢复规则一致：
 * 1) 先兑现「错过」：调度台账中已过点且仍待投递的记录转为错过终态（合并提示，绝不逐条补响）；
 * 2) 再重建未来：待办与日程提醒分别走既有 ReminderUseCases/CalendarReminderUseCases 的
 *    restoreAll（与设置提醒同一套 applySchedule 规则，不另建不一致路径）；
 * 3) 终态抑制：已投递/已错过的调度即使系统时间倒拨也不重新调度（不补响）；
 * 4) 如实报告：通知/精确提醒/省电限制状态、创建时区核对项、ColorOS 投递窗口限制。
 *
 * 业务记录不被设备状态改变：恢复只重算提醒调度，绝不自动延期、完成或取消任何事项。
 * 恢复幂等：同一提醒按同一调度键覆盖；同一错过记录只转换一次，重复恢复不产生重复提示。
 */
class ReminderRestoreUseCases(
    private val tasksRepository: TasksRepository,
    private val calendarRepository: CalendarRepository,
    private val remindersRepository: RemindersRepository,
    private val calendarRemindersRepository: CalendarRemindersRepository,
    private val scheduleLogRepository: ReminderScheduleLogRepository,
    private val reminderUseCases: ReminderUseCases,
    private val calendarReminderUseCases: CalendarReminderUseCases,
    private val taskUseCases: TaskUseCases,
    private val calendarUseCases: CalendarUseCases,
    private val capabilityProbe: ReminderCapabilityProbe,
    private val now: () -> Long,
    private val deviceZone: () -> ZoneId = { ZoneId.systemDefault() },
) {

    private val mutex = Mutex()
    private val lastReport = MutableStateFlow<ReminderRestoreReport?>(null)

    /** 最近一次恢复报告（应用内观察）；未恢复过为 null。 */
    fun observeLastReport(): StateFlow<ReminderRestoreReport?> = lastReport

    /** 尚未确认知悉的错过提醒（合并提示的数据源）。 */
    fun observeOutstandingMissed(): Flow<List<ReminderScheduleEntry>> =
        scheduleLogRepository.observeOutstandingMissed()

    /**
     * 设备变化后核对并重建提醒。默认手动触发；接收器与界面均调用本入口。
     * 串行执行，避免应用启动与界面同时恢复造成并发重复调度（幂等仍由同键覆盖保证）。
     */
    suspend fun restore(trigger: ReminderRestoreTrigger = ReminderRestoreTrigger.MANUAL): ReminderRestoreReport =
        mutex.withLock {
            val restoredAt = now()
            val device = currentDeviceZone()

            // 1) 先兑现错过：设备关闭/休眠/省电期间到点但仍待投递的调度 → 错过终态
            val newlyMissed = scheduleLogRepository.markMissedBefore(restoredAt, restoredAt)
            // 2) 终态抑制：已投递或已错过，不再重新调度（时间倒拨也不补响）
            val suppressed = scheduleLogRepository.listTerminalInstants()

            // 3) 未来提醒按真实本地记录重建，走既有设置提醒的同一套调度规则
            val taskOutcome = reminderUseCases.restoreAll(suppressed)
            val calendarOutcome = calendarReminderUseCases.restoreAll(suppressed)

            val environment = runCatching { capabilityProbe.snapshot() }.getOrElse {
                // 探针失败按“不可用”如实报告，绝不假装可用
                ReminderEnvironmentStatus(
                    notificationsAllowed = false,
                    exactAlarmsAllowed = false,
                    batteryOptimizationsIgnored = false,
                )
            }
            val missed = scheduleLogRepository.listOutstandingMissed()
            val zoneAnchors = collectZoneAnchors(restoredAt)
            val nextReminder = scheduleLogRepository.listPending()
                .minByOrNull { it.scheduledAtMillis }

            val scanned = taskOutcome.scannedCount + calendarOutcome.scannedCount
            val future = taskOutcome.rescheduledCount + calendarOutcome.rescheduledCount
            val notReady = taskOutcome.notReadyCount + calendarOutcome.notReadyCount
            val skipped = taskOutcome.skippedCount + calendarOutcome.skippedCount

            val report = ReminderRestoreReport(
                trigger = trigger,
                restoredAtMillis = restoredAt,
                scannedCount = scanned,
                futureScheduledCount = future,
                notReadyCount = notReady,
                skippedCount = skipped,
                newlyMissedCount = newlyMissed.size,
                missed = missed,
                environment = environment,
                zoneAnchors = zoneAnchors,
                nextReminder = nextReminder,
                messages = buildMessages(
                    trigger = trigger,
                    scanned = scanned,
                    future = future,
                    notReady = notReady,
                    newlyMissed = newlyMissed.size,
                    missedOutstanding = missed.size,
                    environment = environment,
                    zoneAnchors = zoneAnchors,
                    deviceZoneId = device.id,
                ),
            )
            lastReport.value = report
            report
        }

    /** 用户确认知悉全部错过提示；返回清除条数。错过记录仍保留终态，之后也不会补响。 */
    suspend fun acknowledgeMissed(): Int = scheduleLogRepository.acknowledgeMissed(now())

    /**
     * 时区核对之二：用户确认「按当前设备时区调整」。
     * - 待办：保留墙上时间（本地日期时刻不变），按当前时区重算截止 UTC 毫秒并改写锚定时区；
     * - 日程：复用 CalendarUseCases.reinterpretInZone 的既有语义（保留墙上时间）。
     * 相对提醒经既有联动钩子随截止/开始移动；绝不静默调用，只能由用户显式确认。
     */
    suspend fun confirmZoneAdjustment(ownerType: ReminderOwner, ownerId: String): Result<Unit> {
        val device = currentDeviceZone()
        return when (ownerType) {
            ReminderOwner.TASK -> {
                val task = tasksRepository.getTask(ownerId)
                    ?: return Result.failure(IllegalStateException("待办不存在或已永久删除"))
                val oldZone = runCatching { ZoneId.of(task.dueZoneId) }.getOrElse {
                    return Result.failure(IllegalArgumentException("原时区无效"))
                }
                val newDueAtMillis = task.dueAtMillis?.let { dueAt ->
                    Instant.ofEpochMilli(dueAt).atZone(oldZone).toLocalDateTime()
                        .atZone(device).toInstant().toEpochMilli()
                }
                taskUseCases.update(
                    ownerId,
                    TaskInput(
                        title = task.title,
                        note = task.note,
                        priority = task.priority,
                        category = task.category,
                        dueEpochDay = task.dueEpochDay,
                        dueAtMillis = newDueAtMillis,
                        dueZoneId = device.id,
                        recurrenceRule = task.recurrenceRule,
                    ),
                ).map { }
            }

            ReminderOwner.CALENDAR ->
                calendarUseCases.reinterpretInZone(ownerId, device.id).map { }
        }
    }

    private fun currentDeviceZone(): ZoneId =
        runCatching { deviceZone() }.getOrDefault(ZoneId.systemDefault())

    /**
     * 时区核对项：安排保留创建时区（待办 dueZoneId / 日程 timezoneId），设备时区已变化。
     * 同一事项只报告一次，并带上下一次未来提醒的时刻（按创建时区仍可核对）。
     */
    private suspend fun collectZoneAnchors(nowAtMillis: Long): List<ReminderZoneAnchor> {
        val deviceZoneId = currentDeviceZone().id
        val byOwner = LinkedHashMap<String, ReminderZoneAnchor>()

        remindersRepository.listAll().forEach { reminder ->
            val task = tasksRepository.getTask(reminder.taskId) ?: return@forEach
            if (task.trashedAt != null || task.status != TaskStatus.ACTIVE) return@forEach
            if (task.dueZoneId == deviceZoneId) return@forEach
            val nextAt = reminder.resolvedAt(task)?.takeIf { it >= nowAtMillis }
            mergeAnchor(
                byOwner,
                "TASK:${task.id}",
                ReminderZoneAnchor(
                    ownerType = ReminderOwner.TASK,
                    ownerId = task.id,
                    ownerTitle = task.title,
                    anchoredZoneId = task.dueZoneId,
                    deviceZoneId = deviceZoneId,
                    nextReminderAtMillis = nextAt,
                ),
            )
        }

        calendarRemindersRepository.listAll().forEach { reminder ->
            val entry = calendarRepository.getEntry(reminder.taskId) ?: return@forEach
            if (entry.trashedAt != null) return@forEach
            if (entry.timezoneId == deviceZoneId) return@forEach
            val nextAt = reminder.resolvedAtForEntry(entry)?.takeIf { it >= nowAtMillis }
            mergeAnchor(
                byOwner,
                "CALENDAR:${entry.id}",
                ReminderZoneAnchor(
                    ownerType = ReminderOwner.CALENDAR,
                    ownerId = entry.id,
                    ownerTitle = entry.title,
                    anchoredZoneId = entry.timezoneId,
                    deviceZoneId = deviceZoneId,
                    nextReminderAtMillis = nextAt,
                ),
            )
        }

        return byOwner.values.toList()
    }

    private fun mergeAnchor(
        byOwner: MutableMap<String, ReminderZoneAnchor>,
        key: String,
        anchor: ReminderZoneAnchor,
    ) {
        val existing = byOwner[key]
        if (existing == null) {
            byOwner[key] = anchor
            return
        }
        // 同一事项多条提醒：保留最早的下一次提醒时刻供核对
        val existingNext = existing.nextReminderAtMillis
        val candidateNext = anchor.nextReminderAtMillis
        if (existingNext == null || (candidateNext != null && candidateNext < existingNext)) {
            byOwner[key] = existing.copy(nextReminderAtMillis = candidateNext)
        }
    }

    /** 中文如实报告：既说明做了什么，也说明当前受限条件与平台无法保证的部分。 */
    private fun buildMessages(
        trigger: ReminderRestoreTrigger,
        scanned: Int,
        future: Int,
        notReady: Int,
        newlyMissed: Int,
        missedOutstanding: Int,
        environment: ReminderEnvironmentStatus,
        zoneAnchors: List<ReminderZoneAnchor>,
        deviceZoneId: String,
    ): List<String> = buildList {
        add(
            "本次由「${trigger.label}」触发：核对 $scanned 条提醒记录，重建 $future 条未来提醒；" +
                "同一提醒按同一调度键覆盖，不会创建重复闹钟。",
        )
        if (newlyMissed > 0) {
            add(
                "有 $newlyMissed 条提醒在设备关闭、休眠或省电限制期间错过：已合并为可查看提示，" +
                    "不会逐条补响，也不会自动延期或完成事项。",
            )
        } else if (missedOutstanding > 0) {
            add("当前仍有 $missedOutstanding 条错过提醒待查看；它们在设备变化期间未投递，不会补响。")
        }
        if (notReady > 0) {
            add(
                "有 $notReady 条提醒因缺少精确提醒权限未就绪：提醒与事项记录均已保留，" +
                    "请到系统设置允许「闹钟和提醒」后重新核对。",
            )
        }
        if (!environment.notificationsAllowed) {
            add("通知权限已关闭：提醒到点也无法在通知栏看到。请到系统设置开启知时鹿通知。")
        }
        if (!environment.exactAlarmsAllowed) {
            add(
                "精确提醒能力未开启：相关提醒保持未就绪，不会悄悄降级为非精确调度；" +
                    "请在系统设置允许「闹钟和提醒」。",
            )
        }
        if (!environment.batteryOptimizationsIgnored) {
            add(
                "系统省电或后台限制可能延迟甚至拦下提醒：建议允许知时鹿忽略电池优化，" +
                    "并在 ColorOS 中允许自启动与后台运行。",
            )
        }
        if (zoneAnchors.isNotEmpty()) {
            val zones = zoneAnchors.map { it.anchoredZoneId }.distinct().joinToString("、")
            add(
                "设备时区为 $deviceZoneId，${zoneAnchors.size} 条安排仍保留创建时区（$zones），" +
                    "请核对后确认是否按当前时区调整；不会静默修改。",
            )
        }
        add(
            "提醒已调度不等于已投递：OPPO Reno10（ColorOS 15）实测锁屏待机投递延迟 30 秒～2 分 31 秒，" +
                "省电模式下曾达 6 分 13 秒；本应用无法保证所有机型、省电设置下的精确到秒提醒。",
        )
    }
}
