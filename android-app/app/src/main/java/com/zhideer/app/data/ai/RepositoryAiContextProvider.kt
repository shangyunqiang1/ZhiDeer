package com.zhideer.app.data.ai

import com.zhideer.app.domain.model.TaskStatus
import com.zhideer.app.domain.model.TodoTask
import com.zhideer.app.domain.repository.CalendarRepository
import com.zhideer.app.domain.repository.NotesRepository
import com.zhideer.app.domain.repository.TasksRepository
import com.zhideer.app.domain.usecase.AiContextCategory
import com.zhideer.app.domain.usecase.AiContextGrant
import com.zhideer.app.domain.usecase.AiContextNoteRecord
import com.zhideer.app.domain.usecase.AiContextProvider
import com.zhideer.app.domain.usecase.AiContextScheduleRecord
import com.zhideer.app.domain.usecase.AiContextSnapshot
import com.zhideer.app.domain.usecase.AiContextTaskRecord
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 生产快照提供者（T18/#19）：按授权类别从真实仓库只读生成少量相关记录。
 * - 只读取 [grant] 中已授权类别的仓库，未授权类别绝不读取（用户故事 63）；
 * - 普通仓库查询已排除回收站，此处仍做 [trashedAt] 防御过滤（用户故事 50：回收站不参与 AI 检索）；
 * - 待办只取已过期未完成、今日到期与未来七天（GLOSSARY：日期截止当天属今日到期，次日属过期）；
 * - 日程取与其锚定时区下「今天起未来七天」窗口相交的记录（跨入窗口的进行中日程也提供）；
 * - 便签取最近保存的少量记录；
 * - 各类别设条数上限，只发送完成请求所需的相关少量内容，不默认发送全部本地数据；
 * - 只读：不产生任何业务写入（模型输出不回写业务数据）。
 */
class RepositoryAiContextProvider(
    private val tasksRepository: TasksRepository,
    private val calendarRepository: CalendarRepository,
    private val notesRepository: NotesRepository,
    private val zoneId: ZoneId,
    private val now: () -> Long,
) : AiContextProvider {

    override suspend fun loadSnapshot(grant: AiContextGrant): AiContextSnapshot {
        val generatedAtText = "${Instant.ofEpochMilli(now()).atZone(zoneId).format(dateTimeFmt)}（${zoneId.id}）"
        return AiContextSnapshot(
            generatedAtText = generatedAtText,
            tasks = if (grant.contains(AiContextCategory.TASKS)) loadTaskRecords() else emptyList(),
            schedule = if (grant.contains(AiContextCategory.SCHEDULE)) loadScheduleRecords() else emptyList(),
            notes = if (grant.contains(AiContextCategory.NOTES)) loadNoteRecords() else emptyList(),
        )
    }

    // ---------- 待办 ----------

    private suspend fun loadTaskRecords(): List<AiContextTaskRecord> {
        val today = Instant.ofEpochMilli(now()).atZone(zoneId).toLocalDate().toEpochDay()
        val windowEnd = today + SEVEN_DAYS
        return tasksRepository.observeTasks().first()
            .asSequence()
            .filter { it.trashedAt == null } // 防御：回收站不参与 AI 检索
            .filter { it.status == TaskStatus.ACTIVE }
            .mapNotNull { task -> task.dueDayOrNull()?.let { dueDay -> task to dueDay } }
            .filter { (_, dueDay) -> dueDay <= windowEnd } // 无截止与窗口外不发送（少量相关内容）
            .sortedBy { (_, dueDay) -> dueDay }
            .take(MAX_TASKS)
            .map { (task, dueDay) -> task.toRecord(dueDay, today) }
            .toList()
    }

    /** 截止日期（epochDay）：日期截止直接取值；时刻截止在其锚定时区下取日期。 */
    private fun TodoTask.dueDayOrNull(): Long? {
        val epochDay = dueEpochDay
        if (epochDay != null) return epochDay
        val dueAt = dueAtMillis ?: return null
        return Instant.ofEpochMilli(dueAt).atZone(ZoneId.of(dueZoneId)).toLocalDate().toEpochDay()
    }

    private fun TodoTask.toRecord(dueDay: Long, today: Long) = AiContextTaskRecord(
        title = title,
        dueText = dueEpochDay?.let { LocalDate.ofEpochDay(it).format(dateFmt) }
            ?: dueAtMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneId.of(dueZoneId)).format(dateTimeFmt) }
            ?: "",
        stateLabel = when {
            dueDay < today -> "已过期"
            dueDay == today -> "今日到期"
            else -> "未来七天"
        },
        priorityLabel = priority.label,
        recurring = recurrenceRule != null,
    )

    // ---------- 日程 ----------

    private suspend fun loadScheduleRecords(): List<AiContextScheduleRecord> =
        calendarRepository.observeEntries().first()
            .asSequence()
            .filter { it.trashedAt == null } // 防御：回收站不参与 AI 检索
            .filter { entry -> windowIntersects(entry) }
            .sortedBy { it.startMillis }
            .take(MAX_SCHEDULE)
            .map { entry ->
                val zone = ZoneId.of(entry.timezoneId)
                val start = Instant.ofEpochMilli(entry.startMillis).atZone(zone)
                val end = Instant.ofEpochMilli(entry.endMillis).atZone(zone)
                AiContextScheduleRecord(
                    title = entry.title,
                    timeText = if (start.toLocalDate() == end.toLocalDate()) {
                        "${start.format(timeFmt)}～${end.format(timeOnlyFmt)}"
                    } else {
                        "${start.format(timeFmt)}～${end.format(timeFmt)}"
                    },
                    location = entry.location,
                    note = entry.note,
                )
            }
            .toList()

    /** 与该日程锚定时区下「今天 00:00 起、共八天（今天 + 未来七天）」的窗口相交。 */
    private fun windowIntersects(entry: com.zhideer.app.domain.model.CalendarEntry): Boolean {
        val zone = ZoneId.of(entry.timezoneId)
        val windowStart = Instant.ofEpochMilli(now()).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val windowEnd = windowStart + (SEVEN_DAYS + 1) * DAY_MILLIS
        return entry.startMillis < windowEnd && entry.endMillis > windowStart
    }

    // ---------- 便签 ----------

    private suspend fun loadNoteRecords(): List<AiContextNoteRecord> =
        notesRepository.observeNotes().first()
            .asSequence()
            .filter { it.trashedAt == null } // 防御：回收站不参与 AI 检索
            .sortedByDescending { it.updatedAt }
            .take(MAX_NOTES)
            .map { note ->
                val zone = zoneId
                AiContextNoteRecord(
                    title = note.title,
                    updatedText = Instant.ofEpochMilli(note.updatedAt).atZone(zone).format(dateTimeFmt),
                    content = note.content.take(NOTE_CONTENT_MAX),
                    itemsText = note.items.joinToString("、") { item ->
                        (if (item.checked) "[x] " else "[ ] ") + item.text
                    },
                )
            }
            .toList()

    private companion object {
        const val DAY_MILLIS = 24 * 3600_000L
        const val SEVEN_DAYS = 7L
        const val MAX_TASKS = 30
        const val MAX_SCHEDULE = 30
        const val MAX_NOTES = 10
        const val NOTE_CONTENT_MAX = 200

        val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
        val timeOnlyFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val dateTimeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
