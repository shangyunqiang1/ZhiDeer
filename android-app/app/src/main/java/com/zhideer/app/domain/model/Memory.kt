package com.zhideer.app.domain.model

/**
 * 长期记忆类型（GLOSSARY：长期记忆——用户明确要求记住并确认保存的偏好或事实）。
 * - [PREFERENCE] 偏好：用户的习惯、喜好与倾向；
 * - [FACT] 事实：关于用户或其环境的客观信息。
 * 类型只在用户显式保存时由用户给定，不自动推断（issue #27：敏感属性不自动推断）。
 */
enum class MemoryType(val label: String) {
    PREFERENCE("偏好"),
    FACT("事实"),
}

/**
 * 长期记忆来源（GLOSSARY：长期记忆具有可追溯的来源）。
 * 本任务仅开放手动录入入口；便签与会话来源由后续任务接入时扩展枚举，
 * 枚举封闭保证不会伪造不存在的来源（issue #27：不伪造已不存在的来源）。
 */
enum class MemorySource(val label: String) {
    MANUAL("手动录入"),
}

/**
 * 长期记忆：用户显式保存并确认的偏好或事实，独立于便签与待办的隐私数据。
 * - [content] 保存用户原文，不自动扩写或改写；
 * - [confirmed] 确认状态：只有显式保存并确认的内容才是长期记忆；
 * - [enabled] 停用状态：停用是可逆的软禁用，停用内容不进入可用记忆集合；
 * - [verifiedAt] 验证时间：用户最近一次确认内容仍然有效的时刻；
 * - [expiresAt] 有效期：为空表示长期有效，过期内容不进入可用记忆集合；
 * - 删除是彻底删除：不进入回收站，不适用普通事项三十天保留规则（#17：记忆与
 *   隐私数据不误套普通事项三十天保留规则）。
 */
data class Memory(
    val id: String,
    val content: String,
    val type: MemoryType = MemoryType.FACT,
    val source: MemorySource = MemorySource.MANUAL,
    val confirmed: Boolean = true,
    val enabled: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
    val verifiedAt: Long,
    val expiresAt: Long? = null,
)
