package com.zhideer.app.data.ai

import androidx.datastore.core.DataStore
import androidx.datastore.core.createSingleProcessCoordinator
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.zhideer.app.domain.model.AiProposal
import com.zhideer.app.domain.model.AiProposalStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Path.Companion.toOkioPath
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext

/**
 * AI 操作提案存储（T19/#20）：DataStore Preferences 中保存 JSON 数组。
 * 有意避开 Room（数据库零改动）：提案不是业务事项，是待确认的草稿状态，
 * 确认后创建的记录才进入业务仓库；持久化便于退出后仍可核对提案状态与回执。
 * 文件系统策略与 [AiSessionStore] 相同（ReliableFileSystem，兼容 Windows 单元测试）。
 */
class AiProposalStore(
    file: File,
    ioDispatcher: CoroutineContext = Dispatchers.IO,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        storage = OkioStorage(
            fileSystem = ReliableFileSystem,
            serializer = PreferencesSerializer,
            coordinatorProducer = { _, _ -> createSingleProcessCoordinator(file) },
            producePath = { file.toOkioPath() },
        ),
        scope = scope,
    )

    /** 全部提案（存储顺序，追加在后）。 */
    suspend fun load(): List<AiProposal> = parse(readRaw())

    /** 观察提案：每次写入后发出最新列表。 */
    fun observe(): Flow<List<AiProposal>> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> parse(prefs[KEY_PROPOSALS]) }

    suspend fun save(proposals: List<AiProposal>) {
        dataStore.edit { prefs ->
            prefs[KEY_PROPOSALS] = json.encodeToString(proposals)
        }
    }

    /**
     * 原子状态转移：仅当 [id] 存在且当前状态 ∈ [from] 时应用 [update] 并持久化，
     * 返回更新后的提案；否则返回 null 且绝不写入。
     * DataStore edit 互斥执行，供确认抢占（PENDING→CONFIRMED）防重复确认。
     */
    suspend fun transition(
        id: String,
        from: Set<AiProposalStatus>,
        update: (AiProposal) -> AiProposal,
    ): AiProposal? {
        var result: AiProposal? = null
        dataStore.edit { prefs ->
            val current = parse(prefs[KEY_PROPOSALS])
            val target = current.find { it.id == id }?.takeIf { it.status in from } ?: return@edit
            val updated = update(target)
            result = updated
            prefs[KEY_PROPOSALS] = json.encodeToString(current.map { if (it.id == id) updated else it })
        }
        return result
    }

    fun close() {
        scope.cancel()
    }

    private suspend fun readRaw(): String? = try {
        dataStore.data.first()[KEY_PROPOSALS]
    } catch (e: IOException) {
        null
    }

    private fun parse(raw: String?): List<AiProposal> {
        raw ?: return emptyList()
        // 数据损坏时返回空列表而不是让整个应用崩溃
        return runCatching { json.decodeFromString<List<AiProposal>>(raw) }.getOrDefault(emptyList())
    }

    private companion object {
        val KEY_PROPOSALS = stringPreferencesKey("ai_proposals_json")
    }
}
