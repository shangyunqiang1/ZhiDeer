package com.zhideer.app.data.ai

import androidx.datastore.core.DataStore
import androidx.datastore.core.Storage
import androidx.datastore.core.createSingleProcessCoordinator
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.zhideer.app.domain.model.AiChatMessage
import com.zhideer.app.domain.model.AiChatRole
import com.zhideer.app.domain.model.AiChatSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.FileHandle
import okio.FileMetadata
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Sink
import okio.Source
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.CoroutineContext

/**
 * AI 聊天会话历史存储（T17/#18）：DataStore Preferences 中保存 JSON 数组。
 * 有意避开 Room：其他并行分支在调整 Room schema，会话历史不属于业务事项库。
 *
 * 自定义 [Storage]（[ReliableFileSystem]）：DataStore 默认 JVM 存储在 Windows 上
 * 重命名目标文件被短暂占用（如安全软件扫描）时直接失败且不重试、不保留原始异常；
 * 这里对原子重命名做有限重试并抛出原始异常，保证桌面 JVM（含单元测试）与 Android 一致可用。
 */
class AiSessionStore(
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

    suspend fun load(): List<AiChatSession> = parse(readRaw())

    /** 观察会话列表：DataStore 每次写入后自动发出最新列表。 */
    fun observe(): Flow<List<AiChatSession>> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> parse(prefs[KEY_SESSIONS]) }

    suspend fun save(sessions: List<AiChatSession>) {
        dataStore.edit { prefs ->
            prefs[KEY_SESSIONS] = json.encodeToString(sessions.map { it.toDto() })
        }
    }

    fun close() {
        scope.cancel()
    }

    private suspend fun readRaw(): String? = try {
        dataStore.data.first()[KEY_SESSIONS]
    } catch (e: IOException) {
        null
    }

    private fun parse(raw: String?): List<AiChatSession> {
        raw ?: return emptyList()
        // 历史数据损坏时返回空列表而不是让整个应用崩溃
        return runCatching { json.decodeFromString<List<SessionDto>>(raw) }.getOrDefault(emptyList())
            .map { it.toDomain() }
    }

    private fun SessionDto.toDomain(): AiChatSession = AiChatSession(
        id = id,
        title = title,
        messages = messages.map { AiChatMessage(role = AiChatRole.valueOf(it.role), content = it.content) },
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun AiChatSession.toDto(): SessionDto = SessionDto(
        id = id,
        title = title,
        messages = messages.map { MessageDto(role = it.role.name, content = it.content) },
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    @Serializable
    private data class SessionDto(
        val id: String,
        val title: String,
        val messages: List<MessageDto>,
        val createdAt: Long,
        val updatedAt: Long,
    )

    @Serializable
    private data class MessageDto(
        val role: String,
        val content: String,
    )

    private companion object {
        val KEY_SESSIONS = stringPreferencesKey("ai_chat_sessions_json")
    }
}

/**
 * 代理系统文件系统，仅覆盖 atomicMove：
 * - 优先 REPLACE_EXISTING + ATOMIC_MOVE；不支持原子移动时退回 REPLACE_EXISTING；
 * - 目标被短暂占用导致失败时小步重试，最终失败抛出原始异常（便于诊断）。
 * Windows 上 DataStore 默认实现此处失败会吞掉原因，Android/Linux 的 rename 本身可覆盖，不受影响。
 * internal：T19/#20 的 AiProposalStore 复用同一文件系统策略（仍仅限 data.ai 包内）。
 */
internal object ReliableFileSystem : FileSystem() {
    private const val MOVE_ATTEMPTS = 8
    private const val MOVE_RETRY_DELAY_MS = 50L

    override fun atomicMove(source: Path, target: Path) {
        val sourceFile = source.toFile().toPath()
        val targetFile = target.toFile().toPath()
        var lastError: IOException? = null
        repeat(MOVE_ATTEMPTS) { attempt ->
            try {
                try {
                    Files.move(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    return
                } catch (e: AtomicMoveNotSupportedException) {
                    Files.move(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING)
                    return
                }
            } catch (e: IOException) {
                lastError = e
                if (attempt < MOVE_ATTEMPTS - 1) Thread.sleep(MOVE_RETRY_DELAY_MS)
            }
        }
        throw lastError ?: IOException("原子重命名失败：$source -> $target")
    }

    override fun canonicalize(path: Path): Path = FileSystem.SYSTEM.canonicalize(path)
    override fun metadataOrNull(path: Path): FileMetadata? = FileSystem.SYSTEM.metadataOrNull(path)
    override fun list(dir: Path): List<Path> = FileSystem.SYSTEM.list(dir)
    override fun listOrNull(dir: Path): List<Path>? = FileSystem.SYSTEM.listOrNull(dir)
    override fun openReadOnly(file: Path): FileHandle = FileSystem.SYSTEM.openReadOnly(file)
    override fun openReadWrite(file: Path, mustCreate: Boolean, mustAppend: Boolean): FileHandle =
        FileSystem.SYSTEM.openReadWrite(file, mustCreate, mustAppend)
    override fun source(file: Path): Source = FileSystem.SYSTEM.source(file)
    override fun sink(file: Path, mustCreate: Boolean): Sink = FileSystem.SYSTEM.sink(file, mustCreate)
    override fun appendingSink(file: Path, mustExist: Boolean): Sink = FileSystem.SYSTEM.appendingSink(file, mustExist)
    override fun createDirectory(dir: Path, mustCreate: Boolean) = FileSystem.SYSTEM.createDirectory(dir, mustCreate)
    override fun delete(path: Path, mustExist: Boolean) = FileSystem.SYSTEM.delete(path, mustExist)
    override fun createSymlink(source: Path, target: Path) = FileSystem.SYSTEM.createSymlink(source, target)

    // FileSystem.SYSTEM 为进程级单例，包装器不持有资源
    override fun close() = Unit
}
