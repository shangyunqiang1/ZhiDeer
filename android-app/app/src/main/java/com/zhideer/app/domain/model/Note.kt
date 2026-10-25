package com.zhideer.app.domain.model

/**
 * 便签清单项：便签内可勾选的记录条目（GLOSSARY：便签清单项）。
 * 勾选状态完全独立于待办系统，不自动创建、关联或完成任何待办。
 * _Avoid_: 子任务、待办
 */
data class NoteListItem(
    val id: String,
    val text: String,
    val checked: Boolean = false,
)

/**
 * 便签：用户记录的文字、清单与标签。
 * - [title]、[content] 保存用户原文，不自动扩写或改写；
 * - [items] 是便签清单项，不是子任务，更不是待办；
 * - 普通删除走回收站（[trashedAt]）：正常查询与搜索都排除回收站记录，且不永久删除。
 */
data class Note(
    val id: String,
    val title: String,
    val content: String = "",
    val tags: List<String> = emptyList(),
    val pinned: Boolean = false,
    val items: List<NoteListItem> = emptyList(),
    val trashedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
