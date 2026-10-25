package com.zhideer.app.domain.usecase

import com.zhideer.app.data.ai.AdvisorRecordStore
import com.zhideer.app.data.ai.AiProposalStore
import com.zhideer.app.domain.model.AdvisorAdoptionOutcome
import com.zhideer.app.domain.model.AdvisorAdoptionRecord
import com.zhideer.app.domain.model.AdvisorEvidenceSnapshot
import com.zhideer.app.domain.model.AdvisorFeedbackRecord
import com.zhideer.app.domain.model.AdvisorFeedbackType
import com.zhideer.app.domain.model.AdvisorProposalBinding
import com.zhideer.app.domain.model.AdvisorRecords
import com.zhideer.app.domain.model.AiProposal
import com.zhideer.app.domain.model.AiProposalChanges
import com.zhideer.app.domain.model.AiProposalItem
import com.zhideer.app.domain.model.AiProposalKind
import com.zhideer.app.domain.model.AiProposalOp
import com.zhideer.app.domain.model.AiProposalStatus
import com.zhideer.app.domain.model.AiProposalTargetRecord
import com.zhideer.app.domain.model.CalendarEntry
import com.zhideer.app.domain.model.Memory
import com.zhideer.app.domain.model.TaskDue
import com.zhideer.app.domain.model.TaskFilter
import com.zhideer.app.domain.model.TodoTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * 建议的一条依据引用：指向真实记录（id + 类型 + 标题），并附可核对的事实说明与内容指纹。
 * UI 展示依据列表，用户可据此到对应页面核对建议对象与来源（验收：建议与依据可核对）；
 * [recordFingerprint] 是生成时真实记录的内容快照，采纳前与确认前重新比对，
 * 记录被修改、完成、删除或失效后旧建议/旧提案不可直接执行（T30/#31）。
 */
data class AdvisorEvidence(
    val recordKind: AiProposalKind,
    val recordId: String,
    val title: String,
    val detail: String,
    val recordFingerprint: String = "",
)

/**
 * 一条参谋建议（T29/#30，不持久化：每次主动生成时按真实仓库实时派生）。
 * - [evidence]：依据引用列表，绝不空（无依据不生成）；
 * - [proposalDraft]：转为操作提案的草稿条目（修改类，目标已绑定真实记录）；
 *   提醒式建议（明日日程冲突、记忆核对）为空列表——不编造调整方案，
 *   [AdvisorUseCases.suggestionToProposal] 对其拒绝并中文说明；
 * - [basisFingerprint]：全部依据记录指纹的稳定组合，采纳时与当前真实记录比对。
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

    /** 依据指纹：建议 id + 类型 + 每条依据记录的内容指纹，供采纳/确认前核对依据是否变化。 */
    val basisFingerprint: String
        get() = buildString {
            append(id).append('|').append(kind.name)
            evidence.forEach { e ->
                append('|').append(e.recordKind.name).append(':').append(e.recordId).append(':').append(e.recordFingerprint)
            }
        }
}

