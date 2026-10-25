package com.zhideer.app.data.db

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import com.zhideer.app.domain.model.CalendarEntry

@Entity(tableName = "calendar_entries")
data class CalendarEntryEntity(
    @PrimaryKey val id: String,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val timezoneId: String,
    val location: String,
    val note: String,
    val category: String,
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
    trashedAt = trashedAt,
    zoneConfirmedAt = zoneConfirmedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
