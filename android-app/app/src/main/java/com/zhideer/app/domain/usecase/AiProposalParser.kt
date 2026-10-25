package com.zhideer.app.domain.usecase

import com.zhideer.app.domain.model.AiProposalChanges
import com.zhideer.app.domain.model.AiProposalDeleteAction
import com.zhideer.app.domain.model.AiProposalItem
import com.zhideer.app.domain.model.AiProposalKind
import com.zhideer.app.domain.model.AiProposalOp
import com.zhideer.app.domain.model.AiProposalReminderSpec
import com.zhideer.app.domain.model.AiProposalReminderType
import com.zhideer.app.domain.model.AiProposalTargetRecord
import com.zhideer.app.domain.model.MemoryType
import com.zhideer.app.domain.model.TaskPriority
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * 操作提案解析（T19/#20，T20/#21 扩展日程与提醒）：把模型回复文本解析为提案条目。
 *
 * 协议（system prompt 与模型约定）：模型在回复末尾输出 ```json 代码块，
 * 结构为 {"proposals":[{"kind":"task"|"note"|"schedule",...}]}；无创建意图时不输出 JSON 块。
 * 解析规则：
 * - 无 JSON 块 → [AiProposalParseOutcome.Plain]（普通回复）；
 * - JSON 无法解析（含截断）→ [AiProposalParseOutcome.Invalid]（调用方自动重试一次）；
 * - 字段非法（空标题、双截止、未知类型/优先级、日程缺结束时间、结束早于开始、
 *   提醒非法、跨类型携带对方字段）→ [AiProposalParseOutcome.Invalid]，整批不保存；
 * - 合法 → [AiProposalParseOutcome.Drafts]，条目 id 已生成、截止与日程起止/独立提醒时刻
 *   均已按创建时区换算为 UTC 毫秒（日期截止不补具体时刻）。
 * 全部错误原因均为中文可诊断提示，不含英文裸异常。
 */
internal sealed interface AiProposalParseOutcome {
    /** 无提案 JSON 块：普通回复。 */
    data object Plain : AiProposalParseOutcome

    /** 合法提案条目（id 已生成、时间已按时区换算）。 */
    data class Drafts(val items: List<AiProposalItem>) : AiProposalParseOutcome

    /** 解析或字段校验失败：[reason] 为中文可诊断提示。 */
    data class Invalid(val reason: String) : AiProposalParseOutcome
}

/** 协议 DTO（ignoreUnknownKeys：容忍模型输出额外字段）。 */
@Serializable
private data class ProposalPayload(val proposals: List<ProposalItemDto> = emptyList())

@Serializable
private data class ProposalItemDto(
    val kind: String? = null,
    val title: String? = null,
    val note: String? = null,
    val priority: String? = null,
    val category: String? = null,
    val dueDate: String? = null,
    val dueAt: String? = null,
    val content: String? = null,
    val tags: List<String>? = null,
    val items: List<String>? = null,
    // T20/#21 日程字段
    val start: String? = null,
    val end: String? = null,
    val location: String? = null,
    val reminder: ProposalReminderDto? = null,
    val reminders: List<ProposalReminderDto>? = null,
    // T21/#22 修改/删除字段
    val target: ProposalTargetDto? = null,
    val changes: ProposalChangesDto? = null,
    val action: String? = null,
    // T28/#29 记忆条目字段：preference（偏好）或 fact（事实），缺省按事实处理
    val memoryType: String? = null,
)

/** 修改/删除提案的目标定位线索（T21/#22）：kind 为目标类型，title 为用户原话中的名称或关键词。 */
@Serializable
private data class ProposalTargetDto(
    val kind: String? = null,
    val title: String? = null,
)

/** 修改提案的变更集 DTO（T21/#22）：只含用户要改的字段，缺省 = 不改。 */
@Serializable
private data class ProposalChangesDto(
    val title: String? = null,
    val note: String? = null,
    val priority: String? = null,
    val category: String? = null,
    val dueDate: String? = null,
    val dueAt: String? = null,
    val clearDue: Boolean? = null,
    val completed: Boolean? = null,
    val content: String? = null,
    val tags: List<String>? = null,
    val items: List<String>? = null,
    val start: String? = null,
    val end: String? = null,
    val location: String? = null,
)

/** 提醒 DTO：相对提前量（offsetMillis，毫秒）或独立时刻（remindAt，本地时间字符串）互斥。 */
@Serializable
private data class ProposalReminderDto(
    val type: String? = null,
    val offsetMillis: Long? = null,
    val remindAt: String? = null,
)

private val proposalJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * 提取回复中的提案 JSON 文本：```json 代码块（取最后一个，允许模型省略语言标注），
 * 或整体就是裸 JSON 对象；无则返回 null。
 */
internal fun extractProposalJson(raw: String): String? {
    val blocks = Regex("```(?:json)?\\s*(.*?)\\s*```", RegexOption.DOT_MATCHES_ALL)
        .findAll(raw)
        .map { it.groupValues[1].trim() }
        .filter { it.isNotEmpty() }
        .toList()
    if (blocks.isNotEmpty()) return blocks.last()
    val trimmed = raw.trim()
    return if (trimmed.startsWith("{") && trimmed.endsWith("}")) trimmed else null
}

