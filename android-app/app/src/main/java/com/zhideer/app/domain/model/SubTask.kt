package com.zhideer.app.domain.model

/**
 * 子任务：属于某个父待办的独立完成步骤。
 * 与父待办分别确认完成，不因一方完成而自动完成另一方（术语见 GLOSSARY.md）。
 *
 * - [doneAt] 记录子任务自身完成时刻，撤销完成即清除；与父待办的 completedAt 互不读写；
 * - 普通删除使用回收站语义（[trashedAt]），记录不永久删除，父子关系保留；
 * - [sortOrder] 决定显示顺序，新子任务排在已有子任务之后，顺序稳定可调整。
 */
data class SubTask(
    val id: String,
    val parentId: String,
    val title: String,
    val done: Boolean = false,
    val doneAt: Long? = null,
    val sortOrder: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val trashedAt: Long? = null,
)
