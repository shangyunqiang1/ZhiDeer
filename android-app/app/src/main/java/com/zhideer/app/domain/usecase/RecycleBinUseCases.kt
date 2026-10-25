package com.zhideer.app.domain.usecase

import com.zhideer.app.domain.model.CalendarEntry
import com.zhideer.app.domain.model.Note
import com.zhideer.app.domain.model.SubTask
import com.zhideer.app.domain.model.TodoTask
import com.zhideer.app.domain.repository.CalendarRepository
import com.zhideer.app.domain.repository.NotesRepository
import com.zhideer.app.domain.repository.SubTasksRepository
import com.zhideer.app.domain.repository.TaskOccurrenceRepository
import com.zhideer.app.domain.repository.TasksRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlin.coroutines.CoroutineContext

/**
 * 回收站条目类型（T16/#17，GLOSSARY：回收站）：四类普通事项。
 * 记忆与隐私数据不属于回收站范畴，不套用三十天保留规则。
 */
enum class RecycleBinKind(val label: String) {
    TASK("待办"),
    SCHEDULE("日程"),
    NOTE("便签"),
    SUB_TASK("子任务"),
}

/**
 * 回收站条目展示模型：原记录的关键信息与删除时间。
 * [title] 为原记录标题（便签无标题时取正文首行）；[subtitle] 为补充摘要。
 */
data class RecycleBinItem(
    val kind: RecycleBinKind,
    val id: String,
    val title: String,
    val subtitle: String,
    val trashedAt: Long,
)

/**
 * 恢复结果：除业务恢复本身外，向界面提供如实提示（不假装恢复）。
 * - [restoredSubTaskCount]：随父待办同批恢复的子任务数（仅恢复待办时可能大于 0）；
 * - [restoredWithParent]：恢复子任务时其父待办也在回收站，已随父整体恢复；
 * - [message]：用户可读的完整提示（关联已解除、提醒需重新设置等）。
 */
data class RecycleBinRestoreResult(
    val kind: RecycleBinKind,
    val id: String,
    val title: String,
    val restoredSubTaskCount: Int = 0,
    val restoredWithParent: Boolean = false,
    val message: String,
)

/** 到期清理结果：各类被彻底删除的条数，供界面或日志核对。 */
data class RecycleBinPurgeResult(
    val purgedTasks: Int = 0,
    val purgedEntries: Int = 0,
    val purgedNotes: Int = 0,
    val purgedSubTasks: Int = 0,
) {
    val total: Int get() = purgedTasks + purgedEntries + purgedNotes + purgedSubTasks
}

/**
 * 统一回收站用例入口（T16/#17，GLOSSARY：回收站）：查看、恢复、彻底删除与三十天到期清理。
 *
 * 查看与过滤边界：
 * - [observeAll] 合并四类普通事项的回收站记录，按删除时间倒序；仅回收站界面使用，
 *   绝不进入普通查询、搜索或 AI 数据源（各仓库正常查询本就排除回收站记录）；
 *
 * 恢复策略（issue #17 要求先明确，语义在 RecycleBinUseCasesTest 固定）：
 * - 父待办恢复时同批恢复子任务（trashedAt 相同），父子整体还原；
 * - 恢复子任务时若父待办也在回收站，随父待办整体恢复，避免出现父在回收站而子任务在外的分裂状态；
 * - 已解除的日程关联【默认不自动重连】：普通删除时 unlinkTask 已把日程侧 taskId 置空且无
 *   原关联痕迹，自动重连可能复连到已删除或已改期的日程（保守优先）；恢复结果明确提示
 *   「原日程关联已解除，可重新关联」，重连由用户在日程界面显式完成；
 * - 提醒已随普通删除停止（取消闹钟并清除记录），恢复时不复活、不补造提醒记录；
 *   错过的提醒（恢复后重新设置但时刻已过去）只提示不补响——不向系统调度，仅展示层标记过期；
 *   未来提醒按当前权限状态处理（未就绪保持标记），该行为由既有 ReminderUseCases.applySchedule 承载；
 * - 恢复不触碰出现完成记录（重复待办的历史完成保留）；
 * - 未在回收站中的记录恢复为幂等无操作。
 *
 * 彻底删除：
 * - 物理删除该记录及其全部从属数据（子任务/清单项/提醒/出现完成记录/关联残留），
 *   逐表显式硬删除，不依赖外键级联是否启用；
 * - 删除待办保留其曾关联的日程记录本身（与普通删除语义一致），仅解除关联残留；
 * - 仅作用于四类普通事项：记忆与隐私数据不进回收站、不套用三十天规则（#17）；
 *   但来源被彻底删除时经 [onSourcePurged] 级联清理引用它的长期记忆（#28），
 *   记忆自身无保留期，不在此三十天范畴内。
 *
 * 三十天到期清理：
 * - [purgeExpired] 删除 trashedAt <= cutoffMillis 的回收站记录；
 *   三十天 = trashedAt + [RETENTION_MILLIS]（固定毫秒，绝对时间判定，与时区无关）；
 * - 调度入口采用应用启动懒执行（AppContainer/ZhiDeerApplication 触发一次，
 *   不引入 WorkManager 依赖）；测试注入固定时钟构造 cutoff。
 */
