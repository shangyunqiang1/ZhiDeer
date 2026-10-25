package com.zhideer.app.domain.repository

import com.zhideer.app.domain.model.Memory
import kotlinx.coroutines.flow.Flow

/**
 * 长期记忆持久化入口。记忆是独立于便签与待办的隐私数据：
 * - 没有回收站语义，删除即彻底删除（不适用普通事项三十天保留规则）；
 * - 不参与 AI 上下文快照（#19 授权体系管理，本任务零耦合）。
 */
interface MemoriesRepository {
    fun observeMemories(): Flow<List<Memory>>
    suspend fun getMemory(id: String): Memory?
    suspend fun upsertMemory(memory: Memory)

    /** 彻底删除：物理删除该记忆行，不保留任何痕迹。 */
    suspend fun deleteMemory(id: String)
}
