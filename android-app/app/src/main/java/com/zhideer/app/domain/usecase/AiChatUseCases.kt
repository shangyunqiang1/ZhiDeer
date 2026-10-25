package com.zhideer.app.domain.usecase

import com.zhideer.app.data.ai.AiChatException
import com.zhideer.app.data.ai.AiConfigStore
import com.zhideer.app.data.ai.AiSessionStore
import com.zhideer.app.data.ai.DeepSeekChatClient
import com.zhideer.app.domain.model.AiChatMessage
import com.zhideer.app.domain.model.AiChatRole
import com.zhideer.app.domain.model.AiChatSession
import com.zhideer.app.domain.model.AiConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** AI 配置编辑输入；[apiKey] 为空表示保留已保存的 Key（便于只改模型或 Base URL）。 */
data class AiConfigInput(
    val apiKey: String,
    val model: String,
    val baseUrl: String,
)

/** 面向 UI 的配置视图：只含掩码后的 Key，绝不携带明文。 */
data class AiConfigView(
    val model: String,
    val baseUrl: String,
    val hasApiKey: Boolean,
    val maskedApiKey: String?,
)

/**
 * 统一 AI 对话用例入口（T17/#18）：
 * - [saveConfig]/[loadConfig]/[testConnection]：配置 API Key（加密存储）、模型标识与 Base URL；
 * - [sendStreaming]/[cancelGeneration]：多轮流式对话与取消；
 * - [listSessions]/[openSession]/[deleteSession]：本地会话历史（DataStore，避开 Room）。
 *
 * 边界约定（对应用户故事验收）：
 * - 请求体只包含会话内消息；在上下文授权任务（#19/T18）完成前，不上传本地事项、长期记忆或其他历史；
 * - 取消生成只停止接收：不保存部分回复，不产生任何业务写入，已发送的用户消息保留；
 * - 所有失败以中文可诊断提示返回，不把英文裸异常抛给 UI。
 */
