package com.zhideer.app.domain.usecase

import com.zhideer.app.data.ai.AiChatException
import com.zhideer.app.data.ai.AiConfigStore
import com.zhideer.app.data.ai.AiSessionStore
import com.zhideer.app.data.ai.DeepSeekChatClient
import com.zhideer.app.domain.model.AiChatMessage
import com.zhideer.app.domain.model.AiChatRole
import com.zhideer.app.domain.model.AiChatSession
import com.zhideer.app.domain.model.AiConfig
import com.zhideer.app.domain.model.AiProposalSendReply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

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
 * 统一 AI 对话用例入口（T17/#18、T18/#19）：
 * - [saveConfig]/[loadConfig]/[testConnection]：配置 API Key（加密存储）、模型标识与 Base URL；
 * - [sendStreaming]/[cancelGeneration]：多轮流式对话与取消（请求只含会话内消息，语义不变）；
 * - [sendWithAuthorizedContext]：显式授权后把真实记录快照注入请求（#19）；
 * - [authorizeContext]/[revokeContextAuthorization]/[observeContextGrant]：会话级授权状态；
 * - [listSessions]/[openSession]/[deleteSession]：本地会话历史（DataStore，避开 Room）。
 *
 * 边界约定（对应用户故事验收）：
 * - [sendStreaming] 请求体只包含会话内消息；
 * - [sendWithAuthorizedContext] 必须先经 [AiContextAuthorizer] 显式授权：未授权拒绝发送；
 *   注入的快照只含授权类别的真实记录，未授权类别即使出现在快照中也不渲染；
 * - 快照只注入请求、不落盘、不回写业务数据（本任务只读）；
 * - 取消生成只停止接收：不保存部分回复，不产生任何业务写入，已发送的用户消息保留；
 * - 所有失败以中文可诊断提示返回，不把英文裸异常抛给 UI。
 */
