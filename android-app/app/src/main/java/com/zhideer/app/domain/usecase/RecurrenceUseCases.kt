package com.zhideer.app.domain.usecase

import com.zhideer.app.domain.model.RecurrenceRule
import com.zhideer.app.domain.model.TaskDue
import com.zhideer.app.domain.model.RecurrenceAnchors
import com.zhideer.app.domain.model.TaskDue.RecurringDueState
import com.zhideer.app.domain.model.TaskOccurrence
import com.zhideer.app.domain.model.TaskStatus
import com.zhideer.app.domain.model.TodoTask
import com.zhideer.app.domain.repository.TaskOccurrenceRepository
import com.zhideer.app.domain.repository.TasksRepository
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * 重复待办在某一日的出现视图：待办、生效日与该次完成状态。
 * [originalEpochDay] 区分常规出现（= 生效日）与单次延期而来（≠ 生效日，T14/#15）；
 * 同日两次时该日返回两条（各自独立完成状态）。
 */
data class RecurringTaskOccurrence(
    val task: TodoTask,
    val occurrenceEpochDay: Long,
    val completed: Boolean,
    val originalEpochDay: Long = occurrenceEpochDay,
) {
    /** 该次出现是否由单次延期移入当天。 */
    val postponed: Boolean get() = originalEpochDay != occurrenceEpochDay
}

/**
 * 统一重复待办用例入口（T12/#13；T14/#15 单次延期）。
 * GLOSSARY：重复待办按照固定日期规则反复出现，每一次分别记录完成或未完成，
 * 本次未完成不阻止下一次出现。出现锚点由待办 createdAt 在创建时锚定时区（dueZoneId）下
 * 派生（[anchorEpochDay]），不冗余存储、天然幂等。
 *
 * 出现记录以稳定 id 为主键（同一 (taskId, originalEpochDay) 至多一行），携带
 * originalEpochDay（几何出现日）与 effectiveEpochDay（实际生效日）：
 * - 完成等记录按生效日驱动列表/过滤（今天/逾期/即将到期）；
 * - 单次延期只改被延期那一行的生效日，不新增行、不改规则几何——
 *   原日不再出现，目标日与该日常规出现行并存（同日两次），其余日期不变（issue #15）。
 * 不实现“完成后再重复推进同一记录”的上游模式。
 */
