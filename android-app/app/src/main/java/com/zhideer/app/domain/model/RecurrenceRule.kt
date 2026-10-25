package com.zhideer.app.domain.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * 重复频次（T12/#13 每日与每周；T13/#14 每月）。
 * 不实现“完成后再重复”的另一种语义（GLOSSARY：重复待办按固定日期规则反复出现）。
 */
enum class RecurrenceFrequency(val label: String) {
    DAILY("每日"),
    WEEKLY("每周"),
    MONTHLY("每月"),
}

/**
 * 重复规则：按照固定日期规则反复出现的待办安排（GLOSSARY：重复待办）。
 * - DAILY：每 [intervalDays] 天出现一次（intervalDays >= 1，1 即每天）；
 * - WEEKLY：每周命中的 [weekdays]（至少一个）出现；
 * - MONTHLY：每月 [monthDay]（1-31）日出现。该月没有此日（如 2 月无 29/30/31 日）
 *   时落到当月最后一天，之后月份回到 [monthDay] 日，不漂移（issue #14：
 *   每月 31 日遇二月落月末，三月仍回到 31 日，不漂移成每月 28 日）。
 *
 * 出现锚点不冗余存储：使用待办 createdAt 在其 [TodoTask.dueZoneId]（创建时锚定时区）下的日期，
 * 由 [RecurrenceAnchors.anchorEpochDay] 派生，天然幂等且编辑规则不改变既有节奏。
 *
 * 每一次出现都是独立的记录维度：完成状态以 (taskId, 出现日 epochDay) 分别保存，
 * 本次未完成不阻止下一次出现；不存在“推进 dueDate 的同一条记录”。
 *
 * [exceptions]（T14/#15 纯新增）为例外日（epochDay）集合：被排除的出现日不再出现、
 * 不再要求完成（issue #15 例外日期与单次跳过共用此机制）。例外日只做排除，
 * 不改写规则几何，也不影响既有完成记录；旧数据默认空集合，天然兼容。
 */
data class RecurrenceRule(
    val frequency: RecurrenceFrequency,
    val intervalDays: Int = 1,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val monthDay: Int = 1,
    val exceptions: Set<Long> = emptySet(),
) {

    /** day 是否为该规则按锚点与频率几何的一次出现（不含例外日排除，供跳过/例外校验）。 */
    fun occursByPatternOn(anchorEpochDay: Long, day: Long): Boolean {
        if (day < anchorEpochDay) return false
        return when (frequency) {
            RecurrenceFrequency.DAILY -> (day - anchorEpochDay) % intervalDays == 0L
            RecurrenceFrequency.WEEKLY -> LocalDate.ofEpochDay(day).dayOfWeek in weekdays
            RecurrenceFrequency.MONTHLY -> {
                val date = LocalDate.ofEpochDay(day)
                date.dayOfMonth == resolvedMonthDay(YearMonth.from(date), monthDay)
            }
        }
    }

    /** day 是否为该规则的一次出现（锚点为出现序列的下限，例外日被排除）。 */
    fun occursOn(anchorEpochDay: Long, day: Long): Boolean =
        occursByPatternOn(anchorEpochDay, day) && day !in exceptions

    /** 从 day 起（含当天）下一次出现；规则有效则 DAILY/WEEKLY/MONTHLY 必有解，例外日被跳过。 */
    fun nextOnOrAfter(anchorEpochDay: Long, day: Long): Long {
        var candidate = nextPatternDayOnOrAfter(anchorEpochDay, day)
        // 例外日逐个向后跳：例外集合有限且候选严格递增，必终止
        while (candidate in exceptions) {
            candidate = nextPatternDayOnOrAfter(anchorEpochDay, candidate + 1)
        }
        return candidate
    }

    /** 按锚点与频率几何（不含例外日）从 day 起（含当天）下一次出现。 */
    private fun nextPatternDayOnOrAfter(anchorEpochDay: Long, day: Long): Long = when (frequency) {
        RecurrenceFrequency.DAILY ->
            if (day <= anchorEpochDay) anchorEpochDay
            else anchorEpochDay + ((day - anchorEpochDay + intervalDays - 1) / intervalDays) * intervalDays
        RecurrenceFrequency.WEEKLY -> {
            var candidate = maxOf(day, anchorEpochDay)
            // 最多看 7 天必命中一个星期几（几何不含例外，不会因例外死循环）
            while (!occursByPatternOn(anchorEpochDay, candidate)) candidate++
            candidate
        }
        RecurrenceFrequency.MONTHLY -> {
            val lower = maxOf(day, anchorEpochDay)
            var month = YearMonth.from(LocalDate.ofEpochDay(lower))
            var candidate = month.atDay(resolvedMonthDay(month, monthDay)).toEpochDay()
            // 当前月出现日早于下限时看下一月；下一月任意出现日都晚于本月，最多两轮必命中
            while (candidate < lower) {
                month = month.plusMonths(1)
                candidate = month.atDay(resolvedMonthDay(month, monthDay)).toEpochDay()
            }
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
            // WEEKLY/MONTHLY 逐日核对 occursOn（区间由调用方限定，预览最多一年）
            RecurrenceFrequency.WEEKLY -> first
            RecurrenceFrequency.MONTHLY -> first
        }
        while (day <= endDay) {
            if (occursOn(anchorEpochDay, day)) result += day
            day++
        }
        return result
    }

    /** 中文规则摘要：如「每天」「每 3 天」「每周 一、三、五」「每月 15 日」；有例外日时追加例外数。 */
    fun summary(): String {
        val base = when (frequency) {
            RecurrenceFrequency.DAILY ->
                if (intervalDays == 1) "每天" else "每 $intervalDays 天"
            RecurrenceFrequency.WEEKLY -> {
                val names = weekdays.sorted().joinToString("、") { weekdayName(it) }
                "每周 $names"
            }
            RecurrenceFrequency.MONTHLY -> "每月 $monthDay 日"
        }
        // T14/#15：摘要显示例外数，让例外日期在任何列表位置都可见
        return if (exceptions.isEmpty()) base else "$base，已排除 ${exceptions.size} 天"
    }

    companion object {
        /**
         * 规则校验：null 表示无重复（有效）；返回中文错误消息或 null（有效）。
         * 至少一个有效配置：每日间隔 >= 1 天；每周至少一个星期几；每月第几日为 1-31。
         */
        fun validate(rule: RecurrenceRule?): String? = when {
            rule == null -> null
            rule.frequency == RecurrenceFrequency.DAILY && rule.intervalDays < 1 -> "每日重复需间隔至少 1 天"
            rule.frequency == RecurrenceFrequency.WEEKLY && rule.weekdays.isEmpty() -> "每周重复需选择至少一个星期几"
            rule.frequency == RecurrenceFrequency.MONTHLY && rule.monthDay !in 1..31 -> "每月重复需选择 1-31 日"
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

/**
 * 短月回落（issue #14）：目标日 [monthDay] 在 [month] 不存在时取当月最后一天。
 * 只影响当月，不改写规则本身——三月仍回到目标日，不漂移。
 */
private fun resolvedMonthDay(month: YearMonth, monthDay: Int): Int = minOf(monthDay, month.lengthOfMonth())

/** 出现锚点派生：待办 createdAt 在创建时锚定时区下的日期（幂等，不随设备时区变化）。 */
object RecurrenceAnchors {
    fun anchorEpochDay(task: TodoTask): Long =
        java.time.LocalDate.ofInstant(java.time.Instant.ofEpochMilli(task.createdAt), java.time.ZoneId.of(task.dueZoneId))
            .toEpochDay()
}