class AiChatUseCases(
    private val configStore: AiConfigStore,
    private val sessionStore: AiSessionStore,
    private val client: DeepSeekChatClient,
    private val idGen: () -> String,
    private val now: () -> Long,
) {

    @Volatile
    private var activeJob: Job? = null
    // ---------- 配置 ----------

    suspend fun saveConfig(input: AiConfigInput): Result<AiConfigView> {
        val model = input.model.trim()
        if (model.isEmpty()) return Result.failure(IllegalArgumentException("模型标识不能为空"))
        val baseUrl = input.baseUrl.trim().trimEnd('/')
        if (baseUrl.isEmpty()) return Result.failure(IllegalArgumentException("Base URL 不能为空"))
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            return Result.failure(IllegalArgumentException("Base URL 需以 http:// 或 https:// 开头"))
        }
        val apiKey = input.apiKey.trim().ifBlank { configStore.load()?.apiKey.orEmpty() }
        val config = AiConfig(apiKey = apiKey, model = model, baseUrl = baseUrl)
        configStore.save(config)
        return Result.success(viewOf(config))
    }

    suspend fun loadConfig(): AiConfigView? = configStore.load()?.let { viewOf(it) }

    /** 测试连接：发送一条极短的非流式补全，成功或失败都返回中文结果。 */
    suspend fun testConnection(): Result<String> {
        val config = configStore.load()
            ?: return Result.failure(IllegalStateException("请先在设置中配置 API Key、模型标识和 Base URL"))
        return try {
            client.chatCompletion(config, listOf(AiChatMessage(AiChatRole.SYSTEM, "你是连通性测试端点。")))
            Result.success("连接成功：模型 ${config.model} 可用")
        } catch (e: AiChatException) {
            Result.failure(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(AiChatException("连接测试失败，请稍后重试。（${e.javaClass.simpleName}）", e))
        }
    }

    // ---------- 流式对话 ----------

    /**
     * 发送用户消息并流式接收回复。
     * - [sessionId] 为 null 时新建会话，否则在既有会话上追加；
     * - [onDelta] 每收到一个增量文本回调一次（增量，非全量）；
     * - 用户消息在请求前即保存（用户已确认的本地写入）；回复只在流以 [DONE] 正常结束后保存；
     * - 通过 [cancelGeneration] 取消：不保存部分回复，返回失败结果，用户消息保留。
     */
    suspend fun sendStreaming(
        sessionId: String?,
        userText: String,
        onDelta: (String) -> Unit,
    ): Result<AiChatSession> = coroutineScope {
        val text = userText.trim()
        if (text.isEmpty()) {
            return@coroutineScope Result.failure(IllegalArgumentException("消息内容不能为空"))
        }
        if (activeJob?.isActive == true) {
            return@coroutineScope Result.failure(IllegalStateException("已有回复正在生成，请先停止或等待完成"))
        }
        val config = configStore.load()
            ?: return@coroutineScope Result.failure(IllegalStateException("请先在设置中配置 API Key、模型标识和 Base URL"))

        val sessions = sessionStore.load()
        val existing = sessionId?.let { id -> sessions.find { it.id == id } }
        if (sessionId != null && existing == null) {
            return@coroutineScope Result.failure(IllegalStateException("会话不存在或已删除"))
        }

        val createdAt = now()
        val target = existing ?: AiChatSession(
            id = idGen(),
            title = deriveTitle(text),
            messages = emptyList(),
            createdAt = createdAt,
            updatedAt = createdAt,
        )
        val withUser = target.copy(
            messages = target.messages + AiChatMessage(role = AiChatRole.USER, content = text),
            updatedAt = now(),
        )
        // 用户消息是已确认的本地写入，请求前落盘（非业务事项写入，不涉及操作提案）
        persistSession(withUser)

        val deferred = CompletableDeferred<Result<AiChatSession>>()
        val job = launch {
            try {
                val reply = StringBuilder()
                // 请求体只包含会话内消息
                client.streamChat(config, withUser.messages).collect { delta ->
                    reply.append(delta)
                    onDelta(delta)
                }
                // 生成期间会话列表可能变化：重读后合并，避免覆盖其他会话或用户删除
                val fresh = sessionStore.load()
                val current = fresh.find { it.id == withUser.id }
                if (current == null) {
                    deferred.complete(Result.failure(AiChatException("会话在生成期间被删除，回复未保存")))
                } else {
                    val completed = current.copy(
                        messages = current.messages + AiChatMessage(role = AiChatRole.ASSISTANT, content = reply.toString()),
                        updatedAt = now(),
                    )
                    sessionStore.save(fresh.map { if (it.id == completed.id) completed else it })
                    deferred.complete(Result.success(completed))
                }
            } catch (e: CancellationException) {
                deferred.complete(Result.failure(AiChatException(CANCELLED_MESSAGE)))
                throw e
            } catch (e: AiChatException) {
                deferred.complete(Result.failure(e))
            } catch (e: Throwable) {
                deferred.complete(Result.failure(AiChatException("AI 对话出现意外错误，请稍后重试。（${e.javaClass.simpleName}）", e)))
            }
        }
        activeJob = job
        try {
            job.join()
        } finally {
            if (activeJob === job) activeJob = null
        }
        // 任务在真正启动前就被取消时，deferred 可能尚未完成
        if (!deferred.isCompleted) {
            deferred.complete(Result.failure(AiChatException(CANCELLED_MESSAGE)))
        }
        deferred.await()
    }

    /** 取消当前正在进行的生成；无进行中任务时为空操作。 */
    fun cancelGeneration() {
        activeJob?.cancel()
    }

    // ---------- 会话历史 ----------

    /** 全部会话，按更新时间倒序；每次历史变化都会发出最新列表。 */
    fun listSessions(): Flow<List<AiChatSession>> =
        sessionStore.observe().map { sessions -> sessions.sortedByDescending { it.updatedAt } }

    suspend fun openSession(id: String): AiChatSession? =
        sessionStore.load().find { it.id == id }

    suspend fun deleteSession(id: String): Result<Unit> {
        val sessions = sessionStore.load()
        if (sessions.none { it.id == id }) {
            return Result.failure(IllegalStateException("会话不存在或已删除"))
        }
        sessionStore.save(sessions.filterNot { it.id == id })
        return Result.success(Unit)
    }

    // ---------- 内部 ----------

    private suspend fun persistSession(session: AiChatSession) {
        val sessions = sessionStore.load()
        sessionStore.save(sessions.filterNot { it.id == session.id } + session)
    }

    private fun viewOf(config: AiConfig): AiConfigView = AiConfigView(
        model = config.model,
        baseUrl = config.baseUrl,
        hasApiKey = config.apiKey.isNotBlank(),
        maskedApiKey = maskApiKey(config.apiKey),
    )

    private fun deriveTitle(firstUserMessage: String): String {
        val trimmed = firstUserMessage.trim()
        return if (trimmed.length > TITLE_N) trimmed.take(TITLE_N) + "…" else trimmed
    }

    private companion object {
        const val TITLE_N = 12
        const val CANCELLED_MESSAGE = "已取消生成：未保存部分回复，已发送的消息保留。"
    }
}

/** 掩码显示：保留前 4 与后 4 位，中间以 **** 遮蔽；过短则整体遮蔽。 */
fun maskApiKey(key: String): String? {
    val trimmed = key.trim()
    if (trimmed.isEmpty()) return null
    return if (trimmed.length > 8) trimmed.take(4) + "****" + trimmed.takeLast(4) else "****"
}