/**
 * 统一参谋建议用例入口（T29/#30，T30/#31 补齐确认采纳与反馈追溯）。
 *
 * 有依据（T29）：每条建议由本地规则引擎从真实仓库只读派生并引用依据记录，
 * 不依赖模型也能给出基础建议（离线可用）；不编造交通时间、空档或历史。
 *
 * 采纳语义（GLOSSARY「操作提案」「参谋建议」）：[suggestionToProposal] 把建议草稿落为
 * PENDING 操作提案（sessionId = null，由参谋面板展示），此后完全复用既有提案状态机
 * （[AiProposalUseCases.confirmProposal] 确认执行、幂等、忽略）——建议采纳绝不直接写入。
 * T30/#31 起：
 * - 点击采纳先经 [adoptionPreview] 展示将创建或修改的事项，所有写入仍须显式确认；
 * - 采纳时重新核对依据指纹，依据记录被修改/完成/删除/失效即拒绝并要求重新生成；
 * - 确认执行经 [confirmAdoptedProposal]：先再次核对依据指纹（确认前被改则旧提案失效），
 *   真实执行成功才记录 ADOPTED；失败、取消、未知结果都不标已采纳；
 * - 执行记录按提案 id 唯一，重复采纳/响应丢失后可核对，不重复产生事项；
 * - 反馈（忽略/调整/不再建议）经 [recordFeedback] 持久化追溯，不写长期记忆。
 *
 * 失效语义（验收：拒绝过期操作）：建议不持久化、每次生成实时派生，记录已完成/取消/
 * 删除后重新生成自然不再出现；旧建议/旧提案在依据变化后不可执行，必须重新生成并确认。
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
    // T30/#31 纯新增：反馈、采纳执行记录与提案依据绑定的持久化
    private val recordStore: AdvisorRecordStore,
    // T30/#31 纯新增：确认执行复用既有提案状态机（由参谋用例统一记录采纳结果）；
    // 可空以保持既有测试/装配的最小构造，缺省时确认返回中文错误。
    private val proposalUseCases: AiProposalUseCases? = null,
    private val idGen: () -> String,
    private val now: () -> Long,
    private val zoneId: ZoneId,
) {

    /** 采纳串行化：重复点击采纳不产生多条提案（确认竞态由提案状态机的原子抢占处理）。 */
    private val adoptMutex = Mutex()

    /**
     * 按真实记录主动生成参谋建议（只读，绝不写任何仓库，含提案库）。
     * 返回顺序：过期待办、重复错过、明日冲突、记忆核对（各自有依据才出现，可混合）。
     * 「不再建议」反馈过的建议不再生成（只过滤该建议，不推断长期人格标签）。
     */
    suspend fun generateSuggestions(): List<AdvisorSuggestion> {
        val muted = recordStore.load().feedback
            .filter { it.type == AdvisorFeedbackType.NEVER_SUGGEST }
            .map { it.suggestionId }
            .toSet()
        val suggestions = mutableListOf<AdvisorSuggestion>()
        suggestions += overdueTaskSuggestions()
        suggestions += scheduleConflictSuggestions()
        suggestions += memoryReviewSuggestions()
        return suggestions.filterNot { it.id in muted }
    }

    /**
     * 采纳前预览（T30/#31，验收：点击采纳先显示将创建或修改的事项）：
     * 返回将创建/修改/删除的对象与字段变化（复用提案卡片同一口径），
     * 只读、绝不落提案或写业务仓库；提醒类建议说明不涉及写入。
     */
    fun adoptionPreview(suggestion: AdvisorSuggestion): List<String> {
        if (suggestion.proposalDraft.isEmpty()) {
            return listOf("该建议是提醒类建议，不涉及任何写入：请按依据到对应页面核对处理")
        }
        return suggestion.proposalDraft.map { item ->
            when (item.op) {
                AiProposalOp.CREATE -> "将创建${kindCn(item.kind)}「${item.title}」"
                AiProposalOp.MODIFY -> {
                    val changes = proposalChangePreviewLines(item, zoneId)
                    "将修改${kindCn(item.kind)}「${item.targetRecord?.title ?: item.title}」" +
                        if (changes.isEmpty()) "（无字段变化）" else "：" + changes.joinToString("；")
                }
                AiProposalOp.DELETE ->
                    "将删除${kindCn(item.kind)}「${item.targetRecord?.title ?: item.title}」：" +
                        proposalDeleteActionText(item)
            }
        }
    }

    /**
     * 采纳建议：转为操作提案并落提案库（PENDING），走既有状态机确认执行。
     * - 提醒式建议（无草稿）拒绝并中文说明；
     * - 依据指纹重新核对：依据记录被修改/完成/删除/失效 → 拒绝并要求重新生成；
     * - 幂等：同一建议已有未确认/已执行提案时返回既有提案，绝不重复落库；
     * - 依据变化后重新生成的新建议：旧 PENDING 提案自动失效，按新依据重新落提案；
     * - 落库不写业务仓库；确认执行由 [confirmAdoptedProposal] 完成。
     */
    suspend fun suggestionToProposal(suggestion: AdvisorSuggestion): Result<AiProposal> =
        adoptMutex.withLock { suggestionToProposalLocked(suggestion) }

    private suspend fun suggestionToProposalLocked(suggestion: AdvisorSuggestion): Result<AiProposal> {
        if (suggestion.proposalDraft.isEmpty()) {
            return Result.failure(
                IllegalStateException("该建议是提醒类建议，不涉及任何写入：请按依据到对应页面核对处理"),
            )
        }
        // T30/#31：依据指纹重新核对（生成后被修改/完成/删除/失效 → 必须重新生成）
        staleEvidenceReason(suggestion.evidence.map { it.toSnapshot() })?.let { reason ->
            return Result.failure(
                IllegalStateException("建议依据已变化：$reason。请重新生成建议并确认；本次未创建提案"),
            )
        }
        // 幂等与旧提案失效：同一建议的最新提案绑定
        val binding = recordStore.load().bindings.lastOrNull { it.suggestionId == suggestion.id }
        if (binding != null) {
            val existing = proposalStore.load().find { it.id == binding.proposalId }
            if (existing != null) {
                if (binding.basisFingerprint == suggestion.basisFingerprint) {
                    when (existing.status) {
                        AiProposalStatus.PENDING -> return Result.success(existing)
                        AiProposalStatus.CONFIRMED -> return Result.failure(
                            IllegalStateException(
                                "该建议的提案已确认，执行结果未知：请先到待办页面核对真实记录，不要重复采纳",
                            ),
                        )
                        AiProposalStatus.EXECUTED -> {
                            if (executionSucceeded(existing)) return Result.success(existing)
                            // 全部失败未产生任何改动：允许重新采纳；部分失败必须先核对真实记录
                            if (!existing.resultMessage.orEmpty().startsWith("全部执行失败")) {
                                return Result.failure(
                                    IllegalStateException(
                                        "上次采纳执行失败（可能已有部分改动）：请先核对真实记录，再重新生成建议后确认",
                                    ),
                                )
                            }
                        }
                        AiProposalStatus.EXPIRED -> Unit // 已忽略/已失效：允许重新采纳生成新提案
                    }
                } else {
                    when (existing.status) {
                        AiProposalStatus.PENDING -> expireProposal(
                            existing.id,
                            "依据已变化：旧提案已失效，未执行任何写入；请重新生成建议并确认",
                        )
                        AiProposalStatus.CONFIRMED -> return Result.failure(
                            IllegalStateException(
                                "该建议的提案已确认，执行结果未知：请先核对真实记录，不要重复采纳",
                            ),
                        )
                        AiProposalStatus.EXECUTED, AiProposalStatus.EXPIRED -> Unit
                    }
                }
            }
        } else {
            // 兼容旧数据（无绑定）：既有 PENDING 提案按确定性条目 id 幂等返回
            proposalStore.load()
                .firstOrNull { it.status == AiProposalStatus.PENDING && it.items.any { item -> item.id == advisorItemId(suggestion.id) } }
                ?.let { return Result.success(it) }
        }

        // 语义新鲜度核对：重新只读核对真实过期视图（含重复待办合成状态），按当前真实状态重算草稿
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
        recordStore.bindProposal(
            AdvisorProposalBinding(
                proposalId = proposal.id,
                suggestionId = suggestion.id,
                suggestionKind = suggestion.kind.name,
                title = suggestion.title,
                evidence = suggestion.evidence.map { it.toSnapshot() },
                basisFingerprint = suggestion.basisFingerprint,
                createdAt = now(),
            ),
        )
        return Result.success(proposal)
    }

    /** 参谋产生的全部提案（sessionId = null），供参谋面板展示与确认。 */
    fun observeAdvisorProposals(): Flow<List<AiProposal>> =
        proposalStore.observe().map { proposals -> proposals.filter { it.sessionId == null } }

    // ---------- T30/#31 采纳确认与执行记录 ----------

    /**
     * 确认采纳（复用既有提案状态机）：
     * 1. 依据指纹再次核对：确认前记录被修改/完成/删除/失效 → 旧提案置 EXPIRED，
     *    未执行任何写入并如实回执（须重新生成建议并确认）；
     * 2. 真实执行成功（全部条目成功）才记录 [AdvisorAdoptionOutcome.ADOPTED]；
     *    失败/部分失败记 FAILED，已取消记 CANCELLED，执行中/中断记 UNKNOWN——都不标已采纳；
     * 3. 重复确认幂等：已采纳直接返回既有提案，不重放写入；执行记录仍只有一条。
     */
    suspend fun confirmAdoptedProposal(proposalId: String): Result<AiProposal> {
        val proposal = proposalStore.load().find { it.id == proposalId }
            ?: return Result.failure(IllegalStateException("操作提案不存在或已清除"))
        val binding = recordStore.load().bindings.lastOrNull { it.proposalId == proposalId }
            ?: return proposalUseCases?.confirmProposal(proposalId)
            ?: Result.failure(IllegalStateException("采纳确认入口未配置，无法确认该提案"))
        val existingAdoption = adoptionFor(proposalId)
        if (existingAdoption?.outcome == AdvisorAdoptionOutcome.ADOPTED) {
            // 已采纳为终态：重复确认幂等返回既有提案，不重放写入
            return Result.success(proposal)
        }
        return when (proposal.status) {
            AiProposalStatus.EXECUTED -> {
                val outcome = if (executionSucceeded(proposal)) {
                    AdvisorAdoptionOutcome.ADOPTED
                } else {
                    AdvisorAdoptionOutcome.FAILED
                }
                recordAdoption(proposal, binding, outcome, proposal.resultMessage.orEmpty())
                Result.success(proposal)
            }
            AiProposalStatus.CONFIRMED -> {
                val detail = "该提案已确认过，执行结果未知：请到待办页面核对真实记录，不要重复确认"
                recordAdoption(proposal, binding, AdvisorAdoptionOutcome.UNKNOWN, detail)
                Result.failure(IllegalStateException(detail))
            }
            AiProposalStatus.EXPIRED -> {
                recordAdoption(
                    proposal,
                    binding,
                    AdvisorAdoptionOutcome.CANCELLED,
                    proposal.resultMessage.orEmpty().ifBlank { "提案已被忽略或已失效，未执行任何写入" },
                )
                Result.failure(IllegalStateException("该提案已被忽略或已失效，无法确认创建"))
            }
            AiProposalStatus.PENDING -> {
                val stale = staleEvidenceReason(binding.evidence)
                if (stale != null) {
                    val reason = "依据已变化：$stale。旧提案已失效，未执行任何写入；请重新生成建议并确认"
                    expireProposal(proposalId, reason)
                    recordAdoption(proposal, binding, AdvisorAdoptionOutcome.INVALIDATED, reason)
                    return Result.failure(IllegalStateException(reason))
                }
                val useCases = proposalUseCases
                    ?: return Result.failure(IllegalStateException("采纳确认入口未配置，无法确认该提案"))
                try {
                    useCases.confirmProposal(proposalId).fold(
                        onSuccess = { executed ->
                            val outcome = if (executionSucceeded(executed)) {
                                AdvisorAdoptionOutcome.ADOPTED
                            } else {
                                AdvisorAdoptionOutcome.FAILED
                            }
                            recordAdoption(executed, binding, outcome, executed.resultMessage.orEmpty())
                            Result.success(executed)
                        },
                        onFailure = { e ->
                            val fresh = proposalStore.load().find { it.id == proposalId }
                            val outcome = when (fresh?.status) {
                                AiProposalStatus.CONFIRMED -> AdvisorAdoptionOutcome.UNKNOWN
                                AiProposalStatus.EXPIRED -> AdvisorAdoptionOutcome.CANCELLED
                                AiProposalStatus.EXECUTED ->
                                    if (executionSucceeded(fresh)) AdvisorAdoptionOutcome.ADOPTED
                                    else AdvisorAdoptionOutcome.FAILED
                                else -> AdvisorAdoptionOutcome.FAILED
                            }
                            recordAdoption(
                                fresh ?: proposal,
                                binding,
                                outcome,
                                e.message ?: "确认执行失败，结果待核实",
                            )
                            Result.failure(e)
                        },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // 异常中断结果未知：不标已采纳，记录待核实
                    val detail = "确认执行过程中发生异常，结果待核实：${e.message ?: "未知原因"}"
                    recordAdoption(proposal, binding, AdvisorAdoptionOutcome.UNKNOWN, detail)
                    Result.failure(IllegalStateException(detail))
                }
            }
        }
    }

    /**
     * 取消采纳（用户主动忽略待确认提案）：走既有忽略入口置 EXPIRED，不执行任何写入，
     * 记录 CANCELLED（不标已采纳）；已执行/执行中的提案不可取消。
     */
    suspend fun cancelAdoptedProposal(proposalId: String): Result<Unit> {
        val proposal = proposalStore.load().find { it.id == proposalId }
            ?: return Result.failure(IllegalStateException("操作提案不存在或已清除"))
        val binding = recordStore.load().bindings.lastOrNull { it.proposalId == proposalId }
            ?: return proposalUseCases?.dismissProposal(proposalId)
            ?: Result.failure(IllegalStateException("取消入口未配置，无法取消该提案"))
        return when (proposal.status) {
            AiProposalStatus.PENDING -> {
                val dismissed = proposalUseCases?.dismissProposal(proposalId)
                    ?: runCatching { expireProposal(proposalId, "用户取消采纳：未执行任何写入") }
                        .map { if (it) Unit else error("该提案已被确认或忽略") }
                dismissed.fold(
                    onSuccess = {
                        recordAdoption(
                            proposal,
                            binding,
                            AdvisorAdoptionOutcome.CANCELLED,
                            "用户取消采纳：未执行任何写入",
                        )
                        Result.success(Unit)
                    },
                    onFailure = { e ->
                        val fresh = proposalStore.load().find { it.id == proposalId }
                        val outcome = when (fresh?.status) {
                            AiProposalStatus.CONFIRMED -> AdvisorAdoptionOutcome.UNKNOWN
                            AiProposalStatus.EXECUTED -> AdvisorAdoptionOutcome.UNKNOWN
                            else -> AdvisorAdoptionOutcome.CANCELLED
                        }
                        recordAdoption(fresh ?: proposal, binding, outcome, e.message ?: "取消失败")
                        Result.failure(e)
                    },
                )
            }
            AiProposalStatus.EXPIRED -> {
                recordAdoption(
                    proposal,
                    binding,
                    AdvisorAdoptionOutcome.CANCELLED,
                    "用户取消采纳：未执行任何写入",
                )
                Result.success(Unit)
            }
            AiProposalStatus.CONFIRMED -> {
                val detail = "该提案已确认，执行结果待核实，无法取消"
                recordAdoption(proposal, binding, AdvisorAdoptionOutcome.UNKNOWN, detail)
                Result.failure(IllegalStateException(detail))
            }
            AiProposalStatus.EXECUTED -> Result.failure(
                IllegalStateException("该提案已执行完成，无法取消；如需撤销请到待办页面手动调整"),
            )
        }
    }

    // ---------- T33/#34 备份恢复 ----------

    /**
     * 备份恢复后的参谋记录还原（T33/#34）：
     * - 备份中的反馈、采纳执行记录与提案依据绑定原样保存，保留建议使用与执行的追溯能力；
     * - 恢复前仍待确认的参谋提案一并失效（业务数据已被整库替换，旧依据不可直接执行，
     *   必须重新生成建议并确认）；已执行、结果未知或已失效的提案不改写；
     * - 不授予任何 AI 上下文授权、不写长期记忆。
     */
    suspend fun restoreAdvisorRecords(records: AdvisorRecords) {
        // 恢复前仍待确认的参谋提案（sessionId = null）一律失效：业务数据已被整库替换，
        // 旧依据不可直接执行，避免恢复后直接沿用旧方案；已执行/未知/已失效状态不改写。
        val staleReason = "数据已从备份恢复：旧建议需重新生成并确认，未执行任何写入"
        proposalStore.load()
            .filter { it.sessionId == null && it.status == AiProposalStatus.PENDING }
            .forEach { expireProposal(it.id, staleReason) }
        recordStore.replaceAll(records)
    }

    /** 全部反馈（按保存顺序，供追溯）。 */
    fun observeFeedback(): Flow<List<AdvisorFeedbackRecord>> = recordStore.observe().map { it.feedback }

    /** 全部采纳执行记录（按写入顺序，供核对）。 */
    fun observeAdoptions(): Flow<List<AdvisorAdoptionRecord>> = recordStore.observe().map { it.adoptions }

    /** 单条提案的采纳执行记录（没有执行过则为 null）。 */
    suspend fun adoptionFor(proposalId: String): AdvisorAdoptionRecord? =
        recordStore.load().adoptions.firstOrNull { it.proposalId == proposalId }

    /** 某条建议的全部反馈记录（按保存顺序）。 */
    suspend fun feedbackFor(suggestionId: String): List<AdvisorFeedbackRecord> =
        recordStore.load().feedback.filter { it.suggestionId == suggestionId }

    /**
     * 保存反馈（T30/#31）：忽略/调整/不再建议均可追溯；[adjustmentNote] 为调整说明。
     * 反馈只写参谋记录存储，绝不写长期记忆，也不自动推断人格标签（验收：反馈不写长期记忆）。
     */
    suspend fun recordFeedback(
        suggestion: AdvisorSuggestion,
        type: AdvisorFeedbackType,
        adjustmentNote: String = "",
    ): Result<AdvisorFeedbackRecord> {
        val note = adjustmentNote.trim()
        if (type == AdvisorFeedbackType.ADJUST && note.isEmpty()) {
            return Result.failure(
                IllegalArgumentException("调整建议需要说明调整内容（例如改为明天），便于追溯；本次未保存反馈"),
            )
        }
        val record = AdvisorFeedbackRecord(
            id = idGen(),
            suggestionId = suggestion.id,
            suggestionKind = suggestion.kind.name,
            title = suggestion.title,
            type = type,
            adjustmentNote = note,
            evidence = suggestion.evidence.map { it.toSnapshot() },
            createdAt = now(),
        )
        recordStore.appendFeedback(record)
        return Result.success(record)
    }

    /**
     * 忽略建议 = 保存 IGNORE 反馈 + 让该建议已落库的待确认提案失效（不可再直接执行）。
     * 不写业务仓库、不写长期记忆。
     */
    suspend fun ignoreSuggestion(suggestion: AdvisorSuggestion): Result<AdvisorFeedbackRecord> {
        val feedback = recordFeedback(suggestion, AdvisorFeedbackType.IGNORE).getOrElse { return Result.failure(it) }
        val bindings = recordStore.load().bindings.filter { it.suggestionId == suggestion.id }
        val proposals = proposalStore.load()
        bindings.forEach { binding ->
            val proposal = proposals.find { it.id == binding.proposalId } ?: return@forEach
            if (proposal.status == AiProposalStatus.PENDING) {
                val reason = "建议已被忽略：该提案已失效，未执行任何写入"
                if (expireProposal(proposal.id, reason)) {
                    recordAdoption(proposal, binding, AdvisorAdoptionOutcome.CANCELLED, reason)
                }
            }
        }
        return Result.success(feedback)
    }

    // ---------- 依据指纹与执行结果判定 ----------

    /** 依据记录当前指纹；记录不存在（已彻底删除）返回 null。 */
    private suspend fun currentFingerprint(kind: AiProposalKind, recordId: String): String? = when (kind) {
        AiProposalKind.TASK -> taskUseCases.get(recordId)?.fingerprint()
        AiProposalKind.SCHEDULE -> calendarUseCases.get(recordId)?.fingerprint()
        AiProposalKind.MEMORY -> memoryUseCases?.get(recordId)?.fingerprint()
        // 参谋建议不产生便签依据：无读取入口时视为不可核对（保守判为失效）
        AiProposalKind.NOTE -> null
        // T25/#26：系统闹钟不是本地记录（应用无法读取），不产生建议依据
        AiProposalKind.ALARM -> null
    }

    /** 依据失效原因（null = 仍与生成时一致）；修改、完成、删除、失效都会改变指纹或使记录消失。 */
    private suspend fun staleEvidenceReason(evidence: List<AdvisorEvidenceSnapshot>): String? {
        for (e in evidence) {
            val current = currentFingerprint(e.recordKind, e.recordId)
                ?: return "依据记录已删除或不可用（${e.title}）"
            if (current != e.recordFingerprint) {
                return "依据记录已被修改、完成或失效（${e.title}）"
            }
        }
        return null
    }

    /** 既有提案的真实执行是否全部成功（旧提案无该字段时按中文回执判断）。 */
    private fun executionSucceeded(proposal: AiProposal): Boolean =
        proposal.executionSucceeded
            ?: (proposal.status == AiProposalStatus.EXECUTED &&
                !proposal.resultMessage.orEmpty().contains("执行失败"))

    /** 旧 PENDING 提案失效（不可再确认）；返回是否成功转移。 */
    private suspend fun expireProposal(proposalId: String, reason: String): Boolean =
        proposalStore.transition(proposalId, setOf(AiProposalStatus.PENDING)) {
            it.copy(status = AiProposalStatus.EXPIRED, resultMessage = reason)
        } != null

    private suspend fun recordAdoption(
        proposal: AiProposal,
        binding: AdvisorProposalBinding,
        outcome: AdvisorAdoptionOutcome,
        detail: String,
    ) {
        recordStore.upsertAdoption(
            AdvisorAdoptionRecord(
                id = idGen(),
                suggestionId = binding.suggestionId,
                suggestionKind = binding.suggestionKind,
                title = binding.title,
                proposalId = proposal.id,
                outcome = outcome,
                detail = detail,
                createdAt = now(),
            ),
        )
    }

    private fun AdvisorEvidence.toSnapshot(): AdvisorEvidenceSnapshot = AdvisorEvidenceSnapshot(
        recordKind = recordKind,
        recordId = recordId,
        title = title,
        detail = detail,
        recordFingerprint = recordFingerprint,
    )

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
                    recordFingerprint = task.fingerprint(),
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
                    recordFingerprint = task.fingerprint(),
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
        recordFingerprint = entry.fingerprint(),
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
                            recordFingerprint = memory.fingerprint(),
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

    // ---------- 内容指纹（T30/#31）：生成/采纳/确认三处比对同一依据是否变化 ----------

    // 指纹取“用户可见内容”而非 updatedAt：建议执行本身可能刷新 updatedAt（如重复待办
    // 完成一次出现时字段更新），那不算依据变化；内容字段变化才要求重新生成。
    private fun TodoTask.fingerprint(): String = "TASK|" + listOf(
        id, title, note, priority.name, category,
        dueEpochDay, dueAtMillis, dueZoneId, status.name,
        trashedAt, createdAt, completedAt, postponedAt, cancelledAt,
        recurrenceRule,
    ).joinToString("|") { it?.toString().orEmpty() }

    private fun CalendarEntry.fingerprint(): String = "SCHEDULE|" + listOf(
        id, title, startMillis, endMillis, timezoneId, location, note, category,
        taskId, trashedAt, zoneConfirmedAt, createdAt,
    ).joinToString("|") { it?.toString().orEmpty() }

    private fun Memory.fingerprint(): String = "MEMORY|" + listOf(
        id, content, type.name, source.name, confirmed, enabled,
        createdAt, verifiedAt, expiresAt, sourceRef,
    ).joinToString("|") { it?.toString().orEmpty() }

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
