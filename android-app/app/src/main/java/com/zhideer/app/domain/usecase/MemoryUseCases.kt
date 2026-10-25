package com.zhideer.app.domain.usecase

import com.zhideer.app.domain.model.Memory
import com.zhideer.app.domain.model.MemorySource
import com.zhideer.app.domain.model.MemoryType
import com.zhideer.app.domain.repository.MemoriesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** 长期记忆编辑输入：内容、类型与有效期均可选，内容不能为空。 */
data class MemoryInput(
    val content: String = "",
    val type: MemoryType = MemoryType.FACT,
    val expiresAt: Long? = null,
)

/**
 * 统一长期记忆用例入口：创建、编辑、停用启用、删除、观察与搜索。
 * - 只有用户显式保存并确认的内容成为长期记忆（GLOSSARY：长期记忆）；本任务唯一
 *   写入入口是用户手动录入，确认状态恒为已确认，不存在自动提取或未确认写入路径，
 *   因此“未确认不覆盖”天然满足；会话来源与新旧冲突展示由后续接入聊天的任务交付。
 * - 内容保存用户原文，不做自动扩写，不自动推断敏感属性；
 * - 停用是可逆的软禁用，停用或过期内容不进入可用记忆集合；
 * - 删除是彻底删除（物理删除）：记忆是独立于便签与待办的隐私数据，不进入回收站、
 *   不适用普通事项三十天保留规则（#17：记忆与隐私数据不误套普通事项三十天保留
 *   规则）；用户想暂时不用某条记忆时用停用（可恢复），确定不要才删除（不可恢复）。
 */
class MemoryUseCases(
    private val repository: MemoriesRepository,
    private val idGen: () -> String,
    private val now: () -> Long,
) {

    /** 显式保存一条长期记忆：手动录入即用户确认保存，来源恒为手动录入。 */
    suspend fun create(input: MemoryInput): Result<Memory> {
        val error = validate(input)
        if (error != null) return Result.failure(IllegalArgumentException(error))
        val t = now()
        val memory = Memory(
            id = idGen(),
            content = input.content.trim(),
            type = input.type,
            source = MemorySource.MANUAL,
            confirmed = true,
            enabled = true,
            createdAt = t,
            updatedAt = t,
            verifiedAt = t,
            expiresAt = input.expiresAt,
        )
        repository.upsertMemory(memory)
        return Result.success(memory)
    }

    /** 编辑长期记忆：更新内容、类型与有效期；编辑保存是用户对内容的重新确认。 */
    suspend fun update(id: String, input: MemoryInput): Result<Memory> {
        val error = validate(input)
        if (error != null) return Result.failure(IllegalArgumentException(error))
        val current = repository.getMemory(id)
            ?: return Result.failure(IllegalStateException("记忆不存在或已彻底删除"))
        val t = now()
        val updated = current.copy(
            content = input.content.trim(),
            type = input.type,
            expiresAt = input.expiresAt,
            updatedAt = t,
            verifiedAt = t,
        )
        repository.upsertMemory(updated)
        return Result.success(updated)
    }

    /** 停用或启用：停用内容不进入可用记忆集合，可随时恢复；仅更新状态不重置验证时间。 */
    suspend fun setEnabled(id: String, enabled: Boolean): Result<Memory> {
        val current = repository.getMemory(id)
            ?: return Result.failure(IllegalStateException("记忆不存在或已彻底删除"))
        val updated = current.copy(enabled = enabled, updatedAt = now())
        repository.upsertMemory(updated)
        return Result.success(updated)
    }

    /** 彻底删除：物理删除该记忆，不可恢复，不进入回收站。 */
    suspend fun delete(id: String): Result<Unit> {
        repository.getMemory(id)
            ?: return Result.failure(IllegalStateException("记忆不存在或已彻底删除"))
        repository.deleteMemory(id)
        return Result.success(Unit)
    }

    /** 按 id 读取记忆；彻底删除后返回 null。 */
    suspend fun get(id: String): Memory? = repository.getMemory(id)

    /** 观察全部记忆：按更新时间倒序，含停用与过期条目（管理视角）。 */
    fun observe(): Flow<List<Memory>> = repository.observeMemories()

    /**
     * 可用记忆集合：仅含已确认、未停用且未过期的记忆（issue #27：过期或停用内容
     * 不进入可用记忆集合）。供后续 AI 检索任务使用；本任务不接入任何 AI 功能。
     */
    fun observeActive(): Flow<List<Memory>> = repository.observeMemories().map { memories ->
        val t = now()
        memories.filter { it.isActiveAt(t) }
    }

    /**
     * 关键词搜索：命中记忆内容，大小写不敏感；空白关键词返回空结果。
     * 面向管理定位原条目，覆盖全部记忆（含停用与过期）；不涉及普通待办/便签搜索。
     */
    fun search(keyword: String): Flow<List<Memory>> {
        val query = keyword.trim().lowercase()
        if (query.isEmpty()) return flowOf(emptyList())
        return repository.observeMemories().map { memories ->
            memories.filter { it.content.lowercase().contains(query) }
        }
    }

    /** 内容为空白时拒绝保存，避免产生空记忆。 */
    private fun validate(input: MemoryInput): String? = when {
        input.content.isBlank() -> "记忆内容不能为空"
        else -> null
    }
}

/** 记忆在 [atMillis] 时刻是否属于可用记忆集合：已确认、未停用且未过有效期。 */
fun Memory.isActiveAt(atMillis: Long): Boolean =
    confirmed && enabled && (expiresAt == null || expiresAt > atMillis)
