package com.zhideer.app.data.db

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import com.zhideer.app.domain.model.Memory
import com.zhideer.app.domain.model.MemoryRefType
import com.zhideer.app.domain.model.MemorySource
import com.zhideer.app.domain.model.MemorySourceRef
import com.zhideer.app.domain.model.MemoryType

/**
 * 长期记忆表：独立于便签（notes）与待办（tasks）的隐私数据表。
 * - 无回收站列（trashedAt 不存在于此表）：删除即物理删除，不适用普通事项三十天保留规则；
 * - 不参与 AI 上下文快照与普通事项查询，仅供记忆界面与后续授权检索任务使用；
 * - 来源引用拆列存储（sourceRefType/sourceRefId/sourceRefMissing，T27/#28）：
 *   支持按来源 SQL 级联标记与清理（WHERE sourceRefType = ? AND sourceRefId = ?），
 *   为空表示无来源引用。
 */
@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey val id: String,
    val content: String,
    val type: String,
    val source: String,
    val confirmed: Boolean,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val verifiedAt: Long,
    val expiresAt: Long?,
    val sourceRefType: String? = null,
    val sourceRefId: String? = null,
    val sourceRefMissing: Boolean = false,
)

fun MemoryEntity.toModel(): Memory = Memory(
    id = id,
    content = content,
    type = MemoryType.valueOf(type),
    source = MemorySource.valueOf(source),
    confirmed = confirmed,
    enabled = enabled,
    createdAt = createdAt,
    updatedAt = updatedAt,
    verifiedAt = verifiedAt,
    expiresAt = expiresAt,
    sourceRef = sourceRefType?.let { type ->
        MemorySourceRef(
            type = MemoryRefType.valueOf(type),
            id = requireNotNull(sourceRefId) { "来源引用类型与 id 必须成对存储" },
            missing = sourceRefMissing,
        )
    },
)

fun Memory.toEntity(): MemoryEntity = MemoryEntity(
    id = id,
    content = content,
    type = type.name,
    source = source.name,
    confirmed = confirmed,
    enabled = enabled,
    createdAt = createdAt,
    updatedAt = updatedAt,
    verifiedAt = verifiedAt,
    expiresAt = expiresAt,
    sourceRefType = sourceRef?.type?.name,
    sourceRefId = sourceRef?.id,
    sourceRefMissing = sourceRef?.missing ?: false,
)
