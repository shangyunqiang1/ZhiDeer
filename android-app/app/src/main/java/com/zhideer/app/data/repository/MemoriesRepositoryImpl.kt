package com.zhideer.app.data.repository

import com.zhideer.app.data.db.MemoryDao
import com.zhideer.app.data.db.toEntity
import com.zhideer.app.data.db.toModel
import com.zhideer.app.domain.model.Memory
import com.zhideer.app.domain.model.MemoryRefType
import com.zhideer.app.domain.repository.MemoriesRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class MemoriesRepositoryImpl(
    private val memoryDao: MemoryDao,
    private val ioDispatcher: CoroutineDispatcher,
) : MemoriesRepository {

    override fun observeMemories(): Flow<List<Memory>> =
        memoryDao.observeAll()
            .map { list -> list.map { it.toModel() } }
            .flowOn(ioDispatcher)

    override suspend fun getMemory(id: String): Memory? = withContext(ioDispatcher) {
        memoryDao.get(id)?.toModel()
    }

    override suspend fun upsertMemory(memory: Memory) = withContext(ioDispatcher) {
        memoryDao.upsert(memory.toEntity())
    }

    /** 彻底删除：直接物理删除该行，不保留任何痕迹。 */
    override suspend fun deleteMemory(id: String) = withContext(ioDispatcher) {
        memoryDao.deleteById(id)
    }

    override suspend fun disableAndMarkSourceMissing(
        sourceType: MemoryRefType,
        sourceId: String,
        updatedAt: Long,
    ): Int = withContext(ioDispatcher) {
        memoryDao.disableAndMarkSourceMissing(sourceType.name, sourceId, updatedAt)
    }

    override suspend fun clearSourceMissing(
        sourceType: MemoryRefType,
        sourceId: String,
        updatedAt: Long,
    ): Int = withContext(ioDispatcher) {
        memoryDao.clearSourceMissing(sourceType.name, sourceId, updatedAt)
    }

    override suspend fun deleteBySourceRef(sourceType: MemoryRefType, sourceId: String): Int =
        withContext(ioDispatcher) {
            memoryDao.deleteBySourceRef(sourceType.name, sourceId)
        }
}
