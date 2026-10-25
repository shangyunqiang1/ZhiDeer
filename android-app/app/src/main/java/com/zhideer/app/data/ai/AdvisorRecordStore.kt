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
import com.zhideer.app.domain.model.AdvisorAdoptionOutcome
import com.zhideer.app.domain.model.AdvisorAdoptionRecord
import com.zhideer.app.domain.model.AdvisorFeedbackRecord
import com.zhideer.app.domain.model.AdvisorProposalBinding
import com.zhideer.app.domain.model.AdvisorRecords
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
 * 参谋建议反馈、采纳执行记录与提案绑定存储（T30/#31）：DataStore Preferences 中保存一份 JSON。
 *
 * 边界：
 * - 有意避开 Room（提案与参谋记录不是业务事项，无需业务库 schema 迁移）；
 * - 不写长期记忆：反馈是「建议的使用记录」，不是用户偏好；长期记忆只经用户显式确认保存；
 * - 采纳记录按提案 id 唯一：重复采纳/响应丢失后以同一条记录核对真实结果；
 * - 一旦记录为 [AdvisorAdoptionOutcome.ADOPTED] 即终态，后续并发/重复写入不会把
 *   已采纳降级为未知或失败（真实执行成功的事实不因响应丢失而回退）。
 */
class AdvisorRecordStore(
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

    suspend fun load(): AdvisorRecords = parse(readRaw())

    /** 观察记录：DataStore 每次写入后发出最新快照。 */
    fun observe(): Flow<AdvisorRecords> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> parse(prefs[KEY_RECORDS]) }

    /** 追加一条反馈（DataStore edit 互斥，避免并发覆盖）。 */
    suspend fun appendFeedback(record: AdvisorFeedbackRecord) {
        mutate { it.copy(feedback = it.feedback + record) }
    }

    /**
     * 按提案 id 写入/更新采纳执行记录（同一提案只保留一条，供核对）。
     * 已采纳（ADOPTED）为终态：后续 UNKNOWN/FAILED/CANCELLED 等不会覆盖它。
     */
    suspend fun upsertAdoption(record: AdvisorAdoptionRecord) {
        mutate { records ->
            val existing = records.adoptions.firstOrNull { it.proposalId == record.proposalId }
            if (existing?.outcome == AdvisorAdoptionOutcome.ADOPTED) {
                records
            } else {
                records.copy(
                    adoptions = records.adoptions.filterNot { it.proposalId == record.proposalId } + record,
                )
            }
        }
    }

    /** 保存/更新建议与提案的绑定（同一提案只保留一条）。 */
    suspend fun bindProposal(binding: AdvisorProposalBinding) {
        mutate { records ->
            records.copy(
                bindings = records.bindings.filterNot { it.proposalId == binding.proposalId } + binding,
            )
        }
    }

    fun close() {
        scope.cancel()
    }

    private suspend fun mutate(block: (AdvisorRecords) -> AdvisorRecords) {
        dataStore.edit { prefs ->
            prefs[KEY_RECORDS] = json.encodeToString(block(parse(prefs[KEY_RECORDS])))
        }
    }

    private suspend fun readRaw(): String? = try {
        dataStore.data.first()[KEY_RECORDS]
    } catch (e: IOException) {
        null
    }

    private fun parse(raw: String?): AdvisorRecords {
        raw ?: return AdvisorRecords()
        // 记录损坏时返回空集合而不是让应用崩溃
        return runCatching { json.decodeFromString<AdvisorRecords>(raw) }.getOrDefault(AdvisorRecords())
    }

    private companion object {
        val KEY_RECORDS = stringPreferencesKey("advisor_records_json")
    }
}