class AiChatUseCases(
    private val configStore: AiConfigStore,
    private val sessionStore: AiSessionStore,
    private val client: DeepSeekChatClient,
    private val idGen: () -> String,
    private val now: () -> Long,
    private val contextAuthorizer: AiContextAuthorizer,
    private val contextProvider: AiContextProvider,
    // T19/#20 纯新增：提案协议提示注入"今天"日期与相对时间推算所用的创建时区；
    // 默认系统时区保持既有构造（T17/T18 测试）不变。
    private val zoneId: ZoneId = ZoneId.systemDefault(),
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
     * 发送用户消息并流式接收回复（T17/#18，语义不变）。
     * - [sessionId] 为 null 时新建会话，否则在既有会话上追加；
     * - [onDelta] 每收到一个增量文本回调一次（增量，非全量）；
     * - 用户消息在请求前即保存（用户已确认的本地写入）；回复只在流以 [DONE] 正常结束后保存；
     * - 通过 [cancelGeneration] 取消：不保存部分回复，返回失败结果，用户消息保留；
     * - 请求体只包含会话内消息，绝不携带本地事项数据。
     */
    suspend fun sendStreaming(
        sessionId: String?,
        userText: String,
        onDelta: (String) -> Unit,
    ): Result<AiChatSession> =
        sendInternal(sessionId, userText, onDelta, requestPrefix = emptyList(), includeHistory = true)

    /**
     * 携带授权上下文发送（T18/#19）：
     * - 必须先经 [authorizeContext] 显式授权，未授权拒绝发送且绝不发出请求；
     * - 授权后由 [AiContextProvider] 只读生成真实记录快照，渲染为 system 结构化文本注入请求：
     *   回答基于真实记录、不编造；未授权类别在 prompt 中如实说明限制与本地回退；
     * - 关闭"会话历史"类别时请求只含快照与当前消息（既有会话消息仍保存在本地）；
     * - 快照生成失败、未配置服务等错误路径均为中文可诊断提示；
     * - 保存、取消、多轮语义与 [sendStreaming] 完全一致。
     */
    suspend fun sendWithAuthorizedContext(
        sessionId: String?,
        userText: String,
        onDelta: (String) -> Unit,
    ): Result<AiChatSession> {
        val grant = contextAuthorizer.currentGrant()
            ?: return Result.failure(
                IllegalStateException("尚未授权访问本地事项：请先在「授权」面板勾选要提供的上下文范围并确认"),
            )
        val snapshot = try {
            contextProvider.loadSnapshot(grant)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return Result.failure(
                AiChatException("无法读取本地记录快照，本次未发送任何内容，请稍后重试。（${e.javaClass.simpleName}）", e),
            )
        }
        val systemMessage = AiChatMessage(role = AiChatRole.SYSTEM, content = buildAiContextPrompt(grant, snapshot))
        return sendInternal(
            sessionId,
            userText,
            onDelta,
            requestPrefix = listOf(systemMessage),
            includeHistory = grant.contains(AiContextCategory.SESSION_HISTORY),
        )
    }

    // ---------- 授权状态（会话级，T18/#19） ----------

    /** 授权状态流：UI 观察徽标与面板用。 */
    fun observeContextGrant(): StateFlow<AiContextGrant?> = contextAuthorizer.grant

    /** 当前授权；null 表示未授权。 */
    fun currentContextGrant(): AiContextGrant? = contextAuthorizer.currentGrant()

    /** 应用授权：按类别集合整体确认（各类别已在集合中独立勾选；空集合视同撤销）。 */
    fun authorizeContext(categories: Set<AiContextCategory>) = contextAuthorizer.grant(categories)

    /** 撤销全部授权：下一次请求恢复为不含任何本地数据。 */
    fun revokeContextAuthorization() = contextAuthorizer.revoke()

    // ---------- 操作提案（T19/#20，纯新增） ----------

    /**
     * 以"操作提案模式"发送（T19/#20）：
     * - 请求额外注入提案协议 system 提示（模型按约定在回复末尾输出提案 JSON 块）；
     * - 不要求、也不携带本地数据授权快照：创建提案只写不读（验收边界）；
     * - 流式结束后解析回复：无 JSON 块 → [AiProposalSendReply.PlainReply]（原文保存）；
     *   合法提案 → [AiProposalSendReply.Drafts]（会话保存人类可读摘要，条目交用例层落提案库）；
     * - 解析失败自动重试一次（非流式补全），仍失败返回中文可诊断提示，不创建半成品；
     * - 取消/中断沿用既有语义：不保存部分回复，用户消息保留，无提案产生。
     */
    suspend fun sendProposalRequest(
        sessionId: String?,
        userText: String,
        onDelta: (String) -> Unit,
    ): Result<AiProposalSendReply> {
        val today = Instant.ofEpochMilli(now()).atZone(zoneId).toLocalDate()
        val system = AiChatMessage(role = AiChatRole.SYSTEM, content = buildProposalSystemPrompt(today, zoneId))
        val reply = StringBuilder()
        val sent = sendInternal(
            sessionId,
            userText,
            onDelta = { delta ->
                reply.append(delta)
                onDelta(delta)
            },
            requestPrefix = listOf(system),
            includeHistory = true,
            saveReply = false,
        )
        // 取消/中断/网络错误直接透传（均为中文提示）：不解析、不产生提案、无业务写入
        val session = sent.getOrElse { return Result.failure(it) }
        return finishProposalReply(session, reply.toString(), retried = false)
    }

    /**
     * 回复落地：解析 → 无块保存原文 / 合法保存摘要并交出条目 / 失败且未重试过则重试一次。
     * 重试走非流式补全（二次流式增量会污染 UI 的拼接文本）。
     */
    private suspend fun finishProposalReply(
        session: AiChatSession,
        replyText: String,
        retried: Boolean,
    ): Result<AiProposalSendReply> = when (val parsed = parseProposalReply(replyText, zoneId, idGen)) {
        is AiProposalParseOutcome.Plain ->
            appendAssistantMessage(session.id, replyText).map { AiProposalSendReply.PlainReply(it) }
        is AiProposalParseOutcome.Drafts ->
            appendAssistantMessage(session.id, summarizeProposal(parsed.items, zoneId))
                .map { AiProposalSendReply.Drafts(it, parsed.items) }
        is AiProposalParseOutcome.Invalid -> {
            val reason = parsed.reason
            if (retried) {
                Result.failure(AiChatException(proposalFailureMessage(reason)))
            } else {
                retryProposalReply(session, reason)
            }
        }
    }

    /** 解析失败自动重试一次（非流式补全）；仍失败返回中文可诊断提示，本次不保存任何回复。 */
    private suspend fun retryProposalReply(
        session: AiChatSession,
        firstReason: String,
    ): Result<AiProposalSendReply> {
        val config = configStore.load()
            ?: return Result.failure(IllegalStateException("请先在设置中配置 API Key、模型标识和 Base URL"))
        val retryReply = try {
            // 会话消息已含本次用户消息（第一轮坏回复未保存），补上协议 system 提示后整轮重发
            client.chatCompletion(
                config,
                listOf(AiChatMessage(role = AiChatRole.SYSTEM, content = buildProposalSystemPrompt(todayText(), zoneId))) +
                    session.messages,
                maxTokens = PROPOSAL_RETRY_MAX_TOKENS,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return Result.failure(AiChatException(proposalFailureMessage(firstReason), e))
        }
        return finishProposalReply(session, retryReply, retried = true)
    }

    private fun todayText(): java.time.LocalDate = Instant.ofEpochMilli(now()).atZone(zoneId).toLocalDate()

    private fun proposalFailureMessage(reason: String): String =
        "无法生成操作提案：$reason 已自动重试一次仍未成功，本次未创建任何内容，请换个说法再试。"

    /**
     * 向既有会话追加一条助手消息（T19/#20 纯新增）：供提案回复摘要与确认回执写入对话流使用。
     * 生成期间删除会话等失败路径返回中文提示；不触发任何网络请求。
     */
    suspend fun appendAssistantMessage(sessionId: String, content: String): Result<AiChatSession> {
        val fresh = sessionStore.load()
        val current = fresh.find { it.id == sessionId }
            ?: return Result.failure(IllegalStateException("会话不存在或已删除，回执未保存"))
        val updated = current.copy(
            messages = current.messages + AiChatMessage(role = AiChatRole.ASSISTANT, content = content),
            updatedAt = now(),
        )
        sessionStore.save(fresh.map { if (it.id == updated.id) updated else it })
        return Result.success(updated)
    }

    // ---------- 内部发送路径（sendStreaming / sendWithAuthorizedContext 共用） ----------

    /**
     * 共享发送实现：
     * - [requestPrefix] 为注入请求头部的额外消息（授权上下文的 system 快照消息）；
     * - [includeHistory] 为 false 时请求只含前缀与当前用户消息（关闭"会话历史"类别）；
     * - 会话保存始终包含完整历史（本地记录完整），与实际请求的消息列表无关。
     */
    private suspend fun sendInternal(
        sessionId: String?,
        userText: String,
        onDelta: (String) -> Unit,
        requestPrefix: List<AiChatMessage>,
        includeHistory: Boolean,
        // T19/#20 纯新增：提案路径先解析回复再决定保存内容（摘要或原文），
        // 传 false 时流正常结束也不自动保存助手消息，由调用方追加。
        saveReply: Boolean = true,
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

        // 实际请求的消息列表：前缀（如授权快照 system 消息）+ 会话历史（可关闭）+ 当前消息
        val requestMessages = requestPrefix +
            (if (includeHistory) withUser.messages else listOf(withUser.messages.last()))

        val deferred = CompletableDeferred<Result<AiChatSession>>()
        val job = launch {
            try {
                val reply = StringBuilder()
                client.streamChat(config, requestMessages).collect { delta ->
                    reply.append(delta)
                    onDelta(delta)
                }
                // 生成期间会话列表可能变化：重读后合并，避免覆盖其他会话或用户删除
                val fresh = sessionStore.load()
                val current = fresh.find { it.id == withUser.id }
                if (current == null) {
                    deferred.complete(Result.failure(AiChatException("会话在生成期间被删除，回复未保存")))
                } else if (!saveReply) {
                    // 提案路径：助手消息由调用方解析回复后以摘要或原文追加
                    deferred.complete(Result.success(current))
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

        // T19/#20：提案重试走非流式补全，放宽输出上限以容纳完整提案 JSON
        const val PROPOSAL_RETRY_MAX_TOKENS = 2048
    }
}

/** 掩码显示：保留前 4 与后 4 位，中间以 **** 遮蔽；过短则整体遮蔽。 */
fun maskApiKey(key: String): String? {
    val trimmed = key.trim()
    if (trimmed.isEmpty()) return null
    return if (trimmed.length > 8) trimmed.take(4) + "****" + trimmed.takeLast(4) else "****"
}
