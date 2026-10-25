package com.zhideer.app.domain.model

enum class TaskPriority(val label: String) {
    LOW("低"),
    NORMAL("普通"),
    HIGH("高"),
}

enum class TaskStatus {
    ACTIVE,
    COMPLETED,
    CANCELLED,
}

/**
 * 待办：用户希望完成的结果，可以具有截止时间。
 * 截止时间表示最晚何时完成，不表示占用一段时间（那是日程）。
 *
 * 截止有两种形态，互斥：
 * - [dueEpochDay]：日期截止，仅指定最晚完成日期、不含时刻，到期当天属于今日到期，次日属于过期；
 * - [dueAtMillis]：具体时刻截止（UTC 毫秒）。
 *
 * [dueZoneId] 在创建时锚定，用于日期截止的“今天/过期”判定，不随设备时区静默变化。
 * 取消（CANCELLED）保留业务记录并停止提醒；普通删除走回收站（[trashedAt]），不永久删除。
 */
data class TodoTask(
    val id: String,
    val title: String,
    val note: String = "",
    val priority: TaskPriority = TaskPriority.NORMAL,
    val category: String = "",
    val dueEpochDay: Long? = null,
    val dueAtMillis: Long? = null,
    val dueZoneId: String,
    val status: TaskStatus = TaskStatus.ACTIVE,
    val trashedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long? = null,
    val postponedAt: Long? = null,
    val cancelledAt: Long? = null,
)