class RecycleBinUseCases(
    private val tasksRepository: TasksRepository,
    private val subTasksRepository: SubTasksRepository,
    private val calendarRepository: CalendarRepository,
    private val notesRepository: NotesRepository,
    private val taskOccurrenceRepository: TaskOccurrenceRepository,
    // 彻底删除/恢复联动：停止提醒（取消闹钟并清除记录）由既有用例承载，幂等
    private val reminderUseCases: ReminderUseCases,
    private val calendarReminderUseCases: CalendarReminderUseCases,
    // 彻底删除待办时的关联残留清理（日程记录保留，taskId 置空）
    private val taskScheduleLinkUseCases: TaskScheduleLinkUseCases,
    private val now: () -> Long,
    private val ioDispatcher: CoroutineContext = Dispatchers.IO,
    // T27/#28 纯新增：来源恢复联动钩子，默认无操作。恢复待办/日程/便签成功后触发，
    // AppContainer 注册为 MemoryUseCases::clearSourceMissing——清除关联记忆的
    // 来源缺失标记，但【不自动启用】记忆（恢复来源不自动启用，issue #28）。不改既有语义。
    private val onSourceRestored: suspend (kind: RecycleBinKind, sourceId: String) -> Unit = { _, _ -> },
    // T27/#28 纯新增：来源彻底删除联动钩子，默认无操作。purge 与三十天到期清理
    // 成功彻底删除待办/日程/便签后触发，AppContainer 注册为 MemoryUseCases::purgeBySource
    // ——物理删除关联记忆并清理检索入口（issue #28：清理关联记忆和检索索引）。
    // 记忆自身不进回收站，不在此三十天保留范畴内（只随来源彻底删除级联清理）。不改既有语义。
    private val onSourcePurged: suspend (kind: RecycleBinKind, sourceId: String) -> Unit = { _, _ -> },
) {

    // ---------- 查看 ----------

    /**
     * 观察回收站全部条目：四类合并、按删除时间倒序（同刻按类型顺序稳定）。
     * 仅回收站界面使用；正常查询、搜索与 AI 数据源均不经过本入口。
     */
    fun observeAll(): Flow<List<RecycleBinItem>> = combine(
        tasksRepository.observeTrashedTasks(),
        calendarRepository.observeTrashedEntries(),
        notesRepository.observeTrashedNotes(),
        subTasksRepository.observeTrashedSubTasks(),
    ) { tasks, entries, notes, subTasks ->
        (tasks.map { it.toItem() } + entries.map { it.toItem() } +
            notes.map { it.toItem() } + subTasks.map { it.toItem() })
            .sortedWith(compareByDescending<RecycleBinItem> { it.trashedAt }.thenBy { it.kind.ordinal })
    }.flowOn(ioDispatcher)

    private fun TodoTask.toItem() = RecycleBinItem(
        kind = RecycleBinKind.TASK,
        id = id,
        title = title,
        subtitle = note,
        trashedAt = trashedAt ?: 0L,
    )

    private fun CalendarEntry.toItem() = RecycleBinItem(
        kind = RecycleBinKind.SCHEDULE,
        id = id,
        title = title,
        subtitle = note,
        trashedAt = trashedAt ?: 0L,
    )

    private fun Note.toItem() = RecycleBinItem(
        kind = RecycleBinKind.NOTE,
        id = id,
        title = title.ifBlank { content.lineSequence().firstOrNull()?.take(TITLE_FALLBACK_MAX) ?: "（无标题便签）" },
        subtitle = content.take(SUBTITLE_MAX),
        trashedAt = trashedAt ?: 0L,
    )

    private fun SubTask.toItem() = RecycleBinItem(
        kind = RecycleBinKind.SUB_TASK,
        id = id,
        title = title,
        subtitle = "",
        trashedAt = trashedAt ?: 0L,
    )

    // ---------- 恢复 ----------

    /**
     * 恢复待办：清除回收站标记并同批恢复子任务（trashedAt 相同者）。
     * 不重连日程关联、不复活提醒；恢复结果携带如实提示。
     */
    suspend fun restoreTask(taskId: String): Result<RecycleBinRestoreResult> {
        val task = tasksRepository.getTask(taskId)
            ?: return Result.failure(IllegalStateException("待办不存在或已永久删除"))
        val trashedAt = task.trashedAt
            ?: return Result.success(idempotentRestore(taskId, RecycleBinKind.TASK, task.title))
        val t = now()
        tasksRepository.upsertTask(task.copy(trashedAt = null, updatedAt = t))
        val before = subTasksRepository.listByParent(taskId).size
        subTasksRepository.restoreForParent(taskId, trashedAt, t)
        val restoredSubTasks = subTasksRepository.listByParent(taskId).size - before
        onSourceRestored(RecycleBinKind.TASK, taskId) // #28：清除关联记忆的来源缺失标记（不自动启用）
        val message = buildString {
            append("已恢复「${task.title}」")
            if (restoredSubTasks > 0) append("，同批恢复 $restoredSubTasks 个子任务")
            append("。原日程关联已解除，可在日程中重新关联。")
            append("提醒已随删除停止，如需请重新设置；错过的提醒只提示不补响。")
        }
        return Result.success(
            RecycleBinRestoreResult(
                kind = RecycleBinKind.TASK,
                id = taskId,
                title = task.title,
                restoredSubTaskCount = restoredSubTasks,
                message = message,
            ),
        )
    }

    /** 恢复日程：清除回收站标记。原日程提醒已随删除停止，不复活。 */
    suspend fun restoreEntry(entryId: String): Result<RecycleBinRestoreResult> {
        val entry = calendarRepository.getEntry(entryId)
            ?: return Result.failure(IllegalStateException("日程不存在或已永久删除"))
        if (entry.trashedAt == null) {
            return Result.success(idempotentRestore(entryId, RecycleBinKind.SCHEDULE, entry.title))
        }
        calendarRepository.upsertEntry(entry.copy(trashedAt = null, updatedAt = now()))
        onSourceRestored(RecycleBinKind.SCHEDULE, entryId) // #28：清除关联记忆的来源缺失标记（不自动启用）
        return Result.success(
            RecycleBinRestoreResult(
                kind = RecycleBinKind.SCHEDULE,
                id = entryId,
                title = entry.title,
                message = "已恢复「${entry.title}」。提醒已随删除停止，如需请重新设置；错过的提醒只提示不补响。",
            ),
        )
    }

    /** 恢复便签：清除回收站标记，清单项原样还原。 */
    suspend fun restoreNote(noteId: String): Result<RecycleBinRestoreResult> {
        val note = notesRepository.getNote(noteId)
            ?: return Result.failure(IllegalStateException("便签不存在或已永久删除"))
        if (note.trashedAt == null) {
            return Result.success(idempotentRestore(noteId, RecycleBinKind.NOTE, note.title))
        }
        notesRepository.upsertNote(note.copy(trashedAt = null, updatedAt = now()))
        onSourceRestored(RecycleBinKind.NOTE, noteId) // #28：清除关联记忆的来源缺失标记（不自动启用）
        return Result.success(
            RecycleBinRestoreResult(
                kind = RecycleBinKind.NOTE,
                id = noteId,
                title = note.title,
                message = "已恢复「${note.title.ifBlank { "（无标题便签）" }}」。",
            ),
        )
    }

    /**
     * 恢复子任务：父待办正常时单独恢复；父待办也在回收站时随父整体恢复（父子一致）。
     */
    suspend fun restoreSubTask(subTaskId: String): Result<RecycleBinRestoreResult> {
        val subTask = subTasksRepository.get(subTaskId)
            ?: return Result.failure(IllegalStateException("子任务不存在"))
        if (subTask.trashedAt == null) {
            return Result.success(idempotentRestore(subTaskId, RecycleBinKind.SUB_TASK, subTask.title))
        }
        val parent = tasksRepository.getTask(subTask.parentId)
        if (parent != null && parent.trashedAt != null) {
            val parentResult = restoreTask(parent.id).getOrThrow()
            return Result.success(
                RecycleBinRestoreResult(
                    kind = RecycleBinKind.SUB_TASK,
                    id = subTaskId,
                    title = subTask.title,
                    restoredSubTaskCount = parentResult.restoredSubTaskCount,
                    restoredWithParent = true,
                    message = "已随父待办「${parent.title}」整体恢复${parentResult.restoredSubTaskCount}个子任务。" +
                        "原日程关联已解除，可在日程中重新关联。",
                ),
            )
        }
        subTasksRepository.upsert(subTask.copy(trashedAt = null, updatedAt = now()))
        return Result.success(
            RecycleBinRestoreResult(
                kind = RecycleBinKind.SUB_TASK,
                id = subTaskId,
                title = subTask.title,
                message = "已恢复子任务「${subTask.title}」。",
            ),
        )
    }

    private fun idempotentRestore(id: String, kind: RecycleBinKind, title: String) =
        RecycleBinRestoreResult(
            kind = kind,
            id = id,
            title = title,
            message = "该记录不在回收站中，无需恢复。",
        )

    // ---------- 彻底删除 ----------

    /**
     * 彻底删除待办：级联物理删除其子任务、提醒、出现完成记录，并解除日程关联残留；
     * 曾关联的日程记录本身保留（与普通删除语义一致）。
     */
    suspend fun purgeTask(taskId: String): Result<Unit> {
        tasksRepository.getTask(taskId)
            ?: return Result.failure(IllegalStateException("待办不存在或已永久删除"))
        reminderUseCases.cancelReminders(taskId) // 防御：取消可能残留的闹钟并清除提醒记录
        taskScheduleLinkUseCases.unlinkTask(taskId) // 关联残留：日程保留，关联解除
        onSourcePurged(RecycleBinKind.TASK, taskId) // #28：来源彻底删除，清理关联记忆与检索入口
        taskOccurrenceRepository.purgeByTask(taskId)
        subTasksRepository.purgeByParent(taskId)
        tasksRepository.purgeTask(taskId)
        return Result.success(Unit)
    }

    /** 彻底删除日程：先停止其提醒（取消闹钟并清除记录），再物理删除日程行。 */
    suspend fun purgeEntry(entryId: String): Result<Unit> {
        calendarRepository.getEntry(entryId)
            ?: return Result.failure(IllegalStateException("日程不存在或已永久删除"))
        calendarReminderUseCases.cancelReminders(entryId)
        onSourcePurged(RecycleBinKind.SCHEDULE, entryId) // #28：来源彻底删除，清理关联记忆与检索入口
        calendarRepository.purgeEntry(entryId)
        return Result.success(Unit)
    }

    /** 彻底删除便签：物理删除该便签及其全部清单项。 */
    suspend fun purgeNote(noteId: String): Result<Unit> {
        notesRepository.getNote(noteId)
            ?: return Result.failure(IllegalStateException("便签不存在或已永久删除"))
        onSourcePurged(RecycleBinKind.NOTE, noteId) // #28：来源彻底删除，清理关联记忆与检索入口
        notesRepository.purgeNote(noteId)
        return Result.success(Unit)
    }

    /** 彻底删除子任务：物理删除该行，不影响父待办与其余子任务。 */
    suspend fun purgeSubTask(subTaskId: String): Result<Unit> {
        subTasksRepository.get(subTaskId)
            ?: return Result.failure(IllegalStateException("子任务不存在"))
        subTasksRepository.purge(subTaskId)
        return Result.success(Unit)
    }

    // ---------- 三十天到期清理 ----------

    /**
     * 清理删除时间早于等于 [cutoffMillis] 的全部回收站记录（三十天到期：cutoff = 当前时刻 - 30 天）。
     * 先清子任务与日程/便签，再清待办（其残留回收站子任务随 purgeByParent 一并清理）；
     * 快照中个别条目已被并发清理时跳过，不影响其余条目。幂等：重复调用无额外动作。
     */
    suspend fun purgeExpired(cutoffMillis: Long): RecycleBinPurgeResult {
        var purgedTasks = 0
        var purgedEntries = 0
        var purgedNotes = 0
        var purgedSubTasks = 0
        // 子任务先行：父待办随后彻底删除时会清空其全部子任务，避免重复计数
        subTasksRepository.listTrashedSubTasks()
            .filter { it.trashedAt != null && it.trashedAt <= cutoffMillis }
            .forEach { sub ->
                if (subTasksRepository.purgeIfExists(sub.id)) purgedSubTasks++
            }
        tasksRepository.listTrashedTasks()
            .filter { it.trashedAt != null && it.trashedAt <= cutoffMillis }
            .forEach { task ->
                if (tasksRepository.purgeIfExists(task.id)) {
                    purgedTasks++
                    onSourcePurged(RecycleBinKind.TASK, task.id) // #28：来源到期清理，级联清理关联记忆
                }
            }
        calendarRepository.listTrashedEntries()
            .filter { it.trashedAt != null && it.trashedAt <= cutoffMillis }
            .forEach { entry ->
                if (calendarRepository.purgeIfExists(entry.id)) {
                    purgedEntries++
                    onSourcePurged(RecycleBinKind.SCHEDULE, entry.id) // #28：同上
                }
            }
        notesRepository.listTrashedNotes()
            .filter { it.trashedAt != null && it.trashedAt <= cutoffMillis }
            .forEach { note ->
                if (notesRepository.purgeIfExists(note.id)) {
                    purgedNotes++
                    onSourcePurged(RecycleBinKind.NOTE, note.id) // #28：同上
                }
            }
        return RecycleBinPurgeResult(
            purgedTasks = purgedTasks,
            purgedEntries = purgedEntries,
            purgedNotes = purgedNotes,
            purgedSubTasks = purgedSubTasks,
        )
    }

    companion object {
        /** 普通事项回收站保留期：三十天（固定毫秒，绝对时间判定，与时区无关）。 */
        const val RETENTION_MILLIS: Long = 30L * 24 * 60 * 60 * 1000

        private const val TITLE_FALLBACK_MAX = 20
        private const val SUBTITLE_MAX = 60
    }
}

/**
 * 幂等彻底删除辅助：记录存在才物理删除，返回是否发生删除。
 * 供到期清理在快照与实际状态可能不一致（并发清理）时准确计数。
 */
private suspend fun TasksRepository.purgeIfExists(id: String): Boolean {
    if (getTask(id) == null) return false
    purgeTask(id)
    return true
}

private suspend fun SubTasksRepository.purgeIfExists(id: String): Boolean {
    if (get(id) == null) return false
    purge(id)
    return true
}

private suspend fun CalendarRepository.purgeIfExists(id: String): Boolean {
    if (getEntry(id) == null) return false
    purgeEntry(id)
    return true
}

private suspend fun NotesRepository.purgeIfExists(id: String): Boolean {
    if (getNote(id) == null) return false
    purgeNote(id)
    return true
}
