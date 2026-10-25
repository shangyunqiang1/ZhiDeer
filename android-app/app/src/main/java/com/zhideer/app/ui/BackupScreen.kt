package com.zhideer.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.zhideer.app.domain.usecase.BackupSummary
import com.zhideer.app.domain.usecase.BackupUseCases
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val backupTimeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private val backupFileNameFmt = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

/** 备份表名 → 中文标签（展示用，顺序与 BACKUP_TABLES 一致）。 */
private val backupTableLabels = mapOf(
    "tasks" to "待办",
    "sub_tasks" to "子任务",
    "calendar_entries" to "日程",
    "notes" to "便签",
    "note_list_items" to "便签清单项",
    "reminders" to "待办提醒",
    "task_occurrences" to "重复出现记录",
    "calendar_reminders" to "日程提醒",
    "occurrence_subtask_records" to "出现步骤记录",
    "memories" to "长期记忆",
)

/**
 * 备份主界面（T31/#32）：手动导出加密备份。
 * - 导出前说明备份所含内容与潜在隐私数据（个人事项与对话内容）；
 * - 要求独立备份口令：两次输入一致才可导出，明示忘记口令无法恢复；
 *   口令不会出现在文件名、日志或备份内容中；API Key 与本机配置绝不进入备份；
 * - 导出中禁用按钮；成功展示文件位置、格式版本、数据库版本、导出时间与各表计数摘要；
 * - 失败给出中文提示且不显示成功摘要（失败绝不报告成可用备份）。
 * MVP 导出位置为应用外部文件目录并展示绝对路径；SAF「另存为」由后续任务补齐。
 * 公开 composable，由 Root 挂导航。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(useCases: BackupUseCases, exportDirectory: File) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<BackupSummary?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val mismatch = confirm.isNotEmpty() && password != confirm
    val canExport = !exporting && password.isNotEmpty() && password == confirm

    Scaffold(
        topBar = { TopAppBar(title = { Text("备份") }) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // —— 导出前说明：备份内容与隐私提示（issue #32 验收） ——
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("备份将包含以下内容", style = MaterialTheme.typography.titleSmall)
                    Text("· 待办、日程、便签（含回收站记录）及其全部本地关系", style = MaterialTheme.typography.bodySmall)
                    Text("· AI 会话历史与长期记忆", style = MaterialTheme.typography.bodySmall)
                    Text("· 不包含 API Key 与本机配置，配置不会被导出", style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text(
                        "隐私提示：备份文件包含您的个人事项与对话内容，属于敏感数据，请妥善保管导出文件。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "备份文件默认加密保存。请设置独立备份口令（建议与其他密码不同）；" +
                            "忘记口令将无法恢复备份内容，口令不会出现在文件名或日志中。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // —— 独立备份口令（两次输入确认） ——
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("备份口令") },
                supportingText = { Text("独立口令，忘记将无法恢复备份；导出后请与备份文件分开保管") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showPassword = !showPassword }) {
                        Text(if (showPassword) "隐藏" else "显示")
                    }
                },
            )
            OutlinedTextField(
                value = confirm,
                onValueChange = { confirm = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("再次输入备份口令") },
                isError = mismatch,
                supportingText = { if (mismatch) Text("两次输入不一致") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            )

            Text(
                "备份将保存到：${exportDirectory.absolutePath}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = {
                    exporting = true
                    error = null
                    summary = null
                    scope.launch {
                        val target = File(
                            exportDirectory,
                            "zhideer-backup-" + LocalDateTime.now().format(backupFileNameFmt) + ".zdbak",
                        )
                        val result = useCases.exportAll(password.trim(), target)
                        exporting = false
                        result
                            .onSuccess { summary = it }
                            .onFailure { error = it.message ?: "备份导出失败：未知错误" }
                    }
                },
                enabled = canExport,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (exporting) "正在导出…" else "导出加密备份")
            }

            // —— 失败反馈：中文提示，不显示任何成功摘要 ——
            error?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // —— 成功摘要：文件位置 + 版本信息 + 各表计数清单（供恢复核对） ——
            summary?.let { result ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("备份完成", style = MaterialTheme.typography.titleSmall)
                        Text("文件：${result.file.absolutePath}", style = MaterialTheme.typography.bodySmall)
                        Text("大小：${result.fileSizeBytes} 字节", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "格式版本：${result.formatVersion}　数据库版本：${result.dbVersion}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "导出时间：${Instant.ofEpochMilli(result.exportedAt).atZone(ZoneId.systemDefault()).format(backupTimeFmt)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text("会话：${result.aiSessionCount} 个", style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        result.tableCounts.forEach { (table, count) ->
                            Text(
                                "${backupTableLabels[table] ?: table}：$count 条",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}
