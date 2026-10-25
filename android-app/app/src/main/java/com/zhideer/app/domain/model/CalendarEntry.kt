package com.zhideer.app.domain.model

/**
 * 日程：占用某个时间段的安排（GLOSSARY：日程占用一段时间，与待办截止时间语义严格区分）。
 *
 * - [startMillis]/[endMillis] 为 UTC 毫秒；约束：结束不早于开始（endMillis >= startMillis）。
 * - [timezoneId] 在创建时锚定并保存，用于「今日/明日/未来七天」等日期归属判定，
 *   不随设备时区静默变化；时区调整必须经用户显式确认。
 * - [zoneConfirmedAt] 记录用户对时区核对（保持原时区 / 按新时区重新解释）的确认动作时间戳。
 * - [taskId] 为可选关联的待办稳定 id（外键语义，不保存标题文本副本）：一个日程最多关联一个待办，
 *   一个待办可关联多个日程；改任一方标题不影响关联。日程与待办的完成状态互不自动联动。
 * - 日程结束只表示时间已过，不表示业务完成（完成状态属于待办，日程模型没有完成字段）。
 * - 普通删除使用回收站语义（[trashedAt]），正常查询排除、不永久删除。
 */
data class CalendarEntry(
    val id: String,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val timezoneId: String,
    val location: String = "",
    val note: String = "",
    val category: String = "",
    val taskId: String? = null,
    val trashedAt: Long? = null,
    val zoneConfirmedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
