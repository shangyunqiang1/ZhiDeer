package com.zhideer.app.domain.usecase

import com.zhideer.app.domain.model.Note
import com.zhideer.app.domain.model.NoteListItem
import com.zhideer.app.domain.repository.NotesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** 便签清单项编辑输入；[id] 为空白表示新增项，由 idGen 生成。 */
data class NoteListItemInput(
    val id: String = "",
    val text: String,
    val checked: Boolean = false,
)

/** 便签编辑输入：标题、正文、标签与清单项均可选，但不能全部为空。 */
data class NoteInput(
    val title: String = "",
    val content: String = "",
    val tags: List<String> = emptyList(),
    val items: List<NoteListItemInput> = emptyList(),
)

/**
 * 统一便签用例入口：创建、编辑、清单项勾选、置顶切换、回收站删除、搜索与观察。
 * - 便签保存用户原文，不做自动扩写；
 * - 清单项勾选完全独立于待办系统，不自动创建、关联或完成任何待办；
 * - 普通删除使用回收站语义：正常查询与搜索排除，记录不永久删除。
 */
class NoteUseCases(
    private val repository: NotesRepository,
    private val idGen: () -> String,
    private val now: () -> Long,
    // T27/#28 纯新增：便签进入回收站成功后的来源联动钩子，默认无操作；
    // AppContainer 组装后注册为 MemoryUseCases::markSourceMissing（NOTE），
    // 立即停用引用该便签的记忆并标记来源缺失。现有语义与既有调用不受影响。
    private val onTrashed: suspend (noteId: String) -> Unit = {},
) {

    suspend fun create(input: NoteInput): Result<Note> {
        val error = validate(input)
        if (error != null) return Result.failure(IllegalArgumentException(error))
        val t = now()
        val note = Note(
            id = idGen(),
            title = input.title.trim(),
            content = input.content,
            tags = normalizeTags(input.tags),
            items = normalizeItems(input.items),
            pinned = false,
            createdAt = t,
            updatedAt = t,
        )
        repository.upsertNote(note)
        return Result.success(note)
    }

    suspend fun update(id: String, input: NoteInput): Result<Note> {
        val error = validate(input)
        if (error != null) return Result.failure(IllegalArgumentException(error))
        val current = repository.getNote(id)
            ?: return Result.failure(IllegalStateException("便签不存在或已永久删除"))
        val updated = current.copy(
            title = input.title.trim(),
            content = input.content,
            tags = normalizeTags(input.tags),
            items = normalizeItems(input.items),
            updatedAt = now(),
        )
        repository.upsertNote(updated)
        return Result.success(updated)
    }

    /** 勾选或取消勾选便签清单项；状态只落在清单项本身，与待办零耦合。 */
    suspend fun toggleItem(noteId: String, itemId: String, checked: Boolean): Result<Note> {
        val current = repository.getNote(noteId)
            ?: return Result.failure(IllegalStateException("便签不存在或已永久删除"))
        if (current.items.none { it.id == itemId }) {
            return Result.failure(IllegalStateException("清单项不存在"))
        }
        val updated = current.copy(
            items = current.items.map { if (it.id == itemId) it.copy(checked = checked) else it },
            updatedAt = now(),
        )
        repository.upsertNote(updated)
        return Result.success(updated)
    }

    /** 置顶切换：置顶便签在观察列表中排在最前。 */
    suspend fun togglePin(id: String, pinned: Boolean): Result<Note> {
        val current = repository.getNote(id)
            ?: return Result.failure(IllegalStateException("便签不存在或已永久删除"))
        val updated = current.copy(pinned = pinned, updatedAt = now())
        repository.upsertNote(updated)
        return Result.success(updated)
    }

    /** 普通删除：标记回收站并从正常查询与搜索排除，不永久删除；来源联动经 onTrashed（#28）。 */
    suspend fun trash(id: String): Result<Unit> {
        repository.getNote(id)
            ?: return Result.failure(IllegalStateException("便签不存在或已永久删除"))
        repository.trashNote(id, now())
        onTrashed(id)
        return Result.success(Unit)
    }

    /** 按 id 读取便签（含回收站记录，用于打开原始记录与恢复管理）。 */
    suspend fun get(id: String): Note? = repository.getNote(id)

    /** 观察正常便签：置顶在前，其余按更新时间倒序；永远不含回收站记录。 */
    fun observe(): Flow<List<Note>> = repository.observeNotes().map { notes ->
        notes.sortedWith(
            compareByDescending<Note> { it.pinned }
                .thenByDescending { it.updatedAt },
        )
    }

    /**
     * 关键词搜索：命中标题、正文或清单项文本，大小写不敏感；不检索回收站内容。
     * 空白关键词返回空结果。
     */
    fun search(keyword: String): Flow<List<Note>> {
        val query = keyword.trim().lowercase()
        if (query.isEmpty()) return flowOf(emptyList())
        return repository.observeNotes().map { notes ->
            notes.filter { note ->
                note.title.lowercase().contains(query) ||
                    note.content.lowercase().contains(query) ||
                    note.items.any { it.text.lowercase().contains(query) }
            }
        }
    }

    /** 标题、正文与清单项文本全为空白时拒绝保存，避免产生空便签。 */
    private fun validate(input: NoteInput): String? = when {
        input.title.isBlank() && input.content.isBlank() &&
            input.items.all { it.text.isBlank() } -> "便签内容不能为空"
        else -> null
    }

    /** 清单项规范化：丢弃空白文本项，空白 id 由 idGen 生成，保持用户书写顺序。 */
    private fun normalizeItems(items: List<NoteListItemInput>): List<NoteListItem> =
        items.filter { it.text.isNotBlank() }.map { item ->
            NoteListItem(
                id = item.id.ifBlank { idGen() },
                text = item.text.trim(),
                checked = item.checked,
            )
        }
}

/** 标签规范化：去除首尾空白、丢弃空白标签、按首次出现去重，保持用户书写顺序。 */
private fun normalizeTags(tags: List<String>): List<String> =
    tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
