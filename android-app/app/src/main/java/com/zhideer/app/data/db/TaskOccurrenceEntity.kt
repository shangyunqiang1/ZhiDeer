package com.zhideer.app.data.db

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import com.zhideer.app.domain.model.TaskOccurrence

/**
 * 重复待办出现完成记录（T12/#13）：主键 (taskId, occurrenceEpochDay)，
 * 每一次出现分别记录完成状态与完成时间戳（GLOSSARY：重复待办每一次分别记录）。
 * 主键保证重复写入幂等——重开应用或重复计算不产生重复记录。
 * 外键级联只作用于待办行被永久删除的场景；取消/回收站由用例与查询语义处理。
 */
@Entity(
    tableName = "task_occurrences",
    primaryKeys = ["taskId", "occurrenceEpochDay"],
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("occurrenceEpochDay")],
)
data class TaskOccurrenceEntity(
    val taskId: String,
    val occurrenceEpochDay: Long,
    val completed: Boolean,
    val completedAt: Long?,
    val updatedAt: Long,
)

fun TaskOccurrenceEntity.toModel() = TaskOccurrence(
    taskId = taskId,
    occurrenceEpochDay = occurrenceEpochDay,
    completed = completed,
    completedAt = completedAt,
    updatedAt = updatedAt,
)

fun TaskOccurrence.toEntity() = TaskOccurrenceEntity(
    taskId = taskId,
    occurrenceEpochDay = occurrenceEpochDay,
    completed = completed,
    completedAt = completedAt,
    updatedAt = updatedAt,
)
