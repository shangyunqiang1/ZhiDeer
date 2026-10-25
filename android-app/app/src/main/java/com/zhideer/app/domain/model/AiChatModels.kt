package com.zhideer.app.domain.model

import kotlinx.serialization.Serializable

/**
 * AI 对话（T17/#18）领域模型。
 * 会话历史仅为本机聊天记录，不属于长期记忆：长期记忆须用户明确要求并确认保存（见 GLOSSARY）。
 * 在上下文授权任务（#19/T18）完成前，请求体只包含会话内消息。
 */

/** 消息角色。 */
@Serializable
enum class AiChatRole { USER, ASSISTANT, SYSTEM }

/** 会话内一条消息。 */
@Serializable
data class AiChatMessage(
    val role: AiChatRole,
    val content: String,
)

/** 一个聊天会话；标题为首条用户消息前 N 字。 */
@Serializable
data class AiChatSession(
    val id: String,
    val title: String,
    val messages: List<AiChatMessage>,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * AI 服务连接配置。[apiKey] 为明文，仅保存在本机加密存储（EncryptedSharedPreferences，AndroidKeystore 主密钥），
 * 绝不写入日志。[toString] 已遮蔽 Key，避免意外泄露。
 */
data class AiConfig(
    val apiKey: String,
    val model: String,
    val baseUrl: String,
) {
    override fun toString(): String =
        "AiConfig(apiKey=${if (apiKey.isBlank()) "" else "****"}, model=$model, baseUrl=$baseUrl)"
}
