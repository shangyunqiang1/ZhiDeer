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
 * - [MANUAL] 手动录入（记忆页面的显式保存）；
 * - [CHAT] 聊天确认（T28/#29 纯新增）：用户在聊天中明确要求记住并确认操作提案后保存；
 *   未经提案确认的聊天内容绝不写入（聊天历史本身不等于长期记忆）。
 * 枚举封闭保证不会伪造不存在的来源（issue #27：不伪造已不存在的来源）。
 */
enum class MemorySource(val label: String) {
    MANUAL("手动录入"),
    CHAT("聊天确认"),
}

/**
 * 记忆来源引用的记录类型（T27/#28）：记忆内容所依据的原始记录属于哪类普通事项。
 * 来源删除边界只作用于普通事项（GLOSSARY：回收站范畴的待办/日程/便签）；
 * 聊天来源由后续任务接入时扩展枚举，枚举封闭保证不会伪造不存在的来源。
 */
enum class MemoryRefType(val label: String) {
    TASK("待办"),
    SCHEDULE("日程"),
    NOTE("便签"),
}

/**
 * 记忆来源引用（T27/#28，GLOSSARY：长期记忆具有可追溯的来源）：
 * 指向记忆内容所依据的一条原始记录；为空表示无来源引用（如纯手动录入）。
 * - [missing] 来源记录已被删除（进入回收站）时置真，由删除联动自动停用记忆；
 *   来源恢复时清除该标记，但不自动重新启用记忆（恢复不自动启用）；
 * - 记忆与来源是引用关系而非从属关系：删除记忆不触碰来源原文，
 *   来源彻底删除时才级联清理关联记忆（issue #28：清理关联记忆和检索索引）。
 */
data class MemorySourceRef(
    val type: MemoryRefType,
    val id: String,
    val missing: Boolean = false,
)

/**
 * 长期记忆：用户显式保存并确认的偏好或事实，独立于便签与待办的隐私数据。
 * - [content] 保存用户原文，不自动扩写或改写；
 * - [confirmed] 确认状态：只有显式保存并确认的内容才是长期记忆；
 * - [enabled] 停用状态：停用是可逆的软禁用，停用内容不进入可用记忆集合；
 * - [verifiedAt] 验证时间：用户最近一次确认内容仍然有效的时刻；
 * - [expiresAt] 有效期：为空表示长期有效，过期内容不进入可用记忆集合；
 * - [sourceRef] 来源引用：指向记忆依据的原始记录（可空），来源删除联动见 [MemorySourceRef]；
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
    val sourceRef: MemorySourceRef? = null,
)