/** 生成注入请求的提案协议 system 提示（注入今天日期与时区，供模型推算相对日期）。 */
internal fun buildProposalSystemPrompt(today: LocalDate, zoneId: ZoneId): String = buildString {
    appendLine("你是知时鹿（一款本地日程、待办与便签应用）的 AI 助手。")
    appendLine("当用户明确想新增待办、便签或日程，或明确要求记住某条偏好/事实时，在回复的最末尾输出一个 ```json 代码块起草操作提案；提案只是供用户核对的草案，用户确认后才会真正保存。")
    appendLine("今天是 $today（${weekdayCn(today)}），用户时区 ${zoneId.id}。涉及相对日期（明天、下周三等）时按今天推算，一律写绝对日期。")
    appendLine("JSON 结构（proposals 数组可含多项）：")
    appendLine("```json")
    appendLine(
        "{\"proposals\":[" +
            "{\"kind\":\"task\",\"title\":\"标题\",\"note\":\"备注\",\"priority\":\"NORMAL\",\"category\":\"分类\"," +
            "\"dueDate\":\"2026-10-26\"|\"dueAt\":\"2026-10-26T14:30\"}," +
            "{\"kind\":\"note\",\"title\":\"标题\",\"content\":\"正文\",\"tags\":[\"标签\"],\"items\":[\"清单项\"]}," +
            "{\"kind\":\"schedule\",\"title\":\"标题\",\"start\":\"2026-10-26T14:00\",\"end\":\"2026-10-26T15:30\"," +
            "\"location\":\"地点\",\"note\":\"备注\",\"category\":\"分类\"," +
            "\"reminder\":{\"type\":\"relative\",\"offsetMillis\":900000}}," +
            "{\"kind\":\"memory\",\"content\":\"要记住的内容原话\",\"memoryType\":\"preference\"|\"fact\"}" +
            "]}",
    )
    appendLine("```")
    appendLine("字段规则：")
    appendLine("1. 新增提案 kind 只能是 \"task\"（待办）、\"note\"（便签）、\"schedule\"（日程）或 \"memory\"（长期记忆），title 必填且不能为空（memory 条目除外，见规则 12）；修改与删除提案 kind 为 \"modify\" 或 \"delete\"，目标写在 target 字段（见规则 9-11）。")
    appendLine("2. task 可选字段：note（备注）、priority（LOW/NORMAL/HIGH，默认 NORMAL）、category（分类）、dueDate 或 dueAt。")
    appendLine("3. dueDate 是日期截止（yyyy-MM-dd，不含具体时刻）；dueAt 是具体时刻截止（yyyy-MM-dd'T'HH:mm）。两者只能选其一，绝不同时给出；日期截止不要补 23:59 之类的时刻。")
    appendLine("4. 用户提到的时间不明确（如\"尽快\"或说不清具体哪天）时，先用文字向用户追问，确认后再输出 JSON；绝不编造日期。")
    appendLine("5. note 可选字段：content（便签正文，保留用户原话，不改写不扩写）、tags（标签数组）、items（清单项文本数组）。")
    appendLine("6. schedule 字段：start 与 end 必填（本地时间 yyyy-MM-dd'T'HH:mm；结束可跨日，直接写次日的日期与时刻）；可选 location（地点）、note（备注）、category（分类）。日程是占用一段时间，用户只说了一个时间点而没说明结束时间或时长时，先用文字追问，绝不编造结束时间。task/note 绝不携带 start/end/reminder/reminders 字段，schedule 绝不携带 dueDate/dueAt 字段。")
    appendLine("7. schedule 的提醒只在用户明确要求时给出，用户没要求提醒时绝不添加提醒字段：reminder 为单个提醒对象 {\"type\":\"relative\",\"offsetMillis\":毫秒数}（相对日程开始提前，如提前 15 分钟为 900000）或 {\"type\":\"absolute\",\"remindAt\":\"yyyy-MM-dd'T'HH:mm\"}（独立时刻，不得早于日程开始；用户要提前提醒时优先换算为相对提前量）；相对提前量与独立时刻只能选其一；多个提醒用 reminders 数组（元素与 reminder 相同）。")
    appendLine("8. 一次可起草多项（proposals 数组多项）。用户没有新增、修改或删除意图时正常对话，绝不输出任何 JSON 代码块。")
    appendLine("9. 修改提案结构：{\"kind\":\"modify\",\"target\":{\"kind\":\"task\"|\"note\"|\"schedule\",\"title\":\"用户提到的名称\"},\"changes\":{...}}。target.title 一律用用户原话中的名称或关键词，不改写不补全；系统会按它在本地记录中定位目标，匹配到多条时由用户挑选。changes 只包含用户明确要改的字段，没提到的字段绝不给出。task 可改：title、note、priority、category、dueDate、dueAt、clearDue（true 表示清空截止）、completed（true 完成、false 撤销完成）；note 可改：title、content、tags、items；schedule 可改：title、start、end（改时间必须同时给出新的 start 与 end）、location、note、category。跨类型字段绝不出现在 changes 中，顶层也绝不携带 title/dueDate/priority 等创建字段。")
    appendLine("10. 删除/取消提案结构：{\"kind\":\"delete\",\"target\":{\"kind\":...,\"title\":...},\"action\":\"cancel\"|\"trash\"}。用户说不再做某个待办 → action:\"cancel\"（取消待办：保留记录并停止其提醒，不是删除）；说删除 → \"trash\"；便签与日程只有删除语义，action 用 \"trash\" 或省略。删除提案绝不带 changes 字段。")
    appendLine("11. 用户没说清要改哪一条、要改什么内容，或说不清是取消还是删除时，先用文字向用户追问，确认后再输出 JSON；绝不猜测目标，绝不编造不存在的记录。")
    appendLine("12. 长期记忆提案结构：{\"kind\":\"memory\",\"content\":\"用户原话\",\"memoryType\":\"preference\"|\"fact\"}。只在用户明确要求记住（如\"记住：…\"\"帮我记住…\"）时输出；content 一律用用户原话，不改写不扩写；memoryType 写 \"preference\"（习惯、喜好与倾向）或 \"fact\"（客观信息，如过敏、住址、家庭成员），判断不了时省略（按 fact 处理）。绝不为普通聊天总结或你没被明确要求记住的内容起草记忆提案；记忆没有截止时间和优先级，绝不携带 dueDate/dueAt/priority 等其他创建字段。")
}

private fun weekdayCn(date: LocalDate): String =
    when (date.dayOfWeek.value) {
        1 -> "星期一"
        2 -> "星期二"
        3 -> "星期三"
        4 -> "星期四"
        5 -> "星期五"
        6 -> "星期六"
        else -> "星期日"
    }

/** 解析模型回复；含提案块且合法 → Drafts；无块 → Plain；失败 → Invalid（中文原因）。 */
internal fun parseProposalReply(raw: String, zoneId: ZoneId, idGen: () -> String): AiProposalParseOutcome {
    val jsonText = extractProposalJson(raw)
    when {
        jsonText != null -> Unit
        // 有 ```json 开栅栏但没有闭合块：回复被截断，视为解析失败（可自动重试）而不是普通回复
        Regex("```").findAll(raw).count() % 2 == 1 ->
            return AiProposalParseOutcome.Invalid("提案内容被截断（JSON 代码块未闭合），无法解析。")
        else -> return AiProposalParseOutcome.Plain
    }
    val payload = try {
        proposalJson.decodeFromString<ProposalPayload>(jsonText)
    } catch (e: SerializationException) {
        return AiProposalParseOutcome.Invalid("提案内容不完整或格式错误（可能被截断），无法解析。")
    } catch (e: IllegalArgumentException) {
        return AiProposalParseOutcome.Invalid("提案内容不完整或格式错误，无法解析。")
    }
    if (payload.proposals.isEmpty()) {
        return AiProposalParseOutcome.Invalid("提案内容为空：没有给出任何可创建条目。")
    }
    val items = mutableListOf<AiProposalItem>()
    payload.proposals.forEachIndexed { index, dto ->
        try {
            items += parseProposalItem(dto, index + 1, zoneId, idGen)
        } catch (e: ProposalFieldException) {
            // 任一项非法：整批不保存（与既有协议一致）
            return AiProposalParseOutcome.Invalid(e.message!!)
        }
    }
    return AiProposalParseOutcome.Drafts(items)
}

/** 按顶层 kind 分派：task/note/schedule/memory 为新增，modify/delete 为修改与删除（T21/#22、T28/#29）。 */
private fun parseProposalItem(
    dto: ProposalItemDto,
    n: Int,
    zoneId: ZoneId,
    idGen: () -> String,
): AiProposalItem = when (dto.kind?.trim()?.lowercase(Locale.ROOT)) {
    "task" -> parseCreateItem(dto, n, AiProposalKind.TASK, zoneId, idGen)
    "note" -> parseCreateItem(dto, n, AiProposalKind.NOTE, zoneId, idGen)
    "schedule" -> parseCreateItem(dto, n, AiProposalKind.SCHEDULE, zoneId, idGen)
    "memory" -> parseMemoryItem(dto, n, idGen)
    "modify" -> parseModifyItem(dto, n, zoneId, idGen)
    "delete" -> parseDeleteItem(dto, n, idGen)
    else -> throw ProposalFieldException(
        "第 $n 项提案的类型无法识别（kind 只能是 task、note、schedule、memory、modify 或 delete）。",
    )
}

