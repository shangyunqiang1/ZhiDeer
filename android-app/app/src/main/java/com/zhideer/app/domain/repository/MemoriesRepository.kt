package com.zhideer.app.domain.repository

import com.zhideer.app.domain.model.Memory
import com.zhideer.app.domain.model.MemoryRefType
import kotlinx.coroutines.flow.Flow

/**
 * 长期记忆持久化入口。记忆是独立于便签与待办的隐私数据：
 * - 没有回收站语义，删除即彻底删除（不适用普通事项三十天保留规则）；
 * - 不参与 AI 上下文快照（#19 授权体系管理，本任务零耦合）；
 * - 来源删除边界（T27/#28）：来源记录删除/恢复/彻底删除时按来源引用联动，
 *   语义由 [MemoryUseCases] 承载，此处只提供按来源的持久化操作。
 */
interface MemoriesRepository {
    fun observeMemories(): Flow<List<Memory>>
    suspend fun getMemory(id: String): Memory?
    suspend fun upsertMemory(memory: Memory)

    /** 彻底删除：物理删除该记忆行，不保留任何痕迹。 */
    suspend fun deleteMemory(id: String)

    /** 来源进入回收站联动：停用并标记来源缺失，返回受影响条数（幂等）。[updatedAt] 由用例时钟提供。 */
    suspend fun disableAndMarkSourceMissing(sourceType: MemoryRefType, sourceId: String, updatedAt: Long): Int

    /** 来源恢复联动：清除来源缺失标记（不启用），返回受影响条数（幂等）。[updatedAt] 由用例时钟提供。 */
    suspend fun clearSourceMissing(sourceType: MemoryRefType, sourceId: String, updatedAt: Long): Int

    /** 来源彻底删除联动：物理删除关联记忆，返回清理条数。 */
    suspend fun deleteBySourceRef(sourceType: MemoryRefType, sourceId: String): Int
}
