package com.zhideer.app.domain.usecase

import com.zhideer.app.domain.model.AiProposalKind
import com.zhideer.app.domain.model.AiProposalTargetRecord
import com.zhideer.app.domain.model.TaskStatus
import com.zhideer.app.domain.repository.CalendarRepository
import com.zhideer.app.domain.repository.NotesRepository
import com.zhideer.app.domain.repository.TasksRepository
import kotlinx.coroutines.flow.first

/**
 * 目标匹配结果（T21/#22）：修改/删除提案按定位线索在本地记录中的匹配结局。
 * - [Unique]：唯一候选，可直连绑定（不是猜测：匹配空间里只有这一条）；
 * - [Multiple]：多条候选（≤ 上限），内嵌提案条目由用户在卡片中点选，绝不静默猜测；
 * - [TooMany]：候选超过上限（指代过于模糊），调用方追问更具体的名称；
 * - [NotFound]：没有匹配记录，调用方中文追问，绝不编造。
 */
sealed interface AiProposalTargetMatch {
    data class Unique(val record: AiProposalTargetRecord) : AiProposalTargetMatch

    data class Multiple(val candidates: List<AiProposalTargetRecord>) : AiProposalTargetMatch

    data class TooMany(val count: Int) : AiProposalTargetMatch

    data object NotFound : AiProposalTargetMatch
}

/**
 * 修改/删除提案的目标匹配器（T21/#22）：
 * 从真实仓库只读匹配提案 [AiProposalItem.targetTitle] 指向的本地记录。
 *
 * 设计边界：
 * - 只读：绝不写任何业务数据；候选是记录的只读快照副本（含 updatedAt 一致性指纹）；
 * - 回收站绝不参与（GLOSSARY：回收站记录可恢复但不再是正常查询对象）；
 * - 待办只匹配未进回收站的记录（含已完成/已取消：删除已完成待办是合法诉求；
 *   取消已取消的记录是幂等无害操作）；
 * - 授权门不在本类：调用方（AiProposalUseCases）必须先经 AiContextAuthorizer 校验
 *   目标类别已授权（#19 体系）才调用本类，未授权绝不读取任何仓库；
 * - 不复用 [RepositoryAiContextProvider] 的快照：那是为了注入请求而裁剪的窗口视图
 *   （未来七天 + 条数上限 + 不含 id），修改目标完全可能落在窗口之外；
 *   匹配需要全量正常记录与稳定 id，两者用途不同，授权语义同源。
 *
 * 匹配规则：标题包含匹配（线索是标题的子串，忽略大小写；精确相等是其特例，天然包含在内）。
 * 全部命中恰好一条 → Unique（匹配空间里只有这一条，直连不是猜测）；
 * 命中多条 → 即使存在与线索完全相等的标题也列出全部候选：只要存在相似记录，
 * 自动绑定任何一条都可能覆盖用户真正想改的记录（模糊指代先澄清）；候选超过
 * [MAX_CANDIDATES] 判为 TooMany（指代过于模糊，先澄清再执行）。
 */
class AiProposalTargetMatcher(
    private val tasksRepository: TasksRepository,
    private val notesRepository: NotesRepository,
    private val calendarRepository: CalendarRepository,
) {

    /** 按线索匹配目标记录。clue 为空视为未提供线索（解析层已保证非空，此处防御）。 */
    suspend fun match(kind: AiProposalKind, clue: String): AiProposalTargetMatch {
        val key = clue.trim()
        if (key.isEmpty()) return AiProposalTargetMatch.NotFound
        val pool = normalRecords(kind)
        val hits = pool.filter { it.title.contains(key, ignoreCase = true) }
        return when {
            hits.isEmpty() -> AiProposalTargetMatch.NotFound
            hits.size == 1 -> AiProposalTargetMatch.Unique(hits.single())
            hits.size > MAX_CANDIDATES -> AiProposalTargetMatch.TooMany(hits.size)
            else -> AiProposalTargetMatch.Multiple(hits)
        }
    }

    /** 按 id 读取单条目标记录的最新快照（点选目标时刷新，含 updatedAt 一致性指纹）。 */
    suspend fun loadRecord(kind: AiProposalKind, recordId: String): AiProposalTargetRecord? = when (kind) {
        AiProposalKind.TASK -> tasksRepository.getTask(recordId)?.takeIf { it.trashedAt == null }?.toTargetRecord()
        AiProposalKind.NOTE -> notesRepository.getNote(recordId)?.takeIf { it.trashedAt == null }?.toTargetRecord()
        AiProposalKind.SCHEDULE -> calendarRepository.getEntry(recordId)?.takeIf { it.trashedAt == null }?.toTargetRecord()
        // T28/#29：记忆条目只支持新增提案，不参与目标定位（穷举防御分支，实际不可达）
        AiProposalKind.MEMORY -> null
    }

    /** 正常记录池：排除回收站；待办不按状态过滤（完成/取消的记录也可被修改或删除）。 */
    private suspend fun normalRecords(kind: AiProposalKind): List<AiProposalTargetRecord> = when (kind) {
        AiProposalKind.TASK ->
            tasksRepository.observeTasks().first().filter { it.trashedAt == null }.map { it.toTargetRecord() }
        AiProposalKind.NOTE ->
            notesRepository.observeNotes().first().filter { it.trashedAt == null }.map { it.toTargetRecord() }
        AiProposalKind.SCHEDULE ->
            calendarRepository.observeEntries().first().filter { it.trashedAt == null }.map { it.toTargetRecord() }
        // T28/#29：记忆条目只支持新增提案，不参与目标定位（穷举防御分支，实际不可达）
        AiProposalKind.MEMORY -> emptyList()
    }

    private fun com.zhideer.app.domain.model.TodoTask.toTargetRecord() = AiProposalTargetRecord(
        recordId = id,
        title = title,
        updatedAt = updatedAt,
        note = note,
        priority = priority.name,
        category = category,
        dueEpochDay = dueEpochDay,
        dueAtMillis = dueAtMillis,
        status = status.name,
    )

    private fun com.zhideer.app.domain.model.Note.toTargetRecord() = AiProposalTargetRecord(
        recordId = id,
        title = title,
        updatedAt = updatedAt,
        content = content,
        tags = tags,
        items = items.map { it.text },
    )

    private fun com.zhideer.app.domain.model.CalendarEntry.toTargetRecord() = AiProposalTargetRecord(
        recordId = id,
        title = title,
        updatedAt = updatedAt,
        startMillis = startMillis,
        endMillis = endMillis,
        location = location,
        timezoneId = timezoneId,
        status = TaskStatus.ACTIVE.name,
    )

    private companion object {
        /** 候选展示上限：超过说明指代过于模糊，先追问再执行，绝不把一长串候选丢给用户。 */
        const val MAX_CANDIDATES = 5
    }
}
