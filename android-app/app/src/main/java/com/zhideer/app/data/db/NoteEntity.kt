package com.zhideer.app.data.db

import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.Relation
import com.zhideer.app.domain.model.Note
import com.zhideer.app.domain.model.NoteListItem

/** 标签存储：以单元分隔符连接，标签文本来自逗号/空格分隔输入，不含该控制字符。 */
private const val TAG_SEPARATOR = "\u0001"

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    val content: String,
    val tags: String,
    val pinned: Boolean,
    val trashedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 便签清单项：一对多属于便签，随父便签级联删除；[sortOrder] 保证列表顺序稳定。 */
@Entity(
    tableName = "note_list_items",
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("noteId")],
)
data class NoteListItemEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    val text: String,
    val checked: Boolean,
    val sortOrder: Int,
)

/** 便签与其清单项的一次性读取结果。 */
data class NoteWithItems(
    @Embedded val note: NoteEntity,
    @Relation(parentColumns = ["id"], entityColumns = ["noteId"])
    val items: List<NoteListItemEntity>,
)

fun NoteWithItems.toModel(): Note = Note(
    id = note.id,
    title = note.title,
    content = note.content,
    tags = decodeTags(note.tags),
    pinned = note.pinned,
    items = items.sortedBy { it.sortOrder }.map { NoteListItem(id = it.id, text = it.text, checked = it.checked) },
    trashedAt = note.trashedAt,
    createdAt = note.createdAt,
    updatedAt = note.updatedAt,
)

fun Note.toEntity(): NoteEntity = NoteEntity(
    id = id,
    title = title,
    content = content,
    tags = encodeTags(tags),
    pinned = pinned,
    trashedAt = trashedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun NoteListItem.toEntity(noteId: String, sortOrder: Int): NoteListItemEntity = NoteListItemEntity(
    id = id,
    noteId = noteId,
    text = text,
    checked = checked,
    sortOrder = sortOrder,
)

fun encodeTags(tags: List<String>): String = tags.joinToString(TAG_SEPARATOR)

fun decodeTags(raw: String): List<String> =
    if (raw.isEmpty()) emptyList() else raw.split(TAG_SEPARATOR)