/**
 * 解析记忆条目（T28/#29 纯新增）：content 为用户原话（必填，不改写），
 * memoryType 只能是 preference（偏好）或 fact（事实），缺省按 fact。
 * 记忆没有截止时间与优先级，携带其他创建字段时整批拒绝（可诊断提示）。
 */
private fun parseMemoryItem(dto: ProposalItemDto, n: Int, idGen: () -> String): AiProposalItem {
    if (dto.target != null || dto.changes != null || dto.action != null) {
        throw ProposalFieldException("第 $n 项提案是记忆类型，不应包含修改或删除字段（target/changes/action）。")
    }
    val hasForeign = !dto.note.isNullOrEmpty() || !dto.priority.isNullOrEmpty() ||
        !dto.category.isNullOrEmpty() || !dto.dueDate.isNullOrEmpty() || !dto.dueAt.isNullOrEmpty() ||
        !dto.tags.isNullOrEmpty() || !dto.items.isNullOrEmpty() || !dto.start.isNullOrEmpty() ||
        !dto.end.isNullOrEmpty() || !dto.location.isNullOrEmpty() || dto.reminder != null ||
        !dto.reminders.isNullOrEmpty()
    if (hasForeign) {
        throw ProposalFieldException(
            "第 $n 项提案是记忆类型，只支持 content 与 memoryType 字段（记忆没有截止时间、优先级或清单等属性）。",
        )
    }
    val content = dto.content?.trim().orEmpty()
    if (content.isEmpty()) {
        throw ProposalFieldException("第 $n 项记忆提案缺少内容（content）：请给出要记住的用户原话。")
    }
    val memoryTypeRaw = dto.memoryType?.trim()?.lowercase(Locale.ROOT)
    val memoryType = when (memoryTypeRaw) {
        null, "" -> "FACT"
        "preference" -> "PREFERENCE"
        "fact" -> "FACT"
        else -> throw ProposalFieldException(
            "第 $n 项记忆提案的类型无法识别（memoryType 只能是 preference（偏好）或 fact（事实））。",
        )
    }
    return AiProposalItem(
        id = idGen(),
        kind = AiProposalKind.MEMORY,
        title = memoryTitleOf(dto, content),
        content = content,
        memoryType = memoryType,
    )
}

/** 记忆条目无标题概念：展示摘要取模型给的 title，否则用内容前缀截断（原文不受影响）。 */
private fun memoryTitleOf(dto: ProposalItemDto, content: String): String {
    val given = dto.title?.trim().orEmpty()
    return if (given.isNotEmpty()) given else content.take(MEMORY_TITLE_MAX) + if (content.length > MEMORY_TITLE_MAX) "…" else ""
}

/** 目标 DTO → 目标类型；缺失或非法给出中文可诊断提示。 */
private fun targetKindOf(target: ProposalTargetDto?, n: Int): AiProposalKind = when (target?.kind?.trim()?.lowercase(Locale.ROOT)) {
    "task" -> AiProposalKind.TASK
    "note" -> AiProposalKind.NOTE
    "schedule" -> AiProposalKind.SCHEDULE
    null -> throw ProposalFieldException("第 $n 项提案缺少目标（target）：请指明要操作的记录类型与名称（{\"kind\":...,\"title\":...}）。")
    else -> throw ProposalFieldException("第 $n 项提案的目标类型无法识别（target.kind 只能是 task、note 或 schedule）。")
}

/** 目标 DTO → 定位线索（用户原话中的名称或关键词，绝不改写）。 */
private fun targetTitleOf(target: ProposalTargetDto?, n: Int, verb: String): String =
    target?.title?.trim()?.takeUnless { it.isEmpty() }
        ?: throw ProposalFieldException("第 $n 项${verb}提案缺少目标名称（target.title）：请用用户原话中的名称或关键词指明要操作的记录。")

/** 修改/删除提案不应在顶层携带新增字段（模型混淆类型时整批拒绝并给出可诊断提示）。 */
private fun hasTopLevelCreateFields(dto: ProposalItemDto): Boolean =
    !dto.title.isNullOrEmpty() || !dto.note.isNullOrEmpty() || !dto.priority.isNullOrEmpty() ||
        !dto.category.isNullOrEmpty() || !dto.dueDate.isNullOrEmpty() || !dto.dueAt.isNullOrEmpty() ||
        !dto.content.isNullOrEmpty() || !dto.tags.isNullOrEmpty() || !dto.items.isNullOrEmpty() ||
        !dto.start.isNullOrEmpty() || !dto.end.isNullOrEmpty() || !dto.location.isNullOrEmpty() ||
        dto.reminder != null || !dto.reminders.isNullOrEmpty()

/** 解析修改提案（T21/#22）：target 定位线索 + changes 变更集（仅用户要改的字段）。 */
private fun parseModifyItem(dto: ProposalItemDto, n: Int, zoneId: ZoneId, idGen: () -> String): AiProposalItem {
    if (dto.action != null) {
        throw ProposalFieldException("第 $n 项修改提案不应包含删除动作字段（action 只用于 delete 提案）。")
    }
    if (hasTopLevelCreateFields(dto)) {
        throw ProposalFieldException("第 $n 项修改提案不应在顶层携带创建字段：目标写在 target，要改的内容写在 changes。")
    }
    val kind = targetKindOf(dto.target, n)
    val clue = targetTitleOf(dto.target, n, "修改")
    val changesDto = dto.changes
        ?: throw ProposalFieldException("第 $n 项修改提案缺少变更字段（changes）：请给出要修改的内容。")
    val changes = parseChanges(changesDto, kind, n, zoneId)
        ?: throw ProposalFieldException("第 $n 项修改提案没有给出任何变更（changes 至少包含一项要修改的内容）。")
    return AiProposalItem(
        id = idGen(),
        kind = kind,
        title = clue,
        op = AiProposalOp.MODIFY,
        targetTitle = clue,
        changes = changes,
    )
}

