package com.zhideer.app.domain.usecase

import com.zhideer.app.data.ai.AiProposalStore
import com.zhideer.app.domain.model.AiProposal
import com.zhideer.app.domain.model.AiProposalChanges
import com.zhideer.app.domain.model.AiProposalItem
import com.zhideer.app.domain.model.AiProposalKind
import com.zhideer.app.domain.model.AiProposalOp
import com.zhideer.app.domain.model.AiProposalStatus
import com.zhideer.app.domain.model.AiProposalTargetRecord
import com.zhideer.app.domain.model.CalendarEntry
import com.zhideer.app.domain.model.TaskDue
import com.zhideer.app.domain.model.TaskFilter
import com.zhideer.app.domain.model.TodoTask
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 参谋建议类型（T29/#30）：每类建议都必须由真实记录派生，绝不无中生有。
 */
enum class AdvisorSuggestionKind(val label: String) {
    /** 过期待办处理：日期/时刻截止已过且未完成（GLOSSARY：日期截止次日属过期）。 */
    OVERDUE_TASK("过期待办"),

    /** 重复待办长期未完成：下一次未完成出现在今天之前（GLOSSARY：错过不自动完成、不静默消失）。 */
    MISSED_RECURRENCE("重复待办错过"),

    /** 明日日程冲突：明天窗口内两条日程时间重叠（GLOSSARY：日程占用一段时间）。 */
    SCHEDULE_CONFLICT("明日日程冲突"),

    /** 长期记忆核对：活动记忆久未核对（GLOSSARY：长期记忆具有可追溯来源，核对是用户动作）。 */
    MEMORY_REVIEW("记忆核对"),
}

/**
 * 建议的一条依据引用：指向真实记录（id + 类型 + 标题），并附可核对的事实说明。
 * UI 展示依据列表，用户可据此到对应页面核对建议对象与来源（验收：建议与依据可核对）。
 */
data class AdvisorEvidence(
    val recordKind: AiProposalKind,
    val recordId: String,
    val title: String,
    val detail: String,
)

/**
 * 一条参谋建议（T29/#30，不持久化：每次主动生成时按真实仓库实时派生）。
 * - [evidence]：依据引用列表，绝不空（无依据不生成）；
 * - [proposalDraft]：转为操作提案的草稿条目（修改类，目标已绑定真实记录）；
 *   提醒式建议（明日日程冲突、记忆核对）为空列表——不编造调整方案，
 *   [AdvisorUseCases.suggestionToProposal] 对其拒绝并中文说明。
 */
data class AdvisorSuggestion(
    val id: String,
    val kind: AdvisorSuggestionKind,
    val title: String,
    val reason: String,
    val evidence: List<AdvisorEvidence>,
    val proposalDraft: List<AiProposalItem>,
) {
    /** 依据锚定的真实记录 id（单记录建议的失效判定与转提案核对使用）。 */
    val anchorRecordId: String? get() = evidence.firstOrNull()?.recordId
}

/**
 * 统一参谋建议用例入口（T29/#30）：主动生成有依据的建议 + 采纳转操作提案。
 *
 * 有依据（验收核心）：每条建议由本地规则引擎从真实仓库只读派生并引用依据记录，
 * 不依赖模型也能给出基础建议（离线可用）；不编造交通时间、空档或历史。
 * 规则（首版四类，见 [AdvisorSuggestionKind]）：
 * - 过期待办：[TaskFilter.OVERDUE] 真实过滤（含日期/时刻截止），建议今天补做或改期；
 * - 重复待办错过：同上过滤中的重复待办分支（下一次未完成出现在过去），建议补做这一次；
 * - 明日日程冲突：明天窗口内日程两两时间重叠（UTC 毫秒直接比较，不涉时区换算误差），
 *   提醒式建议、不编造新时间；
 * - 记忆核对：仅当 [AiContextCategory.MEMORY] 已授权才读取活动记忆（记忆是默认关闭的
 *   独立隐私数据），久未核对的记忆建议核对——未授权绝不读取、绝不产生该类建议。
 *
 * 采纳语义（GLOSSARY「操作提案」「参谋建议」）：[suggestionToProposal] 把建议草稿落为
 * PENDING 操作提案（sessionId = null，由参谋面板展示），此后完全复用既有提案状态机
 * （[AiProposalUseCases.confirmProposal] 确认执行、幂等、忽略）——建议采纳绝不直接写入，
 * 生成与查看建议本身也绝不修改任何业务事项。
 *
 * 失效语义（验收：拒绝过期操作）：建议不持久化、每次生成实时派生，记录已完成/取消/
 * 删除后重新生成自然不再出现；转提案时再对依据做一次新鲜度核对（待办必须仍在
 * 真实过期视图中且类型一致），依据已失效则拒绝并中文说明，绝不落半失效提案；
 * 落库后到确认前的一致性由既有提案快照（updatedAt 指纹）继续保障。
 *
 * 首版仅主动触发（验收）：生成只由用户显式点击发起，无后台自动生成、无主动通知。
 */
