package com.zhideer.app.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/** 系统调度台账 DAO（T10/#11）。 */
@Dao
interface ReminderScheduleLogDao {

    /** 同键同刻幂等写入；已存在终态记录时保留历史，绝不把错过/已投递改回待投递。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPending(entity: ReminderScheduleEntity)

    /** 无台账时补记已投递（仅 markDelivered 未命中任何行时使用）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDelivered(entity: ReminderScheduleEntity)

    @Query("DELETE FROM reminder_schedule_log WHERE reminderKey = :reminderKey AND state = 'PENDING'")
    suspend fun deletePendingForKey(reminderKey: String)

    @Query(
        "UPDATE reminder_schedule_log SET state = 'DELIVERED', deliveredAtMillis = :deliveredAtMillis, " +
            "detectedAtMillis = NULL, acknowledgedAtMillis = NULL " +
            "WHERE reminderKey = :reminderKey AND scheduledAtMillis = :scheduledAtMillis",
    )
    suspend fun markDelivered(reminderKey: String, scheduledAtMillis: Long, deliveredAtMillis: Long): Int

    @Query("SELECT * FROM reminder_schedule_log WHERE state = 'PENDING' ORDER BY scheduledAtMillis ASC")
    suspend fun listPending(): List<ReminderScheduleEntity>

    @Query(
        "SELECT * FROM reminder_schedule_log WHERE state = 'PENDING' AND scheduledAtMillis <= :cutoffMillis " +
            "ORDER BY scheduledAtMillis ASC",
    )
    suspend fun listPendingDue(cutoffMillis: Long): List<ReminderScheduleEntity>

    @Query(
        "UPDATE reminder_schedule_log SET state = 'MISSED', detectedAtMillis = :detectedAtMillis " +
            "WHERE reminderKey = :reminderKey AND scheduledAtMillis = :scheduledAtMillis AND state = 'PENDING'",
    )
    suspend fun markMissed(reminderKey: String, scheduledAtMillis: Long, detectedAtMillis: Long): Int

    @Query("SELECT * FROM reminder_schedule_log WHERE state != 'PENDING' ORDER BY scheduledAtMillis ASC")
    suspend fun listTerminal(): List<ReminderScheduleEntity>

    @Query(
        "SELECT * FROM reminder_schedule_log WHERE state = 'MISSED' AND acknowledgedAtMillis IS NULL " +
            "ORDER BY scheduledAtMillis DESC",
    )
    suspend fun listOutstandingMissed(): List<ReminderScheduleEntity>

    @Query(
        "SELECT * FROM reminder_schedule_log WHERE state = 'MISSED' AND acknowledgedAtMillis IS NULL " +
            "ORDER BY scheduledAtMillis DESC",
    )
    fun observeOutstandingMissed(): Flow<List<ReminderScheduleEntity>>

    @Query("UPDATE reminder_schedule_log SET acknowledgedAtMillis = :acknowledgedAtMillis WHERE state = 'MISSED' AND acknowledgedAtMillis IS NULL")
    suspend fun acknowledgeMissed(acknowledgedAtMillis: Long): Int
}
