package com.zhideer.app.domain.model

import kotlinx.serialization.Serializable

/**
 * AI 操作提案领域模型（T19/#20，T20/#21 扩展日程与提醒，T21/#22 扩展修改与删除，
 * T22/#23 扩展重复规则、子任务与「仅本次/本次及以后」范围）。
 * GLOSSARY「操作提案」：尚未执行、供用户核对的新增/修改/删除方案；用户确认后才执行，
 * 提案本身不表示事项已保存或修改成功。提案有意不进 Room（数据库零改动），存 DataStore JSON。
 */

/**
 * 提案条目类型：待办、便签、日程或长期记忆（T20/#21 扩展日程，T28/#29 扩展记忆）。
 * [MEMORY] 只支持新增（CREATE）：记忆的停用/启用与删除在记忆页面管理，
 * 聊天总结绝不自动升级为长期记忆（GLOSSARY：聊天历史本身不等于长期记忆）。
 */
@Serializable
enum class AiProposalKind { TASK, NOTE, SCHEDULE, MEMORY }

/**
 * 提案操作类型（T21/#22 纯新增）：新增、修改或删除既有记录。
 * 默认 [CREATE] 保证既有持久化提案 JSON 零回归。
 */
@Serializable
enum class AiProposalOp { CREATE, MODIFY, DELETE }

/**
 * 删除类提案的具体动作（T21/#22 纯新增）。
 * GLOSSARY：取消待办 ≠ 删除待办——[CANCEL] 保留业务记录并停止其提醒；
 * [TRASH] 移入回收站（可恢复）。便签与日程只有删除（回收站）语义，没有取消。
 */
@Serializable
enum class AiProposalDeleteAction { CANCEL, TRASH }

/**
 * 修改提案的目标记录快照（T21/#22 纯新增）：目标定位时从真实仓库只读复制的关键字段
 * （含 [updatedAt] 一致性指纹）。卡片据此展示「旧值 → 新值」预览；
 * 确认执行时若当前记录 [updatedAt] 与快照不一致（确认前被手动修改），拒绝覆盖并如实回执。
 */
@Serializable
data class AiProposalTargetRecord(
    val recordId: String,
    val title: String,
    val updatedAt: Long,
    // 待办字段（其余 kind 为默认值）
    val note: String = "",
    val priority: String = "",
    val category: String = "",
    val dueEpochDay: Long? = null,
    val dueAtMillis: Long? = null,
    val status: String = "",
    // 便签字段
    val content: String = "",
    val tags: List<String> = emptyList(),
    val items: List<String> = emptyList(),
    // 日程字段
    val startMillis: Long? = null,
    val endMillis: Long? = null,
    val location: String = "",
    val timezoneId: String = "",
    // T22/#23 重复字段（仅待办使用）：目标当前重复规则与出现锚点快照。
    // 修改/取消重复待办时据此要求并展示「仅本次/本次及以后」范围；
    // 两者均为 null = 普通待办（无需选择范围）。
    val recurrence: AiProposalRecurrence? = null,
    val anchorEpochDay: Long? = null,
)

/**
 * 修改提案的变更集（T21/#22 纯新增）：null / 缺省字段 = 不改（最小变更，绝不静默清空）；
 * [clearDue] 表示清空待办截止（与设置新截止互斥）；[completed] 为待办完成/撤销完成变更。
 * 时间字段已在解析层按创建时区换算为 UTC 毫秒（[dueEpochDay] 日期截止不补时刻）。
 */
