package com.zhideer.app.domain.usecase

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AI 上下文授权类别（T18/#19）：发送给模型的"当前消息以外"的内容分类。
 * 各类别可独立开关（用户故事 63）；关闭的类别绝不外发（用户故事 64）。
 */
enum class AiContextCategory(val label: String, val scopeSummary: String) {
    /** 当前会话中此前的对话消息（不含其他会话；当前这条消息始终发送）。 */
    SESSION_HISTORY("会话历史", "当前会话中此前的对话消息"),

    /** 已过期未完成、今日到期及未来七天的待办（GLOSSARY：待办；不含回收站）。 */
    TASKS("待办", "已过期未完成、今日到期及未来七天的待办"),

    /** 今天起未来七天内的日程（GLOSSARY：日程；不含回收站）。 */
    SCHEDULE("日程", "今天起未来七天内的日程"),

    /** 最近保存的便签（GLOSSARY：便签；不含回收站）。 */
    NOTES("便签", "最近保存的便签（不含回收站）"),

    /**
     * 已确认保存的长期记忆（T28/#29 纯新增；GLOSSARY：长期记忆）。
     * 记忆是独立隐私数据，默认关闭：仅在用户主动勾选后，最近更新的活动记忆
     * （已确认、未停用、未过期，上限少量条数）才随请求外发。
     */
    MEMORY("记忆", "已确认保存的长期记忆偏好与事实"),
}

/**
 * 一次授权的快照：用户确认提供的上下文类别集合。
 * 空集合不构成有效授权（[isEmpty] 为 true 时视同未授权）。
 */
data class AiContextGrant(val categories: Set<AiContextCategory>) {
    fun contains(category: AiContextCategory): Boolean = category in categories
    val isEmpty: Boolean get() = categories.isEmpty()
}

/**
 * 会话级授权状态（T18/#19）：
 * - 纯内存保存，进程退出即失效：用户每次打开应用需重新授权（显式授权门，不持久化）；
 * - 授权、调整类别、撤销均立即生效于下一次请求；已发出的在途请求无法撤回，
 *   与 T17「取消生成只停止接收」的边界一致；
 * - 未授权（null）时 [AiChatUseCases.sendWithAuthorizedContext] 拒绝发送。
 */
class AiContextAuthorizer {

    private val state = MutableStateFlow<AiContextGrant?>(null)

    /** 授权状态流：UI 观察（徽标、面板）用。 */
    val grant: StateFlow<AiContextGrant?> = state.asStateFlow()

    /** 当前授权；null 表示未授权。 */
    fun currentGrant(): AiContextGrant? = state.value

    /** 应用授权（整体确认；各类别已在集合中独立勾选）。空集合视同撤销。 */
    fun grant(categories: Set<AiContextCategory>) {
        state.value = if (categories.isEmpty()) null else AiContextGrant(categories)
    }

    /** 撤销全部授权：下一次请求恢复为不含任何本地数据。 */
    fun revoke() {
        state.value = null
    }
}
