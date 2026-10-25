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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.zhideer.app.domain.usecase.AiChatUseCases
import com.zhideer.app.domain.usecase.AiConfigInput
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val aiTimeFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/**
 * AI 助手主界面（T17/#18），公开供根导航挂载：
 * - 会话列表 + 新建会话 + 重开 + 删除；
 * - 聊天视图：多轮流式回复、发送/停止、生成中可取消；
 * - 配置对话框：API Key（掩码输入、可切换显示）、模型标识、Base URL、「测试连接」。
 * 全部文案为中文；错误提示来自用例层（已是中文可诊断文案）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiScreen(useCases: AiChatUseCases) {
    val scope = rememberCoroutineScope()
    val sessions by useCases.listSessions().collectAsState(initial = emptyList())
    var chatSessionId by remember { mutableStateOf<String?>(null) } // null=列表页；""=新会话；其他=既有会话
    var currentSession by remember { mutableStateOf<AiChatSession?>(null) }
    var input by remember { mutableStateOf("") }
    var generating by remember { mutableStateOf(false) }
    var streamingText by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showConfig by remember { mutableStateOf(false) }

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
                            val result = useCases.sendStreaming(id, text) { delta -> streamingText += delta }
                            generating = false
                            result
                                .onSuccess { session ->
                                    streamingText = null
                                    chatSessionId = session.id
                                    currentSession = session
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

/** 聊天视图：消息流 + 流式生成中的部分回复 + 输入行（发送/停止）。 */
@Composable
private fun ChatView(
    session: AiChatSession?,
    streamingText: String?,
    generating: Boolean,
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
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
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(session?.messages ?: emptyList(), key = { it.content.hashCode().toLong() }) { message ->
                MessageBubble(role = message.role, content = message.content)
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
                        "当前不会把本地事项、长期记忆或其他历史上传给 AI，请求只包含本会话的消息。",
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

private fun formatMillis(millis: Long): String {
    val zoned = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    return zoned.format(aiTimeFmt)
}