@Serializable
data class AiProposalChanges(
    val title: String? = null,
    val note: String? = null,
    val priority: String? = null,
    val category: String? = null,
    val dueEpochDay: Long? = null,
    val dueAtMillis: Long? = null,
    val clearDue: Boolean = false,
    val completed: Boolean? = null,
    val content: String? = null,
    val tags: List<String>? = null,
    val items: List<String>? = null,
    val startMillis: Long? = null,
    val endMillis: Long? = null,
    val location: String? = null,
    // T22/#23 重复与子任务变更（仅待办、仅「本次及以后」）：
    // [recurrence] 为新规则（替换），[clearRecurrence] 为移除重复（与设置新规则互斥），
    // [addSubTasks] 为追加的子任务步骤模板（不删除既有步骤）。
    val recurrence: AiProposalRecurrence? = null,
    val clearRecurrence: Boolean = false,
    val addSubTasks: List<String> = emptyList(),
)

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
 * [op] 区分新增/修改/删除（T21/#22，默认 CREATE 兼容既有 JSON）：
 * - CREATE：字段即新记录内容；
 * - MODIFY/DELETE：[kind] 表示目标类型，[title] 为用户提供的定位线索（[targetTitle] 同值），
 *   [changes] 为修改的变更集（null = 删除），[deleteAction] 为删除动作；
 *   目标由设备端按线索匹配本地记录：唯一候选自动绑定（[targetRecordId]/[targetRecord]），
 *   多候选内嵌 [candidates] 供用户在卡片中点选（绝不静默猜测），匹配失败不落提案、中文追问。
 * 待办字段（[note]/[priority]/[category]/[dueEpochDay]/[dueAtMillis]）、便签字段
 * （[content]/[tags]/[items]）与日程字段（[startMillis]/[endMillis]/[location]/[reminders]，T20/#21）
 * 按 [kind] 各取所需；解析层保证互斥与合法性：
 * - [dueEpochDay] 日期截止（不含时刻）；[dueAtMillis] 时刻截止（按创建时区换算的 UTC 毫秒）；两者绝不同时非空；
 * - [startMillis]/[endMillis] 日程起止（按创建时区换算的 UTC 毫秒，结束不早于开始）；
 * - [priority] 为归一后的 LOW/NORMAL/HIGH；
 * - 便签 [content] 保留用户原话，解析层不改写。
 * 新增字段均有默认值：既有持久化提案 JSON 与 task/note/schedule 协议零回归。
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
    // T21/#22 修改/删除字段（op=MODIFY/DELETE 时使用，解析层保证合法性）
    val op: AiProposalOp = AiProposalOp.CREATE,
    val targetTitle: String = "",
    val changes: AiProposalChanges? = null,
    val deleteAction: AiProposalDeleteAction? = null,
    val candidates: List<AiProposalTargetRecord> = emptyList(),
    val targetRecordId: String? = null,
    val targetRecord: AiProposalTargetRecord? = null,
    // T28/#29 记忆条目字段（仅 MEMORY 条目使用）：类型为偏好或事实，解析层校验；
    // 记忆原文存 [content]（用户原话不改写），[title] 为展示用摘要（原文截断）。
    val memoryType: String = "FACT",
    // T22/#23 重复与子任务字段：
    // - [recurrence]：新增/修改重复规则（确认执行经 RecurrenceUseCases 真实入口落地）；
    // - [subTasks]：新增待办的子任务步骤模板（确认后经 SubTaskUseCases 逐条创建）；
    // - [scope]/[occurrenceEpochDay]：修改或取消重复待办时的范围（仅本次/本次及以后）
    //   与「仅本次」指向的那一次出现日（解析层保证 scope=本次时日期非空）。
    val recurrence: AiProposalRecurrence? = null,
    val subTasks: List<String> = emptyList(),
    val scope: AiProposalScope? = null,
    val occurrenceEpochDay: Long? = null,
)

/**
 * 提案中的重复规则（T22/#23 纯新增）：与领域模型 [RecurrenceRule] 一一对应，
 * 但以可序列化原语表达（[weekdays] 用 DayOfWeek.name，[exceptions] 用 epochDay），
 * 因为提案存 DataStore JSON 而非 Room，不直接序列化领域模型。
 * [exceptions] 为例外日期（仅本次排除该日出现，不改写规则几何）。
 */
@Serializable
data class AiProposalRecurrence(
    val frequency: String,
    val intervalDays: Int = 1,
    val weekdays: List<String> = emptyList(),
    val monthDay: Int = 1,
    val exceptions: List<Long> = emptyList(),
)

/**
 * 修改或取消重复待办的作用范围（T22/#23）：[THIS_OCCURRENCE] 仅本次（单次延期或跳过），
 * [THIS_AND_FUTURE] 本次及以后（修改模板/规则，过去记录保持不变）。
 */
@Serializable
enum class AiProposalScope { THIS_OCCURRENCE, THIS_AND_FUTURE }

/**
 * 一次对话产生的操作提案：唯一 [id] + 状态机 + 全部条目。
 * [sessionId] 关联产生它的聊天会话（新会话时由发送路径生成后回填）；
 * [createdTaskIds]/[createdNoteIds]/[createdScheduleIds]（T20/#21）与 [createdMemoryIds]（T28/#29）
 * 为确认后经真实用例入口创建的记录标识；
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
    val createdMemoryIds: List<String> = emptyList(),
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