/** 解析删除/取消提案（T21/#22）：action 区分取消（仅待办）与删除（回收站）。 */
private fun parseDeleteItem(dto: ProposalItemDto, n: Int, idGen: () -> String): AiProposalItem {
    val kind = targetKindOf(dto.target, n)
    val clue = targetTitleOf(dto.target, n, "删除")
    if (dto.changes != null) {
        throw ProposalFieldException("第 $n 项删除提案不应包含变更字段（changes）。")
    }
    if (hasTopLevelCreateFields(dto)) {
        throw ProposalFieldException("第 $n 项删除提案不应在顶层携带创建字段：目标写在 target，动作写在 action。")
    }
    val actionRaw = dto.action?.trim()?.lowercase(Locale.ROOT)
    val action = when (kind) {
        AiProposalKind.TASK -> when (actionRaw) {
            "cancel" -> AiProposalDeleteAction.CANCEL
            "trash" -> AiProposalDeleteAction.TRASH
            null -> throw ProposalFieldException(
                "第 $n 项删除提案缺少动作（action）：待办需明确是取消（cancel，保留记录并停止提醒）还是删除（trash，移入回收站）。",
            )
            else -> throw ProposalFieldException("第 $n 项删除提案的动作无法识别（action 只能是 cancel 或 trash）。")
        }
        // GLOSSARY：取消待办是待办专属状态变化；便签与日程只有删除（回收站）语义
        AiProposalKind.NOTE -> when (actionRaw) {
            null, "trash" -> AiProposalDeleteAction.TRASH
            "cancel" -> throw ProposalFieldException("第 $n 项删除提案动作非法：便签没有取消语义，只有删除（action 只能是 trash 或省略）。")
            else -> throw ProposalFieldException("第 $n 项删除提案的动作无法识别（action 只能是 trash 或省略）。")
        }
        AiProposalKind.SCHEDULE -> when (actionRaw) {
            null, "trash" -> AiProposalDeleteAction.TRASH
            "cancel" -> throw ProposalFieldException("第 $n 项删除提案动作非法：日程没有取消语义，只有删除（action 只能是 trash 或省略）。")
            else -> throw ProposalFieldException("第 $n 项删除提案的动作无法识别（action 只能是 trash 或省略）。")
        }
        // T28/#29：记忆条目只支持新增提案（targetKindOf 不会给出 MEMORY，此分支为穷举防御）
        AiProposalKind.MEMORY ->
            throw ProposalFieldException("第 $n 项删除提案目标类型非法（记忆不支持删除提案，可在记忆页面停用或删除）。")
    }
    return AiProposalItem(
        id = idGen(),
        kind = kind,
        title = clue,
        op = AiProposalOp.DELETE,
        targetTitle = clue,
        deleteAction = action,
    )
}

/** 解析修改变更集：无任何变更返回 null（整批拒绝）；跨类型字段非法抛 [ProposalFieldException]。 */
private fun parseChanges(dto: ProposalChangesDto, kind: AiProposalKind, n: Int, zoneId: ZoneId): AiProposalChanges? {
    val title = dto.title?.trim()
    if (title != null && title.isEmpty()) {
        throw ProposalFieldException("第 $n 项修改提案的变更标题不能为空白。")
    }
    return when (kind) {
        AiProposalKind.TASK -> parseTaskChanges(dto, n, zoneId, title)
        AiProposalKind.NOTE -> parseNoteChanges(dto, n, title)
        AiProposalKind.SCHEDULE -> parseScheduleChanges(dto, n, zoneId, title)
        // T28/#29：记忆条目只支持新增提案（targetKindOf 不会给出 MEMORY，此分支为穷举防御）
        AiProposalKind.MEMORY ->
            throw ProposalFieldException("第 $n 项修改提案目标类型非法（记忆不支持修改提案，可在记忆页面编辑）。")
    }
}

/** 待办变更：可改 title/note/priority/category/dueDate/dueAt/clearDue/completed。 */
private fun parseTaskChanges(dto: ProposalChangesDto, n: Int, zoneId: ZoneId, title: String?): AiProposalChanges? {
    val hasForeign = dto.content != null || !dto.tags.isNullOrEmpty() || !dto.items.isNullOrEmpty() ||
        !dto.start.isNullOrEmpty() || !dto.end.isNullOrEmpty() || !dto.location.isNullOrEmpty()
    if (hasForeign) {
        throw ProposalFieldException(
            "第 $n 项修改提案是待办类型，changes 不支持便签或日程字段（待办可改：title、note、priority、category、dueDate、dueAt、clearDue、completed）。",
        )
    }
    val dueDateRaw = dto.dueDate?.trim().takeUnless { it.isNullOrEmpty() }
    val dueAtRaw = dto.dueAt?.trim().takeUnless { it.isNullOrEmpty() }
    if (dueDateRaw != null && dueAtRaw != null) {
        throw ProposalFieldException("第 $n 项修改提案同时给出日期截止与时刻截止：两种截止只能选择其一。")
    }
    if (dto.clearDue == true && (dueDateRaw != null || dueAtRaw != null)) {
        throw ProposalFieldException("第 $n 项修改提案的清空截止（clearDue）与设置新截止只能选择其一。")
    }
    val priority = dto.priority?.trim()?.uppercase(Locale.ROOT)
    if (priority != null && priority !in SUPPORTED_PRIORITIES) {
        throw ProposalFieldException("第 $n 项修改提案的优先级无法识别（只支持 LOW/NORMAL/HIGH）。")
    }
    val dueEpochDay = dueDateRaw?.let {
        try {
            LocalDate.parse(it).toEpochDay()
        } catch (e: DateTimeParseException) {
            throw ProposalFieldException("第 $n 项修改提案的截止日期格式无法识别（应为 yyyy-MM-dd）。")
        }
    }
    val dueAtMillis = dueAtRaw?.let {
        try {
            LocalDateTime.parse(it).atZone(zoneId).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            throw ProposalFieldException("第 $n 项修改提案的时刻截止格式无法识别（应为 yyyy-MM-ddTHH:mm）。")
        }
    }
    val hasChange = title != null || !dto.note.isNullOrEmpty() || priority != null ||
        !dto.category.isNullOrEmpty() || dueEpochDay != null || dueAtMillis != null ||
        dto.clearDue == true || dto.completed != null
    if (!hasChange) return null
    return AiProposalChanges(
        title = title,
        note = dto.note?.trim(),
        priority = priority,
        category = dto.category?.trim(),
        dueEpochDay = dueEpochDay,
        dueAtMillis = dueAtMillis,
        clearDue = dto.clearDue == true,
        completed = dto.completed,
    )
}

/** 便签变更：可改 title/content/tags/items（清单整体替换，预览明确标注勾选状态重置）。 */
private fun parseNoteChanges(dto: ProposalChangesDto, n: Int, title: String?): AiProposalChanges? {
    val hasForeign = !dto.note.isNullOrEmpty() || dto.priority != null || !dto.category.isNullOrEmpty() ||
        !dto.dueDate.isNullOrEmpty() || !dto.dueAt.isNullOrEmpty() || dto.clearDue != null ||
        dto.completed != null || !dto.start.isNullOrEmpty() || !dto.end.isNullOrEmpty() ||
        !dto.location.isNullOrEmpty()
    if (hasForeign) {
        throw ProposalFieldException(
            "第 $n 项修改提案是便签类型，changes 不支持待办或日程字段（便签可改：title、content、tags、items）。",
        )
    }
    val items = dto.items?.map { it.trim() }?.filter { it.isNotEmpty() }
    val hasChange = title != null || dto.content != null || !dto.tags.isNullOrEmpty() || !items.isNullOrEmpty()
    if (!hasChange) return null
    return AiProposalChanges(
        title = title,
        content = dto.content,
        tags = dto.tags?.map { it.trim() }?.filter { it.isNotEmpty() },
        items = items,
    )
}

