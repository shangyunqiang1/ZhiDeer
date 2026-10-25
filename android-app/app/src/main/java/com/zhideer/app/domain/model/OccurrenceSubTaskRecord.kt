package com.zhideer.app.domain.model

/**
 * 重复待办某一次出现中某个子任务步骤的执行记录（T15/#16）。
 * 步骤清单来自父待办的子任务模板（[SubTask]），勾选状态按出现分别记录：
 * 完成 3 月 1 日出现的「步骤 A」不影响 3 月 2 日出现的同一步骤，
 * 新出现不沿用上次勾选状态（无记录即未完成）。
 *
 * - [occurrenceId]：所属出现（[TaskOccurrence] 稳定 id）。步骤记录以出现行存在为准，
 *   单次延期改写出现行生效日不改 id，步骤记录随出现行走；
 * - [subTaskId]：引用父待办的子任务模板。模板后续增删改不改写本记录——
 *   模板删除（回收站）后已记录的历史出现仍保留行级数据供核对；
 * - 唯一约束 (occurrenceId, subTaskId)：同一次出现的同一步骤至多一行，重复写入幂等；
 * - 全部步骤完成不改变该次出现的完成状态，也不改变父待办状态（分别确认，互不联动）。
 */
data class OccurrenceSubTaskRecord(
    val id: String,
    val taskId: String,
    val occurrenceId: String,
    val subTaskId: String,
    val done: Boolean = false,
    val doneAt: Long? = null,
    val updatedAt: Long = 0,
)
