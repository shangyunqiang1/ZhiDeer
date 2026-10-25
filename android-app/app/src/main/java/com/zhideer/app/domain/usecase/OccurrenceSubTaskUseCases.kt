package com.zhideer.app.domain.usecase

import com.zhideer.app.domain.model.OccurrenceSubTaskRecord
import com.zhideer.app.domain.model.SubTask
import com.zhideer.app.domain.model.TaskOccurrence
import com.zhideer.app.domain.model.TaskStatus
import com.zhideer.app.domain.repository.OccurrenceSubTaskRecordsRepository
import com.zhideer.app.domain.repository.SubTasksRepository
import com.zhideer.app.domain.repository.TaskOccurrenceRepository
import com.zhideer.app.domain.repository.TasksRepository
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** 一次出现中某个步骤的展示视图：模板标题 + 该次出现的勾选状态。 */
data class OccurrenceStepView(
    val subTaskId: String,
    val title: String,
    val done: Boolean,
    val doneAt: Long? = null,
)

/** 某次出现的步骤清单快照：进度 n/m 随 [steps] 推进（done 为已勾选数，total 为清单大小）。 */
data class OccurrenceStepsSnapshot(val steps: List<OccurrenceStepView>) {
    val total: Int get() = steps.size
    val doneCount: Int get() = steps.count { it.done }
}

/** 出现维度步骤进度 n/m。 */
data class OccurrenceStepProgress(val total: Int, val done: Int)

/**
 * 统一「重复执行子任务步骤」用例入口（T15/#16）：重复待办的每一次出现可按
 * 父待办的子任务步骤清单（sub_tasks 模板）执行，勾选状态按出现分别记录
 * （GLOSSARY：重复待办每一次分别记录；子任务与父待办分别确认完成）。
 *
 * - 出现维度独立：完成 3 月 1 日出现的「步骤 A」不影响 3 月 2 日出现的同一步骤；
 *   新出现初始未完成、不沿用上次勾选状态（无记录即未勾选）；
 * - 步骤清单来自父待办的子任务模板，出现维度只记录勾选：父待办增删改模板后，
 *   未来出现按当前模板展示，已记录的历史出现行级不变（模板删除后历史记录保留只读）；
 * - 零联动：全部步骤完成不自动完成该次出现、更不自动完成父待办；
 *   完成/撤销该次出现也不改写步骤记录（TaskUseCases/RecurrenceUseCases 同样不联动本用例）；
 * - 与出现语义对齐（T12/#13、T14/#15）：例外日与被单次延期走的原日不能执行步骤；
 *   单次延期改写出现行生效日不改行 id，步骤记录随出现行走；
 *   跳过恢复后步骤记录以出现行存在为准（既有行与记录保留，可继续执行）；
 * - 步骤记录挂在出现行（[TaskOccurrence] 稳定 id）上：首次对某次出现勾选步骤时
 *   先建立未完成的出现行（completed=false，不动完成时间戳），此后记录以该行为准。
 */