/** 日程变更：可改 title/start/end/location/note/category；改时间必须 start/end 成对。 */
private fun parseScheduleChanges(dto: ProposalChangesDto, n: Int, zoneId: ZoneId, title: String?): AiProposalChanges? {
    val hasForeign = dto.priority != null || !dto.dueDate.isNullOrEmpty() || !dto.dueAt.isNullOrEmpty() ||
        dto.clearDue != null || dto.completed != null || dto.content != null ||
        !dto.tags.isNullOrEmpty() || !dto.items.isNullOrEmpty()
    if (hasForeign) {
        throw ProposalFieldException(
            "第 $n 项修改提案是日程类型，changes 不支持待办或便签字段（日程可改：title、start、end、location、note、category）。",
        )
    }
    val startRaw = dto.start?.trim().takeUnless { it.isNullOrEmpty() }
    val endRaw = dto.end?.trim().takeUnless { it.isNullOrEmpty() }
    if ((startRaw == null) != (endRaw == null)) {
        throw ProposalFieldException("第 $n 项修改提案改日程时间必须同时给出新的开始（start）与结束（end）。")
    }
    val startMillis = startRaw?.let {
        try {
            LocalDateTime.parse(it).atZone(zoneId).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            throw ProposalFieldException("第 $n 项修改提案的开始时间格式无法识别（应为 yyyy-MM-ddTHH:mm）。")
        }
    }
    val endMillis = endRaw?.let {
        try {
            LocalDateTime.parse(it).atZone(zoneId).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            throw ProposalFieldException("第 $n 项修改提案的结束时间格式无法识别（应为 yyyy-MM-ddTHH:mm）。")
        }
    }
    if (startMillis != null && endMillis != null && endMillis < startMillis) {
        throw ProposalFieldException("第 $n 项修改提案的结束时间早于开始时间。")
    }
    val hasChange = title != null || startMillis != null || !dto.note.isNullOrEmpty() ||
        !dto.category.isNullOrEmpty() || !dto.location.isNullOrEmpty()
    if (!hasChange) return null
    return AiProposalChanges(
        title = title,
        note = dto.note?.trim(),
        category = dto.category?.trim(),
        startMillis = startMillis,
        endMillis = endMillis,
        location = dto.location?.trim(),
    )
}

/** 解析新增条目（既有协议，T19/#20、T20/#21；校验与行为不变）。 */
private fun parseCreateItem(
    dto: ProposalItemDto,
    n: Int,
    kind: AiProposalKind,
    zoneId: ZoneId,
    idGen: () -> String,
): AiProposalItem {
    if (dto.target != null || dto.changes != null || dto.action != null) {
        throw ProposalFieldException("第 $n 项提案是新增类型，不应包含修改或删除字段（target/changes/action）。")
    }
    val title = dto.title?.trim().orEmpty()
    if (title.isEmpty()) {
        throw ProposalFieldException("第 $n 项提案的标题为空：标题不能为空。")
    }
    val priority = dto.priority?.trim()?.uppercase(Locale.ROOT).takeUnless { it.isNullOrEmpty() } ?: "NORMAL"
    if (priority !in SUPPORTED_PRIORITIES) {
        throw ProposalFieldException("第 $n 项提案的优先级无法识别（只支持 LOW/NORMAL/HIGH）。")
    }
    val dueDateRaw = dto.dueDate?.trim().takeUnless { it.isNullOrEmpty() }
    val dueAtRaw = dto.dueAt?.trim().takeUnless { it.isNullOrEmpty() }
    if (dueDateRaw != null && dueAtRaw != null) {
        throw ProposalFieldException("第 $n 项提案同时给出日期截止与时刻截止：两种截止只能选择其一。")
    }
    val dueEpochDay = dueDateRaw?.let {
        try {
            LocalDate.parse(it).toEpochDay()
        } catch (e: DateTimeParseException) {
            throw ProposalFieldException("第 $n 项提案的截止日期格式无法识别（应为 yyyy-MM-dd）。")
        }
    }
    val dueAtMillis = dueAtRaw?.let {
        try {
            LocalDateTime.parse(it).atZone(zoneId).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            throw ProposalFieldException("第 $n 项提案的时刻截止格式无法识别（应为 yyyy-MM-ddTHH:mm）。")
        }
    }
    if (kind == AiProposalKind.SCHEDULE) {
        // T20/#21 日程条目：start/end 必填并按创建时区换算，提醒作为附属字段解析校验
        if (dueDateRaw != null || dueAtRaw != null) {
            throw ProposalFieldException("第 $n 项提案是日程类型，不应包含截止字段（dueDate/dueAt）；日程时间用 start/end 表示。")
        }
        val startRaw = dto.start?.trim().takeUnless { it.isNullOrEmpty() }
            ?: throw ProposalFieldException("第 $n 项提案缺少开始时间：日程需要明确的开始时间（yyyy-MM-ddTHH:mm）。")
        val endRaw = dto.end?.trim().takeUnless { it.isNullOrEmpty() }
            ?: throw ProposalFieldException("第 $n 项提案缺少结束时间：单个时间点不能擅自推断持续时长，请先与用户确认结束时间或时长。")
        val startMillis = try {
            LocalDateTime.parse(startRaw).atZone(zoneId).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            throw ProposalFieldException("第 $n 项提案的开始时间格式无法识别（应为 yyyy-MM-ddTHH:mm）。")
        }
        val endMillis = try {
            LocalDateTime.parse(endRaw).atZone(zoneId).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            throw ProposalFieldException("第 $n 项提案的结束时间格式无法识别（应为 yyyy-MM-ddTHH:mm）。")
        }
        if (endMillis < startMillis) {
            throw ProposalFieldException("第 $n 项提案的结束时间早于开始时间。")
        }
        val reminderSpecs = reminderSpecsOf(dto, n, startMillis, zoneId)
        return AiProposalItem(
            id = idGen(),
            kind = kind,
            title = title,
            note = dto.note?.trim().orEmpty(),
            priority = priority,
            category = dto.category?.trim().orEmpty(),
            startMillis = startMillis,
            endMillis = endMillis,
            location = dto.location?.trim().orEmpty(),
            reminders = reminderSpecs,
        )
    }
    // task/note 条目不得携带日程字段：模型混淆类型时整批拒绝并给出可诊断提示
    val hasScheduleTime = !dto.start?.trim().isNullOrEmpty() || !dto.end?.trim().isNullOrEmpty()
    val hasReminderField = dto.reminder != null || !dto.reminders.isNullOrEmpty()
    if (hasScheduleTime || hasReminderField) {
        val kindLabel = if (kind == AiProposalKind.TASK) "待办" else "便签"
        throw ProposalFieldException(
            "第 $n 项提案是$kindLabel 类型，不应包含日程时间或提醒字段（start/end/reminder/reminders）。",
        )
    }
    return AiProposalItem(
        id = idGen(),
        kind = kind,
        title = title,
        note = dto.note?.trim().orEmpty(),
        priority = priority,
        category = dto.category?.trim().orEmpty(),
        dueEpochDay = dueEpochDay,
        dueAtMillis = dueAtMillis,
        content = dto.content?.trim().orEmpty(),
        tags = dto.tags.orEmpty().map { it.trim() }.filter { it.isNotEmpty() },
        items = dto.items.orEmpty().map { it.trim() }.filter { it.isNotEmpty() },
    )
}

/** 字段非法的内部信号：message 即面向用户的中文可诊断提示。 */
private class ProposalFieldException(message: String) : Exception(message)

