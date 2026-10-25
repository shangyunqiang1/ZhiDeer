package com.zhideer.app.data.ai

import com.zhideer.app.domain.model.AiChatMessage
import com.zhideer.app.domain.model.AiConfig
import com.zhideer.app.domain.model.AiChatRole
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.utils.io.readLineStrict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * AI 对话业务异常：[message] 一律为面向用户的中文可诊断提示，不得包含 API Key 或英文裸异常。
 */
class AiChatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * DeepSeek 客户端（OpenAI 兼容协议，T17/#18）：
 * - POST {base}/chat/completions，Bearer 鉴权；
 * - 支持非流式补全与 stream=true 的 SSE 流式回复（data: 行 + [DONE] 结束）；
 * - 所有失败路径都映射为中文可诊断提示（无法连接/鉴权失败/4xx/5xx/超时/流中断）；
 * - 不打任何请求/响应日志，避免 API Key 泄露。
 *
 * @param connectTimeoutMs 连接超时（生产 15s）
 * @param readTimeoutMs 读取超时（生产 120s；流式期间无新数据才触发）
 */
class DeepSeekChatClient(
    private val connectTimeoutMs: Long = 15_000,
    private val readTimeoutMs: Long = 120_000,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // OkHttp 引擎连接按需建立；client 本身构造轻量，直接持有便于 close
    private val httpClient: HttpClient = HttpClient(OkHttp) {
        expectSuccess = false // 状态码自行判断，避免英文裸异常进入 UI
        engine {
            config {
                connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
                writeTimeout(30_000, TimeUnit.MILLISECONDS)
                callTimeout(0, TimeUnit.MILLISECONDS) // 流式回复不限制总时长
            }
        }
    }

    /** 释放底层资源；主要供测试收尾使用。 */
    fun close() {
        httpClient.close()
    }

    /**
     * 非流式补全（连接测试用）：返回助手回复文本。
     * 失败时抛 [AiChatException]，message 为中文提示。
     */
    suspend fun chatCompletion(
        config: AiConfig,
        messages: List<AiChatMessage>,
        maxTokens: Int = 16,
    ): String {
        return try {
            val response = httpClient.post(endpointUrl(config.baseUrl)) {
                header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(ChatCompletionRequest(
                    model = config.model,
                    messages = messages.map { it.toRequestMessage() },
                    stream = false,
                    maxTokens = maxTokens,
                )))
            }
            if (response.status != HttpStatusCode.OK) {
                throw mapHttpError(response.status, response.bodyAsText())
            }
            val parsed = try {
                json.decodeFromString<ChatCompletionResponse>(response.bodyAsText())
            } catch (e: SerializationException) {
                throw AiChatException("AI 服务返回了无法解析的数据，请检查模型标识是否正确后重试。")
            }
            parsed.choices.firstOrNull()?.message?.content?.takeIf { it.isNotBlank() }
                ?: throw AiChatException("AI 服务返回了空回复，请稍后重试。")
        } catch (e: AiChatException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw mapConnectionPhase(e)
        }
    }

    /**
     * 流式补全：冷 Flow，逐块产出增量文本（delta）。
     * - 请求体只包含调用方传入的会话内消息；
     * - 以 [DONE] 为正常结束标记，连接提前结束视为流中断；
     * - 收集方取消时停止接收并关闭连接。
     */
    fun streamChat(config: AiConfig, messages: List<AiChatMessage>): Flow<String> = flow {
        val body = json.encodeToString(ChatCompletionRequest(
            model = config.model,
            messages = messages.map { it.toRequestMessage() },
            stream = true,
            maxTokens = null,
        ))
        var connected = false
        try {
            httpClient.preparePost(endpointUrl(config.baseUrl)) {
                header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
                contentType(ContentType.Application.Json)
                setBody(body)
            }.execute { response ->
                if (response.status != HttpStatusCode.OK) {
                    throw mapHttpError(response.status, response.bodyAsText())
                }
                connected = true
                val channel = response.bodyAsChannel()
                var sawDone = false
                while (!channel.isClosedForRead) {
                    val line = channel.readLineStrict() ?: break
                    val trimmed = line.trim()
                    // SSE 注释、事件名与心跳行直接忽略，只处理 data: 载荷
                    if (trimmed.isEmpty() || trimmed.startsWith(":") || !trimmed.startsWith("data:")) continue
                    val payload = trimmed.removePrefix("data:").trim()
                    if (payload == DONE_MARKER) {
                        sawDone = true
                        break
                    }
                    if (payload.isEmpty()) continue
                    val chunk = try {
                        json.decodeFromString<StreamChunk>(payload)
                    } catch (e: SerializationException) {
                        throw AiChatException("AI 服务返回了无法解析的数据，流式回复中止，请重试。")
                    }
                    val delta = chunk.choices.firstOrNull()?.delta?.content
                    if (!delta.isNullOrEmpty()) emit(delta)
                }
                if (!sawDone) {
                    throw AiChatException("流式响应中断：与 AI 服务的连接提前结束，本次回复不完整，请重试。")
                }
            }
        } catch (e: AiChatException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 连接建立前与建立后的失败语义不同：前者是“无法连接”，后者是“流中断/超时”
            throw if (connected) mapStreamPhase(e) else mapConnectionPhase(e)
        }
    }.flowOn(Dispatchers.IO)

    // ---------- 内部实现 ----------

    private fun endpointUrl(baseUrl: String): String = baseUrl.trim().trimEnd('/') + PATH_CHAT_COMPLETIONS

    private fun AiChatMessage.toRequestMessage() = RequestMessage(role = role.wireName(), content = content)

    private fun AiChatRole.wireName(): String = when (this) {
        AiChatRole.USER -> "user"
        AiChatRole.ASSISTANT -> "assistant"
        AiChatRole.SYSTEM -> "system"
    }

    /** 把异常链映射为连接阶段的中文提示。 */
    private fun mapConnectionPhase(e: Throwable): AiChatException {
        val chain = e.causeChain()
        return when {
            chain.any { it is SocketTimeoutException } ->
                AiChatException("连接超时：无法在限定时间内连上 AI 服务，请检查网络与 Base URL。", e)
            chain.any { it is UnknownHostException } ->
                AiChatException("无法连接到 AI 服务：服务地址无法解析，请检查 Base URL 与网络。", e)
            chain.any { it is ConnectException } ->
                AiChatException("无法连接到 AI 服务：连接被拒绝，请检查 Base URL 与网络。", e)
            chain.any { it is SSLException } ->
                AiChatException("与 AI 服务建立安全连接（HTTPS）失败，请检查 Base URL 与网络环境。", e)
            chain.any { it is IOException } ->
                AiChatException("无法连接到 AI 服务，请检查网络连接与 Base URL 是否正确。（${e.javaClass.simpleName}）", e)
            else ->
                AiChatException("AI 对话出现意外错误，请稍后重试。（${e.javaClass.simpleName}）", e)
        }
    }

    /** 把异常链映射为流式阶段（已建立连接）的中文提示。 */
    private fun mapStreamPhase(e: Throwable): AiChatException {
        val chain = e.causeChain()
        return when {
            chain.any { it is SocketTimeoutException } ->
                AiChatException("请求超时：AI 服务长时间未响应，请检查网络后重试。", e)
            chain.any { it is IOException } ->
                AiChatException("流式响应中断：与 AI 服务的连接提前断开，本次回复不完整，请重试。（${e.javaClass.simpleName}）", e)
            else ->
                AiChatException("AI 对话出现意外错误，请稍后重试。（${e.javaClass.simpleName}）", e)
        }
    }

    private fun Throwable.causeChain(): List<Throwable> =
        generateSequence<Throwable>(this) { t -> t.cause?.takeIf { c -> c !== t } }.toList()

    private fun mapHttpError(status: HttpStatusCode, rawBody: String): AiChatException {
        val code = status.value
        val snippet = rawBody.replace(Regex("\\s+"), " ").trim().take(200)
        val detail = if (snippet.isEmpty()) "" else "服务返回：$snippet"
        return AiChatException(
            when {
                code == 401 || code == 403 ->
                    "AI 服务鉴权失败（HTTP $code）：API Key 无效、未授权或已过期，请在设置中检查 API Key。$detail"
                code == 429 ->
                    "AI 服务请求过于频繁（HTTP 429）：请稍后再试。$detail"
                code in 400..499 ->
                    "AI 服务拒绝了本次请求（HTTP $code）：请检查模型标识与 Base URL 是否正确。$detail"
                code in 500..599 ->
                    "AI 服务暂时不可用（HTTP $code）：请稍后重试。$detail"
                else ->
                    "AI 服务返回异常状态（HTTP $code）：$detail"
            },
        )
    }

    private companion object {
        const val PATH_CHAT_COMPLETIONS = "/chat/completions"
        const val DONE_MARKER = "[DONE]"
    }
}

// ---------- OpenAI 兼容协议 DTO（仅本文件使用） ----------

@Serializable
private data class ChatCompletionRequest(
    val model: String,
    val messages: List<RequestMessage>,
    val stream: Boolean = false,
    @SerialName("max_tokens") val maxTokens: Int? = null,
)

@Serializable
private data class RequestMessage(val role: String, val content: String)

@Serializable
private data class ChatCompletionResponse(val choices: List<ChoiceMessage> = emptyList())

@Serializable
private data class ChoiceMessage(val message: ResponseMessage? = null)

@Serializable
private data class ResponseMessage(val content: String? = null)

@Serializable
private data class StreamChunk(val choices: List<StreamChoice> = emptyList())

@Serializable
private data class StreamChoice(val delta: StreamDelta? = null)

@Serializable
private data class StreamDelta(val content: String? = null)
