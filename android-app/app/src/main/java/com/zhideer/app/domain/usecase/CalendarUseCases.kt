package com.zhideer.app.domain.usecase

import com.zhideer.app.domain.model.CalendarEntry
import com.zhideer.app.domain.model.CalendarRange
import com.zhideer.app.domain.model.CalendarTimes
import com.zhideer.app.domain.repository.CalendarRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId

/** 日程编辑输入；时间以 UTC 毫秒表示，时区单独保存。 */
data class CalendarInput(
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val timezoneId: String,
    val location: String = "",
    val note: String = "",
    val category: String = "",
)

/**
 * 统一日程用例入口：创建、编辑、回收站删除、今日/明日/未来七天与按日查询、时区核对确认。
 * 创建时区锚定保存；日期归属按所存时区计算；所有查询只返回真实持久记录。
 * T09/#10 纯新增两个联动钩子（默认无操作，仿 TaskUseCases 回调模式）：
 * - [onTimesChanged]：日程时间变化（update/时区重解释）成功后触发，
 *   AppContainer 注册为 CalendarReminderUseCases::rescheduleForStartChange，
 *   相对提醒随开始时刻移动、独立时刻提醒不动；
 * - [onTrashed]：日程进入回收站成功后触发，注册为 CalendarReminderUseCases::cancelReminders，
 *   停止日程自身提醒，不影响关联待办自身的提醒。
 */
class CalendarUseCases(
    private val repository: CalendarRepository,
    private val idGen: () -> String,
    private val now: () -> Long,
    private val zoneId: String,
    // T09/#10 纯新增：日程时间变化后的提醒重算钩子，默认无操作。不改既有语义。
    private val onTimesChanged: suspend (entryId: String) -> Unit = {},
    // T09/#10 纯新增：日程进入回收站后的提醒停止钩子，默认无操作。不改既有语义。
    private val onTrashed: suspend (entryId: String) -> Unit = {},
) {

    suspend fun create(input: CalendarInput): Result<CalendarEntry> {
        val error = validate(input)
        if (error != null) return Result.failure(IllegalArgumentException(error))
        val t = now()
        val entry = CalendarEntry(
            id = idGen(),
            title = input.title.trim(),
            startMillis = input.startMillis,
            endMillis = input.endMillis,
            timezoneId = input.timezoneId,
            location = input.location.trim(),
            note = input.note,
            category = input.category.trim(),
            createdAt = t,
            updatedAt = t,
        )
        repository.upsertEntry(entry)
        return Result.success(entry)
    }

    suspend fun update(id: String, input: CalendarInput): Result<CalendarEntry> {
        val error = validate(input)
        if (error != null) return Result.failure(IllegalArgumentException(error))
        val current = repository.getEntry(id)
            ?: return Result.failure(IllegalStateException("日程不存在或已永久删除"))
        val updated = current.copy(
            title = input.title.trim(),
            startMillis = input.startMillis,
            endMillis = input.endMillis,
            timezoneId = input.timezoneId,
            location = input.location.trim(),
            note = input.note,
            category = input.category.trim(),
            updatedAt = now(),
        )
        repository.upsertEntry(updated)
        // T09/#10：改期成功后重算相对提醒（随开始时刻移动），独立时刻提醒不动
        onTimesChanged(id)
        return Result.success(updated)
    }

    /** 普通删除：标记回收站并从正常查询排除，不永久删除。T09/#10：同时停止日程自身提醒。 */
    suspend fun trash(id: String): Result<Unit> {
        val current = repository.getEntry(id)
            ?: return Result.failure(IllegalStateException("日程不存在或已永久删除"))
        repository.trashEntry(id, now())
        onTrashed(id)
        return Result.success(Unit)
    }

    suspend fun get(id: String): CalendarEntry? = repository.getEntry(id)

    fun observeAll(): Flow<List<CalendarEntry>> = repository.observeEntries()

    /** 今日/明日/未来七天查询：按每条日程所存时区计算归属日期，绝不虚构记录。 */
    fun observe(range: CalendarRange): Flow<List<CalendarEntry>> =
        observeAll().map { entries ->
            val t = now()
            entries.filter { CalendarTimes.matchesRange(it, range, t) }
        }

    /** 指定日期的日程（含跨日安排占据该日的情况），日期按所存时区解释。 */
    fun observeOnDay(day: LocalDate): Flow<List<CalendarEntry>> =
        observeAll().map { entries ->
            entries.filter { CalendarTimes.spansDay(it, day) }
        }

    /**
     * 时区核对之一：用户确认「保持原时区」。
     * 不改动开始/结束时间与所存时区，只记录确认动作时间戳。
     */
    suspend fun confirmKeepZone(id: String): Result<CalendarEntry> {
        val current = repository.getEntry(id)
            ?: return Result.failure(IllegalStateException("日程不存在或已永久删除"))
        val t = now()
        val updated = current.copy(zoneConfirmedAt = t, updatedAt = t)
        repository.upsertEntry(updated)
        return Result.success(updated)
    }

    /**
     * 时区核对之二：用户确认「按新时区重新解释」。
     * 保留墙上时间（本地日期时刻不变），按新时区重算 UTC 毫秒并改写所存时区，
     * 记录确认动作时间戳；绝不静默执行，只能由用户显式触发。
     */
    suspend fun reinterpretInZone(id: String, newZoneId: String = zoneId): Result<CalendarEntry> {
        val newZone = runCatching { ZoneId.of(newZoneId) }.getOrElse {
            return Result.failure(IllegalArgumentException("时区无效"))
        }
        val current = repository.getEntry(id)
            ?: return Result.failure(IllegalStateException("日程不存在或已永久删除"))
        val oldZone = ZoneId.of(current.timezoneId)
        val startLocal = java.time.Instant.ofEpochMilli(current.startMillis).atZone(oldZone).toLocalDateTime()
        val endLocal = java.time.Instant.ofEpochMilli(current.endMillis).atZone(oldZone).toLocalDateTime()
        val newStart = startLocal.atZone(newZone).toInstant().toEpochMilli()
        val newEnd = endLocal.atZone(newZone).toInstant().toEpochMilli()
        if (newEnd < newStart) {
            return Result.failure(IllegalArgumentException("结束时间不能早于开始时间"))
        }
        val t = now()
        val updated = current.copy(
            startMillis = newStart,
            endMillis = newEnd,
            timezoneId = newZone.id,
            zoneConfirmedAt = t,
            updatedAt = t,
        )
        repository.upsertEntry(updated)
        // T09/#10：重解释同样改变开始时刻，相对提醒随之重算，独立时刻提醒不动
        onTimesChanged(id)
        return Result.success(updated)
    }

    private fun validate(input: CalendarInput): String? = when {
        input.title.isBlank() -> "标题不能为空"
        runCatching { ZoneId.of(input.timezoneId) }.isFailure -> "时区无效"
        input.endMillis < input.startMillis -> "结束时间不能早于开始时间"
        else -> null
    }
}
