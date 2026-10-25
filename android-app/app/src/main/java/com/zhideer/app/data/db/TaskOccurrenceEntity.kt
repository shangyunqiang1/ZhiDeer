package com.zhideer.app.data.db

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import com.zhideer.app.domain.model.TaskOccurrence

/**
 * 重复待办出现记录（T12/#13；T14/#15 主键改造）：主键为稳定 id（UUID），
 * 每一次出现分别记录完成状态与完成时间戳（GLOSSARY：重复待办每一次分别记录）。
 * 同一 (taskId, originalEpochDay) 至多一行，重复写入幂等。
 * - originalEpochDay：几何出现日（规则与创建锚点派生）；
 * - effectiveEpochDay：实际生效日（默认 = original；单次延期移到目标日，
 *   与目标日常规出现行不同行，同日两次由此表达，issue #15）。
 * 外键级联只作用于待办行被永久删除的场景；取消/回收站由用例与查询语义处理。
 */
@Entity(
    tableName = "task_occurrences",
    primaryKeys = ["id"],
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("taskId"), Index("effectiveEpochDay")],
)
data class TaskOccurrenceEntity(
    val id: String,
    val taskId: String,
    val originalEpochDay: Long,
    val effectiveEpochDay: Long,
    val completed: Boolean,
    val completedAt: Long?,
    val updatedAt: Long,
)

fun TaskOccurrenceEntity.toModel() = TaskOccurrence(
    id = id,
    taskId = taskId,
    originalEpochDay = originalEpochDay,
    effectiveEpochDay = effectiveEpochDay,
    completed = completed,
    completedAt = completedAt,
    updatedAt = updatedAt,
)

fun TaskOccurrence.toEntity() = TaskOccurrenceEntity(
    id = id,
    taskId = taskId,
    originalEpochDay = originalEpochDay,
    effectiveEpochDay = effectiveEpochDay,
    completed = completed,
    completedAt = completedAt,
    updatedAt = updatedAt,
)
