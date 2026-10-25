package com.zhideer.app.data.db

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import com.zhideer.app.domain.model.OccurrenceSubTaskRecord

/**
 * 出现维度子任务步骤记录表（T15/#16）：重复待办每一次出现的步骤勾选状态。
 * 步骤清单来自父待办的 sub_tasks 模板，本表只记录勾选，不复制模板内容——
 * 模板增删改不改写已记录的历史出现（行级不变），未来出现按当前模板展示。
 *
 * - 主键稳定 id（UUID）；
 * - 外键级联只作用于待办/出现行被永久删除的场景（出现记录随破坏性迁移重建时
 *   步骤记录一并重建）；取消/回收站由用例与查询语义处理；
 * - [subTaskId] 仅作模板引用、不加外键：模板进入回收站后历史记录仍须保留行级数据；
 * - 唯一索引 (occurrenceId, subTaskId)：同一次出现的同一步骤至多一行，幂等写入。
 */
@Entity(
    tableName = "occurrence_subtask_records",
    primaryKeys = ["id"],
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TaskOccurrenceEntity::class,
            parentColumns = ["id"],
            childColumns = ["occurrenceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("taskId"),
        Index("occurrenceId"),
        Index(value = ["occurrenceId", "subTaskId"], unique = true),
    ],
)
data class OccurrenceSubTaskRecordEntity(
    val id: String,
    val taskId: String,
    val occurrenceId: String,
    val subTaskId: String,
    val done: Boolean,
    val doneAt: Long?,
    val updatedAt: Long,
)

fun OccurrenceSubTaskRecordEntity.toModel() = OccurrenceSubTaskRecord(
    id = id,
    taskId = taskId,
    occurrenceId = occurrenceId,
    subTaskId = subTaskId,
    done = done,
    doneAt = doneAt,
    updatedAt = updatedAt,
)

fun OccurrenceSubTaskRecord.toEntity() = OccurrenceSubTaskRecordEntity(
    id = id,
    taskId = taskId,
    occurrenceId = occurrenceId,
    subTaskId = subTaskId,
    done = done,
    doneAt = doneAt,
    updatedAt = updatedAt,
)
