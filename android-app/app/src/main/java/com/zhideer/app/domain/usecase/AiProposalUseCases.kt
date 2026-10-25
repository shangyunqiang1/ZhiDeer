package com.zhideer.app.domain.usecase

import com.zhideer.app.data.ai.AiProposalStore
import com.zhideer.app.domain.model.AiProposal
import com.zhideer.app.domain.model.AiProposalItem
import com.zhideer.app.domain.model.AiProposalKind
import com.zhideer.app.domain.model.AiProposalSendOutcome
import com.zhideer.app.domain.model.AiProposalSendReply
import com.zhideer.app.domain.model.AiProposalStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import java.time.ZoneId

/**
 * AI 操作提案用例（T19/#20）：起草提案入库、确认创建、忽略与逐项移除。
 *
 * 状态机（GLOSSARY「操作提案」：尚未执行、供用户核对的方案；用户确认后才执行）：
 * - PENDING（待确认）：唯一可执行状态，产生提案绝不写业务仓库（验收断言）；
 * - PENDING --confirm--> CONFIRMED（已确认，执行中；DataStore 原子抢占防并发重复确认）
 *   --结果回写--> EXECUTED（成功/部分失败/全部失败均如实回写 resultMessage 并回执到对话流）；
 * - PENDING --dismiss/清空条目--> EXPIRED（不执行、不残留可执行状态）；
 * - 进程在 CONFIRMED 期间中断时结果未知：再次确认返回"待核实"提示，绝不虚报成功或失败，也不重复创建。
 *
 * 授权边界：提案路径（[sendAndIngest]）不读取任何本地业务数据（只写不读），
 * 不要求上下文授权；[AiContextAuthorizer] 的授权门只作用于读取类请求，行为不受本任务影响。
 *
 * 幂等：EXECUTED 后再次确认只返回既有结果；重复忽略幂等成功——重复确认或重试绝不产生重复记录。
 */
class AiProposalUseCases(
    private val chat: AiChatUseCases,
    private val store: AiProposalStore,
    private val taskUseCases: TaskUseCases,
    private val noteUseCases: NoteUseCases,
    private val idGen: () -> String,
    private val now: () -> Long,
    // 创建时区（提案截止换算基准）；公开供提案卡片按同一时区渲染可读时间
    val zoneId: ZoneId,
) {

    /**
     * 提案模式下发送并入库：
     * - 走 [AiChatUseCases.sendProposalRequest]（带提案协议 system 提示，不携带本地数据快照）；
     * - 普通回复 → [AiProposalSendOutcome.PlainReply]；
     * - 合法提案 → 以 PENDING 落提案库（唯一 id + 状态机），返回 [AiProposalSendOutcome.ProposalCreated]；
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
                val proposal = AiProposal(
                    id = idGen(),
                    sessionId = reply.session.id,
                    createdAt = now(),
                    status = AiProposalStatus.PENDING,
                    items = reply.items,
                )
                store.save(store.load() + proposal)
                Result.success(AiProposalSendOutcome.ProposalCreated(reply.session, proposal))
            }
        }
    }

    /** 全部提案（存储顺序，追加在后）；UI 按会话过滤渲染卡片。 */
    fun observeProposals(): Flow<List<AiProposal>> = store.observe()

    /**
     * 确认提案（幂等）：
     * - PENDING：DataStore 原子抢占 CONFIRMED 后，先整批防御校验（任一条目非法整批不保存、
     *   状态回 PENDING），再逐条经 [TaskUseCases.create]/[NoteUseCases.create] 真实入口创建；
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

    // ---------- 确认执行 ----------

    private suspend fun executeConfirmed(claimed: AiProposal): Result<AiProposal> {
        // 整批校验：任一条目非法则整批不保存，提案回到 PENDING（可忽略或重新核对）
        claimed.items.forEachIndexed { index, item ->
            validateProposalItem(item)?.let { problem ->
                store.transition(claimed.id, setOf(AiProposalStatus.CONFIRMED)) {
                    it.copy(status = AiProposalStatus.PENDING)
                }
                return Result.failure(IllegalArgumentException("无法创建：第 ${index + 1} 项$problem，未保存任何内容"))
            }
        }
        val taskIds = mutableListOf<String>()
        val noteIds = mutableListOf<String>()
        val okLines = mutableListOf<String>()
        val failLines = mutableListOf<String>()
        claimed.items.forEachIndexed { index, item ->
            val label = "第${index + 1}项"
            val outcome = try {
                when (item.kind) {
                    AiProposalKind.TASK -> taskUseCases.create(item.toTaskInput(zoneId)).map { it.id }
                    AiProposalKind.NOTE -> noteUseCases.create(item.toNoteInput()).map { it.id }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
            outcome.fold(
                onSuccess = { createdId ->
                    when (item.kind) {
                        AiProposalKind.TASK -> {
                            taskIds += createdId
                            okLines += "$label 待办「${item.title}」已创建（记录 id=$createdId）"
                        }
                        AiProposalKind.NOTE -> {
                            noteIds += createdId
                            okLines += "$label 便签「${item.title}」已创建（记录 id=$createdId）"
                        }
                    }
                },
                onFailure = { e ->
                    failLines += "$label「${item.title}」保存失败：${e.message ?: "未知原因"}"
                },
            )
        }
        val resultMessage = buildResultMessage(okLines, failLines)
        val executed = store.transition(claimed.id, setOf(AiProposalStatus.CONFIRMED)) {
            it.copy(
                status = AiProposalStatus.EXECUTED,
                createdTaskIds = taskIds,
                createdNoteIds = noteIds,
                resultMessage = resultMessage,
            )
        } ?: return Result.failure(IllegalStateException("操作提案状态已变化，请刷新后重试"))
        // 结果回写到对话流（成功消息含已创建记录标识）；回执写入失败不影响创建结果
        executed.sessionId?.let { chat.appendAssistantMessage(it, resultMessage) }
        return Result.success(executed)
    }

    /** 回执文案：成功、部分失败、全部失败都如实说明，绝不虚报（issue #20 验收）。 */
    private fun buildResultMessage(okLines: List<String>, failLines: List<String>): String = when {
        failLines.isEmpty() -> "已创建 ${okLines.size} 项：\n" + okLines.joinToString("\n")
        okLines.isEmpty() -> "全部保存失败，未创建任何记录：\n" + failLines.joinToString("\n")
        else -> "已创建 ${okLines.size} 项、${failLines.size} 项保存失败" +
            "（可到待办、便签页面核对后手动补建）：\n" + (okLines + failLines).joinToString("\n")
    }
}