/** 提醒规格去重键：同一（相对提前量 | 独立时刻）只保留一条。 */
internal fun reminderSpecKey(spec: AiProposalReminderSpec): String = when (spec.type) {
    AiProposalReminderType.RELATIVE -> "R:${spec.offsetMillis}"
    AiProposalReminderType.ABSOLUTE -> "A:${spec.remindAtMillis}"
}

/**
 * 解析日程条目的附属提醒（T20/#21）：reminder（单个）或 reminders（数组）均可，合并后去重。
 * 校验：type 只能是 relative/absolute；相对提前量为正且与独立时刻互斥；
 * 独立时刻可解析且不早于日程开始（早于开始按创建时区换算后判定）。
 * 非法抛 [ProposalFieldException]（中文提示），由调用方整批拒绝。
 */
private fun reminderSpecsOf(
    dto: ProposalItemDto,
    n: Int,
    startMillis: Long,
    zoneId: ZoneId,
): List<AiProposalReminderSpec> {
    val reminderDtos = buildList {
        dto.reminder?.let { add(it) }
        dto.reminders?.let { addAll(it) }
    }
    if (reminderDtos.isEmpty()) return emptyList()
    val specs = mutableListOf<AiProposalReminderSpec>()
    reminderDtos.forEach { r ->
        val type = when (r.type?.trim()?.lowercase(Locale.ROOT)) {
            "relative" -> AiProposalReminderType.RELATIVE
            "absolute" -> AiProposalReminderType.ABSOLUTE
            else -> throw ProposalFieldException("第 $n 项提案的提醒类型无法识别（type 只能是 relative 或 absolute）。")
        }
        val spec = when (type) {
            AiProposalReminderType.RELATIVE -> {
                if (r.remindAt != null) {
                    throw ProposalFieldException("第 $n 项提案的提醒字段非法：相对提前量与独立时刻只能选择其一。")
                }
                val offset = r.offsetMillis
                    ?: throw ProposalFieldException("第 $n 项提案的相对提醒缺少提前量（offsetMillis，毫秒）。")
                if (offset <= 0) {
                    throw ProposalFieldException("第 $n 项提案的相对提醒提前量非法（需为提前于日程开始的正毫秒数）。")
                }
                AiProposalReminderSpec(type = type, offsetMillis = offset)
            }
            AiProposalReminderType.ABSOLUTE -> {
                if (r.offsetMillis != null) {
                    throw ProposalFieldException("第 $n 项提案的提醒字段非法：相对提前量与独立时刻只能选择其一。")
                }
                val raw = r.remindAt?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: throw ProposalFieldException("第 $n 项提案的独立时刻提醒缺少提醒时间（remindAt，yyyy-MM-ddTHH:mm）。")
                val millis = try {
                    LocalDateTime.parse(raw).atZone(zoneId).toInstant().toEpochMilli()
                } catch (e: DateTimeParseException) {
                    throw ProposalFieldException("第 $n 项提案的提醒时刻格式无法识别（应为 yyyy-MM-ddTHH:mm）。")
                }
                if (millis < startMillis) {
                    throw ProposalFieldException("第 $n 项提案的独立时刻提醒早于日程开始时间。")
                }
                AiProposalReminderSpec(type = type, remindAtMillis = millis)
            }
        }
        specs += spec
    }
    return specs.distinctBy { reminderSpecKey(it) }
}

/** 提案条目的人类可读摘要（保存进会话的助手消息内容，绝不包含原始 JSON）。 */
internal fun summarizeProposal(items: List<AiProposalItem>, zoneId: ZoneId): String = buildString {
    append("已起草 ").append(items.size).append(" 项操作提案，请在下方卡片中核对、确认执行或忽略：")
    items.forEachIndexed { index, item ->
        append("\n").append(index + 1).append(". ")
        when (item.op) {
            // T21/#22：修改/删除条目的摘要（目标线索 + 将修改的字段 / 取消与删除语义）
            AiProposalOp.MODIFY -> {
                append("[修改").append(kindCn(item.kind)).append("] ").append(item.targetTitle)
                val names = item.changes?.let { changeFieldNames(it) }.orEmpty()
                if (names.isNotEmpty()) append("（将修改：").append(names.joinToString("、")).append("）")
            }
            AiProposalOp.DELETE -> {
                append(
                    if (item.kind == AiProposalKind.TASK && item.deleteAction == AiProposalDeleteAction.CANCEL) {
                        "[取消待办] "
                    } else {
                        "[删除${kindCn(item.kind)}] "
                    },
                ).append(item.targetTitle)
            }
            AiProposalOp.CREATE -> when (item.kind) {
                AiProposalKind.TASK -> {
                    append("[待办] ").append(item.title)
                    val details = buildList {
                        proposalDueText(item, zoneId)?.let { add("截止 $it") }
                        if (item.priority != "NORMAL") add("优先级 ${priorityCn(item.priority)}")
                        if (item.category.isNotBlank()) add("分类 ${item.category}")
                    }
                    if (details.isNotEmpty()) append("（").append(details.joinToString("，")).append("）")
                }
                AiProposalKind.NOTE -> {
                    append("[便签] ").append(item.title)
                    val details = buildList {
                        if (item.content.isNotBlank()) add("正文 ${item.content.length} 字")
                        if (item.items.isNotEmpty()) add("清单 ${item.items.size} 项")
                        if (item.tags.isNotEmpty()) add("标签 ${item.tags.size} 个")
                    }
                    if (details.isNotEmpty()) append("（").append(details.joinToString("，")).append("）")
                }
                AiProposalKind.SCHEDULE -> {
                    append("[日程] ").append(item.title)
                    val details = buildList {
                        proposalScheduleRangeText(item, zoneId)?.let { add(it) }
                        if (item.location.isNotBlank()) add("地点 ${item.location}")
                        if (item.category.isNotBlank()) add("分类 ${item.category}")
                        proposalReminderText(item, zoneId)?.let { add("提醒 $it") }
                    }
                    if (details.isNotEmpty()) append("（").append(details.joinToString("，")).append("）")
                }
                // T28/#29 记忆条目摘要：类型 + 原文（原话不改写）
                AiProposalKind.MEMORY -> {
                    append("[记忆] ").append(item.content)
                    append("（").append(memoryTypeCn(item.memoryType)).append("）")
                }
            }
        }
    }
}

/** 目标类型的中文统称（T21/#22、T28/#29 扩展记忆）。 */
internal fun kindCn(kind: AiProposalKind): String = when (kind) {
    AiProposalKind.TASK -> "待办"
    AiProposalKind.NOTE -> "便签"
    AiProposalKind.SCHEDULE -> "日程"
    AiProposalKind.MEMORY -> "记忆"
}

/** 变更集涉及的字段名列表（摘要与预览共用）。 */
private fun changeFieldNames(changes: AiProposalChanges): List<String> = buildList {
    if (changes.title != null) add("标题")
    if (changes.note != null) add("备注")
    if (changes.priority != null) add("优先级")
    if (changes.category != null) add("分类")
    if (changes.clearDue) add("清空截止")
    if (changes.dueEpochDay != null || changes.dueAtMillis != null) add("截止")
    if (changes.completed != null) add("完成状态")
    if (changes.content != null) add("正文")
    if (changes.tags != null) add("标签")
    if (changes.items != null) add("清单")
    if (changes.startMillis != null || changes.endMillis != null) add("时间")
    if (changes.location != null) add("地点")
}

