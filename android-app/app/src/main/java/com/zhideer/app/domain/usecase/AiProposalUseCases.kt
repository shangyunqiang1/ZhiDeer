package com.zhideer.app.domain.usecase

import com.zhideer.app.data.ai.AiProposalStore
import com.zhideer.app.domain.model.AiChatSession
import com.zhideer.app.domain.model.AiProposal
import com.zhideer.app.domain.model.AiProposalChanges
import com.zhideer.app.domain.model.AiProposalDeleteAction
import com.zhideer.app.domain.model.AiProposalItem
import com.zhideer.app.domain.model.AiProposalKind
import com.zhideer.app.domain.model.AiProposalOp
import com.zhideer.app.domain.model.AiProposalScope
import com.zhideer.app.domain.model.AiProposalSendOutcome
import com.zhideer.app.domain.model.AiProposalSendReply
import com.zhideer.app.domain.model.AiProposalStatus
import com.zhideer.app.domain.model.AiProposalTargetRecord
import com.zhideer.app.domain.model.ClockCapability
import com.zhideer.app.domain.model.MemorySource
import com.zhideer.app.domain.model.Reminder
import com.zhideer.app.domain.model.TaskPriority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.ZoneId

/**
 * AI 操作提案用例（T19/#20，T20/#21 扩展日程与提醒，T21/#22 扩展修改与删除，
 * T22/#23 扩展重复规则、子任务与「仅本次/本次及以后」范围，T25/#26 扩展系统闹钟交接）：
 * 起草提案入库、确认执行、忽略与逐项移除、多候选目标点选。
 *
 * 状态机（GLOSSARY「操作提案」：尚未执行、供用户核对的方案；用户确认后才执行）：
 * - PENDING（待确认）：唯一可执行状态，产生提案绝不写业务仓库（验收断言）；
 * - PENDING --confirm--> CONFIRMED（已确认，执行中；DataStore 原子抢占防并发重复确认）
 *   --结果回写--> EXECUTED（成功/部分失败/全部失败均如实回写 resultMessage 并回执到对话流）；
 * - PENDING --dismiss/清空条目--> EXPIRED（不执行、不残留可执行状态）；
 * - 进程在 CONFIRMED 期间中断时结果未知：再次确认返回"待核实"提示，绝不虚报成功或失败，也不重复执行。
 *
 * 系统闹钟（T25/#26）：确认后经既有 [ClockHandoffUseCases] 发起交接，只记录「待核对」，
 * 不宣称系统闹钟已保存；指定日历日期或平台无处理器时**不发起**并给两条回退；
 * 该路径绝不创建知时鹿提醒（避免与系统闹钟双份响铃）。
 *
 * 确认执行（真实入口，不绕过）：
 * - 新增：待办经 [TaskUseCases.create]（重复规则再经 [RecurrenceUseCases.create]、子任务步骤逐条经
 *   [SubTaskUseCases.create]）、便签经 [NoteUseCases.create]；
 *   日程（T20/#21）先经 [CalendarUseCases.create] 创建，附属提醒一并经
 *   [CalendarReminderUseCases.setReminders] 设置（含去重；非法提醒在确认前已整批拒绝）；
 *   长期记忆（T28/#29）经 [MemoryUseCases.create] 保存，来源标记聊天确认（可追溯）、
 *   写入即已确认；确认前记忆表绝无写入（GLOSSARY：操作提案）；
 * - 修改（T21/#22）：待办经 [TaskUseCases.update]/[TaskUseCases.complete]、便签经 [NoteUseCases.update]、
 *   日程经 [CalendarUseCases.update]（时间变化自动联动相对提醒重算）；
 * - 重复规则与子任务（T22/#23）：修改待办的重复规则经 [RecurrenceUseCases.update]/[RecurrenceUseCases.remove]，
 *   追加步骤经 [SubTaskUseCases.create]；「仅本次」修改经 [RecurrenceUseCases.postponeOccurrence]
 *   单次延期（不挪动后续日期、不改写过去记录），取消「仅本次」经 [RecurrenceUseCases.skipOccurrence]
 *   跳过该次出现（例外日）；未说明范围时绝不静默改整组；
 * - 删除（T21/#22）：待办取消经 [TaskUseCases.cancel]（GLOSSARY：取消待办保留记录并停止提醒，不是删除）；
 *   待办/便签/日程删除分别经各自 trash 入口进入回收站（可恢复，不永久删除）。
 * 重复/子任务执行全部复用上述既有真实入口，不另建 AI 专用路径（验收第 5 条）。
 *
 * 目标定位（T21/#22）：修改/删除提案由模型给出定位线索（target.title，用户原话中的名称），
 * 落库前在设备端经 [AiProposalTargetMatcher] 从真实仓库只读匹配：
 * - 授权门：目标类别必须已在 [AiContextAuthorizer] 授权（#19 体系），未授权绝不读取任何仓库、
 *   不落提案，中文提示先授权；
 * - 唯一候选自动绑定（匹配空间里只有这一条，不是猜测）并保存目标快照（含 updatedAt 一致性指纹）；
 * - 多候选（≤上限）内嵌条目 [AiProposalItem.candidates] 供用户在卡片中点选
 *   [selectProposalTarget]（点选哪条就是哪条），确认前所有条目必须已绑定目标；
 * - 匹配不到或候选过多：不落提案，中文提示并追问，绝不猜测、绝不编造；
 * - 执行时重读当前记录，updatedAt 与快照不一致（确认前被手动修改）则拒绝覆盖该项并如实回执。
 *
 * 授权边界：新增提案路径（[sendAndIngest] 对 CREATE 条目，含 T28/#29 记忆提案）不读取任何本地业务数据
 * （只写不读），不要求上下文授权；修改/删除条目的目标匹配需要读取对应类别，授权门只对它们生效。
 *
 * 幂等：EXECUTED 后再次确认只返回既有结果；重复忽略幂等成功——重复确认或重试绝不重复执行。
 */
