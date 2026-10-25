package com.zhideer.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.zhideer.app.domain.model.AiChatRole
import com.zhideer.app.domain.model.AiChatSession
import com.zhideer.app.domain.model.AiProposal
import com.zhideer.app.domain.model.AiProposalDeleteAction
import com.zhideer.app.domain.model.AiProposalItem
import com.zhideer.app.domain.model.AiProposalKind
import com.zhideer.app.domain.model.AiProposalOp
import com.zhideer.app.domain.model.AiProposalSendOutcome
import com.zhideer.app.domain.model.AiProposalStatus
import com.zhideer.app.domain.usecase.AiChatUseCases
import com.zhideer.app.domain.usecase.AiConfigInput
import com.zhideer.app.domain.usecase.AiContextCategory
import com.zhideer.app.domain.usecase.AiProposalUseCases
import com.zhideer.app.domain.usecase.kindCn
import com.zhideer.app.domain.usecase.memoryTypeCn
import com.zhideer.app.domain.usecase.proposalChangePreviewLines
import com.zhideer.app.domain.usecase.proposalDeleteActionText
import com.zhideer.app.domain.usecase.proposalDueText
import com.zhideer.app.domain.usecase.proposalReminderText
import com.zhideer.app.domain.usecase.proposalScheduleRangeText
import com.zhideer.app.domain.usecase.proposalTargetSummary
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val aiTimeFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/**
 * AI 助手主界面（T17/#18、T18/#19、T19/#20、T20/#21、T21/#22），公开供根导航挂载：
 * - 会话列表 + 新建会话 + 重开 + 删除；
 * - 聊天视图：多轮流式回复、发送/停止、生成中可取消；
 * - 创建模式（#20/#21/#22/#29）：开启后发送走提案路径——AI 起草待办/便签/日程的新增、修改与删除，
 *   以及「记住」类长期记忆的操作提案（T28/#29：确认前绝不写入记忆表，确认后以聊天确认来源保存），
 *   提案以卡片呈现在对话流中（完整字段预览 + 「确认创建/确认执行」「忽略」与逐项「移除」），
 *   日程卡片显示按创建时区换算的时间范围（跨日明确显示）、地点/分类/备注与提醒摘要（无提醒明确标注）；
 *   修改条目逐字段展示「旧值 → 新值」并显示选定目标快照；删除条目明确取消/删除语义
 *   （GLOSSARY：取消待办保留记录并停止提醒，不是删除）；修改/删除需读取本地数据定位目标，
 *   未授权对应类别时提示先授权；多候选时列出候选供点选（点选哪条就是哪条，未点选不可确认），
 *   匹配不到时由 AI 追问；确认前绝不写入业务数据，确认后回执含记录标识写回对话；
 * - 授权面板（#19，T28/#29 扩展记忆）：列出将随请求提供的上下文类别（会话历史/待办/日程/便签/记忆），
 *   各类别独立勾选、整体确认授权、可随时撤销；记忆默认关闭，勾选后仅活动记忆随请求外发；
 *   对话页显示"已授权范围"徽标；
 *   已授权时普通发送走 [AiChatUseCases.sendWithAuthorizedContext]，未授权回退普通发送（不含本地数据）；
 *   新增提案只写不读，不需要授权（#20）；修改/删除提案的目标定位需要对应类别授权（#22）；
 * - 配置对话框：API Key（掩码输入、可切换显示）、模型标识、Base URL、「测试连接」。
 * 全部文案为中文；错误提示来自用例层（已是中文可诊断文案）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiScreen(useCases: AiChatUseCases, proposalUseCases: AiProposalUseCases) {
    val scope = rememberCoroutineScope()
    val sessions by useCases.listSessions().collectAsState(initial = emptyList())
    // 提案（#20）：卡片渲染按当前会话过滤
    val allProposals by proposalUseCases.observeProposals().collectAsState(initial = emptyList())
    // 授权状态（#19）：会话级、内存保存；null = 未授权
    val grant by useCases.observeContextGrant().collectAsState(initial = useCases.currentContextGrant())
    var chatSessionId by remember { mutableStateOf<String?>(null) } // null=列表页；""=新会话；其他=既有会话
    var currentSession by remember { mutableStateOf<AiChatSession?>(null) }
    var input by remember { mutableStateOf("") }
    var generating by remember { mutableStateOf(false) }
    var streamingText by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showConfig by remember { mutableStateOf(false) }
    var showAuth by remember { mutableStateOf(false) }
    // #20：创建模式开关——开启时发送走提案路径（AI 起草提案，确认后创建）
    var createMode by remember { mutableStateOf(false) }
    // #20：正在确认中的提案 id（确认按钮防重）
    var confirmingId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (chatSessionId == null) "AI 助手" else (currentSession?.title ?: "新会话")) },
                navigationIcon = {
                    if (chatSessionId != null) {
                        IconButton(onClick = {
                            chatSessionId = null
                            currentSession = null
                            streamingText = null
                            error = null
                        }) { Text("←") }
                    }
                },
                actions = {
                    TextButton(onClick = { showAuth = true }) { Text("授权") }
                    TextButton(onClick = { showConfig = true }) { Text("设置") }
                },
            )
        },
        floatingActionButton = {
            if (chatSessionId == null) {
                FloatingActionButton(onClick = { chatSessionId = ""; currentSession = null; error = null }) {
                    Text("＋")
                }
            }
        },
    ) { padding ->
        if (chatSessionId == null) {
            SessionList(
                sessions = sessions,
                modifier = Modifier.fillMaxSize().padding(padding),
                onOpen = { id ->
                    error = null
                    chatSessionId = id
                    scope.launch {
                        currentSession = useCases.openSession(id)
                        if (currentSession == null) {
                            error = "会话不存在或已删除"
                            chatSessionId = null
                        }
                    }
                },
                onDelete = { id ->
                    scope.launch {
                        useCases.deleteSession(id)
                            .onFailure { e -> error = e.message }
                    }
                },
                error = error,
            )
        } else {
            ChatView(
                session = currentSession,
                streamingText = streamingText,
                generating = generating,
                input = input,
                onInputChange = { input = it; error = null },
                onSend = {
                    val text = input
                    if (text.isNotBlank() && !generating) {
                        input = ""
                        generating = true
                        streamingText = ""
                        scope.launch {
                            // 新会话传 null，由用例创建；既有会话传 id
                            val id = chatSessionId?.takeIf { it.isNotEmpty() }
                            // #20/#22：创建模式走提案路径——新增提案只写不读不需要授权；
                            // 修改/删除提案的目标定位需读取本地数据，未授权对应类别时由用例层提示先授权
                            // #19：普通模式已授权时发送携带真实记录快照，未授权回退普通发送
                            val result: Result<*> = if (createMode) {
                                proposalUseCases.sendAndIngest(id, text) { delta -> streamingText += delta }
                            } else {
                                val grantNow = useCases.currentContextGrant()
                                if (grantNow == null || grantNow.isEmpty) {
                                    useCases.sendStreaming(id, text) { delta -> streamingText += delta }
                                } else {
                                    useCases.sendWithAuthorizedContext(id, text) { delta -> streamingText += delta }
                                }
                            }
                            generating = false
                            result
                                .onSuccess { value ->
                                    streamingText = null
                                    val session = when (value) {
                                        is AiChatSession -> value
                                        is AiProposalSendOutcome.PlainReply -> value.session
                                        is AiProposalSendOutcome.ProposalCreated -> value.session
                                        else -> null
                                    }
                                    session?.let {
                                        chatSessionId = it.id
                                        currentSession = it
                                    }
                                }
                                .onFailure { e ->
                                    streamingText = null
                                    error = e.message
                                    // 取消或失败后重开一次会话，保证显示与磁盘一致
                                    id?.let { currentSession = useCases.openSession(it) }
                                }
                        }
                    }
                },
                onStop = {
                    if (generating) {
                        useCases.cancelGeneration()
                    }
                },
                grantedLabels = grant?.categories?.joinToString("、") { it.label },
                createMode = createMode,
                onCreateModeChange = { createMode = it; error = null },
                proposals = allProposals.filter { it.sessionId == (chatSessionId?.takeIf { id -> id.isNotEmpty() }) },
                proposalZone = proposalUseCases.zoneId,
                confirmingId = confirmingId,
                onConfirmProposal = { proposalId ->
                    confirmingId = proposalId
                    scope.launch {
                        val outcome = proposalUseCases.confirmProposal(proposalId)
                        confirmingId = null
                        outcome
                            .onFailure { e -> error = e.message }
                        // 回执由用例层写回会话；刷新本地会话以显示回执消息
                        chatSessionId?.let { currentSession = useCases.openSession(it) }
                    }
                },
                onDismissProposal = { proposalId ->
                    scope.launch {
                        proposalUseCases.dismissProposal(proposalId)
                            .onFailure { e -> error = e.message }
                    }
                },
                onRemoveProposalItem = { proposalId, itemId ->
                    scope.launch {
                        proposalUseCases.removeProposalItem(proposalId, itemId)
                            .onFailure { e -> error = e.message }
                    }
                },
                // T21/#22：多候选修改/删除条目的目标点选（点选哪条就是哪条，绝不静默猜测）
                onSelectProposalTarget = { proposalId, itemId, recordId ->
                    scope.launch {
                        proposalUseCases.selectProposalTarget(proposalId, itemId, recordId)
                            .onFailure { e -> error = e.message }
                    }
                },
                modifier = Modifier.fillMaxSize().padding(padding),
                error = error,
            )
        }
    }

    if (showConfig) {
        AiConfigDialog(
            useCases = useCases,
            onDismiss = { showConfig = false },
        )
    }

    if (showAuth) {
        AiContextPanel(
            useCases = useCases,
            onDismiss = { showAuth = false },
        )
    }
}

/** 会话列表：按更新时间倒序，支持重开与删除。 */
@Composable
private fun SessionList(
    sessions: List<AiChatSession>,
    modifier: Modifier = Modifier,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    error: String?,
) {
    Column(modifier) {
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (sessions.isEmpty()) {
            Text(
                "还没有会话。点右下角「＋」开始与 AI 助手对话。",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(sessions, key = { it.id }) { session ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(session.title, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${formatMillis(session.updatedAt)} · ${session.messages.size} 条消息",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = { onOpen(session.id) }) { Text("打开") }
                        TextButton(onClick = { onDelete(session.id) }) { Text("删除") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** 聊天视图：授权范围徽标 + 消息流 + 提案卡片（#20）+ 流式生成中的部分回复 + 输入行（创建模式/发送/停止）。 */
@Composable
private fun ChatView(
    session: AiChatSession?,
    streamingText: String?,
    generating: Boolean,
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    grantedLabels: String?,
    createMode: Boolean,
    onCreateModeChange: (Boolean) -> Unit,
    proposals: List<AiProposal>,
    proposalZone: ZoneId,
    confirmingId: String?,
    onConfirmProposal: (String) -> Unit,
    onDismissProposal: (String) -> Unit,
    onRemoveProposalItem: (String, String) -> Unit,
    onSelectProposalTarget: (String, String, String) -> Unit,
    modifier: Modifier = Modifier,
    error: String?,
) {
    Column(modifier) {
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        // #19：已授权范围徽标——用户随时能看到本次请求会携带哪些本地上下文
        ContextGrantBadge(grantedLabels = grantedLabels)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(session?.messages ?: emptyList(), key = { it.content.hashCode().toLong() }) { message ->
                MessageBubble(role = message.role, content = message.content)
            }
            // #20：本会话的操作提案卡片（确认前绝不写入业务数据）
            items(proposals, key = { "proposal-${it.id}" }) { proposal ->
                ProposalCard(
                    proposal = proposal,
                    zoneId = proposalZone,
                    busy = confirmingId == proposal.id,
                    onConfirm = { onConfirmProposal(proposal.id) },
                    onDismiss = { onDismissProposal(proposal.id) },
                    onRemoveItem = { itemId -> onRemoveProposalItem(proposal.id, itemId) },
                    onSelectTarget = { itemId, recordId ->
                        onSelectProposalTarget(proposal.id, itemId, recordId)
                    },
                )
            }
            if (generating && streamingText != null) {
                item(key = "streaming") {
                    MessageBubble(
                        role = AiChatRole.ASSISTANT,
                        content = streamingText.ifEmpty { "正在生成…" },
                    )
                }
            }
        }
        // #20/#21/#22：创建模式开关——开启后 AI 把一句话起草为新增/修改/删除操作提案，确认后才执行
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        ) {
            Checkbox(checked = createMode, onCheckedChange = onCreateModeChange)
            Text(
                "创建模式：让 AI 起草新增/修改/删除提案（修改与删除需授权对应类别，确认后才会执行）",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                label = { Text("输入消息") },
                modifier = Modifier.weight(1f),
                enabled = !generating,
            )
            if (generating) {
                Button(onClick = onStop, modifier = Modifier.padding(start = 8.dp)) {
                    Text("停止")
                }
            } else {
                Button(onClick = onSend, modifier = Modifier.padding(start = 8.dp)) {
                    Text("发送")
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(role: AiChatRole, content: String) {
    val isUser = role == AiChatRole.USER
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .padding(4.dp),
        ) {
            Text(
                if (isUser) "我" else "助手",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(content, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** 配置对话框：API Key 掩码输入（可切换显示）、模型、Base URL、测试连接。 */
@Composable
private fun AiConfigDialog(
    useCases: AiChatUseCases,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var hasSavedKey by remember { mutableStateOf(false) }
    var savedKeyMask by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var isMessageError by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // 已保存的 Key 只以掩码形式回显，明文不可读出
        val view = useCases.loadConfig()
        if (view != null) {
            model = view.model
            baseUrl = view.baseUrl
            hasSavedKey = view.hasApiKey
            savedKeyMask = view.maskedApiKey
        }
    }

    fun report(result: Result<*>) {
        result
            .onSuccess {
                isMessageError = false
                message = if (it is String) it else "已保存"
            }
            .onFailure { e ->
                isMessageError = true
                message = e.message
            }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI 服务配置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; message = null },
                    label = { Text(if (hasSavedKey) "API Key（已保存：$savedKeyMask）" else "API Key") },
                    placeholder = { Text(if (hasSavedKey) "留空则保留已保存的 Key" else "sk-…") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showKey = !showKey }) {
                            Text(if (showKey) "隐藏" else "显示")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it; message = null },
                    label = { Text("模型标识") },
                    placeholder = { Text("deepseek-chat") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it; message = null },
                    label = { Text("Base URL") },
                    placeholder = { Text("https://api.deepseek.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "API Key 仅保存在本机加密存储中，不会写入日志。" +
                        "除当前消息外，是否携带本地事项由顶栏「授权」面板控制：未授权时请求只包含会话内消息。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                message?.let {
                    Text(
                        it,
                        color = if (isMessageError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                testing = true
                                val result = useCases.testConnection()
                                testing = false
                                report(result)
                            }
                        },
                        enabled = !testing,
                    ) { Text(if (testing) "测试中…" else "测试连接") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    val result = useCases.saveConfig(
                        AiConfigInput(apiKey = apiKey, model = model, baseUrl = baseUrl),
                    )
                    report(result)
                    result.onSuccess { view ->
                        hasSavedKey = view.hasApiKey
                        savedKeyMask = view.maskedApiKey
                        apiKey = ""
                    }
                }
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/**
 * 已授权范围徽标（#19）：已授权时显示类别列表；未授权时提示当前不携带本地数据。
 * 点击打开授权面板的入口由顶栏「授权」提供，徽标本身只做状态展示。
 */
@Composable
private fun ContextGrantBadge(grantedLabels: String?) {
    Surface(
        color = if (grantedLabels == null) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            if (grantedLabels == null) "未授权本地数据" else "已授权：$grantedLabels",
            style = MaterialTheme.typography.labelSmall,
            color = if (grantedLabels == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * 授权面板（#19，T28/#29 扩展记忆类别）：说明并勾选将随请求提供的上下文范围（用户故事 63/64）。
 * - 各类别独立勾选、整体「保存并应用」确认；空集合应用即撤销全部授权；
 * - 长期记忆默认关闭：记忆是独立隐私数据，仅在用户主动勾选后，确认保存的活动记忆
 *   （上限少量条数、原文不篡改）才随请求外发；
 * - 已授权时可「撤销全部授权」：撤销立即生效于下一次请求（已发出的在途请求无法撤回）；
 * - 回收站记录与未授权类别绝不外发；关闭类别后可随时在此重新授权。
 */
@Composable
private fun AiContextPanel(
    useCases: AiChatUseCases,
    onDismiss: () -> Unit,
) {
    val currentGrant = useCases.currentContextGrant()
    var selected by remember {
        mutableStateOf(
            currentGrant?.categories
                // 记忆默认关闭：未授权过时记忆类别不预选（其余类别维持原有默认全选）
                ?: (AiContextCategory.entries.toSet() - AiContextCategory.MEMORY),
        )
    }
    val isAuthorized = currentGrant != null

    fun toggle(category: AiContextCategory, checked: Boolean) {
        selected = if (checked) selected + category else selected - category
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("上下文授权") },
        text = {
            Column {
                Text(
                    "发送消息时，除你当前这条消息外，只会随请求提供以下勾选的内容。" +
                        "各类别可独立关闭；关闭的类别绝不外发，AI 会如实说明无法查询该类别。" +
                        "回收站中的记录不会提供。",
                    style = MaterialTheme.typography.bodySmall,
                )
                AiContextCategory.entries.forEach { category ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(
                            checked = category in selected,
                            onCheckedChange = { checked -> toggle(category, checked) },
                        )
                        Column {
                            Text(category.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                category.scopeSummary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    if (isAuthorized) {
                        "当前已授权：" + (currentGrant.categories.joinToString("、") { it.label }
                            .ifEmpty { "（无）" })
                    } else {
                        "当前未授权：AI 只能看到你的消息，无法查询本地事项。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isAuthorized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "授权为本次会话级（内存保存，退出应用后失效）；可随时撤销，撤销后请求恢复为不含本地数据。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                useCases.authorizeContext(selected) // 空集合视同撤销
                onDismiss()
            }) { Text(if (isAuthorized) "保存并应用" else "同意并授权") }
        },
        dismissButton = {
            if (isAuthorized) {
                TextButton(onClick = {
                    useCases.revokeContextAuthorization()
                    onDismiss()
                }) { Text("撤销全部授权") }
            } else {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/**
 * 操作提案卡片（#20/#21，#22 扩展修改与删除）：完整字段预览（截止/日程时间按创建时区换算的可读时间，
 * 跨日明确显示）+ 状态标记 +「确认创建/确认执行」「忽略」按钮与逐项「移除」。PENDING 才有操作按钮；
 * 修改条目逐字段展示「旧值 → 新值」，删除条目明确取消/删除语义（GLOSSARY：取消待办≠删除）；
 * 多候选修改/删除条目列出候选供点选（点选哪条就是哪条，未点选不可确认）；EXECUTED 显示含记录标识的
 * 中文回执；CONFIRMED 显示结果待核实；EXPIRED 不残留可执行操作。
 */
@Composable
private fun ProposalCard(
    proposal: AiProposal,
    zoneId: ZoneId,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onRemoveItem: (String) -> Unit,
    onSelectTarget: (itemId: String, recordId: String) -> Unit,
) {
    // T21/#22：含修改/删除条目时按钮文案与语义不同；有条目未点选目标时禁止确认（用例层同样拦截）
    val hasModifyDelete = proposal.items.any { it.op != AiProposalOp.CREATE }
    val pendingSelection = proposal.items.any { it.op != AiProposalOp.CREATE && it.targetRecordId == null }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "操作提案",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    proposalStatusLabel(proposal.status),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            proposal.items.forEach { item ->
                ProposalItemRow(
                    item = item,
                    zoneId = zoneId,
                    removable = proposal.status == AiProposalStatus.PENDING && !busy,
                    onRemove = { onRemoveItem(item.id) },
                    onSelectTarget = { recordId -> onSelectTarget(item.id, recordId) },
                )
            }
            proposal.resultMessage?.let { receipt ->
                Text(
                    receipt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (proposal.status == AiProposalStatus.PENDING) {
                if (pendingSelection) {
                    Text(
                        "还有条目未点选目标记录：请先在上方候选中点选，再确认执行。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = onConfirm, enabled = !busy && !pendingSelection) {
                        Text(
                            when {
                                busy -> if (hasModifyDelete) "执行中…" else "创建中…"
                                hasModifyDelete -> "确认执行"
                                else -> "确认创建"
                            },
                        )
                    }
                    OutlinedButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.padding(start = 8.dp)) {
                        Text("忽略")
                    }
                }
            }
        }
    }
}

/** 提案条目标签（#22）：按操作类型与目标类型区分，取消待办明确标注「取消」而非删除。 */
private fun proposalItemLabel(item: AiProposalItem): String = when (item.op) {
    AiProposalOp.CREATE -> "[${kindCn(item.kind)}]"
    AiProposalOp.MODIFY -> "[修改${kindCn(item.kind)}]"
    AiProposalOp.DELETE -> when {
        item.kind == AiProposalKind.TASK && item.deleteAction == AiProposalDeleteAction.CANCEL -> "[取消待办]"
        else -> "[删除${kindCn(item.kind)}]"
    }
}

/**
 * 提案条目字段预览（#22 扩展）：
 * - 新增：待办（截止/优先级/分类/备注）、便签（正文/标签/清单项）或日程（时间/地点/分类/备注/提醒），保留原话不改写；
 * - 修改：目标快照 + 逐字段「旧值 → 新值」；多候选未点选时列出候选按钮（点选哪条就是哪条）；
 * - 删除：目标快照 + 取消/删除语义说明（GLOSSARY：取消待办保留记录并停止提醒，不是删除）。
 */
@Composable
private fun ProposalItemRow(
    item: AiProposalItem,
    zoneId: ZoneId,
    removable: Boolean,
    onRemove: () -> Unit,
    onSelectTarget: (recordId: String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                proposalItemLabel(item),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                item.targetRecord?.title ?: item.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            if (removable) {
                TextButton(onClick = onRemove) { Text("移除") }
            }
        }
        // T21/#22：目标定位展示——唯一候选/已点选显示选定目标；多候选未点选列出候选供点选
        if (item.op != AiProposalOp.CREATE) {
            val target = item.targetRecord
            if (target != null) {
                Text(
                    "已选定目标：" + proposalTargetSummary(target, zoneId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            } else if (item.candidates.size > 1) {
                Text(
                    "找到 ${item.candidates.size} 条匹配记录，请点选要操作的目标：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                item.candidates.forEach { candidate ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "${candidate.title}（${proposalTargetSummary(candidate, zoneId)}）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onSelectTarget(candidate.recordId) }) { Text("选它") }
                    }
                }
            }
        }
        val details = buildList {
            when (item.op) {
                AiProposalOp.CREATE -> when (item.kind) {
                    AiProposalKind.TASK -> {
                        proposalDueText(item, zoneId)?.let { add("截止：$it") }
                        add("优先级：${priorityCn(item.priority)}")
                        if (item.category.isNotBlank()) add("分类：${item.category}")
                        if (item.note.isNotBlank()) add("备注：${item.note}")
                    }
                    AiProposalKind.NOTE -> {
                        if (item.content.isNotBlank()) add("正文：${item.content}")
                        if (item.tags.isNotEmpty()) add("标签：${item.tags.joinToString("、")}")
                        item.items.forEachIndexed { index, text -> add("清单${index + 1}：$text") }
                    }
                    // T20/#21 日程：时间按创建时区换算可读（跨日明确显示），提醒显式给出无提醒状态
                    AiProposalKind.SCHEDULE -> {
                        proposalScheduleRangeText(item, zoneId)?.let { add("时间：$it") }
                        add("时区：${zoneId.id}")
                        if (item.location.isNotBlank()) add("地点：${item.location}")
                        if (item.category.isNotBlank()) add("分类：${item.category}")
                        if (item.note.isNotBlank()) add("备注：${item.note}")
                        add("提醒：" + (proposalReminderText(item, zoneId) ?: "无"))
                    }
                    // T28/#29 记忆：类型 + 确认后保存为聊天确认来源的长期记忆（原文原样保存）
                    AiProposalKind.MEMORY -> {
                        add("类型：${memoryTypeCn(item.memoryType)}")
                        if (item.content.isNotBlank()) add("内容：${item.content}")
                    }
                }
                // T21/#22：修改逐字段「旧值 → 新值」；删除明确取消/删除语义与去向
                AiProposalOp.MODIFY -> addAll(proposalChangePreviewLines(item, zoneId))
                AiProposalOp.DELETE -> add(proposalDeleteActionText(item))
            }
        }
        details.forEach { line ->
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

private fun proposalStatusLabel(status: AiProposalStatus): String = when (status) {
    AiProposalStatus.PENDING -> "待确认"
    AiProposalStatus.CONFIRMED -> "已确认 · 结果待核实"
    AiProposalStatus.EXECUTED -> "已完成"
    AiProposalStatus.EXPIRED -> "已忽略或已失效"
}

private fun priorityCn(priority: String): String = when (priority) {
    "LOW" -> "低"
    "HIGH" -> "高"
    else -> "普通"
}

private fun formatMillis(millis: Long): String {
    val zoned = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    return zoned.format(aiTimeFmt)
}