/**
 * 修改条目的字段预览（T21/#22，卡片与回执共用同一口径）：
 * 目标快照存在时逐字段给出「旧值 → 新值」（旧新相同则省略该行）；
 * 快照缺失（多候选未点选）时只列出将修改的字段名，不猜旧值。
 */
internal fun proposalChangePreviewLines(item: AiProposalItem, zoneId: ZoneId): List<String> {
    if (item.op != AiProposalOp.MODIFY) return emptyList()
    val changes = item.changes ?: return emptyList()
    val snap = item.targetRecord ?: return listOf(
        "将修改：" + changeFieldNames(changes).joinToString("、") + "（点选目标后展示旧值 → 新值）",
    )
    val lines = mutableListOf<String>()
    fun line(label: String, oldText: String, newText: String) {
        if (oldText != newText) lines += "$label：$oldText → $newText"
    }
    changes.title?.let { line("标题", snap.title, it) }
    changes.note?.let { line("备注", snap.note.ifBlank { "（无）" }, it) }
    changes.priority?.let { line("优先级", priorityCn(snap.priority), priorityCn(it)) }
    changes.category?.let { line("分类", snap.category.ifBlank { "（无）" }, it.ifBlank { "（无）" }) }
    if (changes.clearDue || changes.dueEpochDay != null || changes.dueAtMillis != null) {
        val newDue = if (changes.clearDue) {
            null to null
        } else {
            changes.dueEpochDay to changes.dueAtMillis
        }
        line("截止", targetDueText(snap.dueEpochDay, snap.dueAtMillis, zoneId), targetDueText(newDue.first, newDue.second, zoneId))
    }
    changes.completed?.let { newCompleted ->
        val oldText = when (snap.status) {
            "COMPLETED" -> "已完成"
            "CANCELLED" -> "已取消"
            else -> "进行中"
        }
        line("完成状态", oldText, if (newCompleted) "已完成" else "进行中")
    }
    changes.content?.let { line("正文", snap.content.ifBlank { "（无）" }, it) }
    changes.tags?.let { line("标签", snap.tags.joinToString("、").ifBlank { "（无）" }, it.joinToString("、").ifBlank { "（无）" }) }
    changes.items?.let {
        line("清单", "${snap.items.size} 项", "${it.size} 项")
        lines += "（便签清单将整体替换，勾选状态重置）"
    }
    if (changes.startMillis != null || changes.endMillis != null) {
        val newStart = changes.startMillis ?: snap.startMillis
        val newEnd = changes.endMillis ?: snap.endMillis
        line("时间", scheduleRangeText(snap.startMillis, snap.endMillis, zoneId).orEmpty(), scheduleRangeText(newStart, newEnd, zoneId).orEmpty())
    }
    changes.location?.let { line("地点", snap.location.ifBlank { "（无）" }, it.ifBlank { "（无）" }) }
    return lines
}

/** 截止的可读文本（快照/变更集通用）：时刻截止按时区换算；日期截止原样；无截止显示（无）。 */
private fun targetDueText(epochDay: Long?, dueAtMillis: Long?, zoneId: ZoneId): String = when {
    dueAtMillis != null ->
        Instant.ofEpochMilli(dueAtMillis).atZone(zoneId).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    epochDay != null -> LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ISO_LOCAL_DATE)
    else -> "（无）"
}

/** 删除条目的动作说明（T21/#22）：取消待办明确标注「取消」而非删除（GLOSSARY）。 */
internal fun proposalDeleteActionText(item: AiProposalItem): String = when {
    item.op != AiProposalOp.DELETE -> ""
    item.kind == AiProposalKind.TASK && item.deleteAction == AiProposalDeleteAction.CANCEL ->
        "取消待办：记录保留、其提醒停止，不是删除"
    else -> "删除：记录将移入回收站，可在回收站恢复"
}

/** 目标候选的区分性摘要（多候选点选时展示）：完成/取消状态、截止或时间范围、更新时间。 */
internal fun proposalTargetSummary(record: AiProposalTargetRecord, zoneId: ZoneId): String {
    val parts = mutableListOf<String>()
    when (record.status) {
        "COMPLETED" -> parts += "已完成"
        "CANCELLED" -> parts += "已取消"
    }
    when {
        record.dueAtMillis != null -> parts += "截止 " + targetDueText(null, record.dueAtMillis, zoneId)
        record.dueEpochDay != null -> parts += "截止 " + targetDueText(record.dueEpochDay, null, zoneId)
        record.startMillis != null && record.endMillis != null ->
            parts += scheduleRangeText(record.startMillis, record.endMillis, zoneId).orEmpty()
    }
    if (parts.isEmpty()) {
        parts += "更新于 " + Instant.ofEpochMilli(record.updatedAt).atZone(zoneId)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }
    return parts.joinToString("，")
}

/** 截止的可读文本：时刻截止按 [zoneId] 换算；日期截止原样（不补时刻）。 */
internal fun proposalDueText(item: AiProposalItem, zoneId: ZoneId): String? = when {
    item.dueAtMillis != null ->
        Instant.ofEpochMilli(item.dueAtMillis).atZone(zoneId).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    item.dueEpochDay != null ->
        LocalDate.ofEpochDay(item.dueEpochDay).format(DateTimeFormatter.ISO_LOCAL_DATE)
    else -> null
}

/**
 * 日程时间范围的可读文本（T20/#21，按创建时区换算）：
 * 同日显示为「2026-10-26 14:00 ～ 15:30」；跨日明确显示结束日期「2026-10-26 23:00 ～ 2026-10-27 01:00」。
 */
internal fun proposalScheduleRangeText(item: AiProposalItem, zoneId: ZoneId): String? =
    scheduleRangeText(item.startMillis, item.endMillis, zoneId)

/** 时间范围的可读文本（T21/#22 抽取：修改预览对旧/新起止共用同一格式）。 */
internal fun scheduleRangeText(startMillis: Long?, endMillis: Long?, zoneId: ZoneId): String? {
    val start = startMillis ?: return null
    val end = endMillis ?: return null
    val startZoned = Instant.ofEpochMilli(start).atZone(zoneId)
    val endZoned = Instant.ofEpochMilli(end).atZone(zoneId)
    val full = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    val endText = if (startZoned.toLocalDate() == endZoned.toLocalDate()) {
        endZoned.format(DateTimeFormatter.ofPattern("HH:mm"))
    } else {
        endZoned.format(full)
    }
    return "${startZoned.format(full)} ～ $endText"
}

/** 日程提醒的可读摘要（按创建时区换算）：相对提前量或独立时刻；无提醒返回 null。 */
internal fun proposalReminderText(item: AiProposalItem, zoneId: ZoneId): String? =
    item.reminders.takeIf { it.isNotEmpty() }?.joinToString("、") { spec ->
        when (spec.type) {
            AiProposalReminderType.RELATIVE -> "开始前${offsetMillisCn(spec.offsetMillis ?: 0L)}"
            AiProposalReminderType.ABSOLUTE ->
                spec.remindAtMillis?.let {
                    Instant.ofEpochMilli(it).atZone(zoneId).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                }.orEmpty()
        }
    }

