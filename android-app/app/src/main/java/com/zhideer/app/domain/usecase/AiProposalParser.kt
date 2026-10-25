package com.zhideer.app.domain.usecase

import com.zhideer.app.domain.model.AiProposalItem
import com.zhideer.app.domain.model.AiProposalKind
import com.zhideer.app.domain.model.AiProposalReminderSpec
import com.zhideer.app.domain.model.AiProposalReminderType
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
    appendLine("当用户明确想新增待办、便签或日程时，在回复的最末尾输出一个 ```json 代码块起草操作提案；提案只是供用户核对的草案，用户确认后才会真正保存。")
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
            "\"reminder\":{\"type\":\"relative\",\"offsetMillis\":900000}}" +
            "]}",
    )
    appendLine("```")
    appendLine("字段规则：")
    appendLine("1. kind 只能是 \"task\"（待办）、\"note\"（便签）或 \"schedule\"（日程）；title 必填且不能为空。")
    appendLine("2. task 可选字段：note（备注）、priority（LOW/NORMAL/HIGH，默认 NORMAL）、category（分类）、dueDate 或 dueAt。")
    appendLine("3. dueDate 是日期截止（yyyy-MM-dd，不含具体时刻）；dueAt 是具体时刻截止（yyyy-MM-dd'T'HH:mm）。两者只能选其一，绝不同时给出；日期截止不要补 23:59 之类的时刻。")
    appendLine("4. 用户提到的时间不明确（如\"尽快\"或说不清具体哪天）时，先用文字向用户追问，确认后再输出 JSON；绝不编造日期。")
    appendLine("5. note 可选字段：content（便签正文，保留用户原话，不改写不扩写）、tags（标签数组）、items（清单项文本数组）。")
    appendLine("6. schedule 字段：start 与 end 必填（本地时间 yyyy-MM-dd'T'HH:mm；结束可跨日，直接写次日的日期与时刻）；可选 location（地点）、note（备注）、category（分类）。日程是占用一段时间，用户只说了一个时间点而没说明结束时间或时长时，先用文字追问，绝不编造结束时间。task/note 绝不携带 start/end/reminder/reminders 字段，schedule 绝不携带 dueDate/dueAt 字段。")
    appendLine("7. schedule 的提醒只在用户明确要求时给出，用户没要求提醒时绝不添加提醒字段：reminder 为单个提醒对象 {\"type\":\"relative\",\"offsetMillis\":毫秒数}（相对日程开始提前，如提前 15 分钟为 900000）或 {\"type\":\"absolute\",\"remindAt\":\"yyyy-MM-dd'T'HH:mm\"}（独立时刻，不得早于日程开始；用户要提前提醒时优先换算为相对提前量）；相对提前量与独立时刻只能选其一；多个提醒用 reminders 数组（元素与 reminder 相同）。")
    appendLine("8. 一次可起草多项（proposals 数组多项）。用户没有创建意图时正常对话，绝不输出任何 JSON 代码块。")
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
        val n = index + 1
        val kind = when (dto.kind?.trim()?.lowercase(Locale.ROOT)) {
            "task" -> AiProposalKind.TASK
            "note" -> AiProposalKind.NOTE
            "schedule" -> AiProposalKind.SCHEDULE
            else -> return AiProposalParseOutcome.Invalid("第 $n 项提案的类型无法识别（kind 只能是 task、note 或 schedule）。")
        }
        val title = dto.title?.trim().orEmpty()
        if (title.isEmpty()) {
            return AiProposalParseOutcome.Invalid("第 $n 项提案的标题为空：标题不能为空。")
        }
        val priority = dto.priority?.trim()?.uppercase(Locale.ROOT).takeUnless { it.isNullOrEmpty() } ?: "NORMAL"
        if (priority !in SUPPORTED_PRIORITIES) {
            return AiProposalParseOutcome.Invalid("第 $n 项提案的优先级无法识别（只支持 LOW/NORMAL/HIGH）。")
        }
        val dueDateRaw = dto.dueDate?.trim().takeUnless { it.isNullOrEmpty() }
        val dueAtRaw = dto.dueAt?.trim().takeUnless { it.isNullOrEmpty() }
        if (dueDateRaw != null && dueAtRaw != null) {
            return AiProposalParseOutcome.Invalid("第 $n 项提案同时给出日期截止与时刻截止：两种截止只能选择其一。")
        }
        val dueEpochDay = dueDateRaw?.let {
            try {
                LocalDate.parse(it).toEpochDay()
            } catch (e: DateTimeParseException) {
                return AiProposalParseOutcome.Invalid("第 $n 项提案的截止日期格式无法识别（应为 yyyy-MM-dd）。")
            }
        }
        val dueAtMillis = dueAtRaw?.let {
            try {
                LocalDateTime.parse(it).atZone(zoneId).toInstant().toEpochMilli()
            } catch (e: DateTimeParseException) {
                return AiProposalParseOutcome.Invalid("第 $n 项提案的时刻截止格式无法识别（应为 yyyy-MM-ddTHH:mm）。")
            }
        }
        if (kind == AiProposalKind.SCHEDULE) {
            // T20/#21 日程条目：start/end 必填并按创建时区换算，提醒作为附属字段解析校验
            if (dueDateRaw != null || dueAtRaw != null) {
                return AiProposalParseOutcome.Invalid("第 $n 项提案是日程类型，不应包含截止字段（dueDate/dueAt）；日程时间用 start/end 表示。")
            }
            val startRaw = dto.start?.trim().takeUnless { it.isNullOrEmpty() }
                ?: return AiProposalParseOutcome.Invalid("第 $n 项提案缺少开始时间：日程需要明确的开始时间（yyyy-MM-ddTHH:mm）。")
            val endRaw = dto.end?.trim().takeUnless { it.isNullOrEmpty() }
                ?: return AiProposalParseOutcome.Invalid("第 $n 项提案缺少结束时间：单个时间点不能擅自推断持续时长，请先与用户确认结束时间或时长。")
            val startMillis = try {
                LocalDateTime.parse(startRaw).atZone(zoneId).toInstant().toEpochMilli()
            } catch (e: DateTimeParseException) {
                return AiProposalParseOutcome.Invalid("第 $n 项提案的开始时间格式无法识别（应为 yyyy-MM-ddTHH:mm）。")
            }
            val endMillis = try {
                LocalDateTime.parse(endRaw).atZone(zoneId).toInstant().toEpochMilli()
            } catch (e: DateTimeParseException) {
                return AiProposalParseOutcome.Invalid("第 $n 项提案的结束时间格式无法识别（应为 yyyy-MM-ddTHH:mm）。")
            }
            if (endMillis < startMillis) {
                return AiProposalParseOutcome.Invalid("第 $n 项提案的结束时间早于开始时间。")
            }
            val reminderSpecs = try {
                reminderSpecsOf(dto, n, startMillis, zoneId)
            } catch (e: ProposalFieldException) {
                return AiProposalParseOutcome.Invalid(e.message!!)
            }
            items += AiProposalItem(
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
        } else {
            // task/note 条目不得携带日程字段：模型混淆类型时整批拒绝并给出可诊断提示
            val hasScheduleTime = !dto.start?.trim().isNullOrEmpty() || !dto.end?.trim().isNullOrEmpty()
            val hasReminderField = dto.reminder != null || !dto.reminders.isNullOrEmpty()
            if (hasScheduleTime || hasReminderField) {
                val kindCn = if (kind == AiProposalKind.TASK) "待办" else "便签"
                return AiProposalParseOutcome.Invalid(
                    "第 $n 项提案是$kindCn 类型，不应包含日程时间或提醒字段（start/end/reminder/reminders）。",
                )
            }
            items += AiProposalItem(
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
    }
    return AiProposalParseOutcome.Drafts(items)
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
    append("已起草 ").append(items.size).append(" 项操作提案，请在下方卡片中核对、确认创建或忽略：")
    items.forEachIndexed { index, item ->
        append("\n").append(index + 1).append(". ")
        when (item.kind) {
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
        }
    }
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
internal fun proposalScheduleRangeText(item: AiProposalItem, zoneId: ZoneId): String? {
    val start = item.startMillis ?: return null
    val end = item.endMillis ?: return null
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

internal fun priorityCn(priority: String): String = when (priority) {
    "LOW" -> "低"
    "HIGH" -> "高"
    else -> "普通"
}

private val SUPPORTED_PRIORITIES = setOf("LOW", "NORMAL", "HIGH")

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
internal fun validateProposalItem(item: AiProposalItem): String? = when {
    item.title.isBlank() -> "标题为空"
    item.kind == AiProposalKind.TASK && item.dueEpochDay != null && item.dueAtMillis != null ->
        "同时存在日期截止与时刻截止"
    item.kind == AiProposalKind.NOTE && item.content.isBlank() && item.items.all { it.isBlank() } ->
        "便签缺少正文或清单项"
    item.kind == AiProposalKind.SCHEDULE && (item.startMillis == null || item.endMillis == null) ->
        "日程缺少开始或结束时间"
    item.kind == AiProposalKind.SCHEDULE && item.endMillis!! < item.startMillis!! ->
        "结束时间早于开始时间"
    item.reminders.isNotEmpty() &&
        item.reminders.firstNotNullOfOrNull { reminderSpecProblem(it, item.startMillis) } != null ->
        "提醒字段非法：" + item.reminders.firstNotNullOfOrNull { reminderSpecProblem(it, item.startMillis) }
    else -> null
}
