package com.zhideer.app.domain.model

/**
 * 重复待办某一次出现的记录（T12/#13；T14/#15 主键改造支持单次延期）。
 * 主键是稳定 [id]（UUID）：同一 (taskId, originalEpochDay) 至多一行，
 * 重复写入同一行幂等，重开应用或重复计算不产生重复记录。
 *
 * - [originalEpochDay]：几何出现日——按重复规则与创建锚点派生的原出现日；
 * - [effectiveEpochDay]：实际生效日，默认等于 [originalEpochDay]；
 *   单次延期把它移到目标日（原行原位改生效日，不新增行），
 *   与目标日的常规出现行是不同行，「同日出现两次」由此天然表达（issue #15）。
 *   单次延期不改变重复规则几何——其余出现日全部不变。
 *
 * 每一次出现分别记录完成或未完成（GLOSSARY），完成时间戳独立；
 * 例外日由 [RecurrenceRule.exceptions] 排除，不改写出现记录。
 */
data class TaskOccurrence(
    val id: String,
    val taskId: String,
    val originalEpochDay: Long,
    val effectiveEpochDay: Long,
    val completed: Boolean,
    val completedAt: Long? = null,
    val updatedAt: Long = 0,
) {
    /** 该次出现是否被单次延期过（生效日 ≠ 原始出现日）。 */
    val postponed: Boolean get() = originalEpochDay != effectiveEpochDay
}
