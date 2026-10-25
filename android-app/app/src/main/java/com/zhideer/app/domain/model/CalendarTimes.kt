package com.zhideer.app.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 日程范围查询：今日、明日、未来七天。结果一律来自真实持久记录，绝不虚构。 */
enum class CalendarRange(val label: String) {
    TODAY("今日"),
    TOMORROW("明日"),
    NEXT_SEVEN_DAYS("未来七天"),
}

/**
 * 日程时间语义。日期归属一律按日程所存 [CalendarEntry.timezoneId] 计算，
 * 不使用设备当前时区，避免设备时区变化静默改变安排的归属日期。
 */
object CalendarTimes {

    /** 指定时区与时刻下的“今天”（本地日期）。 */
    fun todayIn(zoneId: String, nowMillis: Long): LocalDate =
        LocalDate.ofInstant(Instant.ofEpochMilli(nowMillis), ZoneId.of(zoneId))

    /** 日程开始时刻在其所存时区的本地日期（跨日安排的开始日）。 */
    fun startLocalDate(entry: CalendarEntry): LocalDate =
        Instant.ofEpochMilli(entry.startMillis).atZone(ZoneId.of(entry.timezoneId)).toLocalDate()

    /** 日程结束时刻在其所存时区的本地日期（跨日安排的结束日）。 */
    fun endLocalDate(entry: CalendarEntry): LocalDate =
        Instant.ofEpochMilli(entry.endMillis).atZone(ZoneId.of(entry.timezoneId)).toLocalDate()

    /** 该日程是否占据指定日期（开始日到结束日之间，含两端；跨日安排显示在每一天）。 */
    fun spansDay(entry: CalendarEntry, day: LocalDate): Boolean =
        !startLocalDate(entry).isAfter(day) && !endLocalDate(entry).isBefore(day)

    /** 该日程是否命中范围查询；判定基准日按日程所存时区计算。 */
    fun matchesRange(entry: CalendarEntry, range: CalendarRange, nowMillis: Long): Boolean {
        val today = todayIn(entry.timezoneId, nowMillis)
        return when (range) {
            CalendarRange.TODAY -> spansDay(entry, today)
            CalendarRange.TOMORROW -> spansDay(entry, today.plusDays(1))
            CalendarRange.NEXT_SEVEN_DAYS -> (0L..6L).any { offset -> spansDay(entry, today.plusDays(offset)) }
        }
    }

    /** 日程结束只表示时间已过（供界面展示「已结束」），不表示业务完成。 */
    fun hasEnded(entry: CalendarEntry, nowMillis: Long): Boolean = entry.endMillis < nowMillis

    /** 时区核对：日程所存时区与设备当前时区不一致时需要用户显式确认。 */
    fun detectMismatch(entry: CalendarEntry, deviceZoneId: String): Boolean =
        entry.timezoneId != deviceZoneId
}