class OccurrenceSubTaskUseCases(
    private val tasksRepository: TasksRepository,
    private val occurrenceRepository: TaskOccurrenceRepository,
    private val recordsRepository: OccurrenceSubTaskRecordsRepository,
    private val subTasksRepository: SubTasksRepository,
    private val now: () -> Long,
    private val idGen: () -> String = { UUID.randomUUID().toString() },
) {

    /**
     * 完成/撤销某次出现的某个步骤：只写出现维度的勾选记录，绝不触碰
     * 该次出现的完成状态与父待办状态。
     * [occurrenceEpochDay] 为出现生效日、[originalEpochDay] 为原始出现日
     * （与 [RecurrenceUseCases.observeForDay] 的出现视图对齐；同日两次时以此区分
     * 常规出现与延期入行）。重复提交同一勾选幂等（复用同一行，不刷新时间戳）。
     */
    suspend fun toggleStep(
        taskId: String,
        occurrenceEpochDay: Long,
        subTaskId: String,
        done: Boolean,
        originalEpochDay: Long = occurrenceEpochDay,
    ): Result<OccurrenceSubTaskRecord> {
        val anchor = resolveAnchor(taskId, occurrenceEpochDay, originalEpochDay)
        if (anchor.isFailure) return Result.failure(anchor.exceptionOrNull()!!)
        val row = anchor.getOrNull() ?: ensureUnfinishedRow(taskId, occurrenceEpochDay)
        val sub = subTasksRepository.get(subTaskId)
            ?: return Result.failure(IllegalArgumentException("子任务步骤不存在"))
        if (sub.parentId != taskId || sub.trashedAt != null) {
            return Result.failure(IllegalArgumentException("该步骤不属于此待办或已在父待办中删除"))
        }
        val existing = recordsRepository.listByOccurrence(row.id).firstOrNull { it.subTaskId == subTaskId }
        if (existing != null && existing.done == done) return Result.success(existing)
        val t = now()
        val record = existing?.copy(
            done = done,
            doneAt = if (done) t else null,
            updatedAt = t,
        ) ?: OccurrenceSubTaskRecord(
            id = idGen(),
            taskId = taskId,
            occurrenceId = row.id,
            subTaskId = subTaskId,
            done = done,
            doneAt = if (done) t else null,
            updatedAt = t,
        )
        recordsRepository.upsert(record)
        return Result.success(record)
    }

    /**
     * 观察某次出现的步骤清单与勾选状态（供步骤面板展示）。
     * 步骤清单 = 当前有效模板（按 sortOrder 稳定排序）∪ 出现上有记录但模板已删除的步骤；
     * 无记录的步骤一律展示为未完成。出现无效（非重复待办、例外日、被延期走的原日、
     * 非出现日）时返回空快照。
     */
    fun observeForOccurrence(
        taskId: String,
        occurrenceEpochDay: Long,
        originalEpochDay: Long = occurrenceEpochDay,
    ): Flow<OccurrenceStepsSnapshot> =
        combine(
            tasksRepository.observeTasks(),
            occurrenceRepository.observeAll(),
            subTasksRepository.observeByParent(taskId),
            recordsRepository.observeAll(),
        ) { tasks, occurrences, templates, records ->
            val task = tasks.firstOrNull { it.id == taskId }
                ?: return@combine OccurrenceStepsSnapshot(emptyList())
            val anchor = resolveAnchor(taskId, occurrenceEpochDay, originalEpochDay)
            if (anchor.isFailure) return@combine OccurrenceStepsSnapshot(emptyList())
            val row = anchor.getOrNull()
            val steps = composeSteps(
                templates = templates,
                records = records.filter { it.occurrenceId == row?.id },
            )
            OccurrenceStepsSnapshot(steps)
        }

    /**
     * 查询某次出现的步骤进度 n/m；出现无效时返回失败（中文说明）。
     * 尚无记录的出现（未交互过）按模板清单给出 0/m。
     */
    suspend fun progressForOccurrence(
        taskId: String,
        occurrenceEpochDay: Long,
        originalEpochDay: Long = occurrenceEpochDay,
    ): Result<OccurrenceStepProgress> {
        val anchor = resolveAnchor(taskId, occurrenceEpochDay, originalEpochDay)
        if (anchor.isFailure) return Result.failure(anchor.exceptionOrNull()!!)
        val row = anchor.getOrNull()
        val records = row?.let { recordsRepository.listByOccurrence(it.id) } ?: emptyList()
        val steps = composeSteps(
            templates = subTasksRepository.listByParent(taskId),
            records = records,
        )
        return Result.success(OccurrenceStepProgress(total = steps.size, done = steps.count { it.done }))
    }

    /**
     * 锚定某次出现的记录行（与 [RecurrenceUseCases] 的出现语义对齐）：
     * 返回该次出现已存在的出现行；有效但尚未交互（无行）时返回 null（成功），
     * 由调用方决定是否建立行（勾选步骤时建立，纯观察不建立）。
     * 例外日、被单次延期走的原日、非出现日、回收站/取消/非重复待办一律失败并给出中文说明。
     */
    private suspend fun resolveAnchor(
        taskId: String,
        occurrenceEpochDay: Long,
        originalEpochDay: Long,
    ): Result<TaskOccurrence?> {
        val task = tasksRepository.getTask(taskId)
            ?: return Result.failure(IllegalStateException("待办不存在或已永久删除"))
        if (task.trashedAt != null) {
            return Result.failure(IllegalStateException("待办已进入回收站"))
        }
        if (task.status == TaskStatus.CANCELLED) {
            return Result.failure(IllegalStateException("待办已取消，不再记录各次步骤"))
        }
        val rule = task.recurrenceRule
            ?: return Result.failure(IllegalStateException("该待办不是重复待办"))
        if (originalEpochDay in rule.exceptions) {
            return Result.failure(IllegalArgumentException("该出现日已设为例外，不再出现，无法执行步骤"))
        }
        val target = occurrenceRepository.listByTask(taskId)
            .firstOrNull { it.originalEpochDay == originalEpochDay }
        if (target != null) {
            // 原日出现被延期走（original==生效日入参但行已移走），或延期行被再次延期导致引用过期
            val movedAway = (originalEpochDay == occurrenceEpochDay && target.postponed) ||
                (originalEpochDay != occurrenceEpochDay && target.effectiveEpochDay != occurrenceEpochDay)
            if (movedAway) {
                return Result.failure(
                    IllegalArgumentException(
                        "该次出现已单次延期到 ${LocalDate.ofEpochDay(target.effectiveEpochDay)}",
                    ),
                )
            }
            return Result.success(target)
        }
        // 无行：只可能是尚未交互过的常规几何出现（延期入行在延期时已建立）
        if (originalEpochDay == occurrenceEpochDay &&
            rule.occursOn(RecurrenceUseCases.anchorEpochDay(task), occurrenceEpochDay)
        ) {
            return Result.success(null)
        }
        return Result.failure(IllegalArgumentException("该日期不属于重复计划"))
    }

    /** 为尚未交互的常规几何出现建立未完成出现行（completed=false，不动完成时间戳）。 */
    private suspend fun ensureUnfinishedRow(taskId: String, occurrenceEpochDay: Long): TaskOccurrence {
        val row = TaskOccurrence(
            id = idGen(),
            taskId = taskId,
            originalEpochDay = occurrenceEpochDay,
            effectiveEpochDay = occurrenceEpochDay,
            completed = false,
            completedAt = null,
            updatedAt = now(),
        )
        occurrenceRepository.upsert(row)
        return row
    }

    /**
     * 合成某次出现的步骤展示清单：当前有效模板在前（稳定排序），后接模板已删除
     * （进入回收站或不存在）但出现上有记录的步骤——历史记录行级不变、仍可核对。
     */
    private suspend fun composeSteps(
        templates: List<SubTask>,
        records: List<OccurrenceSubTaskRecord>,
    ): List<OccurrenceStepView> {
        val bySubTaskId = records.associateBy { it.subTaskId }
        val current = templates.map { template ->
            val record = bySubTaskId[template.id]
            OccurrenceStepView(
                subTaskId = template.id,
                title = template.title,
                done = record?.done ?: false,
                doneAt = record?.doneAt,
            )
        }
        val knownIds = templates.map { it.id }.toSet()
        val legacy = records.filter { it.subTaskId !in knownIds }.map { record ->
            val template = subTasksRepository.get(record.subTaskId)
            OccurrenceStepView(
                subTaskId = record.subTaskId,
                title = template?.title ?: "已删除的步骤",
                done = record.done,
                doneAt = record.doneAt,
            )
        }
        return current + legacy
    }
}
