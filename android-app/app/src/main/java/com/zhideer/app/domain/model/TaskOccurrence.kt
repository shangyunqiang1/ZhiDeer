package com.zhideer.app.domain.model

/**
 * 重复待办某一次出现的完成记录（T12/#13）。
 * 维度是 (taskId, occurrenceEpochDay)：每一次出现分别记录完成或未完成（GLOSSARY），
 * 完成时间戳独立；重复写入同一出现主键幂等，重开应用或重复计算不产生重复记录。
 */
data class TaskOccurrence(
    val taskId: String,
    val occurrenceEpochDay: Long,
    val completed: Boolean,
    val completedAt: Long? = null,
    val updatedAt: Long = 0,
)
