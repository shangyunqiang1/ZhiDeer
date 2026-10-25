package com.zhideer.app.domain.model

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 重复频次（T12/#13）。本任务仅交付每日与每周；月规则与范围调整由后续任务交付，
 * 也不实现“完成后再重复”的另一种语义（GLOSSARY：重复待办按固定日期规则反复出现）。
 */
enum class RecurrenceFrequency(val label: String) {
    DAILY("每日"),
    WEEKLY("每周"),
}

/**
 * 重复规则：按照固定日期规则反复出现的待办安排（GLOSSARY：重复待办）。
 * - DAILY：每 [intervalDays] 天出现一次（intervalDays >= 1，1 即每天）；
 * - WEEKLY：每周命中的 [weekdays]（至少一个）出现。
 *
 * 出现锚点不冗余存储：使用待办 createdAt 在其 [TodoTask.dueZoneId]（创建时锚定时区）下的日期，
 * 由 [RecurrenceAnchors.anchorEpochDay] 派生，天然幂等且编辑规则不改变既有节奏。
 *
 * 每一次出现都是独立的记录维度：完成状态以 (taskId, 出现日 epochDay) 分别保存，
 * 本次未完成不阻止下一次出现；不存在“推进 dueDate 的同一条记录”。
 */
data class RecurrenceRule(
    val frequency: RecurrenceFrequency,
    val intervalDays: Int = 1,
    val weekdays: Set<DayOfWeek> = emptySet(),
) {

    /** day 是否为该规则的一次出现（锚点为出现序列的下限）。 */
    fun occursOn(anchorEpochDay: Long, day: Long): Boolean {
        if (day < anchorEpochDay) return false
        return when (frequency) {
            RecurrenceFrequency.DAILY -> (day - anchorEpochDay) % intervalDays == 0L
            RecurrenceFrequency.WEEKLY -> LocalDate.ofEpochDay(day).dayOfWeek in weekdays
        }
    }

    /** 从 day 起（含当天）下一次出现；规则有效则 DAILY/WEEKLY 必有解。 */
    fun nextOnOrAfter(anchorEpochDay: Long, day: Long): Long = when (frequency) {
        RecurrenceFrequency.DAILY ->
            if (day <= anchorEpochDay) anchorEpochDay
            else anchorEpochDay + ((day - anchorEpochDay + intervalDays - 1) / intervalDays) * intervalDays
        RecurrenceFrequency.WEEKLY -> {
            var candidate = maxOf(day, anchorEpochDay)
            // 最多看 7 天必命中一个星期几
            while (!occursOn(anchorEpochDay, candidate)) candidate++
            candidate
        }
    }

    /** [startDay, endDay] 内的全部出现日（升序）。 */
    fun occurrencesBetween(anchorEpochDay: Long, startDay: Long, endDay: Long): List<Long> {
        if (endDay < startDay) return emptyList()
        val first = maxOf(startDay, anchorEpochDay)
        if (first > endDay) return emptyList()
        val result = mutableListOf<Long>()
        var day = when (frequency) {
            RecurrenceFrequency.DAILY ->
                if (first <= anchorEpochDay) anchorEpochDay
                else anchorEpochDay + ((first - anchorEpochDay + intervalDays - 1) / intervalDays) * intervalDays
            RecurrenceFrequency.WEEKLY -> first
        }
        while (day <= endDay) {
            if (occursOn(anchorEpochDay, day)) result += day
            day++
        }
        return result
    }

    /** 中文规则摘要：如「每天」「每 3 天」「每周 一、三、五」。 */
    fun summary(): String = when (frequency) {
        RecurrenceFrequency.DAILY ->
            if (intervalDays == 1) "每天" else "每 $intervalDays 天"
        RecurrenceFrequency.WEEKLY -> {
            val names = weekdays.sorted().joinToString("、") { weekdayName(it) }
            "每周 $names"
        }
    }

    companion object {
        /**
         * 规则校验：null 表示无重复（有效）；返回中文错误消息或 null（有效）。
         * 至少一个有效配置：每日间隔 >= 1 天；每周至少一个星期几。
         */
        fun validate(rule: RecurrenceRule?): String? = when {
            rule == null -> null
            rule.frequency == RecurrenceFrequency.DAILY && rule.intervalDays < 1 -> "每日重复需间隔至少 1 天"
            rule.frequency == RecurrenceFrequency.WEEKLY && rule.weekdays.isEmpty() -> "每周重复需选择至少一个星期几"
            else -> null
        }

        /** 中文星期名（从周一开始：一、二、三、四、五、六、日）。 */
        fun weekdayName(day: DayOfWeek): String = when (day) {
            DayOfWeek.MONDAY -> "一"
            DayOfWeek.TUESDAY -> "二"
            DayOfWeek.WEDNESDAY -> "三"
            DayOfWeek.THURSDAY -> "四"
            DayOfWeek.FRIDAY -> "五"
            DayOfWeek.SATURDAY -> "六"
            DayOfWeek.SUNDAY -> "日"
        }
    }
}

/** 出现锚点派生：待办 createdAt 在创建时锚定时区下的日期（幂等，不随设备时区变化）。 */
object RecurrenceAnchors {
    fun anchorEpochDay(task: TodoTask): Long =
        java.time.LocalDate.ofInstant(java.time.Instant.ofEpochMilli(task.createdAt), java.time.ZoneId.of(task.dueZoneId))
            .toEpochDay()
}