class AdvisorUseCases(
    private val taskUseCases: TaskUseCases,
    private val calendarUseCases: CalendarUseCases,
    // T29/#30 纯新增：长期记忆只读入口（记忆核对建议；仅 MEMORY 授权后使用）
    private val memoryUseCases: MemoryUseCases? = null,
    private val authorizer: AiContextAuthorizer,
    private val proposalStore: AiProposalStore,
    private val idGen: () -> String,
    private val now: () -> Long,
    private val zoneId: ZoneId,
) {

    /**
     * 按真实记录主动生成参谋建议（只读，绝不写任何仓库，含提案库）。
     * 返回顺序：过期待办、重复错过、明日冲突、记忆核对（各自有依据才出现，可混合）。
     */
    suspend fun generateSuggestions(): List<AdvisorSuggestion> {
        val suggestions = mutableListOf<AdvisorSuggestion>()
        suggestions += overdueTaskSuggestions()
        suggestions += scheduleConflictSuggestions()
        suggestions += memoryReviewSuggestions()
        return suggestions
    }

    /**
     * 采纳建议：转为操作提案并落提案库（PENDING），走既有状态机确认执行。
     * - 提醒式建议（无草稿）拒绝并中文说明；
     * - 幂等：同一建议已有未确认提案时直接返回既有提案，绝不重复落库；
     * - 依据新鲜度核对：待办必须仍在真实过期视图中且与建议类型一致
     *   （已完成/取消/删除/不再过期 → 拒绝），并按当前真实状态重算草稿与目标快照；
     * - 落库不写业务仓库；确认执行由调用方经 [AiProposalUseCases.confirmProposal] 完成。
     */
    suspend fun suggestionToProposal(suggestion: AdvisorSuggestion): Result<AiProposal> {
        if (suggestion.proposalDraft.isEmpty()) {
            return Result.failure(
                IllegalStateException("该建议是提醒类建议，不涉及任何写入：请按依据到对应页面核对处理"),
            )
        }
        // 幂等：同源建议已落提案且仍待确认 → 返回既有提案（重复点击不重复落库）
        val itemId = advisorItemId(suggestion.id)
        proposalStore.load()
            .firstOrNull { it.status == AiProposalStatus.PENDING && it.items.any { item -> item.id == itemId } }
            ?.let { return Result.success(it) }

        // 依据新鲜度核对：重新只读核对真实过期视图（含重复待办合成状态）
        val recordId = suggestion.anchorRecordId
            ?: return Result.failure(IllegalStateException("建议缺少依据记录，无法转为提案"))
        val fresh = taskUseCases.observe(TaskFilter.OVERDUE).first().find { it.id == recordId }
            ?: return Result.failure(
                IllegalStateException("建议依据已失效：该待办已完成、取消或已删除（或不再过期），本次未创建提案"),
            )
        val expectRecurring = suggestion.kind == AdvisorSuggestionKind.MISSED_RECURRENCE
        if ((fresh.recurrenceRule != null) != expectRecurring) {
            return Result.failure(
                IllegalStateException("建议依据已变化：待办状态与建议生成时不一致，请重新生成建议后再试；本次未创建提案"),
            )
        }
        val draft = when (suggestion.kind) {
            AdvisorSuggestionKind.OVERDUE_TASK ->
                overdueDraftFor(fresh, suggestion.id)
                    ?: return Result.failure(
                        IllegalStateException("建议依据已变化：时刻截止已改期无意义，请重新生成建议后再试；本次未创建提案"),
                    )
            AdvisorSuggestionKind.MISSED_RECURRENCE ->
                modifyTaskDraft(fresh, suggestion.id, AiProposalChanges(completed = true))
            else -> return Result.failure(IllegalStateException("该建议类型不支持转为操作提案"))
        }
        val proposal = AiProposal(
            id = idGen(),
            sessionId = null, // 参谋产生的提案：由参谋面板展示与确认，不属于任何聊天会话
            createdAt = now(),
            status = AiProposalStatus.PENDING,
            items = listOf(draft),
        )
        proposalStore.save(proposalStore.load() + proposal)
        return Result.success(proposal)
    }

    /** 参谋产生的全部提案（sessionId = null），供参谋面板展示与确认。 */
    fun observeAdvisorProposals(): Flow<List<AiProposal>> =
        proposalStore.observe().map { proposals -> proposals.filter { it.sessionId == null } }

    // ---------- 规则 1：过期待办与重复待办错过 ----------

    /**
     * 过期待办处理（每条真实过期待办一条建议）：
     * - 普通待办：日期截止 → 建议改期到今天（草稿 changes.dueEpochDay = 今天）；
     *   时刻截止 → 建议改期到今天同一时刻（保留用户原定的时刻，只移动日期）；
     *   今日时刻已过（改期到今天无意义）→ 降级为提醒式建议（无草稿）；
     * - 重复待办：建议补做错过的这一次（草稿 changes.completed = true，确认后经
     *   TaskUseCases.complete 走出现维度完成最早一次错过，本体保持进行中）。
     */
    private suspend fun overdueTaskSuggestions(): List<AdvisorSuggestion> {
        val today = TaskDue.todayEpochDay(zoneId.id, now())
        return taskUseCases.observe(TaskFilter.OVERDUE).first()
            .take(MAX_OVERDUE)
            .map { task ->
                if (task.recurrenceRule != null) {
                    missedRecurrenceSuggestion(task)
                } else {
                    overdueTaskSuggestion(task, today)
                }
            }
    }

    private fun missedRecurrenceSuggestion(task: TodoTask): AdvisorSuggestion {
        val rule = task.recurrenceRule
        val ruleText = rule?.summary().orEmpty()
        return AdvisorSuggestion(
            id = "missed-recurrence:${task.id}",
            kind = AdvisorSuggestionKind.MISSED_RECURRENCE,
            title = "补做重复待办「${task.title}」错过的出现",
            reason = "重复待办「${task.title}」（$ruleText）有未完成的错过出现：下一次未完成出现在今天之前。" +
                "建议今天补做这一次；采纳将起草「完成最早错过的一次」的操作提案，确认后才执行。",
            evidence = listOf(
                AdvisorEvidence(
                    recordKind = AiProposalKind.TASK,
                    recordId = task.id,
                    title = task.title,
                    detail = "重复待办，规则「$ruleText」，存在未完成的错过出现（已过期未补做）",
                ),
            ),
            proposalDraft = listOf(
                modifyTaskDraft(task, "missed-recurrence:${task.id}", AiProposalChanges(completed = true)),
            ),
        )
    }

    private fun overdueTaskSuggestion(task: TodoTask, today: Long): AdvisorSuggestion {
        val suggestionId = "overdue-task:${task.id}"
        val dueDetail: String
        val draft: AiProposalItem?
        when {
            task.dueEpochDay != null -> {
                val due = LocalDate.ofEpochDay(task.dueEpochDay)
                dueDetail = "日期截止 $due"
                draft = modifyTaskDraft(task, suggestionId, AiProposalChanges(dueEpochDay = today))
            }
            task.dueAtMillis != null -> {
                val due = Instant.ofEpochMilli(task.dueAtMillis).atZone(ZoneId.of(task.dueZoneId))
                dueDetail = "时刻截止 ${due.format(DATETIME_FMT)}"
                // 改期到今天同一时刻：保留用户原定时刻、只移动日期（不编造新时刻）
                val moved = due.plusDays(today - due.toLocalDate().toEpochDay())
                draft = if (moved.toInstant().toEpochMilli() != task.dueAtMillis) {
                    modifyTaskDraft(task, suggestionId, AiProposalChanges(dueAtMillis = moved.toInstant().toEpochMilli()))
                } else {
                    null // 今日该时刻已过：改期到今天无意义，降级为提醒式建议
                }
            }
            else -> {
                dueDetail = "无截止但已标记过期"
                draft = null
            }
        }
        return AdvisorSuggestion(
            id = suggestionId,
            kind = AdvisorSuggestionKind.OVERDUE_TASK,
            title = "处理过期待办「${task.title}」",
            reason = "待办「${task.title}」已过期未完成（$dueDetail）。建议今天补做或改期；" +
                (if (draft != null) "采纳将起草「把截止改到今天」的操作提案，确认后才执行。"
                else "建议尽快处理或到待办页手动调整截止。"),
            evidence = listOf(
                AdvisorEvidence(
                    recordKind = AiProposalKind.TASK,
                    recordId = task.id,
                    title = task.title,
                    detail = "待办「${task.title}」$dueDetail，状态未完成（已过期）",
                ),
            ),
            proposalDraft = listOfNotNull(draft),
        )
    }

    // ---------- 规则 2：明日日程冲突 ----------

    /**
     * 明日日程冲突提醒式建议：明天窗口（创建时区下明天 00:00 起一整天）内相交的日程
     * 两两按 UTC 毫秒判重叠，每个重叠对一条建议。依据 = 冲突的两条真实日程；
     * 不编造新的时间段，无操作提案草稿（调整由用户在日程页完成）。
     */
    private suspend fun scheduleConflictSuggestions(): List<AdvisorSuggestion> {
        val zone = zoneId
        val today = Instant.ofEpochMilli(now()).atZone(zone).toLocalDate()
        val windowStart = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val windowEnd = today.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        val tomorrow = calendarUseCases.observeAll().first()
            .filter { it.trashedAt == null && it.startMillis < windowEnd && it.endMillis > windowStart }
            .sortedBy { it.startMillis }
        val suggestions = mutableListOf<AdvisorSuggestion>()
        for (i in tomorrow.indices) {
            for (j in i + 1 until tomorrow.size) {
                val a = tomorrow[i]
                val b = tomorrow[j]
                if (a.endMillis > b.startMillis && a.startMillis < b.endMillis) {
                    suggestions += conflictSuggestion(a, b)
                    if (suggestions.size >= MAX_CONFLICT) return suggestions
                }
            }
        }
        return suggestions
    }

    private fun conflictSuggestion(a: CalendarEntry, b: CalendarEntry): AdvisorSuggestion {
        val overlapStart = maxOf(a.startMillis, b.startMillis)
        val overlapEnd = minOf(a.endMillis, b.endMillis)
        val overlapText = rangeText(overlapStart, overlapEnd, a.timezoneId)
        return AdvisorSuggestion(
            id = "schedule-conflict:${a.id}:${b.id}",
            kind = AdvisorSuggestionKind.SCHEDULE_CONFLICT,
            title = "明天日程时间重叠：「${a.title}」与「${b.title}」",
            reason = "两条日程在明天 $overlapText 重叠，无法同时进行。建议核对并调整其中一条的时间；" +
                "这是提醒类建议，不编造调整方案，请到日程页处理。",
            evidence = listOf(scheduleEvidence(a), scheduleEvidence(b)),
            proposalDraft = emptyList(),
        )
    }

    private fun scheduleEvidence(entry: CalendarEntry): AdvisorEvidence = AdvisorEvidence(
        recordKind = AiProposalKind.SCHEDULE,
        recordId = entry.id,
        title = entry.title,
        detail = "日程「${entry.title}」：${entryRangeText(entry)}" +
            (if (entry.location.isBlank()) "" else "，地点 ${entry.location}"),
    )

    // ---------- 规则 3：长期记忆核对 ----------

    /**
     * 记忆核对建议（提醒式）：仅当 MEMORY 类别已授权才读取活动记忆（未授权绝不读取、
     * 绝不产生该类建议——记忆是默认关闭的独立隐私数据，复用 #19 授权门）；
     * 久未核对（超过 [MEMORY_REVIEW_AFTER_DAYS] 天）的活动记忆建议核对，
     * 依据 = 该条真实记忆原文（可在记忆页核对）；不自动推断偏好、不自动打人格标签，
     * 核对/停用/删除由用户在记忆页完成，无操作提案草稿。
     */
    private suspend fun memoryReviewSuggestions(): List<AdvisorSuggestion> {
        val grant = authorizer.currentGrant()
        if (grant == null || !grant.contains(AiContextCategory.MEMORY)) return emptyList()
        val memories = memoryUseCases ?: return emptyList()
        val cutoff = now() - MEMORY_REVIEW_AFTER_DAYS * DAY_MILLIS
        return memories.observeActive().first()
            .filter { it.verifiedAt < cutoff }
            .take(MAX_MEMORY)
            .map { memory ->
                AdvisorSuggestion(
                    id = "memory-review:${memory.id}",
                    kind = AdvisorSuggestionKind.MEMORY_REVIEW,
                    title = "核对长期记忆是否仍然准确",
                    reason = "这条${memory.type.label}类记忆已超过 $MEMORY_REVIEW_AFTER_DAYS 天未核对" +
                        "（最近核对 ${Instant.ofEpochMilli(memory.verifiedAt).atZone(zoneId).format(DATE_FMT)}），" +
                        "建议确认它是否仍然适用；可到记忆页核对、停用或删除。这是提醒类建议，不修改任何记忆。",
                    evidence = listOf(
                        AdvisorEvidence(
                            recordKind = AiProposalKind.MEMORY,
                            recordId = memory.id,
                            title = memory.content,
                            detail = "活动长期记忆（${memory.type.label}），最近核关于 " +
                                Instant.ofEpochMilli(memory.verifiedAt).atZone(zoneId).format(DATE_FMT),
                        ),
                    ),
                    proposalDraft = emptyList(),
                )
            }
    }

    // ---------- 草稿构造（目标绑定真实记录，供提案卡片预览与执行一致性校验） ----------

    private fun modifyTaskDraft(task: TodoTask, suggestionId: String, changes: AiProposalChanges): AiProposalItem =
        AiProposalItem(
            id = advisorItemId(suggestionId), // 确定性条目 id：幂等判定与来源可追溯
            kind = AiProposalKind.TASK,
            title = task.title,
            op = AiProposalOp.MODIFY,
            targetTitle = task.title,
            changes = changes,
            targetRecordId = task.id,
            targetRecord = task.toTargetRecord(),
        )

    /** 过期待办的改期草稿；改期无意义（今日时刻已过等）时返回 null（提醒式）。 */
    private fun overdueDraftFor(task: TodoTask, suggestionId: String): AiProposalItem? {
        val today = TaskDue.todayEpochDay(zoneId.id, now())
        return when {
            task.dueEpochDay != null ->
                modifyTaskDraft(task, suggestionId, AiProposalChanges(dueEpochDay = today))
            task.dueAtMillis != null -> {
                val due = Instant.ofEpochMilli(task.dueAtMillis).atZone(ZoneId.of(task.dueZoneId))
                val moved = due.plusDays(today - due.toLocalDate().toEpochDay())
                if (moved.toInstant().toEpochMilli() != task.dueAtMillis) {
                    modifyTaskDraft(task, suggestionId, AiProposalChanges(dueAtMillis = moved.toInstant().toEpochMilli()))
                } else {
                    null
                }
            }
            else -> null
        }
    }

    private fun TodoTask.toTargetRecord(): AiProposalTargetRecord = AiProposalTargetRecord(
        recordId = id,
        title = title,
        updatedAt = updatedAt, // 一致性指纹：确认前被手动修改则既有状态机拒绝覆盖
        note = note,
        priority = priority.name,
        category = category,
        dueEpochDay = dueEpochDay,
        dueAtMillis = dueAtMillis,
        status = status.name,
    )

    private fun entryRangeText(entry: CalendarEntry): String {
        val zone = ZoneId.of(entry.timezoneId)
        val start = Instant.ofEpochMilli(entry.startMillis).atZone(zone)
        val end = Instant.ofEpochMilli(entry.endMillis).atZone(zone)
        return if (start.toLocalDate() == end.toLocalDate()) {
            "${start.format(DATETIME_FMT)}～${end.format(TIME_FMT)}"
        } else {
            "${start.format(DATETIME_FMT)}～${end.format(DATETIME_FMT)}"
        }
    }

    private fun rangeText(startMillis: Long, endMillis: Long, timezoneId: String): String {
        val zone = ZoneId.of(timezoneId)
        return "${Instant.ofEpochMilli(startMillis).atZone(zone).format(DATETIME_FMT)}～" +
            Instant.ofEpochMilli(endMillis).atZone(zone).format(DATETIME_FMT)
    }

    private companion object {
        const val DAY_MILLIS = 24 * 3_600_000L

        /** 每类建议条数上限：只呈现少量相关建议，避免刷屏。 */
        const val MAX_OVERDUE = 5
        const val MAX_CONFLICT = 8
        const val MAX_MEMORY = 3

        /** 记忆核对阈值：最近核对时间早于该天数的活动记忆才建议核对。 */
        const val MEMORY_REVIEW_AFTER_DAYS = 30L

        /** 参谋提案条目 id 前缀：确定性派生自建议 id，供幂等判定与来源区分。 */
        const val ITEM_PREFIX = "advisor-"

        fun advisorItemId(suggestionId: String): String = ITEM_PREFIX + suggestionId

        val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val DATETIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
