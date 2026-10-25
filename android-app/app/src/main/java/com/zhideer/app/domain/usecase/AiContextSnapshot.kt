package com.zhideer.app.domain.usecase

/**
 * 授权上下文快照模型（T18/#19）：
 * 生产实现从真实仓库只读生成（[RepositoryAiContextProvider]），测试用 Fake 注入；
 * 请求体组装（渲染为 system 结构化文本）在 provider 之外，由 [buildAiContextPrompt] 完成，保持可测。
 * 快照本身只注入请求、不落盘、不回写业务数据（本任务只读）。
 */

/** 快照中的一条待办记录（GLOSSARY：待办；截止时间不表示占用时段）。 */
data class AiContextTaskRecord(
    val title: String,
    val dueText: String,
    val stateLabel: String,
    val priorityLabel: String,
    val recurring: Boolean,
)

/** 快照中的一条日程记录（GLOSSARY：日程；占用一段时间）。 */
data class AiContextScheduleRecord(
    val title: String,
    val timeText: String,
    val location: String,
    val note: String,
)

/** 快照中的一条便签记录（GLOSSARY：便签；清单项独立于待办）。 */
data class AiContextNoteRecord(
    val title: String,
    val updatedText: String,
    val content: String,
    val itemsText: String,
)

/**
 * 快照中的一条长期记忆记录（T28/#29 纯新增；GLOSSARY：长期记忆）。
 * [content] 为记忆原文（用户确认保存的原话，不篡改不扩写）；[typeLabel] 为偏好/事实中文标签。
 */
data class AiContextMemoryRecord(
    val content: String,
    val typeLabel: String,
)

/**
 * 一次请求用的真实记录快照。各列表只含已授权类别的记录；
 * 已授权但无记录时对应列表为空（prompt 中如实说明"暂无记录"，不编造）。
 */
data class AiContextSnapshot(
    val generatedAtText: String,
    val tasks: List<AiContextTaskRecord> = emptyList(),
    val schedule: List<AiContextScheduleRecord> = emptyList(),
    val notes: List<AiContextNoteRecord> = emptyList(),
    // T28/#29 纯新增：记忆段（仅活动记忆，上限少量条数，原文不篡改）
    val memories: List<AiContextMemoryRecord> = emptyList(),
)

/** 上下文快照提供者：按授权类别只读生成快照；未授权类别绝不读取。 */
fun interface AiContextProvider {
    suspend fun loadSnapshot(grant: AiContextGrant): AiContextSnapshot
}

/**
 * 把快照渲染为注入请求的 system 结构化文本。
 * 安全属性：未授权类别即使出现在快照中也绝不渲染（请求体组装的最后一道门）；
 * 关闭类别时在对应节明确写"未授权"，引导模型如实说明限制（用户故事 64），
 * 并以固定规则要求模型不编造任何记录。
 */
internal fun buildAiContextPrompt(grant: AiContextGrant, snapshot: AiContextSnapshot): String = buildString {
    appendLine("以下是用户本次授权提供的本地真实记录快照（生成于 ${snapshot.generatedAtText}）。")
    appendLine("回答必须仅基于这些真实记录与对话内容。")

    appendLine()
    if (grant.contains(AiContextCategory.TASKS)) {
        appendLine("【待办】（${AiContextCategory.TASKS.scopeSummary}）")
        if (snapshot.tasks.isEmpty()) {
            appendLine("暂无待办记录。")
        } else {
            snapshot.tasks.forEach { task ->
                val recur = if (task.recurring) "，重复" else ""
                appendLine("- [${task.stateLabel}] ${task.title}（截止 ${task.dueText}，优先级 ${task.priorityLabel}$recur）")
            }
        }
    } else {
        appendLine("【待办】用户未授权该类别，本次未提供待办记录。")
    }

    appendLine()
    if (grant.contains(AiContextCategory.SCHEDULE)) {
        appendLine("【日程】（${AiContextCategory.SCHEDULE.scopeSummary}）")
        if (snapshot.schedule.isEmpty()) {
            appendLine("暂无日程记录。")
        } else {
            snapshot.schedule.forEach { entry ->
                val location = if (entry.location.isBlank()) "" else "（地点：${entry.location}）"
                appendLine("- ${entry.timeText} ${entry.title}$location")
                if (entry.note.isNotBlank()) appendLine("  备注：${entry.note}")
            }
        }
    } else {
        appendLine("【日程】用户未授权该类别，本次未提供日程记录。")
    }

    appendLine()
    if (grant.contains(AiContextCategory.NOTES)) {
        appendLine("【便签】（${AiContextCategory.NOTES.scopeSummary}）")
        if (snapshot.notes.isEmpty()) {
            appendLine("暂无便签记录。")
        } else {
            snapshot.notes.forEach { note ->
                appendLine("- ${note.title}（更新于 ${note.updatedText}）")
                if (note.content.isNotBlank()) appendLine("  正文：${note.content}")
                if (note.itemsText.isNotBlank()) appendLine("  清单项：${note.itemsText}")
            }
        }
    } else {
        appendLine("【便签】用户未授权该类别，本次未提供便签记录。")
    }

    appendLine()
    if (grant.contains(AiContextCategory.MEMORY)) {
        appendLine("【长期记忆】（${AiContextCategory.MEMORY.scopeSummary}）")
        if (snapshot.memories.isEmpty()) {
            appendLine("暂无记忆记录。")
        } else {
            appendLine("以下是用户此前明确要求记住并确认保存的长期记忆，回答涉及其偏好的问题时应遵守：")
            snapshot.memories.forEach { memory ->
                appendLine("- [${memory.typeLabel}] ${memory.content}")
            }
        }
    } else {
        appendLine("【长期记忆】用户未授权该类别，本次未提供记忆记录。")
    }

    appendLine()
    appendLine("【回答规则】")
    appendLine("1. 以上快照是本次请求可用的全部真实记录：快照中不存在的待办、日程、便签或记忆一律不存在，绝不编造任何记录、日期或结果。")
    appendLine("2. 用户询问了未授权类别时，如实说明当前无法查询该类别，建议在 AI 界面的「授权」面板中开启对应类别，或前往本地待办、日历、便签页面直接查看。")
    appendLine("3. 已授权类别没有记录时，如实说明当前没有相关记录，不要假设或补全。")
}
