package com.zhideer.app.data.diagnostics

import androidx.datastore.core.DataStore
import androidx.datastore.core.createSingleProcessCoordinator
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.zhideer.app.data.ai.ReliableFileSystem
import com.zhideer.app.domain.model.DiagnosticsFailure
import com.zhideer.app.domain.model.DiagnosticsFailureRecords
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
 * 最近失败记录存储（T35/#36）：DataStore Preferences 中保存一份 JSON，最多保留 [MAX_RECORDS] 条。
 *
 * 边界：
 * - 有意避开 Room：诊断记录不是业务事项，不需要业务库 schema 迁移；
 * - 写入前必须已脱敏（见 DiagnosticsRedaction），本类不读取 API Key、原始录音或事项正文；
 * - 相同类别的连续相同摘要只保留一条（更新时间，不刷屏）；
 * - 记录损坏时返回空列表而不是让应用崩溃。
 */
class DiagnosticsFailureStore(
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

    /** 最近失败记录，最新的在前。 */
    suspend fun load(): List<DiagnosticsFailure> = parse(readRaw()).records

    fun observe(): Flow<List<DiagnosticsFailure>> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> parse(prefs[KEY_RECORDS]).records }

    /** 追加一条已脱敏失败；超过上限丢弃最旧记录。 */
    suspend fun append(failure: DiagnosticsFailure) {
        dataStore.edit { prefs ->
            val existing = parse(prefs[KEY_RECORDS]).records
            val deduped = if (existing.firstOrNull()?.let {
                    it.category == failure.category && it.summary == failure.summary
                } == true
            ) {
                existing.drop(1)
            } else {
                existing
            }
            val updated = (listOf(failure) + deduped).take(MAX_RECORDS)
            prefs[KEY_RECORDS] = json.encodeToString(DiagnosticsFailureRecords(updated))
        }
    }

    /** 清空失败记录（用户显式操作；不影响任何业务数据）。 */
    suspend fun clear() {
        dataStore.edit { prefs -> prefs.remove(KEY_RECORDS) }
    }

    /** 释放本存储的 DataStore 作用域（测试/退出用）。 */
    fun close() {
        scope.cancel()
    }

    private suspend fun readRaw(): String? = try {
        dataStore.data.first()[KEY_RECORDS]
    } catch (e: IOException) {
        null
    }

    private fun parse(raw: String?): DiagnosticsFailureRecords {
        raw ?: return DiagnosticsFailureRecords()
        return runCatching { json.decodeFromString<DiagnosticsFailureRecords>(raw) }
            .getOrDefault(DiagnosticsFailureRecords())
    }

    companion object {
        /** 保留的最大失败条数（只为排查最近故障，不做长期日志）。 */
        const val MAX_RECORDS = 50

        private val KEY_RECORDS = stringPreferencesKey("diagnostics_failures_json")
    }
}
