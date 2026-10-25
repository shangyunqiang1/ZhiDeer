package com.zhideer.app.data.db

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.zhideer.app.domain.model.SubTask

/**
 * 子任务表：一对多属于待办。
 * 外键级联只作用于父行被永久删除的场景；回收站删除（trashedAt）保留行与父子关系，
 * 供后续恢复交互完整还原。
 */
@Entity(
    tableName = "sub_tasks",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("parentId")],
)
data class SubTaskEntity(
    @PrimaryKey val id: String,
    val parentId: String,
    val title: String,
    val done: Boolean,
    val doneAt: Long?,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val trashedAt: Long?,
)

fun SubTaskEntity.toModel() = SubTask(
    id = id,
    parentId = parentId,
    title = title,
    done = done,
    doneAt = doneAt,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt,
    trashedAt = trashedAt,
)

fun SubTask.toEntity() = SubTaskEntity(
    id = id,
    parentId = parentId,
    title = title,
    done = done,
    doneAt = doneAt,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt,
    trashedAt = trashedAt,
)