class RecurrenceUseCases(
    private val tasksRepository: TasksRepository,
    private val occurrenceRepository: TaskOccurrenceRepository,
    private val now: () -> Long,
    // T14/#15 纯新增：新出现记录的稳定 id 生成器（默认 UUID）。
    private val occurrenceIdGen: () -> String = { UUID.randomUUID().toString() },
) {

    /** 为尚无规则的待办设置重复规则（规则须至少一个有效配置）。 */
    suspend fun create(taskId: String, rule: RecurrenceRule): Result<TodoTask> {
        RecurrenceRule.validate(rule)?.let { return Result.failure(IllegalArgumentException(it)) }
        val task = tasksRepository.getTask(taskId)
            ?: return Result.failure(IllegalStateException("待办不存在或已永久删除"))
        if (task.trashedAt != null) {
            return Result.failure(IllegalStateException("待办已进入回收站"))
        }
        if (task.recurrenceRule != null) {
            return Result.failure(IllegalStateException("该待办已设置重复规则，请使用编辑修改"))
        }
        val updated = task.copy(recurrenceRule = rule, updatedAt = now())
        tasksRepository.upsertTask(updated)
        return Result.success(updated)
    }

    /**
     * 修改既有重复规则（规则须至少一个有效配置；传 null 表示移除）。
     * T14/#15 语义：出现按当前规则与锚点即时派生，规则调整只影响以后的出现、不留旧调度；
     * 既有出现记录（含完成与单次延期行）一律不删除、不改写（过去记录不被重写，issue #15），
     * 例外日集合随传入规则保留。
     */
    suspend fun update(taskId: String, rule: RecurrenceRule?): Result<TodoTask> {
        RecurrenceRule.validate(rule)?.let { return Result.failure(IllegalArgumentException(it)) }
        val task = tasksRepository.getTask(taskId)
            ?: return Result.failure(IllegalStateException("待办不存在或已永久删除"))
        if (task.trashedAt != null) {
            return Result.failure(IllegalStateException("待办已进入回收站"))
        }
        val updated = task.copy(recurrenceRule = rule, updatedAt = now())
        tasksRepository.upsertTask(updated)
        return Result.success(updated)
    }

    /** 移除重复规则，待办保留为普通待办；各次记录保留供核对。 */
    suspend fun remove(taskId: String): Result<TodoTask> = update(taskId, null)

    /**
     * 规则在 [startDay, endDay] 内的全部几何出现日（按创建锚点与时区，升序）。
     * 这是“计划”视图：不含单次延期调整（延期行按生效日另行驱动列表与完成判定），
     * 供核对规则节奏与预览；取消/回收站待办的规则几何结果仍可读，但不再进入列表过滤。
     */
    suspend fun occurrencesForRange(taskId: String, startDay: Long, endDay: Long): Result<List<Long>> {
        val task = tasksRepository.getTask(taskId)
            ?: return Result.failure(IllegalStateException("待办不存在或已永久删除"))
        val rule = task.recurrenceRule
            ?: return Result.failure(IllegalStateException("该待办不是重复待办"))
        return Result.success(rule.occurrencesBetween(anchorEpochDay(task), startDay, endDay))
    }

    /**
     * 完成或撤销某一次出现：完成记录独立时间戳。
     * 完成作用于 [occurrenceEpochDay]（按生效日）当天的最早一次未完成出现——
     * 同日两次（如常规出现 + 延期而来）分别完成；重复调用同一出现幂等（稳定 id 主键，
     * 已完成时刷新完成时间戳但不产生新行）。
     * 仅 ACTIVE 的重复待办可记录；非出现日、例外日、被单次延期走的原日被拒绝并返回中文说明。
     */
    suspend fun completeOccurrence(
        taskId: String,
        occurrenceEpochDay: Long,
        completed: Boolean,
    ): Result<TaskOccurrence> {
        val task = tasksRepository.getTask(taskId)
            ?: return Result.failure(IllegalStateException("待办不存在或已永久删除"))
        if (task.trashedAt != null) {
            return Result.failure(IllegalStateException("待办已进入回收站"))
        }
        if (task.status == TaskStatus.CANCELLED) {
            return Result.failure(IllegalStateException("待办已取消，不再记录各次完成"))
        }
        val rule = task.recurrenceRule
            ?: return Result.failure(IllegalStateException("该待办不是重复待办"))
        if (occurrenceEpochDay in rule.exceptions) {
            // T14/#15：例外日与完成明确区分——被排除的出现日不再要求完成
            return Result.failure(IllegalArgumentException("该出现日已设为例外，不再要求完成"))
        }
        val rows = occurrenceRepository.listByTask(taskId)
        // T14/#15：单次延期后原日不再出现、不再要求完成，并说明被延期到了哪一天
        rows.firstOrNull { it.originalEpochDay == occurrenceEpochDay && it.postponed }
            ?.let {
                return Result.failure(
                    IllegalArgumentException("该次出现已单次延期到 ${LocalDate.ofEpochDay(it.effectiveEpochDay)}"),
                )
            }
        val isGeometric = rule.occursOn(anchorEpochDay(task), occurrenceEpochDay)
        // 延期入行：生效日为当天、原始日不同的记录（同日两次中的延期出现）
        val incomingRows = rows.filter {
            it.effectiveEpochDay == occurrenceEpochDay && it.originalEpochDay != occurrenceEpochDay
        }.sortedBy { it.originalEpochDay }
        val geometricRow = rows.firstOrNull {
            it.originalEpochDay == occurrenceEpochDay && it.effectiveEpochDay == occurrenceEpochDay
        }
        if (!isGeometric && incomingRows.isEmpty()) {
            return Result.failure(IllegalArgumentException("该日期不属于重复计划"))
        }
        val t = now()
        val row: TaskOccurrence = if (completed) {
            // 完成该日最早未完成的一次；全部完成时幂等刷新时间戳（不产生新行）
            val unfinished = incomingRows.firstOrNull { !it.completed }
                ?: geometricRow?.takeIf { !it.completed }
            when {
                unfinished != null -> unfinished.copy(completed = true, completedAt = t, updatedAt = t)
                geometricRow != null -> geometricRow.copy(completed = true, completedAt = t, updatedAt = t)
                isGeometric -> TaskOccurrence(
                    id = occurrenceIdGen(),
                    taskId = taskId,
                    originalEpochDay = occurrenceEpochDay,
                    effectiveEpochDay = occurrenceEpochDay,
                    completed = true,
                    completedAt = t,
                    updatedAt = t,
                )
                else -> incomingRows.first().copy(completed = true, completedAt = t, updatedAt = t)
            }
        } else {
            // 撤销该日最近完成的一次；无完成记录时幂等保持该日未完成行
            val latestDone = (incomingRows + listOfNotNull(geometricRow))
                .filter { it.completed }
                .maxByOrNull { it.completedAt ?: 0L }
            when {
                latestDone != null -> latestDone.copy(completed = false, completedAt = null, updatedAt = t)
                geometricRow != null -> geometricRow.copy(completed = false, completedAt = null, updatedAt = t)
                isGeometric -> TaskOccurrence(
                    id = occurrenceIdGen(),
                    taskId = taskId,
                    originalEpochDay = occurrenceEpochDay,
                    effectiveEpochDay = occurrenceEpochDay,
                    completed = false,
                    completedAt = null,
                    updatedAt = t,
                )
                else -> incomingRows.first().copy(completed = false, completedAt = null, updatedAt = t)
            }
        }
        occurrenceRepository.upsert(row)
        return Result.success(row)
    }

    /**
     * 单次延期某次出现（T14/#15，issue #15 验收第 3 条）：把 [occurrenceEpochDay]
     * 这一次的生效日移到 [toEpochDay]。
     * - 不改变原后续日期：只移动这一次，其余出现日全部按规则几何不变；
     * - 目标日已有常规出现时同日出现两次（几何出现行与延期行并存，分别完成）；
     * - 实现：原 (taskId, originalEpochDay) 行原位改写生效日，不新增行——
     *   同一出现重复提交延期幂等（同目标直接成功，改目标更新同一行）；
     * - 该日必须按规则几何（含锚点下限）是一次出现且非例外日；
     *   已完成的出现不能延期（过去完成记录不被改写）。
     */
    suspend fun postponeOccurrence(
        taskId: String,
        occurrenceEpochDay: Long,
        toEpochDay: Long,
    ): Result<TaskOccurrence> {
        val task = tasksRepository.getTask(taskId)
            ?: return Result.failure(IllegalStateException("待办不存在或已永久删除"))
        if (task.trashedAt != null) {
            return Result.failure(IllegalStateException("待办已进入回收站"))
        }
        if (task.status == TaskStatus.CANCELLED) {
            return Result.failure(IllegalStateException("待办已取消，不能延期各次出现"))
        }
        val rule = task.recurrenceRule
            ?: return Result.failure(IllegalStateException("该待办不是重复待办"))
        if (occurrenceEpochDay in rule.exceptions) {
            return Result.failure(IllegalArgumentException("该出现日已设为例外，不再出现，请先恢复例外"))
        }
        if (!rule.occursByPatternOn(anchorEpochDay(task), occurrenceEpochDay)) {
            return Result.failure(IllegalArgumentException("该日期不属于重复计划"))
        }
        if (toEpochDay == occurrenceEpochDay) {
            return Result.failure(IllegalArgumentException("目标日期与原日期相同，无需延期"))
        }
        val existing = occurrenceRepository.listByTask(taskId)
            .firstOrNull { it.originalEpochDay == occurrenceEpochDay }
        if (existing != null && existing.effectiveEpochDay == toEpochDay) {
            // 幂等：已是目标生效日，不重复写入
            return Result.success(existing)
        }
        if (existing?.completed == true) {
            return Result.failure(IllegalStateException("该次出现已完成，不再延期"))
        }
        val t = now()
        val row = existing?.copy(effectiveEpochDay = toEpochDay, updatedAt = t)
            ?: TaskOccurrence(
                id = occurrenceIdGen(),
                taskId = taskId,
                originalEpochDay = occurrenceEpochDay,
                effectiveEpochDay = toEpochDay,
                completed = false,
                completedAt = null,
                updatedAt = t,
            )
        occurrenceRepository.upsert(row)
        return Result.success(row)
    }

    /**
     * 单次跳过某次出现（T14/#15）：把该出现日加入规则的例外集合，仅本次不再出现、
     * 不再要求完成，不改变其余出现日，也不产生完成记录（跳过与完成区分）。
     * - 该日必须按规则几何（含锚点下限）是一次出现，否则拒绝；
     * - 该日任一出现（含延期行）已完成时拒绝（需先撤销完成），保证跳过与完成互斥；
     * - 已是例外时幂等成功，不重复写入（issue #15：重复提交不重复产生事项）；
     * - 例外只排除规则几何，不删除、不改写出现记录：被单次延期走的日子加例外
     *   不影响其延期行按生效日照常出现。
     */
    suspend fun skipOccurrence(taskId: String, occurrenceEpochDay: Long): Result<TodoTask> {
        val task = tasksRepository.getTask(taskId)
            ?: return Result.failure(IllegalStateException("待办不存在或已永久删除"))
        if (task.trashedAt != null) {
            return Result.failure(IllegalStateException("待办已进入回收站"))
        }
        if (task.status == TaskStatus.CANCELLED) {
            return Result.failure(IllegalStateException("待办已取消，不能跳过各次出现"))
        }
        val rule = task.recurrenceRule
            ?: return Result.failure(IllegalStateException("该待办不是重复待办"))
        if (!rule.occursByPatternOn(anchorEpochDay(task), occurrenceEpochDay)) {
            return Result.failure(IllegalArgumentException("该日期不属于重复计划"))
        }
        if (occurrenceRepository.listByTask(taskId).any {
                (it.originalEpochDay == occurrenceEpochDay || it.effectiveEpochDay == occurrenceEpochDay) &&
                    it.completed
            }
        ) {
            return Result.failure(IllegalStateException("该次出现已完成，请先撤销完成再跳过"))
        }
        if (occurrenceEpochDay in rule.exceptions) {
            return Result.success(task)
        }
        val updated = task.copy(
            recurrenceRule = rule.copy(exceptions = rule.exceptions + occurrenceEpochDay),
            updatedAt = now(),
        )
        tasksRepository.upsertTask(updated)
        return Result.success(updated)
    }

    /**
     * 恢复被跳过（例外）的出现日：从例外集合移除，该日重新出现且完成状态以既有记录为准
     * （既有出现记录一律不删除、不改写）。该日不是例外时幂等成功，不产生任何写入。
     */
    suspend fun restoreOccurrence(taskId: String, occurrenceEpochDay: Long): Result<TodoTask> {
        val task = tasksRepository.getTask(taskId)
            ?: return Result.failure(IllegalStateException("待办不存在或已永久删除"))
        if (task.trashedAt != null) {
            return Result.failure(IllegalStateException("待办已进入回收站"))
        }
        val rule = task.recurrenceRule
            ?: return Result.failure(IllegalStateException("该待办不是重复待办"))
        if (occurrenceEpochDay !in rule.exceptions) {
            return Result.success(task)
        }
        val updated = task.copy(
            recurrenceRule = rule.copy(exceptions = rule.exceptions - occurrenceEpochDay),
            updatedAt = now(),
        )
        tasksRepository.upsertTask(updated)
        return Result.success(updated)
    }

    /**
     * 观察某日在重复计划中出现的待办及其完成状态（排除取消与回收站）。
     * 同日两次（目标日既有常规出现又有延期而来）返回两条，分别携带完成状态；
     * 被单次延期走的原日不再返回。
     */
    fun observeForDay(day: Long): Flow<List<RecurringTaskOccurrence>> =
        combine(tasksRepository.observeTasks(), occurrenceRepository.observeAll()) { tasks, occurrences ->
            val rowsByTask = occurrences.groupBy { it.taskId }
            tasks.flatMap { task ->
                val rule = task.recurrenceRule ?: return@flatMap emptyList()
                if (task.trashedAt != null || task.status != TaskStatus.ACTIVE) return@flatMap emptyList()
                val rows = rowsByTask[task.id] ?: emptyList()
                val anchor = anchorEpochDay(task)
                val result = mutableListOf<RecurringTaskOccurrence>()
                // 常规几何出现：未被单次延期走
                if (rule.occursOn(anchor, day) && rows.none { it.originalEpochDay == day && it.postponed }) {
                    val geometricRow = rows.firstOrNull {
                        it.originalEpochDay == day && it.effectiveEpochDay == day
                    }
                    result += RecurringTaskOccurrence(
                        task = task,
                        occurrenceEpochDay = day,
                        completed = geometricRow?.completed == true,
                        originalEpochDay = day,
                    )
                }
                // 延期入行：生效日为当天、从别的日期延期而来
                rows.filter { it.effectiveEpochDay == day && it.postponed }.forEach { row ->
                    result += RecurringTaskOccurrence(
                        task = task,
                        occurrenceEpochDay = day,
                        completed = row.completed,
                        originalEpochDay = row.originalEpochDay,
                    )
                }
                result
            }
        }

    /** 观察某待办的各次出现记录（供详情核对，按原始出现日与生效日升序）。 */
    fun observeOccurrences(taskId: String): Flow<List<TaskOccurrence>> =
        occurrenceRepository.observeAll().map { rows ->
            rows.filter { it.taskId == taskId }
                .sortedWith(compareBy({ it.originalEpochDay }, { it.effectiveEpochDay }))
        }

    companion object {

        /** 出现锚点：createdAt 在创建时锚定时区（dueZoneId）下的日期。 */
        fun anchorEpochDay(task: TodoTask): Long = RecurrenceAnchors.anchorEpochDay(task)

        /**
         * 下一次未完成出现的生效日（T14/#15 行版）：按各次出现记录的生效日驱动。
         * 从锚点起最早一次尚未完成的出现在今天或过去（含错过）——含“无记录的常规出现”
         * 与“未完成的延期行”；全部已完/无错过时为今天之后最近一次有效出现
         * （常规几何出现未被延期走，或延期行生效日）。规则有效则恒有解。
         */
        fun nextUnfinishedDay(
            task: TodoTask,
            occurrences: List<TaskOccurrence>,
            todayEpochDay: Long,
        ): Long {
            val rule = task.recurrenceRule ?: error("非重复待办没有出现序列")
            val anchor = anchorEpochDay(task)
            val byOriginal = occurrences.associateBy { it.originalEpochDay }
            val postponedAwayDays = occurrences.filter { it.postponed }.map { it.originalEpochDay }.toSet()
            // 未完成记录的生效日集合（延期行走后的原日不在其中）
            val openEffectiveDays = occurrences.filter { !it.completed }
                .map { it.effectiveEpochDay }.toSortedSet()
            // 今天及之前：最早一次未完成的常规出现（无记录或未完成）
            val pastGeometric = rule.occurrencesBetween(anchor, anchor, todayEpochDay)
                .filter { it !in postponedAwayDays }
                .firstOrNull { day -> byOriginal[day]?.completed != true }
            val pastRowDay = openEffectiveDays.firstOrNull { it <= todayEpochDay }
            listOfNotNull(pastGeometric, pastRowDay).minOrNull()?.let { return it }
            // 未来：最早一次有效出现——常规几何出现（被延期走则顺延）与未完成延期行取早
            var geometric = rule.nextOnOrAfter(anchor, todayEpochDay + 1)
            while (byOriginal[geometric]?.let { it.postponed } == true) {
                geometric = rule.nextOnOrAfter(anchor, geometric + 1)
            }
            val futureRowDay = openEffectiveDays.firstOrNull { it > todayEpochDay }
            return if (futureRowDay == null) geometric else minOf(geometric, futureRowDay)
        }

        /**
         * 兼容入口（T12/#13 完成日集合版）：把完成日集合视为完成记录
         * （原始日 = 生效日 = 该日）后走行版判定。无单次延期时与旧行为完全一致。
         */
        fun nextUnfinishedDay(task: TodoTask, completedDays: Set<Long>, todayEpochDay: Long): Long =
            nextUnfinishedDay(
                task,
                completedDays.map { day ->
                    TaskOccurrence(
                        id = "",
                        taskId = task.id,
                        originalEpochDay = day,
                        effectiveEpochDay = day,
                        completed = true,
                    )
                },
                todayEpochDay,
            )

        /** 合成列表过滤所需的重复出现状态（T14/#15 行版）；非重复待办返回 null。 */
        fun recurringState(task: TodoTask, occurrences: List<TaskOccurrence>, nowMillis: Long): RecurringDueState? {
            if (task.recurrenceRule == null) return null
            val today = TaskDue.todayEpochDay(task.dueZoneId, nowMillis)
            return RecurringDueState(
                nextUnfinishedDay = nextUnfinishedDay(task, occurrences, today),
                todayEpochDay = today,
            )
        }

        /** 兼容入口（T12/#13 完成日集合版）；无单次延期时与旧行为完全一致。 */
        fun recurringState(task: TodoTask, completedDays: Set<Long>, nowMillis: Long): RecurringDueState? =
            recurringState(
                task,
                completedDays.map { day ->
                    TaskOccurrence(
                        id = "",
                        taskId = task.id,
                        originalEpochDay = day,
                        effectiveEpochDay = day,
                        completed = true,
                    )
                },
                nowMillis,
            )
    }
}
