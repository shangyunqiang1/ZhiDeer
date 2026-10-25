package com.zhideer.app.data.repository

import com.zhideer.app.data.db.ReminderScheduleEntity
import com.zhideer.app.data.db.ReminderScheduleLogDao
import com.zhideer.app.domain.model.ReminderScheduleEntry
import com.zhideer.app.domain.model.ReminderScheduleState
import com.zhideer.app.domain.repository.ReminderScheduleLogRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** 系统调度台账持久化实现（T10/#11）。 */
class ReminderScheduleLogRepositoryImpl(
    private val dao: ReminderScheduleLogDao,
    private val ioDispatcher: CoroutineDispatcher,
) : ReminderScheduleLogRepository {

    override suspend fun recordPending(
        reminderKey: String,
        ownerId: String,
        ownerTitle: String,
        scheduledAtMillis: Long,
        recordedAtMillis: Long,
    ) = withContext(ioDispatcher) {
        dao.insertPending(
            ReminderScheduleEntity(
                reminderKey = reminderKey,
                scheduledAtMillis = scheduledAtMillis,
                ownerId = ownerId,
                ownerTitle = ownerTitle,
                state = ReminderScheduleState.PENDING.name,
                recordedAtMillis = recordedAtMillis,
            ),
        )
    }

    override suspend fun removePending(reminderKey: String) = withContext(ioDispatcher) {
        dao.deletePendingForKey(reminderKey)
    }

    override suspend fun markDelivered(
        reminderKey: String,
        ownerId: String,
        ownerTitle: String,
        scheduledAtMillis: Long,
        deliveredAtMillis: Long,
    ) = withContext(ioDispatcher) {
        val updated = dao.markDelivered(reminderKey, scheduledAtMillis, deliveredAtMillis)
        if (updated == 0) {
            // 无既有台账（例如升级前已提交的调度）：补记已投递，供终态抑制与追溯
            dao.upsertDelivered(
                ReminderScheduleEntity(
                    reminderKey = reminderKey,
                    scheduledAtMillis = scheduledAtMillis,
                    ownerId = ownerId,
                    ownerTitle = ownerTitle,
                    state = ReminderScheduleState.DELIVERED.name,
                    recordedAtMillis = deliveredAtMillis,
                    deliveredAtMillis = deliveredAtMillis,
                ),
            )
        }
    }

    override suspend fun markMissedBefore(
        cutoffMillis: Long,
        detectedAtMillis: Long,
    ): List<ReminderScheduleEntry> = withContext(ioDispatcher) {
        dao.listPendingDue(cutoffMillis).mapNotNull { row ->
            if (dao.markMissed(row.reminderKey, row.scheduledAtMillis, detectedAtMillis) > 0) {
                row.copy(
                    state = ReminderScheduleState.MISSED.name,
                    detectedAtMillis = detectedAtMillis,
                ).toModel()
            } else {
                null
            }
        }
    }

    override suspend fun listPending(): List<ReminderScheduleEntry> = withContext(ioDispatcher) {
        dao.listPending().map { it.toModel() }
    }

    override suspend fun listTerminalInstants(): Set<String> = withContext(ioDispatcher) {
        dao.listTerminal().map { it.instantKey() }.toSet()
    }

    override suspend fun listOutstandingMissed(): List<ReminderScheduleEntry> = withContext(ioDispatcher) {
        dao.listOutstandingMissed().map { it.toModel() }
    }

    override fun observeOutstandingMissed(): Flow<List<ReminderScheduleEntry>> =
        dao.observeOutstandingMissed()
            .map { rows -> rows.map { it.toModel() } }
            .flowOn(ioDispatcher)

    override suspend fun acknowledgeMissed(acknowledgedAtMillis: Long): Int = withContext(ioDispatcher) {
        dao.acknowledgeMissed(acknowledgedAtMillis)
    }
}

private fun ReminderScheduleEntity.toModel() = ReminderScheduleEntry(
    reminderKey = reminderKey,
    ownerId = ownerId,
    ownerTitle = ownerTitle,
    scheduledAtMillis = scheduledAtMillis,
    state = runCatching { ReminderScheduleState.valueOf(state) }
        .getOrDefault(ReminderScheduleState.PENDING),
    recordedAtMillis = recordedAtMillis,
    deliveredAtMillis = deliveredAtMillis,
    detectedAtMillis = detectedAtMillis,
    acknowledgedAtMillis = acknowledgedAtMillis,
)

private fun ReminderScheduleEntity.instantKey(): String = "$reminderKey@$scheduledAtMillis"
