package com.zhideer.app.domain.model

import kotlinx.serialization.Serializable

/**
 * AI 操作提案领域模型（T19/#20，T20/#21 扩展日程与提醒）。
 * GLOSSARY「操作提案」：尚未执行、供用户核对的新增方案；用户确认后才执行，
 * 提案本身不表示事项已保存。提案有意不进 Room（数据库零改动），存 DataStore JSON。
 */

/** 提案条目类型：待办、便签或日程（T20/#21 扩展日程）。 */
@Serializable
enum class AiProposalKind { TASK, NOTE, SCHEDULE }

/**
 * 提案状态机：
 * - [PENDING] 待确认：唯一可执行状态，此时业务仓库绝无写入；
 * - [CONFIRMED] 已确认：执行中。进程在此态中断（如执行期间退出）时结果未知，
 *   界面须显示"待核实"，不得虚报成功或失败；
 * - [EXECUTED] 已执行：结果（成功、部分失败或全部失败）已如实回写 [AiProposal.resultMessage]，
 *   再次确认幂等返回既有结果，绝不重复创建；
 * - [EXPIRED] 已失效：被用户忽略或条目全部移除，不可执行、不残留可执行状态。
 */
@Serializable
enum class AiProposalStatus { PENDING, CONFIRMED, EXECUTED, EXPIRED }

/**
 * 提案中的提醒规格（T20/#21 纯新增）：作为日程提案的附属字段，GLOSSARY：
 * 相对提醒（相对开始提前量，随日程开始移动）或独立时刻提醒，两种形态互斥。
 * 解析层保证互斥与合法（提前量为正、独立时刻不早于日程开始，时间已按创建时区换算为 UTC 毫秒）；
 * 确认创建时经 CalendarReminderUseCases 统一设置（含去重），提案本身不直接写提醒。
 */
@Serializable
enum class AiProposalReminderType { RELATIVE, ABSOLUTE }

@Serializable
data class AiProposalReminderSpec(
    val type: AiProposalReminderType,
    val offsetMillis: Long? = null,
    val remindAtMillis: Long? = null,
)

/**
 * 提案中的一个条目（GLOSSARY：操作提案的组成部分）。
 * 待办字段（[note]/[priority]/[category]/[dueEpochDay]/[dueAtMillis]）、便签字段
 * （[content]/[tags]/[items]）与日程字段（[startMillis]/[endMillis]/[location]/[reminders]，T20/#21）
 * 按 [kind] 各取所需；解析层保证互斥与合法性：
 * - [dueEpochDay] 日期截止（不含时刻）；[dueAtMillis] 时刻截止（按创建时区换算的 UTC 毫秒）；两者绝不同时非空；
 * - [startMillis]/[endMillis] 日程起止（按创建时区换算的 UTC 毫秒，结束不早于开始）；
 * - [priority] 为归一后的 LOW/NORMAL/HIGH；
 * - 便签 [content] 保留用户原话，解析层不改写。
 * 新增字段均有默认值：既有持久化提案 JSON 与 task/note 协议零回归。
 */
@Serializable
data class AiProposalItem(
    val id: String,
    val kind: AiProposalKind,
    val title: String,
    val note: String = "",
    val priority: String = "NORMAL",
    val category: String = "",
    val dueEpochDay: Long? = null,
    val dueAtMillis: Long? = null,
    val content: String = "",
    val tags: List<String> = emptyList(),
    val items: List<String> = emptyList(),
    // T20/#21 日程字段（仅 SCHEDULE 条目使用，解析层保证互斥）
    val startMillis: Long? = null,
    val endMillis: Long? = null,
    val location: String = "",
    val reminders: List<AiProposalReminderSpec> = emptyList(),
)

/**
 * 一次对话产生的操作提案：唯一 [id] + 状态机 + 全部条目。
 * [sessionId] 关联产生它的聊天会话（新会话时由发送路径生成后回填）；
 * [createdTaskIds]/[createdNoteIds]/[createdScheduleIds]（T20/#21）为确认后经真实用例入口创建的记录标识；
 * [resultMessage] 为执行结果的中文回执（含已创建记录标识；部分失败时如实说明）。
 */
@Serializable
data class AiProposal(
    val id: String,
    val sessionId: String? = null,
    val createdAt: Long,
    val status: AiProposalStatus = AiProposalStatus.PENDING,
    val items: List<AiProposalItem> = emptyList(),
    val createdTaskIds: List<String> = emptyList(),
    val createdNoteIds: List<String> = emptyList(),
    val createdScheduleIds: List<String> = emptyList(),
    val resultMessage: String? = null,
)

/** [AiChatUseCases.sendProposalRequest] 的解析结果（发送层只解析，不落提案库）。 */
sealed interface AiProposalSendReply {
    /** 普通对话回复：无提案 JSON 块，原文保存进会话。 */
    data class PlainReply(val session: AiChatSession) : AiProposalSendReply

    /** 模型给出了合法提案：会话中保存的是人类可读摘要，条目待用例层落提案库。 */
    data class Drafts(val session: AiChatSession, val items: List<AiProposalItem>) : AiProposalSendReply
}

/** [AiProposalUseCases.sendAndIngest] 的结果（提案已落库）。 */
sealed interface AiProposalSendOutcome {
    data class PlainReply(val session: AiChatSession) : AiProposalSendOutcome

    data class ProposalCreated(val session: AiChatSession, val proposal: AiProposal) : AiProposalSendOutcome
}