/** 提前量毫秒数的中文可读文本：如 900000 → 「30分钟」、5400000 → 「1小时 30分钟」。 */
private fun offsetMillisCn(offsetMillis: Long): String {
    val totalMinutes = offsetMillis / 60_000
    val days = totalMinutes / (24 * 60)
    val hours = (totalMinutes % (24 * 60)) / 60
    val minutes = totalMinutes % 60
    return buildList {
        if (days > 0) add("${days}天")
        if (hours > 0) add("${hours}小时")
        if (minutes > 0 || (days == 0L && hours == 0L)) add("${minutes}分钟")
    }.joinToString(" ")
}

/** 记忆条目类型的中文统称（摘要与卡片共用）。 */
internal fun memoryTypeCn(memoryType: String): String = when (memoryType.uppercase(Locale.ROOT)) {
    "PREFERENCE" -> "偏好"
    else -> "事实"
}

internal fun priorityCn(priority: String): String = when (priority) {
    "LOW" -> "低"
    "HIGH" -> "高"
    else -> "普通"
}

private val SUPPORTED_PRIORITIES = setOf("LOW", "NORMAL", "HIGH")

/** 记忆条目展示摘要的最大长度（原文截断仅用于卡片标题，保存的原文不受影响）。 */
internal const val MEMORY_TITLE_MAX = 12

/** 记忆条目类型字符串 → 记忆类型（解析层已归一化为 PREFERENCE/FACT）。 */
internal fun memoryTypeOf(memoryType: String): MemoryType = when (memoryType.uppercase(Locale.ROOT)) {
    "PREFERENCE" -> MemoryType.PREFERENCE
    else -> MemoryType.FACT
}

/** 供确认执行路径把条目换算为待办/便签输入（编码校验在解析层完成）。 */
internal fun AiProposalItem.toTaskInput(zoneId: ZoneId): TaskInput = TaskInput(
    title = title,
    note = note,
    priority = runCatching { TaskPriority.valueOf(priority) }.getOrDefault(TaskPriority.NORMAL),
    category = category,
    dueEpochDay = dueEpochDay,
    dueAtMillis = dueAtMillis,
    dueZoneId = zoneId.id,
)

internal fun AiProposalItem.toNoteInput(): NoteInput = NoteInput(
    title = title,
    content = content,
    tags = tags,
    items = items.map { NoteListItemInput(text = it) },
)

/** 供确认执行路径把记忆条目换算为记忆输入（T28/#29）：原文原样，类型按条目字段。 */
internal fun AiProposalItem.toMemoryInput(): MemoryInput = MemoryInput(
    content = content,
    type = memoryTypeOf(memoryType),
)

/** 供确认执行路径把日程条目换算为日历创建输入（编码校验在解析层与 [validateProposalItem] 完成）。 */
internal fun AiProposalItem.toCalendarInput(zoneId: ZoneId): CalendarInput = CalendarInput(
    title = title,
    startMillis = startMillis ?: 0L,
    endMillis = endMillis ?: 0L,
    timezoneId = zoneId.id,
    location = location,
    note = note,
    category = category,
)

/** 供确认执行路径把日程附属提醒规格换算为 CalendarReminderUseCases 的设置输入。 */
internal fun AiProposalItem.toReminderInputs(): List<ReminderInput> = reminders.map { spec ->
    when (spec.type) {
        AiProposalReminderType.RELATIVE -> ReminderInput(relativeOffsetMillis = spec.offsetMillis)
        AiProposalReminderType.ABSOLUTE -> ReminderInput(remindAtMillis = spec.remindAtMillis)
    }
}

/** 提醒规格的防御校验（解析层已保证；确认执行前按真实入口语义再验一次）。 */
internal fun reminderSpecProblem(spec: AiProposalReminderSpec, startMillis: Long?): String? = when {
    spec.offsetMillis != null && spec.remindAtMillis != null -> "提醒的相对提前量与独立时刻只能选择其一"
    spec.type == AiProposalReminderType.RELATIVE && (spec.offsetMillis == null || spec.offsetMillis <= 0) ->
        "相对提醒提前量非法（需为提前于日程开始的正毫秒数）"
    spec.type == AiProposalReminderType.ABSOLUTE && spec.remindAtMillis == null -> "独立时刻提醒缺少提醒时间"
    spec.type == AiProposalReminderType.ABSOLUTE && startMillis != null && spec.remindAtMillis!! < startMillis ->
        "独立时刻提醒早于日程开始时间"
    else -> null
}

/** 确认执行前的整批防御校验（解析层已保证；此处按真实用例入口语义再验一次）。 */
internal fun validateProposalItem(item: AiProposalItem): String? = when (item.op) {
    AiProposalOp.CREATE -> when {
        item.title.isBlank() -> "标题为空"
        item.kind == AiProposalKind.TASK && item.dueEpochDay != null && item.dueAtMillis != null ->
            "同时存在日期截止与时刻截止"
        item.kind == AiProposalKind.NOTE && item.content.isBlank() && item.items.all { it.isBlank() } ->
            "便签缺少正文或清单项"
        item.kind == AiProposalKind.SCHEDULE && (item.startMillis == null || item.endMillis == null) ->
            "日程缺少开始或结束时间"
        item.kind == AiProposalKind.SCHEDULE && item.endMillis!! < item.startMillis!! ->
            "结束时间早于开始时间"
        item.kind == AiProposalKind.MEMORY && item.content.isBlank() -> "记忆内容为空"
        item.reminders.isNotEmpty() &&
            item.reminders.firstNotNullOfOrNull { reminderSpecProblem(it, item.startMillis) } != null ->
            "提醒字段非法：" + item.reminders.firstNotNullOfOrNull { reminderSpecProblem(it, item.startMillis) }
        else -> null
    }
    // T21/#22：修改条目防御校验（目标线索、变更集、日程成对时间）
    AiProposalOp.MODIFY -> when {
        item.targetTitle.isBlank() -> "修改提案缺少目标线索"
        item.changes == null -> "修改提案没有变更内容"
        item.kind == AiProposalKind.SCHEDULE &&
            (item.changes.startMillis == null) != (item.changes.endMillis == null) ->
            "日程修改时间必须同时给出开始与结束"
        item.kind == AiProposalKind.SCHEDULE &&
            item.changes.startMillis != null && item.changes.endMillis != null &&
            item.changes.endMillis < item.changes.startMillis -> "结束时间早于开始时间"
        item.changes.title != null && item.changes.title.isBlank() -> "变更标题为空白"
        else -> null
    }
    // T21/#22：删除条目防御校验（目标线索、动作合法性）
    AiProposalOp.DELETE -> when {
        item.targetTitle.isBlank() -> "删除提案缺少目标线索"
        item.kind == AiProposalKind.TASK && item.deleteAction == null -> "待办删除缺少动作（取消或删除）"
        item.kind != AiProposalKind.TASK && item.deleteAction == AiProposalDeleteAction.CANCEL ->
            "${kindCn(item.kind)}没有取消语义"
        else -> null
    }
}