class AiProposalUseCases(
    private val chat: AiChatUseCases,
    private val store: AiProposalStore,
    private val taskUseCases: TaskUseCases,
    private val noteUseCases: NoteUseCases,
    // T20/#21 纯新增：日程与日程提醒真实入口（仅确认执行分支调用，不改其语义）
    private val calendarUseCases: CalendarUseCases,
    private val calendarReminderUseCases: CalendarReminderUseCases,
    // T21/#22 纯新增：修改/删除提案的目标定位（授权门在本用例，匹配器只读仓库）
    private val targetMatcher: AiProposalTargetMatcher,
    // T28/#29 纯新增：长期记忆真实入口（仅确认执行分支调用；可空默认值保持既有构造零回归，
    // 生产装配必须传入，缺省时记忆提案确认返回中文错误、绝不静默丢失）
    private val memoryUseCases: MemoryUseCases? = null,
    // T22/#23 纯新增：重复规则与子任务真实入口（仅确认执行分支调用；可空默认值保持既有构造
    // 零回归，生产装配必须传入；缺省时重复/子任务提案确认返回中文错误、绝不静默丢失）
    private val recurrenceUseCases: RecurrenceUseCases? = null,
    private val subTaskUseCases: SubTaskUseCases? = null,
    // T25/#26 纯新增：系统闹钟提案确认执行经既有 ClockHandoffUseCases 发起交接（可空默认值保持
    // 既有构造零回归；生产装配必须传入，缺省时闹钟提案确认返回中文错误、绝不静默丢失）
    private val clockHandoffUseCases: ClockHandoffUseCases? = null,
    private val idGen: () -> String,
    private val now: () -> Long,
    // 创建时区（提案截止/日程起止换算基准）；公开供提案卡片按同一时区渲染可读时间
    val zoneId: ZoneId,
) {

    /**
     * 提案模式下发送并入库：
     * - 走 [AiChatUseCases.sendProposalRequest]（带提案协议 system 提示）；
     * - 普通回复 → [AiProposalSendOutcome.PlainReply]；
     * - 合法提案 → 新增条目直接入库；修改/删除条目先经授权门与目标定位
     *   （[prepareTargets]，T21/#22：未授权/匹配失败不落提案并中文追问），
     *   以 PENDING 落提案库，返回 [AiProposalSendOutcome.ProposalCreated]；
     * - 解析失败（已自动重试一次）→ 中文可诊断提示，提案库与业务仓库均无写入。
     */
    suspend fun sendAndIngest(
        sessionId: String?,
        userText: String,
        onDelta: (String) -> Unit,
    ): Result<AiProposalSendOutcome> {
        val reply = chat.sendProposalRequest(sessionId, userText, onDelta)
            .getOrElse { return Result.failure(it) }
        return when (reply) {
            is AiProposalSendReply.PlainReply -> Result.success(AiProposalSendOutcome.PlainReply(reply.session))
            is AiProposalSendReply.Drafts -> {
                val prepared = prepareTargets(reply.session, reply.items).getOrElse { reason ->
                    return Result.failure(IllegalStateException(reason))
                }
                val proposal = AiProposal(
                    id = idGen(),
                    sessionId = reply.session.id,
                    createdAt = now(),
                    status = AiProposalStatus.PENDING,
                    items = prepared,
                )
                store.save(store.load() + proposal)
                Result.success(AiProposalSendOutcome.ProposalCreated(reply.session, proposal))
            }
        }
    }

    /**
     * 修改/删除条目的目标定位（T21/#22，落库前执行）：
     * 1. 授权门（复用 #19 体系）：目标类别未授权 → 绝不读取任何仓库，不落提案，
     *    中文提示先授权（会话留痕说明），不猜不编；
     * 2. 只读匹配：唯一候选自动绑定目标快照；多候选内嵌候选列表等用户点选；
     * 3. 匹配不到/候选过多 → 不落提案，中文提示并追问（会话留痕）。
     * 全部条目通过才返回成功；任一失败整批不落库（不产生半可执行提案）。
     */
    private suspend fun prepareTargets(
        session: AiChatSession,
        items: List<AiProposalItem>,
    ): Result<List<AiProposalItem>> {
        val targets = items.filter { it.op != AiProposalOp.CREATE }
        if (targets.isEmpty()) return Result.success(items)

        // 1. 授权门：未授权对应类别时绝不读取本地数据（#19 体系，会话级授权）
        val grant = chat.currentContextGrant()
        for (item in targets) {
            val category = targetCategory(item.kind)
            if (grant == null || !grant.contains(category)) {
                val verb = when {
                    item.op == AiProposalOp.MODIFY -> "修改"
                    item.kind == AiProposalKind.TASK && item.deleteAction == AiProposalDeleteAction.CANCEL -> "取消"
                    else -> "删除"
                }
                val reason = "要通过 AI $verb${kindCn(item.kind)}，需要先读取本地${kindCn(item.kind)}记录：" +
                    "请在「授权」面板开启「${category.label}」类别并确认后重试；本次未创建提案，未读取任何数据。"
                appendFollowUp(session.id, reason)
                return Result.failure(IllegalStateException(reason))
            }
        }

        // 2+3. 只读匹配与绑定/追问（按原条目顺序，保持提案内条目次序不变）
        val prepared = mutableListOf<AiProposalItem>()
        for (item in items) {
            if (item.op == AiProposalOp.CREATE) {
                prepared += item
                continue
            }
            val noun = kindCn(item.kind)
            when (val match = targetMatcher.match(item.kind, item.targetTitle)) {
                is AiProposalTargetMatch.Unique -> {
                    // T22/#23：唯一候选绑定后判定重复范围（未说明先澄清；仅本次校验出现日）
                    val scoped = prepareScopedTarget(session.id, item, match.record)
                    scoped.onFailure { error -> return Result.failure(error) }
                    prepared += scoped.getOrThrow()
                }
                is AiProposalTargetMatch.Multiple -> prepared += item.copy(candidates = match.candidates)
                is AiProposalTargetMatch.NotFound -> {
                    val reason = "没有找到名称包含「${item.targetTitle}」的$noun：" +
                        "可以到${noun}页面核对准确名称后再试，或告诉我更完整的名称；本次未创建提案，未改动任何记录。"
                    appendFollowUp(session.id, reason)
                    return Result.failure(IllegalStateException(reason))
                }
                is AiProposalTargetMatch.TooMany -> {
                    val reason = "找到 ${match.count} 条名称包含「${item.targetTitle}」的$noun，指代过于模糊：" +
                        "请告诉我更完整的名称，确认后我再起草提案；本次未创建提案，未改动任何记录。"
                    appendFollowUp(session.id, reason)
                    return Result.failure(IllegalStateException(reason))
                }
            }
        }
        return Result.success(prepared)
    }

    /**
     * 唯一候选绑定 + 重复范围校验（T22/#23）：
     * - 非重复待办/删除（回收站）：范围字段无意义，清空后不展示；
     * - 重复待办的修改或取消未说明范围：不落提案，中文追问「仅本次还是本次及以后」
     *   （验收第 2 条：未说明范围时先澄清，绝不静默改整组）；
     * - 「仅本次」必须指明是哪一次出现，且该日期须按当前规则确实是一次出现；
     * - 其余非法（如仅本次改标题）由 [validateProposalItem] 统一拦截。
     */
    private suspend fun prepareScopedTarget(
        sessionId: String,
        item: AiProposalItem,
        record: AiProposalTargetRecord,
    ): Result<AiProposalItem> {
        val bound = item.copy(
            candidates = listOf(record),
            targetRecordId = record.recordId,
            targetRecord = record,
        )
        val normalized = normalizeScopeForRecord(bound, record)
        validateProposalItem(normalized)?.let { problem ->
            val reason = "该提案还不能执行：$problem。请补充说明后再试；本次未创建提案，未改动任何记录。"
            appendFollowUp(sessionId, reason)
            return Result.failure(IllegalStateException(reason))
        }
        val row = record.recurrence
        val needsScope = normalized.op == AiProposalOp.MODIFY ||
            (normalized.op == AiProposalOp.DELETE && normalized.deleteAction == AiProposalDeleteAction.CANCEL)
        if (row != null && needsScope) {
            if (normalized.scope == null) {
                val reason = "「${record.title}」是重复待办：修改或取消前需要明确范围——只改这一次选「仅本次」，" +
                    "影响以后选「本次及以后」。请告诉我范围后我再起草提案；本次未创建提案，未改动任何记录。"
                appendFollowUp(sessionId, reason)
                return Result.failure(IllegalStateException(reason))
            }
            if (normalized.scope == AiProposalScope.THIS_OCCURRENCE) {
                val rule = row.toRecurrenceRule()
                val anchor = record.anchorEpochDay
                val occurrence = normalized.occurrenceEpochDay
                if (anchor == null || occurrence == null || !rule.occursByPatternOn(anchor, occurrence)) {
                    val dateText = occurrence?.let { LocalDate.ofEpochDay(it).toString() } ?: "（未给出日期）"
                    val reason = "按「${record.title}」当前的重复规则，$dateText 不是一次出现（或未说明是哪一次）：" +
                        "请核对要调整的那一次是哪天；本次未创建提案，未改动任何记录。"
                    appendFollowUp(sessionId, reason)
                    return Result.failure(IllegalStateException(reason))
                }
            }
        }
        return Result.success(normalized)
    }

    /** 范围只对「重复待办的修改/取消」有意义；其余条目清空范围字段（避免误导展示）。 */
    private fun normalizeScopeForRecord(item: AiProposalItem, record: AiProposalTargetRecord): AiProposalItem {
        val recurring = item.kind == AiProposalKind.TASK && record.recurrence != null
        val applicable = recurring && (
            item.op == AiProposalOp.MODIFY ||
                (item.op == AiProposalOp.DELETE && item.deleteAction == AiProposalDeleteAction.CANCEL)
            )
        return if (applicable) item else item.copy(scope = null, occurrenceEpochDay = null)
    }

    /** 目标类别对应的授权类别（#19 体系映射）。 */
    private fun targetCategory(kind: AiProposalKind) = when (kind) {
        AiProposalKind.TASK -> AiContextCategory.TASKS
        AiProposalKind.NOTE -> AiContextCategory.NOTES
        AiProposalKind.SCHEDULE -> AiContextCategory.SCHEDULE
        // T28/#29：记忆条目只支持新增（CREATE），修改/删除记忆不走目标定位；
        // 若未来开放修改/删除记忆，其目标读取应经此记忆授权门。
        AiProposalKind.MEMORY -> AiContextCategory.MEMORY
        // T25/#26：系统闹钟只支持新增，不读取本地记录、不参与目标定位（穷举防御分支，实际不可达）
        AiProposalKind.ALARM -> AiContextCategory.SCHEDULE
    }

    /** 匹配失败/未授权等中文追问写入会话（留痕），写入失败不影响发送结果。 */
    private suspend fun appendFollowUp(sessionId: String, message: String) {
        runCatching { chat.appendAssistantMessage(sessionId, message) }
    }

    /** 全部提案（存储顺序，追加在后）；UI 按会话过滤渲染卡片。 */
    fun observeProposals(): Flow<List<AiProposal>> = store.observe()

    // ---------- T25/#26 系统闹钟：能力探测与回退 ----------

    /** 当前系统时钟能力（null = 交接入口未装配）；卡片据此展示不可用与回退，绝不假装可用。 */
    fun clockCapability(): ClockCapability? = clockHandoffUseCases?.capability()

    /**
     * 回退选项②：打开系统时钟列表（或系统时钟应用）供用户手动核对/设置。
     * 不写交接记录、不创建任何闹钟或知时鹿提醒；失败一律中文提示。
     */
    suspend fun openSystemClock(): Result<Unit> =
        clockHandoffUseCases?.openAlarmList()
            ?: Result.failure(IllegalStateException("系统时钟入口未配置，请手动打开系统时钟。"))

    /** 回退说明（含双响警告），供系统闹钟提案卡片展示；与交接页同一口径。 */
    fun clockFallbackHint(): String = clockHandoffUseCases?.fallbackHint()
        ?: "回退选择：① 手动打开系统时钟自行设置；② 在知时鹿内为事项设置提醒。若两者同时存在，可能重复响铃。"

    /**
     * 确认提案（幂等）：
     * - PENDING：DataStore 原子抢占 CONFIRMED 后，先整批防御校验（任一条目非法整批不保存、
     *   状态回 PENDING），再逐条经 [TaskUseCases.create]/[NoteUseCases.create]/[CalendarUseCases.create]
     *   （日程附属提醒经 [CalendarReminderUseCases.setReminders]）真实入口创建；
     *   结果（成功、部分失败或全部失败）统一回写 EXECUTED 并把中文回执追加到对话流；
     * - EXECUTED：直接返回既有提案（不重建，重复确认只产生同一条记录）；
     * - CONFIRMED：执行结果未知（执行中或进程曾中断），返回"待核实"提示，不执行任何创建；
     * - EXPIRED：已被忽略或失效，拒绝确认。
     */
    suspend fun confirmProposal(proposalId: String): Result<AiProposal> {
        val current = store.load().find { it.id == proposalId }
            ?: return Result.failure(IllegalStateException("操作提案不存在或已清除"))
        return when (current.status) {
            AiProposalStatus.EXECUTED -> Result.success(current)
            AiProposalStatus.CONFIRMED -> Result.failure(
                IllegalStateException(
                    "该提案已确认过，执行结果未知：请到待办、便签页面核对真实记录，不要重复确认",
                ),
            )
            AiProposalStatus.EXPIRED -> Result.failure(
                IllegalStateException("该提案已被忽略或已失效，无法确认创建"),
            )
            AiProposalStatus.PENDING -> {
                val claimed = store.transition(proposalId, setOf(AiProposalStatus.PENDING)) {
                    it.copy(status = AiProposalStatus.CONFIRMED)
                } ?: return Result.failure(IllegalStateException("该提案已被确认或忽略，无法再次确认"))
                // T21/#22：修改/删除条目必须先在卡片中点选目标（多候选绝不静默猜测执行对象）
                val unselected = claimed.items.count { it.op != AiProposalOp.CREATE && it.targetRecordId == null }
                if (unselected > 0) {
                    // 回滚到 PENDING：目标选定后仍可确认
                    store.transition(proposalId, setOf(AiProposalStatus.CONFIRMED)) {
                        it.copy(status = AiProposalStatus.PENDING)
                    }
                    return Result.failure(
                        IllegalStateException(
                            "还有 $unselected 项修改或删除未选择目标记录：请在提案卡片中点选后再确认，绝不猜测执行对象",
                        ),
                    )
                }
                // T22/#23：重复待办的修改/取消必须先选择范围（仅本次或本次及以后），绝不静默猜整组
                val unscoped = claimed.items.count { needsScopeSelection(it) }
                if (unscoped > 0) {
                    store.transition(proposalId, setOf(AiProposalStatus.CONFIRMED)) {
                        it.copy(status = AiProposalStatus.PENDING)
                    }
                    return Result.failure(
                        IllegalStateException(
                            "还有 $unscoped 项重复待办未选择范围：请在提案卡片中选择「仅本次」或「本次及以后」后再确认",
                        ),
                    )
                }
                executeConfirmed(claimed)
            }
        }
    }

    /**
     * 忽略提案：仅 PENDING 可忽略（→ EXPIRED），忽略后不执行、不残留可执行状态；
     * 已忽略的提案重复忽略幂等成功；已确认/已执行的提案不可忽略。
     */
    suspend fun dismissProposal(proposalId: String): Result<Unit> {
        val current = store.load().find { it.id == proposalId }
            ?: return Result.failure(IllegalStateException("操作提案不存在或已清除"))
        return when (current.status) {
            AiProposalStatus.PENDING -> {
                store.transition(proposalId, setOf(AiProposalStatus.PENDING)) {
                    it.copy(status = AiProposalStatus.EXPIRED)
                } ?: return Result.failure(IllegalStateException("该提案已被确认或忽略"))
                Result.success(Unit)
            }
            AiProposalStatus.EXPIRED -> Result.success(Unit)
            AiProposalStatus.CONFIRMED -> Result.failure(
                IllegalStateException("该提案已确认，正在创建或结果待核实，无法忽略"),
            )
            AiProposalStatus.EXECUTED -> Result.failure(
                IllegalStateException("该提案已执行完成，无法忽略"),
            )
        }
    }

    /**
     * 逐项移除（issue #20：提案可逐项修改或移除）：仅 PENDING 可移除；
     * 移除最后一项时提案自动失效（EXPIRED，无可执行内容）。
     */
    suspend fun removeProposalItem(proposalId: String, itemId: String): Result<AiProposal> {
        val current = store.load().find { it.id == proposalId }
            ?: return Result.failure(IllegalStateException("操作提案不存在或已清除"))
        if (current.status != AiProposalStatus.PENDING) {
            return Result.failure(IllegalStateException("该提案已确认或已失效，不能再移除条目"))
        }
        if (current.items.none { it.id == itemId }) {
            return Result.failure(IllegalStateException("提案条目不存在或已移除"))
        }
        val remaining = current.items.filterNot { it.id == itemId }
        val updated = if (remaining.isEmpty()) {
            current.copy(items = remaining, status = AiProposalStatus.EXPIRED)
        } else {
            current.copy(items = remaining)
        }
        val saved = store.transition(proposalId, setOf(AiProposalStatus.PENDING)) { updated }
            ?: return Result.failure(IllegalStateException("该提案已被确认或忽略"))
        return Result.success(saved)
    }

    /**
     * 点选修改/删除条目的目标记录（T21/#22）：多候选时用户在卡片中点选，点选哪条就是哪条。
     * - 仅 PENDING 提案、仅修改/删除条目可点选；
     * - recordId 必须在该条目的候选列表内（绝不信任候选外的任意记录标识）；
     * - 点选时重读最新记录快照（含 updatedAt 一致性指纹），供卡片预览旧值与执行时一致性校验；
     * - 重复点选（换目标）允许，以最后一次点选为准。
     */
    suspend fun selectProposalTarget(proposalId: String, itemId: String, recordId: String): Result<AiProposal> {
        val current = store.load().find { it.id == proposalId }
            ?: return Result.failure(IllegalStateException("操作提案不存在或已清除"))
        if (current.status != AiProposalStatus.PENDING) {
            return Result.failure(IllegalStateException("该提案已确认或已失效，不能再选择目标"))
        }
        val item = current.items.find { it.id == itemId }
            ?: return Result.failure(IllegalStateException("提案条目不存在或已移除"))
        if (item.op == AiProposalOp.CREATE) {
            return Result.failure(IllegalStateException("新增条目无需选择目标"))
        }
        if (item.candidates.none { it.recordId == recordId }) {
            return Result.failure(IllegalStateException("所选记录不在候选列表中，请从候选中点选"))
        }
        val fresh = targetMatcher.loadRecord(item.kind, recordId)
            ?: return Result.failure(IllegalStateException("所选记录已不存在或已进回收站，请重新核对"))
        val bound = normalizeScopeForRecord(
            item.copy(targetRecordId = recordId, targetRecord = fresh),
            fresh,
        )
        val saved = store.transition(proposalId, setOf(AiProposalStatus.PENDING)) { proposal ->
            proposal.copy(
                items = proposal.items.map { candidate ->
                    if (candidate.id == itemId) bound else candidate
                },
            )
        } ?: return Result.failure(IllegalStateException("该提案已被确认或忽略"))
        return Result.success(saved)
    }

    /**
     * 选择重复待办的修改/取消范围（T22/#23，验收第 2 条）：
     * - 仅 PENDING 提案、仅已绑定重复待办目标的修改/取消可选；
     * - 「仅本次」必须已指明出现日期（解析层保证），且单次修改只支持延期到另一天——
     *   要改标题、规则或子任务须选「本次及以后」（不静默改成整组）；
     * - 重复选择以最后一次为准。选择本身不写任何业务仓库。
     */
    suspend fun selectProposalScope(
        proposalId: String,
        itemId: String,
        scope: AiProposalScope,
    ): Result<AiProposal> {
        val current = store.load().find { it.id == proposalId }
            ?: return Result.failure(IllegalStateException("操作提案不存在或已清除"))
        if (current.status != AiProposalStatus.PENDING) {
            return Result.failure(IllegalStateException("该提案已确认或已失效，不能再选择范围"))
        }
        val item = current.items.find { it.id == itemId }
            ?: return Result.failure(IllegalStateException("提案条目不存在或已移除"))
        if (item.op == AiProposalOp.CREATE) {
            return Result.failure(IllegalStateException("新增条目无需选择范围"))
        }
        val target = item.targetRecord
            ?: return Result.failure(IllegalStateException("请先点选目标记录，再选择修改范围"))
        if (item.kind != AiProposalKind.TASK || target.recurrence == null) {
            return Result.failure(IllegalStateException("目标不是重复待办，无需选择仅本次或本次及以后"))
        }
        if (item.op == AiProposalOp.DELETE && item.deleteAction != AiProposalDeleteAction.CANCEL) {
            return Result.failure(IllegalStateException("整条删除没有仅本次或本次及以后的范围"))
        }
        if (scope == AiProposalScope.THIS_OCCURRENCE) {
            if (item.occurrenceEpochDay == null) {
                return Result.failure(IllegalStateException("「仅本次」需要指明是哪一次出现（日期）"))
            }
            if (item.op == AiProposalOp.MODIFY && !(item.changes?.let { isSingleOccurrencePostpone(it) } == true)) {
                return Result.failure(
                    IllegalStateException("「仅本次」只支持把这一次延期到另一天；修改标题、规则或子任务请选择「本次及以后」"),
                )
            }
        }
        val saved = store.transition(proposalId, setOf(AiProposalStatus.PENDING)) { proposal ->
            proposal.copy(
                items = proposal.items.map { candidate ->
                    if (candidate.id == itemId) candidate.copy(scope = scope) else candidate
                },
            )
        } ?: return Result.failure(IllegalStateException("该提案已被确认或忽略"))
        return Result.success(saved)
    }

    /** 该条目是否仍需选择范围（重复待办的修改/取消且尚未选择）。 */
    private fun needsScopeSelection(item: AiProposalItem): Boolean =
        item.op != AiProposalOp.CREATE && item.kind == AiProposalKind.TASK &&
            item.targetRecord?.recurrence != null &&
            (item.op == AiProposalOp.MODIFY ||
                (item.op == AiProposalOp.DELETE && item.deleteAction == AiProposalDeleteAction.CANCEL)) &&
            item.scope == null

    // ---------- 确认执行 ----------

    private suspend fun executeConfirmed(claimed: AiProposal): Result<AiProposal> {
        // 整批校验：任一条目非法则整批不保存，提案回到 PENDING（可忽略或重新核对）
        claimed.items.forEachIndexed { index, item ->
            validateProposalItem(item)?.let { problem ->
                store.transition(claimed.id, setOf(AiProposalStatus.CONFIRMED)) {
                    it.copy(status = AiProposalStatus.PENDING)
                }
                return Result.failure(IllegalArgumentException("无法执行：第 ${index + 1} 项$problem，未保存任何内容"))
            }
        }
        val taskIds = mutableListOf<String>()
        val noteIds = mutableListOf<String>()
        val scheduleIds = mutableListOf<String>()
        val memoryIds = mutableListOf<String>()
        // T25/#26：系统闹钟交接记录 id（只表示已发起请求，记录本身保持待核对）
        val alarmHandoffIds = mutableListOf<String>()
        val okLines = mutableListOf<String>()
        val failLines = mutableListOf<String>()
        claimed.items.forEachIndexed { index, item ->
            val label = "第${index + 1}项"
            val targetName = item.targetRecord?.title ?: item.title
            val dependency = dependencyProblem(item)
            val outcome: Result<CreatedRecord> = try {
                if (dependency != null) Result.failure(IllegalStateException(dependency)) else when (item.op) {
                    AiProposalOp.CREATE -> when (item.kind) {
                        // T22/#23：重复规则与子任务经既有真实入口落地（Task/Recurrence/SubTask）
                        AiProposalKind.TASK -> createTaskWithRecurrenceAndSteps(item)
                        AiProposalKind.NOTE -> noteUseCases.create(item.toNoteInput()).map { CreatedRecord(it.id) }
                        AiProposalKind.SCHEDULE -> createScheduleWithReminders(item).map { (id, suffix) ->
                            CreatedRecord(id, suffix)
                        }
                        // T28/#29：记忆提案确认后经 MemoryUseCases 真实入口保存（来源 CHAT，写入即已确认）
                        AiProposalKind.MEMORY -> createChatMemory(item)
                        // T25/#26：系统闹钟经 ClockHandoffUseCases 发起交接；指定日期/无处理器不发起
                        AiProposalKind.ALARM -> executeAlarmHandoff(item)
                    }
                    // T21/#22：修改与删除经真实用例入口执行
                    AiProposalOp.MODIFY -> executeModify(item)
                    AiProposalOp.DELETE -> executeDelete(item)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
            outcome.fold(
                onSuccess = { created ->
                    when (item.op) {
                        AiProposalOp.CREATE -> when (item.kind) {
                            AiProposalKind.TASK -> {
                                taskIds += created.id
                                okLines += "$label 待办「${item.title}」已创建（记录 id=${created.id}）${created.detail.orEmpty()}"
                            }
                            AiProposalKind.NOTE -> {
                                noteIds += created.id
                                okLines += "$label 便签「${item.title}」已创建（记录 id=${created.id}）"
                            }
                            AiProposalKind.SCHEDULE -> {
                                scheduleIds += created.id
                                okLines += "$label 日程「${item.title}」已创建（记录 id=${created.id}）${created.detail.orEmpty()}"
                            }
                            AiProposalKind.MEMORY -> {
                                memoryIds += created.id
                                okLines += "$label 记忆「${item.content}」已创建（来源：聊天确认；记录 id=${created.id}）"
                            }
                            AiProposalKind.ALARM -> {
                                alarmHandoffIds += created.id
                                // 只宣称「已发起交接」，绝不宣称系统闹钟已保存（应用无法核实）
                                okLines += "$label 系统闹钟「${item.title}」已发起交接" +
                                    "（${proposalAlarmTimeText(item)}，${proposalAlarmRepeatText(item)}；" +
                                    "状态：待核对——知时鹿只发起创建请求，无法核实系统时钟是否保存，" +
                                    "请到系统时钟核对；未创建知时鹿提醒；记录 id=${created.id}）"
                            }
                        }
                        AiProposalOp.MODIFY -> {
                            if (item.scope == AiProposalScope.THIS_OCCURRENCE) {
                                // 单次延期回执：只说这一次，不虚称规则/模板被修改
                                okLines += "$label ${kindCn(item.kind)}「$targetName」已单次延期" +
                                    "（${created.detail.orEmpty()}；记录 id=${created.id}）"
                            } else {
                                // 回执与卡片预览同一口径（旧值 → 新值）；重复/子任务变更附加在后
                                val changeLines = proposalChangePreviewLines(item, zoneId)
                                val changeText = if (changeLines.isEmpty()) "无字段变化" else changeLines.joinToString("；")
                                val extra = created.detail?.let { "；$it" }.orEmpty()
                                okLines += "$label ${kindCn(item.kind)}「$targetName」已修改（$changeText$extra；记录 id=${created.id}）"
                            }
                        }
                        AiProposalOp.DELETE -> okLines += when {
                            item.kind == AiProposalKind.TASK &&
                                item.deleteAction == AiProposalDeleteAction.CANCEL ->
                                "$label 待办「$targetName」已取消（记录保留、其提醒已停止，不是删除" +
                                    "${created.detail?.let { "；$it" }.orEmpty()}；记录 id=${created.id}）"
                            else ->
                                "$label ${kindCn(item.kind)}「$targetName」已移入回收站（可恢复；记录 id=${created.id}）"
                        }
                    }
                },
                onFailure = { e ->
                    failLines += "$label「$targetName」执行失败：${e.message ?: "未知原因"}"
                },
            )
        }
        // T25/#26：含系统闹钟的提案不使用「已创建」措辞——只发起了交接请求，不是已保存的系统闹钟
        val allCreate = claimed.items.all { it.op == AiProposalOp.CREATE } &&
            claimed.items.none { it.kind == AiProposalKind.ALARM }
        val resultMessage = buildResultMessage(okLines, failLines, allCreate)
        val executed = store.transition(claimed.id, setOf(AiProposalStatus.CONFIRMED)) {
            it.copy(
                status = AiProposalStatus.EXECUTED,
                createdTaskIds = taskIds,
                createdNoteIds = noteIds,
                createdScheduleIds = scheduleIds,
                createdMemoryIds = memoryIds,
                createdAlarmHandoffIds = alarmHandoffIds,
                resultMessage = resultMessage,
                // T30/#31：真实执行是否全部成功（成功才可标记采纳；失败/部分失败不标已采纳）
                executionSucceeded = failLines.isEmpty(),
            )
        } ?: return Result.failure(IllegalStateException("操作提案状态已变化，请刷新后重试"))
        // 结果回写到对话流（成功消息含已执行记录标识）；回执写入失败不影响执行结果
        executed.sessionId?.let { chat.appendAssistantMessage(it, resultMessage) }
        return Result.success(executed)
    }

    /**
     * 系统闹钟交接（T25/#26）：确认后调用既有 [ClockHandoffUseCases] 真实入口。
     *
     * 平台事实（docs/validation/t02-oppo-device.md D09/D10）：
     * - SET_ALARM 只接受时/分与星期，无法传达日历日期：指定日期时**不发起**（不丢日期），
     *   如实失败并给出「知时鹿通知提醒 / 手动打开系统时钟」两条回退；
     * - 无 handler 时**禁用发起**且不写交接记录，中文说明回退，不崩溃、不冒充成功；
     * - 发起成功只记录「待核对」，绝不宣称系统闹钟已保存；
     * - 本路径不创建任何知时鹿提醒/待办/日程（回退也不自动创建，避免双份响铃）。
     */
    private suspend fun executeAlarmHandoff(item: AiProposalItem): Result<CreatedRecord> {
        val clock = clockHandoffUseCases
            ?: return Result.failure(IllegalStateException("系统时钟交接入口未配置，无法发起交接"))
        val hour = item.alarmHour
            ?: return Result.failure(IllegalStateException("系统闹钟缺少响铃时间（时）"))
        val minute = item.alarmMinute
            ?: return Result.failure(IllegalStateException("系统闹钟缺少响铃时间（分）"))
        val preview = clock.preview(
            hour = hour,
            minute = minute,
            label = item.title,
            targetEpochDay = item.alarmEpochDay,
            weekdays = item.alarmWeekdays.toSet(),
        ).getOrElse { return Result.failure(it) }
        if (preview.dateNotConveyable) {
            val date = item.alarmEpochDay?.let { LocalDate.ofEpochDay(it).toString() }.orEmpty()
            return Result.failure(
                IllegalStateException(
                    "系统时钟接口只接受小时与分钟，无法传达日期 $date：日期已保留，本次未发起跳转、未创建任何提醒。" +
                        "回退可选：① 改用知时鹿通知提醒（另行起草并确认）；② 打开系统时钟手动选择日期。",
                ),
            )
        }
        if (!preview.capability.canCreateAlarm) {
            return Result.failure(
                IllegalStateException(
                    "当前系统没有可用的时钟应用，已禁用发起：本次未跳转、未创建任何提醒。" +
                        "回退可选：① 手动打开系统时钟（如系统允许）；② 改用知时鹿提醒。",
                ),
            )
        }
        return clock.handoff(preview).fold(
            onSuccess = { handoff -> Result.success(CreatedRecord(handoff.id)) },
            onFailure = { e -> Result.failure(e) },
        )
    }

    /**
     * 执行修改条目（T21/#22）：先做一致性校验（确认前记录被手动修改则拒绝覆盖该项），
     * 再把变更集合并到当前记录、经真实 update 入口执行（未提及的字段保持原值，最小变更）。
     */
    private suspend fun executeModify(item: AiProposalItem): Result<CreatedRecord> {
        val targetId = item.targetRecordId
            ?: return Result.failure(IllegalStateException("目标记录未选择"))
        val changes = item.changes
            ?: return Result.failure(IllegalStateException("缺少变更内容"))
        // T22/#23 仅本次：单次延期（不改规则几何、不挪动后续日期、不改写过去记录）
        if (item.scope == AiProposalScope.THIS_OCCURRENCE) {
            return postponeSingleOccurrence(item, targetId)
        }
        item.targetRecord?.let { snap ->
            val fresh = targetMatcher.loadRecord(item.kind, targetId)
            when {
                fresh == null ->
                    return Result.failure(IllegalStateException("目标记录不存在或已进回收站，未执行修改"))
                fresh.updatedAt != snap.updatedAt ->
                    return Result.failure(
                        IllegalStateException("记录在确认前已被修改，为避免覆盖你的改动请在应用中核对后重试"),
                    )
            }
        }
        return when (item.kind) {
            AiProposalKind.TASK -> modifyTask(targetId, changes)
            AiProposalKind.NOTE -> modifyNote(targetId, changes)
            AiProposalKind.SCHEDULE -> modifySchedule(targetId, changes)
            // T28/#29：记忆条目只支持新增提案，不参与修改执行（解析层已拒绝，穷举防御分支）
            AiProposalKind.MEMORY -> Result.failure(IllegalStateException("记忆不支持修改提案，可在记忆页面编辑"))
            // T25/#26：系统闹钟只支持新增提案（解析层已拒绝，穷举防御分支）
            AiProposalKind.ALARM ->
                Result.failure(IllegalStateException("系统闹钟不支持修改提案，请在系统时钟中调整"))
        }
    }

    /**
     * 待办修改（T22/#23 扩展「本次及以后」）：字段经 [TaskUseCases.update]（未提及字段保持原值），
     * 重复规则经 [RecurrenceUseCases.update]/[RecurrenceUseCases.remove]，
     * 子任务步骤经 [SubTaskUseCases.create] 追加，完成状态经 [TaskUseCases.complete]。
     * 全部走既有真实入口；过去各次出现记录不被删除或改写（验收第 3 条）。
     */
    private suspend fun modifyTask(targetId: String, changes: AiProposalChanges): Result<CreatedRecord> {
        val current = taskUseCases.get(targetId)
            ?: return Result.failure(IllegalStateException("目标记录不存在或已删除"))
        if (current.trashedAt != null) {
            return Result.failure(IllegalStateException("目标记录已进回收站，请先恢复再操作"))
        }
        // 先确认所需的真实入口已装配，避免字段已改而规则/子任务静默丢失
        if ((changes.recurrence != null || changes.clearRecurrence) && recurrenceUseCases == null) {
            return Result.failure(IllegalStateException("重复规则入口未配置，无法修改重复规则"))
        }
        if (changes.addSubTasks.isNotEmpty() && subTaskUseCases == null) {
            return Result.failure(IllegalStateException("子任务入口未配置，无法新增子任务"))
        }
        taskUseCases.update(
            targetId,
            TaskInput(
                title = changes.title?.takeIf { it.isNotBlank() } ?: current.title,
                note = changes.note ?: current.note,
                priority = changes.priority?.let { runCatching { TaskPriority.valueOf(it) }.getOrNull() }
                    ?: current.priority,
                category = changes.category ?: current.category,
                dueEpochDay = if (changes.clearDue) null else changes.dueEpochDay ?: current.dueEpochDay,
                dueAtMillis = if (changes.clearDue) null else changes.dueAtMillis ?: current.dueAtMillis,
                dueZoneId = current.dueZoneId,
                recurrenceRule = current.recurrenceRule,
            ),
        ).getOrElse { return Result.failure(it) }
        val notes = mutableListOf<String>()
        if (changes.clearRecurrence) {
            val recurrence = recurrenceUseCases!!
            recurrence.remove(targetId).getOrElse { return Result.failure(it) }
            notes += "已移除重复规则"
        }
        changes.recurrence?.let { spec ->
            val recurrence = recurrenceUseCases!!
            recurrence.update(targetId, spec.toRecurrenceRule()).getOrElse { return Result.failure(it) }
            notes += "重复规则已更新为：${recurrenceSummaryText(spec)}"
        }
        if (changes.addSubTasks.isNotEmpty()) {
            val subTasks = subTaskUseCases!!
            var created = 0
            changes.addSubTasks.forEach { title ->
                subTasks.create(targetId, title).getOrElse { error ->
                    return Result.failure(
                        IllegalStateException("子任务「$title」创建失败：${error.message ?: "未知原因"}"),
                    )
                }
                created++
            }
            notes += "新增子任务 $created 项（本次及以后按新步骤清单执行）"
        }
        if (changes.completed != null) {
            taskUseCases.complete(targetId, changes.completed).getOrElse { error ->
                return Result.failure(
                    IllegalStateException("字段已更新但完成状态设置失败：${error.message ?: "未知原因"}"),
                )
            }
        }
        return Result.success(CreatedRecord(targetId, notes.joinToString("；").ifEmpty { null }))
    }

    /**
     * 「仅本次」修改 = 单次延期（T22/#23，验收第 3 条）：把 [AiProposalItem.occurrenceEpochDay]
     * 这一次的生效日移到 changes.dueEpochDay，经 [RecurrenceUseCases.postponeOccurrence] 执行——
     * 不改变规则几何、不挪动后续日期；目标日已有出现时同日两次（由既有出现行语义表达）；
     * 已完成的出现与过去记录不被改写（真实入口拒绝并中文说明）。
     */
    private suspend fun postponeSingleOccurrence(item: AiProposalItem, targetId: String): Result<CreatedRecord> {
        val recurrence = recurrenceUseCases
            ?: return Result.failure(IllegalStateException("重复规则入口未配置，无法执行单次延期"))
        val occurrence = item.occurrenceEpochDay
            ?: return Result.failure(IllegalStateException("「仅本次」缺少出现日期"))
        val to = item.changes?.dueEpochDay
            ?: return Result.failure(IllegalStateException("「仅本次」只支持把这一次延期到另一天"))
        recurrence.postponeOccurrence(targetId, occurrence, to).getOrElse { return Result.failure(it) }
        return Result.success(
            CreatedRecord(
                targetId,
                "原日期 ${LocalDate.ofEpochDay(occurrence)} 这一次移到 ${LocalDate.ofEpochDay(to)}，后续日期不变",
            ),
        )
    }

    /** 便签修改：清单未变更时保留原清单项（含 id 与勾选状态）；变更时整体替换（预览已明确标注）。 */
    private suspend fun modifyNote(targetId: String, changes: AiProposalChanges): Result<CreatedRecord> {
        val current = noteUseCases.get(targetId)
            ?: return Result.failure(IllegalStateException("目标记录不存在或已删除"))
        if (current.trashedAt != null) {
            return Result.failure(IllegalStateException("目标记录已进回收站，请先恢复再操作"))
        }
        noteUseCases.update(
            targetId,
            NoteInput(
                title = changes.title?.takeIf { it.isNotBlank() } ?: current.title,
                content = changes.content ?: current.content,
                tags = changes.tags ?: current.tags,
                items = changes.items?.map { NoteListItemInput(text = it) }
                    ?: current.items.map { NoteListItemInput(id = it.id, text = it.text, checked = it.checked) },
            ),
        ).getOrElse { return Result.failure(it) }
        return Result.success(CreatedRecord(targetId))
    }

    /** 日程修改：时间经 [CalendarUseCases.update]（时间变化自动联动相对提醒重算）；时区锚定保持不变。 */
    private suspend fun modifySchedule(targetId: String, changes: AiProposalChanges): Result<CreatedRecord> {
        val current = calendarUseCases.get(targetId)
            ?: return Result.failure(IllegalStateException("目标记录不存在或已删除"))
        if (current.trashedAt != null) {
            return Result.failure(IllegalStateException("目标记录已进回收站，请先恢复再操作"))
        }
        calendarUseCases.update(
            targetId,
            CalendarInput(
                title = changes.title?.takeIf { it.isNotBlank() } ?: current.title,
                startMillis = changes.startMillis ?: current.startMillis,
                endMillis = changes.endMillis ?: current.endMillis,
                timezoneId = current.timezoneId,
                location = changes.location ?: current.location,
                note = changes.note ?: current.note,
                category = changes.category ?: current.category,
            ),
        ).getOrElse { return Result.failure(it) }
        return Result.success(CreatedRecord(targetId))
    }

    /**
     * 执行删除条目（T21/#22）：待办取消经 [TaskUseCases.cancel]（GLOSSARY：取消待办≠删除，
     * 保留记录并停止提醒）；删除分别经待办/便签/日程的 trash 入口进入回收站（可恢复，不永久删除）。
     */
    private suspend fun executeDelete(item: AiProposalItem): Result<CreatedRecord> {
        val targetId = item.targetRecordId
            ?: return Result.failure(IllegalStateException("目标记录未选择"))
        // T22/#23 取消「仅本次」：跳过该次出现（例外日），不改变其余日期与既有记录
        if (item.kind == AiProposalKind.TASK && item.deleteAction == AiProposalDeleteAction.CANCEL &&
            item.scope == AiProposalScope.THIS_OCCURRENCE
        ) {
            val recurrence = recurrenceUseCases
                ?: return Result.failure(IllegalStateException("重复规则入口未配置，无法跳过本次出现"))
            val occurrence = item.occurrenceEpochDay
                ?: return Result.failure(IllegalStateException("「仅本次」缺少出现日期"))
            recurrence.skipOccurrence(targetId, occurrence).getOrElse { return Result.failure(it) }
            return Result.success(
                CreatedRecord(
                    targetId,
                    "本次（${LocalDate.ofEpochDay(occurrence)}）已跳过：该日不再出现、不再要求完成，其余日期与既有记录不变",
                ),
            )
        }
        return when (item.kind) {
            AiProposalKind.TASK -> when (item.deleteAction) {
                AiProposalDeleteAction.CANCEL -> taskUseCases.cancel(targetId).map {
                    CreatedRecord(
                        targetId,
                        if (item.scope == AiProposalScope.THIS_AND_FUTURE) {
                            "本次及以后：整组已取消，过去各次记录保留"
                        } else {
                            null
                        },
                    )
                }
                AiProposalDeleteAction.TRASH -> taskUseCases.trash(targetId).map { CreatedRecord(targetId) }
                null -> Result.failure(IllegalStateException("待办删除缺少动作"))
            }
            AiProposalKind.NOTE -> noteUseCases.trash(targetId).map { CreatedRecord(targetId) }
            AiProposalKind.SCHEDULE -> calendarUseCases.trash(targetId).map { CreatedRecord(targetId) }
            // T28/#29：记忆条目只支持新增提案，不参与删除执行（解析层已拒绝，穷举防御分支）
            AiProposalKind.MEMORY -> Result.failure(IllegalStateException("记忆不支持删除提案，可在记忆页面删除"))
            // T25/#26：系统闹钟只支持新增提案（解析层已拒绝，穷举防御分支）
            AiProposalKind.ALARM ->
                Result.failure(IllegalStateException("系统闹钟不支持删除提案，请在系统时钟中删除"))
        }
    }

    /**
     * 保存记忆条目（T28/#29）：确认执行分支经 [MemoryUseCases.create] 真实入口写入，
     * 来源标记为聊天确认（MemorySource.CHAT，可追溯）、写入即已确认；
     * 记忆写入提案不需要读取授权（只写不读）。入口未配置（装配缺省）时中文报错，绝不静默丢失。
     */
    private suspend fun createChatMemory(item: AiProposalItem): Result<CreatedRecord> {
        val memories = memoryUseCases
            ?: return Result.failure(IllegalStateException("记忆保存入口未配置，无法保存该条记忆"))
        return memories.create(item.toMemoryInput(), MemorySource.CHAT).map { CreatedRecord(it.id) }
    }

    /**
     * 创建待办条目（T22/#23）：先经 [TaskUseCases.create] 创建真实待办，有重复规则时经
     * [RecurrenceUseCases.create]，子任务步骤逐条经 [SubTaskUseCases.create]——全部走既有真实入口，
     * 不另建 AI 重复/父子路径。重复规则或子任务设置失败时如实写进回执后缀（待办本身已创建，
     * 与日程提醒的部分成功语义一致），绝不虚报。
     */
    private suspend fun createTaskWithRecurrenceAndSteps(item: AiProposalItem): Result<CreatedRecord> {
        val task = try {
            taskUseCases.create(item.toTaskInput(zoneId)).getOrElse { return Result.failure(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return Result.failure(e)
        }
        val notes = mutableListOf<String>()
        item.recurrence?.let { spec ->
            val recurrence = recurrenceUseCases
            if (recurrence == null) {
                notes += "重复规则设置失败：入口未配置"
            } else {
                try {
                    recurrence.create(task.id, spec.toRecurrenceRule())
                        .onSuccess { notes += "重复规则：${recurrenceSummaryText(spec)}" }
                        .onFailure { e -> notes += "重复规则设置失败：${e.message ?: "未知原因"}" }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    notes += "重复规则设置失败：${e.message ?: "未知原因"}"
                }
            }
        }
        if (item.subTasks.isNotEmpty()) {
            val subTasks = subTaskUseCases
            if (subTasks == null) {
                notes += "子任务创建失败：入口未配置"
            } else {
                var created = 0
                item.subTasks.forEach { title ->
                    try {
                        subTasks.create(task.id, title)
                            .onSuccess { created++ }
                            .onFailure { e -> notes += "子任务「$title」创建失败：${e.message ?: "未知原因"}" }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        notes += "子任务「$title」创建失败：${e.message ?: "未知原因"}"
                    }
                }
                if (created > 0) notes += "子任务 $created 项"
            }
        }
        val detail = notes.joinToString("；").let { if (it.isEmpty()) "" else "；$it" }
        return Result.success(CreatedRecord(task.id, detail))
    }

    /**
     * 创建日程条目（T20/#21）：先经 [CalendarUseCases.create] 创建真实日程（时区锚定为创建时区），
     * 有附属提醒时一并经 [CalendarReminderUseCases.setReminders] 设置（真实入口，含去重；
     * 精确闹钟能力不足时提醒记录保留并标记未就绪，绝不悄悄降级）。
     * 日程已创建而提醒设置失败时不回滚日程（部分成功语义与 T19 一致）：
     * 返回成功并携带如实说明的提醒后缀；日程本身创建失败才返回失败。
     */
    private suspend fun createScheduleWithReminders(item: AiProposalItem): Result<Pair<String, String>> {
        val entry = try {
            calendarUseCases.create(item.toCalendarInput(zoneId)).getOrElse { error -> return Result.failure(error) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return Result.failure(e)
        }
        if (item.reminders.isEmpty()) return Result.success(entry.id to "；未设置提醒")
        val reminderOutcome: Result<List<Reminder>> = try {
            calendarReminderUseCases.setReminders(entry.id, item.toReminderInputs())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
        return reminderOutcome.fold(
            onSuccess = { reminders ->
                val notReady = reminders.count { it.notReadyReason != null }
                val suffix = if (notReady > 0) {
                    "；提醒已设置（${reminders.size} 条），其中 $notReady 条暂未就绪，可到日程提醒面板修复"
                } else {
                    "；提醒已设置（${reminders.size} 条）"
                }
                Result.success(entry.id to suffix)
            },
            onFailure = { e ->
                Result.success(entry.id to "；提醒设置失败：${e.message ?: "未知原因"}")
            },
        )
    }

    /** 确认执行的单条创建结果：记录 id + 可选回执后缀（日程提醒/重复规则/子任务等如实说明）。 */
    private data class CreatedRecord(val id: String, val detail: String? = null)

    /**
     * 确认执行所需的真实入口是否已装配（T22/#23）：缺省时该条中文失败，
     * 绝不静默丢弃重复规则或子任务信息（生产装配 AppContainer 必传）。
     */
    private fun dependencyProblem(item: AiProposalItem): String? = when {
        item.op == AiProposalOp.CREATE && item.kind == AiProposalKind.TASK &&
            item.recurrence != null && recurrenceUseCases == null ->
            "重复规则入口未配置，无法保存重复规则"
        item.op == AiProposalOp.CREATE && item.kind == AiProposalKind.TASK &&
            item.subTasks.isNotEmpty() && subTaskUseCases == null ->
            "子任务入口未配置，无法保存子任务"
        item.op == AiProposalOp.CREATE && item.kind == AiProposalKind.ALARM &&
            clockHandoffUseCases == null ->
            "系统时钟交接入口未配置，无法发起交接"
        item.op == AiProposalOp.MODIFY &&
            (item.changes?.recurrence != null || item.changes?.clearRecurrence == true) &&
            recurrenceUseCases == null ->
            "重复规则入口未配置，无法修改重复规则"
        item.op == AiProposalOp.MODIFY && !item.changes?.addSubTasks.isNullOrEmpty() &&
            subTaskUseCases == null ->
            "子任务入口未配置，无法新增子任务"
        item.op == AiProposalOp.MODIFY && item.scope == AiProposalScope.THIS_OCCURRENCE &&
            recurrenceUseCases == null ->
            "重复规则入口未配置，无法执行单次延期"
        item.op == AiProposalOp.DELETE && item.scope == AiProposalScope.THIS_OCCURRENCE &&
            recurrenceUseCases == null ->
            "重复规则入口未配置，无法跳过本次出现"
        else -> null
    }

    /**
     * 回执文案：成功、部分失败、全部失败都如实说明，绝不虚报（issue #20/#22 验收）。
     * 纯新增提案用「已创建」；含修改/删除的提案用「已执行」（不把修改删除虚称为创建）。
     */
    private fun buildResultMessage(okLines: List<String>, failLines: List<String>, allCreate: Boolean): String {
        val verb = if (allCreate) "已创建" else "已执行"
        return when {
            failLines.isEmpty() -> "$verb ${okLines.size} 项：\n" + okLines.joinToString("\n")
            okLines.isEmpty() -> "全部执行失败，未产生任何改动：\n" + failLines.joinToString("\n")
            else -> "$verb ${okLines.size} 项、${failLines.size} 项执行失败" +
                "（可到待办、便签、日程页面核对真实记录）：\n" + (okLines + failLines).joinToString("\n")
        }
    }
}
