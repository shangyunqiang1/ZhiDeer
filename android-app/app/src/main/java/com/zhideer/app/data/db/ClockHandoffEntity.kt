package com.zhideer.app.data.db

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import com.zhideer.app.domain.model.ClockHandoff
import com.zhideer.app.domain.model.ClockHandoffStatus

/**
 * 系统闹钟交接记录表（T24/#25）。仅记录本应用发起的请求与待核对状态；
 * 不存储、也无法存储系统闹钟的实际内容（公开接口不可读）。
 */
@Entity(tableName = "clock_handoffs")
data class ClockHandoffEntity(
    @PrimaryKey val id: String,
    val hour: Int,
    val minute: Int,
    val label: String,
    val targetEpochDay: Long?,
    /** 星期规则，逗号分隔的 1..7；空串表示仅一次。 */
    val weekdays: String,
    val requestedAtMillis: Long,
    val status: String,
    val note: String,
)

fun ClockHandoffEntity.toModel() = ClockHandoff(
    id = id,
    hour = hour,
    minute = minute,
    label = label,
    targetEpochDay = targetEpochDay,
    weekdays = weekdays.split(',').filter { it.isNotBlank() }.map { it.trim().toInt() }.toSet(),
    requestedAtMillis = requestedAtMillis,
    status = ClockHandoffStatus.entries.firstOrNull { it.name == status } ?: ClockHandoffStatus.PENDING_CHECK,
    note = note,
)

fun ClockHandoff.toEntity() = ClockHandoffEntity(
    id = id,
    hour = hour,
    minute = minute,
    label = label,
    targetEpochDay = targetEpochDay,
    weekdays = weekdays.sorted().joinToString(","),
    requestedAtMillis = requestedAtMillis,
    status = status.name,
    note = note,
)
