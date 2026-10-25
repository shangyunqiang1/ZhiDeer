package com.zhideer.app.data.db

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import com.zhideer.app.domain.model.TaskPriority
import com.zhideer.app.domain.model.TaskStatus
import com.zhideer.app.domain.model.TodoTask

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val note: String,
    val priority: String,
    val category: String,
    val dueEpochDay: Long?,
    val dueAtMillis: Long?,
    val dueZoneId: String,
    val status: String,
    val trashedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
    val postponedAt: Long?,
    val cancelledAt: Long?,
)

fun TaskEntity.toModel() = TodoTask(
    id = id,
    title = title,
    note = note,
    priority = TaskPriority.entries.firstOrNull { it.name == priority } ?: TaskPriority.NORMAL,
    category = category,
    dueEpochDay = dueEpochDay,
    dueAtMillis = dueAtMillis,
    dueZoneId = dueZoneId,
    status = TaskStatus.entries.firstOrNull { it.name == status } ?: TaskStatus.ACTIVE,
    trashedAt = trashedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    completedAt = completedAt,
    postponedAt = postponedAt,
    cancelledAt = cancelledAt,
)

fun TodoTask.toEntity() = TaskEntity(
    id = id,
    title = title,
    note = note,
    priority = priority.name,
    category = category,
    dueEpochDay = dueEpochDay,
    dueAtMillis = dueAtMillis,
    dueZoneId = dueZoneId,
    status = status.name,
    trashedAt = trashedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    completedAt = completedAt,
    postponedAt = postponedAt,
    cancelledAt = cancelledAt,
)
