package com.zhideer.app.data.db

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.zhideer.app.domain.model.CalendarEntry

/**
 * 日程表。T06/#7 起新增可空外键 [taskId]（指向 tasks.id，删除置空）：
 * 通过稳定标识关联待办，不保存标题文本副本；一个日程最多关联一个待办。
 */
@Entity(
    tableName = "calendar_entries",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("taskId")],
)
data class CalendarEntryEntity(
    @PrimaryKey val id: String,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val timezoneId: String,
    val location: String,
    val note: String,
    val category: String,
    val taskId: String?,
    val trashedAt: Long?,
    val zoneConfirmedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

fun CalendarEntryEntity.toModel() = CalendarEntry(
    id = id,
    title = title,
    startMillis = startMillis,
    endMillis = endMillis,
    timezoneId = timezoneId,
    location = location,
    note = note,
    category = category,
    taskId = taskId,
    trashedAt = trashedAt,
    zoneConfirmedAt = zoneConfirmedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun CalendarEntry.toEntity() = CalendarEntryEntity(
    id = id,
    title = title,
    startMillis = startMillis,
    endMillis = endMillis,
    timezoneId = timezoneId,
    location = location,
    note = note,
    category = category,
    taskId = taskId,
    trashedAt = trashedAt,
    zoneConfirmedAt = zoneConfirmedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
